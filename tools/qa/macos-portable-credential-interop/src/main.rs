#![allow(dead_code)]

#[cfg(not(target_os = "macos"))]
compile_error!("This fixture intentionally runs only on macOS");

// Compile the production implementation itself. The supporting modules are the
// same files used by the native launcher; preflight is compiled but not called.
#[path = "../../../../beta-lab/native-launcher/src/atomic_store.rs"]
mod atomic_store;
#[path = "../../../../native-verifier/src/portable_credential_protection.rs"]
mod portable_credential_protection;
#[path = "../../../../native-verifier/src/user_profile.rs"]
mod user_profile;

use anyhow::{Context, Result, bail};
use rand::{RngCore, rngs::OsRng};
use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

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

fn main() -> Result<()> {
    let mut arguments = std::env::args_os().skip(1);
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
