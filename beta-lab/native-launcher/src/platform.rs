use anyhow::{Context, Result, bail};
use rusqlite::{Connection, OpenFlags, OptionalExtension};
use std::path::{Path, PathBuf};
use std::{env, fs};
use winapi::shared::minwindef::{BOOL, FILETIME, LPARAM, TRUE};
use winapi::shared::windef::HWND;
use winapi::um::handleapi::CloseHandle;
use winapi::um::processthreadsapi::{GetProcessTimes, OpenProcess};
use winapi::um::winbase::QueryFullProcessImageNameW;
use winapi::um::winnt::PROCESS_QUERY_LIMITED_INFORMATION;
use winapi::um::winuser::{
    EnumWindows, GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible,
};
#[derive(Clone, Debug)]
pub struct MinecraftProcess {
    pub pid: u32,
    pub title: String,
    pub started: u64,
}
fn process_identity(pid: u32) -> Result<(String, u64)> {
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
            return Err(std::io::Error::last_os_error())
                .context(native_protection::text!("Cannot inspect selected process"));
        }
        let result = (|| {
            let mut path = vec![0u16; 32_768];
            let mut length = path.len() as u32;
            if QueryFullProcessImageNameW(handle, 0, path.as_mut_ptr(), &mut length) == 0 {
                return Err(std::io::Error::last_os_error())
                    .context(native_protection::text!("Cannot identify Java executable"));
            }
            let mut created: FILETIME = std::mem::zeroed();
            let mut exited: FILETIME = std::mem::zeroed();
            let mut kernel: FILETIME = std::mem::zeroed();
            let mut user: FILETIME = std::mem::zeroed();
            if GetProcessTimes(handle, &mut created, &mut exited, &mut kernel, &mut user) == 0 {
                return Err(std::io::Error::last_os_error()).context(native_protection::text!(
                    "Cannot verify process creation time"
                ));
            }
            let full = String::from_utf16_lossy(&path[..length as usize]);
            let name = Path::new(&full)
                .file_name()
                .and_then(|v| v.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            Ok((
                name,
                (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime),
            ))
        })();
        CloseHandle(handle);
        result
    }
}
#[doc = " Reads only visible titles, PID, executable basename and process creation time."]
#[doc = " It never reads Java command lines, environment, launcher accounts or tokens."]
pub fn minecraft_processes() -> Vec<MinecraftProcess> {
    unsafe extern "system" fn collect(window: HWND, data: LPARAM) -> BOOL {
        unsafe {
            if IsWindowVisible(window) == 0 {
                return TRUE;
            }
            let length = GetWindowTextLengthW(window);
            if !(1..=4096).contains(&length) {
                return TRUE;
            }
            let mut text = vec![0u16; length as usize + 1];
            let actual = GetWindowTextW(window, text.as_mut_ptr(), text.len() as i32);
            if actual <= 0 {
                return TRUE;
            }
            let title = String::from_utf16_lossy(&text[..actual as usize]);
            if !title
                .to_ascii_lowercase()
                .contains(native_protection::text!("minecraft"))
            {
                return TRUE;
            }
            let mut pid = 0;
            GetWindowThreadProcessId(window, &mut pid);
            if let Ok((name, started)) = process_identity(pid) {
                if name == native_protection::text!("java.exe")
                    || name == native_protection::text!("javaw.exe")
                {
                    let values = &mut *(data as *mut Vec<MinecraftProcess>);
                    if !values.iter().any(|value| value.pid == pid) {
                        values.push(MinecraftProcess {
                            pid,
                            title,
                            started,
                        });
                    }
                }
            }
            TRUE
        }
    }
    let mut values: Vec<MinecraftProcess> = Vec::new();
    unsafe {
        EnumWindows(Some(collect), &mut values as *mut _ as LPARAM);
    }
    values.sort_by_key(|value| value.pid);
    values
}
pub fn recheck_process(target: &MinecraftProcess) -> Result<()> {
    let (name, started) = process_identity(target.pid)?;
    if (name != native_protection::text!("java.exe")
        && name != native_protection::text!("javaw.exe"))
        || started != target.started
    {
        bail!(
            "{}",
            native_protection::text!(
                "Selected Minecraft exited or its PID was reused. Refresh and explicitly select it again"
            )
        );
    }
    Ok(())
}
#[derive(Clone, Debug)]
pub struct GameProfile {
    pub name: String,
    pub game: PathBuf,
}
#[doc = " Queries only nonsecret profile metadata. Opens Modrinth SQLite read-only."]
pub fn modrinth_profiles() -> Result<Vec<GameProfile>> {
    modrinth_profiles_under(&crate::windows_profile::current_roaming_app_data()?)
}
fn modrinth_profiles_under(roaming: &Path) -> Result<Vec<GameProfile>> {
    if !roaming.is_absolute() {
        bail!(
            "{}",
            native_protection::text!(
                "Modrinth discovery requires this account's absolute Roaming AppData path"
            )
        );
    }
    let data = roaming.join(native_protection::text!("ModrinthApp"));
    let database = data.join(native_protection::text!("app.db"));
    match fs::metadata(&database) {
        Ok(metadata) if metadata.is_file() => {}
        Ok(_) => bail!(
            "{}",
            native_protection::text!("Modrinth's profile database path is not a file")
        ),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => {
            return Err(error).context(native_protection::text!(
                "Cannot access this account's Modrinth profile database"
            ));
        }
    }
    let db = Connection::open_with_flags(database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(std::time::Duration::from_secs(2))?;
    let custom: Option<String> = db
        .query_row(
            native_protection::text!("SELECT custom_dir FROM settings"),
            [],
            |row| row.get(0),
        )
        .optional()
        .context(native_protection::text!(
            "Cannot read Modrinth's configured profile location"
        ))?
        .flatten();
    let directory = custom
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or(data);
    if !directory.is_absolute() {
        bail!(
            "{}",
            native_protection::text!("Modrinth profile directory is not absolute")
        );
    }
    let mut query = db.prepare(native_protection::text!(
        "SELECT i.path, i.name, COALESCE(cs.game_version, ''), COALESCE(cs.loader, '')
         FROM instances i LEFT JOIN instance_content_sets cs
         ON cs.instance_id = i.id AND cs.id = i.applied_content_set_id"
    ))?;
    let rows = query.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut result = Vec::new();
    for row in rows {
        let (path, name, version, loader) = row?;
        if version != native_protection::text!("1.21.11")
            || !loader.eq_ignore_ascii_case(native_protection::text!("fabric"))
        {
            continue;
        }
        let path = PathBuf::from(path);
        let path = if path.is_absolute() {
            path
        } else {
            directory
                .join(native_protection::text!("profiles"))
                .join(path)
        };
        if path.is_dir() {
            result.push(GameProfile {
                name,
                game: fs::canonicalize(path)?,
            });
        }
    }
    Ok(result)
}
pub fn canonical_directory(value: &str) -> Result<PathBuf> {
    let path = Path::new(value.trim());
    if !path.is_absolute() || !path.is_dir() {
        bail!(
            "{}",
            native_protection::text!("Select an existing absolute game directory")
        );
    }
    fs::canonicalize(path).context(native_protection::text!(
        "Cannot resolve the selected game directory"
    ))
}
pub fn validate_jdk(root: &Path) -> Result<PathBuf> {
    let java = root
        .join(native_protection::text!("bin"))
        .join(native_protection::text!("java.exe"));
    if !java.is_file()
        || !root
            .join(native_protection::text!("jmods"))
            .join(native_protection::text!("jdk.attach.jmod"))
            .is_file()
    {
        bail!(
            "{}",
            native_protection::text!(
                "Select a full Java 21 JDK with jdk.attach; a Java runtime alone is insufficient"
            )
        );
    }
    let metadata = fs::read_to_string(root.join(native_protection::text!("release")))?;
    let version = metadata
        .lines()
        .find_map(|line| line.strip_prefix(native_protection::text!("JAVA_VERSION=")))
        .unwrap_or("")
        .trim_matches('"');
    if version != native_protection::text!("21")
        && !version.starts_with(native_protection::text!("21."))
        && !version.starts_with(native_protection::text!("21+"))
    {
        bail!(
            "{}",
            native_protection::text!("Selected attach JDK is not Java 21")
        );
    }
    fs::canonicalize(java).context(native_protection::text!(
        "Cannot resolve Java 21 attach executable"
    ))
}
pub fn find_jdk() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(path) = env::var_os(native_protection::text!("JAVA_HOME")) {
        candidates.push(PathBuf::from(path));
    }
    for program in [
        env::var_os(native_protection::text!("ProgramFiles")).map(PathBuf::from),
        crate::windows_profile::current_local_app_data().ok(),
    ] {
        let Some(program) = program.filter(|path| path.is_absolute()) else {
            continue;
        };
        for vendor in [
            native_protection::text!("Eclipse Adoptium"),
            native_protection::text!("Java"),
            native_protection::text!("Microsoft"),
            native_protection::text!("Programs\\Eclipse Adoptium"),
        ] {
            if let Ok(entries) = fs::read_dir(program.join(vendor)) {
                candidates.extend(
                    entries
                        .flatten()
                        .map(|entry| entry.path())
                        .filter(|path| path.is_dir()),
                );
            }
        }
    }
    candidates
        .into_iter()
        .find(|path| validate_jdk(path).is_ok())
}
pub fn support_directory(pid: u32, started: u64) -> Result<PathBuf> {
    let root = crate::windows_profile::current_local_app_data()?
        .join(native_protection::text!("Celeste"))
        .join(native_protection::text!("Plus"))
        .join(format!("{pid}-{started}"));
    fs::create_dir_all(&root).context(native_protection::text!(
        "Cannot create the beta agent/support directory"
    ))?;
    fs::canonicalize(root).context(native_protection::text!(
        "Cannot resolve the beta agent/support directory"
    ))
}
#[cfg(test)]
mod tests {
    use super::*;
    use rand::{RngCore, rngs::OsRng};
    struct ProfileFixture {
        root: PathBuf,
        data: PathBuf,
        game: PathBuf,
    }
    impl ProfileFixture {
        fn new() -> Self {
            let mut random = [0u8; 16];
            OsRng.fill_bytes(&mut random);
            let suffix = random
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            let root = std::env::temp_dir().join(format!("celeste-profile-paths-{suffix}"));
            fs::create_dir(&root).unwrap();
            let data = root.join("ModrinthApp");
            let game = root.join("Minecraft games 用户");
            fs::create_dir(&data).unwrap();
            fs::create_dir(&game).unwrap();
            Self { root, data, game }
        }
    }
    impl Drop for ProfileFixture {
        fn drop(&mut self) {
            let _ = fs::remove_file(self.data.join("app.db"));
            let _ = fs::remove_dir(&self.data);
            let _ = fs::remove_dir(&self.game);
            let _ = fs::remove_dir(&self.root);
        }
    }
    #[test]
    fn modrinth_discovery_uses_supplied_known_folder_and_separate_custom_game_root() {
        let fixture = ProfileFixture::new();
        let db = Connection::open(fixture.data.join("app.db")).unwrap();
        db . execute_batch ("CREATE TABLE settings (custom_dir TEXT);
             CREATE TABLE instances (id TEXT, path TEXT, name TEXT, applied_content_set_id TEXT);
             CREATE TABLE instance_content_sets (id TEXT, instance_id TEXT, game_version TEXT, loader TEXT);" ,) . unwrap () ;
        db.execute(
            "INSERT INTO settings VALUES (?1)",
            [fixture.root.to_str().unwrap()],
        )
        .unwrap();
        db.execute(
            "INSERT INTO instances VALUES ('one', ?1, 'Fixture profile', 'content')",
            [fixture.game.to_str().unwrap()],
        )
        .unwrap();
        db.execute_batch(
            "INSERT INTO instance_content_sets VALUES ('content', 'one', '1.21.11', 'fabric');",
        )
        .unwrap();
        drop(db);
        let before = fs::read(fixture.data.join("app.db")).unwrap();
        let profiles = modrinth_profiles_under(&fixture.root).unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].name, "Fixture profile");
        assert_eq!(profiles[0].game, fixture.game.canonicalize().unwrap());
        assert_eq!(fs::read(fixture.data.join("app.db")).unwrap(), before);
    }
    #[test]
    fn missing_modrinth_database_is_optional_and_never_created() {
        let fixture = ProfileFixture::new();
        assert!(modrinth_profiles_under(&fixture.root).unwrap().is_empty());
        assert!(!fixture.data.join("app.db").exists());
        assert!(modrinth_profiles_under(Path::new("relative-profile")).is_err());
    }
    #[test]
    fn corrupt_modrinth_settings_do_not_silently_select_the_default_location() {
        let fixture = ProfileFixture::new();
        let db = Connection::open(fixture.data.join("app.db")).unwrap();
        db.execute_batch("CREATE TABLE settings (unexpected_column TEXT);")
            .unwrap();
        drop(db);
        let error = modrinth_profiles_under(&fixture.root).unwrap_err();
        assert!(error.to_string().contains("configured profile location"));
    }
    #[test]
    fn relative_game_directories_are_rejected() {
        assert!(canonical_directory(".").is_err());
        assert!(canonical_directory("config").is_err());
    }
    #[test]
    fn missing_jdk_is_rejected_without_launching_any_process() {
        assert!(validate_jdk(Path::new("nonexistent-jdk-fixture")).is_err());
    }
}
