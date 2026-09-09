package dev.celestebeta.linkage.fixture;

import dev.celestebeta.linkage.LinkageCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Only targets child JVMs this test has just created; never discovers existing processes. */
public final class FixtureCheck {
    private static int checks;
    private FixtureCheck() { }

    public static void main(String[] args) throws Exception {
        Path runs = Files.createDirectory(Path.of(args[6]).resolve("fixture-" + UUID.randomUUID()));
        run(args, runs, false);
        run(args, runs, true);
        System.out.println("PASS Minecraft linkage diagnostic: " + checks + " checks on self-created synthetic JVMs; no game attached");
    }

    private static void run(String[] args, Path runs, boolean plain) throws Exception {
        String nonce = UUID.randomUUID().toString();
        Process process = new ProcessBuilder(args[0], "-cp", args[1], FixtureTarget.class.getName(), args[2], nonce,
            plain ? "plain" : "minecraft").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            java.io.BufferedReader reader = process.inputReader();
            String ready = reader.readLine();
            check(("READY " + nonce + " " + process.pid()).equals(ready), "fresh fixture identity/nonce");
            Path output = runs.resolve(plain ? "rejected" : "captured");
            try {
                LinkageCli.main(new String[] {"--pid", Long.toString(process.pid()), "--workspace", args[5], "--output", output.toString(),
                    "--agent", args[3], "--bridge", args[4]});
                check(!plain, "ordinary JVM must be rejected");
            } catch (IllegalStateException rejected) {
                if (!plain) throw rejected;
                checks++;
            }
            String report = Files.readString(output.resolve("report.json"));
            if (plain) {
                check(report.contains("\"failedOperation\":\"verify-target\""), "non-Minecraft rejection precedes bridge setup");
                check(report.contains("\"bridgeAppended\":false"), "non-Minecraft target receives no bridge append");
                check(!Files.exists(output.resolve("classes")), "non-Minecraft target emits no class capture");
            } else {
                check(report.contains("\"status\":\"captured\""), "capture completed");
                check(report.contains("\"bridgeAppended\":true"), "canonical bridge available through isolated game loader");
                check(report.contains("otherModAddedField"), "capture preserves actual extra target field schema");
                check(report.contains("\"transformerReturnsNull\":true"), "observer does not replace bytecode");
                check(Files.size(output.resolve("classes/net/minecraft/client/MinecraftClient.class")) > 0, "actual captured bytes exported");
                process.getOutputStream().write('t');
                process.getOutputStream().flush();
                check("2:71".equals(reader.readLine()), "existing instance state and method behavior survive capture");
            }
        } finally {
            if (process.isAlive()) {
                process.getOutputStream().write('q');
                process.getOutputStream().flush();
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
