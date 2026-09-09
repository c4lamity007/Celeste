# Late interaction hooks

Prepares five existing ClientPlayerInteractionManager method bodies in named or
intermediary Minecraft 1.21.11 classes. No fields, interfaces or methods are added.
The transformer accepts supplied class bytes; installing hooks must use the
actual modded game definitions, never replace them with cached vanilla classes.

- Selected-slot synchronization reads the original slot once, then projects the
  server slot through the shared startup helper.
- Block breaking pairs nested begin/exit restoration, including canceled and
  exceptional exits, matching the startup whole-method finally wrapper.
- Entity attacks preserve sprint capture before the original attack and the
  existing post-attack behavior.
- Item use preserves fluid capture and the exact ActionResult return object.

The startup mixins and late handlers use the same helpers. Per-manager state
uses weak identity keys outside Minecraft's class schema. The bridge is active
on its owning client thread and passes through elsewhere. Disconnect clears its
handler and the helper state. Attacks and item use retain original HEAD/normal-
RETURN behavior: their original method exceptions do not run RETURN callbacks.
Breaking now corrects the inherited projection-token leak using a startup
MixinExtras `WrapMethod` finally and schema-preserving late exception cleanup.
Cleanup itself is outside the added catch ranges and is never retried. Existing
handlers retain precedence; breaking returns inside an existing handler range
are unsupported because cleanup there would not match the startup wrapper.
This is not a claim that the whole payload can already unload.

Run the affected check:

```powershell
.\gradlew.bat --offline --no-daemon --console=plain -I beta-lab/interaction-hooks/check.init.gradle betaInteractionHooksCheck
```

The fixture covers actual named/intermediary schemas and JVM-executed ordering,
return identity, exception cleanup, wrong-thread and detach cases. The expanded
cleanup checks must be rerun after this correction. Real Minecraft hook
installation remains an integration step.
