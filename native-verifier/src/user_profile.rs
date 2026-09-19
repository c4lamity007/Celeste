#![doc = " The current account's application-data root for the standard Celeste launcher."]
#![doc = " Java's bootstrap resolves the same paths when it reads the shared activation."]
use anyhow::Result;
#[cfg(unix)]
use anyhow::bail;
#[cfg(unix)]
use std::path::Path;
use std::path::PathBuf;
#[cfg(unix)]
pub(crate) fn ensure_private_activation_dir(directory: &Path) -> Result<()> {
    use anyhow::Context;
    use std::fs;
    use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
    if !directory.is_absolute() {
        bail!(
            "{}",
            native_protection::text!("Activation storage requires an absolute account directory")
        );
    }
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true).mode(0o700);
    builder.create(directory).context(native_protection::text!(
        "Could not create the private activation directory"
    ))?;
    let metadata = fs::symlink_metadata(directory).context(native_protection::text!(
        "Could not inspect the private activation directory"
    ))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        bail!(
            "{}",
            native_protection::text!(
                "The activation path must be a private directory, not a link or file"
            )
        );
    }
    fs::set_permissions(directory, fs::Permissions::from_mode(0o700)).context(
        native_protection::text!("Could not restrict access to the activation directory"),
    )
}
#[cfg(windows)]
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    crate::windows_profile::current_local_app_data()
}
#[cfg(any(target_os = "macos", target_os = "linux"))]
fn current_home() -> Result<PathBuf> {
    let home = std::env::var_os(native_protection::text!("HOME"))
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            anyhow::anyhow!(
                "{}",
                native_protection::text!("This account's HOME is unavailable")
            )
        })?;
    let path = PathBuf::from(home);
    if !path.is_absolute() {
        bail!(
            "{}",
            native_protection::text!("This account's HOME is not an absolute path")
        );
    }
    Ok(path)
}
#[cfg(target_os = "macos")]
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    Ok(current_home()?
        .join(native_protection::text!("Library"))
        .join(native_protection::text!("Application Support")))
}
#[cfg(target_os = "linux")]
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    if let Some(xdg) = std::env::var_os(native_protection::text!("XDG_DATA_HOME"))
        .filter(|value| !value.is_empty())
    {
        let path = PathBuf::from(xdg);
        if path.is_absolute() {
            return Ok(path);
        }
    }
    Ok(current_home()?
        .join(native_protection::text!(".local"))
        .join(native_protection::text!("share")))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn application_data_path_is_absolute() {
        assert!(current_local_app_data().unwrap().is_absolute());
    }
}
