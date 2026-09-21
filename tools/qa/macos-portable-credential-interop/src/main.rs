#![allow(dead_code)]

#[cfg(not(target_os = "macos"))]
compile_error!("This fixture intentionally runs only on macOS");

// Compile the production implementation itself. The supporting modules are the
// same files used by the native launcher, including its complete preflight.
#[path = "../../../../beta-lab/native-launcher/src/atomic_store.rs"]
mod atomic_store;
#[path = "../../../../native-verifier/src/portable_credential_protection.rs"]
mod portable_credential_protection;
#[path = "../../../../native-verifier/src/user_profile.rs"]
mod user_profile;

use anyhow::{Context, Result, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::{RngCore, rngs::OsRng};
use std::ffi::{CString, c_char, c_void};
use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

const SERVICE: &[u8] = b"dev.c4lam.celeste.normal";
const ACCOUNT: &[u8] = b"a39fbbf8e5c344edb39ee82d";

#[link(name = "Security", kind = "framework")]
unsafe extern "C" {
    fn SecTrustedApplicationCreateFromPath(
        path: *const c_char,
        application: *mut *mut c_void,
    ) -> i32;
    fn SecAccessCreate(
        descriptor: *const c_void,
        trusted_list: *const c_void,
        access: *mut *mut c_void,
    ) -> i32;
    fn SecKeychainItemCreateFromContent(
        item_class: u32,
        attributes: *mut SecKeychainAttributeList,
        length: u32,
        data: *const c_void,
        keychain: *mut c_void,
        initial_access: *mut c_void,
        item: *mut *mut c_void,
    ) -> i32;
}

#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFStringCreateWithCString(
        allocator: *const c_void,
        text: *const c_char,
        encoding: u32,
    ) -> *mut c_void;
    fn CFArrayCreate(
        allocator: *const c_void,
        values: *const *const c_void,
        count: isize,
        callbacks: *const c_void,
    ) -> *mut c_void;
    fn CFRelease(value: *const c_void);
}

const UTF8: u32 = 0x0800_0100;
const GENERIC_PASSWORD: u32 = u32::from_be_bytes(*b"genp");
const ACCOUNT_ATTRIBUTE: u32 = u32::from_be_bytes(*b"acct");
const SERVICE_ATTRIBUTE: u32 = u32::from_be_bytes(*b"svce");

#[repr(C)]
struct SecKeychainAttribute {
    tag: u32,
    length: u32,
    data: *mut c_void,
}

#[repr(C)]
struct SecKeychainAttributeList {
    count: u32,
    attributes: *mut SecKeychainAttribute,
}

struct OwnedCf(*mut c_void);

impl OwnedCf {
    fn required(value: *mut c_void, operation: &'static str) -> Result<Self> {
        if value.is_null() {
            bail!("{operation} returned no object");
        }
        Ok(Self(value))
    }
}

impl Drop for OwnedCf {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { CFRelease(self.0) };
        }
    }
}

fn status(operation: &'static str, value: i32) -> Result<()> {
    if value == 0 {
        Ok(())
    } else {
        bail!("{operation} failed with OSStatus {value}")
    }
}

fn synthetic_access() -> Result<OwnedCf> {
    let writer_path =
        std::env::current_exe().context("Could not resolve the fixture executable")?;
    let writer_path = CString::new(writer_path.as_os_str().as_bytes())
        .context("Fixture executable path contains a NUL byte")?;
    let security_path = CString::new("/usr/bin/security").expect("fixed path has no NUL");
    let mut writer = std::ptr::null_mut();
    let mut security = std::ptr::null_mut();
    status("Fixture trusted-application creation", unsafe {
        SecTrustedApplicationCreateFromPath(writer_path.as_ptr(), &mut writer)
    })?;
    let writer = OwnedCf::required(writer, "Fixture trusted-application creation")?;
    status("System reader trusted-application creation", unsafe {
        SecTrustedApplicationCreateFromPath(security_path.as_ptr(), &mut security)
    })?;
    let security = OwnedCf::required(security, "System reader trusted-application creation")?;

    let description = CString::new("Celeste CI synthetic credential interoperability")
        .expect("fixed description has no NUL");
    let description = OwnedCf::required(
        unsafe { CFStringCreateWithCString(std::ptr::null(), description.as_ptr(), UTF8) },
        "Synthetic access description creation",
    )?;
    let trusted_values = [writer.0.cast_const(), security.0.cast_const()];
    // A null callbacks table means the short-lived array does not own the two
    // application references; they stay alive until SecAccessCreate returns.
    let trusted = OwnedCf::required(
        unsafe {
            CFArrayCreate(
                std::ptr::null(),
                trusted_values.as_ptr(),
                trusted_values.len() as isize,
                std::ptr::null(),
            )
        },
        "Synthetic trusted-application list creation",
    )?;
    let mut access = std::ptr::null_mut();
    status("Synthetic access-list creation", unsafe {
        SecAccessCreate(description.0, trusted.0, &mut access)
    })?;
    OwnedCf::required(access, "Synthetic access-list creation")
}

struct SecretKey([u8; 32]);

impl Drop for SecretKey {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

struct SecretBytes(Vec<u8>);

impl Drop for SecretBytes {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

/// Create this disposable synthetic item with its narrow ACL up front. This
/// avoids changing an owner ACL after creation, which can itself request user
/// authorization. Production does not call this fixture-only function.
fn create_authorized_synthetic_item() -> Result<()> {
    let access = synthetic_access()?;
    let mut key = SecretKey([0_u8; 32]);
    OsRng
        .try_fill_bytes(&mut key.0)
        .context("System randomness is unavailable for the synthetic Keychain item")?;
    let encoded = SecretBytes(URL_SAFE_NO_PAD.encode(key.0).into_bytes());
    let mut attributes = [
        SecKeychainAttribute {
            tag: SERVICE_ATTRIBUTE,
            length: SERVICE.len() as u32,
            data: SERVICE.as_ptr().cast_mut().cast(),
        },
        SecKeychainAttribute {
            tag: ACCOUNT_ATTRIBUTE,
            length: ACCOUNT.len() as u32,
            data: ACCOUNT.as_ptr().cast_mut().cast(),
        },
    ];
    let mut attribute_list = SecKeychainAttributeList {
        count: attributes.len() as u32,
        attributes: attributes.as_mut_ptr(),
    };
    let mut item = std::ptr::null_mut();
    status("Authorized synthetic Keychain item creation", unsafe {
        SecKeychainItemCreateFromContent(
            GENERIC_PASSWORD,
            &mut attribute_list,
            encoded.0.len() as u32,
            encoded.0.as_ptr().cast(),
            std::ptr::null_mut(),
            access.0,
            &mut item,
        )
    })?;
    drop(OwnedCf::required(
        item,
        "Authorized synthetic Keychain item creation",
    )?);
    Ok(())
}

fn write_private(path: &Path, value: &[u8]) -> Result<()> {
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .with_context(|| format!("Could not create fixture output {}", path.display()))?;
    output
        .write_all(value)
        .with_context(|| format!("Could not write fixture output {}", path.display()))?;
    output
        .sync_all()
        .with_context(|| format!("Could not persist fixture output {}", path.display()))
}

fn write_payload(mut arguments: impl Iterator<Item = std::ffi::OsString>) -> Result<()> {
    let plain_path = arguments
        .next()
        .map(std::path::PathBuf::from)
        .context("Expected a plaintext fixture output path")?;
    let envelope_path = arguments
        .next()
        .map(std::path::PathBuf::from)
        .context("Expected a protected-envelope output path")?;
    if arguments.next().is_some() {
        bail!("Unexpected fixture argument");
    }

    let mut plain = [0_u8; 257];
    OsRng
        .try_fill_bytes(&mut plain)
        .context("System randomness is unavailable for the synthetic fixture")?;
    let mut envelope = portable_credential_protection::protect(&plain)?;
    if !envelope.starts_with(b"CSP2") {
        bail!("Production writer returned an unexpected envelope");
    }

    let result = (|| -> Result<()> {
        write_private(&plain_path, &plain)?;
        write_private(&envelope_path, &envelope)?;
        Ok(())
    })();
    plain.fill(0);
    envelope.fill(0);
    result?;
    println!("PASS native Security.framework writer produced a synthetic CSP2 envelope");
    Ok(())
}

fn write_authorized_fixture(mut arguments: impl Iterator<Item = std::ffi::OsString>) -> Result<()> {
    let plain_path = arguments
        .next()
        .context("Expected a plaintext fixture output path")?;
    let envelope_path = arguments
        .next()
        .context("Expected a protected-envelope output path")?;
    let storage_path = arguments
        .next()
        .map(std::path::PathBuf::from)
        .context("Expected a native-preflight storage path")?;
    if arguments.next().is_some() {
        bail!("Unexpected fixture argument");
    }
    create_authorized_synthetic_item()?;
    portable_credential_protection::preflight(&storage_path)?;
    write_payload([plain_path, envelope_path].into_iter())?;
    println!("PASS production native preflight with explicit synthetic reader authorization");
    Ok(())
}

fn main() -> Result<()> {
    let mut arguments = std::env::args_os().skip(1);
    let mode = arguments
        .next()
        .and_then(|value| value.into_string().ok())
        .context("Expected write or authorize mode")?;
    match mode.as_str() {
        "write-default" => write_payload(arguments),
        "write-authorized" => write_authorized_fixture(arguments),
        _ => bail!("Expected write-default or write-authorized mode"),
    }
}
