#[path = "../../native-verifier/src/portable_credential_protection.rs"]
mod credential_protection;
#[path = "../../native-verifier/src/user_profile.rs"]
mod user_profile;
#[path = "../../native-verifier/src/atomic_store.rs"]
mod atomic_store;

fn main() -> anyhow::Result<()> {
    let operation = std::env::args().nth(1).expect("operation");
    if operation == "path" {
        println!("{}", user_profile::current_local_app_data()?.join("Celeste/auth/a39fbbf8e5c344edb39ee82d/celeste-active.bin").display());
        return Ok(());
    }
    let output = std::path::PathBuf::from(std::env::var("CELESTE_FIXTURE_DIR")?);
    match operation.as_str() {
        "seal" => {
            credential_protection::preflight(&output)?;
            let sealed = credential_protection::protect(b"celeste-macos-nonsecret-fixture")?;
            std::fs::write(output.join("rust.bin"), sealed)?;
            println!("Rust macOS Keychain preflight and envelope creation passed");
        }
        "open" => {
            let sealed = std::fs::read(output.join("java.bin"))?;
            assert_eq!(credential_protection::unprotect(&sealed)?, b"celeste-macos-nonsecret-fixture");
            println!("Rust opened Java macOS Keychain envelope");
        }
        _ => anyhow::bail!("Unknown fixture operation"),
    }
    Ok(())
}
