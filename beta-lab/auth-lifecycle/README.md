# Auth lifecycle regression harness

Run from PowerShell with Java 21 available:

```powershell
.\beta-lab\auth-lifecycle\run-tests.ps1
```

The runner compiles the **current production** `AuthBootstrap.java` and
`LicenseIdentity.java`, not copies, with small fake Minecraft/Fabric/auth
collaborators. Every scenario runs in a fresh JVM so the production static
executor and terminal lifecycle state are isolated. No network, native code,
game process, real license, credential file, or game instance is accessed.

The fake client executes immediately on its owner/game thread and queues work
from other threads. Tests explicitly drain that queue and assert callback
thread ownership. Reflection drives private bootstrap boundaries and reads
state only inside these fixture JVMs; it does not patch production code.

Coverage includes deferred startup, explicit late startup, once-only start,
duplicate ownership rejection, runtime/linkage initialization failure cleanup,
stop/revocation inside initialization (both direct and queued cleanup),
queued authorization during shutdown, terminal revocation, startup integrity,
heartbeat grant refresh, 401/403/409 rejection, 95-second grace policy,
heartbeat tamper checks, and a heartbeat response arriving after shutdown.
Beta scenarios also verify one-shot catalog selection, mandatory retained memory
before any digest/network work, and immediate lock on invalid signed context.
The default suite also waits for the real production 30-second heartbeat
schedule. `-SkipTimedHeartbeat` runs just the deterministic fast cases and
does **not** verify the real scheduler timing.

This is a unit-level lifecycle test, not proof of cryptographic verification,
network correctness, Minecraft/Fabric compatibility, resource/mixin linking,
or in-game behavior. Production auth collaborators are deliberately stubbed;
tests assert the bootstrap still invokes their gates and fails closed.
Compiled classes remain in this directory's ignored `build/` tree.
