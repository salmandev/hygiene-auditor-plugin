package io.jenkins.plugins.hygieneauditor.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.tasks.Mailer;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends one plain-text email per scan through the Mailer plugin's SMTP settings. Loaded only when Mailer is
 * installed. Recipients that are not valid addresses are skipped (and logged), never passed to the mail library as
 * free text; the subject is fixed text plus a number, so no scanned content can reach a header.
 */
@Extension(optional = true)
public class EmailNotifier extends HygieneNotifier {
    private static final Logger LOGGER = Logger.getLogger(EmailNotifier.class.getName());
    static final int MAX_LISTED = 50;

    /** How a finished message leaves the building. A seam for tests only; production always uses SMTP. */
    @FunctionalInterface
    interface Sender {
        void send(MimeMessage message) throws MessagingException;
    }

    static volatile Sender sender = Transport::send;

    @NonNull
    @Override
    public String getId() {
        return "email";
    }

    @Override
    public boolean isConfigured() {
        return !HygieneAuditorConfiguration.get().getEmailRecipients().isEmpty();
    }

    @Override
    public void notifyNewFindings(@NonNull NotificationBatch batch) throws MessagingException {
        List<InternetAddress> to = recipients(HygieneAuditorConfiguration.get().getEmailRecipients());
        if (to.isEmpty()) {
            throw new MessagingException("No valid recipient address");
        }
        Mailer.DescriptorImpl d = Mailer.descriptor();
        MimeMessage msg = build(d.createSession(), d.getAdminAddress(), to, batch);
        sender.send(msg);
    }

    static MimeMessage build(
            Session session, @CheckForNull String from, List<InternetAddress> to, NotificationBatch batch)
            throws MessagingException {
        MimeMessage m = new MimeMessage(session);
        try {
            m.setFrom(
                    from == null || from.isBlank() ? new InternetAddress("nobody@nowhere") : new InternetAddress(from));
        } catch (AddressException e) {
            m.setFrom(new InternetAddress("nobody@nowhere", false));
        }
        m.setRecipients(Message.RecipientType.TO, to.toArray(new InternetAddress[0]));
        int n = batch.findings().size();
        m.setSubject(
                "[Jenkins] Hygiene Auditor: " + n + " new finding" + (n == 1 ? "" : "s"),
                StandardCharsets.UTF_8.name());
        m.setText(body(batch), StandardCharsets.UTF_8.name());
        return m;
    }

    static String body(NotificationBatch batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("The Hygiene Auditor found ")
                .append(batch.findings().size())
                .append(" new finding(s) in scan ")
                .append(batch.scan().scanId())
                .append(".\n");
        sb.append("Nothing has been changed; these are reports for you to review.\n\n");
        int i = 0;
        for (Finding f : batch.findings()) {
            if (i++ >= MAX_LISTED) {
                sb.append("... and ")
                        .append(batch.findings().size() - MAX_LISTED)
                        .append(" more.\n");
                break;
            }
            sb.append("- [")
                    .append(f.severity())
                    .append(" / confidence ")
                    .append(f.confidence())
                    .append("] ")
                    .append(f.type())
                    .append("  ")
                    .append(oneLine(f.subject().displayName()))
                    .append('\n');
            String link = batch.linkTo(f);
            if (link != null) {
                sb.append("  ").append(link).append('\n');
            }
        }
        if (batch.instanceUrl() != null) {
            sb.append("\nAll findings: ").append(batch.instanceUrl()).append("manage/hygiene-auditor/\n");
        }
        return sb.toString();
    }

    static List<InternetAddress> recipients(String spec) {
        List<InternetAddress> out = new ArrayList<>();
        for (String part : spec.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                InternetAddress a = new InternetAddress(part, true);
                a.validate();
                out.add(a);
            } catch (AddressException e) {
                LOGGER.log(Level.WARNING, "Skipping an invalid notification address");
            }
        }
        return out;
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }
}
