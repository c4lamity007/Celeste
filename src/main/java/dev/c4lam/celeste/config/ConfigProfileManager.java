package dev.c4lam.celeste.config;

import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Every saved config is a PNG. That is the same file a later VPS cloud host would serve.
 */
public final class ConfigProfileManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Celeste/Configs");
    private static final ConfigProfileManager INSTANCE = new ConfigProfileManager();
    private static final String DEFAULT_PROFILE = "default";

    private final Path root = FabricLoader.getInstance().getConfigDir().resolve("celeste");
    private final Path indexFile = root.resolve("profiles.properties");
    private Path configsDir = root.resolve("configs");
    private String active = DEFAULT_PROFILE;
    private boolean initialized;

    private ConfigProfileManager() {
    }

    public static ConfigProfileManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        loadIndex();
        try {
            Files.createDirectories(configsDir);
        } catch (IOException exception) {
            LOGGER.error("Could not create config folder", exception);
        }
        migrateLegacyProfiles();
        if (listProfiles().isEmpty()) {
            create(DEFAULT_PROFILE);
        }
        initialized = true;
    }

    public synchronized String active() {
        return active;
    }

    public synchronized Path configsDirectory() {
        return configsDir;
    }

    public synchronized String configsDirectoryDisplay() {
        return configsDir.toAbsolutePath().toString();
    }

    public synchronized void setCloudDirectory(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path next = Path.of(raw.trim());
        if (!next.isAbsolute()) {
            next = root.resolve(next);
        }
        configsDir = next;
        try {
            Files.createDirectories(configsDir);
        } catch (IOException exception) {
            LOGGER.error("Could not create config folder {}", configsDir, exception);
        }
        saveIndex();
    }

    public synchronized List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(configsDir)) {
            return names;
        }
        try (var stream = Files.list(configsDir)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                .map(path -> stripExtension(path.getFileName().toString(), ".png"))
                .filter(name -> !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        } catch (IOException exception) {
            LOGGER.error("Could not list configs", exception);
        }
        return names;
    }

    public synchronized String create(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter a config name");
        }
        writePng(configFile(name), capture());
        active = name;
        saveIndex();
        return name;
    }

    public synchronized void saveActive() {
        writePng(configFile(active), capture());
        saveIndex();
    }

    public synchronized void load(String rawName) {
        String name = sanitize(rawName);
        Path file = configFile(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Config '" + name + "' does not exist");
        }
        apply(readPng(file));
        active = name;
        saveIndex();
    }

    public synchronized void delete(String rawName) {
        String name = sanitize(rawName);
        if (DEFAULT_PROFILE.equals(name)) {
            throw new IllegalArgumentException("The default config cannot be deleted");
        }
        try {
            Files.deleteIfExists(configFile(name));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete " + name, exception);
        }
        if (name.equals(active)) {
            active = DEFAULT_PROFILE;
            if (Files.isRegularFile(configFile(active))) {
                apply(readPng(configFile(active)));
            }
            saveIndex();
        }
    }

    public synchronized void importFromPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Paste a PNG path");
        }
        Path path = resolveImportPath(rawPath.trim());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("No file at " + path);
        }
        Properties properties = readAny(path);
        String imported = sanitize(stripExtension(path.getFileName().toString(), ".png"));
        if (imported.isEmpty()) {
            imported = "imported";
        }
        writePng(configFile(imported), properties);
        apply(properties);
        active = imported;
        saveIndex();
    }

    private Properties capture() {
        ModuleManager.getInstance().saveNow();
        HudManager.getInstance().saveLayout();
        Properties snapshot = new Properties();
        snapshot.putAll(ModuleManager.getInstance().exportState());
        snapshot.putAll(InterfaceConfig.exportState());
        snapshot.putAll(HudManager.getInstance().exportLayout());
        return snapshot;
    }

    private void apply(Properties properties) {
        ModuleManager.getInstance().importState(properties);
        InterfaceConfig.importState(properties);
        HudManager.getInstance().importLayout(properties);
        HudManager.getInstance().synchronizeModuleBackedElements();
    }

    private Path configFile(String name) {
        return configsDir.resolve(name + ".png");
    }

    private Path resolveImportPath(String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        Path fromConfigs = configsDir.resolve(raw.endsWith(".png") ? raw : raw + ".png");
        if (Files.isRegularFile(fromConfigs)) {
            return fromConfigs;
        }
        return root.resolve(raw);
    }

    private void migrateLegacyProfiles() {
        Path legacy = root.resolve("profiles");
        Path legacyCloud = root.resolve("cloud");
        if (Files.isDirectory(legacy)) {
            try (var stream = Files.list(legacy)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".celeste")).forEach(file -> {
                    String name = sanitize(stripExtension(file.getFileName().toString(), ".celeste"));
                    if (name.isEmpty() || Files.isRegularFile(configFile(name))) {
                        return;
                    }
                    try {
                        writePng(configFile(name), readProperties(file));
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Could not migrate {}", file, exception);
                    }
                });
            } catch (IOException exception) {
                LOGGER.warn("Could not migrate legacy configs", exception);
            }
        }
        if (Files.isDirectory(legacyCloud) && !legacyCloud.equals(configsDir)) {
            try (var stream = Files.list(legacyCloud)) {
                stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).forEach(file -> {
                    Path target = configsDir.resolve(file.getFileName());
                    if (Files.isRegularFile(target)) {
                        return;
                    }
                    try {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException exception) {
                        LOGGER.warn("Could not copy {}", file, exception);
                    }
                });
            } catch (IOException exception) {
                LOGGER.warn("Could not migrate cloud PNGs", exception);
            }
        }
    }

    private void loadIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(indexFile)) {
            values.load(input);
            String stored = sanitize(values.getProperty("active", DEFAULT_PROFILE));
            if (!stored.isEmpty()) {
                active = stored;
            }
            String folder = values.getProperty("cloud-dir", values.getProperty("configs-dir"));
            if (folder != null && !folder.isBlank()) {
                Path next = Path.of(folder);
                configsDir = next.isAbsolute() ? next : root.resolve(next);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not load config index", exception);
        }
    }

    private void saveIndex() {
        Properties values = new Properties();
        values.setProperty("active", active);
        values.setProperty("configs-dir", configsDir.toAbsolutePath().toString());
        try {
            Files.createDirectories(root);
            try (OutputStream output = Files.newOutputStream(indexFile)) {
                values.store(output, "Celeste config profiles");
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save config index", exception);
        }
    }

    private static Properties readAny(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return readPng(file);
        }
        return readProperties(file);
    }

    private static Properties readProperties(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + file, exception);
        }
        return properties;
    }

    private static void writePng(Path file, Properties properties) {
        try {
            Files.createDirectories(file.getParent());
            ConfigImageCodec.write(file, encode(properties));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write " + file, exception);
        }
    }

    private static Properties readPng(Path png) {
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(ConfigImageCodec.read(png)));
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private static byte[] encode(Properties properties) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            properties.store(output, "Celeste config");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode config", exception);
        }
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-_]+", "-");
        return trimmed.replaceAll("^-+|-+$", "");
    }

    private static String stripExtension(String name, String extension) {
        if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
            return name.substring(0, name.length() - extension.length());
        }
        return name;
    }
}
