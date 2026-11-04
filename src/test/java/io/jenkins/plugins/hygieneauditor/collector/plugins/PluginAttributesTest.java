package io.jenkins.plugins.hygieneauditor.collector.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

class PluginAttributesTest {
    private static PluginAttributes.Result scan(String xml) throws Exception {
        return PluginAttributes.scan(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Set<String> stamps(String xml) throws Exception {
        return scan(xml).pluginStamps();
    }

    @Test
    void readsPluginNamesFromAttributesAnywhereInTheTree() throws Exception {
        assertEquals(
                Set.of("git", "workflow-job", "bare"),
                stamps("<a plugin=\"workflow-job@1571\"><b><c plugin=\"git@5.11.0\"/><d plugin=\"bare\"/></b></a>"));
        assertEquals(Set.of(), stamps("<a><b other=\"git@1\"/></a>"));
    }

    @Test
    void readsClassNamesFromClassAttributesAndClassNamedElements() throws Exception {
        PluginAttributes.Result r =
                scan("<jenkins><authorizationStrategy class=\"hudson.security.ProjectMatrixAuthorizationStrategy\">"
                        + "<hudson.tasks.Shell><command>echo $HOME</command></hudson.tasks.Shell>"
                        + "<plain/><project/><foo class=\"notAClassName\"/></authorizationStrategy></jenkins>");
        assertEquals(
                Set.of("hudson.security.ProjectMatrixAuthorizationStrategy", "hudson.tasks.Shell"), r.classNames());
        assertFalse(r.classNames().contains("project"), "names without a dot are not classes");
    }

    @Test
    void neverLooksAtElementTextWhereSecretsLive() throws Exception {
        PluginAttributes.Result r = scan("<a plugin=\"ssh-credentials@1\"><password>S3cr3t-Hunter2</password>"
                + "<privateKey>-----BEGIN RSA PRIVATE KEY-----</privateKey></a>");
        assertEquals(Set.of("ssh-credentials"), r.pluginStamps());
        assertFalse(r.toString().contains("Hunter2") || r.toString().contains("PRIVATE KEY"));
    }

    @Test
    void classNamesAreBoundedPerFile() throws Exception {
        StringBuilder xml = new StringBuilder("<a>");
        for (int i = 0; i < PluginAttributes.MAX_CLASSES + 500; i++) {
            xml.append("<x class=\"com.example.C").append(i).append("\"/>");
        }
        assertEquals(
                PluginAttributes.MAX_CLASSES,
                scan(xml.append("</a>").toString()).classNames().size());
    }

    @Test
    void xml11AsWrittenByJenkinsIsAccepted() throws Exception {
        assertEquals(Set.of("p"), stamps("<?xml version='1.1' encoding='UTF-8'?><a plugin=\"p@1\"><x>&#x1;</x></a>"));
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
        assertEquals(Set.of(), stamps("<a plugin=\"" + "x".repeat(500) + "@1\"/>"));
        assertEquals(Set.of(), stamps("<a plugin=\"@1\"/>"));
        assertTrue(scan("<a class=\"" + "a.".repeat(300) + "B\"/>").classNames().isEmpty(), "over-long class names");
    }
}
