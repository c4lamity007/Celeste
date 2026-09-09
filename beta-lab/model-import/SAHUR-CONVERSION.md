# Sahur: reviewed creator pack to personal-beta GLB

This workflow converts the five COLLADA files from the creator's free
[GipsoCartoon pack](https://www.gipsocartoon.com/product/109) into one offline GLB.
The pack's README documents BeamNG installation but grants no explicit
redistribution license. Keep originals, converted assets, renders, and portable
tools in ignored `build/module-qa/`; this is for the Owner's personal beta, not a
license to distribute the asset in a public build or commit it to source control.
No account details or credentials are used by either script.

## Tool and inputs

Use the official Windows x64 portable Blender 4.5.13 LTS distribution, the last
Blender release line with built-in COLLADA import:

- ZIP: `https://download.blender.org/release/Blender4.5/blender-4.5.13-windows-x64.zip`
- Official SHA-256: `b5fdf800ce65fa2f209e8f68d02667e4d720fa1c42f247c72d1882ab04decba6`
- Extract in `build/module-qa/model-tools/blender-4.5.13-windows-x64/` and create
  its `portable/` directory beside `blender.exe`. No system installation needed.

`convert_sahur.py` accepts the extracted `TungTungTungSahur_Pack/` directory,
including five `TungSahur_*.dae` files and `shaded.png`. It pins the reviewed
source file and texture hashes and refuses an altered pack. It verifies matching
geometry, controllers, static scene hierarchy, and Blender rest-bone matrices
before sharing the mesh/rig. Do not weaken these checks to force a different rig.

## Convert and validate

Run from the authoritative repository root:

```powershell
$sahurBlender = '.\build\module-qa\model-tools\blender-4.5.13-windows-x64\blender.exe'
$sahurSource = '.\build\module-qa\model-assets\sahur-original\TungTungTungSahur_Pack'
$sahurGlb = '.\build\module-qa\model-assets\sahur-converted\sahur.glb'
& $sahurBlender --background --factory-startup --disable-autoexec --offline-mode --addons io_scene_gltf2 --python-exit-code 1 --python '.\beta-lab\model-import\convert_sahur.py' -- --source $sahurSource --output $sahurGlb
```

Use a fresh output path for another candidate. Existing GLBs require explicit
`--overwrite`. `--inspect-only` produces the detailed source inventory without
conversion; `--validate-only` checks an existing output without rewriting it.
Reports are adjacent `.inspection.json` / `.report.json` files. Direct Blender
output into an ignored log when desired. Nonzero exit means a failure; don't
package a partially exported file after a failed check.

The converter preserves one original texture byte-for-byte, one skinned mesh,
and one shared 65-bone armature. COLLADA's 52 weighted joints are a subset of
those 65 bones. glTF export splits vertices at UV/normal seams, but preserves all
31,508 indexed triangles. Materials use only core glTF base color; no required
or optional extensions, compression, external resources, or morph animation.
The output must fit the 8 MiB embedded-resource member limit.

| Source suffix | Output clip | Duration |
| --- | --- | --- |
| Stand | `idle` | 6.016667 s |
| Breakdance | `celebration_breakdance` | 3.166667 s |
| Dance_Flair | `celebration_flair` | 1.016667 s |
| Dance_Hiphop | `celebration_hiphop` | 15.966667 s |
| Dance_RunningMan | `celebration_running_man` | 10.833333 s |

Original timing is 60 Hz; the first source key (~1/60 s) becomes zero without
changing clip speed. Channels remain LINEAR. Only scalar tracks varying by at
most 1e-6 are made constant to suppress numerical noise; no genuine root motion
is stripped. Hips first/last translation differs by less than 1.2e-5 m. Larger
within-clip excursions, vertical dips and jumps are intentional choreography,
not player movement. Dance endpoints are close but not exactly identical.
Stand has small breathing/sway and is suitable for idle. Missing finger tracks
reset to the shared rest pose instead of inheriting another dance's pose.

The exporter may log that channels with different key counts require baking:
this is expected after constant-track reduction; 60 Hz sampling is intentional.
Run the project's actual `SahurAssetCheck` after conversion; exporter validation
alone is not proof of client parser, deformation, or in-game compatibility.

## Honest rendered preview

```powershell
& $sahurBlender --background --factory-startup --disable-autoexec --offline-mode --addons io_scene_gltf2 --python-exit-code 1 --python '.\beta-lab\model-import\preview_sahur.py' -- --input $sahurGlb --output '.\build\module-qa\model-assets\sahur-converted\sahur-preview.png' --clip idle
```

`--clip` accepts any exact clip name; `--fraction` selects a point from 0 to 1.
This imports the finished GLB into a fresh scene, evaluates its real skinning,
and renders the original textured geometry in a neutral studio using Cycles CPU.
It hashes the GLB before/after to prove the preview did not change the asset.
The PNG's adjacent JSON records input/image hashes, pose bounds and sample time.
The preview is **Blender offline evidence, not a Minecraft screenshot**. Game
visibility, frame ordering and module enable/disable still require the normal
authorized in-game QA route.
