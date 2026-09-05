#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod launchers;
mod release;
mod ui;

use crate::launchers::{DetectedLauncher, GameInstance};
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
use std::ptr;
use std::slice;
use std::sync::mpsc;
use winapi::shared::minwindef::DWORD;
use winapi::um::dpapi::{CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData};
use winapi::um::winbase::LocalFree;
use winapi::um::wincrypt::DATA_BLOB;
use winreg::RegKey;
use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_64KEY};
use wmi::{COMLibrary, WMIConnection};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

const STORE_MAGIC: &[u8; 4] = b"CST1";
const TICKET_MAGIC: &[u8; 4] = b"CLT1";
const INSTALLER_UUID: &str = "00000000-0000-0000-0000-000000000000";
const INSTALLER_NAME: &str = "CelesteInstaller";
const TOKEN_INFO: &[u8] = b"celeste-install-token-v1";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallCredential {
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ComputerSystem {
    manufacturer: Option<String>,
    model: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ComputerProduct {
    uuid: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Bios {
    serial_number: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct BaseBoard {
    manufacturer: Option<String>,
    product: Option<String>,
    version: Option<String>,
    serial_number: Option<String>,
}

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
        .any(|argument| argument == "--check-hardware")
    {
        match collect_hardware() {
            Ok(hardware) => {
                println!(
                    "{}",
                    serde_json::to_string(&hardware).expect("hardware json")
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

pub(crate) fn verify_and_install(
    license: String,
    launcher: DetectedLauncher,
    instance: GameInstance,
    tx: &mpsc::Sender<UiEvent>,
) -> Result<()> {
    let send = |message: &str| {
        let _ = tx.send(UiEvent::Status(message.to_owned()));
    };
    send("Authenticating");
    let stored = credential_path(&instance.game_dir);
    let runtime_stored = runtime_credential_path(&instance.game_dir);
    let license = license.trim();

    let existing_credential = if stored.is_file() {
        Some(stored.as_path())
    } else if runtime_stored.is_file() {
        Some(runtime_stored.as_path())
    } else {
        None
    };

    let (credential, session) = if let Some(existing) = existing_credential {
        let hardware = collect_hardware()?;
        let credential = load_credential(existing)
            .context("The saved installation binding could not be opened")?;
        match authenticate_installer(&credential, &hardware) {
            Ok(session) => (credential, session),
            Err(error) if is_unauthorized(&error) => {
                if !license_looks_valid(license) {
                    bail!(
                        "This installation is no longer authorized. Enter a valid Celeste license to rebind."
                    );
                }
                let replacement = enroll(license, &hardware)?;
                save_credential(&stored, &replacement)?;
                let session = authenticate_installer(&replacement, &hardware)?;
                (replacement, session)
            }
            Err(error) => return Err(error),
        }
    } else if license_looks_valid(license) {
        let hardware = collect_hardware()?;
        let created = enroll(license, &hardware)?;
        save_credential(&stored, &created)?;
        let session = authenticate_installer(&created, &hardware)?;
        (created, session)
    } else {
        bail!("Enter a valid Celeste license to bind this installation")
    };

    // Older Celeste builds only wrote the runtime credential. Once that
    // server-signed credential authenticates successfully, promote it into
    // the native verifier store so the user is never forced to re-enroll.
    save_credential(&stored, &credential)?;

    send("Downloading");
    let loader = get_bytes(
        required_text(&session, "/bootstrap/url")?,
        Some(required_text(&session, "/grant")?),
    )?;
    let expected = required_text(&session, "/bootstrap/sha256")?.to_ascii_lowercase();
    require_digest(&loader, &expected)?;
    send("Installing");
    // The bootstrap and every remote stage perform their own signed challenge.
    // Give them the same DPAPI-bound credential only after the native verifier
    // has completed its independent authentication and verified the bootstrap.
    save_credential(&runtime_stored, &credential)?;
    let _jar = launchers::install_and_patch(&launcher, &instance, &loader)?;
    save_launch_ticket(
        &instance
            .game_dir
            .join("config")
            .join("celeste")
            .join("auth")
            .join("launch.bin"),
        required_text(&session, "/grant")?,
    )?;
    send("Launching");
    launchers::launch(&launcher, &instance)?;
    Ok(())
}

fn license_looks_valid(license: &str) -> bool {
    let len = license.len();
    (20..=96).contains(&len)
}

pub(crate) fn saved_binding_exists(game_dir: &Path) -> bool {
    credential_path(game_dir).is_file() || runtime_credential_path(game_dir).is_file()
}

pub(crate) fn any_saved_binding() -> bool {
    launchers::detect_installed().iter().any(|launcher| {
        launchers::preferred_instances(&launchers::list_instances(launcher))
            .iter()
            .any(|instance| saved_binding_exists(&instance.game_dir))
    })
}

fn credential_path(game_root: &Path) -> PathBuf {
    game_root
        .join("config")
        .join("celeste")
        .join("auth")
        .join("installer.bin")
}

fn runtime_credential_path(game_root: &Path) -> PathBuf {
    game_root
        .join("config")
        .join("celeste")
        .join("auth")
        .join("device.bin")
}

fn is_unauthorized(error: &anyhow::Error) -> bool {
    error
        .chain()
        .any(|cause| cause.to_string().contains("(401)"))
}

fn collect_hardware() -> Result<HardwareProfile> {
    let com = COMLibrary::new().context("Windows hardware services are unavailable")?;
    let wmi = WMIConnection::new(com).context("Could not connect to Windows hardware services")?;
    let systems: Vec<ComputerSystem> =
        wmi.raw_query("SELECT Manufacturer,Model FROM Win32_ComputerSystem")?;
    let products: Vec<ComputerProduct> =
        wmi.raw_query("SELECT UUID FROM Win32_ComputerSystemProduct")?;
    let bios: Vec<Bios> = wmi.raw_query("SELECT SerialNumber FROM Win32_BIOS")?;
    let boards: Vec<BaseBoard> =
        wmi.raw_query("SELECT Manufacturer,Product,Version,SerialNumber FROM Win32_BaseBoard")?;
    let processors: Vec<Processor> =
        wmi.raw_query("SELECT ProcessorId,Manufacturer,Name FROM Win32_Processor")?;

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

fn canonical(values: &[Option<&str>]) -> String {
    values
        .iter()
        .map(|value| {
            let normalized = value.unwrap_or("").trim().to_lowercase();
            if normalized.is_empty() {
                "unknown".to_owned()
            } else {
                normalized
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn monitor_edids() -> Vec<Vec<u8>> {
    let mut values = Vec::new();
    let root = RegKey::predef(HKEY_LOCAL_MACHINE);
    let Ok(display) = root.open_subkey_with_flags(
        r"SYSTEM\CurrentControlSet\Enum\DISPLAY",
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
            if let Ok(raw) = parameters.get_raw_value("EDID")
                && !raw.bytes.is_empty()
            {
                values.push(raw.bytes);
            }
        }
    }
    values
}

fn enroll(license: &str, hardware: &HardwareProfile) -> Result<InstallCredential> {
    let signing = SigningKey::generate(&mut OsRng);
    let encryption = StaticSecret::random_from_rng(OsRng);
    let encryption_public = X25519PublicKey::from(&encryption);
    let start = post_json(
        "/v1/enroll/start",
        &json!({
            "release_id": release::LAUNCHER_CHANNEL_ID,
            "client_version": release::LAUNCHER_PROTOCOL_VERSION,
            "client_sha256": release::LAUNCHER_CHANNEL_DIGEST,
            "game_uuid": INSTALLER_UUID,
            "game_name": INSTALLER_NAME,
            "license_key": license,
            "install_signing_key": encode(signing.verifying_key().as_bytes()),
            "install_encryption_key": encode(encryption_public.as_bytes()),
            "hardware": hardware,
        }),
    )
    .context(
        "License activation failed. Use the complete one-time key from the current auth dashboard",
    )?;
    let message = decode(required_text(&start, "/message")?)?;
    let complete = post_json(
        "/v1/enroll/complete",
        &json!({
            "challenge_id": required_text(&start, "/challenge_id")?,
            "install_signature": encode(&signing.sign(&message).to_bytes()),
        }),
    )?;
    let certificate = required_text(&complete, "/device_certificate")?.to_owned();
    let certificate_claims = verify_token(&certificate, "celeste-installation")?;
    let device_id = required_text(&complete, "/device_id")?.to_owned();
    let uid = complete
        .pointer("/uid")
        .and_then(Value::as_u64)
        .ok_or_else(|| anyhow!("Enrollment response omitted uid"))?;
    if required_text(&certificate_claims, "/device")? != device_id
        || required_text(&certificate_claims, "/sub")? != uid.to_string()
        || required_text(&certificate_claims, "/install_key")?
            != encode(signing.verifying_key().as_bytes())
    {
        bail!("Installation certificate does not match this device");
    }
    let seed = decrypt_token(
        complete
            .pointer("/token_envelope")
            .ok_or_else(|| anyhow!("Enrollment response omitted token envelope"))?,
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
) -> Result<Value> {
    let signing = signing_key(&credential.signing_private)?;
    let token = signing_key(&credential.token_seed)?;
    let start = post_json(
        "/v1/installer/start",
        &json!({
            "release_id": release::LAUNCHER_CHANNEL_ID,
            "client_version": release::LAUNCHER_PROTOCOL_VERSION,
            "client_sha256": release::LAUNCHER_CHANNEL_DIGEST,
            "game_uuid": INSTALLER_UUID,
            "game_name": INSTALLER_NAME,
            "device_id": credential.device_id,
            "hardware": hardware,
        }),
    )?;
    let message = decode(required_text(&start, "/message")?)?;
    let complete = post_json(
        "/v1/installer/complete",
        &json!({
            "challenge_id": required_text(&start, "/challenge_id")?,
            "install_signature": encode(&signing.sign(&message).to_bytes()),
            "token_signature": encode(&token.sign(&message).to_bytes()),
        }),
    )?;
    let grant = required_text(&complete, "/grant")?;
    let claims = verify_token(grant, "celeste-installer")?;
    let bootstrap_url = required_text(&complete, "/bootstrap/url")?;
    let bootstrap_id = bootstrap_url.rsplit('/').next().unwrap_or("");
    let bootstrap_sha = required_text(&complete, "/bootstrap/sha256")?;
    if required_text(&claims, "/device")? != credential.device_id
        || required_text(&claims, "/release")? != release::LAUNCHER_CHANNEL_ID
        || required_text(&claims, "/client_sha256")? != release::LAUNCHER_CHANNEL_DIGEST
        || required_text(&claims, "/bootstrap")? != bootstrap_id
        || required_text(&claims, "/bootstrap_sha256")? != bootstrap_sha
    {
        bail!("Installer grant does not match this device or release");
    }
    Ok(complete)
}

fn decrypt_token(envelope: &Value, private: &StaticSecret, message: &[u8]) -> Result<[u8; 32]> {
    let ephemeral = fixed_32(&decode(required_text(envelope, "/ephemeral_key")?)?)?;
    let nonce = decode(required_text(envelope, "/nonce")?)?;
    if nonce.len() != 12 {
        bail!("Token envelope nonce is invalid");
    }
    let ciphertext = decode(required_text(envelope, "/ciphertext")?)?;
    let transcript: Value = serde_json::from_slice(message)?;
    let salt = decode(required_text(&transcript, "/nonce")?)?;
    if salt.len() != 32 {
        bail!("Token envelope salt is invalid");
    }
    let shared = private.diffie_hellman(&X25519PublicKey::from(ephemeral));
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
    let mut wrapping = [0u8; 32];
    hkdf.expand(TOKEN_INFO, &mut wrapping)
        .map_err(|_| anyhow!("Token key derivation failed"))?;
    let cipher = Aes256Gcm::new_from_slice(&wrapping)
        .map_err(|_| anyhow!("Token cipher could not start"))?;
    let plain = cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &ciphertext,
                aad: message,
            },
        )
        .map_err(|_| anyhow!("Installation token envelope was rejected"))?;
    fixed_32(&plain)
}

fn post_json(path: &str, body: &Value) -> Result<Value> {
    let url = endpoint(path)?;
    let agent = ureq::AgentBuilder::new().redirects(0).build();
    let result = agent
        .post(&url)
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .send_json(body.clone());
    match result {
        Ok(response) => response.into_json().context("Server returned invalid JSON"),
        Err(ureq::Error::Status(code, response)) => {
            let detail = response.into_string().unwrap_or_default();
            bail!(
                "Authentication server rejected the request ({code}) at {path}: {}",
                short_detail(&detail)
            );
        }
        Err(error) => Err(anyhow!(error).context("Authentication server is unreachable")),
    }
}

fn get_bytes(path: &str, bearer: Option<&str>) -> Result<Vec<u8>> {
    let url = endpoint(path)?;
    let agent = ureq::AgentBuilder::new().redirects(0).build();
    let mut request = agent.get(&url).set("Accept", "application/octet-stream");
    if let Some(value) = bearer {
        request = request.set("Authorization", &format!("Bearer {value}"));
    }
    let response = request
        .call()
        .map_err(|error| anyhow!("Loader delivery failed: {error}"))?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(32 * 1024 * 1024)
        .read_to_end(&mut bytes)?;
    if bytes.is_empty() {
        bail!("Loader delivery was empty");
    }
    Ok(bytes)
}

fn endpoint(path: &str) -> Result<String> {
    if !release::BASE_URL.starts_with("https://")
        || !path.starts_with("/v1/")
        || path.contains("://")
        || path.contains("..")
    {
        bail!("Server response referenced an untrusted endpoint");
    }
    Ok(format!(
        "{}{}",
        release::BASE_URL.trim_end_matches('/'),
        path
    ))
}

fn verify_token(token: &str, audience: &str) -> Result<Value> {
    let parts = token.split('.').collect::<Vec<_>>();
    if parts.len() != 3 {
        bail!("Server returned a malformed signed token");
    }
    let key = VerifyingKey::from_bytes(&fixed_32(&decode(release::SERVER_KEY)?)?)?;
    let signature = Signature::from_slice(&decode(parts[2])?)?;
    key.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature)
        .context("Server token signature is invalid")?;
    let header: Value = serde_json::from_slice(&decode(parts[0])?)?;
    let claims: Value = serde_json::from_slice(&decode(parts[1])?)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    if required_text(&header, "/alg")? != "EdDSA"
        || required_text(&header, "/typ")? != "CST"
        || required_text(&claims, "/aud")? != audience
        || claims.pointer("/nbf").and_then(Value::as_u64).unwrap_or(0) > now + 5
        || claims.pointer("/exp").and_then(Value::as_u64).unwrap_or(0) <= now
    {
        bail!("Server token is expired or has the wrong audience");
    }
    Ok(claims)
}

fn signing_key(encoded: &str) -> Result<SigningKey> {
    let value = decode(encoded)?;
    if value.len() == 32 {
        return Ok(SigningKey::from_bytes(&fixed_32(&value)?));
    }
    SigningKey::from_pkcs8_der(&value).context("Saved signing key is invalid")
}

fn load_credential(path: &Path) -> Result<InstallCredential> {
    let stored = fs::read(path)?;
    if stored.len() <= STORE_MAGIC.len() || &stored[..STORE_MAGIC.len()] != STORE_MAGIC {
        bail!("Credential envelope is invalid");
    }
    let plain = dpapi_unprotect(&stored[STORE_MAGIC.len()..])?;
    let credential: InstallCredential = serde_json::from_slice(&plain)?;
    if credential.device_id.len() < 32 || credential.uid == 0 || credential.certificate.is_empty() {
        bail!("Credential data is incomplete");
    }
    let signing = signing_key(&credential.signing_private)?;
    signing_key(&credential.token_seed)?;
    let claims = verify_token(&credential.certificate, "celeste-installation")?;
    let public_key = encode(signing.verifying_key().as_bytes());
    if required_text(&claims, "/sub")? != credential.uid.to_string()
        || required_text(&claims, "/device")? != credential.device_id
        || required_text(&claims, "/install_key")? != public_key
    {
        bail!("Saved installation credential does not match its signed certificate");
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

fn save_launch_ticket(path: &Path, ticket: &str) -> Result<()> {
    let protected = dpapi_protect(ticket.as_bytes())?;
    let mut stored = Vec::with_capacity(TICKET_MAGIC.len() + protected.len());
    stored.extend_from_slice(TICKET_MAGIC);
    stored.extend_from_slice(&protected);
    atomic_write(path, &stored)
}

pub(crate) fn atomic_write(path: &Path, value: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| anyhow!("Output path has no parent"))?;
    fs::create_dir_all(parent)?;
    let pending = path.with_extension("pending");
    fs::write(&pending, value)?;
    if path.exists() {
        fs::remove_file(path)?;
    }
    fs::rename(&pending, path)?;
    Ok(())
}

fn dpapi_protect(value: &[u8]) -> Result<Vec<u8>> {
    unsafe {
        let mut input = DATA_BLOB {
            cbData: value.len() as DWORD,
            pbData: value.as_ptr() as *mut u8,
        };
        let mut output: DATA_BLOB = std::mem::zeroed();
        if CryptProtectData(
            &mut input,
            ptr::null(),
            ptr::null_mut(),
            ptr::null_mut(),
            ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        ) == 0
        {
            bail!("Windows could not protect the device credential");
        }
        let result = slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as *mut _);
        Ok(result)
    }
}

fn dpapi_unprotect(value: &[u8]) -> Result<Vec<u8>> {
    unsafe {
        let mut input = DATA_BLOB {
            cbData: value.len() as DWORD,
            pbData: value.as_ptr() as *mut u8,
        };
        let mut output: DATA_BLOB = std::mem::zeroed();
        if CryptUnprotectData(
            &mut input,
            ptr::null_mut(),
            ptr::null_mut(),
            ptr::null_mut(),
            ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        ) == 0
        {
            bail!("Windows rejected the saved device credential");
        }
        let result = slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as *mut _);
        Ok(result)
    }
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
        .context("Invalid base64url value")
}

fn fixed_32(value: &[u8]) -> Result<[u8; 32]> {
    value
        .try_into()
        .map_err(|_| anyhow!("Expected a 32-byte key"))
}

fn short_detail(value: &str) -> String {
    let parsed: Value = serde_json::from_str(value).unwrap_or(Value::Null);
    parsed
        .pointer("/detail")
        .and_then(Value::as_str)
        .unwrap_or("request denied")
        .chars()
        .take(120)
        .collect()
}
