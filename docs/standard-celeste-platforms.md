# Standard Celeste on Windows, macOS, and Linux

This document covers the normal Celeste downloader and Fabric mod. Celeste+ is a
separate product and is not part of this platform build.

## Install flow

1. Use a Celeste or Both license to activate the downloader. On later runs, the
   downloader checks the selected device identity online before enabling the
   download controls.
2. Select an **existing folder** where you want the mod JAR saved, then choose
   **Download Celeste**. The downloader obtains and checks the signed normal
   Celeste bootstrap before saving it. It does not select or alter a Minecraft
   instance.
3. Move the downloaded `Celeste.jar` (or a numbered `Celeste-2.jar` if the
   folder already contained that name) into your Fabric instance's `mods`
   folder. Launch Minecraft through your usual launcher.

The intended game target is Minecraft 1.21.11 with Java 21, Fabric Loader
0.19.3, and the matching Fabric API. The bootstrap authenticates again when
the game starts, so the game needs access to the authentication service then.
The installer and bootstrap do not launch or attach to a running game.

The folder you choose is an output location, not necessarily the Minecraft
`mods` folder. If you use several instances, move a copy into each intended
instance yourself. Keep any other mods and launcher settings intact.

The selected activation is kept in the current account's application data,
under `Celeste/auth/<launcher-channel>/celeste-active.bin`. On Windows the root
is `%LOCALAPPDATA%` and protection uses that account's DPAPI. On macOS the root
is `$HOME/Library/Application Support` and the key is stored in that account's
Keychain. On Linux the root is an absolute `$XDG_DATA_HOME`, or
`$HOME/.local/share` when that variable is unset or not absolute, and the key is stored through
`secret-tool` in the desktop Secret Service. Linux users need `secret-tool`
(the `libsecret-tools` package on Ubuntu) and an unlocked Secret Service to
activate or start the game. Moving only the JAR does not move the activation
between operating-system accounts.

## Build and check locally

Install the stable Rust toolchain and Java 21. Run these commands from the
repository root on the machine for which you want a native downloader:

| Platform | Native downloader | Fabric bootstrap check |
| --- | --- | --- |
| Windows x64 | `cd native-verifier; cargo test --locked; cargo build --release --locked` | From the root: `.\gradlew.bat --no-daemon :compileJava :bootstrap-loader:check` |
| macOS (Apple Silicon or Intel) | `cd native-verifier && cargo test --locked && cargo build --release --locked` | From the root: `bash ./gradlew --no-daemon :compileJava :bootstrap-loader:check` |
| Linux x64 | `cd native-verifier && cargo test --locked && cargo build --release --locked` | From the root: `bash ./gradlew --no-daemon :compileJava :bootstrap-loader:check` |

The native result is `native-verifier/target/release/CelesteLauncher.exe` on
Windows and `native-verifier/target/release/CelesteLauncher` on macOS/Linux.
The Java bootstrap JAR is the same Fabric artifact on all three operating
systems; it still must go through the established signed release process before
the downloader will accept it. A raw `remapJar` output is not a release file.

The normal mod's Media HUD uses a Windows JNI bridge. On macOS and Linux its
media control service reports that automatic media control requires Windows
and leaves that HUD without live media data; the rest of Celeste can still run.

On Ubuntu, native builds need `pkg-config`, `libdbus-1-dev`, `libgtk-3-dev`,
`libxkbcommon-dev`, `libwayland-dev`, `libx11-dev`, `libxi-dev`,
`libxcursor-dev`, `libxrandr-dev`, `libxinerama-dev` and `libgl1-mesa-dev`.
Linux also needs a graphical desktop session, `libsecret-tools`, and a working
folder chooser portal at runtime.

The 20260920.2 release has platform-native test/build and hardware-collection
smoke coverage on macOS x64/arm64 and Linux. The distributed Linux package was
built on Ubuntu 22.04 (glibc 2.35 minimum). Mac application archives preserve
executable permissions and are ad-hoc signed, not Apple-notarized; local
Privacy & Security approval may be needed. Both Mac architectures target
macOS 11 or newer. Passing build/auth checks is not proof of licensed Minecraft
startup on every operating system. See [the release record](releases/20260920-dpop.md).
