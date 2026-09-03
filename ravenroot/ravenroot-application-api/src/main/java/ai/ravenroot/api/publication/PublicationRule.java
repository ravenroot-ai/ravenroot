package ai.ravenroot.api.publication;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Closed declarative rule model understood by Ravenroot's standard publication evaluator.
 * Graph content can select a profile but cannot supply a rule or executable predicate.
 */
public sealed interface PublicationRule permits PublicationRule.Destination, PublicationRule.LogicalPath,
        PublicationRule.SensitiveContent, PublicationRule.Language, PublicationRule.ArtifactType,
        PublicationRule.RequiredFilePair, PublicationRule.Provenance {

    /**
     * Stable identity emitted when this rule refuses a candidate.
     *
     * @return the stable rule identity
     */
    PublicationRuleId id();

    /**
     * Exact destination allowlist. Empty sets deny every destination.
     *
     * @param id stable rule identity
     * @param allowedTypes exact provider-neutral destination types
     * @param allowedAddresses exact provider-neutral destination addresses
     */
    record Destination(PublicationRuleId id, Set<String> allowedTypes,
                       Set<String> allowedAddresses) implements PublicationRule {
        /** Normalizes allowlists into sorted immutable sets. */
        public Destination {
            id = requireId(id);
            allowedTypes = tokens(allowedTypes, 128, "destination type");
            allowedAddresses = boundedStrings(allowedAddresses, 2_048, "destination address");
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("Destination", id); }
    }

    /**
     * Generic logical-path constraints; no host filesystem semantics are consulted.
     *
     * @param id stable rule identity
     * @param privatePrefixes normalized logical prefixes that must be refused
     * @param denyAbsolute whether absolute logical paths are refused
     * @param denyParentTraversal whether parent-traversal segments are refused
     * @param denyHomeRelative whether home-relative logical paths are refused
     */
    record LogicalPath(PublicationRuleId id, Set<String> privatePrefixes, boolean denyAbsolute,
                       boolean denyParentTraversal, boolean denyHomeRelative) implements PublicationRule {
        /** Normalizes configured prefixes to forward-slash form. */
        public LogicalPath {
            id = requireId(id);
            var normalized = new TreeSet<String>();
            for (String prefix : privatePrefixes == null ? Set.<String>of() : privatePrefixes) {
                String value = bounded(prefix, 2_048, "private path prefix").replace('\\', '/');
                normalized.add(value);
            }
            privatePrefixes = Set.copyOf(normalized);
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("LogicalPath", id); }
    }

    /** Content categories that can be scanned by one generic sensitive-signature rule. */
    enum SensitiveKind {
        /** Secret material such as bearer tokens or private keys. */ SECRET,
        /** Credential syntax such as passwords or authorization headers. */ CREDENTIAL,
        /** Private names or stable identifiers. */ PRIVATE_IDENTIFIER,
        /** References to private systems or repositories. */ PRIVATE_REFERENCE
    }

    /** How a configured literal is matched after bounded normalization. */
    enum MatchMode {
        /** Literal may occur anywhere. */ SUBSTRING,
        /** Literal must have non-identifier boundaries on both sides. */ TOKEN,
        /** Literal must begin at a token boundary. */ PREFIX
    }

    /**
     * One immutable literal signature.
     *
     * @param literal bounded protected literal
     * @param mode match semantics applied after normalization
     */
    record Signature(String literal, MatchMode mode) {
        /** Requires a bounded non-blank literal and explicit match mode. */
        public Signature {
            literal = bounded(literal, 512, "sensitive signature");
            mode = mode == null ? MatchMode.TOKEN : mode;
        }

        /** @return a redacted summary without the protected literal */
        @Override public String toString() { return "PublicationRule.Signature[literal=redacted]"; }
    }

    /**
     * Bounded text scanning with optional encoding, fragment, and confusable handling.
     *
     * @param id stable rule identity
     * @param kind protected content category
     * @param signatures immutable signatures to scan for
     * @param inspectEncodings whether bounded encoded representations are inspected
     * @param joinFragments whether signatures split across fragment boundaries are inspected
     * @param inspectConfusables whether a bounded confusable skeleton is inspected
     * @param maxNormalizedCharacters maximum normalized characters inspected per resource
     */
    record SensitiveContent(PublicationRuleId id, SensitiveKind kind, List<Signature> signatures,
                            boolean inspectEncodings, boolean joinFragments,
                            boolean inspectConfusables, int maxNormalizedCharacters) implements PublicationRule {
        /** Validates scan budgets and takes an immutable signature snapshot. */
        public SensitiveContent {
            id = requireId(id);
            kind = kind == null ? SensitiveKind.SECRET : kind;
            signatures = List.copyOf(signatures == null ? List.of() : signatures);
            if (signatures.isEmpty() || signatures.size() > 1_024 || signatures.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("sensitive rule requires between 1 and 1024 signatures");
            }
            if (maxNormalizedCharacters < 1 || maxNormalizedCharacters > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("normalized scan budget is outside the supported range");
            }
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("SensitiveContent", id); }
    }

    /**
     * Exact, case-insensitive BCP-47-style language allowlist.
     *
     * @param id stable rule identity
     * @param allowedLanguages allowed normalized language tags
     * @param allowSubtags whether a base tag also allows its subtags
     */
    record Language(PublicationRuleId id, Set<String> allowedLanguages,
                    boolean allowSubtags) implements PublicationRule {
        /** Normalizes configured language tags with the root locale. */
        public Language {
            id = requireId(id);
            var normalized = new TreeSet<String>();
            for (String language : allowedLanguages == null ? Set.<String>of() : allowedLanguages) {
                String value = bounded(language, 63, "language").toLowerCase(Locale.ROOT);
                if (!value.matches("[a-z0-9]{1,8}(-[a-z0-9]{1,8})*")) {
                    throw new IllegalArgumentException("language must be a canonical language tag");
                }
                normalized.add(value);
            }
            allowedLanguages = Set.copyOf(normalized);
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("Language", id); }
    }

    /**
     * Exact artifact-type allowlist plus an explicit binary-content decision.
     *
     * @param id stable rule identity
     * @param allowedTypes exact provider-neutral artifact types
     * @param allowBinary whether binary content can pass this rule
     */
    record ArtifactType(PublicationRuleId id, Set<String> allowedTypes,
                        boolean allowBinary) implements PublicationRule {
        /** Normalizes artifact identifiers. */
        public ArtifactType {
            id = requireId(id);
            allowedTypes = tokens(allowedTypes, 128, "artifact type");
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("ArtifactType", id); }
    }

    /**
     * Requires a path with {@code firstSuffix} to have the same stem with {@code requiredSuffix}.
     *
     * @param id stable rule identity
     * @param firstSuffix suffix that activates the companion requirement
     * @param requiredSuffix suffix required on the companion resource
     */
    record RequiredFilePair(PublicationRuleId id, String firstSuffix,
                            String requiredSuffix) implements PublicationRule {
        /** Validates bounded non-equal suffixes. */
        public RequiredFilePair {
            id = requireId(id);
            firstSuffix = bounded(firstSuffix, 128, "first file suffix");
            requiredSuffix = bounded(requiredSuffix, 128, "required file suffix");
            if (firstSuffix.equals(requiredSuffix)) {
                throw new IllegalArgumentException("required file-pair suffixes must differ");
            }
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("RequiredFilePair", id); }
    }

    /**
     * Requires complete provenance and optionally constrains producer families.
     *
     * @param id stable rule identity
     * @param allowedSourceTypes allowed provider-neutral producer families
     */
    record Provenance(PublicationRuleId id, Set<String> allowedSourceTypes) implements PublicationRule {
        /** Normalizes producer-family identifiers. */
        public Provenance {
            id = requireId(id);
            allowedSourceTypes = tokens(allowedSourceTypes, 128, "provenance source type");
        }

        /** @return a redacted summary containing only the safe rule identity */
        @Override public String toString() { return summary("Provenance", id); }
    }

    private static String summary(String type, PublicationRuleId id) {
        return "PublicationRule." + type + "[id=" + id.value() + ", protectedValues=redacted]";
    }

    private static PublicationRuleId requireId(PublicationRuleId id) {
        return java.util.Objects.requireNonNull(id, "rule id");
    }

    private static Set<String> tokens(Set<String> source, int maximum, String name) {
        var result = new TreeSet<String>();
        for (String value : source == null ? Set.<String>of() : source) {
            String token = bounded(value, maximum, name);
            if (!token.matches("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}")) {
                throw new IllegalArgumentException(name + " must be a bounded canonical identifier");
            }
            result.add(token);
        }
        return Set.copyOf(result);
    }

    private static Set<String> boundedStrings(Set<String> source, int maximum, String name) {
        var result = new TreeSet<String>();
        for (String value : source == null ? Set.<String>of() : source) {
            result.add(bounded(value, maximum, name));
        }
        return Set.copyOf(result);
    }

    private static String bounded(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximum + " characters");
        }
        return value;
    }
}
