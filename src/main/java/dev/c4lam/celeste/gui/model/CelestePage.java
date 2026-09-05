package dev.c4lam.celeste.gui.model;

import net.minecraft.util.Identifier;

public enum CelestePage {
    SWORD("Sword", "Vanilla sword combat", "celeste:textures/gui/icons/sword.png", "S", true),
    MACE("Mace", "Mace and wind-charge combat", "celeste:textures/gui/icons/mace.png", "M", true),
    CRYSTAL("Crystal", "Crystal and anchor combat", "celeste:textures/gui/icons/crystal.png", "C", true),
    MOVEMENT("Movement", "Movement and traversal", "celeste:textures/gui/icons/movement.png", ">", true),
    VISUALS("Visuals", "World and interface rendering", "celeste:textures/gui/icons/visuals.png", "*", true),

    FRIENDS("Friends", "Trusted players and aliases", "celeste:textures/gui/icons/friends.png", "+", false),
    CONFIGS("Configs", "Profiles and presets", "celeste:textures/gui/icons/configs.png", "F", false),
    HUD("HUD", "Heads-up display layout", "celeste:textures/gui/icons/hud.png", "H", false),
    SETTINGS("Settings", "Client and interface options", "celeste:textures/gui/icons/settings.png", "G", false);

    private final String label;
    private final String description;
    private final String iconIdentifier;
    private final String fallbackGlyph;
    private final boolean feature;

    CelestePage(String label, String description, String iconIdentifier, String fallbackGlyph, boolean feature) {
        this.label = label;
        this.description = description;
        this.iconIdentifier = iconIdentifier;
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
        // Keep the complete resource identifier as one literal. The release
        // obfuscator can then relocate the asset and rewrite this lookup as a
        // single mapping instead of losing the dynamically concatenated path.
        return iconIdentifier == null ? null : Identifier.of(iconIdentifier);
    }

    public String fallbackGlyph() {
        return fallbackGlyph;
    }

    public boolean feature() {
        return feature;
    }
}
