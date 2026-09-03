package ai.ravenroot.api.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Immutable publication policy profile whose digest covers every effective field. */
public final class PublicationPolicy {
    /** Absolute ceiling no operator profile may relax. */
    public static final long HARD_MAX_CANDIDATE_BYTES = 16L * 1024 * 1024;

    private final PublicationPolicyReference reference;
    private final long maxCandidateBytes;
    private final List<PublicationRule> rules;

    /**
     * Creates a profile and computes its canonical digest.
     *
     * @param id stable operator-owned profile identifier
     * @param version immutable operator-owned version
     * @param maxCandidateBytes effective decoded candidate byte limit
     * @param rules ordered declarative rules; order is significant because evaluation stops at the first violation
     */
    public PublicationPolicy(String id, String version, long maxCandidateBytes, List<PublicationRule> rules) {
        if (maxCandidateBytes < 1 || maxCandidateBytes > HARD_MAX_CANDIDATE_BYTES) {
            throw new IllegalArgumentException("candidate byte limit is outside the supported range");
        }
        this.rules = List.copyOf(rules == null ? List.of() : rules);
        if (this.rules.isEmpty() || this.rules.size() > 4_096
                || this.rules.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("publication policy requires between 1 and 4096 rules");
        }
        var provisional = new PublicationPolicyReference(id, version, "sha256:" + "0".repeat(64));
        this.maxCandidateBytes = maxCandidateBytes;
        this.reference = new PublicationPolicyReference(id, version,
                "sha256:" + sha256(canonical(provisional.id(), provisional.version(), maxCandidateBytes, this.rules)));
    }

    /**
     * Exact immutable identity and digest of this policy.
     *
     * @return the policy identity and canonical digest
     */
    public PublicationPolicyReference reference() {
        return reference;
    }

    /**
     * Effective decoded candidate byte ceiling.
     *
     * @return the decoded byte ceiling
     */
    public long maxCandidateBytes() {
        return maxCandidateBytes;
    }

    /**
     * Ordered immutable rules.
     *
     * @return the ordered rules
     */
    public List<PublicationRule> rules() {
        return rules;
    }

    private static byte[] canonical(String id, String version, long maxBytes, List<PublicationRule> rules) {
        var value = new StringBuilder("ravenroot.publication-policy/1;");
        field(value, id);
        field(value, version);
        value.append(maxBytes).append(';').append(rules.size()).append(';');
        for (PublicationRule rule : rules) appendRule(value, rule);
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRule(StringBuilder out, PublicationRule rule) {
        field(out, rule.getClass().getSimpleName());
        field(out, rule.id().value());
        switch (rule) {
            case PublicationRule.Destination destination -> {
                strings(out, destination.allowedTypes());
                strings(out, destination.allowedAddresses());
            }
            case PublicationRule.LogicalPath path -> {
                strings(out, path.privatePrefixes());
                flags(out, path.denyAbsolute(), path.denyParentTraversal(), path.denyHomeRelative());
            }
            case PublicationRule.SensitiveContent sensitive -> {
                field(out, sensitive.kind().name());
                out.append(sensitive.signatures().size()).append(';');
                for (PublicationRule.Signature signature : sensitive.signatures()) {
                    field(out, signature.literal());
                    field(out, signature.mode().name());
                }
                flags(out, sensitive.inspectEncodings(), sensitive.joinFragments(), sensitive.inspectConfusables());
                out.append(sensitive.maxNormalizedCharacters()).append(';');
            }
            case PublicationRule.Language language -> {
                strings(out, language.allowedLanguages());
                flags(out, language.allowSubtags());
            }
            case PublicationRule.ArtifactType artifact -> {
                strings(out, artifact.allowedTypes());
                flags(out, artifact.allowBinary());
            }
            case PublicationRule.RequiredFilePair pair -> {
                field(out, pair.firstSuffix());
                field(out, pair.requiredSuffix());
            }
            case PublicationRule.Provenance provenance -> strings(out, provenance.allowedSourceTypes());
        }
    }

    private static void strings(StringBuilder out, Set<String> values) {
        var sorted = new java.util.TreeSet<>(values);
        out.append(sorted.size()).append(';');
        sorted.forEach(value -> field(out, value));
    }

    private static void flags(StringBuilder out, boolean... flags) {
        for (boolean flag : flags) out.append(flag ? '1' : '0');
        out.append(';');
    }

    private static void field(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required for publication policy identity", impossible);
        }
    }
}
