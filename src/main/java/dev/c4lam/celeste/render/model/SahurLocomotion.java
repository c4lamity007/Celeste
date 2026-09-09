package dev.c4lam.celeste.render.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A small in-place stride authored against the bundled Sahur rig; the original four dances are untouched. */
public final class SahurLocomotion {
    private static final int KEYS = 33;
    private static final float DURATION = 1.05f;
    private static final String[] JOINTS = {"LeftUpLeg", "RightUpLeg", "LeftLeg", "RightLeg",
        "LeftFoot", "RightFoot", "LeftArm", "RightArm"};
    private SahurLocomotion() {}

    public static GlbModel withWalk(GlbModel model) {
        if (model.animations().stream().anyMatch(clip -> clip.name().equals("walk"))) return model;
        int idle = -1;
        for (int index = 0; index < model.animations().size(); index++)
            if (model.animations().get(index).name().equals("idle")) { idle = index; break; }
        if (idle < 0) return model;
        int[] joints = new int[JOINTS.length];
        Arrays.fill(joints, -1);
        for (int index = 0; index < model.nodes().size(); index++) {
            var node = model.nodes().get(index);
            for (int joint = 0; joint < JOINTS.length; joint++)
                if (node.name().equals("mixamorig:" + JOINTS[joint]) && node.matrix() == null) joints[joint] = index;
        }
        // Do not guess a different model's bone layout.
        for (int joint : joints) if (joint < 0) return model;

        var standing = model.animations().get(idle);
        GlbPose rest = new GlbPose(model).sample(idle, 0, false);
        List<GlbModel.Channel> channels = new ArrayList<>();
        for (var channel : standing.channels()) {
            int width = channel.target() == GlbModel.Target.ROTATION ? 4 : 3;
            channels.add(new GlbModel.Channel(channel.node(), channel.target(), GlbModel.Interpolation.LINEAR,
                new float[]{0, DURATION}, repeated(channel.values(), width)));
        }
        float[] times = new float[KEYS];
        for (int key = 0; key < KEYS; key++) times[key] = DURATION * key / (KEYS - 1);
        for (int joint = 0; joint < joints.length; joint++) {
            int node = joints[joint];
            float[] base = model.nodes().get(node).rotation();
            for (var channel : standing.channels())
                if (channel.node() == node && channel.target() == GlbModel.Target.ROTATION) {
                    base = channel.values(); break;
                }
            // Convert model-space pitch to this bone's local axis. Mirrored limbs have different bind axes.
            float[] world = rest.nodeMatrix(node);
            double x = world[0], y = world[4], z = world[8];
            double length = Math.sqrt(x * x + y * y + z * z);
            x /= length; y /= length; z /= length;
            float[] values = new float[KEYS * 4];
            for (int key = 0; key < KEYS; key++) {
                double phase = Math.PI * 2 * key / (KEYS - 1) + (joint % 2 == 0 ? 0 : Math.PI);
                double stride = Math.sin(phase), lift = Math.max(0, stride);
                double angle = switch (joint / 2) {
                    case 0 -> -.40 * stride;
                    case 1 -> .50 * lift;
                    case 2 -> -.22 * lift;
                    default -> .25 * stride;
                };
                rotate(base, x, y, z, angle, values, key * 4);
            }
            channels.removeIf(channel -> channel.node() == node && channel.target() == GlbModel.Target.ROTATION);
            channels.add(new GlbModel.Channel(node, GlbModel.Target.ROTATION, GlbModel.Interpolation.LINEAR, times, values));
        }
        List<GlbModel.Animation> clips = new ArrayList<>(model.animations());
        clips.add(new GlbModel.Animation("walk", DURATION, channels));
        GlbModel walking = replaceClips(model, clips);
        return groundStride(walking, times);
    }

    /** Keep the planted foot on the original standing floor instead of letting a pitched leg hover or sink. */
    private static GlbModel groundStride(GlbModel model, float[] times) {
        int hips = -1;
        for (int node = 0; node < model.nodes().size(); node++)
            if (model.nodes().get(node).name().equals("mixamorig:Hips")) { hips = node; break; }
        if (hips < 0) return model;
        int clip = model.animations().size() - 1;
        var walk = model.animations().get(clip);
        float[] base = model.nodes().get(hips).translation();
        for (var channel : walk.channels()) if (channel.node() == hips && channel.target() == GlbModel.Target.TRANSLATION) {
            base = channel.values(); break;
        }
        int largest = model.meshes().stream().flatMap(mesh -> mesh.primitives().stream())
            .mapToInt(primitive -> primitive.positions().length).max().orElse(0);
        if (largest == 0) return model;
        float[] positions = new float[largest];
        GlbPose pose = new GlbPose(model).sample(clip, 0, false);
        double floor = floor(model, pose, positions);
        float[] translations = new float[times.length * 3];
        for (int key = 0; key < times.length; key++) {
            pose.sample(clip, times[key], false);
            translations[key * 3] = base[0];
            translations[key * 3 + 1] = (float) (base[1] + floor - floor(model, pose, positions));
            translations[key * 3 + 2] = base[2];
        }
        int root = hips;
        List<GlbModel.Channel> channels = new ArrayList<>(walk.channels());
        channels.removeIf(channel -> channel.node() == root && channel.target() == GlbModel.Target.TRANSLATION);
        channels.add(new GlbModel.Channel(root, GlbModel.Target.TRANSLATION, GlbModel.Interpolation.LINEAR, times, translations));
        List<GlbModel.Animation> clips = new ArrayList<>(model.animations());
        clips.set(clip, new GlbModel.Animation(walk.name(), walk.duration(), channels));
        return replaceClips(model, clips);
    }

    private static double floor(GlbModel model, GlbPose pose, float[] positions) {
        double floor = Double.POSITIVE_INFINITY;
        for (int node : model.sceneNodes()) {
            int mesh = model.nodes().get(node).mesh();
            if (mesh < 0) continue;
            for (var primitive : model.meshes().get(mesh).primitives()) {
                pose.deform(node, primitive, positions, null);
                for (int vertex : primitive.indices()) floor = Math.min(floor, positions[vertex * 3 + 1]);
            }
        }
        return floor;
    }

    private static GlbModel replaceClips(GlbModel model, List<GlbModel.Animation> clips) {
        return new GlbModel(model.meshes(), model.nodes(), model.skins(), model.materials(), model.images(), clips,
            model.sceneRoots(), model.sceneNodes(), model.topologicalOrder());
    }

    private static float[] repeated(float[] value, int width) {
        float[] result = new float[width * 2];
        System.arraycopy(value, 0, result, 0, width);
        System.arraycopy(value, 0, result, width, width);
        return result;
    }

    private static void rotate(float[] base, double x, double y, double z, double angle, float[] out, int offset) {
        double s = Math.sin(angle * .5), w = Math.cos(angle * .5);
        x *= s; y *= s; z *= s;
        double ax = base[0], ay = base[1], az = base[2], aw = base[3];
        double qx = aw * x + ax * w + ay * z - az * y;
        double qy = aw * y - ax * z + ay * w + az * x;
        double qz = aw * z + ax * y - ay * x + az * w;
        double qw = aw * w - ax * x - ay * y - az * z;
        double length = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        out[offset] = (float) (qx / length); out[offset + 1] = (float) (qy / length);
        out[offset + 2] = (float) (qz / length); out[offset + 3] = (float) (qw / length);
    }
}
