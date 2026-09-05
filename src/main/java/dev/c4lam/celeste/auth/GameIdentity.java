package dev.c4lam.celeste.auth;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;

/** Audit-only public profile data. Authentication tokens are never accessed. */
record GameIdentity(String uuid, String username) {
    static GameIdentity collect() {
        GameProfile profile = MinecraftClient.getInstance().getGameProfile();
        if (profile == null || profile.id() == null || profile.name() == null || profile.name().isBlank()) {
            throw new IllegalStateException("Minecraft profile identity is unavailable");
        }
        return new GameIdentity(profile.id().toString(), profile.name());
    }
}
