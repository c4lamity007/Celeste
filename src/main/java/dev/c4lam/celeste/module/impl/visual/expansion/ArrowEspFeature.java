package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.media.MediaArtwork;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.GuiTextures;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Identifier;

import java.awt.Color;

/** Restrained off-screen direction arrows based on the supplied Silk module. */
public final class ArrowEspFeature implements ModuleFeature {
    private static final int MAX_ARROWS = 64;
    private static final int MAX_PLAYER_VISITS = 256;
    private volatile boolean enabled;
    private volatile double range = 96.0D;
    private volatile double opacity = 0.82D;
    private volatile int arrowStyle = 0; // 0 = default, 1 = arrow2.png, 2 = arrow3.png
    private volatile Identifier arrow2Texture = Identifier.of("celeste", "textures/arrows/arrow2.png");
    private volatile Identifier arrow3Texture = Identifier.of("celeste", "textures/arrows/arrow3.png");
    private volatile int colorRed = 255;
    private volatile int colorGreen = 79;
    private volatile int colorBlue = 163;

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
            if (count >= MAX_ARROWS || visits++ >= MAX_PLAYER_VISITS) break;
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
            if (dx * dx + dz * dz < 1.0E-6D) continue;
            float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F - cameraYaw);
            float distanceFactor = (float) MathHelper.clamp(Math.sqrt(distanceSquared) / maxRange, 0.0D, 1.0D);
            float alphaFactor = (float) (opacity * (1.0D - distanceFactor * 0.55D));
            int alpha = MathHelper.clamp(Math.round(alphaFactor * 255.0F), 24, 255);
            Color color = new Color(colorRed, colorGreen, colorBlue, alpha);
            float offset = 40.0F + 16.0F * distanceFactor;

            matrices.push();
            matrices.translate(centerX, centerY, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(yaw));
            matrices.translate(0.0F, -offset, 0.0F);
            
            if (arrowStyle == 0) {
                // Default rendered arrow
                RenderUtil.RECT.draw(matrices, -1.0F, -4.5F, 2.0F, 7.0F, 1.0F, color);
                matrices.push();
                matrices.translate(-2.4F, -3.1F, 0.0F);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-42.0F));
                RenderUtil.RECT.draw(matrices, -0.8F, -3.3F, 1.6F, 5.0F, 0.8F, color);
                matrices.pop();
                matrices.push();
                matrices.translate(2.4F, -3.1F, 0.0F);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(42.0F));
                RenderUtil.RECT.draw(matrices, -0.8F, -3.3F, 1.6F, 5.0F, 0.8F, color);
                matrices.pop();
            } else {
                // Texture-based arrow
                Identifier texture = (arrowStyle == 1) ? arrow2Texture : arrow3Texture;
                // These are normalized UV extents, not source-pixel sizes.
                // arrow2/arrow3 are 500x500 PNGs; passing 2x7 here sampled
                // outside their texture and made both styles effectively vanish.
                float textureWidth = 1.0F;
                float textureHeight = 1.0F;
                RenderUtil.TEXTURE_RECT.draw(matrices, 
                    -textureWidth/2.0F, -textureHeight/2.0F,  // top-left
                    textureWidth, textureHeight,              // width, height
                    0,                                        // radius (no rounding)
                    color,
                    0, 0,                                     // u, v (starting texture coordinates)
                    textureWidth, textureHeight,              // texture width, height
                    texture);
            }
            
            matrices.pop();
            count++;
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 96.0D), 16.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.82D), 0.1D, 1.0D);
        String modeValue = context.mode("Arrow Style", "Default");
        arrowStyle = switch (modeValue) {
            case "Default" -> 0;
            case "Arrow 2" -> 1;
            case "Arrow 3" -> 2;
            default -> 0; // fallback to default
        };
        
        // Update color from GUI settings
        GuiSetting.Slider redSlider = (GuiSetting.Slider) context.module().settings().stream()
                .filter(s -> s instanceof GuiSetting.Slider && s.label().equalsIgnoreCase("Red"))
                .findFirst()
                .orElse(null);
        if (redSlider != null) {
            colorRed = (int) redSlider.value();
        }
        
        GuiSetting.Slider greenSlider = (GuiSetting.Slider) context.module().settings().stream()
                .filter(s -> s instanceof GuiSetting.Slider && s.label().equalsIgnoreCase("Green"))
                .findFirst()
                .orElse(null);
        if (greenSlider != null) {
            colorGreen = (int) greenSlider.value();
        }
        
        GuiSetting.Slider blueSlider = (GuiSetting.Slider) context.module().settings().stream()
                .filter(s -> s instanceof GuiSetting.Slider && s.label().equalsIgnoreCase("Blue"))
                .findFirst()
                .orElse(null);
        if (blueSlider != null) {
            colorBlue = (int) blueSlider.value();
        }
    }
}
