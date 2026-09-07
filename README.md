# Celeste

Celeste is the normal Minecraft mod installation product. Select a Fabric
Minecraft 1.21.11 instance in `Celeste.exe`, enter a **Celeste** or **Both**
license when needed, and install the signed bootstrap at `mods/Celeste.jar`.
Start Minecraft with its usual launcher afterward. Every game startup performs
fresh signed authentication using the saved device activation, so installation
can happen well before the first launch.

[Celeste+](https://github.com/c4lamity007/Celeste-Plus) is the separate live-loading
project. Both products use this repository's authentication service. License
creation requires **Celeste**, **Celeste+**, or **Both**; labels and numeric UIDs
grant no special access. See the [installer guide](native-verifier/README.md),
[bootstrap guide](bootstrap-loader/README.md), and
[product licensing contract](auth-service/PRODUCT_LICENSING.md).

This repository contains source and build tooling. Generated release binaries,
private credentials, local activations, game data, caches, and obfuscation mappings
are excluded. Publishing or building this source does not deploy the service or
reset licenses; those are separate operator actions.

## Repository layout

- `src/main/`: normal client source, assets, and authored native cleanup wrapper.
- `bootstrap-loader/`: the Fabric mod installed as `Celeste.jar`.
- `stage-two-loader/`, `stage-three-loader/`, `stage-four-loader/`: authenticated
  delivery stages used by the bootstrap.
- `native-verifier/`: Windows instance picker, activation, and mod installer.
- `auth-service/`: shared product licensing service, dashboard, tests, and operator tools.
- `tools/` and `obfuscation/release-profiles/`: source release packaging tools.
- `beta-lab/beta-client-protocol/`: shared normal/Plus authentication protocol
  fixtures; the live-loading implementation is maintained in the Celeste+ project.

The bootstrap has Fabric ID `celeste_bootstrap`; the downloaded runtime retains
ID `celeste`. Fabric Loader supplies MixinExtras as a separate dependency. Normal
installation uses Fabric's ordinary mod discovery and preserves launcher profiles.
The remote delivery stages are separate from the old launcher discovery helper.

## Java builds and checks

Use JDK 21 and put its tools on `PATH`. Dependency versions, including Minecraft
1.21.11, are recorded in `gradle.properties`. From the repository root:

```powershell
.\gradlew.bat build
.\gradlew.bat :bootstrap-loader:checkBootstrapMod
.\gradlew.bat -I beta-lab/beta-client-protocol/check.init.gradle betaClientProtocolCheck
```

Add `--offline` when dependencies are already cached. The bootstrap check uses
Fabric's real mod discovery and metadata parser in a temporary directory. The
protocol check uses real cryptographic proofs with a local fake transport. Neither
check opens Minecraft or contacts the authentication service. A complete signed
delivery game test is a separate validation step.

For Windows media-capable release packaging, supply the compatible external
`MediaPlayerInfo.dll` either through the `MEDIA_PLAYER_INFO_DLL` environment
variable or at the default ignored path `local-libs/MediaPlayerInfo.dll`.
`build.gradle` packages the supplied file under its expected runtime name.
Source compilation and checks can run without this DLL. Its implementation and
binary are not included in this repository.

The authored cleanup wrapper is `src/main/native/CelesteMediaCleanup.cpp`.
Compile it as an x64 Windows JNI DLL with a C++ compiler, Windows SDK, and the
JDK's `include` and `include/win32` headers. Media-capable packaging also needs
that generated wrapper at
`src/main/resources/mediaplayerinfo/natives/win/CelesteMediaCleanup.dll`.
There is currently no automated build task for this native wrapper. Keep supplied
and compiled DLLs out of Git.

## Windows installer build

Use a Rust toolchain supporting edition 2024. Keep the repository layout intact:
the installer embeds the tracked client logo. Cargo dependencies are locked in
`native-verifier/Cargo.lock`.

The source still includes legacy compatibility code whose compile-time asset is
generated from the tracked Java helper. Generate this ignored asset before Cargo;
the current normal installation path does not deploy it or use its launcher patch
routines. No `HOOK_SHA1` source edit is required for the normal installer.

From `native-verifier/`:

```powershell
New-Item -ItemType Directory -Force -Path assets/hook-build
javac --release 21 -d assets/hook-build assets/hook-src/dev/celeste/hook/Main.java
jar --create --file assets/celeste-hook.jar -C assets/hook-build .
cargo test --locked
cargo build --release --locked
```

Distribute `target/release/CelesteLauncher.exe` as `Celeste.exe`. The native checks
use temporary instance fixtures. One additional ignored test requires prepared
release artifacts and is documented in the [installer guide](native-verifier/README.md).

`launcher-channel.json` and `src/release.rs` contain public protocol identifiers,
an endpoint, digests, and the public Ed25519 verification key. They contain no
signing private key. Keep them aligned during deliberate protocol changes;
ordinary payload updates preserve the stable installer channel.

## Shared authentication service

Use Python 3.12, matching the service Dockerfile. In `auth-service/`, create an
isolated environment, install dependencies, and run the tests:

```powershell
python -m pip install -r requirements.txt
python -m pytest
```

Tests use temporary data and fixture keys. Production signing secrets, key pepper,
session secrets, and administrator credentials come from the environment specified
in `app/config.py`. Deployment requires separately prepared release artifacts.
Only the empty-directory marker for `auth-service/releases/` is committed.

Read [PRODUCT_LICENSING.md](auth-service/PRODUCT_LICENSING.md) before using the
explicit key-creation or license-reset tools. Service startup does not create
sample licenses or reset existing ones.

## Release packaging

Build the client and loader projects, apply the authored obfuscation profiles with
the separately supplied compatible obfuscator, and then configure authentication
metadata. Check each profile's dependency paths for the build machine.

`tools/package_final_release.py` prepares the protected payload and release
metadata. `tools/package_loader_chain.py` refreshes the bootstrap and loader chain
against an existing packaged payload. Their `--server-key` argument is the public
verification key. See each tool's `--help` and the [service guide](auth-service/README.md).
Generated JARs, EXEs, payload chunks, `release.env`, manifests, and mappings remain
outside the source publication.

Asset attribution is under `src/main/resources/META-INF/licenses/celeste/`.
