package io.jenkins.plugins.hygieneauditor.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bounded, versioned history of scan summaries ({@code history.json}), oldest first. */
public final class ScanHistoryStore {
    private static final Logger LOGGER = Logger.getLogger(ScanHistoryStore.class.getName());
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "history.json";
    public static final int MAX_ENTRIES = 730;

    private final Path file;
    private List<ScanHistoryEntry> entries = new ArrayList<>();

    private ScanHistoryStore(Path dir) {
        this.file = dir.resolve(FILE_NAME);
    }

    public static ScanHistoryStore open(@NonNull Path dir) throws IOException {
        Files.createDirectories(dir);
        ScanHistoryStore s = new ScanHistoryStore(dir);
        s.load();
        return s;
    }

    @NonNull
    public synchronized List<ScanHistoryEntry> all() {
        return List.copyOf(entries);
    }

    public synchronized void append(@NonNull ScanHistoryEntry entry) throws IOException {
        List<ScanHistoryEntry> next = new ArrayList<>(entries);
        next.add(entry);
        while (next.size() > MAX_ENTRIES) {
            next.remove(0);
        }
        save(next);
        entries = next;
    }

    private synchronized void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement v = root.get("schemaVersion");
            if (v == null || !v.isJsonPrimitive()) {
                throw new JsonParseException("missing schemaVersion");
            }
            int version = v.getAsInt();
            if (version > SCHEMA_VERSION) {
                throw new UnsupportedSchemaException("History schema " + version + " is newer than supported");
            }
            List<ScanHistoryEntry> loaded = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("scans")) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    loaded.add(new ScanHistoryEntry(
                            o.get("scanId").getAsString(),
                            o.get("trigger").getAsString(),
                            Instant.parse(o.get("end").getAsString()),
                            o.get("state").getAsString(),
                            ints(o.getAsJsonObject("openByCategory")),
                            ints(o.getAsJsonObject("openBySeverity"))));
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Skipped a malformed history entry");
                }
            }
            entries = loaded;
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            Path target = file.resolveSibling(FILE_NAME + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.log(Level.WARNING, "History file was unreadable; moved to {0}", target);
        }
    }

    private void save(List<ScanHistoryEntry> list) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray arr = new JsonArray();
        for (ScanHistoryEntry e : list) {
            JsonObject o = new JsonObject();
            o.addProperty("scanId", e.scanId());
            o.addProperty("trigger", e.trigger());
            o.addProperty("end", e.end().toString());
            o.addProperty("state", e.state());
            o.add("openByCategory", obj(e.openByCategory()));
            o.add("openBySeverity", obj(e.openBySeverity()));
            arr.add(o);
        }
        root.add("scans", arr);
        AtomicJson.write(file, root);
    }

    private static JsonObject obj(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        new java.util.TreeMap<>(m).forEach(o::addProperty);
        return o;
    }

    private static Map<String, Integer> ints(JsonObject o) {
        Map<String, Integer> m = new LinkedHashMap<>();
        o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsInt()));
        return m;
    }
}
