# Actual Minecraft linkage diagnostic

This is an explicit local Java 21 diagnostic agent and Attach API CLI. It does not download/load a Celeste payload,
install gameplay hooks, authenticate a license, select/discover a process, edit
logs, or persist a launcher/JVM configuration change.

It addresses the remaining concrete unknown: whether the selected Minecraft
loader can resolve the exact canonical bridge definitions, and what target class
bytes/schema actually exist after that instance's mods transformed them.

## Build and isolated check

```powershell
pwsh -NoProfile -NonInteractive -File .\beta-lab\minecraft-linkage\build.ps1 -RunFixture
```

This invokes the Java 21 tools directly, without Gradle. Every compilation uses
fresh output directories so removed source classes cannot remain in a packaged
bridge. The two resulting artifacts are:

- `build/module-qa/minecraft-linkage/celeste-linkage-probe.jar`: ordinary diagnostic
  agent plus CLI, using only JDK APIs and the `jdk.attach` module.
- `build/module-qa/minecraft-linkage/celeste-linkage-bridges.jar`: the current
  JDK-only `dev.celestebeta.host` classes. No Minecraft, payload, native or other
  dependency classes are appended.

The optional check starts only its own child JVMs, binds each with a fresh nonce,
and stops those children afterward. The positive synthetic target has a separate
game class loader, expected Fabric metadata, and an extra field representing
already-transformed target schema. The negative target is an ordinary JVM with
no Minecraft classes. Checks cover canonical visibility, byte/schema export,
unchanged existing instance state/method behavior, and denial before bridge
append for the non-Minecraft target. This is **not real Minecraft verification**.

## Explicit selected-game invocation

Choose the intended already-running Minecraft **1.21.11 / Fabric / Java 21** PID
through the user's normal game selection context. This CLI never lists JVMs or
chooses one. Supply a fresh output directory whose parent already exists beneath
the explicit project workspace. Replace `12345` with that selected PID:

```powershell
& 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe' `
  --add-modules jdk.attach `
  -jar '.\build\module-qa\minecraft-linkage\celeste-linkage-probe.jar' `
  --pid 12345 `
  --workspace 'C:\Users\c4lam\Downloads\New folder (5)\Celeste - Updated' `
  --output 'C:\Users\c4lam\Downloads\New folder (5)\Celeste - Updated\build\module-qa\minecraft-linkage\selected-game-01' `
  --agent '.\build\module-qa\minecraft-linkage\celeste-linkage-probe.jar' `
  --bridge '.\build\module-qa\minecraft-linkage\celeste-linkage-bridges.jar'
```

The explicitly selected Modrinth game was captured successfully as PID 25244;
that PID is historical evidence, not a target to reuse without fresh selection.
Do not treat fixture checks or an old PID as permission to attach to another JVM.

## Sequence and report

1. CLI requires an explicit positive external PID, observes its process start
   time, creates the new workspace output directory, and binds a random nonce.
   It asks Attach API for system properties but inspects only
   `java.specification.version`. It does not inspect command lines, launch
   arguments, environment variables, account data, instance values or credentials.
2. The agent checks its own PID/start time against the request. Before appending
   anything it requires one already-loaded named/intermediary Minecraft client,
   a loaded Fabric API visible from that game loader, and Fabric's `minecraft`
   metadata identifying 1.21.11. Fabric loader version is reported for review.
3. The agent verifies the local bridge JAR digest/content, appends that small
   JAR through the standard bootstrap search API, and compares every bridge class
   identity as resolved through the bootstrap, actual game and agent loaders.
   Existing bridge definitions from another registration are rejected. The same
   diagnostic may be repeated with the same bridge artifact; a different bridge
   requires a fresh JVM.
4. For the 21 current target class identities, the agent records loaded/absent and
   modifiable state. It does not force absent game target classes to load. A
   temporary retransformation observer captures the actual selected definitions
   and **always returns null**. No cached vanilla bytes or class replacements are
   supplied. The observer is removed in `finally`.
5. `report.json` binds the receipt to the nonce/PID/start time and records the
   game loader, versions, bridge identities, target status, SHA-256, byte lengths,
   and full declaration schema (access flags, superclass, interfaces, fields and
   methods). The corresponding captured definitions are under `classes/`.
   CLI checks its receipt binding before reporting success.

Every report includes an important capture limit: retransformation also invokes
other capable transformers registered in the JVM. Our observer never changes
bytes, but cannot promise those other agents do nothing. Captured bytes are the
view at this observer; a later registered transformer may alter its subsequent
output. Existing Knot/Fabric definition changes are represented in the captured
schema; future hook preparation must preserve those additions. A `captured`
status does not claim every module target is loaded/modifiable or every later
hook pattern will match; inspect those explicit fields.

Bridge append/class definitions remain resident for the lifetime of that JVM;
Java has no removal API for bootstrap search additions. Failure after append is
reported as such and must not be described as rolled back. No files/configuration
are installed into Minecraft or its launcher. Java's ordinary dynamic-agent/CDS
warnings remain visible. If Fabric/Knot refuses the canonical bridge identity,
the report fails at `bridge-linkage`; no delegation bypass is attempted.

The local report and captured classes are diagnostic project artifacts. They
contain code/declarations, not game object values or login/session data. No
network requests are made.

Latest isolated result: **13 checks passed** on two self-created synthetic JVMs.

The selected real-game report at
`build/module-qa/minecraft-linkage/selected-modrinth-20260906-1726/report.json`
records status `captured`, PID **25244**, Minecraft **1.21.11**, Fabric Loader
**0.19.3**, and `KnotClassLoader`. It verified **16 canonical bridge classes**,
captured **17 target definitions**, and found every loaded target modifiable.
The observer returned null throughout; no feature hook replacements were applied.

That game already contained startup Celeste: captured declarations include
`celeste$invokeDoAttack`, `celeste$getAttackCooldown` and other Celeste accessors.
The report therefore proves selected-game bridge visibility/schema capture,
not a clean late-load installation or a playable beta. Do not attach a second
Celeste runtime there. Canonical diagnostic bridge definitions remain resident
until that JVM exits; no gameplay hooks or beta release were enabled by the probe.
