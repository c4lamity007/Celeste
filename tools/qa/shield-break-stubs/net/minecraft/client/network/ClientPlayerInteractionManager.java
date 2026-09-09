package net.minecraft.client.network;
import net.minecraft.entity.LivingEntity;
public final class ClientPlayerInteractionManager {
    @FunctionalInterface public interface Attack { void run(ClientPlayerEntity player, LivingEntity target); }
    public Attack attack = (player, target) -> {};
    public void attackEntity(ClientPlayerEntity player, LivingEntity target) { attack.run(player, target); }
}
