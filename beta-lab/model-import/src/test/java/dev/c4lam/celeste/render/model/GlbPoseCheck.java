package dev.c4lam.celeste.render.model;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Pure-JDK pose fixtures. No Minecraft initialization, rendering, file or native access. */
public final class GlbPoseCheck {
    private static final float EPSILON = 0.00002F;
    private static int checks;
    private GlbPoseCheck() { }

    public static void main(String[] args) {
        transformOrder();
        animationSampling();
        rotations();
        bindOffsetAndPaletteReuse();
        weightedNormals();
        rigidNormals();
        validation();
        blendedSampling();
        System.out.println("PASS GLB pose: " + checks + " checks; TRS, hierarchy, clip interpolation/reset and model-space CPU skinning");
    }

    private static void blendedSampling() {
        GlbModel.Node node = node(0, -1, new int[0], v(0, 0, 0), rotationZ(0), v(1, 1, 1));
        GlbModel.Animation turn = animation("turn", 1,
            channel(0, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR, new float[]{0}, v(2, 0, 0)),
            channel(0, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR, new float[]{0}, rotationZ(90)));
        GlbModel model = model(List.of(mesh(v(1, 0, 0), v(0, 1, 0), null, null)), List.of(node), List.of(), List.of(turn), new int[]{0});
        GlbPose pose = new GlbPose(model);
        pose.sampleBlended(new ModelAnimations.Playback(0, 0, false, -1, 0, false, .5));
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(1 + (float) Math.sqrt(.5), (float) Math.sqrt(.5), 0), "blend lerps position and slerps rotation");
        float[] matrix = pose.nodeMatrix(0);
        pose.sampleBlended(new ModelAnimations.Playback(0, 0, false, -1, 0, false, 0));
        near(point(matrix, 1, 0, 0), v(1, 0, 0), "zero blend returns exact bind source and reuses matrices");
        pose.sampleBlended(new ModelAnimations.Playback(0, 0, false));
        near(point(matrix, 1, 0, 0), v(2, 1, 0), "finished blend is exact target");
        for (double invalid : new double[]{Double.NaN, -1, 1.1})
            expect(IllegalArgumentException.class, () -> pose.sampleBlended(new ModelAnimations.Playback(0, 0, false, -1, 0, false, invalid)), "invalid blend rejected");
        pose.sample(-1, 0, false);
        near(point(matrix, 1, 0, 0), v(1, 0, 0), "bind reset removes all previous blend transforms");
    }

    private static void transformOrder() {
        GlbModel.Node child = node(0, -1, new int[0], v(2, 0, 0), rotationZ(0), v(1, 1, 1));
        GlbModel.Node parent = node(-1, -1, new int[] {0}, v(10, 0, 0), rotationZ(90), v(1, 1, 1));
        GlbModel.Node independent = node(0, -1, new int[0], v(5, -2, 7), rotationZ(90), v(2, 3, 4));
        GlbModel model = model(List.of(mesh(v(1, 1, 1), v(0, 0, 1), null, null)), List.of(child, parent, independent), List.of(), List.of(), new int[] {1, 0, 2});
        InputSnapshot source = new InputSnapshot(model);
        GlbPose pose = new GlbPose(model);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(10, 2, 0), "topological order, not node index, drives parent multiplication");
        near(point(pose.nodeMatrix(2), 1, 1, 1), v(2, 0, 11), "column-major T*R*S order");
        check(pose.skinMatrices(0) == null, "rigid node has no skin palette");
        float[] positions = new float[3], normals = new float[3];
        pose.skin(2, 0, positions, normals);
        near(positions, v(2, 0, 11), "rigid CPU output includes node transform once");
        near(normals, v(0, 0, 1), "rigid transformed normal normalized");
        Arrays.fill(positions, 99); Arrays.fill(normals, 99);
        pose.deform(2, model.meshes().getFirst().primitives().getFirst(), positions, normals);
        near(positions, v(2, 0, 11), "renderer deform entry point matches rigid skin wrapper");
        near(normals, v(0, 0, 1), "renderer deform reuses normal output");
        float[] cached = pose.nodeMatrix(0);
        check(pose.sample(-1, 123, true) == pose && pose.nodeMatrix(0) == cached, "bind reset reuses pose and cached matrices");
        near(point(cached, 0, 0, 0), v(10, 2, 0), "repeated bind sample overwrites complete cached transform");
        source.verify();
    }

    private static void animationSampling() {
        GlbModel.Node node = node(0, -1, new int[0], v(7, 8, 9), rotationZ(0), v(1, 1, 1));
        GlbModel.Animation linear = animation("move and scale", 4,
            channel(0, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR, new float[] {1, 3}, new float[] {2, 3, 4, 6, 7, 8}),
            channel(0, GlbModel.Target.SCALE, GlbModel.Interpolation.LINEAR, new float[] {0, 4}, new float[] {1, 1, 1, 3, 5, 7}));
        GlbModel.Animation step = animation("step", 2,
            channel(0, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.STEP, new float[] {0, 1, 2}, new float[] {0, 0, 0, 5, 0, 0, 9, 0, 0}));
        GlbModel.Animation zero = animation("constant", 0,
            channel(0, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR, new float[] {0}, new float[] {8, 9, 10}));
        GlbModel.Animation noChannels = animation("base only", 5);
        GlbModel model = model(List.of(mesh(v(1, 1, 1), v(0, 1, 0), null, null)), List.of(node), List.of(), List.of(linear, step, zero, noChannels), new int[] {0});
        InputSnapshot source = new InputSnapshot(model); GlbPose pose = new GlbPose(model);
        pose.sample(0, 2, false);
        near(point(pose.nodeMatrix(0), 1, 1, 1), v(6, 8, 10), "linear translation and scale interpolate independently");
        pose.sample(0, -10, false);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(2, 3, 4), "time before first translation key clamps to its first sample");
        pose.sample(0, 20, false);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(6, 7, 8), "time past last translation key clamps to its last sample");
        pose.sample(0, 6, true);
        near(point(pose.nodeMatrix(0), 1, 1, 1), v(6, 8, 10), "loop modulo duration reaches equivalent sample");
        pose.sample(0, -2, true);
        near(point(pose.nodeMatrix(0), 1, 1, 1), v(6, 8, 10), "negative looping time wraps to positive cycle position");
        for (double[] sample : new double[][] {{0.999, 0}, {1, 5}, {1.999, 5}, {2, 9}, {10, 9}, {-1, 0}}) {
            pose.sample(1, sample[0], false);
            near(point(pose.nodeMatrix(0), 0, 0, 0), v((float) sample[1], 0, 0), "STEP selects exact previous key including key boundaries");
            near(pose.nodeMatrix(0)[0], 1, "switching clips restores unanimated base scale");
        }
        pose.sample(1, 2, true);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(0, 0, 0), "exact loop duration wraps to first key");
        pose.sample(1, -0.5, true);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(5, 0, 0), "negative STEP loop picks wrapped interval");
        pose.sample(2, Double.MAX_VALUE, true);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(8, 9, 10), "zero-duration clip stays at its single key without division");
        pose.sample(3, 3, false);
        near(point(pose.nodeMatrix(0), 1, 1, 1), v(8, 9, 10), "channel-free clip fully restores original TRS");
        pose.sample(-1, -99, true);
        near(point(pose.nodeMatrix(0), 0, 0, 0), v(7, 8, 9), "minus-one clip restores bind translation");
        source.verify();
    }

    private static void rotations() {
        float[] nonUnit = {0, 0, 0, 2, 0, 0, 4, 0};
        float[] antipodal = concat(rotationZ(30), negate(rotationZ(30)));
        GlbModel model = model(List.of(), List.of(node(-1, -1, new int[0], v(0, 0, 0), new float[] {0, 0, 0, 3}, v(1, 1, 1))), List.of(), List.of(
            animation("normalized midpoint", 2, channel(0, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR, new float[] {0, 2}, nonUnit)),
            animation("short way around", 2, channel(0, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR, new float[] {0, 2}, concat(rotationZ(170), rotationZ(-170)))),
            animation("same rotation signs", 2, channel(0, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR, new float[] {0, 2}, antipodal)),
            animation("near parallel", 2, channel(0, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR, new float[] {0, 2}, concat(rotationZ(0), rotationZ(0.01)))),
            animation("step rotation", 2, channel(0, GlbModel.Target.ROTATION, GlbModel.Interpolation.STEP, new float[] {0, 1, 2}, concat(rotationZ(0), rotationZ(90), rotationZ(180))))), new int[] {0});
        InputSnapshot source = new InputSnapshot(model); GlbPose pose = new GlbPose(model);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(1, 0, 0), "nonunit base quaternion is normalized");
        pose.sample(0, 1, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(0, 1, 0), "rotation LINEAR is normalized SLERP rather than component lerp");
        pose.sample(0, 0.5, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v((float) Math.sqrt(0.5), (float) Math.sqrt(0.5), 0), "quarter-time SLERP has constant angular speed unlike normalized lerp");
        pose.sample(0, 2, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(-1, 0, 0), "nonunit endpoint quaternion is normalized");
        pose.sample(1, 1, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(-1, 0, 0), "positive170 to negative170 takes shortest path through180");
        for (double time : new double[] {0, 0.5, 1, 1.5, 2}) {
            pose.sample(2, time, false);
            near(point(pose.nodeMatrix(0), 1, 0, 0), v((float) Math.cos(Math.toRadians(30)), 0.5F, 0), "antipodal quaternion encodings never collapse or spin");
        }
        pose.sample(3, 1, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v((float) Math.cos(Math.toRadians(0.005)), (float) Math.sin(Math.toRadians(0.005)), 0), "near-parallel interpolation remains stable");
        pose.sample(4, 0.999, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(1, 0, 0), "STEP rotation holds previous quaternion");
        pose.sample(4, 1, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(0, 1, 0), "STEP rotation changes exactly at its key");
        pose.sample(-1, 0, false);
        near(point(pose.nodeMatrix(0), 1, 0, 0), v(1, 0, 0), "rotation bind reset does not retain clip values");
        source.verify();
    }

    private static void bindOffsetAndPaletteReuse() {
        GlbModel.Mesh mesh = mesh(v(1, 0, 0), v(0, 1, 0), new int[] {0, 0, 0, 0}, new float[] {1, 0, 0, 0});
        GlbModel.Node joint = node(-1, -1, new int[0], v(13, 0, 0), rotationZ(0), v(1, 1, 1));
        GlbModel.Node meshNode = node(0, 0, new int[0], v(100, 0, 0), rotationZ(0), v(1, 1, 1));
        GlbModel.Skin skin = new GlbModel.Skin("offset", new int[] {0}, translated(-3, 0, 0));
        GlbModel.Animation animation = animation("joint move", 2,
            channel(0, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR, new float[] {0, 2}, new float[] {13, 0, 0, 17, 0, 0}));
        GlbModel model = new GlbModel(List.of(mesh), List.of(joint, meshNode), List.of(skin), materials(), List.of(), List.of(animation), new int[] {1}, new int[] {1}, new int[] {0, 1});
        InputSnapshot source = new InputSnapshot(model); GlbPose pose = new GlbPose(model);
        float[] palette = pose.skinMatrices(1), matrix = pose.nodeMatrix(0), positions = new float[3], normals = new float[3];
        near(palette[12], 10, "joint world multiplied by nonidentity inverse bind");
        pose.skin(1, 0, positions, normals);
        near(positions, v(11, 0, 0), "skin output is modelspace and never reapplies mesh translation100");
        near(normals, v(0, 1, 0), "skinning ignores affine translation for normals");
        pose.sample(0, 1, false); pose.skin(1, 0, positions, normals);
        check(pose.skinMatrices(1) == palette && pose.nodeMatrix(0) == matrix, "animated sample reuses world and palette arrays");
        near(palette[12], 12, "skin joint outside selected scene is still animated");
        near(positions, v(13, 0, 0), "animated joint moves weighted vertex");
        pose.sample(-1, 0, false); pose.skin(1, 0, positions, normals);
        near(palette[12], 10, "bind reset overwrites palette values");
        near(positions, v(11, 0, 0), "bind reset restores original skinned position");
        source.verify();
    }

    private static void weightedNormals() {
        GlbModel.Mesh mesh = mesh(v(1, 1, 0), v(1, 1, 0), new int[] {0, 1, 0, 0}, new float[] {1, 1, 0, 0});
        GlbModel model = model(List.of(mesh), List.of(
            node(-1, -1, new int[0], v(0, 0, 0), rotationZ(0), v(1, 1, 1)),
            node(-1, -1, new int[0], v(0, 0, 0), rotationZ(0), v(2, 1, 1)),
            node(0, 0, new int[0], v(100, 50, 70), rotationZ(0), v(1, 1, 1))),
            List.of(new GlbModel.Skin("weighted", new int[] {0, 1}, concat(identity(), identity()))), List.of(), new int[] {0, 1, 2});
        InputSnapshot source = new InputSnapshot(model); GlbPose pose = new GlbPose(model);
        float[] positions = {0, 0, 0, 99}, normals = {0, 0, 0, 77}; pose.skin(2, 0, positions, normals);
        near(Arrays.copyOf(positions, 3), v(1.5F, 1, 0), "weights normalize before matrix blending");
        near(Arrays.copyOf(normals, 3), v((float) (2 / Math.sqrt(13)), (float) (3 / Math.sqrt(13)), 0),
            "inverse-transpose applies to effective weighted matrix, not weighted per-joint inverses");
        near(positions[3], 99, "position buffer capacity beyond vertex count is untouched");
        near(normals[3], 77, "normal buffer capacity beyond vertex count is untouched");
        float[] positionsOnly = new float[3]; pose.skin(2, 0, positionsOnly, null);
        near(positionsOnly, v(1.5F, 1, 0), "positions-only CPU skinning uses same model-space output");
        pose.deform(2, mesh.primitives().getFirst(), positionsOnly, null);
        near(positionsOnly, v(1.5F, 1, 0), "renderer deform entry point matches weighted skin wrapper");
        source.verify();
    }

    private static void rigidNormals() {
        float[] shear = identity(); shear[4] = 2;
        GlbModel.Node matrix = new GlbModel.Node("shear", 0, -1, new int[0], null, null, null, shear);
        GlbModel model = model(List.of(mesh(v(0, 1, 0), v(1, 0, 0), null, null)), List.of(matrix), List.of(), List.of(), new int[] {0});
        InputSnapshot source = new InputSnapshot(model); GlbPose pose = new GlbPose(model);
        float[] positions = new float[3], normals = new float[3]; pose.skin(0, 0, positions, normals);
        near(positions, v(2, 1, 0), "explicit column-major matrix retained");
        near(normals, v((float) (1 / Math.sqrt(5)), (float) (-2 / Math.sqrt(5)), 0), "sheared normal uses correctly indexed inverse-transpose");
        source.verify();
        for (float[] scale : new float[][] {v(-2, 1, 1), v(0, 1, 1), v(0.000000000000001F, 1, 1)}) {
            GlbModel scaled = model(List.of(mesh(v(1, 1, 0), v(1, 1, 0), null, null)),
                List.of(node(0, -1, new int[0], v(0, 0, 0), rotationZ(0), scale)), List.of(), List.of(), new int[] {0});
            new GlbPose(scaled).skin(0, 0, positions, normals);
            if (scale[0] < 0) near(normals, v((float) (-1 / Math.sqrt(5)), (float) (2 / Math.sqrt(5)), 0), "negative determinant preserves reflected normal sign");
            else if (scale[0] == 0) near(normals, v(0, 0, 0), "singular normal transform has documented finite zero fallback");
            else near(normals, v(1, 0, 0), "tiny nonzero scale still uses inverse-transpose rather than an arbitrary singular epsilon");
        }
    }

    private static void validation() {
        GlbModel model = model(List.of(mesh(v(1, 0, 0), v(0, 1, 0), null, null)),
            List.of(node(0, -1, new int[0], v(3, 4, 5), rotationZ(0), v(1, 1, 1))), List.of(),
            List.of(animation("move", 1, channel(0, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, 1, 2, 3}))), new int[] {0});
        GlbPose pose = new GlbPose(model); pose.sample(0, 0.5, false); float[] previous = pose.nodeMatrix(0).clone();
        for (double time : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expect(IllegalArgumentException.class, () -> pose.sample(0, time, true), "nonfinite sample time rejected");
            exact(pose.nodeMatrix(0), previous, "invalid time is rejected before changing cached pose");
        }
        for (int clip : new int[] {-2, 1, Integer.MAX_VALUE}) {
            expect(IllegalArgumentException.class, () -> pose.sample(clip, 0, false), "invalid clip rejected");
            exact(pose.nodeMatrix(0), previous, "invalid clip is rejected before resetting cached pose");
        }
        GlbModel.Primitive primitive = model.meshes().getFirst().primitives().getFirst();
        expect(IllegalArgumentException.class, () -> pose.skin(0, 0, primitive.positions(), new float[3]), "source positions cannot be overwritten through an alias");
        expect(IllegalArgumentException.class, () -> pose.skin(0, 0, new float[3], primitive.normals()), "source normals cannot be overwritten through an alias");
        float[] aliased = new float[3]; expect(IllegalArgumentException.class, () -> pose.skin(0, 0, aliased, aliased), "output positions and normals cannot alias");
        expect(IllegalArgumentException.class, () -> pose.skin(0, 0, new float[2], null), "undersized output rejected");
        GlbModel.Primitive nonfinite = mesh(v(Float.NaN, 0, 0), v(0, 1, 0), null, null).primitives().getFirst();
        expect(IllegalArgumentException.class, () -> pose.deform(0, nonfinite, new float[3], null), "deform rejects nonfinite vertex inputs");
        GlbModel invalidQuaternion = model(List.of(), List.of(node(-1, -1, new int[0], v(0, 0, 0), new float[4], v(1, 1, 1))), List.of(), List.of(), new int[] {0});
        expect(IllegalArgumentException.class, () -> new GlbPose(invalidQuaternion), "zero base quaternion rejected");
        GlbModel overflow = model(List.of(), List.of(
            node(-1, -1, new int[] {1}, v(0, 0, 0), rotationZ(0), v(Float.MAX_VALUE, 1, 1)),
            node(-1, -1, new int[0], v(0, 0, 0), rotationZ(0), v(2, 1, 1))), List.of(), List.of(), new int[] {0, 1});
        expect(IllegalArgumentException.class, () -> new GlbPose(overflow), "finite inputs whose hierarchical product overflows are rejected");
    }

    private static GlbModel model(List<GlbModel.Mesh> meshes, List<GlbModel.Node> nodes, List<GlbModel.Skin> skins, List<GlbModel.Animation> animations, int[] order) {
        return new GlbModel(meshes, nodes, skins, materials(), List.of(), animations, order.length == 0 ? new int[0] : new int[] {order[0]}, order.clone(), order);
    }
    private static List<GlbModel.Material> materials() { return List.of(new GlbModel.Material("white", new float[] {1, 1, 1, 1}, -1, false, GlbModel.AlphaMode.OPAQUE, 0.5F, 10497, 10497, true)); }
    private static GlbModel.Mesh mesh(float[] positions, float[] normals, int[] joints, float[] weights) {
        return new GlbModel.Mesh("fixture", List.of(new GlbModel.Primitive(positions, normals, new float[positions.length / 3 * 2], joints, weights, new int[] {0, 0, 0}, 0)));
    }
    private static GlbModel.Node node(int mesh, int skin, int[] children, float[] translation, float[] rotation, float[] scale) {
        return new GlbModel.Node("fixture", mesh, skin, children, translation, rotation, scale, null);
    }
    private static GlbModel.Channel channel(int node, GlbModel.Target target, GlbModel.Interpolation interpolation, float[] times, float[] values) {
        return new GlbModel.Channel(node, target, interpolation, times, values);
    }
    private static GlbModel.Animation animation(String name, float duration, GlbModel.Channel... channels) { return new GlbModel.Animation(name, duration, List.of(channels)); }
    private static float[] v(float x, float y, float z) { return new float[] {x, y, z}; }
    private static float[] rotationZ(double degrees) { double half = Math.toRadians(degrees) / 2; return new float[] {0, 0, (float) Math.sin(half), (float) Math.cos(half)}; }
    private static float[] negate(float[] values) { float[] result = values.clone(); for (int i = 0; i < result.length; i++) result[i] = -result[i]; return result; }
    private static float[] concat(float[]... values) { int count = 0; for (float[] value : values) count += value.length; float[] result = new float[count]; int offset = 0; for (float[] value : values) { System.arraycopy(value, 0, result, offset, value.length); offset += value.length; } return result; }
    private static float[] identity() { float[] result = new float[16]; result[0] = result[5] = result[10] = result[15] = 1; return result; }
    private static float[] translated(float x, float y, float z) { float[] result = identity(); result[12] = x; result[13] = y; result[14] = z; return result; }
    private static float[] point(float[] matrix, float x, float y, float z) { return v(matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12], matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13], matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]); }
    private static void near(float[] actual, float[] expected, String message) { check(actual.length == expected.length, message + " shape"); for (int i = 0; i < actual.length; i++) near(actual[i], expected[i], message + " component " + i); }
    private static void near(float actual, float expected, String message) { check(Float.isFinite(actual) && Math.abs(actual - expected) <= EPSILON, message + ": " + actual + " != " + expected); }
    private static void exact(float[] actual, float[] expected, String message) { check(Arrays.equals(actual, expected), message); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type, Runnable action, String message) { checks++; try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError(message, failure); } throw new AssertionError(message + ": no failure"); }

    private static final class InputSnapshot {
        private final Map<Object, Object> copies = new IdentityHashMap<>();
        InputSnapshot(GlbModel model) {
            add(model.sceneRoots()); add(model.sceneNodes()); add(model.topologicalOrder());
            for (GlbModel.Node node : model.nodes()) { add(node.children()); add(node.translation()); add(node.rotation()); add(node.scale()); add(node.matrix()); }
            for (GlbModel.Skin skin : model.skins()) { add(skin.joints()); add(skin.inverseBindMatrices()); }
            for (GlbModel.Mesh mesh : model.meshes()) for (GlbModel.Primitive primitive : mesh.primitives()) {
                add(primitive.positions()); add(primitive.normals()); add(primitive.texCoords()); add(primitive.joints()); add(primitive.weights()); add(primitive.indices());
            }
            for (GlbModel.Animation animation : model.animations()) for (GlbModel.Channel channel : animation.channels()) { add(channel.times()); add(channel.values()); }
        }
        private void add(Object value) { if (value instanceof float[] values) copies.put(value, values.clone()); else if (value instanceof int[] values) copies.put(value, values.clone()); }
        void verify() {
            for (var entry : copies.entrySet()) {
                boolean equal = entry.getKey() instanceof float[] values ? Arrays.equals(values, (float[]) entry.getValue()) : Arrays.equals((int[]) entry.getKey(), (int[]) entry.getValue());
                check(equal, "sampling/skinning never mutates read-only model input arrays");
            }
        }
    }
}
