package io.jenkins.plugins.hygieneauditor.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the stable finding id: first 128 bits of SHA-256 over {@code type|kind|fullName}, hex encoded.
 * This is a public contract: ignore rules, exports and REST clients rely on ids surviving across scans.
 */
public final class FindingIds {

    private FindingIds() {}

    @NonNull
    public static String of(@NonNull String type, @NonNull Subject subject) {
        return of(type, subject.kind(), subject.fullName());
    }

    @NonNull
    public static String of(@NonNull String type, @NonNull String kind, @NonNull String fullName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((type + '|' + kind + '|' + fullName).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JLS; cannot happen
        }
    }
}
