package dev.celestebeta.linkage.fixture;

import java.net.URLClassLoader;
import java.nio.file.Path;

public final class FixtureTarget {
    private FixtureTarget() { }
    public static void main(String[] args) throws Exception {
        try (URLClassLoader game = new URLClassLoader(new java.net.URL[] {Path.of(args[0]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> clientType = null;
            Object client = null;
            if (!args[2].equals("plain")) {
                Class.forName("net.fabricmc.loader.api.FabricLoader", true, game);
                clientType = Class.forName("net.minecraft.client.MinecraftClient", true, game);
                client = clientType.getConstructor().newInstance();
                clientType.getMethod("tick").invoke(client);
            }
            System.out.println("READY " + args[1] + " " + ProcessHandle.current().pid());
            System.out.flush();
            int command;
            while ((command = System.in.read()) != -1 && command != 'q') {
                if (command == 't' && clientType != null) {
                    System.out.println(clientType.getMethod("tick").invoke(client) + ":" + clientType.getField("otherModAddedField").get(client));
                    System.out.flush();
                }
            }
        }
    }
}
