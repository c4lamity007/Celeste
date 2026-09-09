package dev.c4lam.celeste.render.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Exercises the actual converted asset, including visible deformation rather than channel counts alone. */
public final class SahurAssetCheck {
    private static final int MAX_BUNDLED_BYTES = 8 * 1024 * 1024;
    private static final int SAMPLES = 17;
    private static int checks;

    private SahurAssetCheck() {}

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        check(args.length == 1 || args.length == 2 && args[1].equals("--bundled"),
            "Usage: SahurAssetCheck <sahur.glb> [--bundled]");
        Path path = Path.of(args[0]).toAbsolutePath().normalize();
        check(Files.isRegularFile(path), "Converted GLB must exist");
        check(Files.size(path) <= MAX_BUNDLED_BYTES, "GLB must fit the beta's 8 MiB uncompressed member limit");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BUNDLED_BYTES + 1);
        }
        check(bytes.length <= MAX_BUNDLED_BYTES, "Converted GLB did not grow beyond the member limit during read");
        GlbModel model = GlbImporter.parse(bytes);
        check(model.animations().size() == 5, "Sahur pack must contain exactly five animation clips");
        check(!model.skins().isEmpty(), "Actual Sahur asset must retain its skeleton");
        check(!model.images().isEmpty(), "Actual Sahur asset must retain embedded textures");

        GlbPose pose = new GlbPose(model);
        List<Part> parts = geometry(model, pose);
        Bounds bind = bounds(parts);
        double height = bind.height();
        check(Double.isFinite(height) && height > .00001, "Bind pose has a usable height");
        check(bind.width() * 1.8 / height <= 16 && bind.depth() * 1.8 / height <= 16,
            "Bind pose meets the renderer's normalized width/depth limits");
        check(bind.width() > 0 && bind.depth() > 0, "Character is a three-dimensional mesh");

        JsonArray clips = new JsonArray();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < model.animations().size(); index++) {
            var clip = model.animations().get(index);
            check(names.add(clip.name().toLowerCase(Locale.ROOT)), "Clip names must be unique: " + clip.name());
            check(Float.isFinite(clip.duration()) && clip.duration() > 0, "Clip has a positive duration: " + clip.name());
            check(!clip.channels().isEmpty(), "Clip retains animation channels: " + clip.name());
            pose.sample(index, 0, false);
            deform(parts, pose, clip.name() + " initial frame");
            for (Part part : parts) System.arraycopy(part.positions, 0, part.reference, 0, part.positions.length);
            double greatestMotion = 0;
            double greatestExtent = 0;
            for (int sample = 0; sample < SAMPLES; sample++) {
                // Includes endpoints and non-key-aligned times to exercise interpolation.
                double fraction = sample / (double) (SAMPLES - 1);
                double time = clip.duration() * fraction;
                pose.sample(index, time, false);
                deform(parts, pose, clip.name() + " at " + time);
                Bounds frame = bounds(parts);
                double extent = Math.max(frame.width(), Math.max(frame.height(), frame.depth())) / height;
                greatestExtent = Math.max(greatestExtent, extent);
                check(extent <= 4, "Animated geometry exploded beyond four bind heights: " + clip.name());
                check(frame.distanceFrom(bind) <= height * 4,
                    "Animated geometry escaped the player's envelope: " + clip.name());
                for (Part part : parts) {
                    for (int vertex : part.primitive.indices()) {
                        int offset = vertex * 3;
                        double dx = part.positions[offset] - part.reference[offset];
                        double dy = part.positions[offset + 1] - part.reference[offset + 1];
                        double dz = part.positions[offset + 2] - part.reference[offset + 2];
                        greatestMotion = Math.max(greatestMotion, Math.sqrt(dx * dx + dy * dy + dz * dz));
                    }
                }
            }
            check(greatestMotion / height > .00001,
                "Clip channels must visibly deform the rendered mesh, not an unrelated skeleton: " + clip.name());
            JsonObject report = new JsonObject();
            report.addProperty("name", clip.name());
            report.addProperty("duration_seconds", clip.duration());
            report.addProperty("max_motion_in_bind_heights", greatestMotion / height);
            report.addProperty("max_extent_in_bind_heights", greatestExtent);
            clips.add(report);
        }
        check(names.equals(Set.of("idle", "celebration_breakdance", "celebration_flair",
                "celebration_hiphop", "celebration_running_man")),
            "All five original pack clips survive conversion with their declared export names");

        checkController(model);
        checkWalking(SahurLocomotion.withWalk(model), model);
        if (args.length == 2) checkBundled(model, bytes);
        JsonObject report = new JsonObject();
        report.addProperty("status", "pass");
        report.addProperty("checks", checks);
        report.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        report.addProperty("asset_bytes", bytes.length);
        report.addProperty("vertices", parts.stream().mapToInt(part -> part.primitive.vertexCount()).sum());
        report.addProperty("triangles", parts.stream().mapToInt(part -> part.primitive.indices().length / 3).sum());
        report.addProperty("skins", model.skins().size());
        report.addProperty("images", model.images().size());
        report.addProperty("frames_per_clip", SAMPLES);
        report.addProperty("bundled_resource_checked", args.length == 2);
        report.addProperty("normalized_width_blocks", bind.width() * 1.8 / height);
        report.addProperty("normalized_depth_blocks", bind.depth() * 1.8 / height);
        report.add("clips", clips);
        System.out.println("PASS actual Sahur asset: " + report);
    }

    private static List<Part> geometry(GlbModel model, GlbPose pose) {
        List<Part> parts = new ArrayList<>();
        int vertices = 0, indices = 0;
        boolean skinned = false, textured = false;
        for (int nodeIndex : model.sceneNodes()) {
            var node = model.nodes().get(nodeIndex);
            if (node.mesh() < 0) continue;
            for (var primitive : model.meshes().get(node.mesh()).primitives()) {
                check(primitive.indices().length > 0 && primitive.indices().length % 3 == 0,
                    "Selected scene geometry uses indexed triangles");
                check(primitive.normals() != null, "Selected scene geometry retains normals");
                vertices += primitive.vertexCount();
                indices += primitive.indices().length;
                if (node.skin() >= 0) {
                    check(primitive.joints() != null && primitive.weights() != null,
                        "Skinned primitives retain four joint influences and weights");
                    skinned = true;
                }
                if (model.materials().get(primitive.material()).image() >= 0) {
                    check(primitive.texCoords() != null, "Textured primitive retains texture coordinates");
                    textured = true;
                }
                parts.add(new Part(nodeIndex, primitive));
            }
        }
        check(!parts.isEmpty() && parts.size() <= 128, "Selected scene fits the renderer's mesh-part budget");
        check(vertices <= 200_000 && indices <= 1_200_000, "Selected scene fits the renderer's geometry budget");
        check(skinned && textured, "The rendered scene contains both rigged and textured geometry");
        deform(parts, pose, "bind pose");
        return parts;
    }

    private static void deform(List<Part> parts, GlbPose pose, String label) {
        for (Part part : parts) {
            pose.deform(part.node, part.primitive, part.positions, part.normals);
            boolean finite = true, normalized = true;
            int nonzeroNormals = 0;
            for (int index = 0; index < part.positions.length; index += 3) {
                double squared = 0;
                for (int axis = 0; axis < 3; axis++) {
                    finite &= Float.isFinite(part.positions[index + axis]) && Float.isFinite(part.normals[index + axis]);
                    squared += (double) part.normals[index + axis] * part.normals[index + axis];
                }
                if (squared > 1e-10) {
                    nonzeroNormals++;
                    normalized &= Math.abs(squared - 1) < .001;
                }
            }
            check(finite, "Deformed positions and normals remain finite: " + label);
            check(normalized, "Nonzero deformed normals remain unit length: " + label);
            check(nonzeroNormals >= part.primitive.vertexCount() * .98,
                "Skin transforms preserve usable lighting normals: " + label);
        }
    }

    private static Bounds bounds(List<Part> parts) {
        Bounds bounds = new Bounds();
        for (Part part : parts) {
            for (int vertex : part.primitive.indices()) bounds.add(part.positions, vertex * 3);
        }
        return bounds;
    }

    private static void checkController(GlbModel model) {
        ModelAnimations controller = new ModelAnimations(model);
        var idle = controller.frame(0, true, true);
        check(idle.clip() >= 0 && idle.loop(), "Stationary character has a looping idle clip");
        String idleName = model.animations().get(idle.clip()).name().toLowerCase(Locale.ROOT);
        check(idleName.contains("idle") || idleName.contains("stand"), "Idle playback selects the exported stand/idle clip");
        check(controller.frame(1, false, true).clip() == idle.clip(),
            "This dance pack uses idle while moving instead of a T-pose or dance");
        Set<Integer> selected = new HashSet<>();
        for (int choice = 0; choice < 4; choice++) {
            controller = new ModelAnimations(model);
            ExactChoice random = new ExactChoice(choice);
            controller.celebrate(10, true, random);
            var started = controller.frame(10, true, true);
            check(random.calls == 1 && random.bound == 4, "Every kill selects once from exactly four dance clips");
            check(started.clip() >= 0 && started.clip() != idle.clip() && !started.loop() && started.seconds() == 0,
                "Celebration starts a dance from zero without looping");
            check(selected.add(started.clip()), "All four dance choices are distinct and reachable");
            double duration = Math.min(20, model.animations().get(started.clip()).duration());
            check(controller.frame(10 + duration * .5, true, true).clip() == started.clip(),
                "Dance remains active halfway through its bounded duration");
            check(controller.frame(10 + duration + .01, true, true).clip() == idle.clip(),
                "Dance finishes and restores idle");
            controller.celebrate(100, false, new ExactChoice(choice));
            check(controller.frame(100.01, false, true).clip() == started.clip(), "Moving kill starts the selected dance immediately");
            check(controller.frame(100.02, true, true).clip() == started.clip(), "Stopping keeps the selected dance active");
            check(controller.frame(100 + duration + .01, false, true).clip() == idle.clip(),
                "Completed dance uses this pack's idle fallback while moving");
        }
        check(selected.size() == model.animations().size() - 1, "Every non-idle clip is available after kills");
    }

    private static void checkBundled(GlbModel expected, byte[] expectedBytes) throws IOException {
        check(BundledPlayerModel.available(), "Bundled model is visible through its defining classloader");
        try (InputStream input = BundledPlayerModel.class.getResourceAsStream("/assets/celeste/models/sahur.glb")) {
            check(input != null, "Bundled resource stream exists");
            check(Arrays.equals(expectedBytes, input.readNBytes(MAX_BUNDLED_BYTES + 1)),
                "Bundled resource bytes exactly match the validated converted asset");
        }
        GlbModel bundled = BundledPlayerModel.load();
        check(sameClips(bundled, SahurLocomotion.withWalk(expected)),
            "Bundled import preserves all original clips and appends the authored walking stride");
        check(bundled.meshes().size() == expected.meshes().size() && bundled.skins().size() == expected.skins().size()
            && bundled.images().size() == expected.images().size(), "Bundled import preserves model, skeleton and texture counts");
    }

    private static void checkWalking(GlbModel model, GlbModel original) {
        check(model.animations().size() == 6, "Bundled Sahur gains exactly one walking clip");
        check(SahurLocomotion.withWalk(model) == model, "Walking augmentation is idempotent");
        for (int clip = 0; clip < original.animations().size(); clip++)
            check(model.animations().get(clip) == original.animations().get(clip), "Original idle/dances are not rewritten");
        ModelAnimations controller = new ModelAnimations(model);
        var walking = controller.frame(0, false, true, 1);
        check(model.animations().get(walking.clip()).name().equals("walk"), "Movement selects the authored stride, not a dance");
        GlbPose pose = new GlbPose(model).sample(walking.clip(), 0, true);
        List<Part> parts = geometry(model, pose);
        for (Part part : parts) System.arraycopy(part.positions, 0, part.reference, 0, part.positions.length);
        Bounds initial = bounds(parts);
        double motion = 0;
        double duration = model.animations().get(walking.clip()).duration();
        for (int frame = 0; frame <= 64; frame++) {
            pose.sample(walking.clip(), duration * frame / 64, false);
            deform(parts, pose, "authored walk frame " + frame);
            Bounds current = bounds(parts);
            check(current.distanceFrom(initial) < initial.height() * .7, "Walk remains anchored at player feet");
            check(Math.abs(current.minY - initial.minY) < initial.height() * .004,
                "Walk planted foot stays on its original floor, without floating or sinking");
            for (Part part : parts) for (int index = 0; index < part.positions.length; index++)
                motion = Math.max(motion, Math.abs(part.positions[index] - part.reference[index]));
        }
        check(motion > initial.height() * .025, "Walking visibly bends the actual skinned mesh");
        for (Part part : parts) for (int index = 0; index < part.positions.length; index++)
            if (Math.abs(part.positions[index] - part.reference[index]) > .00001)
                throw new AssertionError("Walking loop must join seamlessly at its end");
        check(true, "Walking endpoints join seamlessly");
        int idle = controller.frame(2, true, true).clip();
        var source = new ModelAnimations.Playback(walking.clip(), .2, true);
        pose.sampleBlended(source); deform(parts, pose, "blend source");
        for (Part part : parts) System.arraycopy(part.positions, 0, part.reference, 0, part.positions.length);
        pose.sampleBlended(new ModelAnimations.Playback(idle, .2, true, walking.clip(), .2, true, 0));
        deform(parts, pose, "zero blend");
        for (Part part : parts) check(Arrays.equals(part.reference, part.positions), "Zero blend exactly preserves source pose");
        pose.sampleBlended(new ModelAnimations.Playback(idle, .2, true, walking.clip(), .2, true, .5));
        deform(parts, pose, "half blend");
        pose.sampleBlended(new ModelAnimations.Playback(idle, .2, true));
        deform(parts, pose, "blend target");
        System.out.println("PASS authored Sahur walk: max deformation=" + motion / initial.height() + " model heights; 65 skinned frames + crossfade");
    }

    private static boolean sameClips(GlbModel first, GlbModel second) {
        if (first.animations().size() != second.animations().size()) return false;
        for (int i = 0; i < first.animations().size(); i++) {
            var a = first.animations().get(i);
            var b = second.animations().get(i);
            if (!a.name().equals(b.name()) || a.duration() != b.duration() || a.channels().size() != b.channels().size()) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Part {
        final int node;
        final GlbModel.Primitive primitive;
        final float[] positions, normals, reference;
        Part(int node, GlbModel.Primitive primitive) {
            this.node = node;
            this.primitive = primitive;
            positions = new float[primitive.positions().length];
            normals = new float[positions.length];
            reference = new float[positions.length];
        }
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        void add(float[] points, int index) {
            minX = Math.min(minX, points[index]); maxX = Math.max(maxX, points[index]);
            minY = Math.min(minY, points[index + 1]); maxY = Math.max(maxY, points[index + 1]);
            minZ = Math.min(minZ, points[index + 2]); maxZ = Math.max(maxZ, points[index + 2]);
        }
        double width() { return maxX - minX; }
        double height() { return maxY - minY; }
        double depth() { return maxZ - minZ; }
        double distanceFrom(Bounds other) {
            return Math.max(Math.max(Math.abs(minX - other.minX), Math.abs(maxX - other.maxX)),
                Math.max(Math.max(Math.abs(minY - other.minY), Math.abs(maxY - other.maxY)),
                    Math.max(Math.abs(minZ - other.minZ), Math.abs(maxZ - other.maxZ))));
        }
    }

    private static final class ExactChoice implements RandomGenerator {
        private final int choice;
        private int calls, bound;
        ExactChoice(int choice) { this.choice = choice; }
        @Override public int nextInt(int upper) {
            check(choice >= 0 && choice < upper, "Requested dance exists in the actual celebration pool");
            calls++; bound = upper; return choice;
        }
        @Override public long nextLong() { throw new AssertionError("Unexpected unbounded random selection"); }
    }
}
