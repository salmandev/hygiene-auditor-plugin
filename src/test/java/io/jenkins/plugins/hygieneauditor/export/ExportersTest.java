package io.jenkins.plugins.hygieneauditor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import io.jenkins.plugins.hygieneauditor.store.FindingJson;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportersTest {
    static Finding finding(String name, String evidence, String action) {
        return Finding.observed(
                new FindingCandidate(
                        "job.stale",
                        Category.JOBS,
                        Subject.of("job", name, null),
                        Severity.MEDIUM,
                        Confidence.HIGH,
                        List.of(new Evidence("s", evidence, 50), new Evidence("s", "second", 0)),
                        action),
                "jobs",
                Instant.parse("2026-02-03T04:05:06Z"));
    }

    private static String csv(Finding... fs) throws IOException {
        StringWriter w = new StringWriter();
        CsvExporter.write(List.of(fs), w);
        return w.toString();
    }

    @Test
    void csvHasHeaderAndQuotesCommasQuotesAndNewlines() throws Exception {
        String out = csv(finding("a/b", "said \"hi\", twice\nnext line", "Review, then delete"));
        String[] lines = out.split("\r\n");
        assertEquals(2, lines.length, "embedded newlines are flattened, one record per line");
        assertEquals(
                "id,category,type,subject_kind,subject,severity,confidence,status,first_seen,last_seen,suggested_action,evidence",
                lines[0]);
        assertTrue(lines[1].contains("\"Review, then delete\""));
        assertTrue(lines[1].contains("\"said \"\"hi\"\", twice next line | second\""));
        assertTrue(lines[1].contains("2026-02-03T04:05:06Z"));
    }

    @Test
    void csvNeutralisesSpreadsheetFormulas() throws Exception {
        for (String evil : List.of("=cmd|' /C calc'!A0", "+1+1", "-2+3", "@SUM(1,1)", "\tTAB")) {
            String out = csv(finding(evil, "e", "x"));
            String record = out.split("\r\n")[1];
            // the subject column is the fifth cell: it must start with an apostrophe, never with the trigger
            String cell = record.split(",")[4].replace("\"", "");
            assertTrue(cell.startsWith("'"), evil + " -> " + cell);
        }
        assertEquals("plain", CsvExporter.cell("plain"));
        assertEquals("'=1", CsvExporter.cell("=1"));
    }

    @Test
    void jsonIsValidCarriesAVersionAndRoundTripsFindings() throws Exception {
        Finding a = finding("a", "e1", "x");
        Finding b = finding("b \"quoted\" é", "e2", "y");
        StringWriter w = new StringWriter();
        JsonExporter.write(List.of(a, b), Instant.parse("2026-03-01T00:00:00Z"), w);
        JsonObject root = JsonParser.parseString(w.toString()).getAsJsonObject();
        assertEquals(JsonExporter.EXPORT_VERSION, root.get("exportVersion").getAsInt());
        assertEquals(2, root.get("count").getAsInt());
        assertEquals("2026-03-01T00:00:00Z", root.get("exportedAt").getAsString());
        assertEquals(
                b, FindingJson.fromJson(root.getAsJsonArray("findings").get(1).getAsJsonObject()));
    }

    @Test
    void emptyExportsAreWellFormed() throws Exception {
        assertEquals(1, csv().split("\r\n").length);
        StringWriter w = new StringWriter();
        JsonExporter.write(List.of(), Instant.now(), w);
        assertEquals(
                0,
                JsonParser.parseString(w.toString())
                        .getAsJsonObject()
                        .getAsJsonArray("findings")
                        .size());
        assertFalse(w.toString().contains("null"));
    }
}
