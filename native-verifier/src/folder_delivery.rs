//! Save the signed normal Fabric mod to an explicitly selected directory.
//! No instance discovery, game configuration, migration, or process operations.

use anyhow::{Context, Result, bail};
use rand::{RngCore, rngs::OsRng};
use serde_json::Value;
use std::fs::{self, OpenOptions};
use std::io::{self, Cursor, Read, Write};
use std::path::{Path, PathBuf};

pub(crate) fn validate_folder(folder: &Path) -> Result<PathBuf> {
    if !folder.is_absolute() || !folder.is_dir() {
        bail!("Choose an existing folder using Browse, or enter its full path");
    }
    fs::canonicalize(folder).context("Could not open the selected download folder")
}

fn validate_mod(bytes: &[u8]) -> Result<()> {
    let mut jar = zip::ZipArchive::new(Cursor::new(bytes))
        .context("The signed download is not a valid Fabric JAR")?;
    let file = jar
        .by_name("fabric.mod.json")
        .context("The signed download has no Fabric mod metadata")?;
    let mut metadata = Vec::new();
    file.take(65_537).read_to_end(&mut metadata)?;
    if metadata.len() > 65_536 {
        bail!("The signed mod metadata is too large");
    }
    let metadata: Value = serde_json::from_slice(&metadata)?;
    if metadata.get("id").and_then(Value::as_str) != Some("celeste_bootstrap") {
        bail!("The signed download is not the Celeste Fabric mod");
    }
    // Older server artifacts require credentials inside a game instance.
    // Do not report a successful movable download for those incompatible JARs.
    if metadata
        .pointer("/custom/celeste:delivery")
        .and_then(Value::as_str)
        != Some("shared-activation-v1")
    {
        bail!(
            "The server's Celeste mod does not support folder downloads yet. A signed shared-activation release must be published before this downloader can be used"
        );
    }
    Ok(())
}

struct Pending(PathBuf);
impl Drop for Pending {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

pub(crate) fn save_mod(folder: &Path, bytes: &[u8]) -> Result<PathBuf> {
    validate_mod(bytes)?;
    let folder = validate_folder(folder)?;
    let mut random = [0u8; 16];
    OsRng.fill_bytes(&mut random);
    let suffix: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let path = folder.join(format!(".celeste-{suffix}.pending"));
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .context("Could not write to the selected folder")?;
    let pending = Pending(path);
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    for number in 1..=999 {
        let name = if number == 1 {
            "Celeste.jar".to_owned()
        } else {
            format!("Celeste-{number}.jar")
        };
        let destination = folder.join(name);
        // Publishing a complete same-volume file through a hard link is
        // atomic and never replaces an existing file or follows its symlink.
        match fs::hard_link(&pending.0, &destination) {
            Ok(()) => return Ok(destination),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error).context(
                "Could not save the mod without replacing existing files. Choose a local folder on a filesystem that supports hard links"),
        }
    }
    bail!("This folder already contains 999 Celeste downloads. Choose another folder")
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let mut bytes = [0u8; 16];
            OsRng.fill_bytes(&mut bytes);
            let name: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
            let root = std::env::temp_dir().join(format!("celeste-folder-{name}"));
            fs::create_dir(&root).unwrap();
            Self(root)
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            for entry in fs::read_dir(&self.0).unwrap().flatten() {
                let _ = fs::remove_file(entry.path());
            }
            let _ = fs::remove_dir(&self.0);
        }
    }
    fn jar(id: &str, movable: bool) -> Vec<u8> {
        let mut archive = zip::ZipWriter::new(Cursor::new(Vec::new()));
        archive
            .start_file("fabric.mod.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        let marker = if movable {
            "shared-activation-v1"
        } else {
            "legacy"
        };
        let metadata = serde_json::json!({"id":id,"custom":{"celeste:delivery":marker}});
        archive.write_all(metadata.to_string().as_bytes()).unwrap();
        archive.finish().unwrap().into_inner()
    }
    #[test]
    fn arbitrary_folder_receives_exactly_the_verified_mod() {
        let fixture = Fixture::new();
        let bytes = jar("celeste_bootstrap", true);
        let output = save_mod(&fixture.0, &bytes).unwrap();
        assert_eq!(
            output,
            fixture.0.canonicalize().unwrap().join("Celeste.jar")
        );
        assert_eq!(fs::read(output).unwrap(), bytes);
        assert_eq!(fs::read_dir(&fixture.0).unwrap().count(), 1);
        assert!(!fixture.0.join("mods").exists());
        assert!(!fixture.0.join("config").exists());
    }
    #[test]
    fn repeated_download_preserves_existing_files() {
        let fixture = Fixture::new();
        fs::write(fixture.0.join("Celeste.jar"), b"existing user file").unwrap();
        let bytes = jar("celeste_bootstrap", true);
        let output = save_mod(&fixture.0, &bytes).unwrap();
        assert_eq!(output.file_name().unwrap(), "Celeste-2.jar");
        assert_eq!(
            fs::read(fixture.0.join("Celeste.jar")).unwrap(),
            b"existing user file"
        );
        assert_eq!(fs::read(output).unwrap(), bytes);
        assert_eq!(fs::read_dir(&fixture.0).unwrap().count(), 2);
    }
    #[test]
    fn invalid_or_legacy_mod_leaves_folder_untouched() {
        let fixture = Fixture::new();
        for bytes in [
            b"not a jar".to_vec(),
            jar("another_mod", true),
            jar("celeste_bootstrap", false),
        ] {
            assert!(save_mod(&fixture.0, &bytes).is_err());
        }
        assert_eq!(fs::read_dir(&fixture.0).unwrap().count(), 0);
    }
    #[test]
    fn invalid_output_paths_do_not_create_anything() {
        let fixture = Fixture::new();
        assert!(validate_folder(Path::new("relative-folder")).is_err());
        assert!(validate_folder(&fixture.0.join("missing")).is_err());
        let file = fixture.0.join("file");
        fs::write(&file, b"keep").unwrap();
        assert!(validate_folder(&file).is_err());
        assert_eq!(fs::read(file).unwrap(), b"keep");
    }

    #[test]
    #[ignore = "Requires CELESTE_FOLDER_QA_JAR pointing to the locally built bootstrap"]
    fn built_bootstrap_exports_unchanged_into_a_plain_folder() {
        let input = std::env::var_os("CELESTE_FOLDER_QA_JAR").expect("bootstrap fixture path");
        let bytes = fs::read(input).unwrap();
        let fixture = Fixture::new();
        let output = save_mod(&fixture.0, &bytes).unwrap();
        assert_eq!(fs::read(output).unwrap(), bytes);
        assert_eq!(fs::read_dir(&fixture.0).unwrap().count(), 1);
    }
}
