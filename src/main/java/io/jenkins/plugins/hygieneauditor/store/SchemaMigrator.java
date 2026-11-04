package io.jenkins.plugins.hygieneauditor.store;

import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;

/** Upgrades persisted JSON one schema version at a time. */
public final class SchemaMigrator {

    /** Upgrades a document from {@link #from()} to {@code from() + 1}. */
    public interface Step {
        int from();

        @NonNull
        JsonObject apply(@NonNull JsonObject root);
    }

    private SchemaMigrator() {}

    /**
     * @return the document at {@code target} version (same instance if already there)
     * @throws UnsupportedSchemaException if the document is newer than {@code target}
     * @throws IOException if a needed step is missing
     */
    @NonNull
    public static JsonObject migrate(@NonNull JsonObject root, int current, int target, @NonNull List<Step> steps)
            throws IOException {
        if (current > target) {
            throw new UnsupportedSchemaException(
                    "Data file schema " + current + " is newer than supported schema " + target);
        }
        JsonObject doc = root;
        for (int v = current; v < target; v++) {
            final int from = v;
            Step step = steps.stream()
                    .filter(s -> s.from() == from)
                    .findFirst()
                    .orElseThrow(() -> new IOException("No migration from schema " + from));
            doc = step.apply(doc);
            doc.addProperty("schemaVersion", v + 1);
        }
        return doc;
    }
}
