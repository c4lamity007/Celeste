# Celeste Launcher

`Celeste Launcher.exe` is the native Windows entry point for Celeste. It is a
small flat GUI. It does not load Minecraft classes or inject code into the
game process.

Flow:

1. Enter a license key and click Launch.
2. Pick an installed launcher, then a Fabric 1.21.11 instance if more than one exists.
3. Activate or authenticate the hardware-bound, DPAPI-protected installation credential.
4. Download the bootstrap through an authenticated endpoint and verify the SHA-256 digest carried by the signed server grant.
5. Install `CelesteLoader.jar` into Dawn/Lunar's supported Fabric mod directory, or into the selected standard launcher's isolated runtime cache and patch only that selected instance.
6. Write a DPAPI-protected, server-signed launch handoff. The bootstrap consumes it once before any later loader stage can run.
7. Start the chosen launcher/instance. The bootstrap, three remote loader stages, payload delivery, and in-game heartbeat each fail closed if the license or device is no longer active.

There is no offline or local-payload fallback. A rejected or unreachable auth
service never installs a developer JAR.

Badlion does not expose custom Fabric mod loading, so it is detected but shown
as unsupported. The launcher never injects into or silently redirects a
Badlion selection to another Minecraft profile.

`--check-hardware` prints whether a hardware binding is available and exits
without opening the window.

Build with `cargo build --release`. Cargo emits
`target/release/CelesteLauncher.exe`; copy/rename it to
`Celeste Launcher.exe` for distribution.

The values in `launcher-channel.json` identify the native launcher protocol and
remain stable across ordinary Celeste payload releases. Keep `src/release.rs`
aligned with that file when intentionally changing the launcher protocol. A
payload-only or loader-only release must not require redistributing this EXE.
