package dev.c4lam.celeste.hud;

/** The small, borderless widgets available from Celeste's HUD editor. */
public enum HudElementType {
    COORDINATES("Coordinates", 116, 30),
    TARGET_HUD("Target HUD", 136, 44),
    EFFECTS("Effects", 116, 46),
    ARMOR("Armor", 148, 34),
    KEYBINDS("Keybinds", 122, 48),
    COOLDOWNS("Cooldowns", 122, 34),
    ARRAYLIST("Arraylist", 150, 180),
    WATERMARK("Watermark", 164, 30),
    TPS("TPS", 66, 27),
    FPS("FPS", 66, 27),
    SPOTIFY("Media HUD", 190, 82);

    private final String label;
    private final float width;
    private final float height;

    HudElementType(String label, float width, float height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }

    public String label() {
        return label;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }
}
