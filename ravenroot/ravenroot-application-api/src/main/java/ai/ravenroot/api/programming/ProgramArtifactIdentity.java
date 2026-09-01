package ai.ravenroot.api.programming;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical, server-owned identity for executable source.
 *
 * <p>The GraphML document names a language and exact UTF-8 source, but never writes this digest:
 * accepting a caller-provided digest would let a graph claim a revision it did not actually carry.
 * Length prefixes and the domain tag make {@code ("ab", "c")} distinct from
 * {@code ("a", "bc")} and prevent this digest being confused with any other SHA-256 use.</p>
 */
public final class ProgramArtifactIdentity {
    /** Version of the domain-separated digest input format. */
public static final int FORMAT_VERSION = 1;
    /** Maximum accepted UTF-8 source size. */
public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final byte[] DOMAIN = "ravenroot.program-artifact.v1\0".getBytes(StandardCharsets.US_ASCII);

    private ProgramArtifactIdentity() { }

    /**
 * Returns lowercase SHA-256 for the language token and exact UTF-8 source.
* @param language runtime language identifier used to interpret the source
* @param source exact source text used to build the artifact
* @return lowercase domain-separated SHA-256 digest
 */
    public static String sha256(String language, String source) {
        if (language == null || language.isBlank()) throw new IllegalArgumentException("Language cannot be blank");
        byte[] languageBytes = language.getBytes(StandardCharsets.UTF_8);
        byte[] sourceBytes = (source == null ? "" : source).getBytes(StandardCharsets.UTF_8);
        if (sourceBytes.length == 0) throw new IllegalArgumentException("Program source cannot be blank");
        if (sourceBytes.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Program source exceeds " + MAX_SOURCE_BYTES + " UTF-8 bytes");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(languageBytes.length).array());
            digest.update(languageBytes);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(sourceBytes.length).array());
            digest.update(sourceBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
