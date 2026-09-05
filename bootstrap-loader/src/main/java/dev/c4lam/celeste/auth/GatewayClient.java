package dev.c4lam.celeste.auth;

import net.fabricmc.api.ClientModInitializer;

public final class GatewayClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (GatewayRuntime.payloadReady()) {
            GatewayRuntime.launchPayload();
        }
    }
}
