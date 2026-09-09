"""Offline Blender 4.5 conversion of the creator's five-animation Sahur pack.

Run through the isolated portable Blender executable, not system Python.
The original assets are for the owner's personal beta; this script grants no
redistribution rights. Detailed evidence belongs under ignored build/module-qa.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import bpy
from mathutils import Vector


CLIPS = (
    ("TungSahur_Stand.dae", "idle"),
    ("TungSahur_Breakdance.dae", "celebration_breakdance"),
    ("TungSahur_Dance_Flair.dae", "celebration_flair"),
    ("TungSahur_Dance_Hiphop.dae", "celebration_hiphop"),
    ("TungSahur_Dance_RunningMan.dae", "celebration_running_man"),
)
FPS = 60
MAX_GLB_BYTES = 8 * 1024 * 1024
COLLADA_NS = {"c": "http://www.collada.org/2005/11/COLLADASchema"}
SOURCE_SHA256 = {
    "TungSahur_Stand.dae": "8050d3d6107b6868c5b1b73c3eb07edf91383c6c44816bfaf8541c994db291b1",
    "TungSahur_Breakdance.dae": "d7876120dc350ef568f2cb93479b6ee264bd4d98f688ef0820346827d2c68b58",
    "TungSahur_Dance_Flair.dae": "4e4a70389848d796b7665f44e398f6b55d5360158ceb8084b8a6b522bda1cf28",
    "TungSahur_Dance_Hiphop.dae": "9bb38c5ab9bfe4eb20e605e2b9cae0283879b1645847336ba57da20276ddedbf",
    "TungSahur_Dance_RunningMan.dae": "aa8a3c5955feb6a35721a54d39898b6485af2d43d0f7b36e7867653d1529fb10",
}
TEXTURE_SHA256 = "5fa01a65160dbeb033f47bd9a564b509536b22ca01ba9fe151ed3a316f6f1a7d"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def matrix_values(matrix):
    return [round(float(x), 8) for row in matrix for x in row]


def action_curves(action):
    """Handle Blender 4.5 layered/slotted actions without legacy conversion."""
    if action.is_action_legacy:
        return list(action.fcurves)
    curves = []
    for layer in action.layers:
        for strip in layer.strips:
            for slot in action.slots:
                bag = strip.channelbag(slot)
                if bag:
                    curves.extend(bag.fcurves)
    return curves


def import_dae(path: Path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    result = bpy.ops.wm.collada_import(
        filepath=str(path), import_units=False, custom_normals=True,
        fix_orientation=False, find_chains=False, auto_connect=False,
    )
    if result != {"FINISHED"}:
        raise RuntimeError(f"COLLADA import failed: {path.name}: {result}")


def source_signatures(path: Path) -> dict:
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256[path.name]:
        raise ValueError(f"Creator source differs from the reviewed pack: {path.name}")
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise ValueError("DTD/entity declarations are not accepted in source COLLADA")
    xml = ET.fromstring(data)
    signatures = {}
    for name in ("library_geometries", "library_controllers", "library_visual_scenes"):
        section = xml.find("c:" + name, COLLADA_NS)
        if section is None:
            raise ValueError(f"Missing COLLADA {name}")
        signatures[name] = hashlib.sha256(ET.tostring(section)).hexdigest()
    return signatures


def capture_action() -> dict:
    armatures = [o for o in bpy.context.scene.objects if o.type == "ARMATURE"]
    if len(armatures) != 1 or len(bpy.data.actions) != 1:
        raise ValueError("Expected exactly one source armature and one source action")
    action = bpy.data.actions[0]
    start, end = action.frame_range
    rate = bpy.context.scene.render.fps / bpy.context.scene.render.fps_base
    curves = []
    for curve in action_curves(action):
        if not curve.data_path.startswith('pose.bones['):
            raise ValueError(f"Unexpected non-bone animation: {curve.data_path}")
        keys = []
        for key in curve.keyframe_points:
            if key.interpolation != "LINEAR":
                raise ValueError("Source motion must be LINEAR; do not silently change its interpolation")
            frame = (float(key.co.x) - start) * FPS / rate
            if abs(frame - round(frame)) > 0.002:
                raise ValueError("Source key is not aligned to the expected 60 Hz sampling")
            keys.append((round(frame), float(key.co.y)))
        curves.append({"path": curve.data_path, "index": curve.array_index, "keys": keys})
    return {"curves": curves, "duration": (end - start) / rate,
            "last_frame": round((end - start) * FPS / rate)}


def create_action(armature, name: str, captured: dict):
    action = bpy.data.actions.new(name)
    action.use_fake_user = True
    for item in captured["curves"]:
        curve = action.fcurves.new(data_path=item["path"], index=item["index"])
        keys = item["keys"]
        # Reduce only numerically constant scalar channels. This does not remove
        # root movement or change actual dance motion; the error is <= 1e-6.
        values = [value for _, value in keys]
        if len(keys) > 2 and max(values) - min(values) <= 1e-6:
            keys = [(0, values[0]), (captured["last_frame"], values[0])]
        curve.keyframe_points.add(len(keys))
        curve.keyframe_points.foreach_set("co", [v for pair in keys for v in pair])
        for point in curve.keyframe_points:
            point.interpolation = "LINEAR"
        curve.update()
    armature.animation_data_create()
    armature.animation_data.action = action
    if len(action.slots) != 1:
        raise ValueError("Expected one compatible armature action slot")
    armature.animation_data.action_slot = action.slots[0]
    track = armature.animation_data.nla_tracks.new()
    track.name = name
    track.mute = True  # Stashed; it must not blend with the active clip.
    strip = track.strips.new(name, 0, action)
    strip.action_slot = action.slots[0]
    strip.extrapolation = "NOTHING"
    strip.blend_type = "REPLACE"
    return action


def basic_material(source: Path, mesh):
    image = bpy.data.images.load(str(source / "shaded.png"), check_existing=True)
    if tuple(image.size) != (2048, 2048) or image.file_format != "PNG":
        raise ValueError("Unexpected creator texture; inspect before converting")
    image.colorspace_settings.name = "sRGB"
    image.pack()
    material = bpy.data.materials.new("Sahur_Shaded")
    material.use_nodes = True
    material.diffuse_color = (1, 1, 1, 1)
    material.use_backface_culling = False
    nodes = material.node_tree.nodes
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    principled = nodes.new("ShaderNodeBsdfPrincipled")
    principled.inputs["Metallic"].default_value = 0
    principled.inputs["Roughness"].default_value = 1
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = image
    material.node_tree.links.new(texture.outputs["Color"], principled.inputs["Base Color"])
    material.node_tree.links.new(principled.outputs["BSDF"], output.inputs["Surface"])
    mesh.data.materials.clear()
    mesh.data.materials.append(material)
    for polygon in mesh.data.polygons:
        polygon.material_index = 0


def read_glb(path: Path):
    data = path.read_bytes()
    magic, version, total = struct.unpack_from("<III", data)
    if magic != 0x46546C67 or version != 2 or total != len(data):
        raise ValueError("Invalid exported GLB envelope")
    chunks = {}
    offset = 12
    while offset < len(data):
        length, kind = struct.unpack_from("<II", data, offset)
        offset += 8
        if length % 4 or offset + length > len(data) or kind in chunks:
            raise ValueError("Invalid GLB chunk")
        chunks[kind] = data[offset:offset + length]
        offset += length
    return json.loads(chunks[0x4E4F534A]), chunks[0x004E4942]


def exported_report(path: Path) -> dict:
    model, binary = read_glb(path)
    if path.stat().st_size > MAX_GLB_BYTES:
        raise ValueError("Export exceeds the 8 MiB embedded-resource member limit")
    if model.get("extensionsRequired") or model.get("extensionsUsed"):
        raise ValueError("Export contains extensions; expected only core glTF 2.0")
    if len(model["meshes"]) != 1 or len(model["skins"]) != 1 or len(model["images"]) != 1:
        raise ValueError("Expected exactly one shared mesh, skin, and embedded image")
    if len(model["buffers"]) != 1 or "uri" in model["buffers"][0]:
        raise ValueError("Expected one embedded GLB buffer")
    primitives = model["meshes"][0]["primitives"]
    vertex_count = 0
    indices = 0
    for primitive in primitives:
        if primitive.get("mode", 4) != 4 or "indices" not in primitive:
            raise ValueError("Expected indexed triangle primitives")
        attributes = primitive["attributes"]
        if not {"POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0"}.issubset(attributes):
            raise ValueError("Expected textured/normally shaded four-influence skinning")
        if any(k.startswith(("JOINTS_", "WEIGHTS_")) and not k.endswith("_0") for k in attributes):
            raise ValueError("More than four joint influences are unsupported")
        vertex_count += model["accessors"][attributes["POSITION"]]["count"]
        indices += model["accessors"][primitive["indices"]]["count"]
    if indices != 31508 * 3 or len(primitives) != 1:
        raise ValueError("Source geometry changed: expected one 31,508-triangle primitive")
    animation_reports = []
    for animation in model["animations"]:
        duration = 0.0
        for channel in animation["channels"]:
            if channel["target"]["path"] not in ("translation", "rotation", "scale"):
                raise ValueError("Unsupported animation target")
            if "matrix" in model["nodes"][channel["target"]["node"]]:
                raise ValueError("Animated nodes must use TRS")
        for sampler in animation["samplers"]:
            if sampler.get("interpolation", "LINEAR") not in ("LINEAR", "STEP"):
                raise ValueError("Unsupported exported animation interpolation")
            times = model["accessors"][sampler["input"]]
            duration = max(duration, times["max"][0])
            if times["min"][0] != 0:
                raise ValueError("Each exported clip must start at zero")
        animation_reports.append({"name": animation["name"], "seconds": duration,
                                  "channels": len(animation["channels"])})
    if {a["name"] for a in animation_reports} != {name for _, name in CLIPS} or len(animation_reports) != 5:
        raise ValueError("Export lost or duplicated one of the five clips")
    image = model["images"][0]
    if "uri" in image or image["mimeType"] != "image/png":
        raise ValueError("Expected the embedded source PNG")
    view = model["bufferViews"][image["bufferView"]]
    start = view.get("byteOffset", 0)
    image_bytes = binary[start:start + view["byteLength"]]
    if image_bytes[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Embedded texture is not PNG")
    if hashlib.sha256(image_bytes).hexdigest() != TEXTURE_SHA256:
        raise ValueError("Export changed the reviewed creator PNG")
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path),
            "meshes": 1, "primitives": len(primitives), "vertices": vertex_count,
            "triangles": indices // 3, "nodes": len(model["nodes"]),
            "skins": 1, "joints": len(model["skins"][0]["joints"]),
            "images": 1, "embedded_png_sha256": hashlib.sha256(image_bytes).hexdigest(),
            "embedded_png_bytes": len(image_bytes), "animations": animation_reports,
            "extensionsUsed": model.get("extensionsUsed", []),
            "extensionsRequired": model.get("extensionsRequired", []),
            "position_bounds": [{"min": model["accessors"][p["attributes"]["POSITION"]]["min"],
                                 "max": model["accessors"][p["attributes"]["POSITION"]]["max"]}
                                for p in primitives]}


def convert(source: Path, output: Path, actions: list[dict]):
    import_dae(source / CLIPS[0][0])
    scene = bpy.context.scene
    armature = next(o for o in scene.objects if o.type == "ARMATURE")
    mesh = next(o for o in scene.objects if o.type == "MESH")
    armature.animation_data_clear()
    for action in list(bpy.data.actions):
        bpy.data.actions.remove(action)
    for bone in armature.pose.bones:
        bone.matrix_basis.identity()
    scene.render.fps = FPS
    scene.render.fps_base = 1
    scene.frame_start = 0
    scene.frame_end = max(a["last_frame"] for a in actions)
    created = [create_action(armature, name, captured)
               for (_, name), captured in zip(CLIPS, actions)]
    armature.animation_data.action = created[0]
    armature.animation_data.action_slot = created[0].slots[0]
    basic_material(source, mesh)
    scene.frame_set(0)
    result = bpy.ops.export_scene.gltf(
        filepath=str(output), check_existing=False, export_format="GLB",
        export_animations=True, export_animation_mode="ACTIONS",
        export_force_sampling=True, export_sampling_interpolation_fallback="LINEAR",
        export_frame_range=False, export_frame_step=1, export_anim_slide_to_zero=True,
        export_merge_animation="ACTION", export_reset_pose_bones=True,
        export_optimize_animation_size=True, export_anim_single_armature=True,
        export_skins=True, export_influence_nb=4, export_all_influences=False,
        export_def_bones=False, export_leaf_bone=False, export_apply=False,
        export_morph=False, export_morph_animation=False, export_texcoords=True,
        export_normals=True, export_tangents=False, export_yup=True,
        export_materials="EXPORT", export_image_format="AUTO", export_image_add_webp=False,
        export_image_webp_fallback=False, export_use_gltfpack=False,
        export_draco_mesh_compression_enable=False, export_gpu_instances=False,
        export_pointer_animation=False, export_lights=False, export_cameras=False,
        export_extras=False, use_mesh_edges=False, use_mesh_vertices=False,
    )
    if result != {"FINISHED"}:
        raise RuntimeError(f"GLB export failed: {result}")
    return exported_report(output)


def inventory(path: Path) -> dict:
    scene = bpy.context.scene
    meshes = []
    armatures = []
    for obj in scene.objects:
        if obj.type == "MESH":
            meshes.append({
                "object": obj.name, "vertices": len(obj.data.vertices),
                "polygons": len(obj.data.polygons),
                "matrix_world": matrix_values(obj.matrix_world),
                "parent": obj.parent.name if obj.parent else None,
                "groups": [g.name for g in obj.vertex_groups],
                "modifiers": [{"name": m.name, "type": m.type,
                               "object": m.object.name if m.type == "ARMATURE" and m.object else None}
                              for m in obj.modifiers],
            })
        elif obj.type == "ARMATURE":
            bones = [{"name": b.name, "parent": b.parent.name if b.parent else None,
                      "rest": matrix_values(b.matrix_local)} for b in obj.data.bones]
            armatures.append({
                "object": obj.name, "bones": bones,
                "rig_sha256": hashlib.sha256(json.dumps(bones, sort_keys=True).encode()).hexdigest(),
                "matrix_world": matrix_values(obj.matrix_world),
                "action": obj.animation_data.action.name if obj.animation_data and obj.animation_data.action else None,
            })
    actions = []
    for action in bpy.data.actions:
        curves = action_curves(action)
        actions.append({
            "name": action.name, "frames": list(action.frame_range),
            "slots": [{"identifier": s.identifier, "target_id_type": s.target_id_type} for s in action.slots],
            "fcurves": len(curves),
            "channels": [{"path": f.data_path, "index": f.array_index,
                          "keys": len(f.keyframe_points),
                          "min": min((float(k.co.y) for k in f.keyframe_points), default=None),
                          "max": max((float(k.co.y) for k in f.keyframe_points), default=None),
                          "interpolation": sorted({k.interpolation for k in f.keyframe_points})}
                         for f in curves],
        })
    materials = [{"name": m.name, "nodes": [{"name": n.name, "type": n.type,
                                             "image": n.image.name if n.type == "TEX_IMAGE" and n.image else None}
                                            for n in m.node_tree.nodes] if m.node_tree else []}
                 for m in bpy.data.materials]
    images = [{"name": i.name, "path": i.filepath, "size": list(i.size), "format": i.file_format}
              for i in bpy.data.images if i.type == "IMAGE"]
    bounds = []
    if actions:
        start, end = actions[0]["frames"]
        for frame in sorted({int(start), int((start + end) / 2), int(end)}):
            scene.frame_set(frame)
            dg = bpy.context.evaluated_depsgraph_get()
            points = [obj.evaluated_get(dg).matrix_world @ Vector(corner)
                      for obj in scene.objects if obj.type == "MESH"
                      for corner in obj.evaluated_get(dg).bound_box]
            bounds.append({"frame": frame,
                           "min": [min(p[i] for p in points) for i in range(3)],
                           "max": [max(p[i] for p in points) for i in range(3)]})
    return {"file": path.name, "sha256": sha256(path), "fps": scene.render.fps,
            "fps_base": scene.render.fps_base, "scene_frames": [scene.frame_start, scene.frame_end],
            "meshes": meshes, "armatures": armatures, "actions": actions,
            "materials": materials, "images": images, "sample_bounds": bounds}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--inspect-only", action="store_true")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--overwrite", action="store_true", help="Explicitly replace an existing output GLB")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:])
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    if not source.is_dir() or not (source / "shaded.png").is_file():
        raise ValueError("Expected the original Sahur pack directory and shaded.png")
    if output.suffix.lower() != ".glb" or output.is_relative_to(source):
        raise ValueError("Output must be a separate .glb outside the original source directory")
    if sha256(source / "shaded.png") != TEXTURE_SHA256:
        raise ValueError("Creator texture differs from the reviewed pack")
    if args.inspect_only and args.validate_only:
        raise ValueError("Choose inspection or output validation, not both")
    if not bpy.app.background or bpy.app.online_access:
        raise RuntimeError("Use Blender --background --offline-mode")
    if bpy.context.preferences.filepaths.use_scripts_auto_execute:
        raise RuntimeError("Use Blender --disable-autoexec")
    if args.validate_only:
        print("SAHUR_VALIDATED " + json.dumps(exported_report(output), sort_keys=True), flush=True)
        return
    if not args.inspect_only and output.exists() and not args.overwrite:
        raise FileExistsError("Output GLB exists; use a fresh path or explicit --overwrite")
    output.parent.mkdir(parents=True, exist_ok=True)
    inventories = []
    captured_actions = []
    baseline_signatures = None
    for filename, clip_name in CLIPS:
        path = source / filename
        if not path.is_file():
            raise ValueError(f"Missing creator asset: {filename}")
        signatures = source_signatures(path)
        if baseline_signatures is None:
            baseline_signatures = signatures
        elif signatures != baseline_signatures:
            raise ValueError("Geometry/controller/hierarchy differs; do not reuse the rig without explicit retargeting")
        import_dae(path)
        item = inventory(path)
        if inventories and item["armatures"][0]["rig_sha256"] != inventories[0]["armatures"][0]["rig_sha256"]:
            raise ValueError("Blender-imported rest rig differs across clips")
        item["intended_clip"] = clip_name
        inventories.append(item)
        captured_actions.append(capture_action())
        print(f"SAHUR_INSPECT {filename}: {len(item['meshes'])} meshes, "
              f"{len(item['armatures'])} armatures, {len(item['actions'])} actions", flush=True)
    report = {"blender": bpy.app.version_string, "source": str(source),
              "texture_sha256": sha256(source / "shaded.png"), "inputs": inventories,
              "source_shared_sections": baseline_signatures,
              "scope": "Owner personal beta; redistribution permission not established"}
    report_path = output.with_suffix(".inspection.json")
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not args.inspect_only:
        report["output"] = convert(source, output, captured_actions)
        report["root_motion"] = "Preserved; source loops have no meaningful net drift. No positional channels stripped."
        report["time_conversion"] = "First key shifted to zero; original 60 Hz timestamps preserved; LINEAR sampling"
        report["constant_channel_tolerance"] = 1e-6
        report_path = output.with_suffix(".report.json")
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("SAHUR_CONVERTED " + json.dumps(report["output"], sort_keys=True), flush=True)
    print(f"SAHUR_INSPECTION_REPORT {report_path}", flush=True)


if __name__ == "__main__":
    main()
