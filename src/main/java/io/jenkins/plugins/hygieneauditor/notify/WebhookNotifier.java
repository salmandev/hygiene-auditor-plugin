package io.jenkins.plugins.hygieneauditor.notify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * POSTs a JSON summary to a configured URL.
 *
 * <ul>
 *   <li>Body: {@code event}, {@code scanId}, {@code instance}, {@code count}, {@code findings[]} (at most 50: id,
 *       type, subject, severity, confidence, url). No evidence text, so nothing beyond what a finding's name reveals.
 *   <li>If a secret is configured, {@code X-Hygiene-Signature: sha256=<hex HMAC of the exact body>} lets the receiver
 *       authenticate the sender.
 *   <li>Honours Jenkins' proxy settings, never follows redirects (a redirect could otherwise forward the signed body
 *       elsewhere), 5 s connect and 10 s read timeouts. Any non-2xx answer is a failure.
 * </ul>
 */
@Extension
public class WebhookNotifier extends HygieneNotifier {
    static final int MAX_FINDINGS = 50;
    private static final Gson GSON = new Gson();

    @NonNull
    @Override
    public String getId() {
        return "webhook";
    }

    @Override
    public boolean isConfigured() {
        return !HygieneAuditorConfiguration.get().getWebhookUrl().isEmpty();
    }

    @Override
    public void notifyNewFindings(@NonNull NotificationBatch batch) throws IOException {
        HygieneAuditorConfiguration cfg = HygieneAuditorConfiguration.get();
        URL url = parse(cfg.getWebhookUrl());
        byte[] body = GSON.toJson(payload(batch)).getBytes(StandardCharsets.UTF_8);
        URLConnection raw = ProxyConfiguration.open(url);
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("Not an HTTP(S) connection");
        }
        HttpURLConnection c = (HttpURLConnection) raw;
        try {
            c.setRequestMethod("POST");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(5_000);
            c.setReadTimeout(10_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("User-Agent", "Jenkins-HygieneAuditor");
            Secret secret = cfg.getWebhookSecret();
            if (secret != null && !secret.getPlainText().isEmpty()) {
                c.setRequestProperty("X-Hygiene-Signature", "sha256=" + sign(secret.getPlainText(), body));
            }
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("Webhook answered HTTP " + code); // never include the URL: it may hold a token
            }
        } finally {
            c.disconnect();
        }
    }

    static JsonObject payload(NotificationBatch batch) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "hygiene.findings.new");
        o.addProperty("scanId", batch.scan().scanId());
        if (batch.instanceUrl() != null) {
            o.addProperty("instance", batch.instanceUrl());
        }
        o.addProperty("count", batch.findings().size());
        JsonArray arr = new JsonArray();
        for (Finding f : batch.findings().stream().limit(MAX_FINDINGS).toList()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", f.id());
            e.addProperty("type", f.type());
            e.addProperty("subject", f.subject().displayName());
            e.addProperty("severity", f.severity().name());
            e.addProperty("confidence", f.confidence().name());
            String link = batch.linkTo(f);
            if (link != null) {
                e.addProperty("url", link);
            }
            arr.add(e);
        }
        o.add("findings", arr);
        o.addProperty("truncated", batch.findings().size() > MAX_FINDINGS);
        return o;
    }

    static String sign(String secret, byte[] body) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IOException("HMAC unavailable", e);
        }
    }

    /**
     * Accepts only absolute http(s) URLs with a host and no embedded credentials.
     *
     * @throws IllegalArgumentException with a message that is safe to show
     */
    public static URL parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("URL is empty");
        }
        if (spec.length() > 2048 || spec.chars().anyMatch(ch -> ch < 0x21 || ch == 0x7f)) {
            throw new IllegalArgumentException("URL is too long or contains spaces or control characters");
        }
        try {
            URI u = new URI(spec);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only http and https URLs are allowed");
            }
            if (u.getHost() == null || u.getHost().isEmpty()) {
                throw new IllegalArgumentException("URL has no host");
            }
            if (u.getUserInfo() != null) {
                throw new IllegalArgumentException("Do not put credentials in the URL; use the secret field");
            }
            return u.toURL();
        } catch (URISyntaxException | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Not a valid URL");
        }
    }
}
