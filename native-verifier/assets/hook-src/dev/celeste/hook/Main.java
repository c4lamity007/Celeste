package dev.celeste.hook;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path gameDir = gameDir(args);
        Path pointer = gameDir.resolve("config").resolve("celeste").resolve("mods.dir");
        if (Files.isRegularFile(pointer)) {
            String mods = new String(Files.readAllBytes(pointer), StandardCharsets.UTF_8).trim();
            if (!mods.isEmpty()) {
                System.setProperty("fabric.addMods", mods);
            }
        }
        Class<?> knot = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClient");
        Method main = knot.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    private static Path gameDir(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--gameDir".equals(args[i]) && i + 1 < args.length) {
                return Paths.get(args[i + 1]);
            }
            if (args[i].startsWith("--gameDir=")) {
                return Paths.get(args[i].substring("--gameDir=".length()));
            }
        }
        return Paths.get(System.getProperty("user.dir"));
    }
}