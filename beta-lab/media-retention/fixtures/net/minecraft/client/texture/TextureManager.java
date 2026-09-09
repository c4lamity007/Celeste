package net.minecraft.client.texture;
import net.minecraft.util.Identifier;
public final class TextureManager {
    public static int destroyed, registered;
    public void destroyTexture(Identifier id) { destroyed++; System.out.println("MEDIA_ARTWORK_CLEANUP"); }
    public void registerTexture(Identifier id, AbstractTexture texture) { registered++; }
}
