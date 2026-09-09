package net.minecraft.component.type;
import java.util.function.BiPredicate;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
/** Supplied range authority; tests never duplicate Minecraft's geometric implementation. */
public final class AttackRangeComponent {
    private final BiPredicate<PlayerEntity, Vec3d> authority;
    public AttackRangeComponent(BiPredicate<PlayerEntity, Vec3d> authority) { this.authority = authority; }
    public boolean isWithinRange(PlayerEntity self, Vec3d pos) { return authority.test(self, pos); }
}
