package io.jenkins.plugins.hygieneauditor.collector.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.security.SafeXml;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads two kinds of footprint from a Jenkins XML file, from <b>element names and attributes only</b> (element text,
 * where any secret would be, is never looked at):
 *
 * <ul>
 *   <li>the plugin names Jenkins stamps as {@code plugin="name@version"} on elements a plugin wrote;
 *   <li>the class names the file mentions: {@code class="..."} attributes and class-named elements. These catch plugins
 *       that are used but not stamped (for example the authorization strategy in {@code config.xml}).
 * </ul>
 */
final class PluginAttributes {
    /** Per-file bound on distinct class names, so one hostile file cannot grow the result without limit. */
    static final int MAX_CLASSES = 2_000;

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+");

    /** What one file revealed. */
    record Result(
            @NonNull Set<String> pluginStamps, @NonNull Set<String> classNames) {}

    private PluginAttributes() {}

    /** @throws XMLStreamException if the file is not well-formed (the caller counts it as unreadable) */
    @NonNull
    static Result scan(@NonNull InputStream in) throws XMLStreamException {
        Set<String> stamps = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        XMLStreamReader r = SafeXml.factory().createXMLStreamReader(in);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    String v = r.getAttributeValue(null, "plugin");
                    if (v != null) {
                        int at = v.indexOf('@');
                        String name = (at < 0 ? v : v.substring(0, at)).trim();
                        if (!name.isEmpty() && name.length() <= 128) {
                            stamps.add(name);
                        }
                    }
                    addClass(classes, r.getAttributeValue(null, "class"));
                    addClass(classes, r.getLocalName());
                }
            }
        } finally {
            r.close();
        }
        return new Result(stamps, classes);
    }

    private static void addClass(Set<String> into, String candidate) {
        if (candidate != null
                && into.size() < MAX_CLASSES
                && candidate.length() <= 256
                && CLASS_NAME.matcher(candidate).matches()) {
            into.add(candidate);
        }
    }
}
