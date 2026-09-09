package dev.c4lam.celeste.beta.probe;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Owned test JVM only. This is not a Minecraft agent or a game launcher. */
public final class ProbeTarget {
    public static void main(String[] args) throws Exception {
        if (Marker.value() != 1) throw new AssertionError("Bad initial fixture");
        System.out.println("READY " + ProcessHandle.current().pid());
        System.out.flush();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                String command = reader.readLine();
                if (command == null || command.equals("quit")) return;
                if (command.equals("status")) {
                    System.out.println("STATE " + Marker.value() + " "
                        + System.getProperty("celeste.beta.probe.result", "pending"));
                    System.out.flush();
                }
            }
        }
    }
}
