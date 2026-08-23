package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.render.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;

/** Direction arrows for nearby players. Always drawn, including on-screen. */
public final class ArrowEspFeature implements ModuleFeature {
    private static final int MAX_ARROWS = 64;
    private static final int MAX_PLAYER_VISITS = 256;
    private static final int DEFAULT_COLOR = 0xFF4FA3;
    private volatile boolean enabled;
    private volatile double range = 96.0D;
    private volatile double opacity = 0.82D;
    private volatile int arrowStyle = 0;
    private volatile Identifier arrow2Texture = Identifier.of("celeste", "textures/arrows/arrow2.png");
    private volatile Identifier arrow3Texture = Identifier.of("celeste", "textures/arrows/arrow3.png");
    private volatile int colorRgb = DEFAULT_COLOR;

    @Override
    public String name() {
        return "Arrow ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
    }

    void render(MinecraftClient client, int width, int height) {
        ClientPlayerEntity self = client.player;
        if (!enabled || self == null || client.world == null || client.currentScreen != null) {
            return;
        }

        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        float cameraYaw = client.gameRenderer.getCamera().getYaw();
        double maxRange = range;
        double rangeSquared = maxRange * maxRange;
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        int count = 0;
        int visits = 0;

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (count >= MAX_ARROWS || visits++ >= MAX_PLAYER_VISITS) {
                break;
            }
            if (player == self || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || player.isCreative() || FriendManager.getInstance().isFriend(player)) {
                continue;
            }
            double distanceSquared = self.squaredDistanceTo(player);
            if (distanceSquared > rangeSquared || distanceSquared < 1.0E-5D) {
                continue;
            }

            double dx = player.getX() - camera.x;
            double dz = player.getZ() - camera.z;
            if (dx * dx + dz * dz < 1.0E-6D) {
                continue;
            }
            float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F - cameraYaw);
            float distanceFactor = (float) MathHelper.clamp(Math.sqrt(distanceSquared) / maxRange, 0.0D, 1.0D);
            float alphaFactor = (float) (opacity * (1.0D - distanceFactor * 0.55D));
            int alpha = MathHelper.clamp(Math.round(alphaFactor * 255.0F), 24, 255);
            Color color = new Color(colorRgb >> 16 & 0xFF, colorRgb >> 8 & 0xFF, colorRgb & 0xFF, alpha);
            float offset = 40.0F + 16.0F * distanceFactor;
            float size = 30.0F - 6.0F * distanceFactor;
            Identifier texture = arrowStyle == 1 ? arrow3Texture : arrow2Texture;

            matrices.push();
            matrices.translate(centerX, centerY, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(yaw));
            matrices.translate(0.0F, -offset, 0.0F);
            RenderUtil.TEXTURE_RECT.draw(
                matrices,
                -size / 2.0F,
                -size / 2.0F,
                size,
                size,
                0,
                color,
                0,
                0,
                1,
                1,
                texture
            );
            matrices.pop();
            count++;
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 96.0D), 16.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.82D), 0.1D, 1.0D);
        arrowStyle = "Arrow 3".equals(context.mode("Arrow Style", "Arrow 2")) ? 1 : 0;
        colorRgb = context.colorPick("Color", DEFAULT_COLOR) & 0xFFFFFF;
    }
}
