"""Render an honest, offline Blender preview from an already-converted GLB.

This is not Minecraft/in-game evidence. The input GLB is never rewritten.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def point_at(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--clip", default="idle")
    parser.add_argument("--fraction", type=float, default=0.35)
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:])
    source = args.input.resolve(strict=True)
    output = args.output.resolve()
    if source.suffix.lower() != ".glb" or output.suffix.lower() != ".png" or source == output:
        raise ValueError("Expected separate input GLB and output PNG paths")
    if not 0 <= args.fraction <= 1:
        raise ValueError("Clip fraction must be in [0, 1]")
    if not bpy.app.background or bpy.app.online_access or bpy.context.preferences.filepaths.use_scripts_auto_execute:
        raise RuntimeError("Use --background --offline-mode --disable-autoexec")
    original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(source), import_pack_images=True, disable_bone_shape=True)
    scene = bpy.context.scene
    armatures = [o for o in scene.objects if o.type == "ARMATURE"]
    meshes = [o for o in scene.objects if o.type == "MESH"]
    if len(armatures) != 1 or len(meshes) != 1:
        raise ValueError("Expected one imported GLB mesh and one armature")
    armature, mesh = armatures[0], meshes[0]
    action = bpy.data.actions.get(args.clip)
    if action is None or len(action.slots) != 1:
        raise ValueError(f"Missing single-armature clip: {args.clip}")
    armature.animation_data_create()
    for track in armature.animation_data.nla_tracks:
        track.mute = True
    armature.animation_data.action = None
    for bone in armature.pose.bones:
        bone.matrix_basis.identity()
    armature.animation_data.action = action
    armature.animation_data.action_slot = action.slots[0]
    start, end = action.frame_range
    frame = start + (end - start) * args.fraction
    scene.frame_set(math.floor(frame), subframe=frame % 1)
    evaluated = mesh.evaluated_get(bpy.context.evaluated_depsgraph_get())
    corners = [evaluated.matrix_world @ Vector(v) for v in evaluated.bound_box]
    minimum = Vector([min(v[i] for v in corners) for i in range(3)])
    maximum = Vector([max(v[i] for v in corners) for i in range(3)])
    center = (minimum + maximum) * 0.5
    height = max(maximum.z - minimum.z, 0.5)

    # Neutral studio framing; only actual exported geometry/texture supplies the
    # character appearance. No generated art, retouching, or fabricated effects.
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 24
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "AgX"
    scene.world = bpy.data.worlds.new("Preview_Studio")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.14, 0.17, 0.23, 1)
    scene.world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.4
    bpy.ops.object.camera_add(location=(center.x + height * 0.6, center.y - height * 2.8, center.z + height * 0.25))
    camera = bpy.context.object
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = max(height * 1.25, (maximum.x - minimum.x) * 1.9)
    point_at(camera, center)
    scene.camera = camera
    for name, location, power, size in (
        ("Key", (center.x - 3, center.y - 4, maximum.z + 3), 700, 4),
        ("Fill", (center.x + 4, center.y - 2, maximum.z + 1), 450, 3),
        ("Rim", (center.x, center.y + 3, maximum.z + 2), 600, 3),
    ):
        light = bpy.data.lights.new(name, "AREA")
        light.energy = power
        light.shape = "DISK"
        light.size = size
        obj = bpy.data.objects.new(name, light)
        scene.collection.objects.link(obj)
        obj.location = location
        point_at(obj, center)
    bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, min(0.0, minimum.z) - 0.006))
    floor = bpy.context.object
    floor.name = "Preview_Backdrop"
    material = bpy.data.materials.new("Preview_Backdrop_Material")
    material.diffuse_color = (0.055, 0.065, 0.09, 1)
    material.use_nodes = True
    material.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = material.diffuse_color
    material.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 1
    floor.data.materials.append(material)
    output.parent.mkdir(parents=True, exist_ok=True)
    scene.render.filepath = str(output)
    bpy.ops.render.render(write_still=True)
    if hashlib.sha256(source.read_bytes()).hexdigest() != original_hash:
        raise RuntimeError("Input GLB changed during preview")
    report = {"kind": "Blender offline render; not Minecraft/in-game evidence",
              "blender": bpy.app.version_string, "source": str(source),
              "source_sha256": original_hash, "image": str(output),
              "image_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
              "clip": args.clip, "fraction": args.fraction,
              "seconds": frame / (scene.render.fps / scene.render.fps_base),
              "posed_bounds_blender_z_up": {"min": list(minimum), "max": list(maximum)}}
    output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("SAHUR_PREVIEW " + json.dumps(report, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
