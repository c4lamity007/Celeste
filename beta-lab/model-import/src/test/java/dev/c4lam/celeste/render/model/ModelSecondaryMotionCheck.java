package dev.c4lam.celeste.render.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Pure fixture checks: no paid asset, game, GPU or filesystem access. */
public final class ModelSecondaryMotionCheck {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static int checks;

    public static void main(String[] args) {
        selection(); dynamics(); reset(); isolation(); frameRates(); poseContract(); animatedBase();
        System.out.println("PASS ModelSecondaryMotionCheck: " + checks + " checks (synthetic rig only)");
    }

    private static void selection() {
        for (String[] names : List.of(new String[]{"Breast_L", "Breast_R"}, new String[]{"LeftBreast", "RightBreast"},
            new String[]{"J_Sec_L_Bust1", "J_Sec_R_Bust1"}, new String[]{"LeftChest", "RightChest"})) {
            check(new ModelSecondaryMotion(model(names[0], names[1], "LeftTip", "RightTip")).jointCount() == 2, "explicit pair selected");
        }
        for (String[] names : List.of(new String[]{"Chest", "Spine"}, new String[]{"UpperChest", "Hips"},
            new String[]{"LeftBreast", "Chest"}, new String[]{"LeftBreast", "LeftBreast"}, new String[]{"Breast_L", "Bust_R"})) {
            check(!new ModelSecondaryMotion(model(names[0], names[1], "LeftTip", "RightTip")).available(), "torso/unpaired/mismatched bones excluded");
        }
        check(!new ModelSecondaryMotion(model("LeftChest", "RightChest", "LeftUpperArm", "RightUpperArm")).available(), "paired torso names with limb descendants excluded");
        GlbModel source = model("LeftBreast", "RightBreast", "LeftTip", "RightTip");
        var primitive = source.meshes().getFirst().primitives().getFirst();
        float[] weights = primitive.weights().clone(); Arrays.fill(weights, 0);
        var noWeight = new GlbModel.Primitive(primitive.positions(), primitive.normals(), null, primitive.joints(), weights, primitive.indices(), 0);
        check(!new ModelSecondaryMotion(copy(source, List.of(new GlbModel.Mesh("mesh", List.of(noWeight))), source.nodes())).available(), "unweighted bones excluded");
        check(new ModelSecondaryMotion(manyPairs()).jointCount() == 8, "eight-joint hard cap");
    }

    private static void dynamics() {
        GlbModel model = model("LeftBreast", "RightBreast", "LeftTip", "RightTip");
        GlbPose pose = new GlbPose(model); ModelSecondaryMotion motion = new ModelSecondaryMotion(model);
        float[] rest = pose.nodeMatrix(2).clone();
        run(motion, pose, A, 60, 1, true);
        check(distance(rest, pose.nodeMatrix(2)) > 1e-4, "acceleration drives actual secondary joint");
        float[] deformed = new float[27]; pose.skin(6, 0, deformed, null);
        check(distance(deformed, model.meshes().getFirst().primitives().getFirst().positions()) > 1e-5, "weighted mesh follows spring joint");
        check(Arrays.equals(new GlbPose(model).nodeMatrix(1), pose.nodeMatrix(1)), "torso transform never changes");
        for (int i = 61; i <= 600; i++) apply(motion, pose, frame(A, i / 60.0, 0, 1, 0, true, .5f));
        check(distance(rest, pose.nodeMatrix(2)) < 1e-6, "motion settles at rest");
        for (var node : model.nodes()) check(Arrays.equals(node.rotation(), new float[]{0, 0, 0, 1}), "immutable model rotations retained");
    }

    private static void reset() {
        GlbModel model = model("LeftBreast", "RightBreast", "LeftTip", "RightTip");
        GlbPose pose = new GlbPose(model); ModelSecondaryMotion motion = new ModelSecondaryMotion(model);
        float[] rest = pose.nodeMatrix(2).clone();
        run(motion, pose, A, 60, 1, true); float[] moving = pose.nodeMatrix(2).clone();
        apply(motion, pose, frame(A, 1, 0, 1, 0, true, .5f));
        check(Arrays.equals(moving, pose.nodeMatrix(2)), "same-time paused frame freezes exact offset");
        apply(motion, pose, frame(A, 1 + 1e-7, 0, 1, 0, true, .5f));
        check(Arrays.equals(moving, pose.nodeMatrix(2)), "tiny dt does not amplify velocity");
        apply(motion, pose, frame(A, 1.01, 100, 1, 0, true, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)), "teleport resets to base pose");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, frame(A, 5, 0, 1, 0, true, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)), "long gap resumes at base pose");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, frame(A, .5, 0, 1, 0, true, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)), "clock rewind resets");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, frame(A, 1.01, 0, 1, 0, false, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)) && motion.playerCount() == 0, "off is exact base pose and removes state");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, frame(A, 1.01, 0, 1, 0, true, 0));
        check(Arrays.equals(rest, pose.nodeMatrix(2)) && motion.playerCount() == 0, "zero strength is exact base pose");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, frame(A, Double.NaN, 0, 1, 0, true, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)) && motion.playerCount() == 0, "nonfinite time fails inert");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, new ModelSecondaryMotion.Frame(A, 1.01, 0, 1, 0, 0, 2, true, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)), "scale changes reset inertia");
        motion.clear(); check(motion.playerCount() == 0, "world/model/disable clear all state");
        run(motion, pose, A, 60, 1, true);
        apply(motion, pose, new ModelSecondaryMotion.Frame(A, 1.01, 0, 1, 0, 180, 1, true, .5f));
        check(Arrays.equals(rest, pose.nodeMatrix(2)), "large heading discontinuity resets");
    }

    private static void isolation() {
        GlbModel model = model("LeftBreast", "RightBreast", "LeftTip", "RightTip");
        ModelSecondaryMotion mixed = new ModelSecondaryMotion(model), single = new ModelSecondaryMotion(model);
        GlbPose scratch = new GlbPose(model), expected = new GlbPose(model);
        float[] rest = scratch.nodeMatrix(2).clone();
        for (int i = 0; i <= 120; i++) {
            double t = i / 60.0;
            apply(mixed, scratch, frame(B, t, 0, 0, 0, true, .5f));
            check(Arrays.equals(rest, scratch.nodeMatrix(2)), "stationary player unaffected by other UUID");
            apply(mixed, scratch, frame(A, t, 0, Math.sin(t * 3) * .1, 0, true, .5f));
            apply(single, expected, frame(A, t, 0, Math.sin(t * 3) * .1, 0, true, .5f));
            check(Arrays.equals(scratch.nodeMatrix(2), expected.nodeMatrix(2)), "interleaving players preserves isolated history");
        }
        for (int i = 0; i < 20; i++) apply(mixed, scratch, frame(new UUID(0, 10 + i), 0, 0, 0, 0, true, .5f));
        check(mixed.playerCount() == 12, "twelve-player state cap");
    }

    private static void frameRates() {
        GlbModel model = model("LeftBreast", "RightBreast", "LeftTip", "RightTip");
        float[] baseline = null;
        for (int rate : new int[]{30, 60, 144}) {
            ModelSecondaryMotion motion = new ModelSecondaryMotion(model); GlbPose pose = new GlbPose(model);
            run(motion, pose, A, rate, 1, true);
            if (baseline == null) baseline = pose.nodeMatrix(2).clone();
            else check(distance(baseline, pose.nodeMatrix(2)) < .001, "30/60/144 Hz bounded equivalence");
            for (float value : pose.nodeMatrix(2)) check(Float.isFinite(value), "finite high-rate transforms");
        }
    }

    private static void poseContract() {
        GlbPose pose = new GlbPose(model("LeftBreast", "RightBreast", "LeftTip", "RightTip"));
        float[] original = pose.nodeMatrix(2).clone();
        expect(() -> pose.applyLocalRotations(new int[]{2, 2}, new float[]{0, 0, 0, 1, 0, 0, 0, 1}));
        expect(() -> pose.applyLocalRotations(new int[]{2}, new float[]{0, 0, 0, 0}));
        expect(() -> pose.applyLocalRotations(new int[]{2}, new float[]{Float.NaN, 0, 0, 1}));
        check(Arrays.equals(original, pose.nodeMatrix(2)), "invalid offsets leave pose unchanged");
        pose.applyLocalRotations(new int[]{2}, new float[]{.1f, 0, 0, 1});
        check(distance(original, pose.nodeMatrix(2)) > .01, "explicit local offset composes");
        pose.sample(-1, 0, false);
        check(Arrays.equals(original, pose.nodeMatrix(2)), "sample clears all previous offsets exactly");
    }

    private static void animatedBase() {
        GlbModel base = model("LeftBreast", "RightBreast", "LeftTip", "RightTip");
        var translation = new GlbModel.Channel(1, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR,
            new float[]{0, 1}, new float[]{0, 1, 0, 0, 1.2f, 0});
        var rotation = new GlbModel.Channel(2, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR,
            new float[]{0, 1}, new float[]{0, 0, 0, 1, .2f, 0, 0, .98f});
        GlbModel animated = new GlbModel(base.meshes(), base.nodes(), base.skins(), base.materials(), base.images(),
            List.of(new GlbModel.Animation("idle", 1, List.of(translation, rotation))), base.sceneRoots(), base.sceneNodes(), base.topologicalOrder());
        ModelSecondaryMotion motion = new ModelSecondaryMotion(animated);
        GlbPose pose = new GlbPose(animated), baseline = new GlbPose(animated);
        for (int i = 0; i <= 60; i++) {
            double t = i / 60.0;
            pose.sample(0, t, false);
            motion.apply(pose, frame(A, t, 0, t * t, 0, true, .5f));
            baseline.sample(0, t, false);
            check(Arrays.equals(pose.nodeMatrix(1), baseline.nodeMatrix(1)), "animated torso stays unchanged");
        }
        pose.sample(0, .7, false); baseline.sample(0, .7, false);
        motion.apply(pose, frame(A, 1.01, 0, 1, 0, false, .5f));
        for (int node = 0; node < animated.nodes().size(); node++) check(Arrays.equals(pose.nodeMatrix(node), baseline.nodeMatrix(node)), "off retains exact sampled animation");
    }

    private static void run(ModelSecondaryMotion motion, GlbPose pose, UUID id, int fps, double seconds, boolean enabled) {
        motion.forget(id);
        apply(motion, pose, frame(id, -.1, 0, 0, 0, enabled, .5f));
        for (int i = 0; i <= (int) (seconds * fps); i++) {
            double t = i / (double) fps;
            apply(motion, pose, frame(id, t, 0, t * t, 0, enabled, .5f));
        }
    }

    private static void apply(ModelSecondaryMotion motion, GlbPose pose, ModelSecondaryMotion.Frame frame) { pose.sample(-1, 0, false); motion.apply(pose, frame); }
    private static ModelSecondaryMotion.Frame frame(UUID id, double t, double x, double y, double z, boolean enabled, float strength) {
        return new ModelSecondaryMotion.Frame(id, t, x, y, z, 0, 1, enabled, strength);
    }
    private static GlbModel model(String left, String right, String endLeft, String endRight) {
        List<GlbModel.Node> nodes = List.of(node("Root", -1, -1, new int[]{1, 6}, 0, 0, 0), node("Chest", -1, -1, new int[]{2, 3}, 0, 1, 0),
            node(left, -1, -1, new int[]{4}, -.18f, .1f, .1f), node(right, -1, -1, new int[]{5}, .18f, .1f, .1f),
            node(endLeft, -1, -1, new int[0], 0, 0, .2f), node(endRight, -1, -1, new int[0], 0, 0, .2f), node("Mesh", 0, 0, new int[0], 0, 0, 0));
        float[] positions = {-.2f, 1.1f, .1f, -.1f, 1.1f, .1f, -.15f, 1.1f, .3f, .1f, 1.1f, .1f, .2f, 1.1f, .1f, .15f, 1.1f, .3f, 0, 1, 0, .1f, 1, 0, 0, 1.1f, 0};
        int[] joints = new int[36]; float[] weights = new float[36];
        for (int i = 0; i < 9; i++) { joints[i * 4] = i < 3 ? 1 : i < 6 ? 2 : 0; weights[i * 4] = 1; }
        var primitive = new GlbModel.Primitive(positions, null, null, joints, weights, new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, 0);
        float[] inverse = new float[48];
        for (int i = 0; i < 3; i++) { inverse[i * 16] = inverse[i * 16 + 5] = inverse[i * 16 + 10] = inverse[i * 16 + 15] = 1; inverse[i * 16 + 13] = i == 0 ? -1 : -1.1f; }
        inverse[16 + 12] = .18f; inverse[16 + 14] = -.1f; inverse[32 + 12] = -.18f; inverse[32 + 14] = -.1f;
        return new GlbModel(List.of(new GlbModel.Mesh("body", List.of(primitive))), nodes, List.of(new GlbModel.Skin("rig", new int[]{1, 2, 3}, inverse)),
            List.of(new GlbModel.Material("mat", new float[]{1, 1, 1, 1}, -1, false, GlbModel.AlphaMode.OPAQUE, .5f, 10497, 10497, true)), List.of(), List.of(), new int[]{0}, new int[]{0, 1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 3, 4, 5, 6});
    }

    private static GlbModel manyPairs() {
        List<GlbModel.Node> nodes = new ArrayList<>(); int[] children = new int[11]; for (int i = 0; i < children.length; i++) children[i] = i + 1;
        nodes.add(node("Chest", -1, -1, children, 0, 1, 0));
        for (int i = 0; i < 10; i++) nodes.add(node((i % 2 == 0 ? "LeftBreast" : "RightBreast") + (i < 2 ? "" : i / 2), -1, -1, new int[0], i % 2 == 0 ? -.1f : .1f, 0, .2f));
        nodes.add(node("Mesh", 0, 0, new int[0], 0, 0, 0));
        int[] skinJoints = new int[10], vertexJoints = new int[40], order = new int[12]; float[] weights = new float[40], inverse = new float[160];
        for (int i = 0; i < 10; i++) { skinJoints[i] = i + 1; vertexJoints[i * 4] = i; weights[i * 4] = 1; inverse[i * 16] = inverse[i * 16 + 5] = inverse[i * 16 + 10] = inverse[i * 16 + 15] = 1; }
        for (int i = 0; i < order.length; i++) order[i] = i;
        var primitive = new GlbModel.Primitive(new float[30], null, null, vertexJoints, weights, new int[]{0, 1, 2}, 0);
        return new GlbModel(List.of(new GlbModel.Mesh("body", List.of(primitive))), nodes, List.of(new GlbModel.Skin("rig", skinJoints, inverse)), model("a", "b", "c", "d").materials(), List.of(), List.of(), new int[]{0}, order, order);
    }

    private static GlbModel.Node node(String name, int mesh, int skin, int[] children, float x, float y, float z) { return new GlbModel.Node(name, mesh, skin, children, new float[]{x, y, z}, new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}, null); }
    private static GlbModel copy(GlbModel source, List<GlbModel.Mesh> meshes, List<GlbModel.Node> nodes) { return new GlbModel(meshes, nodes, source.skins(), source.materials(), source.images(), source.animations(), source.sceneRoots(), source.sceneNodes(), source.topologicalOrder()); }
    private static double distance(float[] a, float[] b) { double result = 0; for (int i = 0; i < a.length; i++) result = Math.max(result, Math.abs(a[i] - b[i])); return result; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void expect(Runnable action) { try { action.run(); throw new AssertionError("Expected invalid offset rejection"); } catch (IllegalArgumentException expected) { checks++; } }
}
