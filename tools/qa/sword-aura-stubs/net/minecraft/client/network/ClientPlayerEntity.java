package net.minecraft.client.network;
import java.util.function.BiFunction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.HitResult;
/** Query-authority adapter; the production helper chooses no distances or new collision algorithm. */
public final class ClientPlayerEntity extends PlayerEntity {
    public BiFunction<Float, Entity, HitResult> query;
    public int queries;
    private ItemStack stack = new ItemStack();
    public ItemStack getMainHandStack() { return stack; }
    public void setMainHandStack(ItemStack stack) { this.stack = stack; }
    public HitResult getCrosshairTarget(float tickDelta, Entity camera) {
        queries++;
        return query.apply(tickDelta, camera);
    }
}
