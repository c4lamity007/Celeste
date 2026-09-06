package dev.c4lam.celeste.module.impl.visual.expansion;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Runs the production pure helper directly; no Minecraft dependencies or substitute helper. */
public final class JumpCircleAnimationCheck {
    private static int assertions;
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
            message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void animation() {
        for (double maximum : new double[] {.6, .77, 1, 1.8, 2.5, 3}) {
            double previous = -1;
            for (int i = 0; i <= 10000; i++) {
                double age = i / 10000.0;
                double radius = JumpCircleAnimation.radius(age, maximum);
                require(Double.isFinite(radius) && radius >= maximum * .14 - 1e-12 && radius <= maximum + 1e-12,
                    "Radius must stay in [.14*maximum,maximum]");
                require(radius >= previous, "Radius must grow monotonically");
                previous = radius;
                double opacity = JumpCircleAnimation.opacity(age);
                require(Double.isFinite(opacity) && opacity >= 0 && opacity <= 1, "Opacity out of bounds");
            }
            near(JumpCircleAnimation.radius(-1, maximum), maximum * .14, 1e-12, "Pre-birth radius clamped");
            near(JumpCircleAnimation.radius(2, maximum), maximum, 1e-12, "Expired radius clamped");
        }
        near(JumpCircleAnimation.opacity(-1), 0, 0, "Before birth transparent");
        near(JumpCircleAnimation.opacity(0), 0, 0, "Birth transparent");
        near(JumpCircleAnimation.opacity(.075), 1, 1e-12, "Smooth entry reaches full opacity");
        near(JumpCircleAnimation.opacity(1), 0, 0, "Expiry transparent");
        near(JumpCircleAnimation.opacity(2), 0, 0, "After expiry transparent");
        double previous = 0;
        for (int i = 0; i <= 1000; i++) {
            double value = JumpCircleAnimation.opacity(i * .075 / 1000);
            require(value >= previous, "Entry opacity decreases"); previous = value;
        }
        previous = 1;
        for (int i = 0; i <= 1000; i++) {
            double value = JumpCircleAnimation.opacity(.36 + i * .64 / 1000);
            require(value <= previous + 1e-12, "Exit opacity increases"); previous = value;
        }
        System.out.println("PASS production animation math: 60,006 radius/opacity samples; monotonic bounded growth; smooth fade and transparent endpoints");
    }
    private static void clocks() {
        JumpCircleAnimation.Clock clock = new JumpCircleAnimation.Clock();
        near(clock.sample(5_000_000_000L, false), 0, 0, "First sample starts at zero");
        near(clock.sample(5_016_666_667L, false), .016666667, 1e-12, "Fractional frame advances");
        near(clock.sample(6_000_000_000L, false), 1, 1e-12, "One elapsed second");
        near(clock.sample(7_000_000_000L, true), 1, 1e-12, "Pause transition freezes");
        near(clock.sample(86_406_000_000_000L, true), 1, 1e-12, "Long pause does not age effects");
        near(clock.sample(86_407_000_000_000L, false), 1, 1e-12, "Resume excludes paused interval");
        near(clock.sample(86_407_016_666_667L, false), 1.016666667, 1e-12, "First subsequent frame resumes smoothly");
        clock.reset();
        near(clock.sample(100_000_000_000L, false), 0, 0, "Reset clears old elapsed time");
        for (int i = 1; i <= 10000; i++)
            near(clock.sample(100_000_000_000L + i * 1_000_000L, false), i / 1000.0, 2e-10, "Monotonic millisecond samples");
        clock.reset();
        near(clock.sample(0, true), 0, 0, "Starts paused at zero");
        near(clock.sample(100_000_000_000L, false), 0, 0, "Initial resume does not jump");
        near(clock.sample(101_000_000_000L, false), 1, 1e-12, "Runs normally after paused initialization");
        System.out.println("PASS production clock: fractional frames, 10,000 monotonic samples, 24-hour pause/resume without elapsed jump, reset and initially-paused cases");
    }
    private static void takeoff() {
        for (int bits = 0; bits < 16; bits++) {
            boolean wasGrounded = (bits & 1) != 0, grounded = (bits & 2) != 0;
            boolean jumping = (bits & 4) != 0, allowed = (bits & 8) != 0;
            require(JumpCircleAnimation.takeoff(wasGrounded, grounded, jumping, allowed, .42, .1764)
                == (wasGrounded && !grounded && jumping && allowed), "Takeoff eligibility truth table");
        }
        for (double dy : new double[] {-20, -1, -.1, 0, .025, 1, 20, Double.NaN, Double.POSITIVE_INFINITY})
            require(!JumpCircleAnimation.takeoff(true, false, true, true, dy, .2), "Falling/non-jump/vertical teleport must not trigger: " + dy);
        require(JumpCircleAnimation.takeoff(true, false, true, true, .025001, .2), "Just-above-rise threshold should trigger");
        require(JumpCircleAnimation.takeoff(true, false, true, true, .99999, 3.99999), "Valid subthreshold movement should trigger");
        for (double distance : new double[] {4, 16, Double.NaN, Double.POSITIVE_INFINITY})
            require(!JumpCircleAnimation.takeoff(true, false, true, true, .42, distance), "Teleport/nonfinite displacement must not trigger");
        for (String reason : new String[] {"dead", "spectator", "riding", "flying", "gliding", "climbing", "water", "lava"})
            require(!JumpCircleAnimation.takeoff(true, false, true, false, .42, .1764), "Not allowed: " + reason);
        System.out.println("PASS production takeoff: complete boolean eligibility table; downward/too-small/too-large rise rejected; >=2-block displacement and nonfinite input rejected");
    }
    private static int constant(String source, String name) {
        Matcher match = Pattern.compile("\\b" + name + "\\s*=\\s*(\\d+)\\s*;").matcher(source);
        if (!match.find()) throw new AssertionError("Missing production constant: " + name);
        return Integer.parseInt(match.group(1));
    }
    private static void caps(Path feature) throws Exception {
        String source = Files.readString(feature);
        int maxPulses = constant(source, "MAX_PULSES"), maxQuads = constant(source, "MAX_QUADS");
        int maxWavePulses = constant(source, "MAX_WAVE_PULSES");
        require(maxPulses == 6 && maxWavePulses == 2 && maxQuads == 300,
            "Expected six events, two 3D waves and 300 feature quads");
        require(source.contains("Math.min(768, output.size() + MAX_QUADS)"), "Production shared cap changed");
        require(source.contains("output.size() + 2 > limit"), "Production ring reservation changed");
        require(source.contains("framePulses.size() - 1") && source.contains("index--"), "Newest-first wave selection changed");
        require(source.contains("waterPulses++ < MAX_WAVE_PULSES") && source.contains("waveBudget -= used"),
            "Production wave selection/budget guard changed");
        require(source.contains("limit - output.size() - legacyReservation"), "Ground layers are not reserved before waves");
        require(!source.contains("droplets(") && !source.contains("Texture.DROPLET"), "Splash unexpectedly emits floating droplets");
        List<Integer> history = new ArrayList<>();
        for (int event = 0; event < 1000; event++) {
            while (history.size() >= maxPulses) history.removeFirst();
            history.add(event);
            require(history.size() <= 6 && history.getLast() == event, "Pulse FIFO cap/order");
        }
        int cases = 0;
        for (double age : new double[] {.08, .4, .76}) {
            int[] waveCounts = new int[WaterJumpMesh.MAX_WAVE_QUADS + 1];
            for (int budget = 0; budget < waveCounts.length; budget++)
                waveCounts[budget] = WaterJumpMesh.build(age, 1.8, .3, budget).size();
            for (int initial = 0; initial <= 800; initial++) for (int pulses = 0; pulses <= maxPulses; pulses++)
                for (int splashMask = 0; splashMask < (1 << pulses); splashMask++)
                    for (int visibleMask : new int[] {(1 << pulses) - 1, 0b101010}) {
                        int[] result = modeledFrame(initial, pulses, splashMask, visibleMask, maxQuads, maxWavePulses, waveCounts);
                        require(result[0] - initial <= maxQuads, "Modeled per-feature quad cap exceeded");
                        require(result[0] <= Math.max(768, initial), "Modeled shared quad cap exceeded");
                        require(result[1] <= maxWavePulses, "More than two wave events admitted");
                        if (splashMask == 0)
                            require(result[0] - initial <= pulses * 2, "Original Glow path gained extra quads");
                        cases++;
                    }
            if (age == .4) {
                int[] fullest = modeledFrame(0, 6, 0b111111, 0b111111, maxQuads, maxWavePulses, waveCounts);
                require(fullest[0] == 300 && fullest[1] == 2, "Full six-Splash frame must reach exactly 300 quads");
                require(fullest[2] == 0b110000, "Only the newest two visible Splash pulses may receive waves");
                int[] culled = modeledFrame(0, 6, 0b111111, 0b001111, maxQuads, maxWavePulses, waveCounts);
                require(culled[2] == 0b001100, "Culled newer pulses must not consume visible wave slots");
            }
        }
        System.out.println("PASS source-aligned cap model: " + cases + " mixed Glow/Splash, visibility and shared-budget cases; <=6 events, newest <=2 waves, <=300 added quads, <=768 shared total; no floating droplets and original two-quad Glow path preserved");
    }
    /** Conservative production extraction model: both ground layers are counted even when their fade skips one. */
    private static int[] modeledFrame(int initial, int pulses, int splashMask, int visibleMask,
                                     int maxQuads, int maxWavePulses, int[] waveCounts) {
        int size = initial, limit = Math.min(768, initial + maxQuads);
        int waveBudget = Math.max(0, limit - initial - pulses * 2), eligibleWaves = 0, addedWaves = 0, waveMask = 0;
        for (int pulse = pulses - 1; pulse >= 0; pulse--) {
            if (size + 2 > limit || (visibleMask & (1 << pulse)) == 0) continue;
            size += 2;
            if ((splashMask & (1 << pulse)) != 0 && eligibleWaves++ < maxWavePulses && waveBudget >= 48) {
                int budget = Math.min(waveCounts.length - 1, Math.min(waveBudget, limit - size));
                int used = waveCounts[budget];
                size += used; waveBudget -= used;
                if (used > 0) { addedWaves++; waveMask |= 1 << pulse; }
            }
        }
        return new int[] {size, addedWaves, waveMask};
    }
    public static void main(String[] args) throws Exception {
        animation(); clocks(); takeoff(); caps(Path.of(args[0]));
        System.out.println("PASS " + assertions + " deterministic assertions. Runtime eligibility predicates and feature lifecycle require separate source/in-game review.");
    }
}
