package dev.c4lam.celeste.gui.model;

import net.minecraft.util.Identifier;

public enum CelestePage {
    SWORD("Sword", "Vanilla sword combat", "sword.png", "S", true),
    MACE("Mace", "Mace and wind-charge combat", "mace.png", "M", true),
    CRYSTAL("Crystal", "Crystal and anchor combat", "crystal.png", "C", true),
    MOVEMENT("Movement", "Movement and traversal", "movement.png", ">", true),
    VISUALS("Visuals", "World and interface rendering", "visuals.png", "*", true),

    FRIENDS("Friends", "Trusted players and aliases", "friends.png", "+", false),
    CONFIGS("Configs", "Profiles and presets", "configs.png", "F", false),
    HUD("HUD", "Heads-up display layout", "hud.png", "H", false),
    SETTINGS("Settings", "Client and interface options", "settings.png", "G", false);

    private final String label;
    private final String description;
    private final String iconFile;
    private final String fallbackGlyph;
    private final boolean feature;

    CelestePage(String label, String description, String iconFile, String fallbackGlyph, boolean feature) {
        this.label = label;
        this.description = description;
        this.iconFile = iconFile;
        this.fallbackGlyph = fallbackGlyph;
        this.feature = feature;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public Identifier icon() {
        return iconFile == null ? null : Identifier.of("celeste", "textures/gui/icons/" + iconFile);
    }

    public String fallbackGlyph() {
        return fallbackGlyph;
    }

    public boolean feature() {
        return feature;
    }
}
