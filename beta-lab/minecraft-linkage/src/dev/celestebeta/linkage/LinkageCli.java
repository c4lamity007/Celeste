package dev.celestebeta.linkage;

import com.sun.tools.attach.VirtualMachine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Explicit target only. Never enumerates JVMs or reads command lines/environment/account data. */
public final class LinkageCli {
    private LinkageCli() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !java.util.Set.of("--pid", "--workspace", "--output", "--agent", "--bridge").contains(args[index])
                    || options.put(args[index], args[index + 1]) != null) throw new IllegalArgumentException("Expected --pid --workspace --output --agent --bridge, each once");
        }
        if (options.size() != 5) throw new IllegalArgumentException("Expected --pid --workspace --output --agent --bridge");
        long pid = Long.parseLong(options.get("--pid"));
        if (pid <= 0 || pid == ProcessHandle.current().pid()) throw new IllegalArgumentException("Select an explicit external Minecraft PID");
        ProcessHandle target = ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("Selected process is absent"));
        Instant started = target.info().startInstant().orElseThrow(() -> new IllegalStateException("Selected process start time is unavailable"));
        Path workspace = Path.of(options.get("--workspace")).toRealPath();
        Path output = Path.of(options.get("--output"));
        if (!output.isAbsolute() || !output.normalize().startsWith(workspace) || output.normalize().equals(workspace)) {
            throw new IllegalArgumentException("Output must be an explicit new absolute directory beneath the workspace");
        }
        Path parent = output.getParent().toRealPath();
        if (!parent.startsWith(workspace)) throw new IllegalArgumentException("Output parent escapes workspace");
        output = Files.createDirectory(parent.resolve(output.getFileName())).toRealPath();
        Path agent = Path.of(options.get("--agent")).toRealPath();
        Path bridge = Path.of(options.get("--bridge")).toRealPath();
        if (!Files.isRegularFile(agent) || !Files.isRegularFile(bridge)) throw new IllegalArgumentException("Agent and bridge must be ordinary local files");
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        Request request = new Request(pid, started.toEpochMilli(), HexFormat.of().formatHex(nonce), workspace, output, bridge,
            Report.hash(Files.readAllBytes(bridge)));
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            // getSystemProperties returns a Properties object; inspect only this single non-secret key.
            String version = vm.getSystemProperties().getProperty("java.specification.version");
            if (!"21".equals(version)) throw new IllegalStateException("Selected JVM must use Java 21");
            if (!target.isAlive() || !target.info().startInstant().orElseThrow().equals(started)) {
                throw new IllegalStateException("Selected process identity changed");
            }
            vm.loadAgent(agent.toString(), request.encode());
        } finally {
            vm.detach();
        }
        String receipt = Files.readString(output.resolve("report.json"));
        if (!receipt.contains("\"nonce\":\"" + request.nonce() + "\"")
                || !receipt.contains("\"pid\":" + pid + ",")
                || !receipt.contains("\"startedAt\":" + request.startedAt() + ",")) {
            throw new IllegalStateException("Probe receipt does not match the selected process/request");
        }
        System.out.println("Minecraft linkage report: " + output.resolve("report.json"));
        if (!receipt.contains("\"status\":\"captured\"")) throw new IllegalStateException("Probe rejected target; inspect the local report");
    }
}
