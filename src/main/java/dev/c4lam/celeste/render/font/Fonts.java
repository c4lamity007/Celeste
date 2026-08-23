package dev.c4lam.celeste.render.font;

/** Lazy SF Pro accessors; no font resources are touched until first use. */
public final class Fonts {
    private static volatile Font regular;
    private static volatile Font semibold;
    private static volatile Font bold;

    private Fonts() {
    }

    public static Font regular() {
        Font value = regular;
        if (value == null) {
            synchronized (Fonts.class) {
                value = regular;
                if (value == null) {
                    regular = value = load("sf_pro/sf_regular");
                }
            }
        }
        return value;
    }

    public static Font semibold() {
        Font value = semibold;
        if (value == null) {
            synchronized (Fonts.class) {
                value = semibold;
                if (value == null) {
                    semibold = value = load("sf_pro/sf_semibold");
                }
            }
        }
        return value;
    }

    public static Font bold() {
        Font value = bold;
        if (value == null) {
            synchronized (Fonts.class) {
                value = bold;
                if (value == null) {
                    bold = value = load("sf_pro/sf_bold");
                }
            }
        }
        return value;
    }

    /** Clears lazy wrappers after a resource reload; textures remain MC-owned. */
    public static synchronized void reload() {
        regular = null;
        semibold = null;
        bold = null;
    }

    private static Font load(String path) {
        return Font.builder().find(path).load();
    }
}
