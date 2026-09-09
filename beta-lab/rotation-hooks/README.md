# Rotation, movement input and selected-stack hooks

`RotationHooksTransformer.prepare(owner, bytes)` prepares nine existing method
bodies across six exact Minecraft 1.21.11 classes, in named and intermediary
namespaces. It does not install a transformer or attach to a game.

- ClientPlayerEntity: `tick` and `sendMovementPackets` yaw/pitch redirects, with
  the exact 1/1 and 4/4 original call counts.
- ClientPlayerInteractionManager: the `method_41929` item-use packet lambda's
  PlayerEntity yaw/pitch reads, preserving its separate local-player guards.
- Entity: movement yaw in `updateVelocity`.
- LivingEntity: glide vector/pitch and sprint-jump yaw. Active glide callbacks
  retain the original conditional getter timing rather than pre-reading vanilla.
- KeyboardInput: the final return instruction only, matching Mixin `TAIL`.
- PlayerInventory: selected-stack get/set HEAD cancellation, including the exact
  previous stack result and no visible selected-slot mutation.

The startup mixins and `BetaRotationHooks` share `compat.RotationHooks`. Late
keyboard writes use the existing protected Input field through the bounded
`KeyboardInputAccess` adapter. No field, method or accessor interface is added to
Minecraft. No extra persistent per-entity state is introduced.

The host must supply one canonical JDK-only
`dev.celestebeta.host.RotationHookBridge` visible to both Minecraft and the payload,
and connect/close `BetaRotationHooks` on the game thread only. Disconnected and
worker-thread getter calls dispatch to the existing public vanilla getters via
cached method handles; input/stack callbacks are inert. Bridge closure releases
the payload handler and refuses closure during a callback. Handler and vanilla
exceptions retain their original propagation rather than inventing cleanup or
fail-open feature semantics.

Exact schema/anchor checks reject unsupported methods, missing/changed call
counts, duplicate preparation and readable startup Celeste hooks. That readable
prefix check is not a global startup detector: obfuscated startup Mixin handlers
can have other names. The host must reject a startup Celeste installation before
connecting any late runtime. Other bridge families may be composed on the same
original class when their target methods do not conflict.

Run with Java 21 from the project root:

```powershell
.\gradlew.bat --no-daemon -I beta-lab/rotation-hooks/check.init.gradle betaRotationHooksCheck
```

The check reads actual named and intermediary class schemas and runs isolated
executable fixtures for getter timing, exact arguments/results, input tail,
exceptions, stack cancellation and connection lifecycle. It also exercises the
named Input setter and inspects the intermediary field. These are component
checks, not evidence that the edited payload is running in a Minecraft world.
