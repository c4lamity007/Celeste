package dev.c4lam.celeste.module.impl.combat;
import dev.c4lam.celeste.friend.FriendManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
/** Target eligibility is an explicit external authority result, not a copied target selector. */
final class Targeting {
    static boolean allow = true;
    static boolean validLivingTarget(MinecraftClient client, LivingEntity target, FriendManager friends,
                                     double range, boolean players) {
        return allow && target != null && target.isAlive() && !target.isRemoved();
    }
}
