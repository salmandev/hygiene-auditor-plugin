package io.jenkins.plugins.hygieneauditor.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Crash-safe JSON file writing: Jenkins' {@link AtomicFileWriter} writes a temp file and moves it into place. */
final class AtomicJson {
    private static final Gson GSON = new Gson();

    private AtomicJson() {}

    static void write(@NonNull Path file, @NonNull JsonObject json) throws IOException {
        try (AtomicFileWriter writer = new AtomicFileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(json));
            writer.commit(); // without this, closing the writer discards the temp file and leaves the old file intact
        }
    }
}
