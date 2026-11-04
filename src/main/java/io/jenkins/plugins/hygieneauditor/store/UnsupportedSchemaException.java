package io.jenkins.plugins.hygieneauditor.store;

import java.io.IOException;

/** The data file was written by a newer plugin version. It is left untouched so a downgrade cannot destroy it. */
public class UnsupportedSchemaException extends IOException {
    private static final long serialVersionUID = 1L;

    public UnsupportedSchemaException(String message) {
        super(message);
    }
}
