package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;

/** Deterministic contract checks; does not require or impersonate an in-game render. */
public final class SkyLinesCheck {
    private static int checks;
    public static void main(String[] args) {
        for (SkyLinesMath.Pattern pattern : SkyLinesMath.Pattern.values()) {
            check(SkyLinesMath.sample(pattern, 7, 0) == SkyLinesMath.Geometry.EMPTY, "zero intensity does no geometry work");
            check(SkyLinesMath.sample(pattern, 7, Double.NaN).isEmpty(), "invalid intensity is inert");
            for (double time : new double[]{0, .125, 40, 625, 1.0e8, Double.NaN}) {
                var field = SkyLinesMath.sample(pattern, time, .65);
                check(field.equals(SkyLinesMath.sample(pattern, time, .65)), "fixed inputs are deterministic");
                check(field.segments().size() <= SkyLinesMath.MAX_SEGMENTS, "hard segment ceiling");
                check(field.stars().size() <= SkyLinesMath.MAX_STARS, "hard star ceiling");
                check(!field.isEmpty(), "enabled pattern produces geometry");
                for (var segment : field.segments()) {
                    point(segment.from()); point(segment.to());
                    double distance = distance(segment.from(), segment.to());
                    check(distance > 0 && distance < 6, "no collapsed or discontinuous ribbon joins");
                    check(Double.isFinite(segment.width()) && segment.width() > 0, "finite positive width");
                    check(segment.opacity() >= 0 && segment.opacity() <= 1, "bounded segment opacity");
                }
                for (var star : field.stars()) {
                    point(star.position());
                    check(star.opacity() >= 0 && star.opacity() <= 1, "bounded star opacity");
                }
            }
            var dim = SkyLinesMath.sample(pattern, 10, .2);
            var bright = SkyLinesMath.sample(pattern, 10, .8);
            for (int i = 0; i < dim.segments().size(); i++) {
                check(dim.segments().get(i).from().equals(bright.segments().get(i).from()), "brightness never moves geometry");
                check(dim.segments().get(i).opacity() <= bright.segments().get(i).opacity(), "intensity is monotonic");
            }
        }
        var motion = new SkyLinesMath.Motion();
        check(motion.sample(1_000_000_000L, false, 1) == 0, "first sample initializes without jump");
        close(motion.sample(1_100_000_000L, false, 1), .1, "normal elapsed motion");
        close(motion.sample(1_200_000_000L, true, 1), .1, "pause freezes entry");
        close(motion.sample(50_000_000_000L, true, 1), .1, "long pause does not advance");
        close(motion.sample(50_100_000_000L, false, 1), .1, "resume excludes pause boundary");
        close(motion.sample(50_200_000_000L, false, 2), .3, "speed integrates without phase jump");
        close(motion.sample(50_300_000_000L, false, 0), .3, "zero speed holds static sky");
        close(motion.sample(50_200_000_000L, false, 1), .3, "backward timestamp never reverses");
        close(motion.sample(90_000_000_000L, false, 1), .55, "long hidden gap is capped");
        motion.reset();
        close(motion.sample(90_100_000_000L, false, 2), 0, "world or toggle reset clears motion");
        check(SkyLinesMath.sample(SkyLinesMath.Pattern.ORBITAL_RIBBONS, 0, 1).stars().getFirst().opacity() == 0,
            "travelling highlight fades at recycle boundary");
        rails();
        System.out.println("SkyLinesCheck PASS: " + checks + " behavioral assertions; geometry, disable, pause, speed and world reset");
    }
    private record Vertex(double x, double y, double z, int color) { }
    private static void rails() {
        var start = SkyLinesMath.sample(SkyLinesMath.Pattern.LIGHT_RAILS, 12, .7);
        var next = SkyLinesMath.sample(SkyLinesMath.Pattern.LIGHT_RAILS, 12.0 + 1.0 / 60, .7);
        var later = SkyLinesMath.sample(SkyLinesMath.Pattern.LIGHT_RAILS, 13, .7);
        var loopA = SkyLinesMath.sample(SkyLinesMath.Pattern.LIGHT_RAILS, 99.999999, .7);
        var loopB = SkyLinesMath.sample(SkyLinesMath.Pattern.LIGHT_RAILS, 100.000001, .7);
        int changes = 0;
        var vertices = new ArrayList<Vertex>();
        SkyLinesMesh.emit(start, (x,y,z,color) -> vertices.add(new Vertex(x,y,z,color)));
        check(vertices.size() == 4608 && vertices.size() <= SkyLinesMesh.MAX_VERTICES, "rails fixed 4608 vertex budget");
        for (int i = 0; i < start.segments().size(); i++) {
            var a = start.segments().get(i); var b = next.segments().get(i); var c = later.segments().get(i);
            check(a.from().equals(c.from()) && a.to().equals(c.to()), "world angular path never drifts with animation");
            check(a.width() == c.width() && a.width() >= .5, "broad stable rail width");
            check(Math.abs(a.opacity() - b.opacity()) < .04, "per-frame pulse is smooth");
            check(Math.abs(loopA.segments().get(i).opacity() - loopB.segments().get(i).opacity()) < 1e-5, "bounded time wrap is seamless");
            if (Math.abs(a.opacity() - c.opacity()) > .06) changes++;
            if (i % 48 == 47) continue;
            for (int lane = 0; lane < 6; lane++) {
                check(vertices.get(i * 24 + lane * 4 + 1).equals(vertices.get((i+1) * 24 + lane * 4)), "shared low edge and alpha at join");
                check(vertices.get(i * 24 + lane * 4 + 2).equals(vertices.get((i+1) * 24 + lane * 4 + 3)), "shared high edge and alpha at join");
            }
        }
        check(changes > 40, "bright pulses visibly travel rather than globally fading");
        for (var v : vertices) check(Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()), "finite actual emitted vertex");
        for (int i = 0; i < vertices.size(); i += 24) {
            check((vertices.get(i).color() >>> 24) == 0 && (vertices.get(i+23).color() >>> 24) == 0, "outer edges fade to transparent");
        }
        for (var pattern : SkyLinesMath.Pattern.values()) {
            int[] count = {0}; SkyLinesMesh.emit(SkyLinesMath.sample(pattern, 0, 1), (x,y,z,color) -> count[0]++);
            check(count[0] <= SkyLinesMesh.MAX_VERTICES, "every pattern bounded mesh");
            count[0]=0;SkyLinesMesh.emit(SkyLinesMath.sample(pattern, 0, 0), (x,y,z,color) -> count[0]++);
            check(count[0]==0, "disabled pattern emits no vertices");
        }
    }
    private static void point(SkyLinesMath.Point p) {
        check(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()), "finite celestial point");
        close(Math.sqrt(p.x()*p.x() + p.y()*p.y() + p.z()*p.z()), SkyLinesMath.RADIUS, "constant sky sphere radius");
        check(p.y() > SkyLinesMath.RADIUS * .32, "all detail remains above horizon");
    }
    private static double distance(SkyLinesMath.Point a, SkyLinesMath.Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
    }
    private static void close(double value, double expected, String message) { check(Math.abs(value-expected) < 1.0e-8, message); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
