package ai.ravenroot.programming.graalvm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Worker-local implementation of the v1 program identity; the worker has no application API classpath. */
final class ProgramArtifactDigest {
    private static final byte[] DOMAIN = "ravenroot.program-artifact.v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private ProgramArtifactDigest() { }

    static String canonical(String language, String source) {
        byte[] languageBytes = language.getBytes(StandardCharsets.UTF_8);
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
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
