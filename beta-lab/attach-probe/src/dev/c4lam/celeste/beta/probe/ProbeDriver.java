package dev.c4lam.celeste.beta.probe;

import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** No arbitrary PID option: tests only the child process this driver creates. */
public final class ProbeDriver {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected fixture classpath and local probe agent JAR");
        String nonce = HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process child = new ProcessBuilder(java.toString(), "-Dceleste.beta.fixtureNonce=" + nonce,
            "-cp", Path.of(args[0]).toAbsolutePath().toString(), ProbeTarget.class.getName())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var readerExecutor = Executors.newSingleThreadExecutor();
        try (var input = new BufferedReader(new InputStreamReader(child.getInputStream()));
             var commands = new PrintWriter(child.getOutputStream(), true)) {
            String ready = readerExecutor.submit(input::readLine).get(15, TimeUnit.SECONDS);
            if (!("READY " + child.pid()).equals(ready)) throw new AssertionError("Unexpected fixture identity");
            VirtualMachine vm = VirtualMachine.attach(Long.toString(child.pid()));
            try {
                if (!nonce.equals(vm.getSystemProperties().getProperty("celeste.beta.fixtureNonce"))) {
                    throw new SecurityException("Child JVM identity mismatch");
                }
                if (!"21".equals(vm.getSystemProperties().getProperty("java.specification.version"))) {
                    throw new IllegalStateException("This feasibility check targets Java 21 only");
                }
                String agentPath = Path.of(args[1]).toAbsolutePath().toString();
                vm.loadAgent(agentPath, nonce);
                commands.println("status");
                String state = readerExecutor.submit(input::readLine).get(15, TimeUnit.SECONDS);
                if (!"STATE 1 pass".equals(state)) throw new AssertionError("Probe failed: " + state);
                vm.loadAgent(agentPath, nonce);
                var afterDuplicate = vm.getSystemProperties();
                if (!"duplicate-rejected".equals(afterDuplicate.getProperty("celeste.beta.probe.result"))
                        || !"1".equals(afterDuplicate.getProperty("celeste.beta.probe.executions"))) {
                    throw new AssertionError("Duplicate attachment was not safely rejected");
                }
                System.out.println("PASS: late attach, method-body replacement, structural-change rejection, restoration, duplicate rejection");
            } finally {
                vm.detach();
            }
            commands.println("quit");
            if (!child.waitFor(10, TimeUnit.SECONDS) || child.exitValue() != 0) {
                throw new AssertionError("Fixture did not shut down normally");
            }
        } finally {
            readerExecutor.shutdownNow();
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
            }
        }
    }
}
