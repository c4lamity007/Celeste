package dev.c4lam.celeste.silent;
public final class SilentSlotManager {
    private static final SilentSlotManager INSTANCE = new SilentSlotManager();
    public Object owner;
    public boolean active;
    public static SilentSlotManager getInstance() { return INSTANCE; }
    public boolean isActive() { return active; }
    public boolean isOwnedBy(Object candidate) { return candidate != null && owner == candidate; }
}
