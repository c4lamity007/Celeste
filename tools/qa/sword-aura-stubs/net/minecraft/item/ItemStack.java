package net.minecraft.item;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.component.ComponentType;
public final class ItemStack {
    private final Map<ComponentType<?>, Object> components = new HashMap<>();
    @SuppressWarnings("unchecked")
    public <T> T get(ComponentType<T> type) { return (T) components.get(type); }
    public <T> void set(ComponentType<T> type, T value) {
        if (value == null) components.remove(type); else components.put(type, value);
    }
}
