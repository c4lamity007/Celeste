package dev.c4lam.celeste;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.CelesteScreen;
import dev.c4lam.celeste.hud.HudEditorScreen;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.media.SystemMediaService;
import dev.c4lam.celeste.module.BuiltInFeatures;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.render.GuiPipelines;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class CelesteClient implements ClientModInitializer {
    public static final String MOD_ID = "celeste";
    public static final String NAME = "Celeste Client";
    public static final String VERSION = "1.0";

    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    @Override
    public void onInitializeClient() {
        GuiPipelines.register();
        InterfaceConfig.initialize();
        FriendManager.getInstance().initialize();
        HudManager.getInstance().initialize();
        SystemMediaService.getInstance().initialize();
        GameplayFoundation.initialize();
        ModuleManager modules = ModuleManager.getInstance();
        BuiltInFeatures.register(modules);
        modules.initialize();
        HudManager.getInstance().synchronizeModuleBackedElements();

        KeyBinding openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.celeste.open_gui",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGui.wasPressed()) {
                if (client.currentScreen instanceof CelesteScreen || client.currentScreen instanceof HudEditorScreen) {
                    client.setScreen(null);
                } else if (client.currentScreen == null) {
                    client.setScreen(new CelesteScreen());
                }
            }

        });

        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
            Identifier.of(MOD_ID, "fonts"),
            (SynchronousResourceReloader) manager -> Fonts.reload()
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ModuleManager.getInstance().close();
            InterfaceConfig.close();
            GameplayFoundation.close(client);
            FriendManager.getInstance().close();
            SystemMediaService.getInstance().close();
            RenderUtil.cleanup();
        });
    }
}
