package ai.ravenroot.core.publication;

import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationContent;
import ai.ravenroot.api.publication.PublicationPolicy;
import ai.ravenroot.api.publication.PublicationResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** Bounds publication candidates and binds their resources without retaining their values. */
public final class PublicationCandidateMetrics {
    private PublicationCandidateMetrics() {
    }

    /** Safe classification of a measurement refusal. */
    public enum Failure { /** Candidate exceeds a byte ceiling. */ TOO_LARGE,
        /** Candidate has malformed text or binary encoding. */ MALFORMED }

    /** Fixed-message exception carrying no candidate-derived content. */
    public static final class MeasurementException extends IllegalArgumentException {
        private final Failure failure;

        MeasurementException(Failure failure) {
            super(failure == Failure.TOO_LARGE
                    ? "publication candidate exceeds its byte limit"
                    : "publication candidate encoding is malformed");
            this.failure = failure;
        }

        /** Safe failure classifier. */
        public Failure failure() { return failure; }
    }

    /** Bounded metadata and exact resource digest. */
    public record Measurement(long bytes, int resourceCount, String resourceDigest) { }

    /**
     * Measures before decoding, then computes a digest only after both hard and policy ceilings pass.
     */
    public static Measurement measure(PublicationCandidate candidate, long policyLimit) {
        if (candidate == null || policyLimit < 1 || policyLimit > PublicationPolicy.HARD_MAX_CANDIDATE_BYTES) {
            throw new MeasurementException(Failure.MALFORMED);
        }
        long limit = Math.min(policyLimit, PublicationPolicy.HARD_MAX_CANDIDATE_BYTES);
        long total = 0;
        total = add(total, utf8Length(candidate.contract()), limit);
        total = add(total, utf8Length(candidate.destination().type()), limit);
        total = add(total, utf8Length(candidate.destination().address()), limit);
        int fragments = 0;
        for (PublicationResource resource : candidate.resources()) {
            total = add(total, utf8Length(resource.logicalPath()), limit);
            total = add(total, utf8Length(resource.artifactType()), limit);
            total = add(total, utf8Length(resource.mediaType()), limit);
            total = add(total, utf8Length(resource.language()), limit);
            switch (resource.content()) {
                case PublicationContent.Text text -> {
                    fragments = addFragments(fragments, text.fragments().size());
                    for (String fragment : text.fragments()) {
                        total = add(total, utf8Length(fragment), limit);
                    }
                }
                case PublicationContent.Base64Binary binary -> {
                    fragments = addFragments(fragments, binary.fragments().size());
                    for (String fragment : binary.fragments()) {
                        total = add(total, decodedLengthBeforeValidation(fragment), limit);
                        validateBase64(fragment);
                    }
                }
            }
        }
        if (candidate.provenance() != null) {
            total = add(total, utf8Length(candidate.provenance().sourceType()), limit);
            total = add(total, utf8Length(candidate.provenance().sourceId()), limit);
            total = add(total, utf8Length(candidate.provenance().sourceVersion()), limit);
            total = add(total, utf8Length(candidate.provenance().contentDigest()), limit);
        }
        return new Measurement(total, candidate.resources().size(), digestResources(candidate.resources()));
    }

    private static int addFragments(int current, int added) {
        if (added < 1 || current > 4_096 - added) throw new MeasurementException(Failure.MALFORMED);
        return current + added;
    }

    private static long add(long total, long amount, long limit) {
        if (amount < 0 || total > limit - amount) throw new MeasurementException(Failure.TOO_LARGE);
        return total + amount;
    }

    private static long utf8Length(String value) {
        if (value == null) throw new MeasurementException(Failure.MALFORMED);
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) bytes++;
            else if (character <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new MeasurementException(Failure.MALFORMED);
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(character)) {
                throw new MeasurementException(Failure.MALFORMED);
            } else bytes += 3;
        }
        return bytes;
    }

    private static long decodedLengthBeforeValidation(String value) {
        if (value == null || value.length() > PublicationPolicy.HARD_MAX_CANDIDATE_BYTES * 2L) {
            throw new MeasurementException(Failure.TOO_LARGE);
        }
        int length = value.length();
        if (length == 0) return 0;
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        int unpadded = length - padding;
        if (unpadded % 4 == 1 || padding > 0 && length % 4 != 0) {
            throw new MeasurementException(Failure.MALFORMED);
        }
        return (unpadded * 6L) / 8L;
    }

    private static void validateBase64(String value) {
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        int unpadded = value.length() - padding;
        for (int index = 0; index < unpadded; index++) {
            char c = value.charAt(index);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '+' && c != '/' && c != '-' && c != '_') {
                throw new MeasurementException(Failure.MALFORMED);
            }
        }
        for (int index = unpadded; index < value.length(); index++) {
            if (value.charAt(index) != '=') throw new MeasurementException(Failure.MALFORMED);
        }
    }

    private static String digestResources(java.util.List<PublicationResource> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            integer(digest, resources.size());
            for (PublicationResource resource : resources) {
                field(digest, resource.logicalPath());
                field(digest, resource.artifactType());
                field(digest, resource.mediaType());
                field(digest, resource.language());
                switch (resource.content()) {
                    case PublicationContent.Text text -> {
                        field(digest, "text");
                        integer(digest, text.fragments().size());
                        text.fragments().forEach(fragment -> field(digest, fragment));
                    }
                    case PublicationContent.Base64Binary binary -> {
                        field(digest, "binary");
                        integer(digest, binary.fragments().size());
                        for (String fragment : binary.fragments()) bytes(digest, decode(fragment));
                    }
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required for publication provenance", impossible);
        }
    }

    private static byte[] decode(String value) {
        try {
            boolean url = value.indexOf('-') >= 0 || value.indexOf('_') >= 0;
            return (url ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(value);
        } catch (IllegalArgumentException malformed) {
            throw new MeasurementException(Failure.MALFORMED);
        }
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        bytes(digest, bytes);
    }

    private static void bytes(MessageDigest digest, byte[] bytes) {
        integer(digest, bytes.length);
        digest.update(bytes);
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }
}
