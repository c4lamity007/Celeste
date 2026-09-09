package net.minecraft.entity.damage;
import java.util.HashSet;
import java.util.Set;
public final class DamageSource {
    public final Set<String> tags = new HashSet<>();
    public boolean isIn(String tag) { return tags.contains(tag); }
}
