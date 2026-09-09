# Fog and animated-lightmap hooks

`AtmosphereHooksTransformer.prepare(owner, bytes)` prepares only the existing
world `FogRenderer.applyFog` and `LightmapTextureManager.update` method bodies for
Minecraft 1.21.11, with fixed named/intermediary mappings. It does not install
hooks, attach to a game, allocate GPU resources or change renderer selection.

Fog preserves the two existing startup branches:

- Immediately before the unique `RenderSystem.getDevice` call, the shared-data
  callback receives the current camera, world, FogData and color. Only the Sodium
  branch adjusts this shared input, before both consumers read it.
- At the exact world fog upload invocation, already-evaluated arguments are
  preserved in temporary locals. The vanilla branch can mutate the original
  color and the first two of six upload distances. The shared FogData is not
  changed on this path. Sodium skips this second adjustment.

The transformer derives the unique FogData and Vector4f locals from bytecode
dataflow, not debug names or fixed local indexes. Ambiguous or missing locals,
overwritten camera/world parameters and changed call anchors fail preparation.
The private upload overload, constructor, empty/NONE buffer and GUI path remain
unchanged. Existing stack-map frames are retained; additional temporary locals
do not change the class schema.

Lightmap HEAD sets the existing `dirty` field only when the existing Fullbright
fade is transitioning. Once settled, vanilla retains its normal dirty/update
budget. Only `Std140Builder.putFloat` arguments at ordinals 3 (night vision) and
6 (gamma) of exactly seven calls are adjusted. The gamma option, status effects,
other upload values and render pipeline are untouched.

Startup mixins and `BetaAtmosphereHooks` share `compat.AtmosphereHooks`. The host
must provide one canonical JDK-only
`dev.celestebeta.host.AtmosphereHookBridge` visible to the game and payload and
connect/close the runtime on the game thread. Disconnected or worker-thread calls
are inert. Existing callback exceptions propagate; no new visual fallback or
cleanup policy is imposed.

The host must separately reject a startup Celeste installation; readable
`celeste$` method checks cannot detect all obfuscated Mixin handlers. This
component does not establish compatibility with every other renderer or mod.

Run with Java 21 from the project root:

```powershell
.\gradlew.bat --no-daemon -I beta-lab/atmosphere-hooks/check.init.gradle betaAtmosphereHooksCheck
```

Actual class-schema and executable fixture checks are not a replacement for
build-identified local-world tests of both vanilla and Sodium rendering paths.
