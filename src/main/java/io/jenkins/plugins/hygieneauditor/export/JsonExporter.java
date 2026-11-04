package io.jenkins.plugins.hygieneauditor.export;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.store.FindingJson;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.List;

/**
 * Streams findings as JSON, using the same schema as the on-disk store ({@code exportVersion} tracks it), so a large
 * export never has to be held in memory as one tree.
 */
public final class JsonExporter {
    public static final int EXPORT_VERSION = 1;
    private static final Gson GSON = new Gson();

    private JsonExporter() {}

    public static void write(@NonNull List<Finding> findings, @NonNull Instant exportedAt, @NonNull Writer out)
            throws IOException {
        JsonWriter w = new JsonWriter(out);
        w.beginObject();
        w.name("exportVersion").value(EXPORT_VERSION);
        w.name("exportedAt").value(exportedAt.toString());
        w.name("count").value(findings.size());
        w.name("findings").beginArray();
        for (Finding f : findings) {
            GSON.toJson(FindingJson.toJson(f), w);
        }
        w.endArray();
        w.endObject();
        w.flush();
    }
}
