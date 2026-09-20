package dev.c4lam.celeste.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Keep fact order and fallbacks identical to native-verifier/src/portable_hardware.rs. */
final class PortableHardwareFingerprint {
    private PortableHardwareFingerprint() {}

    static HardwareFingerprint collectLinux() {
        return collectLinux(Path.of("/"));
    }

    static HardwareFingerprint collectLinux(Path root) {
        String machineId = first(read(root.resolve("etc/machine-id")), read(root.resolve("var/lib/dbus/machine-id")));
        String uuid = read(root.resolve("sys/class/dmi/id/product_uuid"));
        String serial = read(root.resolve("sys/class/dmi/id/product_serial"));
        String boardSerial = read(root.resolve("sys/class/dmi/id/board_serial"));
        if (!meaningful(machineId) && !meaningful(uuid) && !meaningful(serial) && !meaningful(boardSerial)) {
            throw new IllegalStateException("No stable Linux machine identity is available for Celeste activation");
        }

        String boardVendor = read(root.resolve("sys/class/dmi/id/board_vendor"));
        String boardName = read(root.resolve("sys/class/dmi/id/board_name"));
        String boardVersion = read(root.resolve("sys/class/dmi/id/board_version"));
        String cpuinfo = first(read(root.resolve("proc/cpuinfo")), "");
        String vendor = cpuinfoValue(cpuinfo, "vendor_id", "CPU implementer");
        String model = cpuinfoValue(cpuinfo, "model name", "Processor", "Hardware");
        String arch = command("uname", "-m");
        List<byte[]> edids = new ArrayList<>();
        Path drm = root.resolve("sys/class/drm");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(drm)) {
            for (Path entry : entries) {
                try {
                    edids.add(Files.readAllBytes(entry.resolve("edid")));
                } catch (IOException ignored) {
                    // A disconnected or inaccessible display has no EDID.
                }
            }
        } catch (IOException ignored) {
            // Headless systems bind the monitor component to the system digest.
        }
        return profile(
            new String[] {"linux", machineId, uuid, serial},
            new String[] {"linux", boardVendor, boardName, boardVersion, boardSerial},
            new String[] {"linux", vendor, model, arch},
            edids
        );
    }

    static HardwareFingerprint collectMac() {
        String platform = first(command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), "");
        String serial = ioregValue(platform, "IOPlatformSerialNumber");
        String uuid = ioregValue(platform, "IOPlatformUUID");
        if (!meaningful(serial) && !meaningful(uuid)) {
            throw new IllegalStateException("No stable macOS machine identity is available for Celeste activation");
        }
        String model = first(command("sysctl", "-n", "hw.model"), ioregValue(platform, "model"));
        String boardId = ioregValue(platform, "board-id");
        String arch = command("sysctl", "-n", "hw.machine");
        String brand = command("sysctl", "-n", "machdep.cpu.brand_string");
        String display = first(command("ioreg", "-l", "-w0", "-c", "IODisplayConnect"), "");
        List<byte[]> edids = new ArrayList<>();
        for (String line : display.split("\\R")) {
            byte[] edid = ioregHexValue(line, "IODisplayEDID");
            if (edid != null) {
                edids.add(edid);
            }
        }
        return profile(
            new String[] {"macos", serial, uuid, model},
            new String[] {"macos", boardId, model, serial},
            new String[] {"macos", arch, brand, model},
            edids
        );
    }

    static HardwareFingerprint profile(String[] system, String[] board, String[] processor, List<byte[]> edids) {
        String systemValue = canonical(system);
        Set<String> monitorValues = new LinkedHashSet<>();
        for (byte[] edid : edids) {
            if (edid.length >= 128) {
                monitorValues.add(Crypto.hex(Crypto.sha256(edid)));
            }
        }
        List<String> monitors = new ArrayList<>(monitorValues);
        monitors.sort(Comparator.naturalOrder());
        if (monitors.size() > 8) {
            monitors = new ArrayList<>(monitors.subList(0, 8));
        }
        if (monitors.isEmpty()) {
            monitors.add(Crypto.sha256(systemValue));
        }
        return new HardwareFingerprint(
            Crypto.sha256(systemValue), Crypto.sha256(canonical(board)),
            Crypto.sha256(canonical(processor)), List.copyOf(monitors)
        );
    }

    private static String first(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String read(Path path) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static String command(String... args) {
        Process process;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            AtomicReference<byte[]> output = new AtomicReference<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    output.set(process.getInputStream().readNBytes(2_000_000));
                } catch (IOException ignored) {
                    // A failed OS query is treated as unavailable on both sides.
                }
            });
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            reader.join(1_000);
            if (process.exitValue() != 0) {
                return null;
            }
            byte[] bytes = output.get();
            if (bytes == null) {
                return null;
            }
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        } catch (IOException | SecurityException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String cpuinfoValue(String text, String... names) {
        for (String name : names) {
            for (String line : text.split("\\R")) {
                int separator = line.indexOf(':');
                if (separator < 0 || !line.substring(0, separator).strip().equals(name)) {
                    continue;
                }
                String value = line.substring(separator + 1).strip();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    static String ioregValue(String text, String name) {
        String prefix = "\"" + name + "\"";
        for (String line : text.split("\\R")) {
            int quote = line.indexOf('"');
            if (quote < 0) {
                continue;
            }
            String candidate = line.substring(quote).strip();
            if (!candidate.startsWith(prefix)) {
                continue;
            }
            String rest = candidate.substring(prefix.length()).strip();
            if (!rest.startsWith("=")) {
                continue;
            }
            String value = rest.substring(1).strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    static byte[] ioregHexValue(String line, String name) {
        String value = ioregValue(line, name);
        if (value == null || !value.startsWith("<") || !value.endsWith(">")) {
            return null;
        }
        String hex = value.substring(1, value.length() - 1).replaceAll("\\s", "");
        if (hex.length() < 256 || (hex.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
        } catch (NumberFormatException invalid) {
            return null;
        }
        return bytes;
    }

    private static boolean meaningful(String input) {
        if (input == null) {
            return false;
        }
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || Set.of(
            "unknown", "none", "not specified", "default string", "to be filled by o.e.m.", "uninitialized"
        ).contains(value)) {
            return false;
        }
        return value.chars().anyMatch(c -> Character.isLetterOrDigit(c) && c != '0');
    }

    private static String canonical(String... values) {
        List<String> parts = new ArrayList<>(values.length);
        for (String value : values) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            parts.add(normalized.isEmpty() ? "unknown" : normalized);
        }
        return String.join("\n", parts);
    }
}
