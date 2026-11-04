package io.jenkins.plugins.hygieneauditor.policy;

/**
 * A CharSequence that aborts regex matching once a deadline passes. Java's regex engine calls {@code charAt}
 * constantly, so this bounds the damage of a catastrophically backtracking pattern.
 */
final class DeadlineCharSequence implements CharSequence {
    static final class MatchTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MatchTimeoutException() {
            super("regex match timed out", null, false, false);
        }
    }

    private final CharSequence inner;
    private final long deadlineNanos;
    private int calls;

    DeadlineCharSequence(CharSequence inner, long timeoutMillis) {
        this.inner = inner;
        this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    @Override
    public char charAt(int index) {
        if ((++calls & 0x3FF) == 0 && System.nanoTime() - deadlineNanos > 0) {
            throw new MatchTimeoutException();
        }
        return inner.charAt(index);
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return inner.subSequence(start, end);
    }

    @Override
    public String toString() {
        return inner.toString();
    }
}
