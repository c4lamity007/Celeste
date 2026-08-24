package dev.c4lam.celeste.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Local config browser state. Public cards are stubs so a later cloud host
 * can replace {@link #listPublic()} without changing the GUI.
 */
public final class ConfigLibrary {
    public enum Tab {
        MY("My Configs"),
        FAVOURITE("Favourite Configs"),
        PUBLIC("Public Configs");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Card(
        String id,
        String name,
        String dateLabel,
        Tab origin,
        List<String> enabledModules,
        Properties snapshot
    ) {
        public String initial() {
            return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeste/ConfigLibrary");
    private static final ConfigLibrary INSTANCE = new ConfigLibrary();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("America/Los_Angeles");

    private final Path libraryFile = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste").resolve("library.json");
    private final Set<String> favourites = new LinkedHashSet<>();
    private final Set<String> hiddenPublic = new LinkedHashSet<>();
    private final List<Card> publicStubs = seedPublic();
    private boolean initialized;

    private ConfigLibrary() {
    }

    public static ConfigLibrary getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        load();
        initialized = true;
    }

    public synchronized boolean isFavourite(String id) {
        return id != null && favourites.contains(id);
    }

    public synchronized void toggleFavourite(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (!favourites.remove(id)) {
            favourites.add(id);
        }
        save();
    }

    public synchronized void unfavourite(String id) {
        if (id != null && favourites.remove(id)) {
            save();
        }
    }

    public synchronized void hidePublic(String id) {
        if (id == null || id.isBlank() || !hiddenPublic.add(id)) {
            return;
        }
        save();
    }

    public synchronized int totalVisible(Tab tab) {
        return list(tab, "").size();
    }

    public synchronized List<Card> list(Tab tab, String query) {
        initialize();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Card> cards = new ArrayList<>();
        switch (tab == null ? Tab.MY : tab) {
            case MY -> cards.addAll(listMine());
            case FAVOURITE -> {
                for (Card card : listMine()) {
                    if (isFavourite(card.id())) {
                        cards.add(card);
                    }
                }
                for (Card card : listPublic()) {
                    if (isFavourite(card.id())) {
                        cards.add(card);
                    }
                }
            }
            case PUBLIC -> cards.addAll(listPublic());
        }
        if (needle.isEmpty()) {
            return List.copyOf(cards);
        }
        return cards.stream()
            .filter(card -> card.name().toLowerCase(Locale.ROOT).contains(needle)
                || card.id().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    }

    public synchronized String downloadPublic(String id) {
        initialize();
        Card card = listPublic().stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
        if (card == null) {
            throw new IllegalArgumentException("Public config is gone");
        }
        return ConfigProfileManager.getInstance().importSnapshot(card.name(), card.snapshot());
    }

    public synchronized List<String> enabledModules(Card card) {
        if (card == null) {
            return List.of();
        }
        if (card.origin() != Tab.MY) {
            return List.copyOf(card.enabledModules());
        }
        return ConfigProfileManager.getInstance().enabledModules(card.name());
    }

    private List<Card> listMine() {
        List<Card> cards = new ArrayList<>();
        for (ConfigProfileManager.ConfigEntry entry : ConfigProfileManager.getInstance().listEntries()) {
            cards.add(new Card(
                "my:" + entry.name(),
                entry.name(),
                format(entry.modifiedMillis()),
                Tab.MY,
                ConfigProfileManager.getInstance().enabledModules(entry.name()),
                new Properties()
            ));
        }
        return cards;
    }

    /**
     * Local placeholders only. Swap this method for a cloud fetch later.
     */
    private List<Card> listPublic() {
        List<Card> cards = new ArrayList<>();
        for (Card stub : publicStubs) {
            if (!hiddenPublic.contains(stub.id())) {
                cards.add(stub);
            }
        }
        return cards;
    }

    private static List<Card> seedPublic() {
        return List.of(
            stub("public:sword-duel", "sword-duel", "4 Aug 2026",
                "Sword Aura", "Trigger Bot", "Aim Assist", "W Tap", "Keep Sprint"),
            stub("public:crystal-pvp", "crystal-pvp", "11 Aug 2026",
                "Auto Crystal", "Surround", "Silent Totem", "Auto Trap"),
            stub("public:mace-combo", "mace-combo", "16 Aug 2026",
                "Mace Aura", "Wind Charge", "Mace Swap", "Auto Firework"),
            stub("public:visual-pack", "visual-pack", "19 Aug 2026",
                "Entity ESP", "Hole ESP", "Fullbright", "Array List", "Watermark")
        );
    }

    private static Card stub(String id, String name, String date, String... modules) {
        Properties snapshot = new Properties();
        for (String module : modules) {
            snapshot.setProperty("module." + key(module) + ".enabled", "true");
        }
        return new Card(id, name, date, Tab.PUBLIC, List.of(modules), snapshot);
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String format(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return DATE.format(Instant.ofEpochMilli(millis).atZone(DISPLAY_ZONE));
    }

    private void load() {
        favourites.clear();
        hiddenPublic.clear();
        if (!Files.isRegularFile(libraryFile)) {
            return;
        }
        try {
            String raw = Files.readString(libraryFile, StandardCharsets.UTF_8);
            favourites.addAll(readArray(raw, "favourites"));
            hiddenPublic.addAll(readArray(raw, "hiddenPublic"));
        } catch (IOException exception) {
            LOGGER.error("Could not load config library {}", libraryFile, exception);
        }
    }

    private void save() {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"favourites\": ");
        writeArray(json, favourites);
        json.append(",\n  \"hiddenPublic\": ");
        writeArray(json, hiddenPublic);
        json.append("\n}\n");
        try {
            Files.createDirectories(libraryFile.getParent());
            Files.writeString(libraryFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("Could not save config library {}", libraryFile, exception);
        }
    }

    private static void writeArray(StringBuilder json, Set<String> values) {
        json.append('[');
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append('"').append(escape(value)).append('"');
        }
        json.append(']');
    }

    private static List<String> readArray(String raw, String key) {
        List<String> values = new ArrayList<>();
        String token = "\"" + key + "\"";
        int keyIndex = raw.indexOf(token);
        if (keyIndex < 0) {
            return values;
        }
        int open = raw.indexOf('[', keyIndex);
        int close = raw.indexOf(']', open);
        if (open < 0 || close < 0) {
            return values;
        }
        String body = raw.substring(open + 1, close);
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                values.add(unescape(trimmed.substring(1, trimmed.length() - 1)));
            }
        }
        return values;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
