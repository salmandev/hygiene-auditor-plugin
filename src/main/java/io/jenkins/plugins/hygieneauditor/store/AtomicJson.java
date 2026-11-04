package io.jenkins.plugins.hygieneauditor.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Crash-safe JSON file writing: temp file, then atomic move. */
final class AtomicJson {
    private static final Gson GSON = new Gson();

    private AtomicJson() {}

    static void write(@NonNull Path file, @NonNull JsonObject json) throws IOException {
        Path name = file.getFileName();
        Path tmp = file.resolveSibling((name == null ? "data" : name.toString()) + ".tmp");
        try {
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
