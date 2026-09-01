package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelope;
import ai.ravenroot.api.error.ErrorEnvelopeSource;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IMAP query connector reports its failures through the product's common error form, the
 * mapping from its own vocabulary onto that form is declared rather than implicit, and nothing an
 * operator or an author supplied travels with it.
 *
 * <h2>Why this connector</h2>
 * <p>Of the four, {@link ImapQueryException.Code} is the one that distinguishes most — seven codes
 * against SMTP's six and Telegram's four. Kafka and AMQP have no enumerated <em>code</em> vocabulary:
 * each has a binary {@code {TEMPORARY, PERMANENT}} connection-failure enum, and beyond that classifies
 * with free-form {@code status}/{@code reason} strings, which is a de-facto vocabulary to be surveyed
 * and promoted rather than a blank sheet.
 * It is therefore the connector that puts the common form under the most pressure, and the one whose
 * mapping produces findings instead of a table of one-to-ones: four of its seven codes collapse, in
 * two groups, and the collapses cost very different amounts. The class documentation on
 * {@link ImapQueryException} states the table and what each collapse loses; this test is what holds
 * the table to the code.</p>
 *
 * <h2>Three counterfactuals with three different numbers</h2>
 * <p>These are easy to conflate, so each is named with the number it actually produces:</p>
 * <ol>
 *   <li><b>No common error form.</b> Neither
 *       {@code ErrorEnvelopeSource} nor {@code ImapQueryException.errorCode()} exists, so this class
 *       <em>does not compile</em>, failing with {@code cannot find symbol: class ErrorEnvelopeSource}.
 *       "Red" is the wrong word for it: the connector
 *       does not produce the common form at all. The two counterfactuals below are the ones that
 *       produce test failures, and their numbers are theirs alone.</li>
 *   <li><b>The connector stops implementing the interface, the API type staying.</b> Five of the eight
 *       tests fail — measured, not estimated. The three survivors are the three whose subject is not
 *       IMAP: {@link #theSentinelSurvivesTheOnlyRedactionTheEnvelopeApplies}, which probes
 *       {@code ErrorEnvelope} alone and must stay independent because its job is to keep the other
 *       scans from passing vacuously, and the two SDK-boundary tests below, whose subject is the
 *       interface.</li>
 *   <li><b>The mapping neutered to {@code INTERNAL_ERROR} for every code.</b> Five of the eight fail —
 *       the same five, with the same three survivors — which is what shows the mapping assertions are
 *       not tautologies.</li>
 * </ol>
 * <p>Every assertion that concerns the connector reaches it through {@link #asEnvelope}, deliberately
 * written as a consumer that knows only {@link ErrorEnvelopeSource} and nothing about IMAP. The two
 * SDK-boundary tests below are the exception: their subject is the interface itself, not IMAP.</p>
 *
 * <h2>The SDK boundary</h2>
 * <p>{@code ErrorEnvelopeSource} is a public type in the Node SDK, inherited by every third-party node
 * author, so the guarantee it advertises has to be the one it holds.
 * {@link #theTwoFactoriesThatWouldHaveLeakedAreRealAndOutOfAnImplementorsReach} measures the two
 * routes that would have leaked — {@code ErrorEnvelope.ofServerCode} and
 * {@code ErrorEnvelope.withIncident} both publish caller text filtered only by a token grammar that
 * admits a hostname or an API key — and then pins that a third-party implementor cannot reach either,
 * because the interface's one route to an envelope is {@code static} and its one abstract member
 * returns a closed enum. Had that route been a {@code default} method, an override would have reached
 * both.</p>
 *
 * <h2>What the redaction test actually scans</h2>
 * <p>Not only the message written into the envelope: {@link #assertEnvelopeIsClean} scans the rendered
 * JSON, {@code toString}, and each of {@code contract}, {@code code}, {@code message},
 * {@code correlationId}, {@code incidentId} and {@code assistantReason} read individually — a member
 * added later and rendered only in one of those places is still caught. The exception the connector
 * hands over alongside the envelope is walked too, cause chain and suppressed included.</p>
 *
 * <p>The vectors were searched for before the scan was written, and three of them are real:</p>
 * <ul>
 *   <li>{@code Integer.parseInt} quotes the value it rejected ({@code For input string: "..."}), and
 *       the node parses its {@code limit} property that way at {@code create} time — the measured
 *       vector, exercised here through the real node rather than assumed;</li>
 *   <li>{@code Set.of(String...)} names the duplicate element in its own message, which is how a
 *       hostile or misconfigured profile resolver can throw an exception carrying an operator's folder
 *       name — the measured vector. The real JDK exception is used, not an imitation of it;</li>
 *   <li><b>the correlation id is the one field of the envelope that carries caller-influenced text at
 *       all.</b> {@code ErrorEnvelope} admits any bounded {@code [A-Za-z0-9._:-]} token verbatim, so a
 *       connector that derived the handle from a profile id, a host or a payload would publish it and
 *       nothing downstream would strip it. {@link #theSentinelSurvivesTheOnlyRedactionTheEnvelopeApplies}
 *       pins that this test's sentinel is one of those surviving tokens, which is what stops the scan
 *       from passing vacuously: a sentinel containing a space or an {@code @} would be silently
 *       dropped by the envelope and every assertion below would hold for the wrong reason.</li>
 * </ul>
 */
class ImapErrorEnvelopeMappingTest {

    /**
     * Chosen to survive {@code ErrorEnvelope}'s token grammar unchanged — lower case, digits and
     * hyphens only. See {@link #theSentinelSurvivesTheOnlyRedactionTheEnvelopeApplies}.
     */
    private static final String SENTINEL = "sentinel-imap-452-a7c3f1";

    private static final String CORRELATION = "corr-452-fixed";

    /** For every specific code, the common code that represents it. */
    private static final Map<ImapQueryException.Code, ErrorCode> DECLARED = declared();

    private static Map<ImapQueryException.Code, ErrorCode> declared() {
        var table = new EnumMap<ImapQueryException.Code, ErrorCode>(ImapQueryException.Code.class);
        table.put(ImapQueryException.Code.INVALID_INPUT, ErrorCode.INVALID_REQUEST);
        table.put(ImapQueryException.Code.SATURATED, ErrorCode.REQUEST_LIMIT_EXCEEDED);
        table.put(ImapQueryException.Code.TIMEOUT, ErrorCode.REQUEST_INTERRUPTED);
        table.put(ImapQueryException.Code.TRANSPORT_FAILURE, ErrorCode.REQUEST_INTERRUPTED);
        table.put(ImapQueryException.Code.PROFILE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR);
        table.put(ImapQueryException.Code.CREDENTIAL_UNAVAILABLE, ErrorCode.INTERNAL_ERROR);
        table.put(ImapQueryException.Code.RESOURCE_LIMIT, ErrorCode.INTERNAL_ERROR);
        return Collections.unmodifiableMap(table);
    }

    // ---------------------------------------------------------------- Mapping and common-form behavior

    /**
     * The table is stated here independently of the production {@code switch}, so the two must agree.
     * The first assertion is the one that matters most: the declared table must cover the enum
     * exactly. A code the table forgets is a code whose representation nobody chose — and while the
     * production {@code switch} has no {@code default} and so cannot compile with a code missing, that
     * property protects the mapping, not this test's claim to describe it.
     */
    @Test void everySpecificCodeDeclaresWhichCommonCodeRepresentsIt() {
        assertEquals(EnumSet.allOf(ImapQueryException.Code.class), EnumSet.copyOf(DECLARED.keySet()),
                "every IMAP code must have a declared common code, and the table must declare no code the enum lacks");

        for (Map.Entry<ImapQueryException.Code, ErrorCode> entry : DECLARED.entrySet()) {
            ErrorEnvelope envelope = asEnvelope(new ImapQueryException(entry.getKey(), "IMAP query failed"))
                    .orElseThrow(() -> new AssertionError(entry.getKey() + " does not report through the common form"));
            assertEquals(entry.getValue().code(), envelope.code(), () -> "wrong common code for " + entry.getKey());
            assertEquals(entry.getValue().message(), envelope.message(), () -> "wrong common message for " + entry.getKey());
        }
    }

    /**
     * The collapses, named. Two IMAP codes share {@code REQUEST_INTERRUPTED} and three share
     * {@code INTERNAL_ERROR}, so a consumer reading the envelope receives four distinctions where the
     * connector drew seven.
     *
     * <p>What each loses is argued on {@link ImapQueryException}; this pins that neither grew. A
     * mapping is allowed to collapse, but a collapse that widens later without anyone noticing is the
     * information loss this complete partition is meant to prevent, so the partition is asserted whole rather than
     * per-entry — an eighth code quietly joining {@code INTERNAL_ERROR} fails here.</p>
     */
    @Test void theCollapsesAreExactlyTheTwoThatWereDeclared() {
        // Grouped by what a consumer of the common form actually receives, so this test measures the
        // published partition rather than the production switch read back to itself.
        var byCommon = new LinkedHashMap<String, Set<ImapQueryException.Code>>();
        for (ImapQueryException.Code code : ImapQueryException.Code.values()) {
            ErrorEnvelope envelope = asEnvelope(new ImapQueryException(code, "IMAP query failed"))
                    .orElseThrow(() -> new AssertionError(code + " does not report through the common form"));
            byCommon.computeIfAbsent(envelope.code(), unused -> EnumSet.noneOf(ImapQueryException.Code.class)).add(code);
        }

        var expected = new LinkedHashMap<String, Set<ImapQueryException.Code>>();
        expected.put(ErrorCode.INVALID_REQUEST.code(), EnumSet.of(ImapQueryException.Code.INVALID_INPUT));
        expected.put(ErrorCode.REQUEST_LIMIT_EXCEEDED.code(), EnumSet.of(ImapQueryException.Code.SATURATED));
        // Collapse A. These two are one event either side of the query watchdog's window,
        // so the pair is the least costly collapse available and is not a compromise.
        expected.put(ErrorCode.REQUEST_INTERRUPTED.code(),
                EnumSet.of(ImapQueryException.Code.TIMEOUT, ImapQueryException.Code.TRANSPORT_FAILURE));
        // Collapse B, the expensive one: two faults that are permanent until an operator acts, merged
        // with one that is a property of the message being read rather than of the deployment.
        expected.put(ErrorCode.INTERNAL_ERROR.code(), EnumSet.of(ImapQueryException.Code.PROFILE_UNAVAILABLE,
                ImapQueryException.Code.CREDENTIAL_UNAVAILABLE, ImapQueryException.Code.RESOURCE_LIMIT));

        assertEquals(expected, byCommon, "the collapse groups must be exactly the two that were declared and argued");
        assertEquals(4, byCommon.size(), "seven specific codes reach the consumer as four");
    }

    /**
     * Stated as a property rather than as a call: a consumer that knows only the common
     * form gets an answer from an IMAP failure, and the answer is the connector's own classification —
     * not a single catch-all. Four distinct outcomes from four distinct causes is what "does not lose
     * what the specific enum distinguished" means once the two declared collapses are accounted for.
     */
    @Test void aConsumerThatKnowsNothingAboutImapCanStillClassifyTheFailure() {
        List<Throwable> failures = List.of(
                new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid folder"),
                new ImapQueryException(ImapQueryException.Code.SATURATED, "IMAP query capacity is unavailable"),
                new ImapQueryException(ImapQueryException.Code.TIMEOUT, "IMAP query deadline exceeded"),
                new ImapQueryException(ImapQueryException.Code.PROFILE_UNAVAILABLE, "IMAP profile unavailable"));

        List<String> codes = failures.stream()
                .map(failure -> asEnvelope(failure).orElseThrow(() -> new AssertionError("no common form: " + failure)))
                .map(ErrorEnvelope::code).toList();

        assertEquals(List.of(ErrorCode.INVALID_REQUEST.code(), ErrorCode.REQUEST_LIMIT_EXCEEDED.code(),
                ErrorCode.REQUEST_INTERRUPTED.code(), ErrorCode.INTERNAL_ERROR.code()), codes);
        assertEquals(4, Set.copyOf(codes).size(), "four distinct causes must not arrive as one code");
    }

    // ---------------------------------------------------------------- Redaction

    /**
     * Proves the probe before trusting it. {@code ErrorEnvelope} accepts a correlation handle verbatim
     * when it is a bounded {@code [A-Za-z0-9._:-]} token and mints a random one otherwise, so a
     * sentinel that failed that grammar would be scrubbed by the envelope itself and every scan below
     * would pass without testing anything. This one survives, which means a leak into the field most
     * able to carry one would be visible.
     */
    @Test void theSentinelSurvivesTheOnlyRedactionTheEnvelopeApplies() {
        assertEquals(SENTINEL, ErrorEnvelope.of(ErrorCode.INTERNAL_ERROR, SENTINEL).correlationId(),
                "the sentinel must pass the envelope's own token grammar unchanged, or the scans below are vacuous");
    }

    /**
     * The three failures an author or operator can actually provoke with material of their own, driven
     * through the real node rather than by constructing exceptions: a configuration value that reaches
     * {@code Integer.parseInt}, a folder name carried in the payload, a profile resolver whose
     * exception carries a folder name the way {@code Set.of} does, and a credential resolver whose
     * exception carries the reference.
     */
    @Test void nothingFromTheConfigurationProfileOrPayloadReachesTheEnvelope() {
        // (1) The parseInt vector, at the one place the node parses operator-authored text: create().
        ImapQueryException limitRejected = assertThrows(ImapQueryException.class,
                () -> behavior(profile(Set.of("INBOX")), reference -> secret())
                        .create(configuration(Map.of("profile", "reader", "folder", "INBOX", "limit", SENTINEL))));
        assertEquals(ImapQueryException.Code.INVALID_INPUT, limitRejected.code());
        assertClean(limitRejected, ErrorCode.INVALID_REQUEST);

        // (2) A folder name from the payload, refused because the profile does not allow it.
        NodeAction folders = behavior(profile(Set.of("INBOX")), reference -> secret()).create(configuration());
        assertClean(failureOf(folders, Map.of("version", "mail.imap.query.v1", "folder", SENTINEL)),
                ImapQueryException.Code.INVALID_INPUT, ErrorCode.INVALID_REQUEST);

        // (3) The Set.of vector, with the real JDK exception rather than an imitation of its wording,
        // thrown from where a profile resolver would raise it.
        RuntimeException duplicate = assertThrows(IllegalArgumentException.class, () -> Set.of(SENTINEL, SENTINEL));
        assertTrue(duplicate.getMessage().contains(SENTINEL), "measured: Set.of names the duplicate element it refused");
        NodeAction hostileProfiles = new MailImapQueryNodeBehavior((tenant, name) -> { throw duplicate; }, reference -> secret())
                .create(configuration());
        assertClean(failureOf(hostileProfiles, Map.of("version", "mail.imap.query.v1")),
                ImapQueryException.Code.PROFILE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR);

        // (4) A credential resolver that fails while naming the reference, against a profile whose host,
        // username and credential reference all carry the sentinel. Nothing reaches a socket: the
        // resolver raises before any connection is attempted.
        RuntimeException hostile = new RuntimeException("cannot resolve " + SENTINEL, new IllegalStateException(SENTINEL));
        hostile.addSuppressed(new IllegalArgumentException(SENTINEL));
        NodeAction credentials = behavior(profile(Set.of("INBOX")), reference -> { throw hostile; }).create(configuration());
        assertClean(failureOf(credentials, Map.of("version", "mail.imap.query.v1")),
                ImapQueryException.Code.CREDENTIAL_UNAVAILABLE, ErrorCode.INTERNAL_ERROR);
    }

    /**
     * The structural redaction check covers all seven codes, including the four no unit test can
     * provoke without a live mailbox. Even an exception whose own message, cause and suppressed
     * exceptions are made of a host, a port and a credential reference produces a clean envelope,
     * because {@link ErrorEnvelopeSource} gives an implementor no parameter through which its text
     * could travel and {@code ErrorEnvelope} has no factory that accepts caller-composed text.
     */
    @Test void anExceptionMadeEntirelyOfSecretsStillProducesACleanEnvelope() {
        for (ImapQueryException.Code code : ImapQueryException.Code.values()) {
            var failure = new ImapQueryException(code, "IMAP " + SENTINEL + ":993 rejected user " + SENTINEL);
            failure.initCause(new IllegalStateException("credential " + SENTINEL));
            failure.addSuppressed(new IllegalArgumentException("duplicate element: " + SENTINEL));
            assertClean(failure, DECLARED.get(code));
        }
    }

    // ---------------------------------------------------------------- the SDK boundary

    /**
     * The two factories that would have leaked, measured rather than dismissed — and then shown to be
     * out of a third-party implementor's reach.
     *
     * <p>The first half is the important one: it asserts that {@code ofServerCode} and
     * {@code withIncident} really do publish caller-supplied text, host, port and credential-shaped
     * string included. That is not a defect in them — each exists for a component that owns its own
     * vocabulary — but it is why {@link ErrorEnvelopeSource}'s route to an envelope is {@code static}
     * and not {@code default}. A {@code default} method is overridable, and an override would have had
     * both of these in scope while the interface's documentation told every node author that
     * overriding was safe by construction.
     */
    @Test void theTwoFactoriesThatWouldHaveLeakedAreRealAndOutOfAnImplementorsReach() {
        // Measured. Both survive ErrorEnvelope's token grammar unchanged and reach the published JSON.
        ErrorEnvelope viaServerCode = ErrorEnvelope.ofServerCode("imap." + SENTINEL + ".example.test:993",
                ErrorCode.INTERNAL_ERROR, CORRELATION);
        assertEquals("imap." + SENTINEL + ".example.test:993", viaServerCode.code(),
                "measured: ofServerCode publishes a host and port verbatim");
        assertTrue(viaServerCode.toJson().contains(SENTINEL));

        ErrorEnvelope viaIncident = ErrorEnvelope.of(ErrorCode.INTERNAL_ERROR, CORRELATION)
                .withIncident("user:alice-" + SENTINEL);
        assertEquals("user:alice-" + SENTINEL, viaIncident.incidentId(),
                "measured: withIncident publishes a credential-shaped handle verbatim");
        assertTrue(viaIncident.toJson().contains(SENTINEL));

        // A third party's implementor, written to leak as hard as the interface permits. It holds the
        // host, the port and the credential reference, and the only thing it gets to publish is a
        // constant of a closed enum.
        ErrorEnvelope published = ErrorEnvelopeSource.envelopeOf(
                new HostileNodeAuthorFailure("imap." + SENTINEL + ".example.test", 993, SENTINEL), CORRELATION);
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), published.code());
        assertEquals(ErrorCode.INTERNAL_ERROR.message(), published.message());
        assertNull(published.incidentId());
        assertNull(published.assistantReason());
        assertFalse((published.toJson() + published).contains(SENTINEL),
                "an implementor acting through this interface must have no route to either factory above");
    }

    /**
     * The structural half, which is what makes the paragraph above a guarantee instead of a habit: the
     * interface offers an implementor exactly one member to write, and it returns a closed enum. If a
     * later edit turned {@code envelopeOf} into a {@code default} instance method for convenience,
     * this fails — which is the point, because that edit is precisely the one that reopens the hole.
     */
    @Test void theInterfaceLeavesAThirdPartyImplementorNothingToOverride() {
        List<Method> instanceMethods = Arrays.stream(ErrorEnvelopeSource.class.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();

        assertEquals(List.of("errorCode"), instanceMethods.stream().map(Method::getName).toList(),
                "the interface must expose exactly one member for an implementor to write");
        assertTrue(Modifier.isAbstract(instanceMethods.getFirst().getModifiers()),
                "a default method would be overridable, and an override reaches ofServerCode and withIncident");
        assertEquals(ErrorCode.class, instanceMethods.getFirst().getReturnType(),
                "what an implementor supplies must be a constant of the closed vocabulary, not text");
        assertEquals(0, instanceMethods.getFirst().getParameterCount(),
                "no parameter means no channel for an implementor's own material");
    }

    /** What a third-party node author would write if they were trying to publish their connection details. */
    private record HostileNodeAuthorFailure(String host, int port, String credentialReference)
            implements ErrorEnvelopeSource {
        @Override public ErrorCode errorCode() { return ErrorCode.INTERNAL_ERROR; }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A consumer of the common form and nothing else: no
     * {@code instanceof ImapQueryException}, no import of a connector type, no knowledge of which
     * system failed. Previously, this returned empty for every connector failure in the product.
     */
    private static Optional<ErrorEnvelope> asEnvelope(Throwable failure) {
        return failure instanceof ErrorEnvelopeSource source
                ? Optional.of(ErrorEnvelopeSource.envelopeOf(source, CORRELATION)) : Optional.empty();
    }

    private static void assertClean(ImapQueryException failure, ErrorCode expected) {
        ErrorEnvelope envelope = asEnvelope(failure)
                .orElseThrow(() -> new AssertionError("the connector does not report through the common form"));
        assertEquals(expected.code(), envelope.code());
        assertEquals(expected.message(), envelope.message(),
                "the message must be the common vocabulary's, never the connector's own text");
        assertEquals(ErrorEnvelope.CONTRACT, envelope.contract());
        assertEquals(CORRELATION, envelope.correlationId(),
                "the handle must be exactly the one the caller supplied, with nothing derived appended to it");
        assertNull(envelope.incidentId(), "a connector has no incident record to name");
        assertNull(envelope.assistantReason(), "a connector must not borrow the assistant's reason member");
        assertTrue(CODE_TOKENS.contains(envelope.code()),
                () -> "the code must stay inside the closed vocabulary, not carry a connector token: " + envelope.code());

        // Every rendering and every member read individually, so a member added later that appears in
        // only one of them is still scanned.
        String rendered = String.join("\0", envelope.toJson(), envelope.toString(), envelope.contract(),
                envelope.code(), envelope.message(), envelope.correlationId(),
                String.valueOf(envelope.incidentId()), String.valueOf(envelope.assistantReason()));
        assertFalse(rendered.contains(SENTINEL), () -> "the envelope carried the sentinel: " + rendered);

        // The exception travels with the envelope, so the same scan applies to it, cause chain and
        // suppressed included -- except where this test planted the sentinel there deliberately.
        if (!failure.getMessage().contains(SENTINEL)) {
            assertFalse(throwableText(failure).contains(SENTINEL),
                    () -> "the failure the connector raised carried the sentinel: " + throwableText(failure));
        }
    }

    private static void assertClean(ImapQueryException failure, ImapQueryException.Code code, ErrorCode expected) {
        assertEquals(code, failure.code(), "the connector's own classification must not change either");
        assertClean(failure, expected);
    }

    private static final Set<String> CODE_TOKENS =
            Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static ImapQueryException failureOf(NodeAction action, Map<String, Object> payload) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> action.handle(node(payload)).toCompletableFuture().join());
        return assertInstanceOfImapFailure(failure.getCause());
    }

    private static ImapQueryException assertInstanceOfImapFailure(Throwable cause) {
        assertTrue(cause instanceof ImapQueryException, () -> "expected a typed IMAP failure but got " + cause);
        return (ImapQueryException) cause;
    }

    private static MailImapQueryNodeBehavior behavior(ImapProfile profile, ai.ravenroot.api.security.CredentialResolver credentials) {
        return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile), credentials);
    }

    /** Host, username and credential reference all carry the sentinel; the port is the only field that cannot. */
    private static ImapProfile profile(Set<String> folders) {
        return new ImapProfile("tenant", "reader", "imap." + SENTINEL + ".example.test", 993, "IMAPS",
                SENTINEL, SENTINEL, folders, 1_000, 3_000, 2, 10, 10);
    }

    private static NodeConfiguration configuration() {
        return configuration(Map.of("profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "2"));
    }

    private static NodeConfiguration configuration(Map<String, Object> properties) {
        return new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, properties);
    }

    private static Optional<SecretValue> secret() { return Optional.of(new SecretValue("secret".toCharArray())); }

    private static NodeMessage node(Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", "tenant", "s", PrincipalType.USER, "i"),
                id, id, id, id, Set.of(), "imap", payload, Map.of());
    }

    private static String throwableText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> pending = new ArrayList<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable value = pending.removeLast();
            if (value == null || !seen.add(value)) continue;
            text.append(value).append(value.getMessage());
            pending.add(value.getCause());
            pending.addAll(List.of(value.getSuppressed()));
        }
        return text.toString();
    }
}
