# Celeste installer

`Celeste.exe` installs the normal Celeste Fabric mod. Select the Minecraft
instance, enter a Celeste or Both license when needed, and click **Install
Celeste**. Open Minecraft from its usual launcher afterward. The installer
does not start or attach to a game process.

The single instance picker lists verified Fabric Minecraft 1.21.11 profiles.
**Other folder / details** provides a folder browser and a full-path field for
portable Prism/MultiMC instances, custom game directories, and `.minecraft`
folders. Detection reads launcher metadata; a profile name is never proof of
its loader or Minecraft version. Modrinth database discovery is read-only.
Quilt and other loaders are unsupported. Metadata is checked again before
authentication and immediately before installation.

The installer authenticates a Windows DPAPI-protected device identity for the
fixed `celeste` product, verifies the signed installer grant's product and
bootstrap digest, then installs the downloaded bootstrap at
`<game directory>/mods/Celeste.jar`. The bootstrap has Fabric mod ID
`celeste_bootstrap`; the delivered client retains ID `celeste`. Authentication
is required again through a fresh signed challenge when Minecraft starts.
Installation does not create an expiring launch handoff, so the first launch
can happen later and subsequent launches use the saved activation.

Generic device certificates and the existing normal cache format stay
compatible. An explicit key is checked by the server against cached device
identities before enrollment, allowing a Both license to share its existing
device activation with Celeste+. Rejected candidates do not reset an account.
An unreadable/revoked cache can be replaced only by successfully activating
the key the user entered. Network errors stop the operation.

The new path does not add launcher libraries, change main classes, create
launcher profiles, or inject extra mod paths. Migration retires only exact
published legacy bootstrap hashes, the exact generated Celeste Prism
component, and its own runtime-cache pointer. It preserves backups under
`config/celeste/installer-backups` and keeps other Prism component metadata.
Unrecognized Celeste JARs or customized injection settings require review;
genuine MixinExtras and other mods are preserved. Other old launcher shims
may remain inert without their Celeste pointer. A small installer receipt
allows verified future replacement of this installer's own `Celeste.jar`.

There is no offline payload fallback. An authentication or digest failure
never installs a developer JAR. No bootstrap or mod is copied into a live
instance during the offline QA suite.

Build with `cargo build --release --offline --locked`. Cargo emits
`target/release/CelesteLauncher.exe`; distribute it as `Celeste.exe`.
`cargo test --offline --locked` runs metadata, safe installation/migration,
activation-selection, and atomic-write checks in temporary fixtures. The
ignored exact-artifact check additionally accepts `CELESTE_INSTALLER_QA_JAR`,
`CELESTE_INSTALLER_QA_SHA256`, and `CELESTE_INSTALLER_QA_LEGACY` to verify a
prepared protected bootstrap with the published legacy artifacts.

The values in `launcher-channel.json` identify the stable native protocol.
Keep `src/release.rs` aligned when deliberately changing that protocol.
Ordinary payload or bootstrap releases do not require a new native EXE.
`--check-hardware` performs only the existing hardware diagnostic.
