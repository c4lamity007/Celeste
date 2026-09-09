package dev.c4lam.celeste.action;

import net.minecraft.client.MinecraftClient;

/** External input boundary only; arbitration and cleanup run in the production manager. */
public final class VanillaInputGuard {
    public static boolean conflict;
    public static boolean hasInteractionConflict(MinecraftClient client) { return conflict; }
}
