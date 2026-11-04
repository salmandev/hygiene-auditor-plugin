package io.jenkins.plugins.hygieneauditor.collector.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Finds credential references in untrusted content. Nothing here evaluates or executes anything: XML goes through
 * a StAX parser with DTDs and external entities disabled; scripts and YAML are matched with bounded regexes.
 *
 * <p>Deliberately conservative towards "used": any string literal equal to a known credential id counts as a
 * reference, including inside comments. Anything that looks like a credential reference but is not a plain
 * literal is counted as <em>dynamic</em> and lowers confidence later.
 *
 * <p>Known limits: triple-quoted strings, ids built by concatenation, references inside shared-library sources
 * that were not part of a scanned run, and credentials selected through user input at build time.
 */
public final class ReferenceScanner {

    static final int MAX_TEXT = 4_000_000;
    private static final int SCRIPT_MIN_LENGTH = 20;

    private static final Pattern LITERAL = Pattern.compile("'([^'\\\\\\n]{1,256})'|\"([^\"\\\\\\n]{1,256})\"");
    private static final Pattern KEY = Pattern.compile(
            "(?i)credentials?Id\\s*[:=]\\s*(?:'([^'\\n]{0,256})'|\"([^\"\\n]{0,256})\"|([^\\s,)\\]}]{1,128}))");
    private static final Pattern CALL = Pattern.compile(
            "(?<![A-Za-z0-9_.])credentials\\s*\\(\\s*(?:'([^'\\n]{0,256})'|\"([^\"\\n]{0,256})\"|([^\\s)'\"]{1,128}))");
    private static final Pattern LIST_VAR =
            Pattern.compile("\\bcredentials\\s*:\\s*\\[\\s*([^'\"\\]\\s][^\\],]{0,127})");
    private static final Pattern YAML_KEY =
            Pattern.compile("(?m)^[ \\t]*(?:-[ \\t]*)?[\\w.-]*[cC]redentials?[iI]d[ \\t]*:[ \\t]*([^\\r\\n]*)$");

    private ReferenceScanner() {}

    // ---------------------------------------------------------------- XML

    /**
     * Scans a Jenkins XML configuration file.
     *
     * @param location full name of the owning item, or empty for global/node files
     * @throws XMLStreamException if the file is not well-formed (the caller counts it as unscanned)
     */
    public static void scanXml(@NonNull InputStream in, @NonNull String location, @NonNull ReferenceIndex idx)
            throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        trySet(f, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        trySet(f, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        XMLStreamReader r = f.createXMLStreamReader(in);
        try {
            String element = "";
            int depth = 0;
            int skipDepth = -1; // >=0 while inside a credential-definition subtree
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (skipDepth == depth) {
                        skipDepth = -1;
                    }
                    depth--;
                    element = "";
                    continue;
                }
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (skipDepth < 0 && isCredentialDefinition(r.getLocalName())) {
                        skipDepth = depth; // folder-level credential stores live inside the folder's config.xml
                    }
                    if (skipDepth >= 0) {
                        continue;
                    }
                    element = r.getLocalName();
                    if (element.endsWith("CredentialsParameterDefinition")) {
                        idx.markDynamic(location); // user picks any credential at build time
                    }
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        String v = r.getAttributeValue(i);
                        if (v != null && v.length() <= 256) {
                            idx.markReferenced(v.trim());
                        }
                    }
                } else if (skipDepth < 0 && (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA)) {
                    String text = r.getText();
                    if (text != null && !text.isBlank() && text.length() <= MAX_TEXT) {
                        handleText(element, text, location, idx);
                    }
                }
            }
        } finally {
            r.close();
        }
    }

    /**
     * Credential definitions are not uses of a credential, and they hold encrypted secrets we must not even look at.
     * Matches the folder property that embeds a credential store and the store's domain map.
     */
    private static boolean isCredentialDefinition(String localName) {
        return localName.equals("domainCredentialsMap") || localName.contains("CredentialsProperty");
    }

    private static void handleText(String element, String text, String location, ReferenceIndex idx) {
        String t = text.trim();
        if (t.length() <= 256) {
            String el = element.toLowerCase(Locale.ROOT);
            boolean credentialField = el.contains("credentialsid") || el.endsWith("credentialid");
            if (credentialField && t.indexOf('$') >= 0) {
                idx.markDynamic(location);
            } else {
                idx.markReferenced(t);
            }
        }
        if (t.length() >= SCRIPT_MIN_LENGTH) {
            scanScript(t, location, idx);
        }
    }

    private static void trySet(XMLInputFactory f, String name, Object value) {
        try {
            f.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // property not supported by this parser: DTD/entity support is already off
        }
    }

    // ------------------------------------------------------------- scripts

    /** Scans Groovy/Pipeline source (inline scripts, Jenkinsfiles of recent runs). Never executes it. */
    public static void scanScript(@NonNull String text, @NonNull String location, @NonNull ReferenceIndex idx) {
        if (text.length() > MAX_TEXT) {
            idx.countUnscanned();
            return;
        }
        Matcher m = LITERAL.matcher(text);
        while (m.find()) {
            String s = m.group(1) != null ? m.group(1) : m.group(2);
            idx.markReferenced(s);
        }
        keyed(KEY, text, location, idx);
        keyed(CALL, text, location, idx);
        Matcher l = LIST_VAR.matcher(text);
        while (l.find()) {
            idx.markDynamic(location);
        }
    }

    private static void keyed(Pattern p, String text, String location, ReferenceIndex idx) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                idx.markReferenced(m.group(1));
            } else if (m.group(2) != null) {
                if (m.group(2).indexOf('$') >= 0) {
                    idx.markDynamic(location);
                } else {
                    idx.markReferenced(m.group(2));
                }
            } else if (m.group(3) != null && !"null".equals(m.group(3))) {
                idx.markDynamic(location);
            }
        }
    }

    // --------------------------------------------------------------- YAML

    /**
     * Scans JCasC YAML/JSON text. Only credentialsId-style keys count: a generic "any scalar equals an id" rule would
     * mistake the credential definitions themselves for uses.
     */
    public static void scanYaml(@NonNull String text, @NonNull ReferenceIndex idx) {
        if (text.length() > MAX_TEXT) {
            idx.countUnscanned();
            return;
        }
        Matcher m = YAML_KEY.matcher(text);
        while (m.find()) {
            String v = m.group(1);
            int hash = v.indexOf(" #");
            if (hash >= 0) {
                v = v.substring(0, hash);
            }
            v = v.trim().replaceAll("^[\"']|[\"'],?$", "").trim();
            if (v.isEmpty() || "null".equals(v) || "~".equals(v)) {
                continue;
            }
            if (v.contains("${") || v.startsWith("$")) {
                idx.markDynamic("");
            } else {
                idx.markReferenced(v);
            }
        }
    }
}
