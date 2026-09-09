package dev.c4lam.celeste.rotation;
public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();
    public Object owner;
    public static RotationManager getInstance() { return INSTANCE; }
    public boolean isOwnedBy(Object candidate) { return candidate != null && owner == candidate; }
}
