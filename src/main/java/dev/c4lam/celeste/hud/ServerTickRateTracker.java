package dev.c4lam.celeste.hud;

import java.util.Locale;

/** Small main-thread estimator based on vanilla world-time updates. */
public final class ServerTickRateTracker {
    private static final ServerTickRateTracker INSTANCE = new ServerTickRateTracker();

    private Object world;
    private long lastServerTick = Long.MIN_VALUE;
    private long lastUpdateNanos;
    private long expectedTickDelta = 20L;
    private double smoothedTps = 20.0D;

    private ServerTickRateTracker() {
    }

    public static ServerTickRateTracker getInstance() {
        return INSTANCE;
    }

    public void onTimeUpdate(Object currentWorld, long serverTick) {
        long now = System.nanoTime();
        if (currentWorld == null || world != currentWorld || lastServerTick == Long.MIN_VALUE
            || serverTick <= lastServerTick || now <= lastUpdateNanos) {
            world = currentWorld;
            lastServerTick = serverTick;
            lastUpdateNanos = now;
            expectedTickDelta = 20L;
            smoothedTps = 20.0D;
            return;
        }

        long tickDelta = serverTick - lastServerTick;
        double elapsedSeconds = (now - lastUpdateNanos) / 1_000_000_000.0D;
        if (tickDelta <= 200L && elapsedSeconds > 0.05D && elapsedSeconds < 30.0D) {
            double sample = Math.min(20.0D, tickDelta / elapsedSeconds);
            smoothedTps += (sample - smoothedTps) * 0.35D;
            expectedTickDelta = tickDelta;
        }
        lastServerTick = serverTick;
        lastUpdateNanos = now;
    }

    public String formatted(Object currentWorld) {
        if (currentWorld == null || currentWorld != world || lastUpdateNanos == 0L) {
            return "20.0";
        }
        double ageSeconds = (System.nanoTime() - lastUpdateNanos) / 1_000_000_000.0D;
        double liveEstimate = ageSeconds <= 1.0D
            ? smoothedTps
            : Math.min(smoothedTps, expectedTickDelta / ageSeconds);
        return String.format(Locale.ROOT, "%.1f", Math.max(0.0D, Math.min(20.0D, liveEstimate)));
    }
}
