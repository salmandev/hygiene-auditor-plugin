package io.jenkins.plugins.hygieneauditor.collector.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

class PluginAttributesTest {
    private static Set<String> scan(String xml) throws Exception {
        Set<String> out = new TreeSet<>();
        PluginAttributes.scan(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), out);
        return out;
    }

    @Test
    void readsPluginNamesFromAttributesAnywhereInTheTree() throws Exception {
        assertEquals(
                Set.of("git", "workflow-job", "bare"),
                scan("<a plugin=\"workflow-job@1571\"><b><c plugin=\"git@5.11.0\"/><d plugin=\"bare\"/></b></a>"));
        assertEquals(Set.of(), scan("<a><b other=\"git@1\"/></a>"));
    }

    @Test
    void neverLooksAtElementTextWhereSecretsLive() throws Exception {
        Set<String> found = scan("<a plugin=\"ssh-credentials@1\"><password>S3cr3t-Hunter2</password>"
                + "<privateKey>-----BEGIN RSA PRIVATE KEY-----</privateKey></a>");
        assertEquals(Set.of("ssh-credentials"), found);
        assertFalse(found.toString().contains("Hunter2"));
    }

    @Test
    void xml11AsWrittenByJenkinsIsAccepted() throws Exception {
        assertEquals(Set.of("p"), scan("<?xml version='1.1' encoding='UTF-8'?><a plugin=\"p@1\"><x>&#x1;</x></a>"));
    }

    @Test
    void hostileXmlFailsSafely() {
        assertThrows(XMLStreamException.class, () -> scan("<a><unclosed></a>"));
        StringBuilder bomb = new StringBuilder("<?xml version='1.0'?><!DOCTYPE l [<!ENTITY a \"aaaaaaaaaa\">");
        String prev = "a";
        for (char c = 'b'; c <= 'j'; c++) {
            bomb.append("<!ENTITY ").append(c).append(" \"");
            for (int i = 0; i < 10; i++) {
                bomb.append('&').append(prev).append(';');
            }
            bomb.append("\">");
            prev = String.valueOf(c);
        }
        bomb.append("]><l plugin=\"x@1\">&").append(prev).append(";</l>");
        assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> assertThrows(XMLStreamException.class, () -> scan(bomb.toString())));
        assertThrows(
                XMLStreamException.class,
                () -> scan(
                        "<?xml version='1.0'?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a plugin=\"p@1\">&e;</a>"));
    }

    @Test
    void absurdNamesAreIgnored() throws Exception {
        assertEquals(Set.of(), scan("<a plugin=\"" + "x".repeat(500) + "@1\"/>"));
        assertEquals(Set.of(), scan("<a plugin=\"@1\"/>"));
    }
}
