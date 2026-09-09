package dev.c4lam.celeste.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Verifies per-payload quiet mode without touching the game's logging backend. */
public final class RuntimeLoggingCheck {
    private RuntimeLoggingCheck() { }
    public static void run() {
        Logger existing = RuntimeLogging.getLogger("Celeste/QuietFixture");
        Logger ordinary = LoggerFactory.getLogger("Celeste/QuietFixture");
        boolean ordinaryInfo = ordinary.isInfoEnabled();
        if (existing.isInfoEnabled() != ordinaryInfo) throw new AssertionError("startup logging behavior changed");
        RuntimeLogging.quietBeta();
        Logger later = RuntimeLogging.getLogger(RuntimeLoggingCheck.class);
        for (Logger logger : new Logger[] {existing, later}) {
            if (logger.isTraceEnabled() || logger.isDebugEnabled() || logger.isInfoEnabled()
                    || logger.isWarnEnabled() || logger.isErrorEnabled()) throw new AssertionError("beta logging remained enabled");
            logger.info("quiet fixture"); logger.warn("quiet {}", "fixture");
            logger.error("quiet fixture", new IllegalStateException("expected fixture"));
            logger.atError().setCause(new IllegalStateException("fluent fixture")).log("quiet fluent fixture");
            if (!logger.equals(logger) || logger.equals(ordinary)) throw new AssertionError("logger identity changed");
        }
        if (ordinary.isInfoEnabled() != ordinaryInfo) throw new AssertionError("game logging configuration was changed");
        if (!existing.getName().equals("Celeste/QuietFixture")) throw new AssertionError("logger identity unavailable");
        System.out.println("PASS scoped beta logging: startup delegation, existing/new loggers, fluent API, unchanged backend");
    }
}
