package dev.c4lam.celeste;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.CelesteScreen;
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
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

public final class CelesteClient implements ClientModInitializer {
    public static final String MOD_ID = "celeste";
    public static final String NAME = "Celeste Client";
    public static final String VERSION = "1.0";

    private boolean initialized;
    private boolean menuKeyDown;

    @Override
    public void onInitializeClient() {
        GuiPipelines.register();
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
            Identifier.of(MOD_ID, "fonts"),
            (SynchronousResourceReloader) manager -> Fonts.reload()
        );
        AuthBootstrap.install(this::initializeAuthorizedClient, this::shutdownAuthorizedClient);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdownAuthorizedClient());
    }

    private void initializeAuthorizedClient() {
        if (initialized) {
            return;
        }
        initialized = true;
        InterfaceConfig.initialize();
        FriendManager.getInstance().initialize();
        HudManager.getInstance().initialize();
        SystemMediaService.getInstance().initialize();
        GameplayFoundation.initialize();
        ModuleManager modules = ModuleManager.getInstance();
        BuiltInFeatures.register(modules);
        modules.initialize();
        ConfigProfileManager.getInstance().initialize();
        HudManager.getInstance().synchronizeModuleBackedElements();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!initialized) {
                return;
            }
            boolean pressed = InputUtil.isKeyPressed(client.getWindow(), InterfaceConfig.menuKey());
            if (pressed && !menuKeyDown && client.currentScreen == null) {
                client.setScreen(new CelesteScreen());
            }
            menuKeyDown = pressed;
        });

    }

    private void shutdownAuthorizedClient() {
        if (!initialized) {
            return;
        }
        initialized = false;
        menuKeyDown = false;
        ModuleManager.getInstance().close();
        InterfaceConfig.close();
        GameplayFoundation.close(net.minecraft.client.MinecraftClient.getInstance());
        FriendManager.getInstance().close();
        SystemMediaService.getInstance().close();
        RenderUtil.cleanup();
    }
}
