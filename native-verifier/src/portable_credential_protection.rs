//! Current-user credential protection for the standard macOS/Linux launcher.
//! A random AES key lives in Keychain or Secret Service, never beside the JAR.

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use anyhow::{Context, Result, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::{RngCore, rngs::OsRng};
use std::fs::{self, OpenOptions};
use std::io;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

const ENVELOPE: &[u8; 4] = b"CSP2";
const AAD: &[u8] = b"Celeste normal credential v2";
const SERVICE: &str = "dev.c4lam.celeste.normal";
const ACCOUNT: &str = "a39fbbf8e5c344edb39ee82d";
const MAX_PLAINTEXT_BYTES: usize = 1024 * 1024;
const NONCE_BYTES: usize = 12;
const TAG_BYTES: usize = 16;

/// A protected-store error has no key, token, or credential in its message.
#[derive(Debug)]
pub(crate) struct ProtectionError {
    operation: &'static str,
    detail: &'static str,
}

impl std::fmt::Display for ProtectionError {
    fn fmt(&self, output: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            output,
            "Could not {} this account's Celeste credential: {}",
            self.operation, self.detail
        )
    }
}

impl std::error::Error for ProtectionError {}

fn protection_error(operation: &'static str, detail: &'static str) -> anyhow::Error {
    ProtectionError { operation, detail }.into()
}

pub(crate) fn protect(value: &[u8]) -> Result<Vec<u8>> {
    if value.is_empty() || value.len() > MAX_PLAINTEXT_BYTES {
        bail!("Credential data is empty or exceeds the supported size bounds");
    }
    let key = current_key(true)?;
    let mut nonce = [0u8; NONCE_BYTES];
    OsRng
        .try_fill_bytes(&mut nonce)
        .context("System randomness is unavailable for credential protection")?;
    seal_with_key(value, &key, &nonce)
}

fn seal_with_key(value: &[u8], key: &[u8; 32], nonce: &[u8; NONCE_BYTES]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| protection_error("protect", "invalid account key"))?;
    let sealed = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: value,
                aad: AAD,
            },
        )
        .map_err(|_| protection_error("protect", "encryption failed"))?;
    let mut result = Vec::with_capacity(ENVELOPE.len() + NONCE_BYTES + sealed.len());
    result.extend_from_slice(ENVELOPE);
    result.extend_from_slice(nonce);
    result.extend_from_slice(&sealed);
    Ok(result)
}

pub(crate) fn unprotect(value: &[u8]) -> Result<Vec<u8>> {
    if value.len() < ENVELOPE.len() + NONCE_BYTES + TAG_BYTES + 1
        || value.len() > ENVELOPE.len() + NONCE_BYTES + TAG_BYTES + MAX_PLAINTEXT_BYTES
        || !value.starts_with(ENVELOPE)
    {
        return Err(protection_error("open", "invalid protected envelope"));
    }
    // A missing OS key must never be silently replaced while opening existing data.
    let key = current_key(false)?;
    open_with_key(value, &key)
}

fn open_with_key(value: &[u8], key: &[u8; 32]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| protection_error("open", "invalid account key"))?;
    let offset = ENVELOPE.len() + NONCE_BYTES;
    cipher
        .decrypt(
            Nonce::from_slice(&value[ENVELOPE.len()..offset]),
            Payload {
                msg: &value[offset..],
                aad: AAD,
            },
        )
        .map_err(|_| {
            protection_error(
                "open",
                "the activation belongs to another account or was damaged",
            )
        })
}

fn current_key(create: bool) -> Result<[u8; 32]> {
    if let Some(key) = keyring::load()? {
        return Ok(key);
    }
    if !create {
        return Err(protection_error(
            "open",
            "the account key is missing from the system password store",
        ));
    }
    let mut new_key = [0u8; 32];
    OsRng
        .try_fill_bytes(&mut new_key)
        .context("System randomness is unavailable for the account key")?;
    keyring::store(&new_key)?;
    // Use the value the OS retained, including if another launcher created it.
    keyring::load()?.ok_or_else(|| {
        protection_error(
            "protect",
            "the system password store did not retain the account key",
        )
    })
}

#[cfg(target_os = "macos")]
mod keyring {
    use super::*;
    use std::ffi::c_void;
    use std::ptr;

    const ITEM_NOT_FOUND: i32 = -25300;
    const DUPLICATE_ITEM: i32 = -25299;

    #[link(name = "Security", kind = "framework")]
    unsafe extern "C" {
        fn SecKeychainFindGenericPassword(
            keychain: *mut c_void,
            service_len: u32,
            service: *const u8,
            account_len: u32,
            account: *const u8,
            password_len: *mut u32,
            password: *mut *mut c_void,
            item: *mut *mut c_void,
        ) -> i32;
        fn SecKeychainAddGenericPassword(
            keychain: *mut c_void,
            service_len: u32,
            service: *const u8,
            account_len: u32,
            account: *const u8,
            password_len: u32,
            password: *const u8,
            item: *mut *mut c_void,
        ) -> i32;
        fn SecKeychainItemFreeContent(attributes: *mut c_void, data: *mut c_void) -> i32;
    }

    pub(super) fn load() -> Result<Option<[u8; 32]>> {
        let mut length = 0u32;
        let mut data = ptr::null_mut();
        let status = unsafe {
            SecKeychainFindGenericPassword(
                ptr::null_mut(),
                SERVICE.len() as u32,
                SERVICE.as_ptr(),
                ACCOUNT.len() as u32,
                ACCOUNT.as_ptr(),
                &mut length,
                &mut data,
                ptr::null_mut(),
            )
        };
        if status == ITEM_NOT_FOUND {
            return Ok(None);
        }
        if status != 0 {
            return Err(protection_error(
                "read",
                "macOS Keychain is unavailable or locked",
            ));
        }
        if data.is_null() || length != 43 {
            if !data.is_null() {
                unsafe {
                    SecKeychainItemFreeContent(ptr::null_mut(), data);
                }
            }
            return Err(protection_error(
                "read",
                "macOS Keychain returned an invalid account key",
            ));
        }
        let encoded = unsafe {
            let copy = std::slice::from_raw_parts(data.cast::<u8>(), length as usize).to_vec();
            SecKeychainItemFreeContent(ptr::null_mut(), data);
            copy
        };
        let bytes = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| {
            protection_error("read", "macOS Keychain returned an invalid account key")
        })?;
        let key: [u8; 32] = bytes.try_into().map_err(|_| {
            protection_error("read", "macOS Keychain returned an invalid account key")
        })?;
        Ok(Some(key))
    }

    pub(super) fn store(key: &[u8; 32]) -> Result<()> {
        let encoded = URL_SAFE_NO_PAD.encode(key);
        let status = unsafe {
            SecKeychainAddGenericPassword(
                ptr::null_mut(),
                SERVICE.len() as u32,
                SERVICE.as_ptr(),
                ACCOUNT.len() as u32,
                ACCOUNT.as_ptr(),
                encoded.len() as u32,
                encoded.as_ptr(),
                ptr::null_mut(),
            )
        };
        if status == 0 || status == DUPLICATE_ITEM {
            Ok(())
        } else {
            Err(protection_error(
                "save",
                "macOS Keychain is unavailable or locked",
            ))
        }
    }
}

#[cfg(target_os = "linux")]
mod keyring {
    use super::*;
    use std::io::Write;
    use std::process::{Command, Stdio};

    fn command() -> Command {
        Command::new("secret-tool")
    }

    pub(super) fn load() -> Result<Option<[u8; 32]>> {
        let output = command()
            .args(["lookup", "service", SERVICE, "account", ACCOUNT])
            .stdin(Stdio::null())
            .output()
            .map_err(|_| {
                protection_error(
                    "read",
                    "install secret-tool and unlock this desktop's Secret Service",
                )
            })?;
        if !output.status.success() {
            // secret-tool uses status 1 and no stderr for a missing item. A
            // backend error also uses status 1, but prints a diagnostic.
            if output.status.code() == Some(1)
                && output.stderr.is_empty()
                && output.stdout.is_empty()
            {
                return Ok(None);
            }
            return Err(protection_error(
                "read",
                "the desktop Secret Service is unavailable or locked",
            ));
        }
        if output.stdout.is_empty() {
            return Ok(None);
        }
        // With piped stdout secret-tool does not append a terminal newline.
        let bytes = URL_SAFE_NO_PAD.decode(&output.stdout).map_err(|_| {
            protection_error(
                "read",
                "the desktop Secret Service returned an invalid account key",
            )
        })?;
        let key: [u8; 32] = bytes.try_into().map_err(|_| {
            protection_error(
                "read",
                "the desktop Secret Service returned an invalid account key",
            )
        })?;
        Ok(Some(key))
    }

    pub(super) fn store(key: &[u8; 32]) -> Result<()> {
        let mut child = command()
            .args([
                "store",
                "--label=Celeste credential key",
                "service",
                SERVICE,
                "account",
                ACCOUNT,
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| {
                protection_error(
                    "save",
                    "install secret-tool and unlock this desktop's Secret Service",
                )
            })?;
        // secret-tool stores stdin bytes verbatim until EOF. No newline is sent.
        let encoded = URL_SAFE_NO_PAD.encode(key);
        child
            .stdin
            .take()
            .ok_or_else(|| protection_error("save", "could not open the password-store input"))?
            .write_all(encoded.as_bytes())
            .map_err(|_| {
                protection_error("save", "could not send the account key to Secret Service")
            })?;
        if child
            .wait()
            .map_err(|_| {
                protection_error(
                    "save",
                    "Secret Service did not finish saving the account key",
                )
            })?
            .success()
        {
            Ok(())
        } else {
            Err(protection_error(
                "save",
                "the desktop Secret Service is unavailable or locked",
            ))
        }
    }
}

struct ProbeFile(Option<PathBuf>);

impl ProbeFile {
    fn reserve(directory: &Path) -> Result<Self> {
        for _ in 0..32 {
            let mut random = [0u8; 16];
            OsRng
                .try_fill_bytes(&mut random)
                .context("System randomness is unavailable for the storage check")?;
            let suffix: String = random.iter().map(|byte| format!("{byte:02x}")).collect();
            let path = directory.join(format!(
                ".celeste-storage-check-{}-{suffix}.probe",
                std::process::id()
            ));
            match OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&path)
            {
                Ok(file) => {
                    drop(file);
                    return Ok(Self(Some(path)));
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => {
                    return Err(error).context("Could not reserve an activation storage probe");
                }
            }
        }
        bail!("Could not reserve a unique activation storage probe")
    }

    fn path(&self) -> &Path {
        self.0.as_deref().expect("owned probe before cleanup")
    }

    fn remove(&mut self) -> io::Result<()> {
        if let Some(path) = &self.0 {
            match fs::remove_file(path) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
            self.0 = None;
        }
        Ok(())
    }
}

impl Drop for ProbeFile {
    fn drop(&mut self) {
        let _ = self.remove();
    }
}

pub(crate) fn preflight(directory: &Path) -> Result<()> {
    if !directory.is_absolute() {
        bail!("Activation storage check requires an absolute account directory");
    }
    let mut sample = [0u8; 64];
    OsRng
        .try_fill_bytes(&mut sample)
        .context("System randomness is unavailable for the protection check")?;
    let protected = protect(&sample)?;
    if unprotect(&protected)? != sample {
        bail!("Credential protection did not pass its round-trip check");
    }
    crate::user_profile::ensure_private_activation_dir(directory)
        .context("Could not prepare private activation storage; enrollment was not started")?;
    let mut probe = ProbeFile::reserve(directory)?;
    let result = (|| -> Result<()> {
        crate::atomic_store::atomic_write(probe.path(), &protected).context(
            "Could not persist the activation storage check; enrollment was not started",
        )?;
        let stored =
            fs::read(probe.path()).context("Could not read the activation storage check")?;
        if stored != protected || unprotect(&stored)? != sample {
            bail!("The activation storage check did not preserve its protected data");
        }
        Ok(())
    })();
    let cleanup = probe.remove();
    match (result, cleanup) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(error), Ok(())) => Err(error),
        (Ok(()), Err(error)) => {
            Err(error).context("Could not remove the temporary activation storage check")
        }
        (Err(error), Err(cleanup_error)) => Err(error.context(format!(
            "The temporary activation storage check also could not be removed: {cleanup_error}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_compatible_envelope_fixture_and_tampering() {
        let mut key = [0u8; 32];
        let mut nonce = [0u8; NONCE_BYTES];
        for (index, byte) in key.iter_mut().enumerate() {
            *byte = index as u8;
        }
        for (index, byte) in nonce.iter_mut().enumerate() {
            *byte = (index + 32) as u8;
        }
        let plain = b"nonsecret portable credential fixture";
        let sealed = seal_with_key(plain, &key, &nonce).unwrap();
        let expected = "43535032202122232425262728292a2bbc55c80309fb686b6e5c32a1b36c959bbc2cccfff5e5078b0282057325aa38600dfa9076eb9ec02829d82e3ff1fd07a85741d550c2";
        let actual: String = sealed.iter().map(|byte| format!("{byte:02x}")).collect();
        assert_eq!(actual, expected);
        assert_eq!(open_with_key(&sealed, &key).unwrap(), plain);
        let mut damaged = sealed;
        *damaged.last_mut().unwrap() ^= 1;
        assert!(open_with_key(&damaged, &key).is_err());
        assert!(unprotect(b"invalid envelope").is_err());
    }

    #[test]
    fn relative_preflight_path_is_rejected_without_creating_a_directory() {
        assert!(preflight(Path::new("relative-storage-probe")).is_err());
    }
}
