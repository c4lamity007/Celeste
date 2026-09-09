package dev.c4lam.celeste.gui;

import java.util.Arrays;
import java.util.function.ToDoubleFunction;

/** Cached font-space caret geometry with the font's kerning and Unicode boundaries. */
final class ModelImportTextLayout {
    private String text;
    private float size;
    private int[] boundaries = {0};
    private float[] advances = {0};
    private float[] origins = {0};

    void update(String value, float fontSize, ToDoubleFunction<String> measure) {
        if (value.equals(text) && fontSize == size) return;
        text = value;
        size = fontSize;
        int count = value.codePointCount(0, value.length());
        boundaries = new int[count + 1];
        advances = new float[count + 1];
        origins = new float[count + 1];
        int previousStart = 0;
        for (int index = 1, start = 0; index <= count; index++) {
            int end = value.offsetByCodePoints(start, 1);
            // Pair width includes the complete string's kerning, without quadratic prefix measuring.
            double advance = measure.applyAsDouble(value.substring(previousStart, end));
            if (start > previousStart) advance -= measure.applyAsDouble(value.substring(previousStart, start));
            boundaries[index] = end;
            origins[index - 1] = advances[index - 1] + (float) advance
                - (float) measure.applyAsDouble(value.substring(start, end));
            advances[index] = advances[index - 1] + Math.max(0, (float) advance);
            previousStart = start;
            start = end;
        }
        origins[count] = advances[count];
    }

    float width() { return advances[advances.length - 1]; }

    float x(int character) {
        return advances[index(character)];
    }

    /** Correct starting pen position when a clipped substring loses its preceding kerning pair. */
    float drawX(int character) { return origins[index(character)]; }

    private int index(int character) {
        int result = Arrays.binarySearch(boundaries, Math.max(0, Math.min(text == null ? 0 : text.length(), character)));
        return result < 0 ? Math.max(0, -result - 2) : result;
    }

    int floor(float x) {
        int index = ceilingIndex(x);
        if (index > 0 && advances[index] > x) index--;
        return boundaries[index];
    }

    int ceiling(float x) { return boundaries[ceilingIndex(x)]; }

    int nearest(float x) {
        int right = ceilingIndex(x);
        if (right == 0) return 0;
        int left = right - 1;
        return boundaries[x - advances[left] < advances[right] - x ? left : right];
    }

    private int ceilingIndex(float x) {
        int low = 0;
        int high = advances.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (advances[middle] < x) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
