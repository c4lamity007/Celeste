# Internal resource reload hooks

`ResourceReloadTransformer.prepare` handles the exact named/intermediary
Minecraft 1.21.11 client class. It modifies two existing method bodies: the
selected resource-pack input and the final overlay result callback. Fields,
methods and interfaces remain unchanged, and untouched method bytes are copied.
Missing, duplicate or changed insertion patterns fail before any bytes are
installed. The utility does not install an agent or redefine a running class.

The host must make one canonical `dev.celestebeta.host.ResourceReloadBridge`
visible to Minecraft and the payload. `assertVisibleFrom` verifies class identity.
The bridge associates reloads with exact client/future identities, preserves
vanilla defaults when dormant, supports the overlay's same-token error replay,
and prevents close while callbacks remain live. A submission that throws before
the overlay exists can release its exact token through guarded abandonment;
the host still retains asset bytes until their resource views are retired.

`MinecraftInternalResourcesHost` supplies the payload binding. It augments only
resource-manager inputs and creates no selectable pack profile. A failing owned
reload first retries with the selected ordinary packs. Independent cleanup
failure retains vanilla recovery. Cancelling an observer does not cancel a
running reload or establish that GPU/resource cleanup has finished.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --no-daemon --console=plain --offline -I beta-lab/reload-hooks/check.init.gradle -I beta-lab/resource-provider/check.init.gradle betaReloadHooksCheck betaInternalResourceIntegrationCheck
```

114 transformer/bridge checks, 169 internal resource-layer checks and 68 host
checks pass. They cover actual mapped class schemas/resource managers and
executable JVM fixtures, including synchronous submission failure. These checks
do not demonstrate hook installation or rendered assets in a running game.
