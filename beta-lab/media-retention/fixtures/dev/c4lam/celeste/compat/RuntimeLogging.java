package dev.c4lam.celeste.compat;
public final class RuntimeLogging {
    public static org.slf4j.Logger getLogger(String name) { return org.slf4j.helpers.NOPLogger.NOP_LOGGER; }
}
