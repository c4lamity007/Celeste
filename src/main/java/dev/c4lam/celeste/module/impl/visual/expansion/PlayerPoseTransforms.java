package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.equipment.EquipmentModelLoader;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * An owned player model and vanilla pose adapter used only for geometry capture.
 * Recreate both slim/wide instances when the client's LoadedEntityModels identity
 * changes after a resource reload. Use only on the client render thread.
 */
public final class PlayerPoseTransforms extends PlayerEntityRenderer<AbstractClientPlayerEntity> {
    private PlayerPoseTransforms(EntityRendererFactory.Context context, boolean slim) {
        super(context, slim);
        // Only getModel().render(...) is used; release the unused feature models.
        features.clear();
    }

    public static PlayerPoseTransforms create(MinecraftClient client, boolean slim) {
        // The vanilla equipment loader has no public getter. This private empty
        // loader only satisfies feature construction; equipment is never drawn.
        EntityRendererFactory.Context context = new EntityRendererFactory.Context(
                client.getEntityRenderDispatcher(),
                client.getItemModelManager(),
                client.getMapRenderer(),
                client.getBlockRenderManager(),
                client.getResourceManager(),
                client.getLoadedEntityModels(),
                new EquipmentModelLoader(),
                client.getAtlasManager(),
                client.textRenderer,
                client.getPlayerSkinCache());
        return new PlayerPoseTransforms(context, slim);
    }

    /**
     * Applies the vanilla body transform before getModel().render(...).
     * The stack must start at the unoffset player origin (state.x/y/z), or at
     * identity for a mesh relative to that origin. This method applies the
     * renderer position offset itself and does not push/pop the stack.
     *
     * First call updateRenderState(player, an owned state, tickProgress), then
     * getModel().setAngles(state). Never call the inherited entity render method.
     */
    public void applyTransforms(PlayerEntityRenderState state, MatrixStack matrices) {
        Vec3d offset = getPositionOffset(state);
        matrices.translate(offset.x, offset.y, offset.z);

        // Same order as LivingEntityRenderer.render in Minecraft 1.21.11.
        if (state.isInPose(EntityPose.SLEEPING)) {
            Direction direction = state.sleepingDirection;
            if (direction != null) {
                float eyeOffset = state.standingEyeHeight - 0.1F;
                matrices.translate(-direction.getOffsetX() * eyeOffset, 0.0F,
                        -direction.getOffsetZ() * eyeOffset);
            }
        }

        float baseScale = state.baseScale;
        matrices.scale(baseScale, baseScale, baseScale);
        // The inherited implementation also handles swimming, gliding, riptide,
        // mounted body yaw, frozen shaking, death and upside-down players.
        setupTransforms(state, matrices, state.bodyYaw, baseScale);
        matrices.scale(-1.0F, -1.0F, 1.0F);
        scale(state, matrices);
        matrices.translate(0.0F, -1.501F, 0.0F);
    }
}
