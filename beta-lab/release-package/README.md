# Actual two-part beta packaging

This directory prepares an actual **intermediary-remapped Celeste client JAR**
for the existing two-part memory loader. Production packaging, loader artifacts,
native verifier, auth settings and deployment are untouched. The prepared local
candidate is not a runnable beta or a server release.

## Prepare and validate actual bytes

```powershell
.\auth-service\.venv\Scripts\python.exe .\beta-lab\release-package\package_beta.py prepare `
  --input .\build\libs\celeste-client-1.0.0.jar `
  --output .\build\module-qa\release-package\candidate-new `
  --release-id owner-beta-candidate-new --client-version 1.0.0-beta-test `
  --validation-only

pwsh -NoProfile -NonInteractive -File .\beta-lab\release-package\validate.ps1 `
  -PackageDirectory .\build\module-qa\release-package\candidate-new

.\auth-service\.venv\Scripts\python.exe .\beta-lab\release-package\check_provenance.py `
  .\build\module-qa\release-package\candidate-new
```

Use a fresh output directory every time; an existing candidate/release is never
overwritten. `--validation-only` explicitly uses a fixture origin/public key in
the packed auth descriptor and **cannot be assembled into a server catalog**.
For an eventual real release omit that flag and supply the existing public
`--base-url` and pinned `--server-key`; neither is a server signing secret. The
descriptor uses the existing production packager's actual format and canonical
digest is calculated after all boundary changes. It is then written into the
sole excluded mutable descriptor resource.

The validator compiles the actual `TwoPartMemoryClassLoader`, production
`ArtifactIntegrity` and `Crypto` with only a compile-time CelesteClient stand-in.
It opens the actual two generated archives, checks every retained part through
the real integrity adapter, checks exact host-support byte equality, caller
buffer isolation and failure after storage closure. It does not execute a
Celeste entrypoint, define Minecraft classes, authenticate or start the game.
`java-validation.json` records this distinction. The independent conservation
check compares every source entry against its exact retained destination and
recomputes canonical framing without importing the packager implementation.

## Exactly two payloads, explicit parent support

`payload-a.bin` and `payload-b.bin` are ordinary disjoint ZIP archives. Namespace
prefixes are derived from the actual compiled class names, not hardcoded to a
pre-obfuscation package. `package.json` records those prefixes, original source
hash, actual entrypoint, archive digests, full source provenance, limits and
constant-pool/declaration class dependency owners.

The existing parent-only material is handled explicitly:

- Original nested JNA/OSHI JARs remain byte-for-byte intact at their original
  `META-INF/jars/` paths **inside the retained payload maps**. Exact copies under
  `host-support/` are inputs for the resident bootstrap host. The memory loader
  treats these nested JARs as integrity-backed resources; it does not discover or
  load classes inside them automatically.
- `dev.redstones.mediaplayerinfo` JNI wrappers and both media DLLs are preserved
  inside `celeste-beta-media.jar`. Canonical `dev.celestebeta.host` classes are
  preserved inside `celeste-beta-bridges.jar`. Both generated support JARs also
  remain inside the retained payload maps under `META-INF/celeste-beta/host-jars/`.
  Every original class/native byte is conserved; no auth/native code is stripped.
- The generated host-support contract contains each resident JAR's exact hash
  and corresponding retained backing entry. The host must verify those copies
  against the actual retained payload before resolving them. Therefore moving
  support out of the child class namespace does not remove its bytes from the
  client's canonical memory-integrity input.
- Root Fabric/mixin metadata is inert under `META-INF/celeste-beta/source-root/`.
  The input currently accidentally packages `.bak`, `.new`, and `.orig` mixin
  metadata from source resources; these too are preserved as inert provenance,
  with no registration. No source files are deleted. No resource-pack profile
  or chooser entry is created.

The resident host must build this classloader relationship:

```text
JVM bootstrap: exact canonical JDK bridges only
  Minecraft/Fabric game loader
    resident support loader (exact JNA/OSHI/media URLs)
      bootstrap-B / the two-part Celeste runtime
```

Use the actual game loader as the support loader's parent. The support loader
must own `com.sun.jna.`, `oshi.`, and `dev.redstones.mediaplayerinfo.` so an
unrelated game library version cannot silently satisfy those classes. Its URLs
are listed in `host_support.resident_url_classpath`; use the six preserved
`host-support/` inputs, with **only** `celeste-beta-bridges.jar` appended through
the canonical bootstrap bridge route. Other dependencies are ordinary resident
URL entries, not bootstrap additions. JNA/JNI retains its existing standard
native-library loading/extraction behavior; this packaging does not implement
fileless native loading or weakening/removal of that auth support.

The child memory loader owns the generated application package/resource prefixes;
all game/Fabric/platform and resident support classes are parent-resolved. The
packager rejects overlapping/missing child-owned classes and unclassified
external class references. `parent-classes.txt` lists every statically referenced
game/platform class that the real parent must expose. Reflective string targets
are not a complete static dependency graph and remain part of runtime QA.

An optional `-ParentClasspathFile` for `validate.ps1` accepts one explicit JAR or
class-directory path per line and resolves those required external classes
without initialization. This checks the supplied local classpath, not actual
Knot delegation. The default check reports `parent_runtime_checked=false`.
Actual parent identity/delegation still requires the selected-game linkage probe.

Default-package obfuscation, client multi-release/module entries and previously
unknown native locations are rejected rather than silently omitted. The current
release profile's future native-transpiled auth output therefore needs an explicit
native-resource/host contract before such an artifact can pass. Obfuscated class
paths are supported when their descriptor/entrypoint is updated and they retain
legal non-overlapping package ownership; class names alone do not establish that
native or reflection linkage works.

## Optional final assembly

Preparation never writes `beta-release.json`. Once actual bootstrap-A and B
exist, `package_beta.py assemble --package ... --bootstrap-a ... --bootstrap-b ...
--output ...` verifies the Java validation receipt and archive hashes, rejects
fixture auth candidates/diagnostic linkage agents, requires the manifest roles
`Celeste-Beta-Bootstrap: A` and `Celeste-Beta-Bootstrap: B`, and verifies every
support input is embedded unchanged in bootstrap-A at the contract's
`bootstrap_entry` path. It then emits exactly the four filenames and the existing
`celeste-beta-release-2` server schema, within the server's size limits.

The actual bootstrap-A implementation must materialize and verify its resident
URL/native support inputs through its documented local runtime path; packaging
them inside the agent alone does not make nested JARs usable. Bootstrap-A/B
implementation, runtime authentication, hook coverage, local game QA and explicit
owner-only server staging remain separate. Assembly itself performs no signing,
server configuration, upload, deployment or in-game verification.

## Isolated UID-7 server context

`stage_server.py` prepares a fresh Docker context only. It does not deploy, use
Fly credentials, read a database or `.env`, enroll/authenticate, touch a Minecraft
instance, edit `auth-service` configuration, or use the separate dirty
`native-verifier` tree. Run after protected payload/bootstrap assembly is final.

The context contains exactly 33 allowlisted files: 14 auth application sources /
templates / CSS, five build/config files, nine production release files and the
five-file assembled beta catalog. All production manifest hashes/sizes are
verified, including the complete configured JAR. The legacy `CelesteLoader.jar`,
which is not described by that manifest, requires its own independently observed
hash pin. `.env`/`release.env`, credential stores, caches, test data and extra
files are neither read nor copied. A default-deny Docker ignore file contains
only exact filename exceptions. `staging-report.json` is local verification
metadata and is excluded from the Docker build context.

The generated Dockerfile preserves every production instruction, adding only
the separate `owner-beta` copy and read-only artifact permissions. The copied
Fly configuration changes only `CELESTE_BETA_OWNER_UID="7"` and
`CELESTE_BETA_RELEASE_DIR="/srv/auth/owner-beta/<beta-release-id>"`. Production
artifacts stay at `/srv/auth/releases`; neither directory contains the other.
No production release ID, digest, loader, stable channel or signing setting is
replaced. Do **not** run the production release packagers or import `release.env`
for this beta deployment.

The following production pins were checked against the healthy live **v64**
machine `85d426c4429798` on 2026-09-06. Recheck the current live image and hashes
immediately before staging/deploying: another launcher task may deploy later.
Do not roll a newer production release back by using stale staging inputs.
Replace `ASSEMBLED_RELEASE` and choose a fresh context directory:

```powershell
.\auth-service\.venv\Scripts\python.exe -B .\beta-lab\release-package\stage_server.py stage `
  --beta-release .\build\module-qa\ASSEMBLED_RELEASE `
  --output .\build\module-qa\owner-beta-server-context-01 `
  --production-manifest-sha256 51d6e73ce63517f82f9b4bcee293df71228c0e031b908c144c7d7d859b824010 `
  --legacy-loader-sha256 31029b20f974fcfc9fddd0256eceb61073f7eac0127de299348e708b3e7d2dcd

.\auth-service\.venv\Scripts\python.exe -B .\beta-lab\release-package\stage_server.py verify `
  --context .\build\module-qa\owner-beta-server-context-01

.\auth-service\.venv\Scripts\python.exe -B -m unittest discover `
  -s beta-lab/release-package -p test_stage_server.py -v
```

Output must be a new child of this project's ignored `build/module-qa/`; existing
outputs, linked inputs, traversal and digest mismatches fail closed. A failed
write may leave a partial new context: never deploy it; choose a fresh path.
`verify` rejects extra/missing/changed files and rechecks both catalogs. Its
receipt is an integrity inventory, not a signature or a live-server attestation.

Only after authorized release review, coordinate with any other deployment task
and deploy the isolated context (the staging script never runs this command):

```powershell
& "$env:USERPROFILE\.fly\bin\fly.exe" deploy .\build\module-qa\owner-beta-server-context-01 `
  --config .\build\module-qa\owner-beta-server-context-01\fly.toml `
  --app qb0f90cf11f4d1d52 --remote-only --ha=false --update-only `
  --only-machines 85d426c4429798
```

Check health, exact production artifact hashes/settings and the staged beta
catalog on the resulting machine before an Owner launch. A healthy `/healthz`
alone does not validate the beta catalog. UID 7 must already have an active,
unexpired license and matching bound device; the launcher still requires both
installation proofs. The beta shares the existing license/device and cloud
configuration data: a genuine signed beta integrity mismatch revokes the Owner
license, including production access. Test against a clean authorized Java 21 /
Fabric Minecraft 1.21.11 process without startup Celeste. Component and backend
tests do not prove a playable beta or cleanup in-game.

Rollback image observed at the audit (v64):
`registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1VVPHY4RTQ384N79Q6N1925`.
Record the actual immediately previous image/config before your deployment and
use those for rollback; do not blindly revert a newer concurrent release. For
the recorded baseline only, an explicitly authorized rollback uses the unchanged
production config, with no beta environment additions:

```powershell
& "$env:USERPROFILE\.fly\bin\fly.exe" deploy .\auth-service `
  --config .\auth-service\fly.toml --app qb0f90cf11f4d1d52 `
  --image registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1VVPHY4RTQ384N79Q6N1925 `
  --ha=false --update-only --only-machines 85d426c4429798
```

Existing production secrets and `/data` volume remain unchanged throughout;
rollback does not undo license/database mutations from a prior runtime test.
