package io.jenkins.plugins.hygieneauditor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.lang.GroovyShell;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generated artifacts are executed, not just inspected: that is the only convincing test of injection safety. */
class RemovalPlanTest {
    static final Instant NOW = Instant.parse("2026-04-01T10:00:00Z");

    static final List<String> HOSTILE = List.of(
            "it's",
            "back\\slash",
            "\\u0027; System.setProperty('hygiene.pwned','1'); '",
            "'; System.setProperty('hygiene.pwned','2'); '",
            "${System.setProperty('hygiene.pwned','3')}",
            "\"\"\"; System.setProperty('hygiene.pwned','4'); \"\"\"",
            "''' ; System.setProperty('hygiene.pwned','5') ; '''",
            "line1\nline2\r\n" + (char) 0x2028 + "end" + (char) 1 + (char) 0x7f,
            "tab\there",
            "$dollar \\n literal backslash-n",
            "unicode " + (char) 0xe9 + (char) 0x4e2d + new String(Character.toChars(0x1F600)));

    static Finding finding(String type, String kind, String name, Confidence c, String evidence) {
        Category cat = type.startsWith("job")
                ? Category.JOBS
                : type.startsWith("agent") ? Category.AGENTS : Category.CREDENTIALS;
        return Finding.observed(
                new FindingCandidate(
                        type,
                        cat,
                        Subject.of(kind, name, null),
                        Severity.LOW,
                        c,
                        List.of(new Evidence("s", evidence, 50)),
                        "act"),
                "x",
                NOW);
    }

    @Test
    void everyHostileStringSurvivesAsExactlyThatStringAndNeverRunsAsCode() {
        System.clearProperty("hygiene.pwned");
        for (String s : HOSTILE) {
            Object evaluated = new GroovyShell().evaluate(RemovalPlan.groovyLiteral(s));
            assertEquals(s, evaluated, "literal for: " + s.replace("\n", "\\n"));
        }
        assertNull(System.getProperty("hygiene.pwned"), "nothing injected was executed");
    }

    @Test
    void generatedLiteralsNeverContainAUnicodeEscapeOrABareQuote() {
        for (String s : HOSTILE) {
            String lit = RemovalPlan.groovyLiteral(s);
            String body = lit.substring(1, lit.length() - 1);
            assertFalse(body.contains("\\u"), "no \\u sequence can ever be formed: " + lit);
            assertFalse(body.contains("'"), "no raw quote inside the literal: " + lit);
            assertFalse(body.contains("\n") || body.contains("\r"), "single line: " + lit);
        }
    }

    @Test
    void groovyPlanWithHostileNamesCompilesAndNothingIsExecuted() {
        System.clearProperty("hygiene.pwned");
        List<Finding> fs = new ArrayList<>();
        for (String h : HOSTILE) {
            fs.add(finding("job.stale", "job", h, Confidence.HIGH, "evidence " + h));
            fs.add(finding("agent.offline", "agent", h, Confidence.HIGH, h));
            fs.add(finding("credential.unused", "credential", h.replace(":", "_") + ":dom:" + h, Confidence.HIGH, h));
        }
        String script = RemovalPlan.generate(fs, RemovalPlan.Format.GROOVY, "admin'; evil()", NOW);
        assertTrue(script.contains("final boolean DRY_RUN = true"));
        assertTrue(script.contains("NEVER runs this"));
        assertFalse(script.lines().anyMatch(l -> l.startsWith("evil()")), "requester name cannot escape its comment");
        new GroovyShell().parse(script); // must compile
        assertNull(System.getProperty("hygiene.pwned"));
    }

    @Test
    void unsupportedTypesAreListedForManualHandlingNotInvented() {
        Finding lib = finding("library.unused", "library", "mylib", Confidence.HIGH, "nobody uses it");
        String script = RemovalPlan.generate(List.of(lib), RemovalPlan.Format.GROOVY, "a", NOW);
        assertTrue(script.contains("no automatic removal"));
        assertFalse(script.contains("kind: 'library'"));
    }

    @Test
    void shellPlanQuotesEveryValueSoEachNameIsOneArgument(@TempDir Path dir) throws Exception {
        List<String> names = List.of(
                "plain/job", "it's/tricky", "a b; touch $HOME/pwned #", "$(id)", "back\\slash", "`id`", "-rf", "*");
        List<Finding> fs = new ArrayList<>();
        for (String n : names) {
            fs.add(finding("job.stale", "job", n, Confidence.HIGH, "old"));
        }
        fs.add(finding("job.stale", "job", "newline\nname", Confidence.HIGH, "x"));
        String script = RemovalPlan.generate(fs, RemovalPlan.Format.CLI, "admin", NOW);
        assertTrue(script.contains("SKIPPED (unsafe characters"), "control characters are refused, not escaped");
        assertTrue(
                script.lines().filter(l -> l.contains("delete-")).allMatch(l -> l.startsWith("#")),
                "all commented out");

        // Make it executable but harmless: uncomment the commands and replace the CLI with printf.
        String runnable =
                script.replaceAll("(?m)^CLI=.*$", "CLI='printf %s\\\\n'").replaceAll("(?m)^# \\$CLI ", "\\$CLI ");
        Path sh = dir.resolve("plan.sh");
        Files.writeString(sh, runnable);
        ProcessBuilder pb = new ProcessBuilder("sh", sh.toString())
                .redirectErrorStream(true)
                .directory(dir.toFile());
        pb.environment().put("JENKINS_URL", "http://example.invalid/");
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        List<String> printed = out.lines().toList();
        for (String n : names) {
            assertTrue(printed.contains(n), "argument arrives intact: " + n + "\n---\n" + out);
        }
        assertFalse(
                Files.exists(Path.of(System.getProperty("user.home"), "pwned")), "no command substitution happened");
    }

    @Test
    void shellPlanOnlyEmitsCredentialCommandsForTheRootStore() {
        Finding root = finding("credential.unused", "credential", ":_:rootcred", Confidence.HIGH, "x");
        Finding folder = finding("credential.unused", "credential", "team:_:foldercred", Confidence.HIGH, "x");
        String script = RemovalPlan.generate(List.of(root, folder), RemovalPlan.Format.CLI, "a", NOW);
        assertTrue(script.contains("delete-credentials system::system::jenkins '_' 'rootcred'"));
        assertFalse(script.contains("'foldercred'"), "folder credentials are not guessed at");
    }

    @Test
    void checklistHasBackupStepsAndDefangsMarkup() {
        Finding f = finding("job.stale", "job", "x", Confidence.HIGH, "see `rm -rf` <script>alert(1)</script>\nnext");
        String md = RemovalPlan.generate(List.of(f), RemovalPlan.Format.CHECKLIST, "alice", NOW);
        assertTrue(md.contains("Back up"));
        assertTrue(md.contains("- [ ] **x**"));
        assertFalse(md.contains("<script>") || md.contains("`rm -rf`"));
        assertEquals(RemovalPlan.Format.GROOVY, RemovalPlan.Format.parse(null));
        assertEquals(RemovalPlan.Format.CLI, RemovalPlan.Format.parse(" cli "));
    }
}
