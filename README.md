# Celeste Client

Current Celeste client source, its Fabric bootstrap and three later loader stages, the Python authentication service, Windows native launcher, assets, and release tooling.

This is a source snapshot, not a distributable release. Private credentials, local game data, build caches, generated JARs/DLLs/EXEs, payload chunks, and release mappings are intentionally excluded. The checked-in Gradle wrapper JAR is the standard build-tool bootstrap dependency, not a Celeste payload.

## Components

- `src/main/`: client Java code, authored native cleanup bridge, shaders, fonts, textures, and asset provenance.
- `bootstrap-loader/`, `stage-two-loader/`, `stage-three-loader/`, `stage-four-loader/`: loader Gradle projects.
- `auth-service/`: FastAPI service, admin UI, tests, and deployment configuration.
- `native-verifier/`: Windows Rust launcher and the Java launcher-hook source.
- `tools/` and `obfuscation/release-profiles/`: authored release packaging scripts and obfuscation profiles.

## Java / Gradle

Use JDK 21 with Java and the JDK tools available on PATH. The root build targets Minecraft 1.21.11; dependency versions are in `gradle.properties`.

From the repository root on Windows:

```powershell
.\gradlew.bat build
```

The root settings include all four loader projects. Generated outputs are written beneath each project's `build/` directory.

The current build retains a machine-specific prerequisite: `build.gradle` references an externally supplied `MediaPlayerInfo.dll` using an absolute workstation path. That DLL and its implementation source are not present in this repository. Obtain the correct compatible bridge separately and adjust that input path in your own checkout before expecting Windows media playback to work. This snapshot does not alter the working project's packaging behavior.

The authored cleanup wrapper is `src/main/native/CelesteMediaCleanup.cpp`. Compile it as an x64 Windows JNI DLL using a compatible C++ compiler, Windows SDK, and JDK JNI headers (both `include` and `include/win32`). Place the generated output at `src/main/resources/mediaplayerinfo/natives/win/CelesteMediaCleanup.dll` before packaging if Windows media cleanup support is required. There is currently no automated native-bridge build task. Generated DLLs remain untracked.

## Native launcher

Use Windows and a Rust toolchain supporting edition 2024. Dependencies are locked in `native-verifier/Cargo.lock`.

Before `cargo build --release`, generate the hook JAR required by `native-verifier/src/launchers.rs`. Its source is `native-verifier/assets/hook-src/dev/celeste/hook/Main.java`. From `native-verifier/`, a JDK 21 build can be prepared with:

```powershell
New-Item -ItemType Directory -Force -Path assets/hook-build
javac --release 21 -d assets/hook-build assets/hook-src/dev/celeste/hook/Main.java
jar --create --file assets/celeste-hook.jar -C assets/hook-build .
Get-FileHash -Algorithm SHA1 assets/celeste-hook.jar
```

Update `HOOK_SHA1` in `src/launchers.rs` in your checkout to the lowercase SHA-1 of that exact generated JAR before building. JAR metadata can change its digest between builds; do not assume it matches the existing digest. The JAR is embedded at compile time through `include_bytes!` and must exist even for a compilation check.

Then run:

```powershell
cargo build --release
```

The launcher icon is embedded from the existing client logo at `src/main/resources/assets/celeste/textures/gui/celeste-logo.png`; keep the repository layout intact.

`launcher-channel.json` and `src/release.rs` are intentionally checked-in public configuration. They contain protocol/channel identity, an endpoint, digests, and a public Ed25519 verification key, not a signing private key. Keep them aligned during an intentional launcher-channel or signing-key migration. Ordinary payload updates should preserve the stable channel.

## Authentication service

Use Python 3.12, matching the Dockerfile. In `auth-service/`, create an isolated environment, install `requirements.txt`, and run:

```powershell
python -m pip install -r requirements.txt
python -m pytest
```

Tests use temporary data and deterministic fixture keys. Production private keys, session secret, key pepper, and admin password hash are required from environment variables in `app/config.py`; do not put real values into source files or Git.

The Dockerfile copies `releases/`. Only its empty-directory marker is committed. Populate it using the release packaging workflow and supply required runtime settings before deploying. A fresh source checkout alone is not a running production auth service.

## Release packaging

See `auth-service/README.md` and each script's `--help`. Build the payload and all loader stages, apply the authored obfuscation profiles with the separately available compatible obfuscator, then package authentication metadata after obfuscation. Some loader profiles retain absolute Gradle-cache dependency paths from the original workstation; resolve those paths for your machine.

`tools/package_final_release.py` creates configured release artifacts and metadata. `tools/package_loader_chain.py` refreshes the loader chain against an existing packaged payload. Their `--server-key` input is the public verification key, not the private signing key. Generated `release.env`, manifest JSON, JARs, binary chunks, logs, and symbol mappings are not part of this source snapshot.

Asset attribution and provenance are under `src/main/resources/META-INF/licenses/celeste/`.

