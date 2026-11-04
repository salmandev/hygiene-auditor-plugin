package io.jenkins.plugins.hygieneauditor.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RFC 4180 CSV. Spreadsheet applications execute cells that start with {@code = + - @} (CSV/formula injection), and
 * job names are attacker-influenced, so such cells are prefixed with an apostrophe.
 */
public final class CsvExporter {
    static final List<String> HEADER = List.of(
            "id",
            "category",
            "type",
            "subject_kind",
            "subject",
            "severity",
            "confidence",
            "status",
            "first_seen",
            "last_seen",
            "suggested_action",
            "evidence");

    private CsvExporter() {}

    public static void write(@NonNull List<Finding> findings, @NonNull Writer out) throws IOException {
        row(out, HEADER);
        for (Finding f : findings) {
            row(
                    out,
                    List.of(
                            f.id(),
                            f.category().name(),
                            f.type(),
                            f.subject().kind(),
                            f.subject().fullName(),
                            f.severity().name(),
                            f.confidence().name(),
                            f.status().name(),
                            f.firstSeen().toString(),
                            f.lastSeen().toString(),
                            f.suggestedAction(),
                            f.evidence().stream().map(Evidence::text).collect(Collectors.joining(" | "))));
        }
    }

    private static void row(Writer out, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(cell(cells.get(i)));
        }
        out.write("\r\n");
    }

    static String cell(String raw) {
        String s = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (!s.isEmpty() && "=+-@\t".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
