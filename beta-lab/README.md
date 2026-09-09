# Owner late-load beta — integration testing

The native EXE, two protected bootstraps and two protected Celeste parts are built.
Actual artifact checks and a bootstrap inspection of a clean running Minecraft
session pass. Authenticated feature activation and in-game stop/cleanup are still
pending; do not claim a completed playable test. The Owner-only server package is
deployed as Fly v67, alongside unchanged production bootstrap/payload files.
The current Owner beta includes GLB player-model importing and the actual Sahur
model with all five creator animations; see `model-import/USAGE.md`. The creator
asset is scoped to the personal Owner build, pending general redistribution rights.
Unrelated native launcher activation-reuse changes in the checkout belong to
the user's separate launcher task and must be preserved.

The user selected the existing **Owner license UID 7** for eventual beta access.
UID 1, which happens to have the same display label, must not receive beta access.
The server implementation checks the immutable UID, not the label. No new license
was generated or binding reset. See `RELEASE-STATUS.md` for exact current evidence.

## Implemented and testable now

- `attach-probe/`: a local Java 21 fixture demonstrates supported agent attachment
  after process startup, a method-body change, rejection of a structural class
  change, restoration and duplicate-attachment handling. It creates and targets
  only its own nonce-bound test JVM. It is **not** a Minecraft attach utility.
- `memory-runtime/`: a bounded two-part memory classloader validates both trusted
  artifact hashes, ZIP structure, entry ownership and limits. Tests generate
  their payload classes and JARs in memory. Closing revokes resources and releases
  owned buffers; it does not forcibly unload defined JVM classes or stop threads.
  Its synchronous retained-part visitor now connects the loader's actual bytes
  and original part boundaries to the integrity adapter. `memory-integrity/`
  tests this connection, resource lookup and failure/closure behavior.
- `auth-service/app/beta_delivery.py`: optional, disabled-by-default beta routes
  reuse installer authentication, live license/device checks and the server
  signer. The manifest and all four artifacts are restricted to the configured
  owner UID. Non-owner, expired, revoked, mismatched and altered requests fail.
  Runtime auth and heartbeats also require the exact current beta catalog and
  owner, with both installation proofs retained. Production and beta grants do
  not share artifact download rights. Stale releases are rejected before tamper
  handling, so an update mismatch alone does not revoke a license.
- `CelesteClient` / `AuthBootstrap`: explicit initialization after the game's
  startup event, one installation per classloader, authenticated activation on
  the game thread, terminal failure handling and cleanup of attempted services.
  HUD, friend input and module callbacks stop when authorization or activity ends.
  This entrypoint requires the host to have supplied compatible hooks/resources.
- `ArtifactIntegrity`: one-shot retained-memory source with the same sorted
  protected-entry digest as the disk path. It recomputes the digest, rejects
  duplicate protected entries, and never falls back to disk after source failure.
  The first authentication digest permanently selects disk or memory; a running
  disk-authenticated client cannot later replace its source with a clean snapshot.
  Bounds cover the measured current release: 44,771 entries / 77,487,732 expanded
  bytes. Focused tests cover this size and both configured maximum boundaries.
- `beta/resources`: internal resource-manager input with a bounded immutable
  asset snapshot, independent reload views and explicit activation/deactivation.
  No Celeste resource-pack profile or provider is registered; Minecraft's pack
  chooser, enabled choices and other packs remain unchanged. The host reload-input
  adapter now observes real attempt identities and final callback results. On an
  owned failure it retries without internal assets before vanilla's recovery can
  clear selected packs. A failed cleanup keeps normal vanilla fallback available.
  Complete bootstrap-driven reload and real-game verification remain pending.
- `reload-hooks/`: an opt-in transformer prepares schema-preserving resource
  hooks for the exact named/intermediary Minecraft 1.21.11 methods. Its JDK-only
  `dev.celestebeta.host.ResourceReloadBridge` must have one canonical definition
  visible to both Minecraft and the payload. No hook is installed automatically.
- `member-access/`: the seven startup accessor/invoker dependencies now have
  cached paths to ten original Minecraft members. Actual key bindings and both
  mapping namespaces are checked. Related gameplay method hooks remain separate.
- `visual-hooks/`: prepares ten existing method bodies for sky, swing, GUI flush,
  HUD selection, media clicks and the local rotation display.
  Sky renderer state is now external and shared with the startup mixin. The late
  entrypoint connects authenticated handlers and disconnects them during cleanup;
  complete host installation and real-game behavior remain unverified.
- `callbacks/`: stable game-owned proxies detach the late client's font/menu
  listeners on close, including pending default reloader work. The old callbacks
  must drain before another runtime can reuse their slots. Twenty additional
  feature/render registrations now use the same scope, with explicit render
  buffer cleanup. Auth callbacks and complete frame/GPU retirement remain open.
- `interaction-hooks/`: five method bodies preserve slot synchronization, nested
  breaking projections, sprint capture and fluid use. Three startup mixins now
  share external state helpers with these late handlers.
- `rotation-hooks/`: six named/intermediary target schemas and executable fixtures
  cover rotation getters, keyboard TAIL callbacks and selected-stack handling.
- `atmosphere-hooks/`: schema-preserving fog/lightmap preparation keeps original
  shared-data and upload timing, including the separate Sodium/vanilla branches.
- `gameplay-callbacks/`: the existing server TAIL observations, scoped sword click,
  main-hand result filter and entity-attack HEAD observer have late equivalents.
  The focused run passed 439 checks; it does not establish in-game installation.
- `fake-gamble-hooks/`: prepares the existing packet, screen, input and item
  presentation hooks. The focused run passed 474 checks without changing module
  behavior or installing anything in Minecraft.
- `combined-hooks/`: composes seven hook families across 19 fixed game classes,
  checks schema preservation and preserves reviewed Fabric cancellation ordering.
  Its combined verification passed 820 checks; composition is not hook installation.
- `runtime-lifecycle/` and `payload-session/`: production orchestration exposes
  readiness and retirement, sequences retained-byte integrity, resource reload,
  authentication and service cleanup, and retains assets when replacement fails.
- `bootstrap-b/`: composes actual signed `MemoryRelease`, two-part loading and
  the payload lifecycle. Its offline integration passed 67 checks, including
  mapping/ownership rejection, stop-before-ready and cleanup-failure retention.
  The current client `remapJar` build also succeeded; neither is a playable proof.
- `bootstrap-a/`: an ordinary explicit-PID Attach API agent now composes verified
  delivery, resident support, whole-set hook installation and Bootstrap B.
  Strict compilation, 154 archive/support/hook checks and 36 real Attach IPC checks
  passed. See [its limits and CLI contract](bootstrap-a/README.md).
- `minecraft-linkage/`: an explicit-PID diagnostic checks canonical bridge
  visibility and captures actual modded target schemas. Its transformer returns
  null. The selected PID 25244 report captured 17 targets and verified 16 canonical
  bridge identities in Minecraft 1.21.11/Fabric Loader 0.19.3. Startup Celeste was
  already present, so this is linkage evidence, not a late feature installation.
- `delivery-client/`: verifies the signed owner manifest and all four bounded
  downloads in memory. Its offline checks include an actual Python-server golden
  vector. Bootstrap A/B now compose delivery and execution; the full signed
  game-launch path is still unverified.
- `AuthServiceClient`: beta runtime grants and heartbeats bind to the verified
  catalog, release, version, device and digest. Production transcript bytes stay
  unchanged. `AuthBootstrap.configureBetaRuntime` selects the verified catalog
  once before initialization and requires a retained memory integrity source.
- `auth-lifecycle/`, `integrity-adapter/`, `resource-provider/`: focused checks
  against the actual production classes, with explicit fixture/runtime limits.

## Intended release shape

The launcher flow is one local native EXE, one local bootstrap agent, one
downloaded internal bootstrap, and two downloaded Celeste payload parts. The
native EXE calls the ordinary Java 21 Attach API through the verified agent.
Celeste classes and renderer assets are retained in two memory parts; ordinary
supported attachment still needs the local agent JAR. Exact verified copies of
JNA/OSHI/media support libraries and the canonical bridge JAR use resident local
files for ordinary Java/native loading. This is not a diskless-native-loader
claim, a promise of uninspectable code, or a guarantee against OS memory paging.

The beta catalog's four fixed artifacts are:

| ID | Role | Server-side filename |
| --- | --- | --- |
| `bootstrap-a` | bootstrap | `bootstrap-a.jar` |
| `bootstrap-b` | bootstrap | `bootstrap-b.bin` |
| `payload-a` | payload | `payload-a.bin` |
| `payload-b` | payload | `payload-b.bin` |

The catalog schema is `celeste-beta-release-2`; `beta-release.json` contains only
`schema`, `release_id`, `client_version`, `runtime_sha256` and `artifacts`.
`runtime_sha256` is the canonical client-integrity digest, not either downloaded
part's ZIP hash. Each artifact contains exactly `id`, `role`,
`filename`, `sha256` and `size`. A distinct release ID and a separate absolute
server directory are required. Filenames are transport packaging, not security.

`GET /v1/beta/manifest` requires an existing valid installer bearer grant. It
returns a signed beta manifest, valid for at most 120 seconds and never longer
than that installer grant. Each of the four `/v1/beta/artifacts/...` URLs requires
both the original bearer grant and the signed `X-Celeste-Beta-Manifest` header.
The manifest is bound to the owner, device, original grant and exact catalog.
Requests are rate-limited and responses are non-cacheable. The current 256 MiB
server still needs concurrency/memory benchmarking before enabling real artifacts.

The beta host passes the verified public catalog's `catalog_sha256` into
`AuthBootstrap.configureBetaRuntime` before initialization. It also installs the
loader's retained-part visitor with `ArtifactIntegrity.installVerifiedMemorySource`
before any digest call. Beta auth/session requests carry `beta_catalog_sha256`;
their signed session transcript appends newline + release ID + newline + client
version + newline + catalog hash to the existing production transcript, without
a trailing newline. The server rechecks current entitlement/catalog on each call.

The Owner test server settings are `CELESTE_BETA_OWNER_UID=7` and an absolute
`CELESTE_BETA_RELEASE_DIR` distinct from production. Use the isolated context
documented in `release-package/README.md`; it includes the exact protected beta
and preserves production artifacts byte-for-byte.

## Remaining integration work

1. Validate the full host transaction in-game against the supported Fabric
   interaction module. Combined and Bootstrap A component checks pass. The
   method-only design cannot add members/interfaces to already-loaded classes.
   Global startup-Celeste exclusion remains mandatory despite passing schemas.
2. Test complete signed startup through the built native Owner EXE. The EXE,
   protected bootstraps and signed-release packaging are implemented and checked.
   Do not relabel production payloads or reuse production auth metadata for
   changed bytes; the normal integrity/license checks remain in force.
3. Test the entire verified chain on an explicitly selected clean local Minecraft
   1.21.11/Fabric/Java 21 instance. The previously captured PID already contained
   startup Celeste and is not a suitable second-runtime installation target.
   Verify build identity, resource reload failure/recovery, actual module behavior,
   auth retirement, hook rollback and resource/GPU cleanup before a playable claim.
4. Establish the remaining native/render/GPU lifetime guarantees. Successful
   service stop does not erase canonical bootstrap classes or undo game history.
   The host currently admits one feature lifetime per JVM; restart before another
   release. No automatic runtime update or unload has been demonstrated.
5. Keep this channel restricted to Owner UID 7. Broader publication and public
   server testing are outside this beta test. Recheck actual runtime behavior
   before extending access.

The runnable EXE is `native-launcher/target/release/CelesteOwnerBeta.exe`.
There is still no authenticated mid-game demonstration or automatic runtime
update proof. Stopping is implemented; a second feature lifetime requires restart.

## Re-run the isolated checks

Use the project's Python environment and a full Java 21 JDK:

```powershell
& .\auth-service\.venv\Scripts\python.exe .\beta-lab\check.py --java-home 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
```

Results go under ignored `build/beta-lab/` and distinguish passing component
checks from unverified Minecraft integration. No deployment or real license
mutation is performed by this command.

Use `--checks auth integrity resources` (or another selection) for the affected
components only. `resources` runs the incremental client build plus the isolated
resource check. `auth` includes the real 30-second heartbeat scheduling check.
All selected Gradle components are grouped into one offline Gradle invocation,
sharing incremental compilation; one failed task marks that group incomplete.

Additional focused selectors map to these exact tasks (each uses its component's
`check.init.gradle`):

| `--checks` selector | Gradle check task |
| --- | --- |
| `rotation-hooks` | `betaRotationHooksCheck` |
| `atmosphere-hooks` | `betaAtmosphereHooksCheck` |
| `gameplay-callbacks` | `betaGameplayCallbacksCheck` |
| `fake-gamble-hooks` | `betaFakeGambleHooksCheck` |
| `combined-hooks` | `betaCombinedHooksCheck` |
| `bootstrap-a` | `betaBootstrapACheck` |
| `bootstrap-b` | `betaBootstrapBCheck` |
| `runtime-lifecycle` | `betaRuntimeLifecycleCheck` |
| `payload-session` | `betaPayloadSessionCheck` |
