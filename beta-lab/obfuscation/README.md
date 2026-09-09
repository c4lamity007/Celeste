# Beta-only obfuscation preparation

These profiles and staging tools are separate from production release profiles.
They do not invoke the obfuscator, alter authentication, sign, upload, deploy,
attach to Minecraft or prove a playable release. Root coordinates actual runs.
Obfuscation precedes payload splitting, runtime-layout creation and signing.

## Located compatible tool

The existing user project is
`C:/Users/c4lam/OneDrive/Documents/ChatGPT/Obfuscator` (Word Obfuscator).
Its built executable JAR is
`target/word-obfuscator-0.1.0-SNAPSHOT.jar`, SHA-256:

```text
f3faeb76f21ec83b728eaf9cab2e176724196d67ef1b331b65a1c21a420dcb28
```

The executable was built September 4, 2026, after the inspected `App.java` and
`JarObfuscator.java` sources. `App.runProfile` supports precisely:
`--config <profile.json> [--output <jar>]`. No input override exists. Relative
profile paths resolve against the profile's directory, not the shell directory.
The tool requires Java 21; all four explicit payload reference JARs were found.
The profiles disable nearby-JAR auto-discovery to avoid the duplicate production
loader/provider warnings in prior release logs.

No production profile, output or mapping is reused. The obfuscator automatically
reads the sidecar `<output>.mapping.json` when present: use new beta outputs and
mapping paths when changing the protection contract, and retain maps privately
for packaging/retrace. Never bundle them into A, B or payload archives.

## Protection contracts

| Profile | Transformed material | Preserved boundary |
| --- | --- | --- |
| `01-payload-compatible.json` | Initial experiment only: actual run froze all 515 classes (0 transformed, 0 class renames, 0 protected strings; 54 private Mixin handler renames) | Original candidate retained for comparison; not sufficient payload protection |
| `02-bootstrap-a-implementation.json` | A implementation-only thin JAR, including name-preserved `BootstrapHost` bodies | Original attach shim, shared delivery/memory ABI, Gson/ASM, canonical bridge bytes and all original resources are outside this pass |
| `03-bootstrap-b-compatible.json` | B bodies/strings and eligible internal class/private names | Exact `MemoryPayloadBootstrap` / `$PreparedGame` names and public ABI; retained mapping resource |
| `04-payload-protected-v2.json` | Preferred payload pass: eligible method-body/string protection with all original class/member names retained | All canonical bridges, Mixins, JNI wrappers, selected Gson models/WMI enums and every original resource are outside the pass and merged byte-exact |

All profiles disable Crazy mode, native extraction/virtualization (including
targeted auth), asset relocation and fragmented vaults. This avoids new native
loading contracts and closed-world public-member/descriptor transformations.
Ordinary methods remain Java. Disabling native protection here does not strip or
weaken the existing auth logic or authored media JNI support.

The initial full-payload pass used `transformKeptApplicationClasses=false` to
prevent decoder/bootstrap references in canonical classes. The actual run
demonstrated that global dynamic-reflection boundaries (including the bridge,
named/intermediary member resolver and FakeLag) freeze all 515 application
classes, so this configuration also suppresses all body/string protection.
Its JAR and sidecar are left untouched; v2 starts from the original remapped JAR.

V2 uses the same thin/stable split principle as A. Stable classes include
`dev/celestebeta/host/**`, all original `dev/c4lam/celeste/mixin/**`, native media
wrappers, `InstallCredential`, `HardwareFingerprint$*Property`, and
`FontData`/nested models. Every non-class entry is also stable. The thin archive
contains only the remaining original classes; its stable sibling is an explicit
reference JAR, never an obfuscator input. Canonical/JNI/schema bytes therefore
cannot acquire generated helper dependencies. V2 disables BOTH `renameClasses`
and `renamePrivateMembers` and enables `transformKeptApplicationClasses`.
The inspected tool then bypasses all name collection, but permits eligible kept
application bodies to receive moderate double/composite string protection.
Application keep patterns retain runtime parameter metadata as well. Dynamic
class/member lookups and untouched callers keep their original names; relative
resource rewriting is disabled along with class and metadata relocation.

`merge-payload` requires an empty class/field/method symbol map, every original
thin class path, no stable-boundary collisions, at least one changed original
class, and generated helper/resource entries. It restores every stable byte and
retains protected class/helper/vault bytes without rewriting their fingerprints.
It reports actual changed-class/helper/resource and compressed/expanded counts.
These are compatibility gates, not proof that every literal is hidden or that
the protected callbacks have passed game/performance QA. Inspect actual tool
string counts too; class names, reflection names, annotations and other unsupported
constants may remain visible. This pass intentionally promises no name obfuscation.

A instead removes stable material before using `transformKeptApplicationClasses=true`
on the thin implementation. Keeping only `AgentMain` in a full-JAR pass with
transforms disabled would transitively freeze `BootstrapHost` and much of A.
Conversely, transforming the attach shim could introduce external decoder/helper
dependencies at its JDK-only entry boundary. Staging avoids both problems.
`BootstrapHost.command(Instrumentation,String,String)` stays discoverable by the
original attach shim. Original A resources, including trust metadata and support
JARs, are restored byte-for-byte; protected implementation/helper bytes are also
copied without rewriting, preserving runtime class-fingerprint checks.

B keeps its two fixed class names. Its `load(MemoryRelease,PreparedGame)` and
capability methods, plus Session's `ready`, `stopped`, `stop`, `status`, stay public
with their existing descriptors. Public members are not part of the ordinary
private-member pass; Crazy mode is off. The tool may conservatively keep private
members on explicitly kept classes as well. B still gets body/string protection;
this is not a claim that every private member is renamed.

### Signed B helper ownership

`VerifiedBootstrapLoader` retains exact class/resource entries from the already
digest-verified B archive. Generated helpers need not use B's original package,
but cannot occupy JDK/game/Fabric/Mixin/canonical/shared-library namespaces.
Only classes actually retained by this loader use child-first resolution;
unrelated classes remain parent-resolved. Missing fixed B classes and its required
runtime layout never fall back to a parent copy.

The inspected tool emits ordinary Java 21 helper classes plus an opaque vault
at `META-INF/<random package path>/<identifier>.<identifier>` (not necessarily
`.bin`). RuntimeCrypto reads its exact own `.class` resource and vault bytes.
The loader therefore returns exact retained class/resource data without
rewriting it. Reserved metadata, services, root Fabric/mixin configuration and
native payloads are rejected. It does not grant arbitrary parent-resource access.

The moderate profile has one unfragmented shard and ordinary 16-class key chains,
not 150–192 helpers per application class. Current loader bounds remain 8 MiB
compressed, 512 entries, 2 MiB/member and 16 MiB expanded. Actual protected B
counts/sizes must be measured; source estimates are not a passed artifact test.

## Coordinated commands

Run from the Celeste project root. These commands are not an automatic release
pipeline. Begin with fresh output/staging paths; do not overwrite a prior
candidate or copy a production mapping beside these outputs.

```powershell
$betaJava = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe'
$betaObfuscator = 'C:\Users\c4lam\OneDrive\Documents\ChatGPT\Obfuscator\target\word-obfuscator-0.1.0-SNAPSHOT.jar'
$betaPython = '.\auth-service\.venv\Scripts\python.exe'

& $betaPython .\beta-lab\obfuscation\test_prepare_obfuscation.py
.\gradlew.bat --no-daemon -I beta-lab/bootstrap-a/check.init.gradle -I beta-lab/obfuscation/check.init.gradle betaBootstrapACheck betaObfuscationCompatibilityCheck

& $betaPython .\beta-lab\obfuscation\prepare_obfuscation.py stage-payload --source .\build\libs\celeste-client-1.0.0.jar --output .\build\module-qa\obfuscation\payload-stage-v2
if ($LASTEXITCODE -ne 0) { throw 'Payload staging failed' }
if ((Test-Path .\build\module-qa\obfuscation\payload-implementation-v2.jar) -or (Test-Path .\build\module-qa\obfuscation\payload-implementation-v2.jar.mapping.json)) { throw 'Use fresh v2 output and sidecar paths' }
& $betaJava -jar $betaObfuscator --config .\beta-lab\obfuscation\profiles\04-payload-protected-v2.json
if ($LASTEXITCODE -ne 0) { throw 'Payload body/string protection failed' }
& $betaPython .\beta-lab\obfuscation\prepare_obfuscation.py merge-payload --stage .\build\module-qa\obfuscation\payload-stage-v2 --protected .\build\module-qa\obfuscation\payload-implementation-v2.jar --mapping .\build\module-qa\obfuscation\payload-implementation-v2.jar.mapping.json --output .\build\module-qa\obfuscation\payload-protected-v2.jar
if ($LASTEXITCODE -ne 0) { throw 'Payload exact merge failed' }
& $betaPython .\beta-lab\obfuscation\prepare_obfuscation.py check-payload-boundaries --source .\build\libs\celeste-client-1.0.0.jar --protected .\build\module-qa\obfuscation\payload-protected-v2.jar
$betaEntry = & $betaPython .\beta-lab\obfuscation\prepare_obfuscation.py payload-entrypoint --mapping .\build\module-qa\obfuscation\payload-implementation-v2.jar.mapping.json --archive .\build\module-qa\obfuscation\payload-protected-v2.jar
if ($LASTEXITCODE -ne 0) { throw 'Mapped payload entrypoint validation failed' }
```

Supply `$betaEntry` as `package_beta.py prepare --payload-entrypoint` along with
the protected input JAR and the existing release's authorized public origin/key.
Its public methods remain `startFromRetainedParts`, `ready`, `stopped`, `stop`,
`status`. The helper rejects a mapping that unexpectedly renames those methods.
Packaging derives actual class/resource prefixes from the protected archive.
Build B with the resulting exact `runtime-layout.json`, and A with that candidate's
exact host-support and bootstrap-trust inputs, before the following commands:

```powershell
& $betaPython .\beta-lab\obfuscation\prepare_obfuscation.py stage-a --source .\build\module-qa\bootstrap-a\bootstrap-a.jar --output .\build\module-qa\obfuscation\a-stage
& $betaJava -jar $betaObfuscator --config .\beta-lab\obfuscation\profiles\02-bootstrap-a-implementation.json
& $betaPython .\beta-lab\obfuscation\prepare_obfuscation.py merge-a --stage .\build\module-qa\obfuscation\a-stage --protected .\build\module-qa\obfuscation\bootstrap-a-implementation.jar --output .\build\module-qa\obfuscation\bootstrap-a.jar
& $betaJava -jar $betaObfuscator --config .\beta-lab\obfuscation\profiles\03-bootstrap-b-compatible.json
.\gradlew.bat --no-daemon -I beta-lab/bootstrap-a/check.init.gradle -I beta-lab/obfuscation/check.init.gradle betaProtectedBootstrapArtifactsCheck -PbetaProtectedA=build/module-qa/obfuscation/bootstrap-a.jar -PbetaProtectedB=build/module-qa/obfuscation/bootstrap-b.bin
```

The stable staging JAR is an explicit reference library for both A/B profiles;
it is not a new release artifact. `merge-a` refuses source/stable changes,
resource/class collisions, missing fixed Host identity or an existing output.
The final release inputs are the merged A, protected B and freshly repackaged
two protected payload parts from `payload-protected-v2.jar`—not either thin
implementation, either stable staging JAR, the old payload candidate or symbol maps.

## Evidence and remaining checks

`test_prepare_obfuscation.py` uses temporary synthetic archives. The Java
`betaObfuscationCompatibilityCheck` exercises generated-helper-shaped classes,
class/vault resource lookup, fixed entry boundaries and rejection cases without
running an obfuscator or attaching to Minecraft. It depends on
`compileBetaBootstrapA`; both init scripts must be supplied in the shown order.

After actual protection, inspect counts and byte equality, load the actual B
through the resident loader, verify reflective entry/capability/Session ABI,
then rerun packaging integrity/provenance and the coordinated actual artifact
checks. Re-sign only after the exact final hashes are established. These local
checks do not establish signed network delivery, selected-game behavior,
frame-time performance, native/GPU retirement or a completed beta rollout.

`betaProtectedBootstrapArtifactsCheck` measures the actual B archive, resolves
all its classes/helper declarations, verifies fixed/public ABIs, exercises A's
inactive status in an isolated loader and invokes B with an absent release.
That B call reads/decrypts its retained layout, then must reject the missing
release before any capability check, authentication, delivery or game activation.
The task does not call Attach, instantiate a game or start payload code.

Celeste module QA informed the separation between deterministic compatibility
evidence and actual game verification. No game screenshot is manufactured here.
