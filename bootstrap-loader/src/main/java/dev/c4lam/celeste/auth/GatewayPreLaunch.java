package dev.c4lam.celeste.auth;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class GatewayPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        GatewayRuntime.preLaunch();
    }
}
