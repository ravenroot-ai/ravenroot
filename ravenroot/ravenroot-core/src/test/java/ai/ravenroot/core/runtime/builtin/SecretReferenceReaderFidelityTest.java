package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every built-in reader of a {@code SECRET_REFERENCE} property hands the authored value downstream
 * unchanged.
 *
 * <h2>What this exists to catch, and why it is not a schema test</h2>
 * <p>Two built-in nodes declared {@code credentialRef} at the same type and read it two ways: the
 * HTTP node applied {@code trim()}, the LLM node did not. The collision that divergence invites was
 * unreachable for a padded reference, but only because {@link ai.ravenroot.core.runtime.BehaviorPropertySchema}
 * refuses whitespace in this type — a fact about a different file, which nothing in either node
 * stated and no test observed. That is the shape {@code docs/qa/what-the-testkits-do-not-cover.md}
 * calls inert by virtue of X rather than by construction: it decays the moment X is relaxed for an
 * unrelated reason, and it decays silently.</p>
 *
 * <h2>One reader is left, and that is exactly why the roster case matters more, not less</h2>
 * <p>{@code llm-prompt} and {@code agent} left the core with the AI nodes (ADR 0029), so
 * {@code http-request} is the only built-in that declares this type today. The two cases against it
 * are unchanged. What must not be concluded from "only one reader" is that comparing readers has
 * stopped mattering: the divergence this file exists for is created by the <em>second</em> reader,
 * and {@link #theBuiltInNodesDeclaringThisTypeAreExactlyTheOnesPinnedAbove} is what makes the second
 * one arrive with a fidelity case attached instead of arriving unobserved. Deleting that case along
 * with the two nodes would have left this file guarding one reader against nothing.</p>
 *
 * <p>So these cases deliberately <strong>bypass the schema</strong>. They construct the node and call
 * the factory directly, which is exactly the world in which the whitespace refusal has been relaxed.
 * In that world a normalising reader is a collision — {@code " a"}, {@code "a "} and {@code "a"} are
 * three references and derive three distinct keys — and these assertions are what turns
 * red. The dependency on the mask is therefore not documented, it is removed: the readers are pinned
 * to a property that holds whatever the mask admits.</p>
 *
 * <p>Both assertions are byte-for-byte, which also constrains the <em>repair</em> and not only the
 * defect. The plausible wrong fix is to pick one normalisation and apply it in a single shared place;
 * that satisfies "the readers agree" and reddens every case here, because a normalisation upstream of
 * an injective encoder is many-to-one wherever it is spelled. See
 * {@link NodePropertyType#SECRET_REFERENCE} for the decision and its reasoning.</p>
 */
class SecretReferenceReaderFidelityTest {

    /** A reference the schema refuses today: the counterfactual in which the mask has been relaxed. */
    private static final String PADDED = " acme-main ";

    /**
     * A reference the schema does <em>not</em> refuse today, and the reachable half of the divergence.
     * {@code BehaviorPropertySchema.validateProperty} treats a blank value as absent and returns
     * before the type check runs, so an all-whitespace {@code credentialRef} reaches both readers on
     * an admitted graph. The HTTP node trimmed it to empty and sent the request with no credential at
     * all; the LLM node handed it to the provider. Silently dropping a credential the author asked
     * for is the worse of the two, so this is not only an inconsistency.
     */
    private static final String BLANK_BUT_PRESENT = "   ";

    private static final ToolPolicy ALLOW_ALL =
            invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");

    @Test
    void theHttpNodeAsksTheResolverForThePaddedReferenceItWasGiven() {
        assertEquals(List.of(PADDED), httpReferencesAskedFor(PADDED),
                "the HTTP node must resolve the characters the author wrote; trimming here maps three "
                        + "distinct references onto one key");
    }

    @Test
    void theHttpNodeAsksTheResolverForAWhitespaceOnlyReferenceRatherThanDroppingIt() {
        assertEquals(List.of(BLANK_BUT_PRESENT), httpReferencesAskedFor(BLANK_BUT_PRESENT),
                "a present-but-blank credentialRef must fail to resolve, loudly, rather than become an "
                        + "unauthenticated request the author never asked for");
    }

    /**
     * The roster this file covers is the whole roster.
     *
     * <p>Reader fidelity is a property of the <em>type</em>, so a built-in node added later that
     * declares {@code SECRET_REFERENCE} is an unguarded reader the moment it exists. Pinning the set
     * makes adding one fail here, with a message saying what the author has to do, instead of adding
     * a third normalisation nobody compares against the other two — which is precisely how the first
     * two drifted apart.</p>
     */
    @Test
    void theBuiltInNodesDeclaringThisTypeAreExactlyTheOnesPinnedAbove() {
        var declaring = StandardBehaviorFactories.all(BehaviorEnvironment.safeDefaults()).stream()
                .map(NodeBehaviorFactory::descriptor)
                .filter(descriptor -> descriptor.properties().stream()
                        .anyMatch(property -> property.type() == NodePropertyType.SECRET_REFERENCE))
                .map(NodeTypeDescriptor::behavior)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(new TreeSet<>(Set.of("http-request")), declaring,
                "a built-in node declaring a SECRET_REFERENCE property must be given a fidelity case "
                        + "in this file: reading one verbatim is a rule of the type, not of this node. "
                        + "The set shrank to one when llm-prompt and agent left the core; it is "
                        + "still pinned, because the next node to declare this type must arrive with a "
                        + "case here rather than with a third normalisation nobody compared");
    }

    // ------------------------------------------------------------------ drivers

    /**
     * Every reference the HTTP node's credential branch asked the resolver for.
     *
     * <p>The resolver answers empty, so the node raises its own {@code SecurityException} and the
     * request is never built. The outbound policy additionally caps a request body at one byte while
     * the node declares a non-empty one: if a regression skips the credential branch entirely — which
     * is what {@code trim()} did for a blank-but-present reference — the run still stops before any
     * socket is opened, so the case fails on the assertion below rather than on the network.</p>
     */
    private static List<String> httpReferencesAskedFor(String authored) {
        var asked = new ArrayList<String>();
        CredentialResolver capturing = reference -> {
            asked.add(reference);
            return Optional.empty();
        };
        var policy = new OutboundHttpPolicy(Set.of("secret-ref-458.invalid"), Duration.ofSeconds(1),
                Set.of(443), 1024L, 1L);
        var handler = new HttpRequestNodeBehaviorFactory(policy, capturing, ALLOW_ALL)
                .create(new GraphNode("http", NodeKind.BEHAVIOR, "http-request", Map.of(
                        "url", "https://secret-ref-458.invalid/probe",
                        "body", "no request may leave this test",
                        "credentialRef", authored)));

        assertThrows(SecurityException.class, () -> handler.handle(message()));
        return asked;
    }

    private static NodeMessage message() {
        return new NodeMessage(
                new SecurityContext("request-458", "tenant-a", "alice", PrincipalType.USER,
                        "urn:ravenroot:test"),
                UUID.randomUUID(), UUID.randomUUID(), "node-458", "payload", Map.of());
    }
}
