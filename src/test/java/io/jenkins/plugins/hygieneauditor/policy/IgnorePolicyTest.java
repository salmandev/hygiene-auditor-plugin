package io.jenkins.plugins.hygieneauditor.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IgnorePolicyTest {

    static Finding f(String type, Category cat, String kind, String name) {
        return Finding.observed(
                new FindingCandidate(
                        type,
                        cat,
                        Subject.of(kind, name, null),
                        Severity.LOW,
                        Confidence.LOW,
                        List.of(new Evidence("t", "e", 1)),
                        "x"),
                "c",
                Instant.now());
    }

    static IgnoreRule rule(String category, String type, String subject, String folder) {
        IgnoreRule r = new IgnoreRule(category);
        r.setType(type);
        r.setSubject(subject);
        r.setFolder(folder);
        r.validate();
        return r;
    }

    @Test
    void credentialRuleMatchesTheIdNotTheOwnerPath() {
        IgnorePolicy p = new IgnorePolicy(List.of(rule("CREDENTIALS", null, "^prod-.*", null)));
        assertTrue(p.isExempt(f("credential.unused", Category.CREDENTIALS, "credential", ":_:prod-deploy")));
        assertTrue(p.isExempt(f("credential.unused", Category.CREDENTIALS, "credential", "team/x:dom:prod-key")));
        assertFalse(p.isExempt(f("credential.unused", Category.CREDENTIALS, "credential", ":_:dev-prod-deploy")));
        assertFalse(p.isExempt(f("credential.unused", Category.CREDENTIALS, "credential", "prod-team:_:dev-key")));
        assertFalse(p.isExempt(f("job.stale", Category.JOBS, "job", "prod-deploy")), "category must also match");
    }

    @Test
    void folderRuleCoversTheSubtreeButNotLookalikes() {
        IgnorePolicy p = new IgnorePolicy(List.of(rule(null, null, null, "sandbox/")));
        assertTrue(p.isExempt(f("job.stale", Category.JOBS, "job", "sandbox/a")));
        assertTrue(p.isExempt(f("job.stale", Category.JOBS, "job", "sandbox/deep/er/a")));
        assertTrue(p.isExempt(f("credential.unused", Category.CREDENTIALS, "credential", "sandbox:_:k")));
        assertFalse(p.isExempt(f("job.stale", Category.JOBS, "job", "sandbox2/a")));
        assertFalse(p.isExempt(f("job.stale", Category.JOBS, "job", "x/sandbox/a")));
        assertFalse(p.isExempt(f("agent.offline", Category.AGENTS, "agent", "sandbox")), "agents are not in folders");
    }

    @Test
    void typeWildcardAndAndSemantics() {
        IgnorePolicy p = new IgnorePolicy(List.of(rule(null, "job.*", null, "archive")));
        assertTrue(p.isExempt(f("job.stale", Category.JOBS, "job", "archive/a")));
        assertFalse(p.isExempt(f("job.stale", Category.JOBS, "job", "live/a")), "both fields must match");
        assertFalse(p.isExempt(f("credential.unused", Category.CREDENTIALS, "credential", "archive:_:k")));
        IgnorePolicy exact = new IgnorePolicy(List.of(rule(null, "job.stale", null, null)));
        assertTrue(exact.isExempt(f("job.stale", Category.JOBS, "job", "a")));
        assertFalse(exact.isExempt(f("job.staleness", Category.JOBS, "job", "a")), "no accidental prefix match");
        assertFalse(exact.isExempt(f("job.disabled", Category.JOBS, "job", "a")));
    }

    @Test
    void anyRuleExemptsAndEmptyPolicyExemptsNothing() {
        IgnorePolicy p = new IgnorePolicy(List.of(rule(null, null, "^a$", null), rule(null, null, "^b$", null)));
        assertTrue(p.isExempt(f("job.stale", Category.JOBS, "job", "b")));
        assertFalse(p.isExempt(f("job.stale", Category.JOBS, "job", "c")));
        assertFalse(IgnorePolicy.NONE.isExempt(f("job.stale", Category.JOBS, "job", "a")));
    }

    @Test
    void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IgnoreRule("NOPE"));
        IgnoreRule bad = new IgnoreRule(null);
        assertThrows(IllegalArgumentException.class, () -> bad.setSubject("(["));
        assertThrows(IllegalArgumentException.class, new IgnoreRule(null)::validate, "matches everything");
    }

    @Test
    void oversizedNamesCannotBlowUpMatching() {
        IgnorePolicy p = new IgnorePolicy(List.of(rule(null, null, "(a+)+$", null)));
        String evil = "a".repeat(1000) + "!";
        // (a+)+\$ backtracks exponentially on this input; the deadline turns that into "no match".
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> assertFalse(p.isExempt(f("job.stale", Category.JOBS, "job", evil))));
    }
}
