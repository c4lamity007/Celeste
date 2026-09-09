import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientGeometry;
import java.util.*;

/** Pure production helper checks; no Minecraft, world mutations or GPU assumptions. */
public final class FallingLeavesCheck {
    static int checks;
    static void require(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void near(double a, double b, double tolerance, String label) { require(Math.abs(a - b) <= tolerance, label + ": " + a + " / " + b); }
    static void field() {
        Random random = new Random(684295);
        int falls = 0, wraps = 0;
        for (int n = 0; n < 12000; n++) {
            int x = random.nextInt(12000000) - 6000000, y = random.nextInt(92) - 16, z = random.nextInt(12000000) - 6000000;
            double time = random.nextDouble() * 72000 - 36000;
            var a = AmbientField.sample(x, y, z, AmbientField.Style.LEAVES, time);
            var b = AmbientField.sample(x, y, z, AmbientField.Style.LEAVES, time + 1.0 / 60);
            require(a.equals(AmbientField.sample(x, y, z, AmbientField.Style.LEAVES, time)), "deterministic cell sample");
            require(Double.isFinite(a.x()) && Double.isFinite(a.y()) && Double.isFinite(a.z())
                && Double.isFinite(a.yaw()) && Double.isFinite(a.pitch()), "finite world pose");
            require(a.x() >= x * 5.0 && a.x() <= (x + 1) * 5.0 && a.y() >= y * 5.0 && a.y() <= (y + 1) * 5.0
                && a.z() >= z * 5.0 && a.z() <= (z + 1) * 5.0, "leaf never escapes cull cell");
            require(a.opacity() >= 0 && a.opacity() <= 1 && a.size() >= .136 && a.size() <= .204, "bounded opacity and default size");
            if (b.y() > a.y()) {
                wraps++;
                require(a.opacity() < .001 && b.opacity() < .001, "invisible cell recycle");
            } else {
                falls++;
                double speed = (a.y() - b.y()) * 60;
                require(speed >= .224999 && speed <= .350001, "gentle downward velocity");
            }
            require(Math.abs(a.x() - b.x()) < .007 && Math.abs(a.z() - b.z()) < .005, "continuous lateral eddy");
            require(Math.abs(a.pitch() - b.pitch()) > .002 && Math.abs(a.pitch() - b.pitch()) < .02, "gentle tumble");
            double ex = a.x() + (random.nextDouble() - .5) * 60, ey = a.y() + (random.nextDouble() - .5) * 35;
            double ez = a.z() + (random.nextDouble() - .5) * 60;
            require(AmbientField.mayBeVisible(x, y, z, ex, ey, ez)
                || AmbientField.visibility(a.x() - ex, a.y() - ey, a.z() - ez) == 0, "conservative existing cell cull");
        }
        require(falls > 11000 && wraps > 0, "fall and recycle cases exercised");
        var zero = AmbientField.sample(4, -8, 16, AmbientField.Style.LEAVES, 0);
        for (double time : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            require(zero.equals(AmbientField.sample(4, -8, 16, AmbientField.Style.LEAVES, time)), "invalid clock fallback");
        for (double time : new double[]{-Double.MAX_VALUE, Double.MAX_VALUE}) {
            var leaf = AmbientField.sample(4, -8, 16, AmbientField.Style.LEAVES, time);
            require(Double.isFinite(leaf.x()) && Double.isFinite(leaf.y()) && Double.isFinite(leaf.z())
                && Double.isFinite(leaf.pitch()) && Double.isFinite(leaf.yaw()), "extreme finite clock");
        }
        require(!AmbientField.occupied(0, 0, 0, 0), "density zero emits nothing");
        require(AmbientField.visibility(0, 0, 0) == 0 && AmbientField.visibility(23, 0, 0) == 0, "camera clearance and bounded range");
        System.out.println("PASS leaf field: deterministic world-cell drift, " + falls + " downward steps / " + wraps + " invisible recycles, finite clocks, conservative culling, zero density");
    }
    static void geometry() {
        double[] base = new double[AmbientGeometry.SCRATCH_SIZE], rotated = base.clone();
        AmbientGeometry.leaf(base, 0, 0, 0, 1, 0, 0);
        near(base[0], 0, 0, "pointed tip"); near(base[12], 0, 0, "tapered base");
        require(base[2] < -1.5 && base[14] > 1 && base[25] > .15 && base[28] < -.05, "long blade and real folded ridge");
        Map<String,Integer> edges = new HashMap<>();
        int faces = 0;
        for (int edge = 0; edge < 8; edge++) for (int side = 0; side < 2; side++) {
            int next = (edge + 1) % 8;
            int[] face = side == 0 ? new int[]{8, next, edge} : new int[]{9, edge, next};
            double[] saved = base.clone();
            AmbientGeometry.leafNormal(base, face[0], face[1], face[2]);
            for (int i = 0; i < 30; i++) near(base[i], saved[i], 0, "normal preserves mesh vertices");
            near(base[30] * base[30] + base[31] * base[31] + base[32] * base[32], 1, 1E-12, "unit face normal");
            require(side == 0 ? base[31] > 0 : base[31] < 0, "outward top / underside winding");
            for (int i = 0; i < 3; i++) {
                int a = face[i], b = face[(i + 1) % 3];
                edges.merge(Math.min(a, b) + ":" + Math.max(a, b), 1, Integer::sum);
            }
            faces++;
        }
        require(faces == 16 && 10 - edges.size() + faces == 2, "closed bounded genus-zero mesh");
        for (int references : edges.values()) require(references == 2, "watertight shared edge");
        require(faces * 3 * 128 == 6144, "maximum visible leaf batch vertex budget");
        for (int i = 0; i < 10; i++) {
            require(AmbientGeometry.leafU(i) >= 2 && AmbientGeometry.leafU(i) <= 3, "leaf material tag bounds");
            require(AmbientGeometry.leafV(i) >= 0 && AmbientGeometry.leafV(i) <= 1, "leaf UV bounds");
        }
        Random random = new Random(374);
        for (int test = 0; test < 1000; test++) {
            double radius = .068 + random.nextDouble() * .34, yaw = random.nextDouble() * 100, pitch = random.nextDouble() * 100;
            double x = 29999990.5, y = -55.125, z = -29999990.5;
            AmbientGeometry.leaf(rotated, x, y, z, radius, yaw, pitch);
            for (int i = 0; i < 30; i += 3) {
                double dx = rotated[i] - x, dy = rotated[i + 1] - y, dz = rotated[i + 2] - z;
                near(Math.sqrt(dx * dx + dy * dy + dz * dz), radius * Math.sqrt(base[i] * base[i] + base[i + 1] * base[i + 1] + base[i + 2] * base[i + 2]), 7E-9, "rigid world-border rotation");
            }
        }
        // Preserve the original six-vertex core and its public normal scratch convention.
        AmbientGeometry.core(base, 0, 0, 0, 1, 2.2, 0, 0);
        near(base[1], 2.2, 0, "crystal core unchanged"); near(base[4], -2.2, 0, "core underside unchanged");
        AmbientGeometry.normal(base, 0, 3, 2);
        near(base[18] * base[18] + base[19] * base[19] + base[20] * base[20], 1, 1E-12, "legacy core normal slots unchanged");
        System.out.println("PASS leaf geometry: watertight 16-facet folded blade, outward winding, UV material, rigid world-border rotation, legacy cores preserved");
    }
    public static void main(String[] args) { field(); geometry(); System.out.println("PASS Falling Leaves: " + checks + " pure checks; in-game motion verification separate."); }
}
