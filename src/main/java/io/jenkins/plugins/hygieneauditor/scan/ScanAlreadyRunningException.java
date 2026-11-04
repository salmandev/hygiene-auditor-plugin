package io.jenkins.plugins.hygieneauditor.scan;

import java.util.concurrent.CompletableFuture;

/** Only one scan runs at a time. Carries the running scan's result so callers may wait for it. */
public class ScanAlreadyRunningException extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient CompletableFuture<ScanRecord> running;

    public ScanAlreadyRunningException(CompletableFuture<ScanRecord> running) {
        super("A scan is already running");
        this.running = running;
    }

    public CompletableFuture<ScanRecord> running() {
        return running;
    }
}
