package dev.c4lam.celeste.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import net.minecraft.client.gl.DynamicUniformStorage;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The shared std140 payload used by Celeste's GUI shaders.
 *
 * <p>Keeping one fixed-size block lets the batch renderer use the same dynamic
 * uniform storage for every GUI pipeline.</p>
 */
public record GuiUniform(float[] values) implements DynamicUniformStorage.Uploadable {
    public static final int MAX_VEC4 = 8;
    public static final int SIZE = calculateSize();

    public GuiUniform {
        if (values.length != MAX_VEC4 * 4) {
            throw new IllegalArgumentException("A GUI uniform must contain exactly " + (MAX_VEC4 * 4) + " floats");
        }
    }

    public static GuiUniform of(float... values) {
        if (values.length > MAX_VEC4 * 4) {
            throw new IllegalArgumentException("A GUI uniform cannot contain more than " + (MAX_VEC4 * 4) + " floats");
        }

        return new GuiUniform(Arrays.copyOf(values, MAX_VEC4 * 4));
    }

    @Override
    public void write(ByteBuffer buffer) {
        Std140Builder builder = Std140Builder.intoBuffer(buffer);
        for (int i = 0; i < MAX_VEC4; i++) {
            int offset = i * 4;
            builder.putVec4(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
        }
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof GuiUniform other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    private static int calculateSize() {
        Std140SizeCalculator calculator = new Std140SizeCalculator();
        for (int i = 0; i < MAX_VEC4; i++) {
            calculator.putVec4();
        }
        return calculator.get();
    }
}
