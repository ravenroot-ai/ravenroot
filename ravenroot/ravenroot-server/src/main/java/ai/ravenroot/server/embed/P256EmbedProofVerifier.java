package ai.ravenroot.server.embed;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded ES256/P-256 proof verifier with exact request binding and TTL replay cleanup. */
public final class P256EmbedProofVerifier {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(1);
    public static final int DEFAULT_CAPACITY = 16_384;
    private static final ECParameterSpec P256 = p256Parameters();

    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final ConcurrentHashMap<String, Instant> replay = new ConcurrentHashMap<>();

    public P256EmbedProofVerifier(Clock clock, Duration ttl) {
        this(clock, ttl, DEFAULT_CAPACITY);
    }

    public P256EmbedProofVerifier(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = EmbedLaunchTicketAuthority.boundedTtl(ttl, "proof");
        if (capacity < 1 || capacity > 1_000_000) throw new IllegalArgumentException("invalid replay capacity");
        this.capacity = capacity;
    }

    public boolean verifyAndConsume(String bearer, long revision, String nonce, String jti,
                                    String method, String uri, Instant issuedAt,
                                    ECPublicKey key, byte[] signature) {
        return verifyAndConsume(bearer, revision, nonce, jti, method, uri, issuedAt,
                key, signature, payload(bearer, revision, nonce, jti, method, uri, issuedAt));
    }

    public boolean verifyExchangeAndConsume(String exchangeId, long revision, String nonce,
                                            String channelId, String acknowledgementCorrelationId,
                                            String jti, String method, String uri, Instant issuedAt,
                                            ECPublicKey key, byte[] signature) {
        if (blank(channelId) || blank(acknowledgementCorrelationId)) return false;
        return verifyAndConsume(exchangeId, revision, nonce, jti, method, uri, issuedAt, key, signature,
                exchangePayload(exchangeId, revision, nonce, channelId,
                        acknowledgementCorrelationId, jti, method, uri, issuedAt));
    }

    private boolean verifyAndConsume(String bearer, long revision, String nonce, String jti,
                                     String method, String uri, Instant issuedAt,
                                     ECPublicKey key, byte[] signature, byte[] payload) {
        if (blank(bearer) || revision < 1 || blank(nonce) || blank(jti) || jti.length() > 256
                || !"POST".equals(method) || blank(uri) || issuedAt == null || key == null
                || signature == null || signature.length != 64 || !isP256(key)) {
            return false;
        }
        Instant now = clock.instant();
        if (issuedAt.isAfter(now) || !now.isBefore(issuedAt.plus(ttl))) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) return false;
        } catch (GeneralSecurityException invalid) {
            return false;
        }
        String replayKey = sha256((EmbedLaunchTicketAuthority.digest(bearer) + ":" + revision + ":" + nonce)
                .getBytes(StandardCharsets.UTF_8));
        synchronized (replay) {
            cleanup(now);
            if (replay.size() >= capacity || replay.containsKey(replayKey)) return false;
            replay.put(replayKey, issuedAt.plus(ttl));
            return true;
        }
    }

    /** Canonical bytes clients sign; every variable field is length-prefixed. */
    public static byte[] payload(String bearer, long revision, String nonce, String jti,
                                 String method, String uri, Instant issuedAt) {
        var out = new ByteArrayOutputStream(256);
        put(out, "ravenroot-embed-pop-v1");
        put(out, EmbedLaunchTicketAuthority.digest(bearer));
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(revision).array());
        put(out, nonce);
        put(out, jti);
        put(out, method);
        put(out, uri);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(issuedAt.toEpochMilli()).array());
        return out.toByteArray();
    }

    /** Canonical exchange proof additionally binds the parent-acknowledged channel and correlation. */
    public static byte[] exchangePayload(String exchangeId, long revision, String nonce,
                                         String channelId, String acknowledgementCorrelationId,
                                         String jti, String method, String uri, Instant issuedAt) {
        var out = new ByteArrayOutputStream(320);
        put(out, "ravenroot-embed-pop-ack-v1");
        put(out, EmbedLaunchTicketAuthority.digest(exchangeId));
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(revision).array());
        put(out, nonce);
        put(out, channelId);
        put(out, acknowledgementCorrelationId);
        put(out, jti);
        put(out, method);
        put(out, uri);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(issuedAt.toEpochMilli()).array());
        return out.toByteArray();
    }

    int retainedReplayEntries() {
        synchronized (replay) {
            cleanup(clock.instant());
            return replay.size();
        }
    }

    private void cleanup(Instant now) {
        replay.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }

    private static void put(ByteArrayOutputStream out, String value) {
        byte[] bytes = Objects.requireNonNull(value, "proof field").getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    private static boolean isP256(ECPublicKey key) {
        ECParameterSpec actual = key.getParams();
        return actual != null && actual.getCurve().equals(P256.getCurve())
                && actual.getGenerator().equals(P256.getGenerator())
                && actual.getOrder().equals(P256.getOrder())
                && actual.getCofactor() == P256.getCofactor();
    }

    static ECParameterSpec parameters() {
        return P256;
    }

    private static ECParameterSpec p256Parameters() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
