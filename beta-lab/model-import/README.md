# GLB model import and pose fixtures

This is an offline visual-asset reader, not an entity, script or Java plugin
loader. It never follows buffer/image URIs (including data URIs). A user-selected
regular file is read once, with a hard 64 MiB cap. Geometry, embedded images and
animation data remain cached; the renderer owns file selection, GPU upload,
player alignment, visual replacement, clip choice and retirement.

## Renderer API

```java
GlbModel model = GlbImporter.load(path); // IOException includes rejection reason
GlbPose pose = new GlbPose(model);       // one reusable pose per animated instance
pose.sample(clipIndex, seconds, loop);  // -1 selects/reset to bind pose
for (int nodeIndex : model.sceneNodes()) {
    GlbModel.Node node = model.nodes().get(nodeIndex);
    if (node.mesh() < 0) continue;
    for (GlbModel.Primitive primitive : model.meshes().get(node.mesh()).primitives()) {
        // Allocate these once per rendered primitive, not every frame:
        // positionsOut/normalsOut both have primitive.positions().length floats.
        pose.deform(nodeIndex, primitive, positionsOut, normalsOut);
        GlbModel.Material material = model.materials().get(primitive.material());
        // Upload/draw using cached primitive.indices()/texCoords(), material and outputs.
    }
}
```

Model arrays are owned cached data: consumers must not mutate them. Lists are
unmodifiable. Pose matrices/results are mutable scratch owned by that pose and
valid until its next sample; poses must not be sampled concurrently. Matrices
are column-major and retain glTF's right-handed, Y-up coordinates. Rigid output
uses the mesh node's world matrix. Skinned output uses the weighted
`jointWorld * inverseBind` palette and is already in model space: do not apply
the mesh node transform again. `nodeMatrix(node)` and
`skinMatrices(meshNode)` expose those cached matrices directly. Normals use the
inverse transpose of the effective linear transform and are normalized;
singular transforms produce a finite zero normal.

Primitive arrays: POSITION/NORMAL are float3, TEXCOORD_0 float2, indices int[],
JOINTS_0 int4 and WEIGHTS_0 float4 (both null for rigid vertices). Weights are
validated nonnegative and normalized once. Missing normals are generated from
the indexed triangles; missing UVs are zero only for untextured primitives.
Each material has a valid base-color factor, image index (-1 for no texture),
alpha mode/cutoff, double-sided flag, wrap S/T and magnification filtering flag.
Images are decoded once to top-to-bottom RGBA8 with straight alpha; the renderer
must interpret base-color channels as sRGB and implement UV wrapping as needed.
Minification mip-chain generation is a renderer choice, not an import feature.

## Supported subset and bounds

Supports GLB2 JSON+BIN, dense indexed TRIANGLES, float POSITION/NORMAL, float or
normalized unsigned byte/short TEXCOORD_0/WEIGHTS_0, unsigned byte/short JOINTS_0,
unsigned byte/short/int indices, interleaved attributes, TRS or affine
TRS-decomposable matrices, hierarchy/skins/inverse binds, embedded PNG/JPEG,
base-color materials, and LINEAR/STEP translation/rotation/scale clips.
Rotation interpolation uses normalized shortest-path quaternion SLERP. It
clamps outside a non-looping clip; looping wraps by the clip duration.
Selects the explicit default scene, else the first scene, else all tree roots.
Off-scene joint transforms are computed without drawing off-scene meshes.

Limits: 8 MiB JSON, depth 64, 300,000 JSON elements, 1024 nodes, 256 meshes,
1024 total primitives, 500,000 total primitive vertices, 1,500,000 indices,
64 skins with 256 joints each, 128 clips/4096 total channels, 100,000 keys per
channel, 64 MiB decoded geometry/animation, and 64 MiB total decoded image pixels
(32 images, at most 4096×4096 each). Runtime CPU/GPU budgets may be stricter.
All referenced indices/ranges, finite decoded values, key ordering, tree cycles,
multiple parents, matrix constraints, image dimensions and joint indices are
checked before a model is returned. Source and parser scratch are additional
bounded transient memory, not included in the decoded-data budgets.

Unsupported features reject with actionable messages: external/data URIs,
required extensions, Draco/meshopt or compressed textures, sparse accessors,
non-indexed/non-triangle topology, morph targets, more than four influences,
base-color UV sets other than zero, texture transforms, animation pointers,
matrix-targeted animation and CUBICSPLINE (resample/export LINEAR or STEP).
This is a base-color visual subset, not a complete PBR renderer: normal maps,
metallic/roughness, occlusion/emissive maps, vertex colors, tangents, lights and
cameras are not used to produce player visuals. Optional unrelated metadata is
ignored; required extensions are never silently ignored.

The coordinate, skinning and accessor conventions were checked against the
[Khronos glTF 2.0 specification](https://github.com/KhronosGroup/glTF/blob/main/specification/2.0/Specification.adoc).

## Coordinated checks

Root owns execution (no Gradle or game launch performed by this subtask):

```powershell
.\gradlew.bat --no-daemon -I beta-lab/model-import/check.init.gradle betaGlbImportCheck betaGlbPoseCheck betaModelAnimationsCheck
```

All three tasks depend on `compileBetaGlbImportCheck`, which explicitly compiles
only `Glb*.java`, `ModelAnimations.java` and fixtures (not the Minecraft renderer)
against the existing main compile classpath, using
Java 21 and `-Xlint:all -Werror`. Fixtures generate real GLB JSON/BIN bytes and
small standard PNG/JPEG images in memory, execute parsing/pose/CPU deformation,
and assert malformed-input failures. They do not create entities, open a
network connection, run model scripts, create a GL context or prove in-game
visual/animation/performance behavior. Celeste module QA requires the main
agent's identified-build local-world checks and a genuine screenshot separately.

`betaModelAnimationsCheck` additionally checks stationary kill selection occurs
once per event, frames advance without restarting/rerolling, movement/disable
cancels, clips end without looping (including the 20-second cap), unknown idle
or walk names use bind pose, and invalid/rewound clocks cannot revive a clip.
The controller fixture does not itself prove entity-death attribution or the
feature's asynchronous import, GPU cleanup or vanilla-render fallback lifecycle.


## Optional secondary motion

The player renderer adds a small local spring rotation layer to explicitly named,
weighted left/right breast or bust bone pairs, and terminal chest pairs without
limb/torso descendants. Generic Chest, UpperChest, Spine and torso bones are never
selected. Supported examples are `Breast_L`/`Breast_R`,
`LeftBreast`/`RightBreast`, and `J_Sec_L_Bust1`/`J_Sec_R_Bust1`.
Pairs require the same parent; unmatched, ambiguous, matrix-based or unweighted
bones stay unchanged. A model with no validated pair has no secondary motion.

`Secondary motion` defaults on with `Motion strength` at 0.5. State is isolated by
player UUID with at most 12 players and 8 joints per player. The spring reacts to
the acceleration of the sampled joint anchors; it does not create geometry or
change the player's actual entity. It freezes at an unchanged timestamp and resets
on disable, zero strength, reload, world/model changes, teleport, scale changes,
large heading changes, invalid time, time reversal or gaps over 0.25 seconds.
The previous `PlayerMeshRenderer.Instance` constructor remains available and opts
out of this layer.

This is a bounded Celeste spring simulation, not a VRChat PhysBone or VRM
spring-bone interpreter. It does not implement collision, grabbing, cloth or
morph-target physics. It cannot guarantee a particular third-party model's
advertised physics or prevent clothing clipping without inspecting its actual
weights, bone axes and outfit. Morph targets remain unsupported; any chosen
existing shape-key configuration must be baked consistently before import.

Run `betaModelSecondaryMotionCheck` using this directory's `check.init.gradle`
for pure synthetic rig checks. These exercise weighted deformation, animation
composition, selection rejection, frame rates, reset behavior and player isolation;
they do not establish in-game or third-party asset verification.
