package dev.c4lam.celeste.bootstrap.qa;

import java.lang.reflect.Proxy;
import net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/** Actual Fabric discovery and dependency parsing, without Minecraft initialization or authentication. */
public final class CelesteBootstrapCheck {
    private static int checks;
    private CelesteBootstrapCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected bootstrap, Fabric loader and isolated output directory");
        Path source = Path.of(args[0]), loader = Path.of(args[1]);
        Path root = Files.createTempDirectory(Path.of(args[2]), "bootstrap-mod-");
        Path mods = Files.createDirectories(root.resolve("mods"));
        Path installed = Files.copy(source, mods.resolve("Celeste.jar"));
        List<Path> discovered = discover(mods);
        check(discovered.equals(List.of(installed)), "ordinary mods-folder discovery finds Celeste.jar without a hook or profile edit");
        LoaderModMetadata metadata;
        Set<String> artifactClasses = new TreeSet<>();
        try (ZipFile jar = new ZipFile(installed.toFile())) {
            jar.stream().filter(e -> e.getName().endsWith(".class")).forEach(e -> artifactClasses.add(e.getName().substring(0, e.getName().length() - 6).replace('/', '.')));
            metadata = metadata(jar.getInputStream(jar.getEntry("fabric.mod.json")), installed, root);
            check(metadata.getId().equals("celeste_bootstrap"), "bootstrap has its own identity distinct from the runtime and MixinExtras");
            check(metadata.getName().equals("Celeste"), "normal product has the Celeste display name");
            check(metadata.getCustomValue("celeste:delivery") != null
                && metadata.getCustomValue("celeste:delivery").getAsString().equals("shared-activation-v1"),
                "movable bootstrap advertises shared activation for the folder downloader");
            check(!metadata.getVersion().getFriendlyString().contains("${"), "artifact version is expanded");
            check(jar.stream().noneMatch(e -> e.getName().startsWith("com/llamalad7/mixinextras/")), "bootstrap does not shadow the loader's canonical MixinExtras classes");
            check(jar.getEntry("mixinextras.init.mixins.json") == null, "bootstrap does not initialize a copied library mixin configuration");
            check(jar.getEntry("dev/c4lam/celeste/CelesteClient.class") == null, "bootstrap does not bundle a local payload fallback");
            for (var nested : metadata.getJars()) {
                check(jar.getEntry(nested.getFile()) != null, "declared hardware-support dependency exists: " + nested.getFile());
            }
        }
        check(dependency(metadata, "fabricloader", ModDependency.Kind.DEPENDS).matches(Version.parse("0.19.3")), "verified Fabric loader satisfies metadata");
        check(!dependency(metadata, "fabricloader", ModDependency.Kind.DEPENDS).matches(Version.parse("0.18.4")), "older unverified loader does not pass dependency admission");
        check(dependency(metadata, "minecraft", ModDependency.Kind.DEPENDS).matches(Version.parse("1.21.11")), "target Minecraft version admitted");
        check(!dependency(metadata, "minecraft", ModDependency.Kind.DEPENDS).matches(Version.parse("1.21.10")), "earlier Minecraft version rejected");
        check(dependency(metadata, "quilt_loader", ModDependency.Kind.BREAKS).matches(Version.parse("0.30.0")), "unverified Quilt private-linker path is explicitly unsupported");
        byte[] library = null;
        try (ZipFile archive = new ZipFile(loader.toFile())) {
            LoaderModMetadata host = metadata(archive.getInputStream(archive.getEntry("fabric.mod.json")), loader, root);
            for (var nested : host.getJars()) {
                if (nested.getFile().contains("mixinextras")) library = archive.getInputStream(archive.getEntry(nested.getFile())).readAllBytes();
            }
        }
        check(library != null, "actual verified Fabric loader supplies MixinExtras");
        LoaderModMetadata extras = null;
        try (ZipInputStream jar = new ZipInputStream(new ByteArrayInputStream(library))) {
            ZipEntry entry;
            while ((entry = jar.getNextEntry()) != null) {
                if (entry.getName().equals("fabric.mod.json")) extras = metadata(new ByteArrayInputStream(jar.readAllBytes()), loader, root);
            }
        }
        check(extras != null && extras.getId().equals("mixinextras"), "loader dependency has genuine MixinExtras identity");
        check(dependency(metadata, "mixinextras", ModDependency.Kind.DEPENDS).matches(extras.getVersion()), "loader's actual MixinExtras version satisfies Celeste");
        check(!dependency(metadata, "mixinextras", ModDependency.Kind.DEPENDS).matches(Version.parse("0.5.3")), "outdated dependency is rejected");
        Files.write(mods.resolve("mixinextras-0.5.4.jar"), library);
        Set<String> identities = new TreeSet<>();
        for (Path candidate : discover(mods)) {
            try (ZipFile jar = new ZipFile(candidate.toFile())) {
                check(identities.add(metadata(jar.getInputStream(jar.getEntry("fabric.mod.json")), candidate, root).getId()), "discovered Celeste and genuine library do not collide on mod identity");
            }
        }
        check(identities.equals(Set.of("celeste_bootstrap", "mixinextras")), "direct install preserves the real library identity");
        try (URLClassLoader entrypoints = new URLClassLoader(new URL[] { installed.toUri().toURL() }, CelesteBootstrapCheck.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = artifactClasses.contains(name) ? findClass(name) : super.loadClass(name, false);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }) {
            for (String key : List.of("preLaunch", "client")) {
                check(metadata.getEntrypoints(key).size() == 1, "one staged bootstrap entrypoint for " + key);
                Class<?> entry = Class.forName(metadata.getEntrypoints(key).getFirst().getValue(), false, entrypoints);
                check(entry.getClassLoader() == entrypoints && Files.isSameFile(installed, Path.of(entry.getProtectionDomain().getCodeSource().getLocation().toURI())), "entrypoint comes from the distributable, with no raw-class fallback");
                check(entry.getDeclaredConstructor() != null, "declared entrypoint resolves without starting auth or game");
            }
        }
        System.out.println("PASS Celeste bootstrap discovery: " + checks + " checks; actual Fabric metadata/dependency parser, no Minecraft/auth initialized");
    }

    private static List<Path> discover(Path mods) throws Exception {
        List<Path> paths = new ArrayList<>();
        Class<?> consumer = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateFinder$ModCandidateConsumer");
        Object callback = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[] { consumer }, (proxy, method, arguments) -> {
            if (!method.getName().equals("accept")) throw new UnsupportedOperationException(method.getName());
            check(Boolean.FALSE.equals(arguments[1]), "production mods are already intermediary-remapped");
            if (arguments[0] instanceof Path path) paths.add(path);
            else if (arguments[0] instanceof List<?> candidates) for (Object candidate : candidates) paths.add((Path) candidate);
            else throw new AssertionError("Unexpected Fabric discovery callback");
            return null;
        });
        var discover = DirectoryModCandidateFinder.class.getMethod("findCandidates", consumer);
        discover.invoke(new DirectoryModCandidateFinder(mods, false), callback);
        return paths;
    }
    private static LoaderModMetadata metadata(InputStream input, Path archive, Path root) throws Exception {
        try (input) {
            return ModMetadataParser.parseMetadata(input, archive.toString(), List.of(), new VersionOverrides(),
                new DependencyOverrides(root.resolve("no-dependency-overrides.json")), false);
        }
    }
    private static ModDependency dependency(LoaderModMetadata metadata, String id, ModDependency.Kind kind) {
        return metadata.getDependencies().stream().filter(d -> d.getModId().equals(id) && d.getKind() == kind)
            .findFirst().orElseThrow(() -> new AssertionError("Missing dependency " + id + " " + kind));
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
