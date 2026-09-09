package net.minecraft.component;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.component.type.WeaponComponent;
public final class DataComponentTypes {
    public static final ComponentType<WeaponComponent> WEAPON = new ComponentType<>();
    public static final ComponentType<BlocksAttacksComponent> BLOCKS_ATTACKS = new ComponentType<>();
    private DataComponentTypes() {}
}
