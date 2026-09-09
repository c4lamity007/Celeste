# Existing local inventory simulation hooks

`FakeGambleHooksTransformer.prepare(owner, bytes)` prepares ten original
Minecraft 1.21.11 method bodies in seven classes, with fixed named/intermediary
mapping pairs. These callbacks preserve the existing Fake Gamble module's
client-only dispenser/prize simulation; the feature implementation is unchanged.
No server inventory mutation, new gameplay feature or automatic installation is
introduced by this component.

HEAD cancellation remains conditional for slot clicks, empty-dispenser events,
fake-item pickup and item picking. The interaction observation, screen-prize
refresh and cursor stash remain non-cancelling HEAD callbacks. Item physics and
both network synchronization callbacks use only the final return instruction,
matching Mixin `TAIL`, not all early returns or exceptional exits.

Two startup mixins share the factored world-event/item-physics helpers. The other
six already called shared feature methods directly and did not need source
changes. The late world callback uses the existing private final
`WorldEventHandler.world` field and existing public event method, retaining the
nested secondary dispenser events. Item physics retains the original moved-item
2-tick vs stationary 40-tick merge cadence through the original no-argument
private `ItemEntity.tryMerge` method. Cached member handles require no new target
fields, methods, interfaces or external per-item state.

The host must provide one canonical JDK-only
`dev.celestebeta.host.FakeGambleHookBridge` visible to the game and payload, then
connect/close `BetaFakeGambleHooks` on the client thread. Reentrant world-event
callbacks are supported; closure during a callback is rejected. Disconnected or
worker-thread callbacks leave vanilla behavior untouched. Original callback and
method exceptions propagate without invented normal-return cleanup.

Preparation preserves all class schemas and existing frames, rejects duplicate
hooks, missing targets and unsupported method shapes, and leaves other methods
unchanged. The host must separately exclude all startup Celeste installations;
the readable `celeste$` guard alone cannot identify obfuscated startup handlers.

Run with Java 21 from the project root:

```powershell
.\gradlew.bat --no-daemon -I beta-lab/fake-gamble-hooks/check.init.gradle betaFakeGambleHooksCheck
```

The check uses actual named/intermediary schemas plus isolated executable timing,
cancellation, argument/return, reentrancy and lifecycle fixtures. It does not
constitute a build-identified in-game demonstration or runtime screenshot.
