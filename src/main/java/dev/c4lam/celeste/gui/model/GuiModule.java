package dev.c4lam.celeste.gui.model;

import java.util.List;

public final class GuiModule {
    private final String name;
    private final String description;
    private final CelestePage page;
    private final List<GuiSetting> settings;
    private boolean enabled;

    public GuiModule(String name, String description, CelestePage page, boolean enabled, GuiSetting... settings) {
        if (!page.feature()) {
            throw new IllegalArgumentException("Modules can only belong to feature pages");
        }
        this.name = name;
        this.description = description;
        this.page = page;
        this.enabled = enabled;
        this.settings = List.of(settings);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public CelestePage page() {
        return page;
    }

    public List<GuiSetting> settings() {
        return settings;
    }

    public boolean enabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
