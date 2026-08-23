package dev.c4lam.celeste.render.font;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads a Celeste MSDF JSON and its matching PNG through Minecraft resources. */
public final class FontBuilder {
    private static final Gson GSON = new Gson();

    private String name;
    private Identifier dataIdentifier;
    private Identifier atlasIdentifier;

    public FontBuilder find(String fontName) {
        name = fontName;
        dataIdentifier = Identifier.of("celeste", "fonts/" + fontName + ".json");
        atlasIdentifier = Identifier.of("celeste", "fonts/" + fontName + ".png");
        return this;
    }

    public Font load() {
        if (dataIdentifier == null || atlasIdentifier == null) {
            throw new IllegalStateException("Call find() before loading an MSDF font");
        }

        MinecraftClient client = MinecraftClient.getInstance();
        FontData data;
        Resource resource = client.getResourceManager().getResource(dataIdentifier).orElseThrow(
                () -> new IllegalStateException("Missing Celeste font metadata: " + dataIdentifier)
        );

        try (Reader reader = resource.getReader()) {
            data = GSON.fromJson(reader, FontData.class);
        } catch (IOException | JsonParseException exception) {
            throw new IllegalStateException("Could not load Celeste font metadata: " + dataIdentifier, exception);
        }

        validate(data);
        AbstractTexture texture = client.getTextureManager().getTexture(atlasIdentifier);
        float atlasWidth = data.atlas().width();
        float atlasHeight = data.atlas().height();

        Map<Integer, MsdfGlyph> glyphs = new HashMap<>();
        for (FontData.GlyphData glyph : data.glyphs()) {
            MsdfGlyph parsed = new MsdfGlyph(glyph, atlasWidth, atlasHeight);
            glyphs.put(parsed.codePoint(), parsed);
        }

        Map<Integer, Map<Integer, Float>> kernings = new HashMap<>();
        List<FontData.KerningData> kerningData = data.kernings();
        if (kerningData != null) {
            for (FontData.KerningData kerning : kerningData) {
                kernings.computeIfAbsent(kerning.leftChar(), ignored -> new HashMap<>())
                        .put(kerning.rightChar(), kerning.advance());
            }
        }

        return new Font(name, texture, data.atlas(), data.metrics(), glyphs, kernings);
    }

    private void validate(FontData data) {
        if (data == null || data.atlas() == null || data.metrics() == null || data.glyphs() == null) {
            throw new IllegalStateException("Incomplete Celeste font metadata: " + dataIdentifier);
        }
        if (data.atlas().width() <= 0.0f || data.atlas().height() <= 0.0f) {
            throw new IllegalStateException("Invalid Celeste font atlas dimensions: " + dataIdentifier);
        }
    }
}
