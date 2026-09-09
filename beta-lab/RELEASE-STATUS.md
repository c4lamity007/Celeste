# Owner beta release status

## Current: repeatable Owner Beta Stop/Load, September 7

Published and live-verified for Owner UID 7 only. Release remains
`owner-beta-20260907-sahur-01`, client `1.0.0-beta.3`; image
`registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1YZBC59BMSA116M6FRR3Q7B`.
Runtime digest: `c9fe75d068d3304fd38efe52e5333db3e5163a9d8c6a570a152111ab82792a7a`.

Use `C:/Users/c4lam/Downloads/Celeste Owner Beta - Simple.exe`
(SHA-256 `6eac11c10735c67f4116d78d7bfccd70d9b8b5243c6f15974d7d3b657c369f04`).
Restart Minecraft once to replace the previously resident A/support version.
Afterward, confirmed Stop permits another Load in the same exact instance.
The UI has inline Profile/process selectors, one Load/Stop action, and Details.
An incomplete or failed cleanup continues to require a restart.

Stop waits for active reloads and overlay fade, retires only owned pipelines and
cached asset textures, and awaits config/media/model/network callback work.
Shutdown hooks and workers no longer permanently retain retired payloads.
B/payload references and bytes are released after queued callbacks drain; the
small verified A/host/native support stays resident and is reused. JVM class
collection is eventual, not a forced removal of every imported class.
Celeste logger output is quiet; resident encrypted IPC avoids repeated agent
attachment warnings. Minecraft/JVM-owned first-attach/reload messages can remain.

The exact protected package passed resource/session/media/config/model/network
cleanup checks, protected A281/B101 lifetime checks, and 39 artifact checks.
Isolated repeated-loader tests observed collection of retired loaders. Actual
Minecraft world continuity and full live-payload collection remain unmeasured;
the user is testing the delivered build. No live game actions were performed.

All 30 deployed file hashes were verified: only the five private beta files
changed; all 25 server/public files (including nine production artifacts) are
identical. Non-owner beta access returns 403; health returns 200. Evidence:
`build/module-qa/owner-beta-20260907-reload-01/publication-result.json`,
`build/module-qa/owner-beta-20260907-reload-02/`, and the final five-file package
`build/module-qa/release-package/owner-beta-20260907-reload-02-release`.

## Previous: Sahur model pack, September 7

Release `owner-beta-20260907-sahur-01`, client `1.0.0-beta.3`, deployed as Fly **v67**
for **Owner UID 7 only**. Image:
`registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1Y8DY7W2MQWWZAJP1WVZA1B`.

Enable Visuals → Player Models to load the bundled Sahur automatically, or choose
Load Sahur after importing another GLB. Self/Others/Everyone, proportional size,
and stationary post-kill playback remain available. The actual creator download
was converted into one mesh, one 65-bone rig, the original embedded texture and
five clips (idle plus four dances). Running Man remains a celebration; missing
walk clips use idle instead of a T-pose. No separate resource-pack menu entry.

Asset: 5,902,760 bytes, SHA-256
`739665e7d033b92d5c66c0cb24bb4ee6490453b49f10ed9ceb941b6914e1327d`.
The original PNG is byte-exact and conversion repeats byte-identically. The
creator supplied no general redistribution license, so the asset is added only
to a separate personal Owner-beta input, never the ordinary Gradle JAR.
The source breakdance choreography dips below the bind-pose floor; conversion
preserves it. Offline preview grounding is documented in `model-import/SAHUR-CONVERSION.md`.

Four artifacts: `build/module-qa/release-package/owner-beta-20260907-sahur-01-release`.

| Artifact | SHA-256 |
| --- | --- |
| bootstrap-a.jar | 6a99b600f217a911fedab8ca7bf2180826d4ae6b93727fc2b2f4374e720dba61 |
| bootstrap-b.bin | 1d14cf429d79766f2369852ef126fc12a2f4a2ec18cf9f82553b76bb6db888ba |
| payload-a.bin | 34a70f6960c9d85d8722b54412c2b3a0bb6dac409860c4750338ea56904dd5d5 |
| payload-b.bin | dbafdd954561bd9d3f89fcb87b38d2c729bdbca3476a9a5aca9cbdcdf4eeeb72 |

Runtime digest: `384487e6ab7907e29ba67a9cf628c4d9f177f979d4f4d20c3bf6b19fc56171dd`.
Live catalog: `2c021072db5b117d7829354660ec72c3467ef290236b8132b2b1735176c96e74`.
Protected client JAR: `11444d34a78544e98870bbf632eeb5e5050d52de45e4475790cd6e44c75d48f4`.

Incremental build passed. Raw importer/pose/controller: 97/341/73 checks; real
asset: 521 checks. Exact protected JAR passed all 1,037 checks including bundled
resource equivalence and class-load origins. Protected A/B: 39 checks; A support
154 plus fixture IPC 36. Two-part retained-memory integrity/provenance passed,
conserving 714 source entries into 684 retained entries with 536 application
classes and six support libraries. Protection changed 464 client implementation
classes and protected 2,032 unique strings; model/texture resources stay byte-exact.

Live health 200; unauthenticated manifest 401; actual deployed server catalog
validated UID 7, release, runtime and all four artifact hashes. All 23 staged
server application/production files match v66 byte-for-byte. Rollback is v66.
The native beta EXE remains the same binary listed below.

Evidence: `build/module-qa/20260907T153305Z-f0694d2c/result.json`,
`build/module-qa/model-import/protected-20260907-sahur-01/result.json`,
`build/module-qa/model-assets/sahur-converted/java-checks.log`.
The rendered model previews are actual Blender renders, not in-game evidence.
Live edited-beta activation and scene verification are blocked: the dedicated
Modrinth QA profile was restarted (PID 11748), then the existing beta EXE's
normal Authenticate / attach flow reported "No saved Owner activation was
found" and "No attachment occurred". No license binding was reset or bypassed.
Enter the existing Owner key in that launcher to continue game testing.

## Previous: Player Models, September 7

Release `owner-beta-20260907-models-01`, client `1.0.0-beta.2`, deployed as Fly **v66**
for **Owner UID 7 only**. Image:
`registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1Y262F14D60201MFKS8P3EG`.

Visuals → Player Models → Load model imports a local self-contained GLB, with
Self/Others/Everyone selection, proportional size, textures, skinning and
stationary kill-animation playback. No custom entities or action packets are
created. First-person hands/GUI previews retain vanilla. [Usage](model-import/USAGE.md).
The requested creator ZIP is login-gated and has not been obtained; Sahur and
its five clips are **not bundled**. Actual edited-beta game testing is pending.

Same native beta EXE and SHA below; restart Minecraft before using this release
because the resident visual bridge changed. Four final artifacts are under
`build/module-qa/release-package/owner-beta-20260907-models-01-release`.

| Artifact | SHA-256 |
| --- | --- |
| bootstrap-a.jar | f854e8982bb511552eaa4b6fd083a4fa489445ea79d760bc56a036e4bc72c323 |
| bootstrap-b.bin | a3bab98ef2779b0df06b810624e20d6212a504b6f21ddfa2224b2daff4b9273c |
| payload-a.bin | 7acc20ff3e52ae17aa2073c3b25e620b9542f28fabf1d0cfd822ed0f93ebb3a4 |
| payload-b.bin | 37c521d5680bb9799763e2add97bf1b77c7b73f93a997e4d9fdd51e6bc97093f |

Canonical runtime digest:
`5542e16a76da1b85577f29e4f963bd226651da8763c93ad35e6f1c1e59bb9b9b`.
Live catalog digest:
`caf4ac37487a4b9aa57eb266f29cac818d13435c3f936d7c67d24c2f8067d192`.

Incremental build passed. Model fixtures passed 97 import, 341 pose, 66 playback
checks both normally and against the exact protected JAR (class-load origins
verified). Hook fixtures passed 224 checks; actual shaders passed 18 offscreen
GPU checks on the RX 6950 XT, including reflected-index recovery, alpha, fog and
depth. Actual A/B protected archives passed 39 checks; A passed 154 support/hook
checks plus 36 Attach IPC checks on a self-created fixture process. The retained
two-part/integrity validator and provenance checks passed for 534 application
classes, six support libraries, and 680 retained entries.

Client protection transformed 462 implementation classes and 2,024 strings;
A protected 46 classes/792 strings and B four classes/41 strings. The compatible
pass preserves names/ABI and stable resources; it does not virtualize Java into
native code. Protection ran before splitting and final hash creation.

Live health is 200, unauthenticated beta manifest is 401. The deployed server's
own catalog validator checked all four hashes, UID 7, and the exact runtime.
Production manifest remains `51d6e73ce63517f82f9b4bcee293df71228c0e031b908c144c7d7d859b824010`.
All staged server application and production artifact files matched the previous
v65 deployment byte-for-byte; only beta packages and beta routing changed.
Rollback baseline is the v65 image/config documented below, not the historical
v64 example in the packaging README. The Fly SSH helper again emitted its local
handle-close error after returning the complete successful validation result.

Evidence:
`build/module-qa/20260907T134521Z-5dc07e45/result.json`,
`build/module-qa/model-import/protected-20260907-models-01/result.json`, and
`build/module-qa/player-model-shader-20260907-064424/player-model-shader-report.json`.
No game screenshot or authenticated beta activation is claimed by these checks.

## Previous baseline: September 6

Release: `owner-beta-20260906-01`, client version `1.0.0-beta.1`.

Built and deployed for **Owner UID 7 only**. Full authenticated feature activation
and in-game behavior remain unverified. Do not describe this as a completed
playable or reload/unload test.

## Native launcher

`native-launcher/target/release/CelesteOwnerBeta.exe`

SHA-256: `ff2a76b625179ca1c3a4b171b32ffb4f66cb708b2990e719659287abd21ca4aa`

The final dark theme was inspected in the actual Windows app. Eighteen native
behavior checks, formatting and the release build passed. The launcher uses the
existing signed, DPAPI-protected UID-7 activation when the license field is blank.
It never resets bindings automatically. The signed release ID and complete
catalog hash are carried through startup and checked again by Bootstrap A.

## Artifacts and server

Prepared payload: `build/module-qa/release-package/owner-beta-20260906-01`.
Final four artifacts: the same path with `-release` appended.
Isolated server context: `build/module-qa/server-stage/owner-beta-20260906-01`.

Canonical runtime SHA-256:
`5d39adfd0e8230170310157b40eff6f24dabe276da40b7042079df750fe4be4a`

Protection was applied before splitting and release hashes were computed from
the final artifacts. The compatible beta pass transforms bodies and strings,
preserving original class names and reflection contracts. It protected 1,695
client strings across 432 implementation classes, 787 strings across 46 external
bootstrap classes, and 41 strings across four internal bootstrap classes.
Canonical bridges, JNI/media, serialization models, authored assets and native
support remain byte-identical to their required source boundaries.

The retained two-part package passed the actual memory-loader/integrity validator
and independent provenance check: 504 application classes, six support libraries,
648 retained entries. The actual protected A/B archives passed 39 compatibility
checks; A passed 154 support/hook checks plus 36 real Attach IPC checks against a
self-created test process. The generated B helper/resource path passed 111 checks.

Fly app `qb0f90cf11f4d1d52`, machine `85d426c4429798`, release **v65** completed.
Image: `registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1WWE6RFJR5MNH5P1XSJF91K`.

Live `/healthz` returned 200. An unauthenticated beta manifest request returned
401. Running the server's own beta catalog validator on the deployed machine
verified all four artifacts, the expected runtime digest and UID-7 configuration.
The production manifest remains
`51d6e73ce63517f82f9b4bcee293df71228c0e031b908c144c7d7d859b824010`.
No production payloads, license records or installation bindings were replaced.
The SSH helper reported a local handle-close error after returning the complete
validation result; deployment and its health checks completed successfully.

Use the isolated staging process for another beta deployment; a normal deploy
from the original production Fly config does not preserve the beta settings.

## Current game evidence and next action

A new Modrinth profile, `Celeste Owner Beta QA`, uses Minecraft 1.21.11,
Fabric Loader 0.19.5 and Fabric API 0.141.4+1.21.11. Its ordinary Knot startup is
separate from the user's existing patched 0.19.3 startup metadata.

The actual protected Bootstrap A inspection succeeded against that running game:
PID 30256, original process start 1788749223814 Unix milliseconds. It reported the
correct canonical game directory and `celeste_loaded=false`. This inspected the
game but did not install feature hooks or start Celeste. PIDs are historical
evidence, never reusable targeting constants after a restart.

The native launcher is open with that profile/process selected. User handoff is
pending at **Authenticate / attach**. After authentication, observe readiness,
open the GUI, test a local world and internal resource behavior, then test Stop
and cleanup. A start response alone does not establish readiness or a passing
game test. No in-game screenshot of the beta is claimed yet.

Bootstrap B and both Celeste parts remain memory-backed. A small ordinary agent
and verified native/support JARs use local files. Assets are internal resource
manager inputs with no selectable texture-pack entry. One feature lifetime per
Minecraft process is admitted; restart before loading another release.
