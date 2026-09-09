package net.minecraft.client.render.entity.state;
public final class PlayerEntityRenderState extends LivingEntityRenderState {
    public int id = 7;
    public boolean isGliding = true, applyFlyingRotation = false, usingRiptide = false;
    public float flyingRotation = .314F, glidingTicks = 12.5F;
    public float leaningPitch = .2F, leftWingPitch = .4F, leftWingYaw = .5F, leftWingRoll = .6F;
}
