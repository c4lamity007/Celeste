# Late visual hooks

`VisualHooksTransformer` prepares five existing `SkyRendering` methods,
`LivingEntity.getHandSwingDuration`, GUI batch flush, HUD selection, chat media
clicks, local rotation display and conditional player-model replacement for Minecraft 1.21.11 / Yarn build 6, in both
named and intermediary namespaces. It adds no class members and preserves the
other method bodies. Preparation rejects missing/duplicate methods, unsafe
rewrites of arguments consumed by return hooks, repeat installation and
already-present Celeste startup hooks. Tick-delta rewrites are allowed because
the sky and living-state return hooks do not read that argument. When a living
state method writes an entity/state argument slot (including Fabric/MixinExtras
LocalRef round-trips), two fresh locals retain the original entry references
for the return callback. Expanded frames carry those locals through branches
and exception handlers, with no class members or allocations added. Methods
without reference-slot writes retain the original fast path. Sky/swing argument
guards still reject consumed-slot rewrites, including overlapping wide stores.
Original stack-map frames may discard arguments once vanilla stops reading them.
`HookArgumentFrames` keeps unchanged callback references declared in every frame,
including chopped/empty frames; this changes verification metadata only. It
rejects overlapping writes before retaining an argument and does not guess class
hierarchies or recompute unrelated frames.

`VisualHookBridge` is a JDK-only host boundary shared with the game. It connects
one runtime on the client thread, preserves vanilla calls from other threads,
and releases its payload handler on close. A failing callback restores vanilla
presentation until the runtime reconnects. Unchanged vanilla swing values are
preserved, including zero/negative values caused by high Haste effects.

`BetaVisualHooks` binds the bridge to actual authenticated Celeste features.
The late entrypoint registers it for lifecycle cleanup. `SkyboxHooks` holds weak
identity-based renderer state outside the target class, and is shared with the
ordinary startup mixin. Vanilla sky layers are skipped only after a successful,
still-current Celeste sky draw. Frame extraction and cleanup discard ownership.

Living state extraction also captures the entity/state association before the
local-rotation early return. Both startup and late hooks consult
`PlayerModelsFeature.replaceVanilla(state)` at the concrete living `render`
HEAD; only a ready, eligible replacement suppresses vanilla. The late JDK-only
`replacePlayerModel(Object)` bridge defaults to false and restores vanilla on
inactive/off-thread/closed/failed dispatch. It does not change the renderer's
class schema or the synthetic EntityRenderState forwarding overload.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --no-daemon --console=plain --offline -I beta-lab/visual-hooks/check.init.gradle betaVisualHooksCheck
```

Checks inspect the actual game class schemas and execute transformed synthetic
classes in the JVM, covering fallbacks, cancellation, all swing returns, thrown
original methods, exact GUI flush placement, thread routing, close and classloader
identity, ready/unready/ineligible player states and player-model failure fallback
(focused executable checks). The host still
must install these hooks; this is not in-game shader/GPU verification and does
not port the remaining mixins.
