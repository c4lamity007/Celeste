#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
mod atomic_store;
pub(crate) use atomic_store::atomic_write;
mod bindings;
#[cfg(windows)]
mod credential_protection;
#[cfg(any(target_os = "macos", target_os = "linux"))]
#[path = "portable_credential_protection.rs"]
mod credential_protection;
#[path = "../../shared-auth/dpop.rs"]
mod dpop;
mod folder_delivery;
#[cfg(windows)]
mod launchers;
#[cfg(any(target_os = "macos", target_os = "linux", test))]
mod portable_hardware;
mod release;
mod ui;
mod user_profile;
#[cfg(windows)]
mod windows_profile;
pub(crate) const SETUP_BUILD: &str = "20260920.2";
use crate::ui::UiEvent;
use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ed25519_dalek::pkcs8::DecodePrivateKey;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
#[cfg(windows)]
use winreg::RegKey;
#[cfg(windows)]
use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_64KEY};
#[cfg(windows)]
use wmi::{COMLibrary, WMIConnection};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};
const STORE_MAGIC: &[u8; 4] = b"CST1";
const INSTALLER_UUID: &str = "00000000-0000-0000-0000-000000000000";
const INSTALLER_NAME: &str = "CelesteInstaller";
const TOKEN_INFO: &[u8] = b"celeste-install-token-v1";
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct InstallCredential {
    device_id: String,
    uid: u64,
    signing_private: String,
    encryption_private: String,
    token_seed: String,
    certificate: String,
    game_uuid: String,
    game_name: String,
}
#[derive(Debug, Clone, Serialize)]
struct HardwareProfile {
    system: String,
    board: String,
    processor: String,
    monitors: Vec<String>,
}
#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ComputerSystem {
    manufacturer: Option<String>,
    model: Option<String>,
}
#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ComputerProduct {
    uuid: Option<String>,
}
#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Bios {
    serial_number: Option<String>,
}
#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct BaseBoard {
    manufacturer: Option<String>,
    product: Option<String>,
    version: Option<String>,
    serial_number: Option<String>,
}
#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Processor {
    processor_id: Option<String>,
    manufacturer: Option<String>,
    name: Option<String>,
}
fn main() {
    if env::args_os()
        .skip(1)
        .any(|argument| argument == native_protection::text!("--check-hardware"))
    {
        match collect_hardware() {
            Ok(hardware) => {
                println!(
                    "{}",
                    serde_json::to_string(&hardware)
                        .expect(native_protection::text!("hardware json"))
                );
            }
            Err(error) => {
                eprintln!("Hardware check failed: {error:#}");
                std::process::exit(1);
            }
        }
        return;
    }
    if let Err(error) = ui::launch_gui() {
        eprintln!("{error:#}");
        std::process::exit(1);
    }
}
pub(crate) fn verify_and_download(
    credential: InstallCredential,
    folder: PathBuf,
    tx: &mpsc::Sender<UiEvent>,
) -> Result<PathBuf> {
    let send = |message: &str| {
        let _ = tx.send(UiEvent::Status(message.to_owned()));
    };
    let folder = folder_delivery::validate_folder(&folder)?;
    send(native_protection::text!("Authenticating"));
    let hardware = collect_hardware()?;
    let session = authenticate_installer(&credential, &hardware, None)?;
    persist_user_binding(&credential)?;
    send(native_protection::text!("Downloading"));
    let loader = get_bytes(
        required_text(&session, native_protection::text!("/bootstrap/url"))?,
        required_text(&session, native_protection::text!("/grant"))?,
        &signing_key(&credential.signing_private)?,
    )?;
    let expected = required_text(&session, native_protection::text!("/bootstrap/sha256"))?
        .to_ascii_lowercase();
    require_digest(&loader, &expected)?;
    send(native_protection::text!("Saving Celeste.jar"));
    folder_delivery::save_mod(&folder, &loader)
}
fn license_looks_valid(license: &str) -> bool {
    let len = license.len();
    (20..=96).contains(&len)
}
pub(crate) fn verify_activation(license: Option<String>) -> Result<Option<InstallCredential>> {
    let license = license.as_deref().map(str::trim);
    if license.is_some_and(|key| !license_looks_valid(key)) {
        bail!(
            "{}",
            native_protection::text!("Enter a valid Celeste license key (20–96 characters)")
        );
    }
    let candidates = if license.is_some() {
        credential_candidates(Path::new(""))
    } else if let Some(active) = user_binding_dir()
        .map(|dir| dir.join(native_protection::text!("celeste-active.bin")))
        .filter(|path| path.is_file())
    {
        vec![bindings::Candidate {
            path: active,
            selected_instance: true,
        }]
    } else {
        credential_candidates(Path::new(""))
    };
    if candidates.is_empty() && license.is_none() {
        return Ok(None);
    }
    let hardware = collect_hardware()?;
    let saved = bindings::reuse_for_key(candidates, license.is_some(), load_credential, |saved| {
        authenticate_installer(saved, &hardware, license)
    });
    finish_activation(
        saved,
        license.is_some(),
        || {
            enroll(
                license.expect(native_protection::text!(
                    "enrollment requires an explicit key"
                )),
                &hardware,
            )
        },
        persist_user_binding,
        |created| authenticate_installer(created, &hardware, license),
    )
}
fn finish_activation<E, P, A>(
    saved: Result<Option<(InstallCredential, Value)>>,
    explicit_key: bool,
    mut enroll_new: E,
    mut persist: P,
    mut authenticate: A,
) -> Result<Option<InstallCredential>>
where
    E: FnMut() -> Result<InstallCredential>,
    P: FnMut(&InstallCredential) -> Result<()>,
    A: FnMut(&InstallCredential) -> Result<Value>,
{
    let credential = match saved {
        Ok(Some((credential, _))) => credential,
        Ok(None) if !explicit_key => return Ok(None),
        Ok(None) => {
            let created = enroll_new()?;
            persist(&created)?;
            authenticate(&created)?;
            created
        }
        Err(error) if explicit_key && (is_unauthorized(&error) || is_product_denied(&error)) => {
            let created = enroll_new()?;
            persist(&created)?;
            authenticate(&created)?;
            created
        }
        Err(error) => return Err(error),
    };
    persist(&credential)?;
    Ok(Some(credential))
}
fn user_binding_dir() -> Option<PathBuf> {
    required_user_binding_dir().ok()
}
fn credential_candidates(game_dir: &Path) -> Vec<bindings::Candidate> {
    let mut candidates = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut add = |path: PathBuf, selected_instance: bool| {
        if path.is_file() && seen.insert(path.clone()) {
            candidates.push(bindings::Candidate {
                path,
                selected_instance,
            });
        }
    };
    if !game_dir.as_os_str().is_empty() {
        add(credential_path(game_dir), true);
        add(runtime_credential_path(game_dir), true);
    }
    if let Some(cache) = user_binding_dir() {
        add(
            cache.join(native_protection::text!("celeste-active.bin")),
            false,
        );
        let mut files = fs::read_dir(cache)
            .into_iter()
            .flatten()
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| {
                        name.starts_with(native_protection::text!("uid-"))
                            && name.ends_with(native_protection::text!(".bin"))
                    })
            })
            .collect::<Vec<_>>();
        files.sort();
        for path in files {
            add(path, false);
        }
    }
    #[cfg(windows)]
    for launcher in launchers::detect_installed() {
        for instance in launchers::list_instances(&launcher) {
            add(credential_path(&instance.game_dir), false);
            add(runtime_credential_path(&instance.game_dir), false);
            if let Some(legacy_root) = launchers::legacy_binding_root(&launcher, &instance) {
                let selected = instance.game_dir == game_dir
                    && !credential_path(game_dir).is_file()
                    && !runtime_credential_path(game_dir).is_file();
                add(credential_path(&legacy_root), selected);
                add(runtime_credential_path(&legacy_root), selected);
            }
        }
    }
    candidates
}
fn required_user_binding_dir() -> Result<PathBuf> {
    Ok(user_profile::current_local_app_data()?
        .join(native_protection::text!("Celeste"))
        .join(native_protection::text!("auth"))
        .join(release::launcher_channel_id()))
}
fn persist_user_binding(credential: &InstallCredential) -> Result<()> {
    let cache = required_user_binding_dir()?;
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    user_profile::ensure_private_activation_dir(&cache)?;
    save_credential(
        &cache.join(format!("uid-{}.bin", credential.uid)),
        credential,
    )
    .context(native_protection::text!(
        "Could not save this account's Celeste activation"
    ))?;
    save_credential(
        &cache.join(native_protection::text!("celeste-active.bin")),
        credential,
    )
    .context(native_protection::text!(
        "Could not save the selected Celeste activation"
    ))
}
fn credential_path(game_root: &Path) -> PathBuf {
    game_root
        .join(native_protection::text!("config"))
        .join(native_protection::text!("celeste"))
        .join(native_protection::text!("auth"))
        .join(native_protection::text!("installer.bin"))
}
fn runtime_credential_path(game_root: &Path) -> PathBuf {
    game_root
        .join(native_protection::text!("config"))
        .join(native_protection::text!("celeste"))
        .join(native_protection::text!("auth"))
        .join(native_protection::text!("device.bin"))
}
fn is_unauthorized(error: &anyhow::Error) -> bool {
    error
        .downcast_ref::<HttpFailure>()
        .is_some_and(|failure| failure.status == 401)
}
#[derive(Debug)]
struct HttpFailure {
    status: u16,
    path: String,
    detail: String,
}
impl std::fmt::Display for HttpFailure {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "Authentication server rejected the request ({}) at {}: {}",
            self.status, self.path, self.detail
        )
    }
}
impl std::error::Error for HttpFailure {}
fn http_failure(status: u16, path: &str, detail: &str) -> anyhow::Error {
    HttpFailure {
        status,
        path: path.to_owned(),
        detail: detail.to_owned(),
    }
    .into()
}
#[cfg(windows)]
fn collect_hardware() -> Result<HardwareProfile> {
    let com = COMLibrary::new().context(native_protection::text!(
        "Windows hardware services are unavailable"
    ))?;
    let wmi = WMIConnection::new(com).context(native_protection::text!(
        "Could not connect to Windows hardware services"
    ))?;
    let systems: Vec<ComputerSystem> = wmi.raw_query(native_protection::text!(
        "SELECT Manufacturer,Model FROM Win32_ComputerSystem"
    ))?;
    let products: Vec<ComputerProduct> = wmi.raw_query(native_protection::text!(
        "SELECT UUID FROM Win32_ComputerSystemProduct"
    ))?;
    let bios: Vec<Bios> = wmi.raw_query(native_protection::text!(
        "SELECT SerialNumber FROM Win32_BIOS"
    ))?;
    let boards: Vec<BaseBoard> = wmi.raw_query(native_protection::text!(
        "SELECT Manufacturer,Product,Version,SerialNumber FROM Win32_BaseBoard"
    ))?;
    let processors: Vec<Processor> = wmi.raw_query(native_protection::text!(
        "SELECT ProcessorId,Manufacturer,Name FROM Win32_Processor"
    ))?;
    let system = systems.first();
    let product = products.first();
    let firmware = bios.first();
    let board = boards.first();
    let processor = processors.first();
    let system_value = canonical(&[
        system.and_then(|v| v.manufacturer.as_deref()),
        system.and_then(|v| v.model.as_deref()),
        firmware.and_then(|v| v.serial_number.as_deref()),
        product.and_then(|v| v.uuid.as_deref()),
    ]);
    let board_value = canonical(&[
        board.and_then(|v| v.manufacturer.as_deref()),
        board.and_then(|v| v.product.as_deref()),
        board.and_then(|v| v.version.as_deref()),
        board.and_then(|v| v.serial_number.as_deref()),
    ]);
    let processor_value = canonical(&[
        processor.and_then(|v| v.processor_id.as_deref()),
        processor.and_then(|v| v.manufacturer.as_deref()),
        processor.and_then(|v| v.name.as_deref()),
    ]);
    let mut monitors = monitor_edids()
        .into_iter()
        .map(|value| hex_sha256(&value))
        .collect::<Vec<_>>();
    monitors.sort();
    monitors.dedup();
    if monitors.is_empty() {
        monitors.push(hex_sha256(system_value.as_bytes()));
    }
    Ok(HardwareProfile {
        system: hex_sha256(system_value.as_bytes()),
        board: hex_sha256(board_value.as_bytes()),
        processor: hex_sha256(processor_value.as_bytes()),
        monitors,
    })
}
#[cfg(any(target_os = "macos", target_os = "linux"))]
fn collect_hardware() -> Result<HardwareProfile> {
    portable_hardware::collect()
}
fn canonical(values: &[Option<&str>]) -> String {
    values
        .iter()
        .map(|value| {
            let normalized = value.unwrap_or("").trim().to_lowercase();
            if normalized.is_empty() {
                native_protection::text!("unknown").to_owned()
            } else {
                normalized
            }
        })
        .collect::<Vec<_>>()
        .join(native_protection::text!("\n"))
}
#[cfg(windows)]
fn monitor_edids() -> Vec<Vec<u8>> {
    let mut values = Vec::new();
    let root = RegKey::predef(HKEY_LOCAL_MACHINE);
    let Ok(display) = root.open_subkey_with_flags(
        native_protection::text!(r"SYSTEM\CurrentControlSet\Enum\DISPLAY"),
        KEY_READ | KEY_WOW64_64KEY,
    ) else {
        return values;
    };
    for vendor in display.enum_keys().flatten() {
        let Ok(vendor_key) = display.open_subkey_with_flags(&vendor, KEY_READ | KEY_WOW64_64KEY)
        else {
            continue;
        };
        for instance in vendor_key.enum_keys().flatten() {
            let Ok(parameters) = vendor_key.open_subkey_with_flags(
                format!(r"{}\Device Parameters", instance),
                KEY_READ | KEY_WOW64_64KEY,
            ) else {
                continue;
            };
            if let Ok(raw) = parameters.get_raw_value(native_protection::text!("EDID"))
                && !raw.bytes.is_empty()
            {
                values.push(raw.bytes);
            }
        }
    }
    values
}
fn enroll(license: &str, hardware: &HardwareProfile) -> Result<InstallCredential> {
    enroll_after_preflight(
        || credential_protection::preflight(&required_user_binding_dir()?),
        || enroll_remote(license, hardware),
    )
}
fn enroll_after_preflight<T>(
    preflight: impl FnOnce() -> Result<()>,
    enroll_remote: impl FnOnce() -> Result<T>,
) -> Result<T> {
    preflight()?;
    enroll_remote()
}
fn enroll_remote(license: &str, hardware: &HardwareProfile) -> Result<InstallCredential> {
    let signing = SigningKey::generate(&mut OsRng);
    let encryption = StaticSecret::random_from_rng(OsRng);
    let encryption_public = X25519PublicKey::from(&encryption);
    let start = post_json (native_protection :: text ! ("/v1/enroll/start") , & json ! ({ (native_protection :: text ! ("product")) : (native_protection :: text ! ("celeste")) , (native_protection :: text ! ("release_id")) : release :: launcher_channel_id () , (native_protection :: text ! ("client_version")) : release :: launcher_protocol_version () , (native_protection :: text ! ("client_sha256")) : release :: launcher_channel_digest () , (native_protection :: text ! ("game_uuid")) : INSTALLER_UUID , (native_protection :: text ! ("game_name")) : INSTALLER_NAME , (native_protection :: text ! ("license_key")) : license , (native_protection :: text ! ("install_signing_key")) : encode (signing . verifying_key () . as_bytes ()) , (native_protection :: text ! ("install_encryption_key")) : encode (encryption_public . as_bytes ()) , (native_protection :: text ! ("hardware")) : hardware , }) ,) . map_err (| error | { if error . downcast_ref :: < HttpFailure > () . is_some_and (| failure | failure . status == 409) { error . context (native_protection :: text ! ("This license is already activated. Use the original account and computer, or contact support if its saved activation was lost")) } else { error . context (native_protection :: text ! ("License activation failed")) } }) ? ;
    let message = decode(required_text(&start, native_protection::text!("/message"))?)?;
    let complete = post_json_with_dpop(
        native_protection::text!("/v1/enroll/complete"),
        &json ! ({ (native_protection :: text ! ("challenge_id")) : required_text (& start , (native_protection :: text ! ("/challenge_id"))) ?, (native_protection :: text ! ("install_signature")) : encode (& signing . sign (& message) . to_bytes ()) , }),
        Some(&signing),
    )?;
    let certificate =
        required_text(&complete, native_protection::text!("/device_certificate"))?.to_owned();
    let certificate_claims = verify_token(
        &certificate,
        native_protection::text!("celeste-installation"),
    )?;
    dpop::require_binding(&certificate_claims, &signing)?;
    let device_id = required_text(&complete, native_protection::text!("/device_id"))?.to_owned();
    let uid = complete
        .pointer(native_protection::text!("/uid"))
        .and_then(Value::as_u64)
        .ok_or_else(|| {
            anyhow!(
                "{}",
                native_protection::text!("Enrollment response omitted uid")
            )
        })?;
    if required_text(&certificate_claims, native_protection::text!("/device"))? != device_id
        || required_text(&certificate_claims, native_protection::text!("/sub"))? != uid.to_string()
        || required_text(
            &certificate_claims,
            native_protection::text!("/install_key"),
        )? != encode(signing.verifying_key().as_bytes())
    {
        bail!(
            "{}",
            native_protection::text!("Installation certificate does not match this device")
        );
    }
    let seed = decrypt_token(
        complete
            .pointer(native_protection::text!("/token_envelope"))
            .ok_or_else(|| {
                anyhow!(
                    "{}",
                    native_protection::text!("Enrollment response omitted token envelope")
                )
            })?,
        &encryption,
        &message,
    )?;
    Ok(InstallCredential {
        device_id,
        uid,
        signing_private: encode(signing.as_bytes()),
        encryption_private: encode(encryption.as_bytes()),
        token_seed: encode(&seed),
        certificate,
        game_uuid: INSTALLER_UUID.to_owned(),
        game_name: INSTALLER_NAME.to_owned(),
    })
}
fn authenticate_installer(
    credential: &InstallCredential,
    hardware: &HardwareProfile,
    license: Option<&str>,
) -> Result<Value> {
    let signing = signing_key(&credential.signing_private)?;
    let token = signing_key(&credential.token_seed)?;
    let start = post_json(
        native_protection::text!("/v1/installer/start"),
        &json ! ({ (native_protection :: text ! ("product")) : (native_protection :: text ! ("celeste")) , (native_protection :: text ! ("release_id")) : release :: launcher_channel_id () , (native_protection :: text ! ("client_version")) : release :: launcher_protocol_version () , (native_protection :: text ! ("client_sha256")) : release :: launcher_channel_digest () , (native_protection :: text ! ("game_uuid")) : INSTALLER_UUID , (native_protection :: text ! ("game_name")) : INSTALLER_NAME , (native_protection :: text ! ("device_id")) : credential . device_id , (native_protection :: text ! ("license_key")) : license , (native_protection :: text ! ("hardware")) : hardware , }),
    )?;
    let message = decode(required_text(&start, native_protection::text!("/message"))?)?;
    let complete = post_json_with_dpop(
        native_protection::text!("/v1/installer/complete"),
        &json ! ({ (native_protection :: text ! ("challenge_id")) : required_text (& start , (native_protection :: text ! ("/challenge_id"))) ?, (native_protection :: text ! ("install_signature")) : encode (& signing . sign (& message) . to_bytes ()) , (native_protection :: text ! ("token_signature")) : encode (& token . sign (& message) . to_bytes ()) , }),
        Some(&signing),
    )?;
    let grant = required_text(&complete, native_protection::text!("/grant"))?;
    let claims = verify_token(grant, native_protection::text!("celeste-installer"))?;
    dpop::require_binding(&claims, &signing)?;
    let bootstrap_url = required_text(&complete, native_protection::text!("/bootstrap/url"))?;
    let bootstrap_id = bootstrap_url.rsplit('/').next().unwrap_or("");
    let bootstrap_sha = required_text(&complete, native_protection::text!("/bootstrap/sha256"))?;
    if required_text(&claims, native_protection::text!("/product"))?
        != native_protection::text!("celeste")
    {
        return Err(ProductDenied.into());
    }
    if required_text(&claims, native_protection::text!("/device"))? != credential.device_id
        || required_text(&claims, native_protection::text!("/release"))?
            != release::launcher_channel_id()
        || required_text(&claims, native_protection::text!("/client_sha256"))?
            != release::launcher_channel_digest()
        || required_text(&claims, native_protection::text!("/bootstrap"))? != bootstrap_id
        || required_text(&claims, native_protection::text!("/bootstrap_sha256"))? != bootstrap_sha
    {
        bail!(
            "{}",
            native_protection::text!("Installer grant does not match this device or release")
        );
    }
    Ok(complete)
}
fn decrypt_token(envelope: &Value, private: &StaticSecret, message: &[u8]) -> Result<[u8; 32]> {
    let ephemeral = fixed_32(&decode(required_text(
        envelope,
        native_protection::text!("/ephemeral_key"),
    )?)?)?;
    let nonce = decode(required_text(envelope, native_protection::text!("/nonce"))?)?;
    if nonce.len() != 12 {
        bail!(
            "{}",
            native_protection::text!("Token envelope nonce is invalid")
        );
    }
    let ciphertext = decode(required_text(
        envelope,
        native_protection::text!("/ciphertext"),
    )?)?;
    let transcript: Value = serde_json::from_slice(message)?;
    let salt = decode(required_text(
        &transcript,
        native_protection::text!("/nonce"),
    )?)?;
    if salt.len() != 32 {
        bail!(
            "{}",
            native_protection::text!("Token envelope salt is invalid")
        );
    }
    let shared = private.diffie_hellman(&X25519PublicKey::from(ephemeral));
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
    let mut wrapping = [0u8; 32];
    hkdf.expand(TOKEN_INFO, &mut wrapping).map_err(|_| {
        anyhow!(
            "{}",
            native_protection::text!("Token key derivation failed")
        )
    })?;
    let cipher = Aes256Gcm::new_from_slice(&wrapping).map_err(|_| {
        anyhow!(
            "{}",
            native_protection::text!("Token cipher could not start")
        )
    })?;
    let plain = cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &ciphertext,
                aad: message,
            },
        )
        .map_err(|_| {
            anyhow!(
                "{}",
                native_protection::text!("Installation token envelope was rejected")
            )
        })?;
    fixed_32(&plain)
}
fn post_json(path: &str, body: &Value) -> Result<Value> {
    post_json_with_dpop(path, body, None)
}
fn post_json_with_dpop(path: &str, body: &Value, signing: Option<&SigningKey>) -> Result<Value> {
    let url = endpoint(path)?;
    let agent = ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .timeout(std::time::Duration::from_secs(25))
        .build();
    let mut request = agent
        .post(&url)
        .set(
            native_protection::text!("Content-Type"),
            native_protection::text!("application/json"),
        )
        .set(
            native_protection::text!("Accept"),
            native_protection::text!("application/json"),
        );
    if let Some(signing) = signing {
        request = request.set(
            native_protection::text!("DPoP"),
            &dpop::proof(signing, native_protection::text!("POST"), &url, None)?,
        );
    }
    let result = request.send_json(body.clone());
    match result {
        Ok(response) => {
            let mut bytes = Vec::new();
            response
                .into_reader()
                .take(65_537)
                .read_to_end(&mut bytes)?;
            if bytes.len() > 65_536 {
                bail!(
                    "{}",
                    native_protection::text!("Authentication response exceeds size bounds")
                );
            }
            serde_json::from_slice(&bytes)
                .context(native_protection::text!("Server returned invalid JSON"))
        }
        Err(ureq::Error::Status(code, response)) => {
            let mut detail = String::new();
            let _ = response
                .into_reader()
                .take(4_097)
                .read_to_string(&mut detail);
            Err(http_failure(code, path, &short_detail(&detail, body)))
        }
        Err(error) => Err(anyhow!(error).context(native_protection::text!(
            "Authentication server is unreachable"
        ))),
    }
}
fn get_bytes(path: &str, grant: &str, signing: &SigningKey) -> Result<Vec<u8>> {
    dpop::require_binding(
        &verify_token(grant, native_protection::text!("celeste-installer"))?,
        signing,
    )?;
    let url = endpoint(path)?;
    let agent = ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .timeout(std::time::Duration::from_secs(25))
        .build();
    let response = agent
        .get(&url)
        .set(
            native_protection::text!("Accept"),
            native_protection::text!("application/octet-stream"),
        )
        .set(
            native_protection::text!("Authorization"),
            &format!("DPoP {grant}"),
        )
        .set(
            native_protection::text!("DPoP"),
            &dpop::proof(signing, native_protection::text!("GET"), &url, Some(grant))?,
        )
        .call()
        .map_err(|error| anyhow!("Loader delivery failed: {error}"))?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(32 * 1024 * 1024)
        .read_to_end(&mut bytes)?;
    if bytes.is_empty() {
        bail!("{}", native_protection::text!("Loader delivery was empty"));
    }
    Ok(bytes)
}
fn endpoint(path: &str) -> Result<String> {
    if !release::base_url().starts_with(native_protection::text!("https://"))
        || !path.starts_with(native_protection::text!("/v1/"))
        || path.contains(native_protection::text!("://"))
        || path.contains(native_protection::text!(".."))
        || path.contains(['?', '#', '%', '\\'])
    {
        bail!(
            "{}",
            native_protection::text!("Server response referenced an untrusted endpoint")
        );
    }
    Ok(format!(
        "{}{}",
        release::base_url().trim_end_matches('/'),
        path
    ))
}
fn verify_token(token: &str, audience: &str) -> Result<Value> {
    let parts = token.split('.').collect::<Vec<_>>();
    if parts.len() != 3 {
        bail!(
            "{}",
            native_protection::text!("Server returned a malformed signed token")
        );
    }
    let key = VerifyingKey::from_bytes(&fixed_32(&decode(release::server_key())?)?)?;
    let signature = Signature::from_slice(&decode(parts[2])?)?;
    key.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature)
        .context(native_protection::text!(
            "Server token signature is invalid"
        ))?;
    let header: Value = serde_json::from_slice(&decode(parts[0])?)?;
    let claims: Value = serde_json::from_slice(&decode(parts[1])?)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    if required_text(&header, native_protection::text!("/alg"))?
        != native_protection::text!("EdDSA")
        || required_text(&header, native_protection::text!("/typ"))?
            != native_protection::text!("CST")
        || required_text(&claims, native_protection::text!("/aud"))? != audience
        || claims
            .pointer(native_protection::text!("/nbf"))
            .and_then(Value::as_u64)
            .unwrap_or(0)
            > now + 5
        || claims
            .pointer(native_protection::text!("/exp"))
            .and_then(Value::as_u64)
            .unwrap_or(0)
            <= now
    {
        bail!(
            "{}",
            native_protection::text!("Server token is expired or has the wrong audience")
        );
    }
    Ok(claims)
}
fn signing_key(encoded: &str) -> Result<SigningKey> {
    let value = decode(encoded)?;
    if value.len() == 32 {
        return Ok(SigningKey::from_bytes(&fixed_32(&value)?));
    }
    SigningKey::from_pkcs8_der(&value)
        .context(native_protection::text!("Saved signing key is invalid"))
}
fn load_credential(path: &Path) -> Result<InstallCredential> {
    let stored = fs::read(path)?;
    if stored.len() <= STORE_MAGIC.len() || &stored[..STORE_MAGIC.len()] != STORE_MAGIC {
        bail!(
            "{}",
            native_protection::text!("Credential envelope is invalid")
        );
    }
    let plain = dpapi_unprotect(&stored[STORE_MAGIC.len()..])?;
    let credential: InstallCredential = serde_json::from_slice(&plain)?;
    if credential.device_id.len() < 32 || credential.uid == 0 || credential.certificate.is_empty() {
        bail!(
            "{}",
            native_protection::text!("Credential data is incomplete")
        );
    }
    let signing = signing_key(&credential.signing_private)?;
    signing_key(&credential.token_seed)?;
    let claims = verify_token(
        &credential.certificate,
        native_protection::text!("celeste-installation"),
    )?;
    if claims.get(native_protection::text!("cnf")).is_some() {
        dpop::require_binding(&claims, &signing)?;
    }
    let public_key = encode(signing.verifying_key().as_bytes());
    if required_text(&claims, native_protection::text!("/sub"))? != credential.uid.to_string()
        || required_text(&claims, native_protection::text!("/device"))? != credential.device_id
        || required_text(&claims, native_protection::text!("/install_key"))? != public_key
    {
        bail!(
            "{}",
            native_protection::text!(
                "Saved installation credential does not match its signed certificate"
            )
        );
    }
    Ok(credential)
}
fn save_credential(path: &Path, credential: &InstallCredential) -> Result<()> {
    let plain = serde_json::to_vec(credential)?;
    let protected = dpapi_protect(&plain)?;
    let mut stored = Vec::with_capacity(STORE_MAGIC.len() + protected.len());
    stored.extend_from_slice(STORE_MAGIC);
    stored.extend_from_slice(&protected);
    atomic_write(path, &stored)
}
fn dpapi_protect(value: &[u8]) -> Result<Vec<u8>> {
    credential_protection::protect(value)
}
fn dpapi_unprotect(value: &[u8]) -> Result<Vec<u8>> {
    credential_protection::unprotect(value)
}
fn required_text<'a>(value: &'a Value, pointer: &str) -> Result<&'a str> {
    value
        .pointer(pointer)
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("Server response omitted {pointer}"))
}
fn require_digest(value: &[u8], expected: &str) -> Result<()> {
    let actual = hex_sha256(value);
    if actual.as_bytes() != expected.as_bytes() {
        bail!(
            "Signed loader digest does not match the downloaded file (expected {expected}, got {actual}, bytes={})",
            value.len()
        );
    }
    Ok(())
}
fn hex_sha256(value: &[u8]) -> String {
    let digest = Sha256::digest(value);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}
fn encode(value: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(value)
}
fn decode(value: &str) -> Result<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(value)
        .context(native_protection::text!("Invalid base64url value"))
}
fn fixed_32(value: &[u8]) -> Result<[u8; 32]> {
    value
        .try_into()
        .map_err(|_| anyhow!("{}", native_protection::text!("Expected a 32-byte key")))
}
fn short_detail(value: &str, request: &Value) -> String {
    let parsed: Value = serde_json::from_str(value).unwrap_or(Value::Null);
    let mut detail = parsed
        .pointer(native_protection::text!("/detail"))
        .and_then(Value::as_str)
        .unwrap_or(native_protection::text!("request denied"))
        .to_owned();
    for key in [
        native_protection::text!("license_key"),
        native_protection::text!("install_signature"),
        native_protection::text!("token_signature"),
        native_protection::text!("grant"),
    ] {
        if let Some(secret) = request
            .get(key)
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
        {
            detail = detail.replace(secret, native_protection::text!("[redacted]"));
        }
    }
    detail.chars().take(120).collect()
}
fn is_product_denied(error: &anyhow::Error) -> bool {
    error.is::<ProductDenied>()
        || error
            .downcast_ref::<HttpFailure>()
            .is_some_and(|failure| failure.status == 403)
}
#[derive(Debug)]
struct ProductDenied;
impl std::fmt::Display for ProductDenied {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(native_protection::text!(
            "This activation is not authorized for Celeste. Enter a Celeste or Both license"
        ))
    }
}
impl std::error::Error for ProductDenied {}
pub(crate) fn activation_key_required(error: &anyhow::Error) -> bool {
    is_unauthorized(error) || is_product_denied(error) || bindings::license_required(error)
}
#[cfg(test)]
mod activation_tests {
    use super::*;
    use std::cell::{Cell, RefCell};
    fn fixture() -> InstallCredential {
        InstallCredential {
            device_id: "nonsecret-test-device".into(),
            uid: 7,
            signing_private: String::new(),
            encryption_private: String::new(),
            token_seed: String::new(),
            certificate: String::new(),
            game_uuid: String::new(),
            game_name: String::new(),
        }
    }
    #[test]
    fn first_use_without_key_has_no_enrollment_or_persistence() {
        let result = finish_activation(
            Ok(None),
            false,
            || panic!("no implicit enrollment"),
            |_| panic!("nothing to persist"),
            |_| panic!("nothing to authenticate"),
        )
        .unwrap();
        assert!(result.is_none());
    }
    #[test]
    fn explicit_activation_saves_before_authentication_and_before_unlocking() {
        let calls = RefCell::new(Vec::new());
        let result = finish_activation(
            Ok(None),
            true,
            || {
                calls.borrow_mut().push("enroll");
                Ok(fixture())
            },
            |_| {
                calls.borrow_mut().push("persist");
                Ok(())
            },
            |_| {
                calls.borrow_mut().push("authenticate");
                Ok(json ! ({ "product" : "celeste" }))
            },
        )
        .unwrap()
        .unwrap();
        assert_eq!(result.uid, 7);
        assert_eq!(
            *calls.borrow(),
            ["enroll", "persist", "authenticate", "persist"]
        );
    }
    #[test]
    fn successful_saved_authentication_still_requires_shared_persistence() {
        let result = finish_activation(
            Ok(Some((fixture(), json ! ({ "product" : "celeste" })))),
            false,
            || panic!("must reuse"),
            |_| bail!("cache not writable"),
            |_| panic!("already authenticated"),
        );
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("cache not writable")
        );
    }
    #[test]
    fn newly_enrolled_identity_cannot_unlock_if_persistence_fails() {
        let result = finish_activation(
            Ok(None),
            true,
            || Ok(fixture()),
            |_| bail!("cache not writable"),
            |_| panic!("must persist before checking or unlocking"),
        );
        assert!(result.is_err());
    }
    #[test]
    fn network_error_on_saved_identity_never_enrolls_even_with_explicit_key() {
        for explicit_key in [false, true] {
            let result = finish_activation(
                Err(anyhow!("Authentication server is unreachable")),
                explicit_key,
                || panic!("network failure is not permission to enroll"),
                |_| panic!("nothing newly verified"),
                |_| panic!("do not retry automatically"),
            );
            assert!(!activation_key_required(&result.unwrap_err()));
        }
    }
    #[test]
    fn failed_first_authentication_preserves_the_new_credential_for_retry() {
        let writes = Cell::new(0);
        let result = finish_activation(
            Ok(None),
            true,
            || Ok(fixture()),
            |_| {
                writes.set(writes.get() + 1);
                Ok(())
            },
            |_| bail!("offline"),
        );
        assert!(result.is_err());
        assert_eq!(writes.get(), 1);
    }
    #[test]
    fn revoked_and_wrong_product_saved_bindings_require_a_key_without_enrolling() {
        for status in [401, 403] {
            let result = finish_activation(
                Err(http_failure(status, "/v1/installer/start", "denied")),
                false,
                || panic!("automatic startup must not enroll"),
                |_| panic!("rejected activation must not persist"),
                |_| panic!("rejected activation must not authenticate again"),
            );
            assert!(activation_key_required(&result.unwrap_err()));
        }
        assert!(activation_key_required(&anyhow!(ProductDenied)));
    }
    #[test]
    fn explicit_key_can_replace_server_rejected_binding() {
        let result = finish_activation(
            Err(http_failure(
                401,
                "/v1/installer/start",
                "old activation revoked",
            )),
            true,
            || Ok(fixture()),
            |_| Ok(()),
            |_| Ok(json ! ({ "product" : "celeste" })),
        )
        .unwrap()
        .unwrap();
        assert_eq!(result.uid, 7);
    }
    #[test]
    fn failed_preflight_makes_zero_enrollment_requests() {
        let requests = Cell::new(0);
        let result = enroll_after_preflight(
            || bail!("simulated Windows protection or cache write failure"),
            || {
                requests.set(requests.get() + 1);
                Ok(())
            },
        );
        assert!(result.is_err());
        assert_eq!(requests.get(), 0);
    }
    #[test]
    fn successful_preflight_precedes_the_only_enrollment_request() {
        let calls = RefCell::new(Vec::new());
        enroll_after_preflight(
            || {
                calls.borrow_mut().push("preflight");
                Ok(())
            },
            || {
                calls.borrow_mut().push("enroll");
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(*calls.borrow(), ["preflight", "enroll"]);
    }
    #[test]
    fn authentication_errors_redact_request_secrets_before_truncation() {
        let license = "nonsecret-placeholder-license-key-123";
        let signature = "nonsecret-signature-fixture";
        let detail = json ! ({ "detail" : format ! ("{} denied {license} {signature}" , "prefix " . repeat (12)) });
        let displayed = short_detail(
            &detail.to_string(),
            &json ! ({ "license_key" : license , "install_signature" : signature }),
        );
        assert!(displayed.contains("[redacted]"));
        assert!(!displayed.contains("nonsecret"));
        assert!(displayed.chars().count() <= 120);
        assert_eq!(short_detail("not JSON", &json!({})), "request denied");
    }
}
