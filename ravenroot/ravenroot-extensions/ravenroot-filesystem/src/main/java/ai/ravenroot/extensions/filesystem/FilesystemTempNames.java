package ai.ravenroot.extensions.filesystem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Exact, graph-reserved grammar for restart-cleanable same-directory write artifacts. */
final class FilesystemTempNames {
    static final String PREFIX = ".ravenroot-fs-private-v1-";
    private static final int OWNER_HEX = 32;
    private static final int GENERATION_HEX = 32;
    private static final int LEAF_HEX = 16;
    private static final int NONCE_HEX = 32;
    private static final String SUFFIX = ".tmp";
    private static final String RUNTIME_GENERATION = randomToken();

    private FilesystemTempNames() { }

    static Path create(FilesystemProfile profile, Path leaf) {
        return create(profile, leaf, RUNTIME_GENERATION, randomToken());
    }

    /** Deterministic generation/nonce seam for restart ownership tests. */
    static Path create(FilesystemProfile profile, Path leaf, String generation, String nonce) {
        if (!lowerHex(generation, GENERATION_HEX) || !lowerHex(nonce, NONCE_HEX)) {
            throw new IllegalArgumentException("invalid private filesystem temp token");
        }
        String name = PREFIX + owner(profile) + '-' + generation + '-'
                + digest(leaf.toString()).substring(0, LEAF_HEX) + '-' + nonce + SUFFIX;
        return leaf.getFileSystem().getPath(name);
    }

    static boolean isReservedComponent(String component) {
        return component != null && component.startsWith(PREFIX);
    }

    static boolean isOwnedBy(FilesystemProfile profile, Path candidate) {
        Path fileName = candidate == null ? null : candidate.getFileName();
        if (fileName == null) return false;
        String text = fileName.toString();
        String expected = PREFIX + owner(profile) + '-';
        if (!text.startsWith(expected)) return false;
        String tail = text.substring(expected.length());
        int expectedLength = GENERATION_HEX + 1 + LEAF_HEX + 1 + NONCE_HEX + SUFFIX.length();
        if (tail.length() != expectedLength
                || tail.charAt(GENERATION_HEX) != '-'
                || tail.charAt(GENERATION_HEX + 1 + LEAF_HEX) != '-'
                || !tail.endsWith(SUFFIX)) {
            return false;
        }
        int leafStart = GENERATION_HEX + 1;
        int nonceStart = leafStart + LEAF_HEX + 1;
        return lowerHex(tail.substring(0, GENERATION_HEX), GENERATION_HEX)
                && lowerHex(tail.substring(leafStart, leafStart + LEAF_HEX), LEAF_HEX)
                && lowerHex(tail.substring(nonceStart, nonceStart + NONCE_HEX), NONCE_HEX);
    }

    private static String owner(FilesystemProfile profile) {
        String authority = profile.name() + '\0' + profile.root();
        return digest(authority).substring(0, OWNER_HEX);
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean lowerHex(String value, int length) {
        if (value.length() != length) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!((current >= '0' && current <= '9') || (current >= 'a' && current <= 'f'))) return false;
        }
        return true;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
