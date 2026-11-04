package io.jenkins.plugins.hygieneauditor.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;

/** The one place that configures an XML parser for untrusted input: no DTDs, no external entities. */
public final class SafeXml {
    private SafeXml() {}

    @NonNull
    public static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        trySet(f, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        trySet(f, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f;
    }

    private static void trySet(XMLInputFactory f, String name, Object value) {
        try {
            f.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // not supported by this parser: DTD and entity support are already off
        }
    }
}
