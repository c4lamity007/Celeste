package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.combat.AuraServerState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes server facts after vanilla handles each packet; never cancels or edits one. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class AuraServerStateMixin {
    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void celeste$auraBlock(BlockUpdateS2CPacket packet, CallbackInfo callback) {
        AuraServerState.observeBlock(MinecraftClient.getInstance().world, packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void celeste$auraBlocks(ChunkDeltaUpdateS2CPacket packet, CallbackInfo callback) {
        packet.visitUpdates((pos, state) -> AuraServerState.observeBlock(MinecraftClient.getInstance().world, pos, state));
    }

    @Inject(method = "onEntitySpawn", at = @At("TAIL"))
    private void celeste$auraSpawn(EntitySpawnS2CPacket packet, CallbackInfo callback) {
        AuraServerState.observeEntity(MinecraftClient.getInstance().world, packet.getEntityId(), true);
    }

    @Inject(method = "onEntitiesDestroy", at = @At("TAIL"))
    private void celeste$auraDestroy(EntitiesDestroyS2CPacket packet, CallbackInfo callback) {
        for (int id : packet.getEntityIds()) AuraServerState.observeEntity(MinecraftClient.getInstance().world, id, false);
    }
}
