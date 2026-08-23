package dev.c4lam.celeste.action;

import net.minecraft.client.MinecraftClient;

@FunctionalInterface
public interface PrimaryAction {
    void execute(MinecraftClient client);
}
