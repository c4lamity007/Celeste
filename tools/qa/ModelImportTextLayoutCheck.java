package dev.c4lam.celeste.gui;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

/** Deterministic input geometry regression; no Minecraft, renderer or external service needed. */
public final class ModelImportTextLayoutCheck {
    private static int checks;

    public static void main(String[] args) {
        ModelImportTextLayout layout = new ModelImportTextLayout();
        eq(layout.width(), 0, "uninitialized width");
        check(layout.nearest(100) == 0, "uninitialized hit");
        AtomicInteger measures = new AtomicInteger();
        ToDoubleFunction<String> font = text -> {
            measures.incrementAndGet();
            return measure(text);
        };
        layout.update("AVx", 9, font);
        eq(layout.width(), 20, "pair kerning");
        eq(layout.x(1), 10, "caret after A");
        eq(layout.x(2), 15, "caret after V");
        eq(layout.drawX(1), 7, "clipped substring retains leading kerning");
        eq(layout.drawX(2), 15, "un-kerned substring origin");
        check(layout.nearest(-1) == 0, "click before text");
        check(layout.nearest(11) == 1, "click left half");
        check(layout.nearest(14) == 2, "click right half");
        check(layout.nearest(100) == 3, "click beyond text");
        check(layout.floor(12) == 1 && layout.ceiling(12) == 2, "clip includes partial glyph");
        check(layout.floor(10) == 1 && layout.ceiling(10) == 1, "exact caret boundary");
        int before = measures.get();
        layout.update("AVx", 9, font);
        check(measures.get() == before, "unchanged frame uses cache");
        layout.update("AVx", 10, font);
        check(measures.get() > before, "size change rebuilds geometry");

        String unicode = "A\uD83D\uDE03V";
        layout.update(unicode, 9, font);
        eq(layout.x(2), 10, "surrogate interior resolves to preceding boundary");
        check(layout.nearest(17) == 3, "emoji nearest position does not split surrogate");
        check(layout.floor(16) == 1 && layout.ceiling(16) == 3, "emoji clipping remains valid UTF16");
        for (int pixel = -2; pixel < 50; pixel++) {
            int hit = layout.nearest(pixel);
            check(hit != 2, "no surrogate split at " + pixel);
        }

        String longPath = "C:\\Models\\" + "AV".repeat(4080) + ".glb";
        measures.set(0);
        layout.update(longPath, 9, font);
        check(measures.get() <= longPath.length() * 3, "path geometry creation is linear");
        eq(layout.width(), (float) measure(longPath), "long path width agrees with renderer metrics");
        int start = layout.floor(layout.width() - 100);
        int end = layout.ceiling(layout.width());
        check(start > 0 && end == longPath.length() && end - start < 50, "only visible long-path glyphs submitted");
        for (int i = 0; i < longPath.length(); i += 71) {
            eq(layout.x(i), (float) measure(longPath.substring(0, i)), "prefix caret width " + i);
        }
        layout.update("", 9, font);
        eq(layout.width(), 0, "clear releases previous geometry");
        check(layout.nearest(100) == 0 && layout.floor(100) == 0 && layout.ceiling(100) == 0,
            "empty field remains selectable");
        System.out.println("Model import text layout: " + checks + " checks passed");
    }

    private static double measure(String text) {
        double width = 0;
        int previous = -1;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            width += cp == 'A' ? 10 : cp == 'V' ? 8 : cp > 0xffff ? 12 : 5;
            if (previous == 'A' && cp == 'V') width -= 3;
            previous = cp;
        }
        return width;
    }

    private static void eq(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.01f, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
