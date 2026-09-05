package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.CelesteClient;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ArtifactIntegrity {
    private static final String MUTABLE_AUTH_RESOURCE = "META-INF/.r/4d7a9c2e";

    private ArtifactIntegrity() {
    }

    static String digest() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String stagedParts = System.getProperty("celeste.payload.parts", "").strip();
            if (!stagedParts.isEmpty()) {
                List<Path> parts = java.util.Arrays.stream(stagedParts.split(
                    java.util.regex.Pattern.quote(java.io.File.pathSeparator)
                )).map(Path::of).toList();
                digestJars(parts, digest);
                return Crypto.hex(digest.digest());
            }
            Path source = Path.of(CelesteClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(source)) {
                digestDirectory(source, digest);
            } else {
                digestJar(source, digest);
            }
            return Crypto.hex(digest.digest());
        } catch (URISyntaxException | NoSuchAlgorithmException exception) {
            throw new IOException("Could not verify the client artifact", exception);
        }
    }

    private static void digestJar(Path source, MessageDigest digest) throws IOException {
        try (JarFile jar = new JarFile(source.toFile())) {
            List<JarEntry> entries = jar.stream()
                .filter(entry -> !entry.isDirectory() && protectedEntry(entry.getName()))
                .sorted(Comparator.comparing(JarEntry::getName))
                .toList();
            for (JarEntry entry : entries) {
                try (InputStream input = jar.getInputStream(entry)) {
                    update(digest, entry.getName(), input.readAllBytes());
                }
            }
        }
    }

    private static void digestJars(List<Path> sources, MessageDigest digest) throws IOException {
        record StagedEntry(Path source, String name) {
        }
        List<StagedEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path source : sources) {
            try (JarFile jar = new JarFile(source.toFile())) {
                jar.stream()
                    .filter(entry -> !entry.isDirectory() && protectedEntry(entry.getName()))
                    .forEach(entry -> {
                        if (!names.add(entry.getName())) {
                            throw new IllegalStateException("Duplicate protected payload entry: " + entry.getName());
                        }
                        entries.add(new StagedEntry(source, entry.getName()));
                    });
            }
        }
        entries.sort(Comparator.comparing(StagedEntry::name));
        for (StagedEntry entry : entries) {
            try (JarFile jar = new JarFile(entry.source().toFile());
                 InputStream input = jar.getInputStream(jar.getJarEntry(entry.name()))) {
                update(digest, entry.name(), input.readAllBytes());
            }
        }
    }

    private static void digestDirectory(Path source, MessageDigest digest) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(source)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> protectedEntry(source.relativize(path).toString().replace('\\', '/')))
                .forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> source.relativize(path).toString().replace('\\', '/')));
        for (Path path : files) {
            update(digest, source.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
    }

    private static boolean protectedEntry(String name) {
        // Class/package and asset names are randomized after compilation, so a
        // source-package allow-list would silently stop protecting most of a
        // release. The packed release descriptor is the sole exclusion because
        // the packager writes the digest into it after this digest is made.
        return !name.equals(MUTABLE_AUTH_RESOURCE);
    }

    private static void update(MessageDigest digest, String name, byte[] content) throws IOException {
        ByteArrayOutputStream framing = new ByteArrayOutputStream(name.length() + 16);
        try (DataOutputStream output = new DataOutputStream(framing)) {
            byte[] encodedName = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.writeInt(encodedName.length);
            output.write(encodedName);
            output.writeLong(content.length);
        }
        digest.update(framing.toByteArray());
        digest.update(content);
    }
}
