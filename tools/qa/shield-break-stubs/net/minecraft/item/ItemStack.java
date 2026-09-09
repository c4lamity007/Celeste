package net.minecraft.item;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.tag.ItemTags;
public final class ItemStack {
    public boolean axe;
    private final Map<ComponentType<?>, Object> components = new HashMap<>();
    public <T> void set(ComponentType<T> type, T value) { components.put(type, value); }
    @SuppressWarnings("unchecked")
    public <T> T get(ComponentType<T> type) { return (T) components.get(type); }
    public boolean isIn(Object tag) { return axe && tag == ItemTags.AXES; }
}
