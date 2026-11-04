package io.jenkins.plugins.hygieneauditor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * In Jelly, {@code ${%text(a,b)}} means "localised text with parameters", so a label such as
 * {@code ${%Removal plan (Groovy)}} silently loses its parenthesised part (three download buttons once all read just
 * "Removal plan"). Nothing fails at build time, so this test does.
 */
class JellyKeysTest {
    private static final Pattern KEY = Pattern.compile("\\$\\{%([^}]*)}");

    @Test
    void noLocalisedLabelContainsParenthesesBecauseJellyReadsThemAsParameters() throws IOException {
        Path root = Path.of("src", "main", "resources");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".jelly"))::iterator) {
                Matcher m = KEY.matcher(Files.readString(p));
                while (m.find()) {
                    if (m.group(1).contains("(") || m.group(1).contains(")")) {
                        offenders.add(root.relativize(p) + ": ${%" + m.group(1) + "}");
                    }
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "Remove the parentheses from these Jelly labels:\n" + String.join("\n", offenders));
    }

    @Test
    void everyJellyViewIsWellFormedXml() throws Exception {
        // A malformed view otherwise only fails when somebody opens the page (and takes the whole page down).
        javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "resources"))) {
            for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".jelly"))::iterator) {
                try {
                    f.newDocumentBuilder().parse(p.toFile());
                } catch (org.xml.sax.SAXException e) {
                    broken.add(p + ": " + e.getMessage());
                }
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }
}
