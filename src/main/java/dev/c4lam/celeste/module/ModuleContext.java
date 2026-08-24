package dev.c4lam.celeste.module;

import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import net.minecraft.client.MinecraftClient;

/** Typed access to a module's UI settings without duplicating their state. */
public record ModuleContext(MinecraftClient client, GuiModule module) {
    public boolean toggle(String label, boolean fallback) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.Toggle toggle ? toggle.value() : fallback;
    }

    public double slider(String label, double fallback) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.Slider slider ? slider.value() : fallback;
    }

    public double rangeLow(String label, double fallback) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.Range range ? range.low() : fallback;
    }

    public double rangeHigh(String label, double fallback) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.Range range ? range.high() : fallback;
    }

    public String mode(String label, String fallback) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.Mode mode ? mode.value() : fallback;
    }

    public int keybind(String label) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.Keybind bind ? bind.keyCode() : -1;
    }

    public int colorPick(String label, int fallbackRgb) {
        GuiSetting setting = setting(label);
        return setting instanceof GuiSetting.ColorPick pick ? pick.rgb() : fallbackRgb;
    }

    public GuiSetting setting(String label) {
        for (GuiSetting setting : module.settings()) {
            if (setting.label().equalsIgnoreCase(label)) {
                return setting;
            }
        }
        return null;
    }
}
