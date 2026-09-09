package net.minecraft.client.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;

public final class ClientWorld {
    public final List<Entity> entities = new ArrayList<>();
    public Iterable<Entity> getEntities() { return entities; }
}
