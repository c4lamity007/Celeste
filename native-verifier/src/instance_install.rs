#![doc = " Ordinary Fabric installation. Legacy launcher injection is never used here."]
use super::*;
use std::io::{Cursor, Read};
#[derive(Clone, Debug)]
pub struct InstallTarget {
    pub launcher: DetectedLauncher,
    pub instance: GameInstance,
}
fn read_json(path: &Path) -> Option<Value> {
    let file = fs::File::open(path).ok()?;
    let mut bytes = Vec::new();
    file.take(2 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    (bytes.len() <= 2 * 1024 * 1024)
        .then(|| serde_json::from_slice(&bytes).ok())
        .flatten()
}
fn same_path(a: &Path, b: &Path) -> bool {
    match (a.canonicalize(), b.canonicalize()) {
        (Ok(a), Ok(b)) => a == b,
        _ => a == b,
    }
}
fn loader_kind(loader: &str) -> &str {
    loader.split([' ', '-']).next().unwrap_or("")
}
fn check_metadata(instance: &GameInstance) -> Result<()> {
    let loader = instance.loader.to_ascii_lowercase();
    if instance.version.is_empty() || loader.is_empty() {
        bail!(
            "{}",
            native_protection::text!(
                "Could not verify this folder's Minecraft and loader settings. Select the configured instance folder in your launcher."
            )
        );
    }
    if loader_kind(&loader) != native_protection::text!("fabric") {
        bail!(
            "This instance uses {}. Celeste requires Fabric; Quilt and other loaders are not supported.",
            instance.loader
        );
    }
    if instance.version != native_protection::text!("1.21.11") {
        bail!(
            "This instance uses Minecraft {}. Celeste requires Minecraft 1.21.11.",
            instance.version
        );
    }
    Ok(())
}
fn version_metadata(root: &Path, id: &str) -> Option<(String, String)> {
    let mut id = id.to_owned();
    let mut visited = std::collections::HashSet::new();
    let mut game = String::new();
    let mut loader = String::new();
    for _ in 0..16 {
        if id.is_empty()
            || !visited.insert(id.clone())
            || id.contains(['/', '\\'])
            || id == native_protection::text!("..")
        {
            return None;
        }
        let value = read_json(
            &root
                .join(native_protection::text!("versions"))
                .join(&id)
                .join(format!("{id}.json")),
        )?;
        let (mc, detected) = json_version_metadata(&value);
        if !mc.is_empty() {
            game = mc;
        }
        if !detected.is_empty() {
            loader = detected;
        }
        let Some(parent) = value
            .get(native_protection::text!("inheritsFrom"))
            .and_then(Value::as_str)
        else {
            break;
        };
        if parent == native_protection::text!("1.21.11") {
            game = parent.to_owned();
            break;
        }
        id = parent.to_owned();
    }
    Some((game, loader))
}
fn json_version_metadata(value: &Value) -> (String, String) {
    let mut game = value
        .get(native_protection::text!("inheritsFrom"))
        .and_then(Value::as_str)
        .filter(|s| s.bytes().all(|b| b.is_ascii_digit() || b == b'.'))
        .or_else(|| {
            value
                .get(native_protection::text!("id"))
                .and_then(Value::as_str)
                .filter(|s| s.bytes().all(|b| b.is_ascii_digit() || b == b'.'))
        })
        .unwrap_or("")
        .to_owned();
    if game.is_empty() {
        game = first_str(
            value,
            &[
                native_protection::text!("/minecraftVersion"),
                native_protection::text!("/gameVersion"),
                native_protection::text!("/mcVersion"),
                native_protection::text!("/baseModLoader/minecraftVersion"),
                native_protection::text!("/loader/mcVersion"),
            ],
        )
        .unwrap_or_default();
    }
    let mut loader = String::new();
    for lib in value
        .get(native_protection::text!("libraries"))
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let name = lib
            .get(native_protection::text!("name"))
            .and_then(Value::as_str)
            .unwrap_or("");
        if let Some(version) =
            name.strip_prefix(native_protection::text!("net.fabricmc:fabric-loader:"))
        {
            loader = format!("fabric {version}");
        }
        if let Some(version) =
            name.strip_prefix(native_protection::text!("org.quiltmc:quilt-loader:"))
        {
            return (game, format!("quilt {version}"));
        }
    }
    if loader.is_empty() {
        loader = first_str(
            value,
            &[
                native_protection::text!("/loaderVersion/type"),
                native_protection::text!("/modLoader"),
                native_protection::text!("/modloader"),
                native_protection::text!("/loader/loaderType"),
                native_protection::text!("/loaderType"),
                native_protection::text!("/baseModLoader/name"),
            ],
        )
        .unwrap_or_default();
    }
    (game, loader)
}
fn inspected_instances(launcher: &DetectedLauncher) -> Vec<GameInstance> {
    let mut instances = list_instances(launcher);
    if matches!(
        launcher.kind,
        LauncherKind::Official | LauncherKind::Fabric | LauncherKind::SkLauncher
    ) {
        for instance in &mut instances {
            let (game, loader) =
                version_metadata(&launcher.data_dir, &instance.version).unwrap_or_default();
            instance.version = game;
            instance.loader = loader;
        }
    }
    instances
}
pub fn discover_install_targets() -> Vec<InstallTarget> {
    let mut out = Vec::<InstallTarget>::new();
    for launcher in detect_installed() {
        for instance in inspected_instances(&launcher) {
            if check_metadata(&instance).is_ok()
                && !out
                    .iter()
                    .any(|old| same_path(&old.instance.game_dir, &instance.game_dir))
            {
                out.push(InstallTarget {
                    launcher: launcher.clone(),
                    instance,
                });
            }
        }
    }
    out.sort_by(|a, b| {
        a.launcher
            .label()
            .cmp(b.launcher.label())
            .then(a.instance.name.cmp(&b.instance.name))
    });
    out
}
pub fn inspect_folder(folder: &Path) -> Result<Vec<InstallTarget>> {
    if !folder.is_absolute() || !folder.is_dir() {
        bail!(
            "{}",
            native_protection::text!("Choose an existing absolute instance folder.")
        );
    }
    let prism_root = if folder
        .join(native_protection::text!("mmc-pack.json"))
        .is_file()
    {
        Some(folder)
    } else {
        folder.parent().filter(|p| {
            p.join(native_protection::text!("mmc-pack.json")).is_file()
                && matches!(
                    folder.file_name().and_then(|n| n.to_str()),
                    Some("minecraft" | ".minecraft")
                )
        })
    };
    if let Some(root) = prism_root {
        let id = root
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        let (version, loader, name) = prism_meta(root, &id);
        let instance = GameInstance {
            id,
            name,
            game_dir: prism_game_dir(root),
            version,
            loader,
            host: None,
        };
        check_metadata(&instance)?;
        return Ok(vec![InstallTarget {
            launcher: DetectedLauncher {
                kind: LauncherKind::Prism,
                exe: None,
                data_dir: root
                    .parent()
                    .and_then(Path::parent)
                    .unwrap_or(root)
                    .to_path_buf(),
            },
            instance,
        }]);
    }
    let found: Vec<_> = discover_install_targets()
        .into_iter()
        .filter(|t| same_path(&t.instance.game_dir, folder))
        .collect();
    if !found.is_empty() {
        return Ok(found);
    }
    let launcher = DetectedLauncher {
        kind: LauncherKind::Official,
        exe: None,
        data_dir: folder.to_path_buf(),
    };
    let mut found: Vec<_> = inspected_instances(&launcher)
        .into_iter()
        .filter(|i| check_metadata(i).is_ok())
        .map(|instance| InstallTarget {
            launcher: launcher.clone(),
            instance,
        })
        .collect();
    if !found.is_empty() {
        return Ok(found);
    }
    for file in [
        native_protection::text!("version.json"),
        native_protection::text!("instance.json"),
        native_protection::text!("minecraftinstance.json"),
        native_protection::text!("config.json"),
        native_protection::text!("metadata.json"),
    ] {
        if let Some(value) = read_json(&folder.join(file)) {
            let (version, loader) = json_version_metadata(&value);
            if version.is_empty() && loader.is_empty() {
                continue;
            }
            let name = value
                .get(native_protection::text!("name"))
                .and_then(Value::as_str)
                .map(str::to_owned)
                .unwrap_or_else(|| {
                    folder
                        .file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .into_owned()
                });
            let instance = GameInstance {
                id: name.clone(),
                name,
                game_dir: folder.to_path_buf(),
                version,
                loader,
                host: None,
            };
            check_metadata(&instance)?;
            found.push(InstallTarget { launcher, instance });
            return Ok(found);
        }
    }
    bail!(
        "{}",
        native_protection::text!(
            "Could not verify this folder. Choose a Fabric 1.21.11 instance folder, or the .minecraft folder containing its launcher profiles."
        )
    )
}
pub fn validate_install_target(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<()> {
    let effective = instance.host.as_ref().unwrap_or(launcher);
    if let Some(current) = inspected_instances(effective)
        .into_iter()
        .find(|i| i.id == instance.id && same_path(&i.game_dir, &instance.game_dir))
    {
        return check_metadata(&current);
    }
    let targets = inspect_folder(&instance.game_dir).or_else(|e| {
        let root = prism_instance_root(instance);
        if root
            .join(native_protection::text!("mmc-pack.json"))
            .is_file()
        {
            inspect_folder(&root)
        } else {
            Err(e)
        }
    })?;
    if targets.iter().any(|t| {
        same_path(&t.instance.game_dir, &instance.game_dir) && check_metadata(&t.instance).is_ok()
    }) {
        Ok(())
    } else {
        bail!(
            "{}",
            native_protection::text!(
                "The selected instance changed. Refresh the instance list before installing."
            )
        )
    }
}
const LEGACY_BOOTSTRAPS: [&str; 2] = [
    "296a93f81180ed2821ae53aa5a2d563d7dcff198d2adf4cb9bba2352315808fe",
    "31029b20f974fcfc9fddd0256eceb61073f7eac0127de299348e708b3e7d2dcd",
];
fn jar_metadata(bytes: &[u8]) -> Option<Value> {
    let mut jar = zip::ZipArchive::new(Cursor::new(bytes)).ok()?;
    let mut file = jar
        .by_name(native_protection::text!("fabric.mod.json"))
        .ok()?;
    if file.size() > 1024 * 1024 {
        return None;
    }
    let mut json = Vec::new();
    file.read_to_end(&mut json).ok()?;
    serde_json::from_slice(&json).ok()
}
fn read_jar(path: &Path) -> Result<Vec<u8>> {
    let file = fs::File::open(path)?;
    if file.metadata()?.len() > 64 * 1024 * 1024 {
        bail!("{} is too large to inspect safely", path.display());
    }
    let mut bytes = Vec::new();
    file.take(64 * 1024 * 1024 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > 64 * 1024 * 1024 {
        bail!(
            "{}",
            native_protection::text!("JAR changed while inspecting it")
        );
    }
    Ok(bytes)
}
fn backup(path: &Path, bytes: &[u8], backup_dir: &Path) -> Result<()> {
    if fs::read(path)? != bytes {
        bail!(
            "{} changed during installation; retry with the launcher closed",
            path.display()
        );
    }
    let name = path.file_name().unwrap_or_default().to_string_lossy();
    let destination = backup_dir.join(format!("{name}-{}.backup", crate::hex_sha256(bytes)));
    if destination.exists() {
        if fs::read(&destination)? != bytes {
            bail!(
                "{}",
                native_protection::text!("A Celeste migration backup differs; no file was removed")
            );
        }
    } else {
        crate::atomic_write(&destination, bytes)?;
    }
    fs::remove_file(path).with_context(|| {
        format!(
            "Close Minecraft and your launcher before replacing {}",
            path.display()
        )
    })?;
    Ok(())
}
pub fn install_mod(
    launcher: &DetectedLauncher,
    instance: &GameInstance,
    bytes: &[u8],
) -> Result<PathBuf> {
    validate_install_target(launcher, instance)?;
    if jar_metadata(bytes)
        .and_then(|v| v.get(native_protection::text!("id")).cloned())
        .and_then(|v| v.as_str().map(str::to_owned))
        .as_deref()
        != Some(native_protection::text!("celeste_bootstrap"))
    {
        bail!(
            "{}",
            native_protection::text!(
                "The signed download is not the Celeste Fabric bootstrap. Update the installer/server release before installing."
            )
        );
    }
    install_verified_mod(&instance.game_dir, bytes)
}
fn install_verified_mod(game: &Path, bytes: &[u8]) -> Result<PathBuf> {
    let mods = game.join(native_protection::text!("mods"));
    let jar = mods.join(native_protection::text!("Celeste.jar"));
    let config = game.join(native_protection::text!("config/celeste"));
    let receipt_path = config.join(native_protection::text!("installer.json"));
    let receipt = read_json(&receipt_path).unwrap_or(Value::Null);
    let original_jar = if jar.exists() {
        Some(read_jar(&jar)?)
    } else {
        None
    };
    let mut retire = Vec::<(PathBuf, Vec<u8>)>::new();
    if let Ok(entries) = fs::read_dir(&mods) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path
                .extension()
                .and_then(|e| e.to_str())
                .is_none_or(|e| !e.eq_ignore_ascii_case(native_protection::text!("jar")))
            {
                continue;
            }
            if fs::metadata(&path)?.len() > 64 * 1024 * 1024 {
                let mut archive = zip::ZipArchive::new(fs::File::open(&path)?)
                    .with_context(|| format!("Could not inspect {}", path.display()))?;
                let id = archive
                    .by_name(native_protection::text!("fabric.mod.json"))
                    .ok()
                    .and_then(|entry| {
                        let mut data = Vec::new();
                        entry.take(1024 * 1024 + 1).read_to_end(&mut data).ok()?;
                        if data.len() > 1024 * 1024 {
                            return None;
                        }
                        serde_json::from_slice::<Value>(&data)
                            .ok()?
                            .get(native_protection::text!("id"))?
                            .as_str()
                            .map(str::to_owned)
                    });
                if path == jar || matches!(id.as_deref(), Some("celeste" | "celeste_bootstrap")) {
                    bail!(
                        "Another unverified Celeste JAR is present at {}",
                        path.display()
                    );
                }
                continue;
            }
            let existing = read_jar(&path)?;
            let hash = crate::hex_sha256(&existing);
            let owned = LEGACY_BOOTSTRAPS.contains(&hash.as_str())
                || (path == jar
                    && receipt
                        .get(native_protection::text!("product"))
                        .and_then(Value::as_str)
                        == Some(native_protection::text!("celeste"))
                    && [
                        native_protection::text!("sha256"),
                        native_protection::text!("pending_sha256"),
                    ]
                    .iter()
                    .any(|key| receipt.get(key).and_then(Value::as_str) == Some(hash.as_str())));
            let id = jar_metadata(&existing).and_then(|v| {
                v.get(native_protection::text!("id"))
                    .and_then(Value::as_str)
                    .map(str::to_owned)
            });
            if path == jar {
                if existing != bytes && !owned {
                    bail!(
                        "{}",
                        native_protection::text!(
                            "mods/Celeste.jar already exists and is not a verified Celeste installation. Move it aside before installing."
                        )
                    );
                }
            } else if owned {
                retire.push((path, existing));
            } else if matches!(id.as_deref(), Some("celeste_bootstrap" | "celeste")) {
                bail!(
                    "Another Celeste mod is present at {}. Move it aside before installing to avoid duplicate mods.",
                    path.display()
                );
            }
        }
    }
    for name in [
        native_protection::text!("mixinextras-0.5.4.jar"),
        native_protection::text!("CelesteLoader.jar"),
    ] {
        for dir in [
            game.join(native_protection::text!("natives/runtime-cache")),
            game.join(native_protection::text!("natives-tmp")),
        ] {
            let path = dir.join(name);
            if path.is_file() {
                let old = read_jar(&path)?;
                if LEGACY_BOOTSTRAPS.contains(&crate::hex_sha256(&old).as_str()) {
                    retire.push((path, old));
                }
            }
        }
    }
    let pointer = config.join(native_protection::text!("mods.dir"));
    if pointer.is_file() {
        let old = fs::read(&pointer)?;
        let target = String::from_utf8_lossy(&old).trim().to_owned();
        if same_path(
            Path::new(&target),
            &game.join(native_protection::text!("natives/runtime-cache")),
        ) {
            retire.push((pointer, old));
        } else {
            bail!(
                "{}",
                native_protection::text!(
                    "This instance has a custom Celeste mods.dir entry. Review it before installing to avoid loading duplicate mods."
                )
            );
        }
    }
    let root = game.parent().filter(|_| {
        matches!(
            game.file_name().and_then(|n| n.to_str()),
            Some("minecraft" | ".minecraft")
        )
    });
    let mut pack_edit = None;
    if let Some(root) = root {
        let patch = root.join(native_protection::text!("patches/dev.celeste.loader.json"));
        if patch.is_file() {
            let old = fs::read(&patch)?;
            let expected = json ! ({ (native_protection :: text ! ("formatVersion")) : 1 , (native_protection :: text ! ("name")) : (native_protection :: text ! ("Celeste")) , (native_protection :: text ! ("uid")) : HOOK_UID , (native_protection :: text ! ("version")) : (native_protection :: text ! ("1.0.0")) , (native_protection :: text ! ("mainClass")) : HOOK_MAIN , (native_protection :: text ! ("+libraries")) : [{ (native_protection :: text ! ("name")) : HOOK_NAME , (native_protection :: text ! ("MMC-hint")) : (native_protection :: text ! ("local")) , (native_protection :: text ! ("MMC-filename")) : (native_protection :: text ! ("celeste-hook.jar")) }] });
            if serde_json::from_slice::<Value>(&old)? != expected {
                bail!(
                    "{}",
                    native_protection::text!(
                        "The Celeste Prism component was customized. Review it before installing."
                    )
                );
            }
            let path = root.join(native_protection::text!("mmc-pack.json"));
            let original = fs::read(&path)?;
            let mut pack: Value = serde_json::from_slice(&original)
                .context(native_protection::text!("Prism component list is invalid"))?;
            let components = pack
                .get_mut(native_protection::text!("components"))
                .and_then(Value::as_array_mut)
                .ok_or_else(|| {
                    anyhow!(
                        "{}",
                        native_protection::text!("Prism component list is invalid")
                    )
                })?;
            components.retain(|v| {
                v.get(native_protection::text!("uid"))
                    .and_then(Value::as_str)
                    != Some(HOOK_UID)
            });
            pack_edit = Some((path, original, serde_json::to_vec_pretty(&pack)?));
            retire.push((patch, old));
        }
    }
    let backup_dir = config.join(native_protection::text!("installer-backups"));
    if let Some((path, original, updated)) = pack_edit {
        if fs::read(&path)? != original {
            bail!(
                "{}",
                native_protection::text!(
                    "The launcher changed its instance configuration. Close it and retry."
                )
            );
        }
        let saved = backup_dir.join(format!(
            "mmc-pack.json-{}.backup",
            crate::hex_sha256(&original)
        ));
        crate::atomic_write(&saved, &original)?;
        crate::atomic_write(&path, &updated)?;
    }
    for (path, old) in retire {
        backup(&path, &old, &backup_dir)?;
    }
    if fs::read(&jar).ok() != original_jar {
        bail!(
            "{}",
            native_protection::text!(
                "mods/Celeste.jar changed during installation. Close Minecraft and retry."
            )
        );
    }
    let old_hash = fs::read(&jar).ok().map(|b| crate::hex_sha256(&b));
    let hash = crate::hex_sha256(bytes);
    crate::atomic_write(
        &receipt_path,
        &serde_json::to_vec_pretty(
            &json ! ({ (native_protection :: text ! ("format")) : 1 , (native_protection :: text ! ("product")) : (native_protection :: text ! ("celeste")) , (native_protection :: text ! ("sha256")) : old_hash . as_deref () . unwrap_or (& hash) , (native_protection :: text ! ("pending_sha256")) : hash }),
        )?,
    )?;
    crate::atomic_write(&jar, bytes)?;
    crate::atomic_write(
        &receipt_path,
        &serde_json::to_vec_pretty(
            &json ! ({ (native_protection :: text ! ("format")) : 1 , (native_protection :: text ! ("product")) : (native_protection :: text ! ("celeste")) , (native_protection :: text ! ("sha256")) : hash }),
        )?,
    )?;
    Ok(jar)
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let n = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let p = std::env::temp_dir()
                .join(format!("celeste-mod-install-{}-{n}", std::process::id()));
            fs::create_dir_all(&p).unwrap();
            Self(p)
        }
        fn prism(&self) -> InstallTarget {
            let root = self.0.join("instances/Portable profile");
            fs::create_dir_all(&root).unwrap();
            crate :: atomic_write (& root . join ("mmc-pack.json") , & serde_json :: to_vec (& json ! ({ "formatVersion" : 1 , "components" : [{ "uid" : "net.minecraft" , "version" : "1.21.11" } , { "uid" : "net.fabricmc.fabric-loader" , "version" : "0.19.3" }] })) . unwrap ()) . unwrap () ;
            inspect_folder(&root).unwrap().remove(0)
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn jar(id: &str, version: &str) -> Vec<u8> {
        let mut z = zip::ZipWriter::new(Cursor::new(Vec::new()));
        z.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        z.write_all(
            serde_json::to_string(&json ! ({ "id" : id , "version" : version }))
                .unwrap()
                .as_bytes(),
        )
        .unwrap();
        z.finish().unwrap().into_inner()
    }
    #[test]
    fn portable_prism_install_and_update_only_writes_mod_and_receipt() {
        let f = Fixture::new();
        let t = f.prism();
        let pack = t.instance.game_dir.parent().unwrap().join("mmc-pack.json");
        let before = fs::read(&pack).unwrap();
        let one = jar("celeste_bootstrap", "1");
        let dest = install_mod(&t.launcher, &t.instance, &one).unwrap();
        assert_eq!(dest, t.instance.game_dir.join("mods/Celeste.jar"));
        assert_eq!(fs::read(&dest).unwrap(), one);
        let two = jar("celeste_bootstrap", "2");
        install_mod(&t.launcher, &t.instance, &two).unwrap();
        assert_eq!(fs::read(&dest).unwrap(), two);
        assert_eq!(fs::read(&pack).unwrap(), before);
        assert!(!f.0.join("libraries").exists());
        assert!(!t.instance.game_dir.join("config/celeste/mods.dir").exists());
    }
    #[test]
    fn misleading_profile_name_does_not_prove_loader_or_version() {
        let f = Fixture::new();
        let mut t = f.prism();
        t.instance.name = "Fabric 1.21.11".into();
        t.instance.loader = "quilt 0.29".into();
        assert!(check_metadata(&t.instance).is_err());
        t.instance.loader = "fabric 0.19.3".into();
        t.instance.version = "1.21.110".into();
        assert!(check_metadata(&t.instance).is_err());
        t.instance.version = "1.21.11".into();
        assert!(check_metadata(&t.instance).is_ok());
    }
    #[test]
    fn disabled_fabric_component_is_not_installed() {
        let f = Fixture::new();
        let t = f.prism();
        let root = t.instance.game_dir.parent().unwrap();
        let p = root.join("mmc-pack.json");
        let mut value = read_json(&p).unwrap();
        value["components"][1]["disabled"] = json!(true);
        crate::atomic_write(&p, &serde_json::to_vec(&value).unwrap()).unwrap();
        assert!(inspect_folder(root).is_err());
        assert!(install_mod(&t.launcher, &t.instance, &jar("celeste_bootstrap", "1")).is_err());
        assert!(!t.instance.game_dir.exists());
    }
    #[test]
    fn owned_prism_component_retired_preserving_other_metadata() {
        let f = Fixture::new();
        let t = f.prism();
        let root = t.instance.game_dir.parent().unwrap();
        edit_prism_component(&t.instance, Path::new("unused")).unwrap();
        let pack = root.join("mmc-pack.json");
        let mut value = read_json(&pack).unwrap();
        value["custom"] = json ! ({ "keep" : true });
        value["components"]
            .as_array_mut()
            .unwrap()
            .push(json ! ({ "uid" : "other.mod" , "extra" : { "keep" : [1 , 2] } }));
        crate::atomic_write(&pack, &serde_json::to_vec(&value).unwrap()).unwrap();
        let hidden = t.instance.game_dir.join("natives/runtime-cache");
        fs::create_dir_all(&hidden).unwrap();
        crate::atomic_write(
            &t.instance.game_dir.join("config/celeste/mods.dir"),
            hidden.display().to_string().replace('\\', "/").as_bytes(),
        )
        .unwrap();
        install_mod(&t.launcher, &t.instance, &jar("celeste_bootstrap", "1")).unwrap();
        value["components"]
            .as_array_mut()
            .unwrap()
            .retain(|v| v["uid"] != HOOK_UID);
        assert_eq!(read_json(&pack).unwrap(), value);
        assert!(!root.join("patches/dev.celeste.loader.json").exists());
        assert!(!t.instance.game_dir.join("config/celeste/mods.dir").exists());
        assert_eq!(
            fs::read_dir(t.instance.game_dir.join("config/celeste/installer-backups"))
                .unwrap()
                .count(),
            3
        );
    }
    #[test]
    fn genuine_mixinextras_and_other_mods_are_unchanged() {
        let f = Fixture::new();
        let t = f.prism();
        let genuine = jar("mixinextras", "0.5.4");
        let other = jar("example", "1");
        let a = t.instance.game_dir.join("mods/mixinextras-0.5.4.jar");
        let b = t.instance.game_dir.join("mods/example.jar");
        crate::atomic_write(&a, &genuine).unwrap();
        crate::atomic_write(&b, &other).unwrap();
        install_mod(&t.launcher, &t.instance, &jar("celeste_bootstrap", "1")).unwrap();
        assert_eq!(fs::read(a).unwrap(), genuine);
        assert_eq!(fs::read(b).unwrap(), other);
    }
    #[test]
    fn unknown_existing_celeste_and_duplicate_ids_fail_before_mutation() {
        let f = Fixture::new();
        let t = f.prism();
        let path = t.instance.game_dir.join("mods/Celeste.jar");
        crate::atomic_write(&path, b"user data").unwrap();
        assert!(install_mod(&t.launcher, &t.instance, &jar("celeste_bootstrap", "1")).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"user data");
        assert!(!t.instance.game_dir.join("config").exists());
        fs::rename(&path, t.instance.game_dir.join("original-user-data")).unwrap();
        let duplicate = t.instance.game_dir.join("mods/renamed.jar");
        let original = jar("celeste_bootstrap", "old");
        crate::atomic_write(&duplicate, &original).unwrap();
        assert!(install_mod(&t.launcher, &t.instance, &jar("celeste_bootstrap", "1")).is_err());
        assert_eq!(fs::read(duplicate).unwrap(), original);
        assert!(!path.exists());
    }
    #[test]
    fn customized_component_and_pointer_are_preserved() {
        let f = Fixture::new();
        let t = f.prism();
        edit_prism_component(&t.instance, Path::new("unused")).unwrap();
        let path = t
            .instance
            .game_dir
            .parent()
            .unwrap()
            .join("patches/dev.celeste.loader.json");
        let mut value = read_json(&path).unwrap();
        value["extra"] = json!(true);
        let original = serde_json::to_vec(&value).unwrap();
        crate::atomic_write(&path, &original).unwrap();
        assert!(install_mod(&t.launcher, &t.instance, &jar("celeste_bootstrap", "1")).is_err());
        assert_eq!(fs::read(path).unwrap(), original);
        assert!(!t.instance.game_dir.join("mods/Celeste.jar").exists());
    }
    #[test]
    fn official_inheritance_uses_metadata_and_rejects_cycles() {
        let f = Fixture::new();
        let path = f.0.join("versions/fabric-profile/fabric-profile.json");
        crate :: atomic_write (& path , & serde_json :: to_vec (& json ! ({ "id" : "fabric-profile" , "inheritsFrom" : "1.21.11" , "libraries" : [{ "name" : "net.fabricmc:fabric-loader:0.19.3" }] })) . unwrap ()) . unwrap () ;
        assert_eq!(
            version_metadata(&f.0, "fabric-profile"),
            Some(("1.21.11".into(), "fabric 0.19.3".into()))
        );
        crate::atomic_write(
            &path,
            &serde_json::to_vec(
                &json ! ({ "id" : "Fabric 1.21.11" , "inheritsFrom" : "fabric-profile" }),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(version_metadata(&f.0, "fabric-profile"), None);
    }
    #[test]
    fn stale_receipt_after_jar_commit_allows_recovery() {
        let f = Fixture::new();
        let t = f.prism();
        let bytes = jar("celeste_bootstrap", "1");
        let target = install_mod(&t.launcher, &t.instance, &bytes).unwrap();
        let hash = crate::hex_sha256(&bytes);
        crate :: atomic_write (& t . instance . game_dir . join ("config/celeste/installer.json") , & serde_json :: to_vec (& json ! ({ "format" : 1 , "product" : "celeste" , "sha256" : "previous" , "pending_sha256" : hash }) ,) . unwrap () ,) . unwrap () ;
        let next = jar("celeste_bootstrap", "2");
        install_mod(&t.launcher, &t.instance, &next).unwrap();
        assert_eq!(fs::read(target).unwrap(), next);
    }
    #[test]
    #[ignore = "Requires exact prepared release and published legacy artifact directories"]
    fn exact_prepared_bootstrap_migrates_published_legacy_bytes_in_temp_instance() {
        let candidate =
            PathBuf::from(std::env::var_os("CELESTE_INSTALLER_QA_JAR").expect("candidate path"));
        let baseline = PathBuf::from(
            std::env::var_os("CELESTE_INSTALLER_QA_LEGACY").expect("baseline directory"),
        );
        let new = read_jar(&candidate).unwrap();
        assert_eq!(
            crate::hex_sha256(&new),
            std::env::var("CELESTE_INSTALLER_QA_SHA256").unwrap()
        );
        let f = Fixture::new();
        let t = f.prism();
        let hidden = t.instance.game_dir.join("natives/runtime-cache");
        for (name, destination) in [
            (
                "mixinextras-0.5.4.jar",
                hidden.join("mixinextras-0.5.4.jar"),
            ),
            (
                "CelesteLoader.jar",
                t.instance.game_dir.join("mods/CelesteLoader.jar"),
            ),
        ] {
            let bytes = read_jar(&baseline.join(name)).unwrap();
            assert!(LEGACY_BOOTSTRAPS.contains(&crate::hex_sha256(&bytes).as_str()));
            crate::atomic_write(&destination, &bytes).unwrap();
        }
        crate::atomic_write(
            &t.instance.game_dir.join("config/celeste/mods.dir"),
            hidden.display().to_string().replace('\\', "/").as_bytes(),
        )
        .unwrap();
        edit_prism_component(&t.instance, Path::new("fixture")).unwrap();
        let path = install_mod(&t.launcher, &t.instance, &new).unwrap();
        assert_eq!(fs::read(path).unwrap(), new);
        assert!(!hidden.join("mixinextras-0.5.4.jar").exists());
        assert!(!t.instance.game_dir.join("mods/CelesteLoader.jar").exists());
        let backups = t.instance.game_dir.join("config/celeste/installer-backups");
        for hash in LEGACY_BOOTSTRAPS {
            assert!(
                fs::read_dir(&backups)
                    .unwrap()
                    .flatten()
                    .any(|e| crate::hex_sha256(&fs::read(e.path()).unwrap()) == hash)
            );
        }
        install_mod(&t.launcher, &t.instance, &new).unwrap();
        assert_eq!(
            fs::read_dir(t.instance.game_dir.join("mods"))
                .unwrap()
                .count(),
            1
        );
    }
}
