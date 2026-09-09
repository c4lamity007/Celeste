# Celeste+ native launcher

Setup build `20260908.2` resolves activation storage, Modrinth profile recovery,
per-user JDK discovery, and the launcher support cache through the current Windows
account's Known Folder API, rather than inherited `APPDATA`/`LOCALAPPDATA` text. Before
new enrollment, Windows initializes any missing Local/Roaming AppData known
folders with their standard security settings, then DPAPI and storage checks run.
It never chooses an account from the EXE location or enumerates `C:\Users`.
Error 3 includes folder diagnostics; missing Windows master-key files require
repair of the Windows profile, not another license reset. No registry changes,
key-file migration, plaintext fallback, or machine-wide DPAPI scope are used.
Setup failures have a **Copy error** button including the build identifier.

An unavailable optional Modrinth database no longer prevents an existing signed
activation from being checked through the account cache. If no saved activation
can be reused, an incomplete profile-recovery search still stops before new
enrollment so it cannot consume another device slot accidentally. The first
license screen remains available even when optional launcher discovery fails.
The preflight failure explicitly says no new device was enrolled and retains
the original Windows error. The EXE's folder, number of `C:\Users` directories,
and a different Downloads location do not choose the credential account.

This supports configured redirected, nested, Unicode, and network known-folder
paths when those locations are available and Windows current-user DPAPI works.
It does not repair a damaged Windows profile or promise that CryptProtectData
error 3 can always be fixed by the launcher. The application never creates or
migrates DPAPI master keys, changes registry/profile settings, switches Windows
accounts, falls back to plaintext or machine-wide protection, or reads other
users' credentials. Old server-consumed activations with no saved key still
need a binding reset only after the local storage preflight succeeds.

The signed launcher channel and protocol are unchanged; no new key, license
reset, server allowlist expansion, or Minecraft account tokens are needed for
this update. Existing EXEs do not self-replace: users need the updated
`Celeste+.exe` once to receive these native setup changes, independently of
automatically delivered game payload updates.

## License-first setup (current interface)

The first screen contains the Celeste+ / Both license field and **Activate**.
Activation does not require a Minecraft profile, a running game, or a selected
JDK. Only after successful server authorization and encrypted local persistence
does the launcher show profile/window selection and **Load**.

On reopening, the launcher verifies the saved activation with the server and
skips the license screen automatically. The product-specific
`celeste-plus-active.bin` remembers the selected Windows-protected identity;
`uid-N.bin` remains compatible with the shared native cache. Keys are never
stored as plaintext. **Details → Change license** returns to activation before
a game context has been established. Each Load still performs fresh server
authentication; the setup screen is not an authorization boundary.

Before a new enrollment request, the launcher checks current-user Windows
DPAPI protection/unprotection and atomic storage/readback with nonsecret probe
bytes. Probe files are cleaned up. Windows failures include the original OS
error code and recovery advice, without disabling encryption or weakening
product checks. A lost credential from an earlier failed activation may still
require an operator binding reset; this check cannot recover unsaved keys.

The historical Owner-beta notes below describe the original implementation;
current access is enforced by the signed `celeste_plus` product entitlement.

Independent Windows/eFrame EXE for the experimental, explicitly selected-PID
beta workflow. It is not the production launcher and does not edit or depend on
the dirty `native-verifier` source tree after this snapshot.

## Runtime contract

- Minecraft 1.21.11, compatible Fabric/Fabric API, and Java 21 are required.
  A full Java 21 JDK with `jdk.attach` runs the small local helper.
- The GUI lists visible Minecraft Java window titles/PIDs and records process
  creation time. It does not read JVM command lines, session tokens, environment
  blocks, launcher accounts or unrelated application windows.
- Only nonsecret Modrinth profile metadata is queried, using read-only SQLite.
  A sole existing Fabric 1.21.11 profile is preselected. Multiple profiles require
  an explicit choice, and the selected profile name remains visible. The user
  must separately select one running process; neither selection infers the other.
- A saved activation is checked using the original Windows DPAPI envelope,
  pinned server certificate, installation key proof, token key proof and current
  hardware profile. Owner access is the immutable signed UID **7**, not a label.
  UID 1 is not accepted. An entered key performs the original enrollment protocol
  only if no saved activation exists. Rejected/unreadable activations are never
  automatically reset, rebound or replaced.
- Existing Owner activation is read from the **original native launcher's shared
  cache** `%LOCALAPPDATA%/Celeste/auth/<launcher-channel-id>/uid-7.bin`, in addition
  to the selected game's installer/device files. It is not a new beta cache.
  Every candidate is DPAPI-decoded and pinned-certificate/UID-7 verified before
  normal server authentication. No other profiles or license IDs are scanned.
- The native verifier checks the exact schema-2 signed beta manifest and all four
  artifact descriptors. Only bootstrap A is downloaded locally, into an immutable
  SHA-256-addressed cache. Bootstrap A re-verifies its identity/catalog and performs
  the internal bootstrap/payload downloads. No beta code is accepted from arbitrary
  paths/URLs or from unverified staging output.
- Start carries the native verifier's signed `expected_release` and recomputed
  `expected_catalog_sha256`. A must compare both immediately after fetching its
  fresh catalog and before downloads/mutation, so a server update between the two
  requests cannot silently select different internal bootstrap or payload bytes.
- The first Attach API request is `inspect`. Native code compares the actual
  canonical Minecraft game directory, PID, creation time and Minecraft version
  against the explicit selection. A running production Celeste client is rejected.
  This inspection installs no feature hooks but leaves the small agent resident.
- Only after a successful directory/identity match may the verified encrypted
  activation be copied to the selected game's `config/celeste/auth/device.bin`.
  A different/unreadable existing binding is never overwritten. Publication is
  atomic/no-clobber and may fail on a filesystem without hard-link support.
- The initial request reaches the hash-checked/locked Java helper through stdin,
  which is closed after the write. Subsequent Load, Status, Stop and Inspect
  requests use authenticated, encrypted loopback control to the resident A,
  without another Attach API load. Its capability stays in launcher memory;
  installer grants and device credentials never appear in arguments or logs.
  Native status is the returned phase/error, not an optimistic attach-success flag.
- `starting` is not `active`; `stopping` is not `stopped`. The launcher checks
  status automatically during transitions and while active. Stop remains pending
  through callback, worker, resource-overlay and hook retirement. A timeout does
  not terminate Minecraft, and a control failure never silently attaches a
  replacement agent.
- A confirmed `stopped` result enables Load again in the same Minecraft process;
  a confirmed `inactive` result permits retry before a feature lifetime starts.
  Each Load rechecks the current host state and retains the exact original
  process/creation time, canonical directory, JDK, signed A digest, signed release
  and full signed catalog hash. It authenticates again and creates fresh B and
  payload loaders. Failure, incomplete cleanup and `restart required` keep reload
  blocked until cleanup is confirmed or Minecraft is restarted.
- Start a fresh Minecraft process when adopting a changed A, resident support or
  signed catalog. A and its compatible native/support owner remain resident
  across successful Load/Stop cycles. Clean retirement makes the old B/payload
  loaders eligible for collection; it does not force every JVM class to unload,
  erase native libraries or undo game history.

Local runtime material is limited to the small agent/support/native files under
`%LOCALAPPDATA%/Celeste/OwnerBeta/<pid>-<creation-time>/`, plus a verified encrypted
credential copy only where described above. No Minecraft JVM, libraries, mods,
launcher configuration or production release is patched. B and both payload
parts are not written by this launcher. This is not a promise about OS paging or
unrecoverable code.

## Launcher interface

The compact Owner interface uses the public launcher's centered gray card, pink
title and Celeste window icon. Profile and running Minecraft selections are
inline, with Refresh beside the process selector. One primary button changes
between Load and Stop; it stays visible while loading or waiting for cleanup.
The selected profile and process remain visible across repeated cycles.

Details contains the optional Owner key, explicit game directory, Java 21 JDK,
full status/error text and a manual status check. Long details scroll without
hiding the primary action. Leave the key blank to reuse the saved Owner activation.
Once a process context is established, profile/process/JDK changes remain locked
and all controls keep using that same context. Successful Stop returns the button
to Load; failed or incomplete cleanup does not enable another lifetime. The
compact layout preserves the authentication and exact target/release checks.

## Build and isolated verification

```powershell
cargo test --offline --manifest-path beta-lab/native-launcher/Cargo.toml
cargo build --offline --release --manifest-path beta-lab/native-launcher/Cargo.toml
```

Output: `beta-lab/native-launcher/target/release/CelesteOwnerBeta.exe`.

### Windows Java helper path regression

The launcher retains canonical Windows paths for artifact locking, digest checks,
and instance identity. Before invoking Java, it converts disk/UNC paths to their
ordinary spelling for both `-jar` and `--agent`, verifies that spelling resolves
to the same existing agent, and rejects ambiguous/device namespace names. Java
21's `-jar` loader fails with `ClassNotFoundException` when given a canonical
`\\?\` path even when the manifest and class are present.

The ignored integration test uses the actual command builder with PID 0, which
AttachCli rejects before attaching. Set `CELESTE_ATTACH_TEST_JAVA` to Java 21's
`java.exe` and `CELESTE_ATTACH_TEST_JAR` to a verified bootstrap-A JAR, then run:

```powershell
cargo test --offline --manifest-path beta-lab/native-launcher/Cargo.toml actual_helper_command_loads_main_before_rejecting_zero_pid -- --ignored --nocapture
```

The separate `check_java_paths.py` fixture covers JVM behavior with spaces,
Unicode and paths longer than 260 characters; see `JAVA-PATH-PROBE.md` for the
observed Windows code-page limitation and the boundary of UNC testing.
The offline tests use public deterministic server fixtures and owned temporary
files. They do not use real licenses, enroll, authenticate, query hardware,
download live artifacts, launch/attach to Minecraft or change an instance.

## Source provenance

`src/auth.rs` was extracted on 2026-09-06 from `native-verifier/src/main.rs`:
the credential format, hardware/WMI/EDID canonicalization, enrollment challenge,
installer challenge and dual signatures, X25519/HKDF/AES token envelope, pinned
CST/Ed25519 checks, DPAPI protection and key decoding are retained. The original
launcher-install/launch/rebind paths were not imported. New wrappers restrict
sources to the explicitly selected profile and UID-7 user cache, require Owner 7,
bound HTTP responses/timeouts and prevent automatic rebind/credential overwrite.

`src/release.rs` and `src/atomic_store.rs` were snapshotted from the corresponding
native-verifier files. The atomic-write implementation is unchanged; Rust formatting
only wraps the public trust/channel constants, whose values are unchanged. This
separate crate has its own dependency lock.

`src/delivery.rs` follows `beta-lab/delivery-client`'s existing schema-2 protocol
and tests against its public `server-schema2-golden.json`, generated by the real
Python server signer. No production server or native-verifier file was edited.

## Current verification boundary

Compilation/offline checks do not establish a working game integration. No
launcher-driven enrollment/authentication, live beta download, payload attachment,
gameplay, stop cleanup or native-window visual QA has been performed for this EXE.
The existing selected game currently contains production Celeste; it must not
receive another payload. A clean compatible local game and a separately authorized
staged beta catalog are necessary for the end-to-end Owner run.
