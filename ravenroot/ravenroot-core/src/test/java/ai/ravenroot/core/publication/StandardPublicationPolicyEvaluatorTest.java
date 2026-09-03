package ai.ravenroot.core.publication;

import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationContent;
import ai.ravenroot.api.publication.PublicationDecision;
import ai.ravenroot.api.publication.PublicationDestination;
import ai.ravenroot.api.publication.PublicationPolicy;
import ai.ravenroot.api.publication.PublicationProvenance;
import ai.ravenroot.api.publication.PublicationResource;
import ai.ravenroot.api.publication.PublicationRule;
import ai.ravenroot.api.publication.PublicationRuleId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StandardPublicationPolicyEvaluatorTest {
    private final StandardPublicationPolicyEvaluator evaluator = new StandardPublicationPolicyEvaluator();

    @Test
    void composesAllDeclarativeRuleFamiliesAndContinuesDeterministically() {
        var candidate = candidate(List.of(
                text("guide.md", "document", "en-GB", "Public guidance"),
                text("guide.notice", "notice", "en", "Copyright notice")));
        PublicationPolicy policy = policy(16_384, List.of(
                destination(), paths(), artifacts(false), languages(), provenance(),
                new PublicationRule.RequiredFilePair(id("pair.notice"), ".md", ".notice"),
                sensitive(PublicationRule.SensitiveKind.PRIVATE_REFERENCE,
                        new PublicationRule.Signature("private.example", PublicationRule.MatchMode.TOKEN))));

        PublicationDecision first = evaluator.evaluate(policy, candidate);
        PublicationDecision second = evaluator.evaluate(policy, candidate);

        assertEquals(PublicationDecision.Disposition.CONTINUE, first.disposition());
        assertEquals(first, second);
        assertEquals(policy.reference(), first.policy());
    }

    @Test
    void detectsRawEncodedSplitAndUnicodeConfusableSensitiveValues() {
        var signature = new PublicationRule.Signature("topsecret", PublicationRule.MatchMode.TOKEN);
        var policy = policy(16_384, List.of(destination(), artifacts(false), provenance(),
                sensitive(PublicationRule.SensitiveKind.SECRET, signature)));
        String encoded = Base64.getEncoder().encodeToString("topsecret".getBytes(StandardCharsets.UTF_8));

        assertReason(policy, candidate(List.of(text("a.txt", "document", "en", "topsecret"))),
                PublicationDecision.Reason.SENSITIVE_CONTENT);
        assertReason(policy, candidate(List.of(textFragments("a.txt", "top", "secret"))),
                PublicationDecision.Reason.SENSITIVE_CONTENT);
        assertReason(policy, candidate(List.of(text("a.txt", "document", "en", encoded))),
                PublicationDecision.Reason.SENSITIVE_CONTENT);

        var confusablePolicy = policy(16_384, List.of(destination(), artifacts(false), provenance(),
                sensitive(PublicationRule.SensitiveKind.CREDENTIAL,
                        new PublicationRule.Signature("token", PublicationRule.MatchMode.TOKEN))));
        assertReason(confusablePolicy, candidate(List.of(text("a.txt", "document", "en", "t\u043eken"))),
                PublicationDecision.Reason.SENSITIVE_CONTENT);
    }

    @Test
    void tokenBoundariesAvoidSubstringFalsePositives() {
        var policy = policy(16_384, List.of(destination(), artifacts(false), provenance(),
                sensitive(PublicationRule.SensitiveKind.PRIVATE_IDENTIFIER,
                        new PublicationRule.Signature("token", PublicationRule.MatchMode.TOKEN))));

        assertEquals(PublicationDecision.Disposition.CONTINUE,
                evaluator.evaluate(policy, candidate(List.of(text("a.txt", "document", "en", "tokenizer"))))
                        .disposition());
    }

    @Test
    void refusesTraversalPrivatePathsDestinationsLanguagesArtifactsAndMissingPairs() {
        assertReason(policy(16_384, List.of(destination(), paths(), artifacts(false), provenance())),
                candidate(List.of(text("../private/value.txt", "document", "en", "safe"))),
                PublicationDecision.Reason.PATH_DENIED);
        assertReason(policy(16_384, List.of(destination(), paths(), artifacts(false), provenance())),
                candidate(List.of(text("private/value.txt", "document", "en", "safe"))),
                PublicationDecision.Reason.PATH_DENIED);

        var wrongDestination = candidate(new PublicationDestination("repository", "urn:elsewhere"),
                List.of(text("guide.md", "document", "en", "safe")));
        assertReason(policy(16_384, List.of(destination(), artifacts(false), provenance())), wrongDestination,
                PublicationDecision.Reason.DESTINATION_DENIED);

        assertReason(policy(16_384, List.of(destination(), languages(), artifacts(false), provenance())),
                candidate(List.of(text("guide.md", "document", "it", "safe"))),
                PublicationDecision.Reason.LANGUAGE_DENIED);

        assertReason(policy(16_384, List.of(destination(), artifacts(false), provenance())),
                candidate(List.of(text("guide.md", "executable", "en", "safe"))),
                PublicationDecision.Reason.ARTIFACT_DENIED);

        assertReason(policy(16_384, List.of(destination(), artifacts(false), provenance(),
                        new PublicationRule.RequiredFilePair(id("pair.notice"), ".md", ".notice"))),
                candidate(List.of(text("guide.md", "document", "en", "safe"))),
                PublicationDecision.Reason.REQUIRED_FILE_MISSING);
    }

    @Test
    void canonicalizesPathAliasesBeforeEveryPathDecision() {
        var policy = policy(16_384, List.of(destination(), paths(), artifacts(false), provenance()));
        List<String> denied = List.of(
                "%2e%2e/private/file",
                "private%2Ffile",
                "..\uFF0Fprivate/file",
                "public/../private/file",
                "private\\file",
                "./private/file",
                "/public/file",
                "C:\\public\\file",
                "\\\\server\\share\\file",
                "~/public/file",
                "public//file",
                "public/" + Character.toString(0) + "file");
        for (String path : denied) {
            assertReason(policy, candidate(List.of(text(path, "document", "en", "safe"))),
                    PublicationDecision.Reason.PATH_DENIED);
        }

        for (String path : List.of("..-safe/file", "%2e%2e-safe/file", "private-file", "private%2Dfile",
                "privateish/file", "public/.well-known")) {
            assertEquals(PublicationDecision.Disposition.CONTINUE,
                    evaluator.evaluate(policy, candidate(List.of(text(path, "document", "en", "safe"))))
                            .disposition(), path);
        }
    }

    @Test
    void binaryRequiresExplicitPolicyAllowanceAndTextRulesFailClosedOnIt() {
        var binary = candidate(List.of(new PublicationResource("archive.bin", "binary", "application/octet-stream", "",
                new PublicationContent.Base64Binary(Base64.getEncoder().encodeToString(new byte[]{0, 1, 2})))));

        assertReason(policy(16_384, List.of(destination(), artifacts(false), provenance())), binary,
                PublicationDecision.Reason.CONTENT_UNSUPPORTED);
        var malformedBinary = new PublicationCandidate(new PublicationDestination("repository", "urn:public"),
                List.of(new PublicationResource("archive.bin", "binary", "application/octet-stream", "",
                        new PublicationContent.Base64Binary("%%%"))),
                new PublicationProvenance("build", "one", "v1", "sha256:" + "1".repeat(64)));
        assertReason(policy(16_384, List.of(destination(),
                        new PublicationRule.ArtifactType(id("artifact.allowed"), Set.of("binary"), true), provenance())),
                malformedBinary, PublicationDecision.Reason.CANDIDATE_MALFORMED);
        assertEquals(PublicationDecision.Disposition.CONTINUE,
                evaluator.evaluate(policy(16_384, List.of(destination(),
                        new PublicationRule.ArtifactType(id("artifact.allowed"), Set.of("binary"), true), provenance())),
                        binary).disposition());
        assertReason(policy(16_384, List.of(destination(),
                        new PublicationRule.ArtifactType(id("artifact.allowed"), Set.of("binary"), true), provenance(),
                        sensitive(PublicationRule.SensitiveKind.SECRET,
                                new PublicationRule.Signature("secret", PublicationRule.MatchMode.TOKEN)))),
                binary, PublicationDecision.Reason.CONTENT_UNSUPPORTED);
    }

    @Test
    void oversizedMalformedAndIncompleteProvenanceFailClosed() {
        var large = candidate(List.of(text("large.txt", "document", "en", "x".repeat(2_000))));
        assertReason(policy(512, List.of(destination(), artifacts(false), provenance())), large,
                PublicationDecision.Reason.CANDIDATE_TOO_LARGE);

        var missing = new PublicationCandidate(new PublicationDestination("repository", "urn:public"),
                List.of(text("a.txt", "document", "en", "safe")), null);
        assertReason(policy(16_384, List.of(destination(), artifacts(false), provenance())), missing,
                PublicationDecision.Reason.PROVENANCE_INCOMPLETE);

        var mismatch = new PublicationCandidate(new PublicationDestination("repository", "urn:public"),
                List.of(text("a.txt", "document", "en", "safe")),
                new PublicationProvenance("build", "one", "v1", "sha256:" + "1".repeat(64)));
        assertReason(policy(16_384, List.of(destination(), artifacts(false), provenance())), mismatch,
                PublicationDecision.Reason.PROVENANCE_MISMATCH);
    }

    @Test
    void guardPinsPolicyDigestAndContainsEvaluatorFailure() {
        PublicationPolicy first = policy(16_384, List.of(destination(), artifacts(false), provenance()));
        PublicationPolicy changed = policy(16_384, List.of(destination(), artifacts(true), provenance()));
        assertNotEquals(first.reference().digest(), changed.reference().digest());
        var candidate = candidate(List.of(text("a.txt", "document", "en", "safe")));

        var drift = new PublicationBoundaryGuard((id, version) -> java.util.Optional.of(changed), evaluator)
                .evaluate(first.reference(), candidate);
        assertEquals(PublicationDecision.Reason.POLICY_DIGEST_MISMATCH, drift.reason());

        var failed = new PublicationBoundaryGuard((id, version) -> java.util.Optional.of(first),
                (policy, value) -> { throw new IllegalStateException("protected-value"); })
                .evaluate(first.reference(), candidate);
        assertEquals(PublicationDecision.Reason.EVALUATOR_FAILED, failed.reason());
        assertEquals(false, failed.toString().contains("protected-value"));

        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var bounded = new PublicationBoundaryGuard((id, version) -> java.util.Optional.of(
                policy(512, List.of(destination(), artifacts(false), provenance()))), (policy, value) -> {
            calls.incrementAndGet();
            return evaluator.evaluate(policy, value);
        });
        PublicationPolicy smallPolicy = policy(512, List.of(destination(), artifacts(false), provenance()));
        var oversized = candidate(List.of(text("large.txt", "document", "en", "x".repeat(2_000))));
        assertEquals(PublicationDecision.Reason.CANDIDATE_TOO_LARGE,
                bounded.evaluate(smallPolicy.reference(), oversized).reason());
        assertEquals(0, calls.get(), "candidate size must be checked before evaluator invocation");

        var invalidReference = new PublicationBoundaryGuard((id, version) -> java.util.Optional.of(first), evaluator)
                .evaluate(null, candidate);
        assertEquals(PublicationDecision.Reason.POLICY_REFERENCE_INVALID, invalidReference.reason());
    }

    @Test
    void boundedEncodingInspectionFailsClosedOnOversizedTokens() {
        var policy = policy(16_384, List.of(destination(), artifacts(false), provenance(),
                sensitive(PublicationRule.SensitiveKind.SECRET,
                        new PublicationRule.Signature("protected", PublicationRule.MatchMode.TOKEN))));

        assertReason(policy, candidate(List.of(text("a.txt", "document", "en", "a".repeat(4_097)))),
                PublicationDecision.Reason.CONTENT_UNSUPPORTED);
    }

    private void assertReason(PublicationPolicy policy, PublicationCandidate candidate,
                              PublicationDecision.Reason reason) {
        PublicationDecision decision = evaluator.evaluate(policy, candidate);
        assertEquals(PublicationDecision.Disposition.VIOLATION, decision.disposition());
        assertEquals(reason, decision.reason());
    }

    private static PublicationPolicy policy(long max, List<PublicationRule> rules) {
        return new PublicationPolicy("public", "v1", max, rules);
    }

    private static PublicationRule destination() {
        return new PublicationRule.Destination(id("destination.public"), Set.of("repository"), Set.of("urn:public"));
    }

    private static PublicationRule paths() {
        return new PublicationRule.LogicalPath(id("path.public"), Set.of("private"), true, true, true);
    }

    private static PublicationRule artifacts(boolean allowBinary) {
        return new PublicationRule.ArtifactType(id("artifact.allowed"), Set.of("document", "notice"), allowBinary);
    }

    private static PublicationRule languages() {
        return new PublicationRule.Language(id("language.english"), Set.of("en"), true);
    }

    private static PublicationRule provenance() {
        return new PublicationRule.Provenance(id("provenance.build"), Set.of("build"));
    }

    private static PublicationRule sensitive(PublicationRule.SensitiveKind kind,
                                             PublicationRule.Signature signature) {
        return new PublicationRule.SensitiveContent(id("content." + kind.name().toLowerCase(java.util.Locale.ROOT)),
                kind, List.of(signature), true, true, true, 32_768);
    }

    private static PublicationResource text(String path, String type, String language, String content) {
        return new PublicationResource(path, type, "text/plain", language, new PublicationContent.Text(content));
    }

    private static PublicationResource textFragments(String path, String... fragments) {
        return new PublicationResource(path, "document", "text/plain", "en",
                new PublicationContent.Text(List.of(fragments)));
    }

    private static PublicationCandidate candidate(List<PublicationResource> resources) {
        return candidate(new PublicationDestination("repository", "urn:public"), resources);
    }

    private static PublicationCandidate candidate(PublicationDestination destination,
                                                  List<PublicationResource> resources) {
        var incomplete = new PublicationCandidate(destination, resources, null);
        String digest = PublicationCandidateMetrics.measure(incomplete,
                PublicationPolicy.HARD_MAX_CANDIDATE_BYTES).resourceDigest();
        return new PublicationCandidate(destination, resources,
                new PublicationProvenance("build", "one", "v1", digest));
    }

    private static PublicationRuleId id(String value) {
        return new PublicationRuleId(value);
    }
}
