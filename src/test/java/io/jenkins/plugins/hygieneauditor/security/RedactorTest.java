package io.jenkins.plugins.hygieneauditor.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedactorTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password=hunter2",
                "PASSWORD: 'hunter2'",
                "client_secret = \"hunter2\"",
                "api-key:hunter2",
                "token=hunter2&x=1",
                "Authorization: Bearer hunter2hunter2",
                "git clone https://user:hunter2@example.com/repo.git",
                "-----BEGIN RSA PRIVATE KEY-----\nhunter2\n-----END RSA PRIVATE KEY-----",
                "ghp_hunter2hunter2hunter2hunter2",
                "AKIAHUNTER2HUNTER2XX",
            })
    void secretsAreMasked(String input) {
        String out = Redactor.redact("see " + input + " end");
        assertFalse(out.toLowerCase().contains("hunter2"), out);
        assertTrue(out.contains(Redactor.MASK), out);
    }

    @Test
    void credentialIdsAreKept() {
        // IDs are identifiers, not secrets: findings must be able to name them.
        assertEquals("credentialsId: 'prod-deploy'", Redactor.redact("credentialsId: 'prod-deploy'"));
    }

    @Test
    void describeRedactsAndTruncates() {
        String d = Redactor.describe(new IllegalStateException("bad password=hunter2 " + "x".repeat(1000)));
        assertFalse(d.contains("hunter2"));
        assertTrue(d.startsWith("java.lang.IllegalStateException: "));
        assertTrue(d.length() < 400);
    }

    @Test
    void nullAndEmpty() {
        assertEquals("", Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }
}
