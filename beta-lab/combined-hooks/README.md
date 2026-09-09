# Combined offline hook preparation

This component composes the seven hook families without starting Minecraft,
attaching an agent, installing prepared bytes, changing authentication or
publishing a release. The host owns installation and rollback.

## API

`CombinedHooksTransformer` exposes `prepare(owner, bytes)`, `prepareAll(map)`,
`targets(intermediary)`, `supportedNames()` / `supportedOwners()`, and
`familiesFor(owner)`. The fixed 19-class Minecraft 1.21.11 target set supports
named and intermediary descriptors. Batch preparation is all-or-nothing; original
bytes and complete member schemas are preserved.

The host still verifies game compatibility, Fabric API availability, canonical
bridge/classloader identity, and absence of startup Celeste. Mod names, Fabric
versions and callback priorities are not admission allowlists. Mixin metadata is
a compatibility hint, not an authentication or artifact identity proof.

## Generic callback compatibility

A first, offline composition identifies methods actually edited by the families.
Only those baseline methods are inspected. A method belonging to an unrelated
mod cannot veto preparation.

For each ordinary Mixin cancellation guard, the adapter verifies:

- A same-owner merged helper invocation followed by the callback-info local,
  `isCancelled()`, its conditional branch, typed getter/cast where required,
  direct return, and adjacent continuation.
- The helper has one correctly typed CallbackInfo/CallbackInfoReturnable
  parameter, a void result and consistent static/instance invocation.
- ASM dataflow proves that this parameter and the guard use the same local value,
  including when captured arguments follow callback-info.
- There is one well-formed MixinMerged annotation. The nonblank origin and integer
  priority may name any mod/priority; helper names are not matched.

Callback-only handlers, captured locals (including long/double), static handlers,
multiple different mods, and multiple sites invoking the same helper are accepted.
Observers without cancellation guards are preserved without requiring an adapter.
Void, primitive, reference and array results are recognized. Normal return counts
are not pinned to vanilla. Helper bodies, decisions, guards and return values are
never rewritten.

Malformed or ambiguous guards and missing required operation anchors still fail
preparation. This does not guarantee compatibility with arbitrary overwrites,
redirects, nonstandard callback transformations or complete method wrappers.

## Return timing and cleanup

Mixin selects ordinary RETURN/TAIL injection points before inserting callback
cancellation returns. A late scan must therefore distinguish those new exits
from normal completion, irrespective of callback priority.

When guarded methods exist, preparation replays offline with classified exits:

| Hook | Policy |
| --- | --- |
| Interaction afterAttack / afterItem | Normal returns only |
| Visual extractSky / swingDuration | Normal returns only |
| Visual applyRotations | Final normal return only (TAIL) |
| Keyboard, item tick, network observers | Final normal return only (TAIL) |
| Block breaking and resource reload cleanup | Every reachable exit, including cancellation and exceptions |

Target methods carrying RETURN/TAIL hooks are first-family or disjoint from
earlier return-producing edits, preserving original return ordinals. The
Interaction → Rotation → FakeGamble → Gameplay composition order remains fixed.
Existing third-party callbacks retain their relative order. Celeste's late HEAD
hooks retain the existing prepend order; arbitrary high/equal-priority startup
HEAD ordering is not reconstructed from final bytecode.

Breaking returns converge on a shared epilogue outside original exception ranges.
Existing handlers get first opportunity to handle errors; cleanup runs once on
normal or exceptional completion and never catches itself. Live operand-stack
values discarded by an original return are also discarded before an epilogue
jump. This includes the original result retained under a cancellable RETURN
injection's replacement value. Original helpers/results remain untouched.

No game classes are loaded to compute frames. Instruction analysis is local,
and unchanged method bytes are copied through.

## Checks and limits

Run the focused Java 21 suites through the opt-in Gradle init scripts. The
combined suite includes real named/intermediary schemas and executable synthetic
callback fixtures: MaLiLib/Litematica, arbitrary new mod names/priorities,
observers, captured/static/omitted arguments, repeated sites, cancellation
results, all return types, normal/early/exceptional exits and dormant cleanup.
Additional executable fixtures cover sky, swing, renderer, keyboard and item
RETURN/TAIL semantics. Standalone family suites cover baseline hooks.

These are offline JVM fixtures, not captured modpack execution, a GPU test or a
new in-game demonstration. Publication must additionally test the exact
protected archive and verify signed delivery through the existing release
process. Full Minecraft restart is required to load an updated resident host.
