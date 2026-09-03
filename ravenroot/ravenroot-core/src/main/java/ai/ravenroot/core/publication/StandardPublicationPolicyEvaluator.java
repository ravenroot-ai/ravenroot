package ai.ravenroot.core.publication;

import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationContent;
import ai.ravenroot.api.publication.PublicationDecision;
import ai.ravenroot.api.publication.PublicationPolicy;
import ai.ravenroot.api.publication.PublicationPolicyEvaluator;
import ai.ravenroot.api.publication.PublicationResource;
import ai.ravenroot.api.publication.PublicationRule;
import ai.ravenroot.api.publication.PublicationRuleId;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic, side-effect-free evaluator for the public declarative rule model. */
public final class StandardPublicationPolicyEvaluator implements PublicationPolicyEvaluator {
    private static final int MAX_LOGICAL_PATH_CHARACTERS = 2_048;
    private static final int MAX_PATH_CANONICALIZATION_ROUNDS = 4;
    private static final PublicationRuleId CONTINUE = new PublicationRuleId("boundary.continue");
    private static final PublicationRuleId CANDIDATE_SIZE = new PublicationRuleId("boundary.candidate.size");
    private static final PublicationRuleId CANDIDATE_MALFORMED = new PublicationRuleId("boundary.candidate.malformed");
    private static final PublicationRuleId PROVENANCE_INCOMPLETE = new PublicationRuleId("boundary.provenance.incomplete");
    private static final PublicationRuleId PROVENANCE_MISMATCH = new PublicationRuleId("boundary.provenance.mismatch");
    private static final PublicationRuleId CONTENT_UNSUPPORTED = new PublicationRuleId("boundary.content.unsupported");
    private static final Pattern BASE64_TOKEN = Pattern.compile("(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{8,4096}={0,2}(?![A-Za-z0-9+/_=-])");

    @Override
    public PublicationDecision evaluate(PublicationPolicy policy, PublicationCandidate candidate) {
        java.util.Objects.requireNonNull(policy, "policy");
        PublicationCandidateMetrics.Measurement measurement;
        try {
            measurement = PublicationCandidateMetrics.measure(candidate, policy.maxCandidateBytes());
        } catch (PublicationCandidateMetrics.MeasurementException failure) {
            return violation(policy, failure.failure() == PublicationCandidateMetrics.Failure.TOO_LARGE
                            ? CANDIDATE_SIZE : CANDIDATE_MALFORMED,
                    failure.failure() == PublicationCandidateMetrics.Failure.TOO_LARGE
                            ? PublicationDecision.Reason.CANDIDATE_TOO_LARGE
                            : PublicationDecision.Reason.CANDIDATE_MALFORMED, 0, 0);
        }
        if (candidate.provenance() == null || !candidate.provenance().complete()) {
            return violation(policy, PROVENANCE_INCOMPLETE, PublicationDecision.Reason.PROVENANCE_INCOMPLETE,
                    measurement.bytes(), measurement.resourceCount());
        }
        if (!constantTimeEquals(candidate.provenance().contentDigest(), measurement.resourceDigest())) {
            return violation(policy, PROVENANCE_MISMATCH, PublicationDecision.Reason.PROVENANCE_MISMATCH,
                    measurement.bytes(), measurement.resourceCount());
        }
        if (containsBinary(candidate) && policy.rules().stream()
                .filter(PublicationRule.ArtifactType.class::isInstance)
                .map(PublicationRule.ArtifactType.class::cast)
                .noneMatch(PublicationRule.ArtifactType::allowBinary)) {
            return violation(policy, CONTENT_UNSUPPORTED, PublicationDecision.Reason.CONTENT_UNSUPPORTED,
                    measurement.bytes(), measurement.resourceCount());
        }

        for (PublicationRule rule : policy.rules()) {
            PublicationDecision.Reason reason = evaluateRule(rule, candidate);
            if (reason != null) {
                return violation(policy, rule.id(), reason, measurement.bytes(), measurement.resourceCount());
            }
        }
        return new PublicationDecision(PublicationDecision.Disposition.CONTINUE, policy.reference(), CONTINUE,
                PublicationDecision.Reason.CONTINUED, measurement.bytes(), measurement.resourceCount());
    }

    private static PublicationDecision.Reason evaluateRule(PublicationRule rule, PublicationCandidate candidate) {
        return switch (rule) {
            case PublicationRule.Destination destination ->
                    destination.allowedTypes().contains(candidate.destination().type())
                            && destination.allowedAddresses().contains(candidate.destination().address())
                            ? null : PublicationDecision.Reason.DESTINATION_DENIED;
            case PublicationRule.LogicalPath paths -> candidate.resources().stream()
                    .anyMatch(resource -> pathDenied(paths, resource.logicalPath()))
                    ? PublicationDecision.Reason.PATH_DENIED : null;
            case PublicationRule.SensitiveContent sensitive -> sensitive(sensitive, candidate);
            case PublicationRule.Language language -> candidate.resources().stream()
                    .anyMatch(resource -> !allowedLanguage(language, resource.language()))
                    ? PublicationDecision.Reason.LANGUAGE_DENIED : null;
            case PublicationRule.ArtifactType artifact -> candidate.resources().stream()
                    .anyMatch(resource -> !artifact.allowedTypes().contains(resource.artifactType())
                            || resource.content() instanceof PublicationContent.Base64Binary && !artifact.allowBinary())
                    ? PublicationDecision.Reason.ARTIFACT_DENIED : null;
            case PublicationRule.RequiredFilePair pair -> hasMissingPair(pair, candidate)
                    ? PublicationDecision.Reason.REQUIRED_FILE_MISSING : null;
            case PublicationRule.Provenance provenance -> provenance.allowedSourceTypes().isEmpty()
                    || !provenance.allowedSourceTypes().contains(candidate.provenance().sourceType())
                    ? PublicationDecision.Reason.PROVENANCE_DENIED : null;
        };
    }

    private static boolean pathDenied(PublicationRule.LogicalPath rule, String path) {
        CanonicalPath candidate = canonicalPath(path, false);
        if (!candidate.valid()
                || rule.denyAbsolute() && candidate.absolute()
                || rule.denyParentTraversal() && candidate.parentTraversal()
                || rule.denyHomeRelative() && candidate.homeRelative()) return true;
        for (String prefix : rule.privatePrefixes()) {
            CanonicalPath privatePrefix = canonicalPath(prefix, true);
            if (!privatePrefix.valid() || candidate.value().equals(privatePrefix.value())
                    || candidate.value().startsWith(privatePrefix.value() + "/")) {
                return true;
            }
        }
        return false;
    }

    private static CanonicalPath canonicalPath(String source, boolean prefix) {
        String normalized = canonicalizePathEncoding(source);
        if (normalized == null) return CanonicalPath.invalid();
        var separators = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length();) {
            int point = normalized.codePointAt(index);
            index += Character.charCount(point);
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT) {
                return CanonicalPath.invalid();
            }
            separators.appendCodePoint(separator(point) ? '/' : point);
        }
        String value = separators.toString();
        boolean absolute = value.startsWith("/") || value.length() >= 2
                && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
        boolean home = value.equals("~") || value.startsWith("~/");
        String body = value.startsWith("/") ? value.replaceFirst("^/+", "") : value;
        String[] components = body.split("/", -1);
        var canonical = new ArrayList<String>(components.length);
        boolean parent = false;
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            if (component.isEmpty()) {
                if (prefix && index == components.length - 1 && !canonical.isEmpty()) continue;
                return CanonicalPath.invalid();
            }
            if (component.equals(".")) continue;
            if (component.equals("..")) {
                parent = true;
                if (canonical.isEmpty()) return CanonicalPath.invalid();
                canonical.removeLast();
            } else {
                canonical.add(component);
            }
        }
        if (canonical.isEmpty()) return CanonicalPath.invalid();
        return new CanonicalPath(true, absolute, home, parent, String.join("/", canonical));
    }

    private static String canonicalizePathEncoding(String source) {
        String current = source;
        for (int round = 0; round < MAX_PATH_CANONICALIZATION_ROUNDS; round++) {
            String normalized = Normalizer.normalize(current, Normalizer.Form.NFKC);
            if (normalized.length() > MAX_LOGICAL_PATH_CHARACTERS) return null;
            String decoded = decodePercentTriplets(normalized);
            if (decoded == null || decoded.length() > MAX_LOGICAL_PATH_CHARACTERS) return null;
            if (decoded.equals(current)) {
                current = decoded;
                break;
            }
            current = decoded;
        }
        String stable = Normalizer.normalize(current, Normalizer.Form.NFKC);
        if (stable.length() > MAX_LOGICAL_PATH_CHARACTERS
                || !stable.equals(current) || containsPercentTriplet(current)) return null;
        return current;
    }

    private static String decodePercentTriplets(String value) {
        var decoded = new StringBuilder(value.length());
        boolean changed = false;
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) != '%' || index + 2 >= value.length()
                    || Character.digit(value.charAt(index + 1), 16) < 0
                    || Character.digit(value.charAt(index + 2), 16) < 0) {
                decoded.append(value.charAt(index++));
                continue;
            }
            var bytes = new ByteArrayOutputStream();
            while (index + 2 < value.length() && value.charAt(index) == '%') {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) break;
                bytes.write((high << 4) | low);
                index += 3;
            }
            try {
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                decoded.append(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())));
                changed = true;
            } catch (CharacterCodingException malformed) {
                return null;
            }
        }
        return changed ? decoded.toString() : value;
    }

    private static boolean containsPercentTriplet(String value) {
        for (int index = 0; index + 2 < value.length(); index++) {
            if (value.charAt(index) == '%'
                    && Character.digit(value.charAt(index + 1), 16) >= 0
                    && Character.digit(value.charAt(index + 2), 16) >= 0) return true;
        }
        return false;
    }

    private static boolean separator(int point) {
        return point == '/' || point == '\\' || point == 0x2044 || point == 0x2215
                || point == 0x29F5 || point == 0x29F8 || point == 0xFE68
                || point == 0xFF0F || point == 0xFF3C;
    }

    private static boolean allowedLanguage(PublicationRule.Language rule, String declared) {
        String language = declared == null ? "" : declared.toLowerCase(Locale.ROOT);
        if (rule.allowedLanguages().contains(language)) return true;
        if (!rule.allowSubtags()) return false;
        return rule.allowedLanguages().stream().anyMatch(allowed -> language.startsWith(allowed + "-"));
    }

    private static boolean hasMissingPair(PublicationRule.RequiredFilePair rule, PublicationCandidate candidate) {
        Set<String> paths = new HashSet<>();
        for (PublicationResource resource : candidate.resources()) {
            CanonicalPath path = canonicalPath(resource.logicalPath(), false);
            if (!path.valid()) return true;
            paths.add(path.value());
        }
        for (String path : paths) {
            if (path.endsWith(rule.firstSuffix())) {
                String peer = path.substring(0, path.length() - rule.firstSuffix().length()) + rule.requiredSuffix();
                if (!paths.contains(peer)) return true;
            }
        }
        return false;
    }

    private static PublicationDecision.Reason sensitive(PublicationRule.SensitiveContent rule,
                                                        PublicationCandidate candidate) {
        var normalizedSignatures = rule.signatures().stream()
                .map(signature -> new NormalizedSignature(normalize(signature.literal(), rule.inspectConfusables()),
                        signature.mode())).toList();
        for (PublicationResource resource : candidate.resources()) {
            if (resource.content() instanceof PublicationContent.Base64Binary) {
                return PublicationDecision.Reason.CONTENT_UNSUPPORTED;
            }
            var text = (PublicationContent.Text) resource.content();
            List<String> views = new ArrayList<>();
            if (rule.joinFragments()) {
                String joined = boundedJoin(text.fragments(), rule.maxNormalizedCharacters());
                if (joined == null) return PublicationDecision.Reason.CONTENT_UNSUPPORTED;
                views.add(joined);
                views.add(compact(joined));
            } else {
                views.addAll(text.fragments());
            }
            for (String view : views) {
                if (view.length() > rule.maxNormalizedCharacters()) {
                    return PublicationDecision.Reason.CONTENT_UNSUPPORTED;
                }
                String normalized = normalize(view, rule.inspectConfusables());
                if (matches(normalized, normalizedSignatures)) return PublicationDecision.Reason.SENSITIVE_CONTENT;
                if (rule.inspectEncodings()) {
                    String percent = percentDecode(view);
                    if (percent != null && matches(normalize(percent, rule.inspectConfusables()), normalizedSignatures)) {
                        return PublicationDecision.Reason.SENSITIVE_CONTENT;
                    }
                    if (hasOversizedEncodingToken(view)) {
                        return PublicationDecision.Reason.CONTENT_UNSUPPORTED;
                    }
                    var matcher = BASE64_TOKEN.matcher(view);
                    int decodedCharacters = 0;
                    while (matcher.find()) {
                        String decoded = base64Utf8(matcher.group());
                        if (decoded == null) continue;
                        decodedCharacters += decoded.length();
                        if (decodedCharacters > rule.maxNormalizedCharacters()) {
                            return PublicationDecision.Reason.CONTENT_UNSUPPORTED;
                        }
                        if (matches(normalize(decoded, rule.inspectConfusables()), normalizedSignatures)) {
                            return PublicationDecision.Reason.SENSITIVE_CONTENT;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String boundedJoin(List<String> fragments, int maximum) {
        long length = 0;
        for (String fragment : fragments) {
            length += fragment.length();
            if (length > maximum) return null;
        }
        var joined = new StringBuilder((int) length);
        fragments.forEach(joined::append);
        return joined.toString();
    }

    private static String compact(String text) {
        var compact = new StringBuilder(text.length());
        text.codePoints().filter(point -> !Character.isWhitespace(point)
                        && point != '\'' && point != '"' && point != '+' && point != '\\')
                .forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private static String normalize(String value, boolean confusables) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (!confusables) return normalized;
        var skeleton = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(point -> skeleton.appendCodePoint(skeleton(point)));
        return skeleton.toString();
    }

    private static int skeleton(int point) {
        return switch (point) {
            case 0x0430, 0x03B1 -> 'a'; // Cyrillic a, Greek alpha
            case 0x0435, 0x03B5 -> 'e';
            case 0x0456, 0x03B9 -> 'i';
            case 0x043E, 0x03BF -> 'o';
            case 0x0440, 0x03C1 -> 'p';
            case 0x0441, 0x03F2 -> 'c';
            case 0x0445, 0x03C7 -> 'x';
            case 0x0443, 0x03C5 -> 'y';
            case 0x0455 -> 's';
            case 0x04BB -> 'h';
            default -> point;
        };
    }

    private static boolean matches(String content, List<NormalizedSignature> signatures) {
        for (NormalizedSignature signature : signatures) {
            int from = 0;
            while (from <= content.length() - signature.literal().length()) {
                int index = content.indexOf(signature.literal(), from);
                if (index < 0) break;
                int end = index + signature.literal().length();
                boolean left = index == 0 || !identifier(content.codePointBefore(index));
                boolean right = end == content.length() || !identifier(content.codePointAt(end));
                if (signature.mode() == PublicationRule.MatchMode.SUBSTRING
                        || signature.mode() == PublicationRule.MatchMode.TOKEN && left && right
                        || signature.mode() == PublicationRule.MatchMode.PREFIX && left) return true;
                from = index + 1;
            }
        }
        return false;
    }

    private static boolean identifier(int point) {
        return Character.isLetterOrDigit(point) || point == '_' || point == '-';
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) return null;
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String base64Utf8(String value) {
        try {
            boolean url = value.indexOf('-') >= 0 || value.indexOf('_') >= 0;
            byte[] bytes = (url ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(value);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException malformed) {
            return null;
        }
    }

    private static boolean hasOversizedEncodingToken(String value) {
        int run = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '+' || character == '/'
                    || character == '_' || character == '-') {
                if (++run > 4_096) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }

    private static boolean containsBinary(PublicationCandidate candidate) {
        return candidate.resources().stream()
                .anyMatch(resource -> resource.content() instanceof PublicationContent.Base64Binary);
    }

    private static PublicationDecision violation(PublicationPolicy policy, PublicationRuleId rule,
                                                 PublicationDecision.Reason reason, long bytes, int count) {
        return new PublicationDecision(PublicationDecision.Disposition.VIOLATION, policy.reference(), rule,
                reason, bytes, count);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private record NormalizedSignature(String literal, PublicationRule.MatchMode mode) { }

    private record CanonicalPath(boolean valid, boolean absolute, boolean homeRelative,
                                 boolean parentTraversal, String value) {
        private static CanonicalPath invalid() {
            return new CanonicalPath(false, false, false, false, "");
        }
    }
}
