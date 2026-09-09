# Player Models (Owner beta)

In the Owner build containing Sahur, enable Visuals → Player Models to load him.
Use Load Sahur to return to him after importing a different model. The bundled
model has the creator's standing animation and four post-kill dance clips.

For other models, open Player Models → Load model. Enter the absolute path to a `.glb`
and choose Import. The file must contain its own meshes, textures and clips.
Successful import enables the module; use F5 to see your replacement in world.
Apply to selects Self, Others, or Everyone. Size preserves model proportions.
The nearest eligible players within 48 blocks use the imported model, subject
to a fixed frame geometry budget; farther/budget-excluded players retain vanilla.

Parsing runs off the game thread. Textures, indices and geometry are cached;
animation time advances each rendered frame. Disabling restores normal
world models. Disconnect releases GPU resources; a loaded model can be reused
in the next world. Stop beta releases both model data and graphics resources.
The import path and asset are not uploaded, copied into a mod/resource pack,
or saved to a profile. Import again after restarting the game.

Only visuals change: movement, hitboxes, attacks and packets remain the game's.
First-person hands and inventory/GUI player previews keep their vanilla rendering.
Skinning allows four bone influences per vertex. Base color, embedded PNG/JPEG,
alpha cutout/transparency and frame-based LINEAR/STEP clips are supported; this
first renderer does not implement glTF's complete PBR lighting model. Skinned
surfaces render both sides to avoid holes during reflected joint blends.

Named idle/stand and walk/run clips provide locomotion poses when available.
If no walking clip exists, movement uses the idle clip instead of a T-pose.
Explicit dance/celebration names take priority over locomotion name matching,
so Running Man stays a celebration. The Sahur pack has no walking clip.
Other non-death/jump/fall clips form the random celebration pool. A recent
attacked target's observed death can trigger one clip while you are grounded
and stationary. Moving, disabling celebrations, changing world, or reaching
the clip end cancels it. This is a local presentation heuristic, not server
proof that the final kill belongs to you. Clips have a 20-second maximum.

Thingiverse STL meshes have no skeleton or animation. Rig and export them as a
self-contained GLB first. For unsupported cubic tracks, export/resample to LINEAR.
COLLADA/FBX/Blend files also need conversion; changing the extension is insufficient.

## Requested Sahur pack

The actual creator pack was downloaded from https://www.gipsocartoon.com/product/109
after account verification. It contains five COLLADA files with the same model,
65-bone skeleton and shared 2048×2048 texture. Conversion combines their clips
into one self-contained GLB without duplicating the geometry or texture.

The original archive SHA-256 is
`0ad5c2893a4ac8012656c7ade9f733e37117b39913f1ac7040ab091cf523f016`.
Its README contains BeamNG installation instructions but no general redistribution
license. Use the conversion for the Owner's personal beta only; obtain permission
before distributing this creator asset to general users.

`bundle_owner_model.py` adds the converted asset and attribution to a fresh,
separate beta input JAR before protection and splitting. The ordinary Gradle
build contains no Sahur asset. The Owner asset is read from the payload's resource
stream; it creates no separate model file or resource-pack chooser entry in game.
Java protection preserves model/texture resource bytes; it does not encrypt or
obfuscate the GLB itself, and rendered assets can still be recovered from memory.
