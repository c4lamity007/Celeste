package dev.c4lam.celeste.action;
public final class ActionScheduler {
    private static final ActionScheduler INSTANCE = new ActionScheduler();
    public long tick;
    public static ActionScheduler getInstance() { return INSTANCE; }
    public long tickNumber() { return tick; }
}
