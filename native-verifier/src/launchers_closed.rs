fn write_hidden_loader(game_dir: &Path, loader: &[u8]) -> Result<PathBuf> {
    let mods_dir = hidden_mods_dir(game_dir);
    fs::create_dir_all(&mods_dir)?;
    let jar = mods_dir.join(LOADER_JAR_NAME);
    crate::atomic_write(&jar, loader)?;
    let pointer = game_dir
        .join("config")
        .join("celeste")
        .join("mods.dir");
    crate::atomic_write(
        &pointer,
        mods_dir.display().to_string().replace('\\', "/").as_bytes(),
    )?;
    purge_celeste_loader_jars(game_dir, &[&mods_dir]);
    Ok(jar)
}

fn kind_from_slug(slug: &str) -> Option<LauncherKind> {
    Some(match slug {
        "official" => LauncherKind::Official,
        "modrinth" => LauncherKind::Modrinth,
        "prism" => LauncherKind::Prism,
        "fabric" => LauncherKind::Fabric,
        "dawn" => LauncherKind::Dawn,
        "lunar" => LauncherKind::Lunar,
        "badlion" => LauncherKind::Badlion,
        "sklauncher" => LauncherKind::SkLauncher,
        "multimc" => LauncherKind::MultiMc,
        "polymc" => LauncherKind::PolyMc,
        "atlauncher" => LauncherKind::AtLauncher,
        "curseforge" => LauncherKind::CurseForge,
        "gdlauncher" => LauncherKind::GdLauncher,
        _ => return None,
    })
}

fn game_instance(
    id: String,
    name: String,
    game_dir: PathBuf,
    version: String,
    loader: String,
) -> GameInstance {
    GameInstance {
        id,
        name,
        game_dir,
        version,
        loader,
        host: None,
    }
}

fn redirect_path(instance: &GameInstance) -> PathBuf {
    instance
        .game_dir
        .join("config")
        .join("celeste")
        .join("launch.via")
}

fn read_redirect(instance: &GameInstance) -> Option<(DetectedLauncher, GameInstance)> {
    let text = fs::read_to_string(redirect_path(instance)).ok()?;
    let value: Value = serde_json::from_str(&text).ok()?;
    let kind = kind_from_slug(value.get("kind")?.as_str()?)?;
    let exe = value
        .get("exe")
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .map(PathBuf::from);
    let data_dir = PathBuf::from(value.get("data_dir")?.as_str()?);
    let host = DetectedLauncher { kind, exe, data_dir };
    let host_inst = game_instance(
        value.get("id")?.as_str()?.to_owned(),
        value.get("name")?.as_str()?.to_owned(),
        PathBuf::from(value.get("game_dir")?.as_str()?),
        value.get("version").and_then(Value::as_str).unwrap_or("").to_owned(),
        value.get("loader").and_then(Value::as_str).unwrap_or("").to_owned(),
    );
    Some((host, host_inst))
}

fn launch_kind(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<()> {
    match launcher.kind {
        LauncherKind::Prism | LauncherKind::MultiMc | LauncherKind::PolyMc => {
            let exe = launcher
                .exe
                .as_ref()
                .ok_or_else(|| anyhow!("{} executable was not found", launcher.label()))?;
            spawn(exe, &["--launch", &instance.id])
        }
        LauncherKind::Modrinth => launch_modrinth(launcher, instance),
        LauncherKind::Official | LauncherKind::Fabric | LauncherKind::SkLauncher => {
            let exe = launcher
                .exe
                .as_ref()
                .ok_or_else(|| anyhow!("Minecraft launcher executable was not found"))?;
            spawn(exe, &[])
        }
        LauncherKind::AtLauncher => {
            let exe = launcher
                .exe
                .as_ref()
                .ok_or_else(|| anyhow!("ATLauncher executable was not found"))?;
            spawn(exe, &["--launch", &instance.id])
        }
        LauncherKind::CurseForge | LauncherKind::GdLauncher => {
            let exe = launcher
                .exe
                .as_ref()
                .ok_or_else(|| anyhow!("{} executable was not found", launcher.label()))?;
            spawn(exe, &[])
        }
        LauncherKind::Dawn => launch_dawn(launcher, instance),
        LauncherKind::Lunar => launch_lunar(launcher, instance),
        LauncherKind::Badlion => launch_closed(launcher, "Badlion Client executable was not found"),
        LauncherKind::Feather | LauncherKind::LabyMod => {
            bail!("{} is unsupported", launcher.label())
        }
    }
}

fn launch_closed(launcher: &DetectedLauncher, missing: &str) -> Result<()> {
    let exe = launcher.exe.as_ref().ok_or_else(|| anyhow!("{missing}"))?;
    spawn(exe, &[])
}

fn launch_dawn(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<()> {
    let exe = launcher
        .exe
        .as_ref()
        .ok_or_else(|| anyhow!("Dawn executable was not found"))?;
    if spawn(exe, &["--launch", &instance.id]).is_err() {
        spawn(exe, &[])?;
    }
    Ok(())
}

fn launch_lunar(launcher: &DetectedLauncher, instance: &GameInstance) -> Result<()> {
    select_lunar_profile(launcher, instance);
    launch_closed(launcher, "Lunar Client executable was not found")
}

fn find_exe(paths: &[PathBuf]) -> Option<PathBuf> {
    first_file(paths)
}

fn scan_named_exes(dirs: &[PathBuf], needles: &[&str]) -> Option<PathBuf> {
    if let Some(found) = first_file(
        &dirs
            .iter()
            .flat_map(|dir| needles.iter().map(|name| dir.join(name)))
            .collect::<Vec<_>>(),
    ) {
        return Some(found);
    }
    for dir in dirs {
        let Ok(entries) = fs::read_dir(dir) else { continue; };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = path
                .file_name()
                .and_then(|value| value.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            if path.extension().and_then(|ext| ext.to_str()).map(|ext| ext.eq_ignore_ascii_case("exe")) != Some(true) {
                continue;
            }
            if needles.iter().any(|needle| name.contains(&needle.to_ascii_lowercase().replace(".exe", ""))) {
                return Some(path);
            }
        }
    }
    None
}

fn dawn_detect() -> Option<DetectedLauncher> {
    let dirs = vec![
        appdata().join(".dawn"),
        userprofile().join(".dawn"),
        appdata().join("Dawn"),
        appdata().join("DawnLauncher"),
        local_appdata().join("Dawn"),
        local_appdata().join("Dawn").join("Dawn (Feather)"),
        appdata().join(".feather"),
        userprofile().join(".feather"),
        appdata().join("Feather Launcher"),
    ];
    let data = first_dir(&dirs)?;
    let exe = scan_named_exes(
        &[
            local_appdata().join("Dawn"),
            local_appdata().join("Dawn").join("Dawn (Feather)"),
            local_appdata().join("Programs").join("Dawn"),
            local_appdata().join("Programs").join("Dawn (Feather)"),
            appdata().join("Dawn"),
            PathBuf::from(r"C:\Program Files\Dawn"),
            PathBuf::from(r"C:\Program Files\Dawn (Feather)"),
        ],
        &[
            "Dawn.exe",
            "Dawn (Feather).exe",
            "DawnLauncher.exe",
            "Feather Launcher.exe",
        ],
    );
    Some(DetectedLauncher { kind: LauncherKind::Dawn, exe, data_dir: data })
}

fn lunar_detect() -> Option<DetectedLauncher> {
    let dirs = vec![
        userprofile().join(".lunarclient"),
        appdata().join(".lunarclient"),
        local_appdata().join("LunarClient"),
        local_appdata().join("Programs").join("lunarclient"),
    ];
    let data = first_dir(&dirs)?;
    let exe = find_exe(&[
        local_appdata().join("Programs").join("lunarclient").join("Lunar Client.exe"),
        local_appdata().join("lunarclient").join("Lunar Client.exe"),
        PathBuf::from(r"C:\Program Files\Lunar Client\Lunar Client.exe"),
        PathBuf::from(r"C:\Program Files (x86)\Lunar Client\Lunar Client.exe"),
    ]);
    Some(DetectedLauncher { kind: LauncherKind::Lunar, exe, data_dir: data })
}

fn badlion_detect() -> Option<DetectedLauncher> {
    let dirs = vec![
        appdata().join("Badlion Client"),
        local_appdata().join("Badlion Client"),
        local_appdata().join("Programs").join("Badlion Client"),
    ];
    let data = first_dir(&dirs)?;
    let exe = find_exe(&[
        PathBuf::from(r"C:\Program Files\Badlion Client\Badlion Client.exe"),
        PathBuf::from(r"C:\Program Files (x86)\Badlion Client\Badlion Client.exe"),
        local_appdata().join("Badlion Client").join("Badlion Client.exe"),
        local_appdata().join("Programs").join("Badlion Client").join("Badlion Client.exe"),
        appdata().join("Badlion Client").join("Badlion Client.exe"),
    ]);
    Some(DetectedLauncher { kind: LauncherKind::Badlion, exe, data_dir: data })
}

fn dawn_instances(data: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    let mut seen = Vec::new();
    for folder in ["profiles", "instances"] {
        collect_profile_instances(&data.join(folder), &mut instances, &mut seen);
    }
    collect_profile_instances(data, &mut instances, &mut seen);
    instances
}

fn collect_profile_instances(root: &Path, instances: &mut Vec<GameInstance>, seen: &mut Vec<String>) {
    let Ok(entries) = fs::read_dir(root) else { return; };
    for entry in entries.flatten() {
        if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.starts_with('.') {
            continue;
        }
        let skip = [
            "libraries", "assets", "versions", "meta", "cache", "caches", "java", "jre",
            "logs", "icons", "translations", "themes", "catpacks", "iconthemes",
        ];
        if skip.iter().any(|item| name.eq_ignore_ascii_case(item)) {
            continue;
        }
        let dir = entry.path();
        let key = dir.to_string_lossy().into_owned();
        if seen.iter().any(|item| item == &key) {
            continue;
        }
        seen.push(key);
        let (version, loader, display, game_dir) = dawn_meta(&dir, &name);
        instances.push(game_instance(name, display, game_dir, version, loader));
    }
}

fn dawn_meta(dir: &Path, fallback: &str) -> (String, String, String, PathBuf) {
    let game_dir = if dir.join(".minecraft").is_dir() {
        dir.join(".minecraft")
    } else if dir.join("minecraft").is_dir() {
        dir.join("minecraft")
    } else {
        dir.to_path_buf()
    };
    let mut version = String::new();
    let mut loader = String::new();
    let mut name = fallback.to_owned();
    for file in [
        "instance.json",
        "profile.json",
        "metadata.json",
        "mmc-pack.json",
        "config.json",
        "pack.json",
    ] {
        for root in [dir.to_path_buf(), game_dir.clone()] {
            let Ok(text) = fs::read_to_string(root.join(file)) else { continue; };
            let Ok(value) = serde_json::from_str::<Value>(&text) else { continue; };
            if let Some(text) = value.get("name").and_then(Value::as_str) {
                if !text.is_empty() {
                    name = text.to_owned();
                }
            }
            version = first_str(
                &value,
                &[
                    "/minecraftVersion",
                    "/gameVersion",
                    "/mcVersion",
                    "/lastVersionId",
                    "/version",
                    "/id",
                    "/runtime/minecraft",
                    "/runtime/version",
                ],
            )
            .unwrap_or(version);
            loader = first_str(
                &value,
                &[
                    "/loader",
                    "/modLoader",
                    "/loaderType",
                    "/modloader",
                    "/runtime/loader",
                    "/loaderVersion/type",
                ],
            )
            .unwrap_or(loader);
            if file == "mmc-pack.json" {
                if let Some(components) = value.get("components").and_then(Value::as_array) {
                    for component in components {
                        let uid = component.get("uid").and_then(Value::as_str).unwrap_or("");
                        let ver = component.get("version").and_then(Value::as_str).unwrap_or("");
                        if uid == "net.minecraft" {
                            version = ver.to_owned();
                        }
                        if uid.contains("fabric") {
                            loader = format!("fabric {ver}");
                        }
                    }
                }
            }
        }
    }
    if loader.is_empty() {
        let hay = format!("{fallback} {version} {name}").to_ascii_lowercase();
        if hay.contains("fabric") || game_dir.join(".fabric").is_dir() || dir.join(".fabric").is_dir() {
            loader = "fabric".to_owned();
        } else if hay.contains("forge") {
            loader = "forge".to_owned();
        } else if hay.contains("quilt") {
            loader = "quilt".to_owned();
        }
    }
    if version.is_empty() {
        version = fallback.to_owned();
    }
    (version, loader, name, game_dir)
}

fn lunar_instances(data: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    let mut seen = Vec::new();
    let profiles = data.join("profiles").join("lunar");
    if let Ok(entries) = fs::read_dir(&profiles) {
        for entry in entries.flatten() {
            if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
                continue;
            }
            let version = entry.file_name().to_string_lossy().into_owned();
            let dir = entry.path();
            let game_dir = if dir.join(".minecraft").is_dir() {
                dir.join(".minecraft")
            } else {
                dir.clone()
            };
            let mut loader = String::new();
            if let Ok(mods) = fs::read_dir(dir.join("mods")) {
                for item in mods.flatten() {
                    let name = item.file_name().to_string_lossy().into_owned();
                    if item.file_type().map(|kind| kind.is_dir()).unwrap_or(false)
                        && name.to_ascii_lowercase().contains("fabric")
                    {
                        loader = format!("fabric {name}");
                    }
                }
            }
            if loader.is_empty() && (game_dir.join(".fabric").is_dir() || dir.join(".fabric").is_dir()) {
                loader = "fabric".to_owned();
            }
            seen.push(game_dir.to_string_lossy().to_ascii_lowercase());
            instances.push(game_instance(
                version.clone(),
                format!("Lunar {version}"),
                game_dir,
                version,
                loader,
            ));
        }
    }

    // Newer Lunar builds store custom profiles by UUID directly below
    // `profiles`, while older builds use `profiles/lunar/<version>`.
    let profile_root = data.join("profiles");
    if let Ok(entries) = fs::read_dir(&profile_root) {
        for entry in entries.flatten() {
            if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
                continue;
            }
            let fallback = entry.file_name().to_string_lossy().into_owned();
            if fallback.eq_ignore_ascii_case("lunar") || fallback.eq_ignore_ascii_case("vanilla") {
                continue;
            }
            let dir = entry.path();
            let (mut version, mut loader, display, game_dir) = dawn_meta(&dir, &fallback);
            if let Ok(mods) = fs::read_dir(dir.join("mods")) {
                for item in mods.flatten() {
                    if !item.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
                        continue;
                    }
                    let name = item.file_name().to_string_lossy().into_owned();
                    let lower = name.to_ascii_lowercase();
                    if lower.contains("fabric") {
                        loader = format!("fabric {name}");
                        if version.is_empty() || !version.contains("1.21.11") {
                            version = name;
                        }
                    }
                }
            }
            let key = game_dir.to_string_lossy().to_ascii_lowercase();
            if !seen.iter().any(|existing| existing == &key)
                && looks_like_fabric_12111(&version, &loader, &display, &fallback)
            {
                seen.push(key);
                instances.push(game_instance(
                    fallback,
                    display,
                    game_dir,
                    version,
                    loader,
                ));
            }
        }
    }

    for instance in lunar_instances_from_db(&data.join("db").join("profiles.db"), data) {
        let key = instance.game_dir.to_string_lossy().to_ascii_lowercase();
        if !seen.iter().any(|existing| existing == &key) {
            seen.push(key);
            instances.push(instance);
        }
    }
    instances
}

fn lunar_instances_from_db(db: &Path, data: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    if !db.is_file() {
        return instances;
    }
    let Ok(connection) = rusqlite::Connection::open(db) else {
        return instances;
    };
    let _ = connection.busy_timeout(Duration::from_secs(5));
    let Ok(mut tables) = connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'") else {
        return instances;
    };
    let table_names = tables
        .query_map([], |row| row.get::<_, String>(0))
        .ok()
        .map(|rows| rows.flatten().collect::<Vec<_>>())
        .unwrap_or_default();
    drop(tables);
    for table in table_names {
        if !table.chars().all(|ch| ch.is_ascii_alphanumeric() || ch == '_') {
            continue;
        }
        let Ok(mut info) = connection.prepare(&format!("PRAGMA table_info({table})")) else {
            continue;
        };
        let columns = info
            .query_map([], |row| row.get::<_, String>(1))
            .ok()
            .map(|rows| rows.flatten().collect::<Vec<_>>())
            .unwrap_or_default();
        drop(info);
        let lower: Vec<String> = columns.iter().map(|name| name.to_ascii_lowercase()).collect();
        let pick = |needles: &[&str]| {
            lower
                .iter()
                .position(|name| needles.iter().any(|needle| name.contains(needle)))
                .map(|index| columns[index].clone())
        };
        let Some(id_col) = pick(&["id", "uuid", "profile"]) else { continue; };
        let name_col = pick(&["name", "title", "label"]).unwrap_or_else(|| id_col.clone());
        let version_col = pick(&["version", "minecraft", "mc"]);
        let loader_col = pick(&["loader", "fabric", "addon"]);
        let dir_col = pick(&["dir", "path", "folder", "gamedir"]);
        let mut sql = format!("SELECT {id_col}, {name_col}");
        if let Some(column) = &version_col {
            sql.push_str(", ");
            sql.push_str(column);
        }
        if let Some(column) = &loader_col {
            sql.push_str(", ");
            sql.push_str(column);
        }
        if let Some(column) = &dir_col {
            sql.push_str(", ");
            sql.push_str(column);
        }
        sql.push_str(" FROM ");
        sql.push_str(&table);
        let Ok(mut statement) = connection.prepare(&sql) else { continue; };
        let width = 2
            + version_col.is_some() as usize
            + loader_col.is_some() as usize
            + dir_col.is_some() as usize;
        let rows = statement.query_map([], |row| {
            let mut values = Vec::new();
            for index in 0..width {
                values.push(row.get::<_, Option<String>>(index)?.unwrap_or_default());
            }
            Ok(values)
        });
        let Ok(rows) = rows else { continue; };
        for row in rows.flatten() {
            let id = row.first().cloned().unwrap_or_default();
            if id.is_empty() {
                continue;
            }
            let name = row.get(1).cloned().unwrap_or_else(|| id.clone());
            let mut index = 2;
            let version = if version_col.is_some() {
                let value = row.get(index).cloned().unwrap_or_default();
                index += 1;
                value
            } else {
                String::new()
            };
            let loader = if loader_col.is_some() {
                let value = row.get(index).cloned().unwrap_or_default();
                index += 1;
                value
            } else {
                String::new()
            };
            let game_dir = if dir_col.is_some() {
                let value = row.get(index).cloned().unwrap_or_default();
                if value.is_empty() {
                    data.to_path_buf()
                } else {
                    PathBuf::from(value)
                }
            } else {
                data.join("profiles").join("lunar").join(&version)
            };
            instances.push(game_instance(id, name, game_dir, version, loader));
        }
    }
    instances
}

fn badlion_instances(data: &Path) -> Vec<GameInstance> {
    let mut instances = Vec::new();
    for root in [data.to_path_buf(), appdata().join(".minecraft")] {
        if root.is_dir() {
            instances.extend(official_instances(&root));
        }
    }
    instances
}

fn select_lunar_profile(launcher: &DetectedLauncher, instance: &GameInstance) {
    let path = launcher.data_dir.join("settings").join("launcher.json");
    let Ok(text) = fs::read_to_string(&path) else { return; };
    let Ok(mut value) = serde_json::from_str::<Value>(&text) else { return; };
    if let Some(settings) = value.get_mut("settings").and_then(Value::as_object_mut) {
        settings.insert("gameProfile".to_owned(), json!(instance.id));
    } else if let Some(root) = value.as_object_mut() {
        root.insert("gameProfile".to_owned(), json!(instance.id));
    }
    let _ = fs::write(&path, serde_json::to_vec_pretty(&value).unwrap_or_else(|_| text.into_bytes()));
}

