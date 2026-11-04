package io.jenkins.plugins.hygieneauditor.collector.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.security.SafeXml;
import java.io.InputStream;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Collects the plugin names Jenkins stamps on serialised configuration as {@code plugin="name@version"}. Reads
 * <b>attributes only</b>: element text, which is where any secret would be, is never looked at.
 */
final class PluginAttributes {
    private PluginAttributes() {}

    /** @throws XMLStreamException if the file is not well-formed (the caller counts it as unreadable) */
    static void scan(@NonNull InputStream in, @NonNull Set<String> into) throws XMLStreamException {
        XMLStreamReader r = SafeXml.factory().createXMLStreamReader(in);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    String v = r.getAttributeValue(null, "plugin");
                    if (v != null) {
                        int at = v.indexOf('@');
                        String name = (at < 0 ? v : v.substring(0, at)).trim();
                        if (!name.isEmpty() && name.length() <= 128) {
                            into.add(name);
                        }
                    }
                }
            }
        } finally {
            r.close();
        }
    }
}
