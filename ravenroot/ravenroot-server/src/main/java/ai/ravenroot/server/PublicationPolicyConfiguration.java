package ai.ravenroot.server;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.publication.PublicationPolicy;
import ai.ravenroot.api.publication.PublicationPolicyResolver;
import ai.ravenroot.api.publication.PublicationRule;
import ai.ravenroot.api.publication.PublicationRuleId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Closed operator-owned publication policy registry for the packaged server. */
final class PublicationPolicyConfiguration {
    static final String CONFIG_VARIABLE = "RAVENROOT_PUBLICATION_POLICY_CONFIG";
    private static final int MAX_CONFIG_BYTES = 1_048_576;

    private PublicationPolicyConfiguration() { }

    static PublicationPolicyResolver fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.getOrDefault(CONFIG_VARIABLE, "").strip();
        if (configured.isEmpty()) return PublicationPolicyResolver.none();
        byte[] bytes;
        try {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            long size = Files.size(path);
            if (size < 1 || size > MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException(CONFIG_VARIABLE + " file must be 1..1048576 bytes");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read " + CONFIG_VARIABLE, failure);
        }
        PayloadValue decoded = PayloadJson.read(bytes,
                new PayloadLimits(MAX_CONFIG_BYTES, 9, 32_768, 32_768, 524_288, 256));
        PayloadValue.MapValue root = map(decoded, "publication policy configuration");
        exact(root, Set.of("schemaVersion", "policies"));
        if (integer(root, "schemaVersion") != 1) {
            throw new IllegalArgumentException("unsupported publication policy configuration schema");
        }
        PayloadValue.ListValue entries = list(root, "policies");
        if (entries.values().isEmpty() || entries.values().size() > 1_024) {
            throw new IllegalArgumentException("publication policy configuration requires 1..1024 policies");
        }
        Map<Key, PublicationPolicy> policies = new LinkedHashMap<>();
        for (PayloadValue entry : entries.values()) {
            PayloadValue.MapValue value = map(entry, "publication policy");
            exact(value, Set.of("id", "version", "maxCandidateBytes", "rules"));
            var rules = new ArrayList<PublicationRule>();
            for (PayloadValue rule : list(value, "rules").values()) rules.add(rule(map(rule, "publication rule")));
            PublicationPolicy policy = new PublicationPolicy(text(value, "id"), text(value, "version"),
                    integer(value, "maxCandidateBytes"), rules);
            Key key = new Key(policy.reference().id(), policy.reference().version());
            if (policies.putIfAbsent(key, policy) != null) {
                throw new IllegalArgumentException("duplicate publication policy id and version");
            }
        }
        Map<Key, PublicationPolicy> immutable = Map.copyOf(policies);
        return (id, version) -> Optional.ofNullable(immutable.get(new Key(id, version)));
    }

    private static PublicationRule rule(PayloadValue.MapValue value) {
        String type = text(value, "type");
        var id = new PublicationRuleId(text(value, "id"));
        return switch (type) {
            case "destination" -> {
                exact(value, Set.of("type", "id", "allowedTypes", "allowedAddresses"));
                yield new PublicationRule.Destination(id, strings(value, "allowedTypes"), strings(value, "allowedAddresses"));
            }
            case "logical-path" -> {
                exact(value, Set.of("type", "id", "privatePrefixes", "denyAbsolute", "denyParentTraversal", "denyHomeRelative"));
                yield new PublicationRule.LogicalPath(id, strings(value, "privatePrefixes"),
                        bool(value, "denyAbsolute"), bool(value, "denyParentTraversal"), bool(value, "denyHomeRelative"));
            }
            case "sensitive-content" -> {
                exact(value, Set.of("type", "id", "kind", "signatures", "inspectEncodings", "joinFragments",
                        "inspectConfusables", "maxNormalizedCharacters"));
                var signatures = new ArrayList<PublicationRule.Signature>();
                for (PayloadValue entry : list(value, "signatures").values()) {
                    PayloadValue.MapValue signature = map(entry, "publication signature");
                    exact(signature, Set.of("literal", "mode"));
                    signatures.add(new PublicationRule.Signature(text(signature, "literal"),
                            PublicationRule.MatchMode.valueOf(text(signature, "mode").toUpperCase(Locale.ROOT))));
                }
                yield new PublicationRule.SensitiveContent(id,
                        PublicationRule.SensitiveKind.valueOf(text(value, "kind").toUpperCase(Locale.ROOT)),
                        signatures, bool(value, "inspectEncodings"), bool(value, "joinFragments"),
                        bool(value, "inspectConfusables"), Math.toIntExact(integer(value, "maxNormalizedCharacters")));
            }
            case "language" -> {
                exact(value, Set.of("type", "id", "allowedLanguages", "allowSubtags"));
                yield new PublicationRule.Language(id, strings(value, "allowedLanguages"), bool(value, "allowSubtags"));
            }
            case "artifact-type" -> {
                exact(value, Set.of("type", "id", "allowedTypes", "allowBinary"));
                yield new PublicationRule.ArtifactType(id, strings(value, "allowedTypes"), bool(value, "allowBinary"));
            }
            case "required-file-pair" -> {
                exact(value, Set.of("type", "id", "firstSuffix", "requiredSuffix"));
                yield new PublicationRule.RequiredFilePair(id, text(value, "firstSuffix"), text(value, "requiredSuffix"));
            }
            case "provenance" -> {
                exact(value, Set.of("type", "id", "allowedSourceTypes"));
                yield new PublicationRule.Provenance(id, strings(value, "allowedSourceTypes"));
            }
            default -> throw new IllegalArgumentException("unknown publication rule type");
        };
    }

    private static void exact(PayloadValue.MapValue value, Set<String> names) {
        if (!value.entries().keySet().equals(names)) throw new IllegalArgumentException("publication configuration has unknown or missing fields");
    }

    private static PayloadValue.MapValue map(PayloadValue value, String name) {
        if (value instanceof PayloadValue.MapValue map) return map;
        throw new IllegalArgumentException(name + " must be an object");
    }

    private static PayloadValue.ListValue list(PayloadValue.MapValue value, String name) {
        if (value.entries().get(name) instanceof PayloadValue.ListValue list) return list;
        throw new IllegalArgumentException(name + " must be a list");
    }

    private static String text(PayloadValue.MapValue value, String name) {
        if (value.entries().get(name) instanceof PayloadValue.TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(name + " must be non-blank text");
    }

    private static long integer(PayloadValue.MapValue value, String name) {
        if (value.entries().get(name) instanceof PayloadValue.IntegerValue integer) return integer.value();
        throw new IllegalArgumentException(name + " must be an integer");
    }

    private static boolean bool(PayloadValue.MapValue value, String name) {
        if (value.entries().get(name) instanceof PayloadValue.BooleanValue bool) return bool.value();
        throw new IllegalArgumentException(name + " must be a boolean");
    }

    private static Set<String> strings(PayloadValue.MapValue value, String name) {
        var result = new TreeSet<String>();
        for (PayloadValue entry : list(value, name).values()) {
            if (!(entry instanceof PayloadValue.TextValue text) || text.value().isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank text");
            }
            result.add(text.value());
        }
        return Set.copyOf(result);
    }

    private record Key(String id, String version) { }
}
