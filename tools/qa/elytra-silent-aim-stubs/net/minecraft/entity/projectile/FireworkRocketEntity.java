package net.minecraft.entity.projectile;

import net.minecraft.entity.Entity;

/** Owner/liveness boundary; attachment is fixture metadata, not a condition used by the guard. */
public final class FireworkRocketEntity extends Entity {
    private final Entity owner;
    public final boolean attachedToShooter;
    public FireworkRocketEntity(Entity owner, boolean attachedToShooter) {
        this.owner = owner;
        this.attachedToShooter = attachedToShooter;
    }
    public Entity getOwner() { return owner; }
}
