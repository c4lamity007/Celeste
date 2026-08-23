package dev.c4lam.celeste.gui;

import static dev.c4lam.celeste.gui.CelesteScreenTypes.*;

import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

final class CelesteScreenPages {
    private CelesteScreenPages() {
    }

    static void drawUtilityPage(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float animation) {
        switch (s.selectedPage) {
            case FRIENDS -> drawFriendsPage(s, matrices, animation);
            case CONFIGS -> drawConfigsPage(s, matrices, mouseX, mouseY, animation);
            case HUD -> drawPlaceholder(s, matrices, "H", "HUD editor", "Arrange interface widgets on screen.", "Open editor", animation);
            case SETTINGS -> drawSettingsPage(s, matrices, mouseX, mouseY, animation);
            default -> {
            }
        }
    }
}
