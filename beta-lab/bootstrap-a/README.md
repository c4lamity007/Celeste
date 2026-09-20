# External bootstrap A — local integration prototype

This is an ordinary Java 21 Attach API agent/CLI, not a new native launcher EXE.
It verifies the explicitly selected Minecraft/Fabric instance, signed beta
delivery, resident dependency copies, canonical bridges and complete hook
preparation before entering Bootstrap B. Compilation with `-Xlint:all -Werror`
passed, along with 154 archive/support/hook checks and 36 real Attach IPC checks
against a self-created test process.
No playable beta or mid-game Celeste demonstration is established by this work;
no public release, live entitlement or deployment has been enabled.

## Build and isolated check

```powershell
.\gradlew.bat --offline --no-daemon --console=plain -I beta-lab/bootstrap-a/check.init.gradle betaBootstrapACheck betaBootstrapAJar
```

The output is `build/module-qa/bootstrap-a/bootstrap-a.jar`. A complete release
build must supply `-PbetaPackageDirectory=<prepared-package-directory>` containing
the exact `host-support/*.jar` files and `bootstrap-trust.json`. An unconfigured
development JAR is not an authenticated release artifact. The isolated check
uses local archives, fixture class identities and an Instrumentation proxy. Its
IPC fixture also creates one nonce-bound plain Java child and exercises the real
Attach CLI/agent handoff against that child only. It does not attach to Minecraft,
append canonical test bridges, fetch a release or install gameplay hooks.

## Supported CLI shape

```text
java --add-modules jdk.attach -jar <verified-bootstrap-a.jar> --pid <selected-Minecraft-PID> --agent <same-verified-bootstrap-a.jar> --started-at <selected-process-start-Unix-ms>
```

Supply one UTF-8 JSON request on standard input, then close stdin. The CLI requires
the selected PID, agent path and original process start time in Unix milliseconds.
Capture that start time when selecting the process; do not silently replace it
with a newly observed value at attach time. The CLI rejects a mismatched start
time before reading the request or attaching, and the agent checks the same
expected PID/start pair again inside the target. The old four-argument shape is
not accepted. Installer credentials must never be placed in command-line
arguments or example files. The start-request JSON shape is:

```json
{"action":"start","installer":"<existing installer grant>","device":"<bound device ID>","uid":42,"support_directory":"<existing local support directory>","expected_game_directory":"<explicitly selected launcher instance directory>","expected_release":"<launcher-verified release ID>","expected_catalog_sha256":"<launcher-verified catalog hash>"}
```

First use `{"action":"inspect"}` with the same CLI/PID/agent shape. This read-only
game inspection returns `game_directory`, `minecraft`, `celeste_loaded`, `pid`
and `started_at` (plus phase `inspected`); it does not fetch or start the payload.
Inspection still attaches the ordinary local agent, which remains resident.
For `start`, `expected_game_directory` is mandatory and is compared with the
observed game's actual directory using `Files.isSameFile` before admission or
delivery. Supply the selected launcher's instance directory, not an inferred one.
The fetched signed catalog must match the launcher's expected release and catalog
hash before downloads or game changes. Preparation failures remain retryable
until resident bridge/native installation starts; later failures report
`restart required` instead of incorrectly reporting `inactive`.

Delivery uses RFC 9449 DPoP proofs from the existing installation signing key.
After the selected game directory is verified, Bootstrap A reads only the
existing `config/celeste/auth/celeste_plus/device.bin` written by the launcher.
It opens that bounded `CST1` envelope through the running game's existing JNA
Windows DPAPI API, verifies the installation certificate against the pinned
server key and expected UID/device, and proves the private/public key pair
matches. Missing Windows protection support fails closed. No private key is
added to the IPC request, command line, a new file, or a public key getter.
Legacy installation certificates remain usable; current installer/manifest
grants must contain the matching `cnf.jkt` and no bearer fallback is accepted.
The offline signer check exercises 18 synthetic certificate, key and identity
cases without accessing an actual saved installation or calling DPAPI.

Control requests are `{"action":"status"}` or `{"action":"stop"}`. Responses
report lifecycle state; a start response does not mean readiness, and a stop
response does not mean retirement has finished.
The handoff uses a bounded, nonce-bound loopback channel tied to PID/start time.
The CLI does not discover/select JVMs. Do not invoke it against the previously
captured game with startup Celeste already installed: use an explicitly selected
clean instance and preserve normal licensing/authentication.

## Compatibility and storage limits

- Requires Java 21, Minecraft 1.21.11 and a loaded Fabric API interaction module.
  Fabric API versions and hashes are not pinned. Detection uses Fabric Loader's
  loaded component list, so nested API jars are supported. Actual hook structure
  is validated before installation; incompatible targets still stop preparation.
  A startup Celeste runtime or unrelated resident bridge is rejected. After a
  confirmed full stop, the same A host admits another lifetime with fresh B and
  payload loaders, reusing only its exact verified bridges/native support and
  game loader. A different A/support contract or uncertain cleanup requires restart.
- Bootstrap A remains a local agent JAR. Bootstrap B and the two Celeste payload
  parts are validated and loaded from retained memory. Renderer resources come
  from those same payload parts, through the internal resource-manager adapter.
- Canonical bridges and exact signed JNA/OSHI/media support copies are ordinary
  resident local JAR files. Standard native-library loading may also use local
  files. This is not a diskless native-loading design or protection against
  inspection of usable client/GPU bytes.
- Bootstrap search append cannot be undone. Stopping waits for observed payload
  retirement before ordinary hook retransformation. Successful cleanup drops
  A/B reflection, payload and loader references; failed cleanup retains ownership.
  Isolated tests exercise two lifetimes and report observed payload-loader GC,
  which remains JVM-controlled. Canonical bridges/native support stay resident.
- Native EXE/launcher integration, full signed launch, playable behavior and an
  actual in-game demo remain unverified. Do not stage or publish this prototype
  as a working beta based on component checks alone.
