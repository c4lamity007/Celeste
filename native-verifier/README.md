# Celeste downloader — cross-platform source candidate 20260919.2

The current normal Celeste source authenticates first, then offers **Save location**,
**Browse**, and **Download Celeste**. Choose an existing folder. Only the signed,
digest-checked JAR is saved there; move it into a Fabric Minecraft 1.21.11 `mods`
folder yourself. Existing files are preserved by choosing `Celeste-2.jar`, etc.
There is no install-target discovery, game launch, instance migration, or game
configuration write in this download path. Legacy activation discovery remains
read-only for compatibility with older installations.

Activation remains encrypted in the current account's shared cache: Windows
uses DPAPI, macOS uses Keychain-backed protection, and Linux uses a key in
Secret Service. The updated normal bootstrap can read that selected activation
when first started in a new game directory, authenticate it with the existing
server protocol, then save the runtime copy. A pre-existing game credential
retains priority, including when it is damaged or rejected; no automatic
identity fallback is performed. See
[`docs/standard-celeste-platforms.md`](../docs/standard-celeste-platforms.md)
for platform prerequisites, paths, build commands, and limitations.

## Release status

This is unpublished cross-platform source, not a tested macOS/Linux release.
Native candidates need platform-native CI builds and testing before distribution.
No Celeste+ loading, obfuscation, or server changes are included.

The signed bootstrap must contain `custom["celeste:delivery"] =
"shared-activation-v1"`. The downloader rejects older artifacts that require the
installer to write game-specific activation files. Build, package, and publish the
updated bootstrap through the established signed release process before shipping
this downloader. A raw local bootstrap is not a substitute for a signed release.
No live artifact was queried or published as part of the local checks.

## Previous installer contract (historical)

Setup build `20260908.1` resolves activation storage through the current Windows
account's Known Folder API, rather than inherited `LOCALAPPDATA` text. Before
new enrollment, Windows initializes any missing Local/Roaming AppData known
folders with their standard security settings, then DPAPI and storage checks run.
It never chooses an account from the EXE location or enumerates `C:\Users`.
Error 3 includes folder diagnostics; missing Windows master-key files require
repair of the Windows profile, not another license reset. No registry changes,
key-file migration, plaintext fallback, or machine-wide DPAPI scope are used.
Setup failures have a **Copy error** button including the build identifier.

`Celeste.exe` installs the normal Celeste Fabric mod. On first use, enter a
Celeste or Both license and click **Activate Celeste**. Activation is verified
and saved before the instance picker, folder browser, or installation becomes
available. Then select a Minecraft instance and click **Install Celeste**.
Open Minecraft from its usual launcher afterward. The installer
does not start or attach to a game process.

On later starts, a background worker checks the saved activation with the
server for the fixed `celeste` product. Only a successful signed challenge
skips the key screen and starts instance discovery. A revoked, invalid, or
wrong-product identity returns to the key screen. Network/service failures
offer **Retry verification**, which never enrolls a device. A Windows
credential-protection failure keeps the original file and provides Windows
account/error-code guidance. **Change license** allows explicit replacement.

Activation needs no selected profile or game directory. It requires the
shared Windows user cache at
`%LOCALAPPDATA%/Celeste/auth/<launcher-channel>/`: existing `uid-N.bin`
records remain compatible and `celeste-active.bin` remembers the selected
device identity (separate from Celeste+'s selection). Both files use the
existing CST1/current-user DPAPI envelope; no plaintext license key, local
authorization flag, or offline authorization is stored. Every new enrollment
first performs a DPAPI round trip and atomic write/readback with nonsecret
probe data in that cache. Failed protection or persistence stops enrollment.
A newly enrolled credential is saved before its installer authentication,
so a network interruption can retry that identity without rebinding it.

The single instance picker lists verified Fabric Minecraft 1.21.11 profiles.
**Other folder / details** provides a folder browser and a full-path field for
portable Prism/MultiMC instances, custom game directories, and `.minecraft`
folders. Detection reads launcher metadata; a profile name is never proof of
its loader or Minecraft version. Modrinth database discovery is read-only.
Quilt and other loaders are unsupported. Metadata is checked again before
authentication and immediately before installation.

Every installation freshly authenticates the setup-verified Windows
DPAPI-protected device identity for the
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
activation-selection, license-first UI state, mandatory persistence, preflight
ordering, DPAPI protection, and atomic-write checks in temporary fixtures. The
ignored exact-artifact check additionally accepts `CELESTE_INSTALLER_QA_JAR`,
`CELESTE_INSTALLER_QA_SHA256`, and `CELESTE_INSTALLER_QA_LEGACY` to verify a
prepared protected bootstrap with the published legacy artifacts.

The values in `launcher-channel.json` identify the stable native protocol.
Keep `src/release.rs` aligned when deliberately changing that protocol.
Ordinary payload or bootstrap releases do not require a new native EXE.
`--check-hardware` performs only the existing hardware diagnostic.
