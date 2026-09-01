package ai.ravenroot.server.assistant;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant reason vocabulary must be <b>total</b> and <b>injective</b> on the wire.
 *
 * <p>This is the test that exists because of a collapse that already happened: seven
 * {@link AssistantOutcome.Reason} values were mapped onto four {@link ErrorCode}s, so three
 * distinctions the service computes were unobservable to the panel — they survived only in the
 * server's own log. Four-into-seven is the class of defect this codebase keeps paying for, and the
 * fix is worth nothing unless the collapse cannot come back.</p>
 *
 * <p>Totality is the compiler's job: {@code wireToken} is a constructor argument on the enum, so a
 * reason added without one does not compile. Injectivity and grammar are this class's job, because
 * the compiler cannot see that two constants were handed the same string.</p>
 */
class AssistantReasonTokenTest {

    /**
     * <b>No two reasons share a token.</b>
     *
     * <p><b>Mutation proof.</b> Give {@code EGRESS_REFUSED} the token
     * {@code "ASSISTANT_PROVIDER_UNAVAILABLE"} — the plausible mistake, since both are "we did not
     * reach the provider" — and this test reds naming the count. That is precisely the collapse the
     * previous mapping made, at the granularity where it is easiest to make again.</p>
     */
    @Test
    void everyReasonHasItsOwnToken() {
        var tokens = Arrays.stream(AssistantOutcome.Reason.values())
                .map(AssistantOutcome.Reason::wireToken)
                .collect(Collectors.toSet());
        assertEquals(AssistantOutcome.Reason.values().length, tokens.size(),
                () -> "two reasons share a wire token, so the panel cannot tell them apart: "
                        + Arrays.stream(AssistantOutcome.Reason.values())
                        .map(reason -> reason.name() + "->" + reason.wireToken()).toList());
    }

    /**
     * <b>Every token is a bare upper-case token that survives the envelope's own grammar.</b>
     *
     * <p>This is what keeps the field a reason rather than a diagnostic: a token that cannot contain a
     * space, a quote, a URL or a colon cannot smuggle a configured endpoint, a model id or a fragment
     * of a provider's error body onto the wire. The grammar is checked here <em>and</em> enforced by
     * {@code ErrorEnvelope}, so a token that slipped past this test would still be dropped there
     * rather than emitted mangled.</p>
     *
     * <p><b>Mutation proof.</b> Change any token to include the configured endpoint — e.g.
     * {@code "ASSISTANT_PROVIDER_REFUSED api.anthropic.com"} — and both the grammar assertion here and
     * the round-trip assertion below red.</p>
     */
    @Test
    void everyTokenIsABareUpperCaseTokenAndCarriesNoDetail() {
        for (AssistantOutcome.Reason reason : AssistantOutcome.Reason.values()) {
            String token = reason.wireToken();
            assertNotNull(token, reason::name);
            assertTrue(token.matches("[A-Z][A-Z0-9_]*"),
                    () -> reason.name() + " has a token that is not a bare upper-case token: " + token);
            assertTrue(token.startsWith("ASSISTANT_"),
                    () -> reason.name() + " must be unmistakably the assistant's vocabulary: " + token);
            for (String forbidden : new String[] {" ", ":", "/", "\"", ".", "="}) {
                assertFalse(token.contains(forbidden),
                        () -> reason.name() + " token contains '" + forbidden + "', which is how a "
                                + "configuration or credential detail would reach the wire");
            }
        }
    }

    /**
     * <b>The token survives the envelope and lands beside the code rather than replacing it.</b>
     *
     * <p>Both members matter: {@code code} is what carries the HTTP status and what the route table
     * and the checked-in spec declare, and {@code assistantReason} is what carries the distinction the
     * status cannot. A change that put the reason into {@code code} would look like it worked and would
     * break the spec-agreement test instead of this one, so the relationship is asserted here.</p>
     */
    @Test
    void theTokenRidesTheEnvelopeBesideTheCode() {
        for (AssistantOutcome.Reason reason : AssistantOutcome.Reason.values()) {
            String json = ErrorEnvelope.of(ErrorCode.INTERNAL_ERROR, "correlation-1")
                    .withAssistantReason(reason.wireToken())
                    .toJson();
            assertTrue(json.contains("\"assistantReason\":\"" + reason.wireToken() + "\""),
                    () -> "the reason must survive the envelope intact: " + json);
            assertTrue(json.contains("\"code\":\"" + ErrorCode.INTERNAL_ERROR.code() + "\""),
                    () -> "the reason must sit beside the code, not replace it: " + json);
        }
    }

    /**
     * <b>The envelope enforces the grammar itself, rather than documenting it.</b>
     *
     * <p>This test exists because the claim came first and the enforcement did not. The field's Javadoc
     * said {@code safeToken} made it a bare upper-case token that "cannot carry configuration,
     * credential or provider-response detail" — but {@code safeToken} permits
     * {@code . - :} and lower case, so {@code api.anthropic.com}, {@code sk-ant-would-work} and
     * {@code ASSISTANT_PROVIDER_REFUSED:api.anthropic.com} all survived. Only a space was dropped. It
     * was a documented control that did not exist, and it was catalogued as such.</p>
     *
     * <p>The enum test above is the first layer and the real one; this is the second, and it is the
     * one the Javadoc promised. Each value below is a thing that would actually be damaging on the
     * wire, not an abstract bad string.</p>
     *
     * <p><b>Mutation proof.</b> Point the constructor back at {@code safeToken} and every assertion
     * here reds.</p>
     */
    @Test
    void theEnvelopeDropsAReasonThatCouldCarryDetail() {
        String[] mustBeDropped = {
            "api.anthropic.com",
            "sk-ant-would-work",
            "ASSISTANT_PROVIDER_REFUSED:api.anthropic.com",
            "ASSISTANT_PROVIDER_REFUSED.claude-opus-5",
            "assistant_provider_refused",
            "ASSISTANT-PROVIDER-REFUSED",
            "1_LEADING_DIGIT",
            "ASSISTANT_PROVIDER_REFUSED api.anthropic.com",
        };
        for (String detail : mustBeDropped) {
            var envelope = ErrorEnvelope.of(ErrorCode.INTERNAL_ERROR, "correlation-3")
                    .withAssistantReason(detail);
            assertNull(envelope.assistantReason(),
                    () -> "'" + detail + "' is not a bare reason token and must be dropped, not "
                            + "carried: the member exists to be a label, and anything that can spell a "
                            + "host, a model id or a key is a channel");
            assertFalse(envelope.toJson().contains("assistantReason"),
                    () -> "a dropped reason must leave no member behind: " + envelope.toJson());
        }
        assertEquals("ASSISTANT_PROVIDER_REFUSED",
                ErrorEnvelope.of(ErrorCode.INTERNAL_ERROR, "correlation-4")
                        .withAssistantReason("ASSISTANT_PROVIDER_REFUSED").assistantReason(),
                "a well-formed token must still pass, or the grammar is simply broken");
    }

    /**
     * An envelope with no assistant reason omits the member entirely.
     *
     * <p>Asserted because the alternative — emitting {@code "assistantReason":null} on every error the
     * product returns — would make a single feature's optional member part of every other surface's
     * response, and a client could not distinguish "not an assistant turn" from "an assistant turn
     * whose reason we failed to set".</p>
     */
    @Test
    void everyOtherSurfaceIsUnchanged() {
        String json = ErrorEnvelope.of(ErrorCode.ACCESS_DENIED, "correlation-2").toJson();
        assertFalse(json.contains("assistantReason"),
                () -> "a non-assistant error must not grow an assistant member: " + json);
    }

    /**
     * <b>The two transport-shaped reasons are retryable; nothing else is.</b>
     *
     * <p>{@code retryable} is a constructor argument on every {@link AssistantOutcome.Reason}, total
     * by the same compiler enforcement as {@code wireToken}: a reason added without deciding it does
     * not compile. This test locks the specific values down, derived from what this codebase already
     * says elsewhere in prose — {@code PROVIDER_REJECTED} is "Terminal, never retried" per its own
     * Javadoc (ADR 0018 §4), and the panel's own sentences for {@code PROVIDER_UNREADABLE} and
     * {@code PROVIDER_UNAVAILABLE} already say "Trying again may succeed."</p>
     *
     * <p><b>Mutation proof.</b> Flip {@code ADAPTER_DEFECT}'s {@code retryable} to {@code true} and the
     * last assertion reds: an adapter defect meets the identical exception on the identical input every
     * time, so telling the author to retry would be false.</p>
     */
    @Test
    void onlyTheTransportShapedReasonsAreRetryable() {
        assertTrue(AssistantOutcome.Reason.PROVIDER_UNREADABLE.retryable());
        assertTrue(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE.retryable());
        for (AssistantOutcome.Reason reason : AssistantOutcome.Reason.values()) {
            if (reason == AssistantOutcome.Reason.PROVIDER_UNREADABLE
                    || reason == AssistantOutcome.Reason.PROVIDER_UNAVAILABLE) {
                continue;
            }
            assertFalse(reason.retryable(),
                    () -> reason + " is not one of the two transport-shaped reasons and must not "
                            + "invite a retry that will meet the same fate");
        }
        assertFalse(AssistantOutcome.Reason.ADAPTER_DEFECT.retryable(),
                "an unexpected internal defect will not resolve itself on an identical retry");
    }
}
