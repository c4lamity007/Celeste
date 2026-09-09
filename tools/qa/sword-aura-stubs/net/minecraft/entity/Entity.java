package net.minecraft.entity;
/** Value-only camera adapter; this is not a Minecraft engine implementation. */
public class Entity {
    private float yaw, pitch, headYaw;
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getHeadYaw() { return headYaw; }
    public void setYaw(float value) { yaw = value; }
    public void setPitch(float value) { pitch = value; }
    public void setHeadYaw(float value) { headYaw = value; }
}
