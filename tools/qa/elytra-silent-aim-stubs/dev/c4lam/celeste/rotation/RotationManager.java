package dev.c4lam.celeste.rotation;

/** Records the adapter boundary; does not substitute for the production manager tests. */
public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();
    public boolean active = true, accepted = true;
    public int submits, renderCalls;
    public float lastRenderDelta = -1;
    public RotationTask lastTask;
    public Rotation display = new Rotation(90, -30), packet = new Rotation(-45, 22);
    public static RotationManager getInstance() { return INSTANCE; }
    public boolean submit(RotationTask task) { submits++; lastTask = task; return accepted; }
    public Rotation renderRotation(float delta) { renderCalls++; lastRenderDelta = delta; return display; }
    public boolean isActive() { return active; }
}
