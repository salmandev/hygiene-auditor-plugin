package io.jenkins.plugins.hygieneauditor.collector.credentials;

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

class ReferenceScannerTest {

    static ReferenceIndex index(String... ids) {
        return new ReferenceIndex(Set.of(ids));
    }

    static void xml(ReferenceIndex idx, String location, String xml) throws Exception {
        ReferenceScanner.scanXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), location, idx);
    }

    // ---- XML

    @Test
    void xmlElementTextAndAttributesAreMatched() throws Exception {
        ReferenceIndex idx = index("a", "b", "c");
        xml(idx, "j", "<p><scm><credentialsId>a</credentialsId></scm><x ref=\"b\"/><y>unrelated</y></p>");
        assertTrue(idx.isReferenced("a"));
        assertTrue(idx.isReferenced("b"));
        assertFalse(idx.isReferenced("c"));
    }

    @Test
    void parameterisedCredentialIdIsDynamic() throws Exception {
        ReferenceIndex idx = index("a");
        xml(idx, "team/app", "<p><credentialsId>${DEPLOY_CRED}</credentialsId></p>");
        assertEquals(1, idx.dynamicInScope("team/app"));
        assertEquals(1, idx.dynamicInScope("team"));
        assertEquals(1, idx.dynamicInScope(""));
        assertEquals(0, idx.dynamicInScope("other"));
        assertEquals(0, idx.dynamicInScope("team/app2"), "path prefix must respect folder boundaries");
        assertFalse(idx.isReferenced("a"));
    }

    @Test
    void credentialsParameterDefinitionIsDynamic() throws Exception {
        ReferenceIndex idx = index("a");
        xml(
                idx,
                "j",
                "<p><com.cloudbees.plugins.credentials.CredentialsParameterDefinition><name>C</name>"
                        + "</com.cloudbees.plugins.credentials.CredentialsParameterDefinition></p>");
        assertEquals(1, idx.dynamicTotal());
    }

    @Test
    void credentialDefinitionsInsideAFolderConfigAreNotCountedAsUses() throws Exception {
        ReferenceIndex idx = index("folder-cred", "real-use");
        xml(
                idx,
                "team",
                "<com.cloudbees.hudson.plugins.folder.Folder><properties>"
                        + "<com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider_-FolderCredentialsProperty>"
                        + "<domainCredentialsMap class=\"hudson.util.CopyOnWriteMap$Hash\"><entry><domain/>"
                        + "<java.util.concurrent.CopyOnWriteArrayList><com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>"
                        + "<id>folder-cred</id><password>{AQAAABAAAAAQ}</password></com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>"
                        + "</java.util.concurrent.CopyOnWriteArrayList></entry></domainCredentialsMap>"
                        + "</com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider_-FolderCredentialsProperty>"
                        + "</properties><libs><credentialsId>real-use</credentialsId></libs></com.cloudbees.hudson.plugins.folder.Folder>");
        assertFalse(idx.isReferenced("folder-cred"), "a definition is not a use");
        assertTrue(idx.isReferenced("real-use"), "scanning resumes after the skipped subtree");
    }

    @Test
    void xmlOneDotOneDeclarationAsWrittenByJenkinsIsParsed() throws Exception {
        ReferenceIndex idx = index("a");
        xml(idx, "j", "<?xml version='1.1' encoding='UTF-8'?><p><credentialsId>a</credentialsId><d>&#x1;</d></p>");
        assertTrue(idx.isReferenced("a"));
    }

    @Test
    void externalEntitiesAreNotResolved() {
        ReferenceIndex idx = index("root");
        String xxe = "<?xml version='1.0'?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>";
        // Either rejected or ignored, but the file content must never be read into the index.
        try {
            xml(idx, "j", xxe);
        } catch (Exception expected) {
            assertTrue(expected instanceof XMLStreamException, expected.toString());
        }
        assertFalse(idx.isReferenced("root"));
    }

    @Test
    void entityExpansionBombFailsFastInsteadOfExhaustingMemory() {
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
        bomb.append("]><l>&").append(prev).append(";</l>");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThrows(XMLStreamException.class, () -> xml(index("x"), "j", bomb.toString()));
        });
    }

    @Test
    void malformedXmlThrowsSoCallerCanCountItAsUnscanned() {
        assertThrows(XMLStreamException.class, () -> xml(index("a"), "j", "<p><unclosed></p>"));
    }

    // ---- scripts

    @Test
    void literalsAreMatchedEverywhereIncludingComments() {
        ReferenceIndex idx = index("k1", "k2", "k3", "k4", "k5", "unused");
        ReferenceScanner.scanScript(
                "git url: 'u', credentialsId: 'k1'\n"
                        + "withCredentials([string(credentialsId: \"k2\", variable: 'X')]) { }\n"
                        + "environment { T = credentials('k3') }\n"
                        + "sshagent(credentials: ['k4']) { }\n"
                        + "// credentialsId: 'k5'\n",
                "j",
                idx);
        for (String k : new String[] {"k1", "k2", "k3", "k4", "k5"}) {
            assertTrue(idx.isReferenced(k), k);
        }
        assertFalse(idx.isReferenced("unused"));
        assertEquals(0, idx.dynamicTotal());
    }

    @Test
    void nonLiteralReferencesAreDynamic() {
        ReferenceIndex idx = index("a");
        ReferenceScanner.scanScript(
                "git credentialsId: params.CRED\n"
                        + "git credentialsId: \"deploy-${env.STAGE}\"\n"
                        + "environment { T = credentials(tokenId) }\n"
                        + "sshagent(credentials: [keyVar]) { }\n"
                        + "git credentialsId: null\n",
                "j",
                idx);
        assertEquals(4, idx.dynamicTotal());
    }

    @Test
    void scriptsAreNeverExecuted() {
        // If this were evaluated, the property would be set. It is only text.
        System.clearProperty("hygiene.pwned");
        ReferenceScanner.scanScript(
                "System.setProperty('hygiene.pwned','1'); credentialsId: 'a' ; \"${new File('/tmp/x').delete()}\"",
                "j",
                index("a"));
        assertEquals(null, System.getProperty("hygiene.pwned"));
    }

    @Test
    void hugeInputIsRefusedNotScanned() {
        ReferenceIndex idx = index("a");
        ReferenceScanner.scanScript("x".repeat(ReferenceScanner.MAX_TEXT + 1), "j", idx);
        assertEquals(1, idx.unscanned());
    }

    @Test
    void pathologicalInputDoesNotBlowUpRegex() {
        ReferenceIndex idx = index("a");
        String evil = "credentialsId: '" + "\\".repeat(50_000) + "\n" + "credentials(" + " ".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> ReferenceScanner.scanScript(evil, "j", idx));
    }

    // ---- YAML

    @Test
    void yamlOnlyCredentialsIdKeysCount() {
        ReferenceIndex idx = index("y1", "y2", "y3", "y4");
        ReferenceScanner.scanYaml(
                "jenkins:\n  clouds:\n    - kubernetes:\n        credentialsId: \"y1\"\n"
                        + "    - ssh:\n        - credentialsId: y2 # comment\n"
                        + "credentials:\n  system:\n    domainCredentials:\n      - credentials:\n"
                        + "          - usernamePassword:\n              id: y3\n"
                        + "        gitCredentialsId: ${GIT_CRED}\n",
                idx);
        assertTrue(idx.isReferenced("y1"));
        assertTrue(idx.isReferenced("y2"));
        assertFalse(idx.isReferenced("y3"), "defining a credential is not using it");
        assertEquals(1, idx.dynamicInScope(""));
    }
}
