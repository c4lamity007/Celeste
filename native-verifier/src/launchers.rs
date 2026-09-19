#[path = "instance_install.rs"]
mod instance_install;
pub use instance_install::{
    InstallTarget, discover_install_targets, inspect_folder, install_mod, validate_install_target,
};

use anyhow::{Context, Result, anyhow, bail};
use serde_json::{Value, json};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

#[cfg(windows)]
use std::os::windows::ffi::OsStrExt;
const ADD_MODS: &str = "-Dfabric.addMods=";
const HOOK_JAR: &[u8] = include_bytes!("../assets/celeste-hook.jar");
const HOOK_MAIN: &str = "dev.celeste.hook.Main";
const HOOK_NAME: &str = "dev.celeste:hook:1.0.0";
const HOOK_SHA1: &str = "ca0fcb6ee3a114453cbc909b99d1b1a809eb3c43";
const HOOK_UID: &str = "dev.celeste.loader";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LauncherKind {
    Official,
    Modrinth,
    Prism,
    Fabric,
    Dawn,
    Lunar,
    Badlion,
    Feather,
    SkLauncher,
    MultiMc,
    PolyMc,
    AtLauncher,
    CurseForge,
    GdLauncher,
    LabyMod,
}

impl LauncherKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Official => "Official Minecraft",
            Self::Modrinth => "Modrinth",
            Self::Prism => "Prism",
            Self::Fabric => "Fabric",
            Self::Dawn => "Dawn",
            Self::Lunar => "Lunar",
            Self::Badlion => "Badlion",
            Self::Feather => "Feather",
            Self::SkLauncher => "SKLauncher",
            Self::MultiMc => "MultiMC",
            Self::PolyMc => "PolyMC",
            Self::AtLauncher => "ATLauncher",
            Self::CurseForge => "CurseForge",
            Self::GdLauncher => "GDLauncher",
            Self::LabyMod => "Labymod",
        }
    }

    pub fn supported(self) -> bool {
        !matches!(self, Self::Badlion | Self::Feather | Self::LabyMod)
    }

    fn product_rank(self) -> u8 {
        match self {
            Self::Official => 0,
            Self::Dawn => 1,
            Self::Lunar => 2,
            Self::Badlion => 3,
            Self::Prism => 4,
            Self::Modrinth => 5,
            Self::Fabric => 6,
            _ => 20,
        }
    }
}

#[derive(Debug, Clone)]
pub struct DetectedLauncher {
    pub kind: LauncherKind,
    pub exe: Option<PathBuf>,
    pub data_dir: PathBuf,
}

impl DetectedLauncher {
    pub fn label(&self) -> &'static str {
        self.kind.label()
    }
    pub fn supported(&self) -> bool {
        self.kind.supported()
    }
}

#[derive(Debug, Clone)]
pub struct GameInstance {
    pub id: String,
    pub name: String,
    pub game_dir: PathBuf,
    pub version: String,
    pub loader: String,
    pub host: Option<DetectedLauncher>,
}

impl GameInstance {
    pub fn is_fabric_12111(&self) -> bool {
        looks_like_fabric_12111(&self.version, &self.loader, &self.name, &self.id)
    }
}

pub fn detect_installed() -> Vec<DetectedLauncher> {
    let mut found = Vec::new();
    for detector in [
        detect_official,
        detect_modrinth,
        detect_prism,
        detect_fabric,
        detect_dawn,
        detect_lunar,
        detect_badlion,
        detect_feather,
        detect_sklauncher,
        detect_multimc,
        detect_polymc,
        detect_atlauncher,
        detect_curseforge,
        detect_gdlauncher,
        detect_labymod,
    ] {
        if let Some(launcher) = detector() {
            found.push(launcher);
        }
    }
    found.sort_by_key(|launcher| launcher.kind.product_rank());
    if found
        .iter()
        .any(|launcher| launcher.kind == LauncherKind::Dawn)
    {
        found.retain(|launcher| launcher.kind != LauncherKind::Feather);
    }
    found
}

pub fn list_instances(launcher: &DetectedLauncher) -> Vec<GameInstance> {
    match launcher.kind {
        LauncherKind::Official | LauncherKind::Fabric | LauncherKind::SkLauncher => {
            official_instances(&launcher.data_dir)
        }
        LauncherKind::Modrinth => modrinth_instances(&launcher.data_dir),
        LauncherKind::Prism | LauncherKind::MultiMc | LauncherKind::PolyMc => {
            prism_like_instances(&launcher.data_dir)
        }
        LauncherKind::AtLauncher => atlauncher_instances(&launcher.data_dir),
        LauncherKind::CurseForge => curseforge_instances(&launcher.data_dir),
        LauncherKind::GdLauncher => gdlauncher_instances(&launcher.data_dir),
        LauncherKind::Dawn => dawn_instances(&launcher.data_dir),
        LauncherKind::Lunar => lunar_instances(&launcher.data_dir),
        LauncherKind::Badlion => badlion_instances(&launcher.data_dir),
        LauncherKind::Feather | LauncherKind::LabyMod => Vec::new(),
    }
}

pub fn preferred_instances(instances: &[GameInstance]) -> Vec<GameInstance> {
    instances
        .iter()
        .filter(|instance| instance.is_fabric_12111())
        .cloned()
        .collect()
}

pub fn install_and_patch(
    launcher: &DetectedLauncher,
    instance: &GameInstance,
    loader: &[u8],
) -> Result<PathBuf> {
    let effective = instance.host.as_ref().unwrap_or(launcher);
    if effective.kind == LauncherKind::Badlion {
        bail!("Badlion does not expose a supported Fabric mod-loading profile");
    }
    if effective.kind == LauncherKind::Dawn {
        return write_direct_loader(&instance.game_dir.join("mods"), loader);
    }
    if effective.kind == LauncherKind::Lunar {
        return write_direct_loader(&lunar_mods_dir(instance)?, loader);
    }
    let jar = write_hidden_loader(&instance.game_dir, loader)?;
    let hook = deploy_hook(effective, instance)?;
    edit_launcher_files(effective, instance, &hook)?;
    Ok(jar)
}

const LOADER_JAR_NAME: &str = "mixinextras-0.5.4.jar";

fn purge_celeste_loader_jars(game_dir: &Path, extra: &[&Path]) {
    let mut paths = vec![
        game_dir.join("mods").join("CelesteLoader.jar"),
        game_dir
            .join("natives")
            .join("runtime-cache")
            .join("CelesteLoader.jar"),
        game_dir.join("natives-tmp").join("CelesteLoader.jar"),
    ];
    for dir in extra {
        paths.push(dir.join("CelesteLoader.jar"));
    }
    for path in paths {
        if path.is_file() {
            let _ = fs::remove_file(path);
        }
    }
}

fn write_direct_loader(mods_dir: &Path, loader: &[u8]) -> Result<PathBuf> {
    fs::create_dir_all(mods_dir)
        .with_context(|| format!("Could not create {}", mods_dir.display()))?;
    let jar = mods_dir.join(LOADER_JAR_NAME);
    crate::atomic_write(&jar, loader)?;
    purge_celeste_loader_jars(mods_dir, &[mods_dir]);
    Ok(jar)
}

fn lunar_mods_dir(instance: &GameInstance) -> Result<PathBuf> {
    let root = instance.game_dir.join("mods");
    if root.is_dir() {
        let mut candidates = fs::read_dir(&root)
            .into_iter()
            .flatten()
            .flatten()
            .filter(|entry| entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false))
            .map(|entry| entry.path())
            .filter(|path| {
                let name = path
                    .file_name()
                    .and_then(|value| value.to_str())
                    .unwrap_or("")
                    .to_ascii_lowercase();
                name.contains("fabric") && name.contains("1.21.11")
            })
            .collect::<Vec<_>>();
        candidates.sort();
        if let Some(path) = candidates.into_iter().next() {
            return Ok(path);
        }
    }
    if instance.is_fabric_12111() {
        return Ok(root);
    }
    bail!("Select or create a Lunar Fabric 1.21.11 profile before installing Celeste")
}

pub fn launch(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<()> {
    if let Some(host) = &instance.host {
        return launch_kind(host, instance);
    }
    if let Some((host, host_inst)) = read_redirect(instance) {
        return launch_kind(&host, &host_inst);
    }
    launch_kind(launcher, instance)
}

fn hidden_mods_dir(game_dir: &Path) -> PathBuf {
    // Vanilla-looking cache path Fabric can scan. Do not hide this tree:
    // Fabric Loader treats Hidden/System (including hidden parents) as a skipped mod path.
    game_dir.join("natives").join("runtime-cache")
}

fn env_dir(name: &str) -> PathBuf {
    std::env::var_os(name)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(format!("%{name}%")))
}
fn appdata() -> PathBuf {
    env_dir("APPDATA")
}
fn local_appdata() -> PathBuf {
    env_dir("LOCALAPPDATA")
}
fn userprofile() -> PathBuf {
    env_dir("USERPROFILE")
}

fn first_file(paths: &[PathBuf]) -> Option<PathBuf> {
    paths.iter().find(|path| path.is_file()).cloned()
}
fn first_dir(paths: &[PathBuf]) -> Option<PathBuf> {
    paths.iter().find(|path| path.is_dir()).cloned()
}
fn urlencode(value: &str) -> String {
    let mut out = String::new();
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}
fn first_str(value: &Value, pointers: &[&str]) -> Option<String> {
    for pointer in pointers {
        if let Some(text) = value.pointer(pointer).and_then(Value::as_str) {
            if !text.is_empty() {
                return Some(text.to_owned());
            }
        }
    }
    None
}
fn spawn(exe: &Path, args: &[&str]) -> Result<()> {
    let mut command = Command::new(exe);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    command
        .spawn()
        .with_context(|| format!("Could not start {}", exe.display()))?;
    Ok(())
}
fn hide_path(path: &Path) {
    #[cfg(windows)]
    {
        let mut wide: Vec<u16> = path.as_os_str().encode_wide().collect();
        wide.push(0);
        unsafe {
            winapi::um::fileapi::SetFileAttributesW(wide.as_ptr(), 0x2 | 0x4);
        }
    }
    #[cfg(not(windows))]
    {
        let _ = path;
    }
}

fn looks_like_fabric_12111(version: &str, loader: &str, name: &str, id: &str) -> bool {
    let hay = format!("{version} {loader} {name} {id}").to_ascii_lowercase();
    let v = concat!("1.21", ".", "11");
    let a = concat!("1_21", "_", "11");
    let d = concat!("1-21", "-", "11");
    let has_v = hay.contains(v) || hay.contains(a) || hay.contains(d);
    has_v && hay.contains("fabric")
}

fn detect_official() -> Option<DetectedLauncher> {
    let minecraft = appdata().join(".minecraft");
    let exe = first_file(&[
        PathBuf::from(r"C:\XboxGames\Minecraft Launcher\Content\Minecraft.exe"),
        PathBuf::from(r"C:\Program Files (x86)\Minecraft Launcher\MinecraftLauncher.exe"),
        PathBuf::from(r"C:\Program Files\Minecraft Launcher\MinecraftLauncher.exe"),
    ]);
    if minecraft.is_dir() || exe.is_some() {
        Some(DetectedLauncher {
            kind: LauncherKind::Official,
            exe,
            data_dir: minecraft,
        })
    } else {
        None
    }
}

fn detect_modrinth() -> Option<DetectedLauncher> {
    let data = appdata().join("ModrinthApp");
    if !data.is_dir() {
        return None;
    }
    Some(DetectedLauncher {
        kind: LauncherKind::Modrinth,
        exe: first_file(&[
            local_appdata()
                .join("Modrinth App")
                .join("Modrinth App.exe"),
            PathBuf::from(r"C:\Program Files\Modrinth App\Modrinth App.exe"),
        ]),
        data_dir: data,
    })
}

fn detect_prism() -> Option<DetectedLauncher> {
    let data = appdata().join("PrismLauncher");
    if !data.is_dir() {
        return None;
    }
    Some(DetectedLauncher {
        kind: LauncherKind::Prism,
        exe: first_file(&[
            local_appdata()
                .join("Programs")
                .join("PrismLauncher")
                .join("prismlauncher.exe"),
            PathBuf::from(r"C:\Program Files\PrismLauncher\prismlauncher.exe"),
        ]),
        data_dir: data,
    })
}

fn detect_fabric() -> Option<DetectedLauncher> {
    let versions = appdata().join(".minecraft").join("versions");
    let Ok(entries) = fs::read_dir(&versions) else {
        return None;
    };
    let has = entries.flatten().any(|entry| {
        entry
            .file_name()
            .to_string_lossy()
            .to_ascii_lowercase()
            .contains("fabric")
    });
    if !has {
        return None;
    }
    Some(DetectedLauncher {
        kind: LauncherKind::Fabric,
        exe: detect_official().and_then(|launcher| launcher.exe),
        data_dir: appdata().join(".minecraft"),
    })
}

fn detect_named(
    kind: LauncherKind,
    dirs: Vec<PathBuf>,
    exes: Vec<PathBuf>,
) -> Option<DetectedLauncher> {
    let data = first_dir(&dirs)?;
    Some(DetectedLauncher {
        kind,
        exe: first_file(&exes),
        data_dir: data,
    })
}

fn detect_dawn() -> Option<DetectedLauncher> {
    dawn_detect()
}
fn detect_lunar() -> Option<DetectedLauncher> {
    lunar_detect()
}
fn detect_badlion() -> Option<DetectedLauncher> {
    badlion_detect()
}
fn detect_feather() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::Feather,
        vec![
            appdata().join("Feather Launcher"),
            appdata().join(".feather"),
            local_appdata().join("Feather"),
        ],
        vec![
            local_appdata()
                .join("Programs")
                .join("Feather")
                .join("Feather Launcher.exe"),
        ],
    )
}
fn detect_sklauncher() -> Option<DetectedLauncher> {
    let data = first_dir(&[
        appdata().join("SKLauncher"),
        local_appdata().join("SKLauncher"),
    ]);
    let exe = first_file(&[
        appdata().join("SKLauncher").join("SKLauncher.exe"),
        local_appdata().join("SKLauncher").join("SKLauncher.exe"),
    ]);
    if data.is_none() && exe.is_none() {
        return None;
    }
    Some(DetectedLauncher {
        kind: LauncherKind::SkLauncher,
        exe,
        data_dir: data.unwrap_or_else(|| appdata().join(".minecraft")),
    })
}
fn detect_multimc() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::MultiMc,
        vec![appdata().join("MultiMC"), userprofile().join("MultiMC")],
        vec![userprofile().join("MultiMC").join("MultiMC.exe")],
    )
}
fn detect_polymc() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::PolyMc,
        vec![appdata().join("PolyMC"), userprofile().join("PolyMC")],
        vec![appdata().join("PolyMC").join("polymc.exe")],
    )
}
fn detect_atlauncher() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::AtLauncher,
        vec![
            userprofile().join("ATLauncher"),
            appdata().join("ATLauncher"),
        ],
        vec![userprofile().join("ATLauncher").join("ATLauncher.exe")],
    )
}
fn detect_curseforge() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::CurseForge,
        vec![
            userprofile().join("curseforge").join("minecraft"),
            appdata().join("CurseForge"),
        ],
        vec![PathBuf::from(r"C:\Program Files\CurseForge\CurseForge.exe")],
    )
}
fn detect_gdlauncher() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::GdLauncher,
        vec![
            appdata().join("gdlauncher_next"),
            appdata().join("gdlauncher_carbon"),
            appdata().join("gdlauncher"),
        ],
        vec![
            local_appdata()
                .join("Programs")
                .join("GDLauncher")
                .join("GDLauncher.exe"),
        ],
    )
}
fn detect_labymod() -> Option<DetectedLauncher> {
    detect_named(
        LauncherKind::LabyMod,
        vec![
            appdata().join("LabyMod"),
            appdata().join("com.labymod.laby4"),
        ],
        vec![
            local_appdata()
                .join("Programs")
                .join("LabyMod")
                .join("LabyMod.exe"),
        ],
    )
}

fn official_instances(minecraft: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    for name in [
        "launcher_profiles.json",
        "launcher_profiles_microsoft_store.json",
    ] {
        let path = minecraft.join(name);
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        let Ok(value) = serde_json::from_str::<Value>(&text) else {
            continue;
        };
        let Some(profiles) = value.get("profiles").and_then(Value::as_object) else {
            continue;
        };
        for (id, profile) in profiles {
            let last_version = profile
                .get("lastVersionId")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            let name = profile
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or(id)
                .to_owned();
            let game_dir = profile
                .get("gameDir")
                .and_then(Value::as_str)
                .map(PathBuf::from)
                .unwrap_or_else(|| minecraft.to_path_buf());
            instances.push(GameInstance {
                id: id.clone(),
                name,
                game_dir,
                version: last_version.clone(),
                loader: last_version,
                host: None,
            });
        }
    }
    if let Ok(entries) = fs::read_dir(minecraft.join("versions")) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if !name.to_ascii_lowercase().contains("fabric") {
                continue;
            }
            if instances
                .iter()
                .any(|instance| instance.version == name || instance.id == name)
            {
                continue;
            }
            instances.push(GameInstance {
                id: name.clone(),
                name: name.clone(),
                game_dir: minecraft.to_path_buf(),
                version: name.clone(),
                loader: name,
                host: None,
            });
        }
    }
    instances
}

fn modrinth_instances(data: &Path) -> Vec<GameInstance> {
    let db = data.join("app.db");
    if db.is_file() {
        if let Ok(instances) = modrinth_instances_from_db(&db, data) {
            if !instances.is_empty() {
                return instances;
            }
        }
    }
    let mut instances = Vec::new();
    let Ok(entries) = fs::read_dir(data.join("profiles")) else {
        return instances;
    };
    for entry in entries.flatten() {
        if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        instances.push(GameInstance {
            id: name.clone(),
            name: name.clone(),
            game_dir: entry.path(),
            version: name,
            loader: String::new(),
            host: None,
        });
    }
    instances
}

fn modrinth_instances_from_db(db: &Path, data: &Path) -> Result<Vec<GameInstance>> {
    let connection =
        rusqlite::Connection::open_with_flags(db, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    connection.busy_timeout(Duration::from_secs(5))?;
    let mut statement = connection.prepare(
        "SELECT i.id, i.path, i.name, COALESCE(cs.game_version, ''), COALESCE(cs.loader, '')
         FROM instances i
         LEFT JOIN instance_content_sets cs
           ON cs.instance_id = i.id AND cs.id = i.applied_content_set_id",
    )?;
    let rows = statement.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, String>(3)?,
            row.get::<_, String>(4)?,
        ))
    })?;
    let mut custom = data.to_path_buf();
    if let Ok(dir) = connection.query_row("SELECT custom_dir FROM settings", [], |row| {
        row.get::<_, Option<String>>(0)
    }) {
        if let Some(dir) = dir {
            if !dir.is_empty() {
                custom = PathBuf::from(dir);
            }
        }
    }
    let mut instances = Vec::new();
    for row in rows.flatten() {
        let (id, path, name, version, loader) = row;
        let game_dir = if Path::new(&path).is_absolute() {
            PathBuf::from(&path)
        } else {
            custom.join("profiles").join(&path)
        };
        instances.push(GameInstance {
            id,
            name,
            game_dir,
            version,
            loader,
            host: None,
        });
    }
    Ok(instances)
}

fn prism_like_instances(root: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    let Ok(entries) = fs::read_dir(root.join("instances")) else {
        return instances;
    };
    for entry in entries.flatten() {
        if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
            continue;
        }
        let id = entry.file_name().to_string_lossy().into_owned();
        if id.starts_with('.') {
            continue;
        }
        let dir = entry.path();
        let (version, loader, name) = prism_meta(&dir, &id);
        instances.push(GameInstance {
            id,
            name,
            game_dir: prism_game_dir(&dir),
            version,
            loader,
            host: None,
        });
    }
    instances
}

fn prism_game_dir(dir: &Path) -> PathBuf {
    let minecraft = dir.join("minecraft");
    if minecraft.is_dir() {
        return minecraft;
    }
    let dotted = dir.join(".minecraft");
    if dotted.is_dir() {
        return dotted;
    }
    // Prism creates this directory on first launch. Its absence must not
    // change where we store the activation, loader, or launch handoff.
    minecraft
}

fn prism_meta(dir: &Path, fallback: &str) -> (String, String, String) {
    let mut version = String::new();
    let mut loader = String::new();
    let mut name = fallback.to_owned();
    if let Ok(text) = fs::read_to_string(dir.join("mmc-pack.json")) {
        if let Ok(value) = serde_json::from_str::<Value>(&text) {
            if let Some(components) = value.get("components").and_then(Value::as_array) {
                for component in components {
                    let uid = component.get("uid").and_then(Value::as_str).unwrap_or("");
                    let ver = component
                        .get("version")
                        .and_then(Value::as_str)
                        .unwrap_or("");
                    if component.get("disabled").and_then(Value::as_bool) == Some(true) {
                        continue;
                    }
                    if uid == "net.minecraft" {
                        version = ver.to_owned();
                    }
                    if uid == "net.fabricmc.fabric-loader" && !loader.starts_with("quilt ") {
                        loader = format!("fabric {ver}");
                    }
                    if uid == "org.quiltmc.quilt-loader" {
                        loader = format!("quilt {ver}");
                    }
                }
            }
        }
    }
    if let Ok(text) = fs::read_to_string(dir.join("instance.cfg")) {
        for line in text.lines() {
            if let Some(value) = line.strip_prefix("name=") {
                if !value.is_empty() {
                    name = value.to_owned();
                }
            } else if let Some(value) = line.strip_prefix("IntendedVersion=") {
                if version.is_empty() {
                    version = value.to_owned();
                }
            } else if let Some(value) = line.strip_prefix("MinecraftVersion=") {
                if version.is_empty() {
                    version = value.to_owned();
                }
            }
        }
    }
    (version, loader, name)
}

fn atlauncher_instances(root: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    let Ok(entries) = fs::read_dir(root.join("instances")) else {
        return instances;
    };
    for entry in entries.flatten() {
        if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
            continue;
        }
        let dir = entry.path();
        let id = entry.file_name().to_string_lossy().into_owned();
        let mut name = id.clone();
        let mut version = String::new();
        let mut loader = String::new();
        if let Ok(text) = fs::read_to_string(dir.join("instance.json")) {
            if let Ok(value) = serde_json::from_str::<Value>(&text) {
                name = value
                    .get("launcher")
                    .and_then(|launcher| launcher.get("name"))
                    .and_then(Value::as_str)
                    .or_else(|| value.get("name").and_then(Value::as_str))
                    .unwrap_or(&id)
                    .to_owned();
                version = first_str(&value, &["/id", "/minecraftVersion", "/version"])
                    .unwrap_or_default();
                loader =
                    first_str(&value, &["/loaderVersion/type", "/modLoader"]).unwrap_or_default();
            }
        }
        instances.push(GameInstance {
            id,
            name,
            game_dir: dir,
            version,
            loader,
            host: None,
        });
    }
    instances
}

fn curseforge_instances(root: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    let search = if root.join("Instances").is_dir() {
        root.join("Instances")
    } else {
        root.join("minecraft").join("Instances")
    };
    let Ok(entries) = fs::read_dir(&search) else {
        return instances;
    };
    for entry in entries.flatten() {
        if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
            continue;
        }
        let dir = entry.path();
        let id = entry.file_name().to_string_lossy().into_owned();
        let mut name = id.clone();
        let mut version = String::new();
        let mut loader = String::new();
        if let Ok(text) = fs::read_to_string(dir.join("minecraftinstance.json")) {
            if let Ok(value) = serde_json::from_str::<Value>(&text) {
                name = value
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or(&id)
                    .to_owned();
                version = value
                    .pointer("/baseModLoader/minecraftVersion")
                    .and_then(Value::as_str)
                    .or_else(|| value.get("gameVersion").and_then(Value::as_str))
                    .unwrap_or("")
                    .to_owned();
                loader = value
                    .pointer("/baseModLoader/name")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_owned();
            }
        }
        instances.push(GameInstance {
            id,
            name,
            game_dir: dir,
            version,
            loader,
            host: None,
        });
    }
    instances
}

fn gdlauncher_instances(root: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    let search = if root.join("instances").is_dir() {
        root.join("instances")
    } else {
        root.join("data").join("instances")
    };
    let Ok(entries) = fs::read_dir(&search) else {
        return instances;
    };
    for entry in entries.flatten() {
        if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
            continue;
        }
        let dir = entry.path();
        let id = entry.file_name().to_string_lossy().into_owned();
        let mut name = id.clone();
        let mut version = String::new();
        let mut loader = String::new();
        for file in ["instance.json", "config.json", "metadata.json"] {
            let Ok(text) = fs::read_to_string(dir.join(file)) else {
                continue;
            };
            let Ok(value) = serde_json::from_str::<Value>(&text) else {
                continue;
            };
            name = value
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or(&id)
                .to_owned();
            version = first_str(&value, &["/gameVersion", "/mcVersion", "/loader/mcVersion"])
                .unwrap_or_default();
            loader = first_str(&value, &["/modloader", "/loader/loaderType", "/loaderType"])
                .unwrap_or_default();
            break;
        }
        instances.push(GameInstance {
            id,
            name,
            game_dir: dir,
            version,
            loader,
            host: None,
        });
    }
    instances
}

fn deploy_hook(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<PathBuf> {
    let global = local_appdata()
        .join("Celeste")
        .join("hooks")
        .join("celeste-hook.jar");
    crate::atomic_write(&global, HOOK_JAR)?;
    hide_path(global.parent().unwrap_or(&global));
    let path = match launcher.kind {
        LauncherKind::Official
        | LauncherKind::Fabric
        | LauncherKind::SkLauncher
        | LauncherKind::Dawn
        | LauncherKind::Lunar
        | LauncherKind::Badlion => launcher
            .data_dir
            .join("libraries")
            .join("dev")
            .join("celeste")
            .join("hook")
            .join("1.0.0")
            .join("hook-1.0.0.jar"),
        LauncherKind::Prism | LauncherKind::MultiMc | LauncherKind::PolyMc => {
            prism_instance_root(instance)
                .join("libraries")
                .join("celeste-hook.jar")
        }
        LauncherKind::Modrinth => launcher
            .data_dir
            .join("meta")
            .join("libraries")
            .join("dev")
            .join("celeste")
            .join("hook")
            .join("1.0.0")
            .join("hook-1.0.0.jar"),
        LauncherKind::AtLauncher | LauncherKind::CurseForge | LauncherKind::GdLauncher => {
            instance.game_dir.join("libraries").join("celeste-hook.jar")
        }
        _ => global.clone(),
    };
    if path != global {
        crate::atomic_write(&path, HOOK_JAR)?;
    }
    Ok(path)
}

fn hook_file_url(path: &Path) -> String {
    let abs = path
        .canonicalize()
        .ok()
        .or_else(|| {
            if path.is_absolute() {
                Some(path.to_path_buf())
            } else {
                std::env::current_dir().ok().map(|cwd| cwd.join(path))
            }
        })
        .unwrap_or_else(|| path.to_path_buf());
    let mut posix = abs.to_string_lossy().replace('\\', "/");
    if let Some(rest) = posix.strip_prefix("//?/") {
        posix = rest.to_string();
    }
    if posix.starts_with('/') {
        format!("file://{posix}")
    } else {
        format!("file:///{posix}")
    }
}

fn hook_library_json(hook: &Path) -> Value {
    json!({
        "name": HOOK_NAME,
        "downloadable": false,
        "include_in_classpath": true,
        "downloads": {
            "artifact": {
                "path": "dev/celeste/hook/1.0.0/hook-1.0.0.jar",
                "sha1": HOOK_SHA1,
                "size": HOOK_JAR.len(),
                "url": hook_file_url(hook)
            }
        }
    })
}

fn set_main_class(value: &mut Value) {
    value["mainClass"] = json!(HOOK_MAIN);
}

fn add_hook_library(value: &mut Value, hook: &Path) {
    if let Some(root) = value.as_object_mut() {
        let libraries = root.entry("libraries").or_insert_with(|| json!([]));
        if let Some(libraries) = libraries.as_array_mut() {
            libraries.retain(|item| item.get("name").and_then(Value::as_str) != Some(HOOK_NAME));
            libraries.push(hook_library_json(hook));
        }
    }
}

fn edit_launcher_files(
    launcher: &DetectedLauncher,
    instance: &GameInstance,
    hook: &Path,
) -> Result<()> {
    match launcher.kind {
        LauncherKind::Prism | LauncherKind::MultiMc | LauncherKind::PolyMc => {
            edit_prism_component(instance, hook)
        }
        LauncherKind::Modrinth => edit_modrinth_version(&launcher.data_dir, instance, hook),
        LauncherKind::Official | LauncherKind::Fabric | LauncherKind::SkLauncher => {
            edit_official(&launcher.data_dir, instance, hook)
        }
        LauncherKind::AtLauncher => edit_atlauncher(instance, hook),
        LauncherKind::CurseForge => edit_curseforge(instance, hook),
        LauncherKind::GdLauncher => edit_gdlauncher(instance, hook),
        LauncherKind::Dawn
        | LauncherKind::Lunar
        | LauncherKind::Badlion
        | LauncherKind::Feather
        | LauncherKind::LabyMod => {
            bail!("{} cannot load Fabric addMods", launcher.label())
        }
    }
}

fn prism_instance_root(instance: &GameInstance) -> PathBuf {
    let name = instance
        .game_dir
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("");
    if name.eq_ignore_ascii_case("minecraft") || name.eq_ignore_ascii_case(".minecraft") {
        instance
            .game_dir
            .parent()
            .unwrap_or(&instance.game_dir)
            .to_path_buf()
    } else {
        instance.game_dir.clone()
    }
}

pub(crate) fn legacy_binding_root(
    launcher: &DetectedLauncher,
    instance: &GameInstance,
) -> Option<PathBuf> {
    if !matches!(
        launcher.kind,
        LauncherKind::Prism | LauncherKind::MultiMc | LauncherKind::PolyMc
    ) {
        return None;
    }
    let root = prism_instance_root(instance);
    (root != instance.game_dir).then_some(root)
}

fn edit_prism_component(instance: &GameInstance, hook: &Path) -> Result<()> {
    let root = prism_instance_root(instance);
    let patches = root.join("patches");
    fs::create_dir_all(&patches)?;
    let _ = hook;
    let patch = json!({
        "formatVersion": 1,
        "name": "Celeste",
        "uid": HOOK_UID,
        "version": "1.0.0",
        "mainClass": HOOK_MAIN,
        "+libraries": [{
            "name": HOOK_NAME,
            "MMC-hint": "local",
            "MMC-filename": "celeste-hook.jar"
        }]
    });
    let pack_path = root.join("mmc-pack.json");
    let mut pack: Value = if pack_path.is_file() {
        serde_json::from_str(&fs::read_to_string(&pack_path)?)?
    } else {
        json!({ "components": [], "formatVersion": 1 })
    };
    let components = pack
        .as_object_mut()
        .map(|object| object.entry("components").or_insert_with(|| json!([])))
        .and_then(Value::as_array_mut)
        .ok_or_else(|| {
            anyhow!("Prism instance has an invalid component list; no configuration was replaced")
        })?;
    let mut component = components
        .iter()
        .find(|item| item.get("uid").and_then(Value::as_str) == Some(HOOK_UID))
        .cloned()
        .unwrap_or_else(|| json!({"uid":HOOK_UID}));
    components.retain(|item| item.get("uid").and_then(Value::as_str) != Some(HOOK_UID));
    component["cachedName"] = json!("Celeste");
    component["cachedVersion"] = json!("1.0.0");
    component["disabled"] = json!(false);
    // Prism applies mainClass in component order. Reinstalling must repair a
    // disabled or reordered Celeste component, after Fabric's mainClass.
    components.push(component);
    crate::atomic_write(
        &patches.join("dev.celeste.loader.json"),
        &serde_json::to_vec_pretty(&patch)?,
    )?;
    crate::atomic_write(&pack_path, &serde_json::to_vec_pretty(&pack)?)?;
    Ok(())
}

fn edit_modrinth_version(data: &Path, instance: &GameInstance, hook: &Path) -> Result<()> {
    let versions = data.join("meta").join("versions");
    let game_version = instance.version.trim();
    let patched = if game_version.is_empty() {
        false
    } else {
        patch_version_tree(&versions, hook, Some(game_version))?
    };
    if !patched {
        let fallback = instance.game_dir.join("celeste-hook-version.json");
        let mut value = json!({
            "id": "celeste-hook",
            "mainClass": HOOK_MAIN,
            "libraries": [hook_library_json(hook)]
        });
        set_main_class(&mut value);
        fs::write(fallback, serde_json::to_vec_pretty(&value)?)?;
    }
    let db = data.join("app.db");
    if db.is_file() {
        if let Ok(connection) = rusqlite::Connection::open(&db) {
            let _ = connection.busy_timeout(Duration::from_secs(5));
            if let Ok(existing) = connection.query_row(
                "SELECT json(overrides) FROM instance_launch_overrides WHERE instance_id = ?1",
                [&instance.id],
                |row| row.get::<_, String>(0),
            ) {
                if let Ok(mut value) = serde_json::from_str::<Value>(&existing) {
                    if let Some(args) = value
                        .get_mut("extra_launch_args")
                        .and_then(Value::as_array_mut)
                    {
                        args.retain(|item| {
                            item.as_str()
                                .map(|text| !text.starts_with(ADD_MODS))
                                .unwrap_or(true)
                        });
                        if args.is_empty() {
                            value["extra_launch_args"] = Value::Null;
                        }
                    }
                    if value
                        .get("java_path")
                        .and_then(Value::as_str)
                        .map(|text| text.contains("Celeste"))
                        .unwrap_or(false)
                    {
                        value["java_path"] = Value::Null;
                    }
                    let encoded = serde_json::to_string(&value).unwrap_or_else(|_| existing);
                    let _ = connection.execute(
                        "UPDATE instance_launch_overrides SET overrides = jsonb(?2) WHERE instance_id = ?1",
                        rusqlite::params![&instance.id, encoded],
                    );
                }
            }
        }
    }
    Ok(())
}

fn patch_version_tree(root: &Path, hook: &Path, game_version: Option<&str>) -> Result<bool> {
    let mut patched = false;
    let mut stack = vec![root.to_path_buf()];
    let version_prefix = game_version
        .filter(|value| !value.is_empty())
        .map(|value| format!("{value}-"));
    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                if dir == root {
                    if let Some(prefix) = version_prefix.as_deref() {
                        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
                        let version = game_version.unwrap_or("");
                        if name != version && !name.starts_with(prefix) {
                            continue;
                        }
                    }
                }
                stack.push(path);
                continue;
            }
            if path.extension().and_then(|ext| ext.to_str()) != Some("json") {
                continue;
            }
            let Ok(text) = fs::read_to_string(&path) else {
                continue;
            };
            if !text.contains("KnotClient") && !text.contains("fabric") {
                continue;
            }
            let Ok(mut value) = serde_json::from_str::<Value>(&text) else {
                continue;
            };
            let main = value.get("mainClass").and_then(Value::as_str).unwrap_or("");
            if main.contains("KnotClient") || main == HOOK_MAIN {
                set_main_class(&mut value);
                add_hook_library(&mut value, hook);
                fs::write(&path, serde_json::to_vec_pretty(&value)?)?;
                patched = true;
            }
        }
    }
    Ok(patched)
}

fn edit_official(minecraft: &Path, instance: &GameInstance, hook: &Path) -> Result<()> {
    let mut version_id = instance.version.clone();
    for name in [
        "launcher_profiles.json",
        "launcher_profiles_microsoft_store.json",
    ] {
        let path = minecraft.join(name);
        if !path.is_file() {
            continue;
        }
        let mut value: Value = serde_json::from_str(&fs::read_to_string(&path)?)?;
        if let Some(id) = value
            .get("profiles")
            .and_then(Value::as_object)
            .and_then(|profiles| profiles.get(&instance.id))
            .and_then(|profile| profile.get("lastVersionId"))
            .and_then(Value::as_str)
        {
            version_id = id.to_owned();
        }
        let mut dirty = false;
        if let Some(profile) = value
            .get_mut("profiles")
            .and_then(Value::as_object_mut)
            .and_then(|profiles| profiles.get_mut(&instance.id))
        {
            if let Some(args) = profile.get("javaArgs").and_then(Value::as_str) {
                if args.contains(ADD_MODS) {
                    let cleaned = strip_add_mods(args);
                    if cleaned.is_empty() {
                        profile
                            .as_object_mut()
                            .map(|object| object.remove("javaArgs"));
                    } else {
                        profile["javaArgs"] = json!(cleaned);
                    }
                    dirty = true;
                }
            }
        }
        if dirty {
            fs::write(&path, serde_json::to_vec_pretty(&value)?)?;
        }
    }
    let json_path = minecraft
        .join("versions")
        .join(&version_id)
        .join(format!("{version_id}.json"));
    if json_path.is_file() {
        let mut value: Value = serde_json::from_str(&fs::read_to_string(&json_path)?)?;
        set_main_class(&mut value);
        add_hook_library(&mut value, hook);
        if let Some(root) = value.as_object_mut() {
            if let Some(arguments) = root.get_mut("arguments").and_then(Value::as_object_mut) {
                if let Some(jvm) = arguments.get_mut("jvm").and_then(Value::as_array_mut) {
                    jvm.retain(|item| {
                        item.as_str()
                            .map(|text| !text.starts_with(ADD_MODS))
                            .unwrap_or(true)
                    });
                }
            }
        }
        fs::write(&json_path, serde_json::to_vec_pretty(&value)?)?;
    } else {
        let cid = format!("celeste-{version_id}");
        let dest_dir = minecraft.join("versions").join(&cid);
        fs::create_dir_all(&dest_dir)?;
        let dest = dest_dir.join(format!("{cid}.json"));
        let mut value = json!({
            "id": cid,
            "inheritsFrom": version_id,
            "type": "release",
            "mainClass": HOOK_MAIN,
            "libraries": [hook_library_json(hook)]
        });
        set_main_class(&mut value);
        fs::write(&dest, serde_json::to_vec_pretty(&value)?)?;
        point_profile_version(minecraft, &instance.id, &cid)?;
    }
    Ok(())
}

fn point_profile_version(minecraft: &Path, profile_id: &str, version_id: &str) -> Result<()> {
    for name in [
        "launcher_profiles.json",
        "launcher_profiles_microsoft_store.json",
    ] {
        let path = minecraft.join(name);
        if !path.is_file() {
            continue;
        }
        let mut value: Value = serde_json::from_str(&fs::read_to_string(&path)?)?;
        if let Some(profile) = value
            .get_mut("profiles")
            .and_then(Value::as_object_mut)
            .and_then(|profiles| profiles.get_mut(profile_id))
        {
            profile["lastVersionId"] = json!(version_id);
            fs::write(&path, serde_json::to_vec_pretty(&value)?)?;
        }
    }
    Ok(())
}

fn edit_atlauncher(instance: &GameInstance, hook: &Path) -> Result<()> {
    let path = instance.game_dir.join("instance.json");
    if !path.is_file() {
        bail!("ATLauncher instance.json is missing");
    }
    let mut value: Value = serde_json::from_str(&fs::read_to_string(&path)?)?;
    set_main_class(&mut value);
    add_hook_library(&mut value, hook);
    if let Some(launcher) = value.get_mut("launcher") {
        launcher["mainClass"] = json!(HOOK_MAIN);
        if let Some(args) = launcher.get("javaArgs").and_then(Value::as_str) {
            if args.contains(ADD_MODS) {
                let cleaned = strip_add_mods(args);
                if cleaned.is_empty() {
                    launcher
                        .as_object_mut()
                        .map(|object| object.remove("javaArgs"));
                } else {
                    launcher["javaArgs"] = json!(cleaned);
                }
            }
        }
        if launcher
            .get("javaPath")
            .and_then(Value::as_str)
            .map(|text| text.contains("Celeste"))
            .unwrap_or(false)
        {
            launcher
                .as_object_mut()
                .map(|object| object.remove("javaPath"));
        }
    }
    fs::write(&path, serde_json::to_vec_pretty(&value)?)?;
    Ok(())
}

fn edit_curseforge(instance: &GameInstance, hook: &Path) -> Result<()> {
    let path = instance.game_dir.join("minecraftinstance.json");
    if !path.is_file() {
        bail!("CurseForge minecraftinstance.json is missing");
    }
    let mut value: Value = serde_json::from_str(&fs::read_to_string(&path)?)?;
    value["mainClass"] = json!(HOOK_MAIN);
    if let Some(args) = value.get("javaArgsOverride").and_then(Value::as_str) {
        if args.contains(ADD_MODS) {
            let cleaned = strip_add_mods(args);
            if cleaned.is_empty() {
                value
                    .as_object_mut()
                    .map(|object| object.remove("javaArgsOverride"));
            } else {
                value["javaArgsOverride"] = json!(cleaned);
            }
        }
    }
    if value
        .get("javaPathOverride")
        .and_then(Value::as_str)
        .map(|text| text.contains("Celeste"))
        .unwrap_or(false)
    {
        value
            .as_object_mut()
            .map(|object| object.remove("javaPathOverride"));
    }
    fs::write(&path, serde_json::to_vec_pretty(&value)?)?;
    let version_json = instance.game_dir.join("version.json");
    if version_json.is_file() {
        if let Ok(mut version) = serde_json::from_str::<Value>(&fs::read_to_string(&version_json)?)
        {
            set_main_class(&mut version);
            add_hook_library(&mut version, hook);
            fs::write(&version_json, serde_json::to_vec_pretty(&version)?)?;
        }
    }
    Ok(())
}

fn edit_gdlauncher(instance: &GameInstance, hook: &Path) -> Result<()> {
    for file in ["instance.json", "config.json"] {
        let path = instance.game_dir.join(file);
        if !path.is_file() {
            continue;
        }
        let mut value: Value = serde_json::from_str(&fs::read_to_string(&path)?)?;
        set_main_class(&mut value);
        add_hook_library(&mut value, hook);
        for key in ["extra_java_args", "javaArgs", "jvmArgs"] {
            if let Some(args) = value.get(key).and_then(Value::as_str) {
                if args.contains(ADD_MODS) {
                    let cleaned = strip_add_mods(args);
                    if cleaned.is_empty() {
                        value.as_object_mut().map(|object| object.remove(key));
                    } else {
                        value[key] = json!(cleaned);
                    }
                }
            }
        }
        fs::write(&path, serde_json::to_vec_pretty(&value)?)?;
        return Ok(());
    }
    bail!("GDLauncher instance config is missing")
}

fn strip_add_mods(existing: &str) -> String {
    existing
        .split_whitespace()
        .map(|part| part.trim_matches('"').to_string())
        .filter(|part| !part.is_empty() && !part.starts_with(ADD_MODS))
        .collect::<Vec<_>>()
        .join(" ")
}

fn launch_modrinth(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<()> {
    let url = format!("modrinth://launch/instance/{}", urlencode(&instance.id));
    if let Some(exe) = &launcher.exe {
        let _ = spawn(exe, &[url.as_str()]);
        return Ok(());
    }
    bail!("Modrinth App executable was not found")
}

include!("launchers_closed.rs");

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn fixture(name: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        std::env::temp_dir().join(format!(
            "celeste-launcher-{name}-{}-{nonce}",
            std::process::id()
        ))
    }

    #[test]
    fn fresh_prism_instance_keeps_game_directory_stable_after_first_start() {
        let root = fixture("prism-fresh");
        let instance_root = root.join("instances").join("fresh");
        fs::create_dir_all(&instance_root).unwrap();
        fs::write(instance_root.join("mmc-pack.json"), serde_json::to_vec(&json!({
            "formatVersion":1,
            "components":[{"uid":"net.minecraft","version":"1.21.11"},{"uid":"net.fabricmc.fabric-loader","version":"0.18.4"}]
        })).unwrap()).unwrap();
        let before = prism_like_instances(&root).pop().unwrap();
        let expected = instance_root.join("minecraft");
        assert_eq!(
            before.game_dir, expected,
            "Prism uses minecraft even before it exists"
        );
        fs::create_dir_all(&expected).unwrap();
        let after = prism_like_instances(&root).pop().unwrap();
        assert_eq!(
            before.game_dir, after.game_dir,
            "activation and loader paths must survive the first launch"
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn prism_preserves_existing_dotted_minecraft_directory() {
        let root = fixture("prism-dotted");
        let dotted = root.join(".minecraft");
        fs::create_dir_all(&dotted).unwrap();
        assert_eq!(prism_game_dir(&root), dotted);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn prism_legacy_binding_root_is_accessible_before_game_directory_exists() {
        let root = fixture("prism-legacy-binding");
        fs::create_dir_all(&root).unwrap();
        let launcher = DetectedLauncher {
            kind: LauncherKind::Prism,
            exe: None,
            data_dir: root.clone(),
        };
        let instance = game_instance(
            "fixture".into(),
            "Fabric 1.21.11".into(),
            root.join("minecraft"),
            "1.21.11".into(),
            "fabric".into(),
        );
        let recovered = legacy_binding_root(&launcher, &instance).unwrap();
        assert_eq!(recovered, root);
        assert!(
            recovered.is_dir(),
            "old activation must be discoverable without creating minecraft first"
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn prism_reinstall_enables_and_applies_celeste_after_fabric() {
        let root = fixture("prism-repair");
        let game = root.join("minecraft");
        fs::create_dir_all(&game).unwrap();
        let original = json!({
            "formatVersion":1,
            "components":[
                {"uid":HOOK_UID,"disabled":true,"cachedName":"Old Celeste"},
                {"uid":"net.minecraft","version":"1.21.11"},
                {"uid":"net.fabricmc.fabric-loader","version":"0.18.4","important":true}
            ]
        });
        fs::write(
            root.join("mmc-pack.json"),
            serde_json::to_vec(&original).unwrap(),
        )
        .unwrap();
        let instance = game_instance(
            "fixture".into(),
            "Fabric 1.21.11".into(),
            game,
            "1.21.11".into(),
            "fabric".into(),
        );
        let hook = root.join("libraries").join("celeste-hook.jar");
        fs::create_dir_all(hook.parent().unwrap()).unwrap();
        fs::write(&hook, HOOK_JAR).unwrap();
        for _ in 0..2 {
            edit_prism_component(&instance, &hook).unwrap();
        }
        let pack: Value =
            serde_json::from_slice(&fs::read(root.join("mmc-pack.json")).unwrap()).unwrap();
        let components = pack["components"].as_array().unwrap();
        assert_eq!(
            components.len(),
            3,
            "repeat install must not duplicate components"
        );
        assert_eq!(components[0], original["components"][1]);
        assert_eq!(components[1], original["components"][2]);
        assert_eq!(components[2]["uid"], HOOK_UID);
        assert_eq!(components[2]["disabled"], false);
        let patch: Value = serde_json::from_slice(
            &fs::read(root.join("patches").join("dev.celeste.loader.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(patch["mainClass"], HOOK_MAIN);
        let library = &patch["+libraries"][0];
        assert_eq!(library["MMC-hint"], "local");
        assert_eq!(
            fs::read(
                root.join("libraries")
                    .join(library["MMC-filename"].as_str().unwrap())
            )
            .unwrap(),
            HOOK_JAR
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn lunar_fabric_profile_uses_its_supported_mod_folder() {
        let root = fixture("lunar");
        let profile = root.join("profiles").join("lunar").join("1.21");
        let mods = profile.join("mods").join("fabric-1.21.11");
        fs::create_dir_all(&mods).expect("fixture");

        let instances = lunar_instances(&root);
        let instance = preferred_instances(&instances)
            .into_iter()
            .next()
            .expect("Fabric 1.21.11 profile");
        let launcher = DetectedLauncher {
            kind: LauncherKind::Lunar,
            exe: None,
            data_dir: root.clone(),
        };
        let output = install_and_patch(&launcher, &instance, b"loader").expect("install");

        assert_eq!(output, mods.join(LOADER_JAR_NAME));
        assert_eq!(fs::read(output).expect("loader"), b"loader");
        fs::remove_dir_all(&root).expect("cleanup");
    }

    #[test]
    fn badlion_is_never_treated_as_a_supported_fabric_host() {
        let root = fixture("badlion");
        let launcher = DetectedLauncher {
            kind: LauncherKind::Badlion,
            exe: None,
            data_dir: root.clone(),
        };
        let instance = game_instance(
            "test".to_owned(),
            "Fabric 1.21.11".to_owned(),
            root,
            "1.21.11".to_owned(),
            "fabric".to_owned(),
        );

        assert!(!launcher.supported());
        assert!(install_and_patch(&launcher, &instance, b"loader").is_err());
    }
}
