package io.jenkins.plugins.hygieneauditor.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import io.jenkins.plugins.hygieneauditor.scan.ScanRequest;
import io.jenkins.plugins.hygieneauditor.scan.ScanService;
import io.jenkins.plugins.hygieneauditor.scan.TestStubs;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NotificationTest {

    @TestExtension
    public static class Stub extends TestStubs.StubCollector {}

    /** A notifier that always fails, to prove isolation. */
    @TestExtension
    public static class Exploding extends HygieneNotifier {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String getId() {
            return "exploding";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void notifyNewFindings(NotificationBatch batch) {
            CALLS.incrementAndGet();
            throw new IllegalStateException("boom password=hunter2");
        }
    }

    record Hit(String path, String body, java.util.Map<String, List<String>> headers) {}

    static final List<MimeMessage> SENT = new CopyOnWriteArrayList<>();

    JenkinsRule j;
    HttpServer server;
    final List<Hit> hits = new CopyOnWriteArrayList<>();
    volatile int answer = 200;
    String base;

    @BeforeEach
    void start(JenkinsRule rule) throws Exception {
        j = rule;
        HygieneAuditorConfiguration.get().setDisabledCollectors(List.of("plugins")); // stub shares the category
        Exploding.CALLS.set(0);
        SENT.clear();
        EmailNotifier.sender = SENT::add;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(new Hit(ex.getRequestURI().getPath(), body, ex.getRequestHeaders()));
            if (ex.getRequestURI().getPath().equals("/hook") && answer == 302) {
                ex.getResponseHeaders().add("Location", "/elsewhere");
            }
            ex.sendResponseHeaders(answer, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        TestStubs.BODY.set(ctx -> {});
        EmailNotifier.sender = jakarta.mail.Transport::send;
    }

    private ScanRecord scan() throws Exception {
        return ScanService.get()
                .start(new ScanRequest(Set.of(Category.PLUGINS), "test"))
                .result()
                .get(60, TimeUnit.SECONDS);
    }

    /** The notification runs on the scan thread just after the result is published: wait for it. */
    private void awaitHits(int n) throws Exception {
        for (int i = 0; i < 100 && hits.size() < n; i++) {
            Thread.sleep(50);
        }
    }

    private void emits(String... nameAndSeverity) {
        TestStubs.BODY.set(ctx -> {
            for (int i = 0; i < nameAndSeverity.length; i += 2) {
                ctx.emit(TestStubs.pluginFinding(nameAndSeverity[i], Severity.valueOf(nameAndSeverity[i + 1])));
            }
        });
    }

    // ------------------------------------------------------------- webhook

    @Test
    void announcesOnlyNewFindingsAtOrAboveTheThresholdAndOnlyOnce() throws Exception {
        HygieneAuditorConfiguration.get().setWebhookUrl(base + "/hook");
        emits("hot", "HIGH", "mild", "LOW");
        scan();
        awaitHits(1);
        assertEquals(1, hits.size());
        JsonObject payload = JsonParser.parseString(hits.get(0).body()).getAsJsonObject();
        assertEquals("hygiene.findings.new", payload.get("event").getAsString());
        assertEquals(1, payload.get("count").getAsInt(), "the LOW finding is below the threshold");
        JsonArray fs = payload.getAsJsonArray("findings");
        assertEquals("hot", fs.get(0).getAsJsonObject().get("subject").getAsString());
        assertEquals("HIGH", fs.get(0).getAsJsonObject().get("severity").getAsString());
        assertFalse(hits.get(0).body().contains("evidence text"), "no evidence in the payload");
        assertTrue(fs.get(0).getAsJsonObject().get("url").getAsString().contains("/manage/hygiene-auditor/finding/"));

        scan(); // same finding still open: not news
        Thread.sleep(300);
        assertEquals(1, hits.size(), "an open finding is announced once, not on every scan");

        emits(); // it disappears (resolved)...
        scan();
        emits("hot", "HIGH"); // ...and comes back
        scan();
        awaitHits(2);
        assertEquals(2, hits.size(), "a finding that returns is news again");
    }

    @Test
    void thresholdIsConfigurable() throws Exception {
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        c.setWebhookUrl(base + "/hook");
        c.setNotifyMinSeverity(Severity.LOW);
        emits("hot", "HIGH", "mild", "LOW", "meh", "INFO");
        scan();
        awaitHits(1);
        assertEquals(
                2,
                JsonParser.parseString(hits.get(0).body())
                        .getAsJsonObject()
                        .get("count")
                        .getAsInt());
    }

    @Test
    void requestsAreSignedWhenASecretIsConfigured() throws Exception {
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        c.setWebhookUrl(base + "/hook");
        c.setWebhookSecret(Secret.fromString("shared-s3cret"));
        emits("hot", "HIGH");
        scan();
        awaitHits(1);
        Hit h = hits.get(0);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("shared-s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected =
                "sha256=" + HexFormat.of().formatHex(mac.doFinal(h.body().getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, h.headers().get("X-hygiene-signature").get(0));
        assertFalse(h.body().contains("shared-s3cret"));
        assertTrue(h.headers().get("Content-type").get(0).startsWith("application/json"));
    }

    @Test
    void unsignedWhenNoSecret() throws Exception {
        HygieneAuditorConfiguration.get().setWebhookUrl(base + "/hook");
        emits("hot", "HIGH");
        scan();
        awaitHits(1);
        assertFalse(hits.get(0).headers().containsKey("X-hygiene-signature"));
    }

    @Test
    void aFailingWebhookNeverFailsTheScanAndRedirectsAreNotFollowed() throws Exception {
        HygieneAuditorConfiguration.get().setWebhookUrl(base + "/hook");
        emits("hot", "HIGH");
        answer = 500;
        assertEquals(ScanRecord.State.COMPLETED, scan().state());
        awaitHits(1);
        assertEquals(1, hits.size());

        hits.clear();
        answer = 302; // a redirect must not carry the signed body somewhere else
        emits();
        scan();
        emits("hot", "HIGH");
        scan();
        awaitHits(1);
        Thread.sleep(500);
        assertTrue(hits.stream().noneMatch(h -> h.path().equals("/elsewhere")), "redirect was followed");

        server.stop(0); // unreachable receiver
        emits();
        scan();
        emits("hot", "HIGH");
        assertEquals(ScanRecord.State.COMPLETED, scan().state());
    }

    @Test
    void oneBrokenNotifierDoesNotStopTheOthers() throws Exception {
        HygieneAuditorConfiguration.get().setWebhookUrl(base + "/hook");
        emits("hot", "HIGH");
        scan();
        awaitHits(1);
        assertEquals(1, hits.size(), "webhook still delivered");
        assertEquals(1, Exploding.CALLS.get(), "the failing notifier was tried");
    }

    @Test
    void nothingIsSentWhenNothingIsConfigured() throws Exception {
        emits("hot", "HIGH");
        scan();
        Thread.sleep(300);
        assertTrue(hits.isEmpty());
        assertTrue(SENT.isEmpty());
    }

    @Test
    void webhookUrlsAreValidated() {
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        for (String bad : List.of(
                "file:///etc/passwd",
                "ftp://example.com/x",
                "gopher://example.com",
                "javascript:alert(1)",
                "http://user:pass@example.com/hook",
                "http://",
                "example.com/no-scheme",
                "http://exa mple.com/",
                "http://example.com/\nX-Injected: 1")) {
            assertThrows(IllegalArgumentException.class, () -> c.setWebhookUrl(bad), bad);
        }
        c.setWebhookUrl("https://hooks.example.com/services/abc");
        assertEquals("https://hooks.example.com/services/abc", c.getWebhookUrl());
        c.setWebhookUrl("  ");
        assertEquals("", c.getWebhookUrl());
    }

    @Test
    void errorsNeverEchoTheWebhookUrl() throws Exception {
        // The URL may carry a token in its query string; a failure message must not repeat it.
        HygieneAuditorConfiguration.get().setWebhookUrl(base + "/hook?token=URLSECRET123");
        answer = 500;
        emits("hot", "HIGH");
        var logs = new java.util.ArrayList<String>();
        java.util.logging.Handler h = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                logs.add(new java.util.logging.SimpleFormatter().formatMessage(r));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger l = java.util.logging.Logger.getLogger(NotificationService.class.getName());
        l.addHandler(h);
        try {
            scan();
            awaitHits(1);
            Thread.sleep(500);
        } finally {
            l.removeHandler(h);
        }
        assertTrue(logs.stream().anyMatch(m -> m.contains("failed")), logs.toString());
        assertTrue(logs.stream().noneMatch(m -> m.contains("URLSECRET123") || m.contains("hunter2")), logs.toString());
    }

    // --------------------------------------------------------------- email

    @Test
    void emailGoesToValidRecipientsOnlyAndCarriesNoEvidence() throws Exception {
        HygieneAuditorConfiguration.get().setEmailRecipients("ops@example.com, second@example.com;");
        emits("hot", "HIGH");
        scan();
        for (int i = 0; i < 100 && SENT.isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertEquals(1, SENT.size(), "one message per scan, not one per finding");
        MimeMessage msg = SENT.get(0);
        List<String> to = java.util.Arrays.stream(msg.getRecipients(Message.RecipientType.TO))
                .map(Address::toString)
                .toList();
        assertEquals(List.of("ops@example.com", "second@example.com"), to);
        assertEquals("[Jenkins] Hygiene Auditor: 1 new finding", msg.getSubject());
        String body = (String) msg.getContent();
        assertTrue(body.contains("plugin.stub") && body.contains("hot") && body.contains("Nothing has been changed"));
        assertFalse(body.contains("evidence text"), "findings' evidence stays out of email");
        assertTrue(body.contains("/manage/hygiene-auditor/finding/"), body);
    }

    @Test
    void invalidRecipientsAreSkippedNotPassedToTheMailLibrary() {
        List<jakarta.mail.internet.InternetAddress> ok =
                EmailNotifier.recipients("good@example.com, bad@, <x>@y z, also@good.org");
        assertEquals(
                List.of("good@example.com", "also@good.org"),
                ok.stream().map(Object::toString).toList());
    }

    @Test
    void recipientsAreValidatedOnInputAndHeaderInjectionIsImpossible() {
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        assertThrows(IllegalArgumentException.class, () -> c.setEmailRecipients("not-an-address"));
        assertThrows(IllegalArgumentException.class, () -> c.setEmailRecipients("a@b.c, <evil>@x"));
        assertThrows(IllegalArgumentException.class, () -> c.setEmailRecipients("a@b.c\"\nBcc: x@y.z"));
        c.setEmailRecipients("a@b.c  d@e.f");
        assertEquals("a@b.c  d@e.f", c.getEmailRecipients());
    }

    // ---------------------------------------------------------------- JCasC

    @Test
    void secretIsAcceptedFromJcascButNeverExportedInTheClear(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("c.yml");
        Files.writeString(
                yml,
                "unclassified:\n  hygieneAuditor:\n    webhookUrl: \"https://hooks.example.com/x\"\n"
                        + "    webhookSecret: \"plain-text-secret\"\n    notifyMinSeverity: MEDIUM\n"
                        + "    emailRecipients: \"ops@example.com\"\n");
        ConfigurationAsCode.get().configure(yml.toString());
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        assertEquals("plain-text-secret", c.getWebhookSecret().getPlainText());
        assertEquals(Severity.MEDIUM, c.getNotifyMinSeverity());
        assertEquals("ops@example.com", c.getEmailRecipients());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        String exported = out.toString(StandardCharsets.UTF_8);
        assertTrue(exported.contains("webhookUrl"), exported);
        assertFalse(exported.contains("plain-text-secret"), "secrets are masked on export");
        assertFalse(
                Files.readString(j.jenkins
                                .getRootDir()
                                .toPath()
                                .resolve("io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration.xml"))
                        .contains("plain-text-secret"),
                "and stored encrypted at rest");
    }
}
