# Existing Minecraft member access

`MinecraftAccess` preserves the original startup accessor/invoker path and adds
cached MethodHandles for objects that were loaded before Celeste. The ten
members use exact named/intermediary aliases and runtime descriptors from the
project's Minecraft 1.21.11 / Yarn build 6. Missing, ambiguous, static or
wrong-type members reject preparation. No Minecraft fields, methods or
interfaces are added. Original exception identity is preserved.

`verifyLateMembers()` is the host preflight. This resolves access to existing
methods; it does not install the associated attack, rotation, inventory or HUD
hooks. Those separate hooks still need to be supplied by the beta host.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --no-daemon --console=plain --offline -I beta-lab/member-access/check.init.gradle betaMemberAccessCheck
```

30 checks pass against real named key bindings, the actual intermediary class
members, and isolated member-resolution cases. Key queries do not consume
queued presses; normal vanilla consumption and startup accessor priority remain
intact. The fixture uses Fabric's normal named-development package access fix.
It does not launch Minecraft, interact with a server or test gameplay timing.
