# Internal bootstrap composition

`MemoryPayloadBootstrap` consumes an existing verified `MemoryRelease`, rechecks
both payload artifact hashes while opening the two-part memory classloader, then
invokes the mapped `BetaPayloadSession` JDK-only entrypoint. Lifecycle readiness,
stop and status are returned to the outer host. Each load uses a fresh payload
classloader under the verified resident parent. Successful stop drops the runtime,
reflection methods and loader references while retaining a JDK-only stopped status;
failed cleanup retains them. Successful stop also closes the
retained loader; cleanup failure retains its bytes for the owning host to inspect.

`RuntimeLayout` must be embedded in the signed internal bootstrap after packaging
and obfuscation. Its class ownership and entrypoint mapping must describe the exact
payload. It is not user-entered configuration. The outer bootstrap owns verified
delivery, complete game-hook installation, resident library loading, canonical
bridge visibility and rejection of an already-installed Celeste runtime.

The parent supplies `BetaDeliveryClient`, `TwoPartMemoryClassLoader`, the game and
verified native/dependency support. The internal bootstrap artifact contains only
its own `dev.celestebeta.bootstrap.internal` classes. Build it with:

```powershell
.\gradlew.bat --offline --no-daemon --console=plain -I beta-lab/bootstrap-b/check.init.gradle betaBootstrapBJar
```

Output: `build/module-qa/bootstrap-b/bootstrap-b.bin` (a Java archive with a
transport filename). It is not a standalone launcher, and the outer bootstrap's
complete game capability is still an integration prerequisite. No beta release
is enabled or published by this build.
