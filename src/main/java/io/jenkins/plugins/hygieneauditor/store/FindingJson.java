package io.jenkins.plugins.hygieneauditor.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Hand-written JSON codec: the persisted format is explicit, not an accident of reflection. */
public final class FindingJson {

    private FindingJson() {}

    @NonNull
    public static JsonObject toJson(@NonNull Finding f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", f.id());
        o.addProperty("collectorId", f.collectorId());
        o.addProperty("category", f.category().name());
        o.addProperty("type", f.type());
        JsonObject s = new JsonObject();
        s.addProperty("kind", f.subject().kind());
        s.addProperty("fullName", f.subject().fullName());
        s.addProperty("displayName", f.subject().displayName());
        o.add("subject", s);
        o.addProperty("severity", f.severity().name());
        o.addProperty("confidence", f.confidence().name());
        JsonArray ev = new JsonArray();
        for (Evidence e : f.evidence()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("source", e.source());
            eo.addProperty("text", e.text());
            eo.addProperty("weight", e.weight());
            ev.add(eo);
        }
        o.add("evidence", ev);
        o.addProperty("suggestedAction", f.suggestedAction());
        o.addProperty("firstSeen", f.firstSeen().toString());
        o.addProperty("lastSeen", f.lastSeen().toString());
        o.addProperty("status", f.status().name());
        putIfNotNull(o, "statusReason", f.statusReason());
        Instant snooze = f.snoozeUntil();
        putIfNotNull(o, "snoozeUntil", snooze == null ? null : snooze.toString());
        putIfNotNull(o, "statusChangedBy", f.statusChangedBy());
        Instant changedAt = f.statusChangedAt();
        putIfNotNull(o, "statusChangedAt", changedAt == null ? null : changedAt.toString());
        return o;
    }

    /** @throws IllegalArgumentException if the entry is malformed (caller skips it) */
    @NonNull
    public static Finding fromJson(@NonNull JsonObject o) {
        try {
            JsonObject s = o.getAsJsonObject("subject");
            List<Evidence> ev = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("evidence")) {
                JsonObject eo = el.getAsJsonObject();
                ev.add(new Evidence(
                        eo.get("source").getAsString(),
                        eo.get("text").getAsString(),
                        eo.get("weight").getAsInt()));
            }
            return new Finding(
                    o.get("id").getAsString(),
                    o.get("collectorId").getAsString(),
                    Category.valueOf(o.get("category").getAsString()),
                    o.get("type").getAsString(),
                    new Subject(
                            s.get("kind").getAsString(),
                            s.get("fullName").getAsString(),
                            s.get("displayName").getAsString()),
                    Severity.valueOf(o.get("severity").getAsString()),
                    Confidence.valueOf(o.get("confidence").getAsString()),
                    ev,
                    o.get("suggestedAction").getAsString(),
                    Instant.parse(o.get("firstSeen").getAsString()),
                    Instant.parse(o.get("lastSeen").getAsString()),
                    Status.valueOf(o.get("status").getAsString()),
                    str(o, "statusReason"),
                    instant(o, "snoozeUntil"),
                    str(o, "statusChangedBy"),
                    instant(o, "statusChangedAt"));
        } catch (RuntimeException e) { // missing field, bad enum, bad date, wrong JSON type
            throw new IllegalArgumentException("Malformed finding entry", e);
        }
    }

    private static void putIfNotNull(JsonObject o, String key, String value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Instant instant(JsonObject o, String key) {
        String s = str(o, key);
        return s == null ? null : Instant.parse(s);
    }
}
