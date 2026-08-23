package dev.c4lam.celeste.hud;

/** Mutable placement state retained while the client is running. */
public final class HudElementState {
    private static final float MAX_FRAME_SECONDS = 0.05f;
    public static final float MIN_SCALE = 0.4f;
    public static final float MAX_SCALE = 3.0f;

    private final HudElementType type;
    private float x;
    private float y;
    private float displayX;
    private float displayY;
    private boolean enabled;
    private float visibility;
    private float hover;
    private float drag;
    private float selection;
    private boolean selected;
    private float scale = 1.0f;
    private long lastVisualUpdate;

    HudElementState(HudElementType type, float x, float y, boolean enabled, boolean selected) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.displayX = x;
        this.displayY = y;
        this.enabled = enabled;
        this.selected = selected;
        this.visibility = 0.0f;
    }

    public HudElementType type() {
        return type;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float displayX() {
        return displayX;
    }

    public float displayY() {
        return displayY;
    }

    public float width() {
        return type.width() * scale;
    }

    public float height() {
        return type.height() * scale;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean selected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public float scale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = Float.isFinite(scale)
            ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale))
            : 1.0f;
    }

    public void moveTo(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void moveImmediately(float x, float y) {
        this.x = x;
        this.y = y;
        this.displayX = x;
        this.displayY = y;
    }

    public void restartEntrance() {
        if (enabled) {
            visibility = 0.0f;
        }
        hover = 0.0f;
        drag = 0.0f;
        selection = 0.0f;
        lastVisualUpdate = 0L;
    }

    public void updateVisuals(boolean hovered, boolean dragging) {
        updateVisuals(hovered, dragging, true);
    }

    public void updateVisuals(boolean hovered, boolean dragging, boolean presentationVisible) {
        long now = System.nanoTime();
        float seconds = lastVisualUpdate == 0L
            ? 1.0f / 60.0f
            : Math.min(MAX_FRAME_SECONDS, Math.max(0.0f, (now - lastVisualUpdate) / 1_000_000_000.0f));
        lastVisualUpdate = now;

        boolean shouldShow = enabled && presentationVisible;
        visibility = damp(visibility, shouldShow ? 1.0f : 0.0f, shouldShow ? 15.0f : 19.0f, seconds);
        hover = damp(hover, hovered ? 1.0f : 0.0f, hovered ? 20.0f : 14.0f, seconds);
        drag = damp(drag, dragging ? 1.0f : 0.0f, dragging ? 23.0f : 16.0f, seconds);
        selection = damp(selection, selected && shouldShow ? 1.0f : 0.0f,
            selected ? 17.0f : 21.0f, seconds);

        float positionSpeed = dragging ? 34.0f : 20.0f;
        displayX = damp(displayX, x, positionSpeed, seconds);
        displayY = damp(displayY, y, positionSpeed, seconds);
        if (Math.abs(displayX - x) < 0.01f) {
            displayX = x;
        }
        if (Math.abs(displayY - y) < 0.01f) {
            displayY = y;
        }
    }

    public float visibility() {
        return easeOutCubic(visibility);
    }

    public float hoverProgress() {
        return hover;
    }

    public float dragProgress() {
        return drag;
    }

    public float selectionProgress() {
        return easeOutCubic(selection);
    }

    public boolean visuallyPresent() {
        return enabled || visibility > 0.01f;
    }

    public boolean visuallyPresent(boolean presentationVisible) {
        return (enabled && presentationVisible) || visibility > 0.01f;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= displayX && mouseX <= displayX + width()
            && mouseY >= displayY && mouseY <= displayY + height();
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
    }

    private static float easeOutCubic(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        float inverse = 1.0f - clamped;
        return 1.0f - inverse * inverse * inverse;
    }
}
