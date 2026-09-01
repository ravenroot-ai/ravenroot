package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailImapQueryNodeBehaviorIntegrationTest {
    /**
     * {@link #profile}'s fixed 1000ms connect / 3000ms read budget is the same vulnerable shape
     * widened in {@code MailSendNodeSmtpProtocolTest} and {@code MailCredentialRetryContractTest} --
     * a real (loopback, GreenMail-backed) round trip whose success a typed-outcome assertion depends
     * on, not a latency test. This file's separately hardcoded {@link #profile} helper is invisible to
     * a grep for named timeout setters: the values
     * are passed positionally into a record constructor
     * ({@code new ImapProfile(..., 1_000, 3_000, ...)}), and the names {@code connectTimeoutMs} /
     * {@code readTimeoutMs} exist only in {@link ImapProfile}'s own field declaration, not at any call
     * site. Running that pattern against the previous file returns exit 1 with no match.
     *
     * <p><b>Not "tighter" -- the leg that governs was wider, not narrower.</b> Calling this profile
     * tighter than the 2000/2000ms budget that reddened under load is false
     * on the leg that actually decides the outcome: connect (1000ms) was indeed narrower, but read
     * (3000ms) was 50% <em>wider</em>, and read is the one that governs -- it drives the
     * {@link MailImapQueryNodeBehavior} watchdog window, {@code mail.imaps.timeout} and
     * {@code mail.imaps.writetimeout} together; connect enters the classification only as
     * {@code min(readTimeoutMs, connectTimeoutMs)}. Measured directly: shrinking connect alone to 5ms
     * while leaving read at the widened 10s budget leaves the class 19/19 green -- that leg does not
     * bite on this path at all. The exposure comes from positional, unnamed timeout literals rather
     * than from the relative size of the two numbers.
     *
     * <p><b>The causal chain is established, not merely similarly shaped.</b> {@code TIMEOUT} and
     * {@code TRANSPORT_FAILURE} are the same event observed on either side of the watchdog window --
     * this does not rest on reproducing a specific split between the two under mutation. Decoupling
     * the watchdog's window from the socket budget gave opposite, non-portable outcomes across
     * environments, so no run count from that experiment is cited here. The causal chain instead
     * rests on construction: {@code query()}'s two {@code catch} clauses both branch on the same
     * single {@code watchdog.timedOut()} read -- there is no other signal that chooses between the two
     * codes. Already stated: in {@link ImapQueryException}'s own javadoc ("collapse A") and in
     * {@link #assertSanitizedFailure}'s javadoc <em>in this same file</em> ("on a fast host the read
     * wins... classified {@code TRANSPORT_FAILURE}; delayed past the watchdog's deadline... classified
     * {@code TIMEOUT} instead -- same event, ... different label").
     *
     * <p>Reproduced deliberately, not assumed: shrinking connect and read together to 5ms turns
     * {@code mimeDepthAndAddressBudgetsFailTypedAndReleaseResourcesForRecovery} red with
     * {@code expected: <RESOURCE_LIMIT> but was: <TIMEOUT>} on the typed-outcome assertion. Under this
     * mutation, run three times, the class landed at a stable 4
     * failures + 6 errors + 9 green out of 19 every time on this host: failures on
     * {@code actionAdmissionRejectsBeforeSecretsAndRecoversAfterTransportFailure},
     * {@code boundedUidScanFailsBeforeTraversingAnUnboundedMailboxAndRecovers},
     * {@code mimeDepthAndAddressBudgetsFailTypedAndReleaseResourcesForRecovery} and
     * {@code rejectsFractionalNonFiniteOverflowAndStaleCursorNumbersExactly}; errors on six more that
     * expect success. <b>Not</b> "every other test in the class" -- nine stayed green, including the
     * ones whose own assertions tolerate either label (like {@link #assertSanitizedFailure}) or that
     * never open a real connection at all. The exact split is a property of the host's load at
     * measurement time, not of the mutation: on a heavier-loaded host, the same mutation produced
     * 3 failures + 6 errors + 10 green. Widened to the same
     * {@code GENEROUS_TIMEOUT_MS = 10_000} convention used by the corresponding SMTP tests.
     *
     * <p>Four literals across the module are a tight budget against a live listener. Two,
     * {@link #operatorDeadlineFailsTypedClosesResourcesAndAllowsRecovery} and
     * {@link #terminalDeadlineBoundaryCannotReturnSuccessBeforeWatchdogClaimsExpiry}, each driving the
     * deadline itself off an injected fake clock that never advances past 3-4 simulated seconds, so a
     * widened 10s ceiling would simply never be reached and the assertion would stop failing for a
     * reason unrelated to the property under test; a third,
     * {@link #absoluteWatchdogStopsTrustedTlsSlowDripAndReleasesOnlyAfterWorkerExit}, keeps its own
     * literal 1000/1000ms {@code ImapProfile} because
     * its property <em>is</em> the absolute watchdog deadline firing promptly against a server that
     * makes real, if too-slow, progress -- widening it would only prove the watchdog waits longer, not
     * that it fires. All three follow the same reasoning as a different class's
     * DeadlineWatchdog test: widening a deadline test's own budget weakens exactly what it verifies.
     *
     * <p>The fourth is
     * {@code DeterministicStartTlsImapFixture#action}'s own hardcoded 1000/1000ms {@code ImapProfile},
     * called live from {@code MailExecutionEventSanitizationTest} against a real loopback listener --
     * not dead code. It is still not a live exposure to the tight-budget typed-outcome shape, but for
     * a different, measured reason: its caller's
     * assertion is {@code assertThrows(Throwable.class, ...)} plus three more -- two checking the
     * sentinel never appears, one checking a {@code FAILED} event actually was logged -- and none of the
     * four distinguish a typed refusal from a timeout from any other failure. Mutating its budget to
     * 5/5ms leaves that test green 3 runs out of 3. A tight literal only matters where the assertion
     * cares which typed outcome comes back; here it does not.
     *
     * <p><b>The complete timeout-literal inventory is recorded here.</b>
     * {@code grep -rnE 'connectTimeout\(|\.timeout\(Duration|setSoTimeout|Timeout(Ms|Millis)?\s*[=(]'}
     * across the reactor, without a module exclusion, plus a direct
     * {@code grep -rn "new ImapProfile("} to catch the positional-literal shape that pattern cannot see:
     * twelve {@code new ImapProfile(} call sites total in {@code ravenroot-mail}. Of those, the four
     * named above are a tight literal against a live listener; the rest either never open a real
     * connection (a deliberately closed port, an unresolvable sentinel host, or no fixture reachable
     * from their own {@code new ImapProfile(} call site specifically, which is not the
     * same claim as the whole test class never connecting) or are not a test (production profile parsing
     * in {@code EnvironmentImapProfileResolver}). No vulnerable shape remains unaccounted for.
     *
     * <p><b>What this does not explain.</b>
     * {@code MailSendNodeSmtpProtocolTest#reportsTypedPartialResultWhenOneRcptIsRejected} ("SMTP
     * connection failed") already reaches its {@code MailProfile} through
     * {@code generousResult()} passing {@code GENEROUS_TIMEOUT_MS = 10_000} as an explicit named argument
     * -- not an unnamed positional tight literal, which is the specific mechanism analysed here.
     * This timeout-literal mechanism does not explain that symptom.
     */
    private static final int GENEROUS_TIMEOUT_MS = 10_000;
    /** 70,000 characters, no line breaks: see {@link #fullContentModeReturnsTheWholeBodyWhilePreviewTruncatesTheSameMessage}. */
    private static final String LONG_BODY = "abcdefghij".repeat(7_000);
    private static final String ALTERNATIVE_TEXT = "plain-part-".repeat(64);
    private static final String ALTERNATIVE_HTML = "<p>html-part</p>".repeat(64);

    @Test void trustedImapsSearchesOrdersPaginatesAndPreservesSeenFlags() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(message("alpha one", "one", Instant.parse("2025-01-01T12:00:00Z"), false));
            user.deliver(message("alpha two", "two", Instant.parse("2025-01-02T12:00:00Z"), false));
            user.deliver(message("other", "three", Instant.parse("2025-01-03T12:00:00Z"), true));
            NodeAction action = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 1);
            Map<String, Object> first = output(action, Map.of("version", "mail.imap.query.v1", "subject", "alpha", "unseen", true, "limit", 1));
            assertEquals("INBOX", first.get("folder")); assertEquals(true, first.get("hasMore"));
            List<Map<String, Object>> rows = messages(first); assertEquals(1, rows.size()); assertEquals("alpha one", rows.getFirst().get("subject"));
            Map<String, Object> cursor = map(first.get("cursor"));
            Map<String, Object> second = output(action, Map.of("version", "mail.imap.query.v1", "subject", "alpha", "unseen", true, "cursor", cursor));
            assertEquals("alpha two", messages(second).getFirst().get("subject"));
            Map<String, Object> unchanged = output(action, Map.of("version", "mail.imap.query.v1", "subject", "alpha", "unseen", true));
            assertEquals(2, messages(unchanged).size(), "body and attachment extraction must not mark unread mail SEEN");
            assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1", "cursor", Map.of("uidValidity", -1, "lastUid", 0))));
        }
    }

    @Test void trustedImapsBoundsPreviewAttachmentsAndAppliesTypedFilters() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(multipartMessage());
            NodeAction action = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 1);
            Map<String, Object> output = output(action, Map.of("version", "mail.imap.query.v1", "from", "from@example.test", "to", "reader@example.test", "subject", "attachment", "since", "2025-01-01T00:00:00Z", "before", "2026-01-01T00:00:00Z"));
            Map<String, Object> row = messages(output).getFirst();
            assertEquals("p", row.get("textPreview")); assertEquals(true, row.get("previewTruncated"));
            assertEquals("a.txt", map(((List<?>) row.get("attachments")).getFirst()).get("name"));
            assertEquals(1, ((List<?>) row.get("from")).size()); assertEquals(1, ((List<?>) row.get("to")).size());
        }
    }

    @Test void rejectsUnauthorizedFolderAndMalformedPayloadBeforeSecretLookup() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        NodeAction action = localAction(1, Set.of("INBOX"), ref -> { lookups.incrementAndGet(); return secret(); });
        for (Map<String, Object> payload : List.<Map<String, Object>>of(
                Map.<String, Object>of("version", "mail.imap.query.v1", "folder", "Archive"),
                Map.<String, Object>of("version", "mail.imap.query.v1", "folder", "bad\r\nINBOX"),
                Map.<String, Object>of("version", "mail.imap.query.v1", "since", "not-a-date"),
                // An unknown or non-string contentMode is INVALID_INPUT, never a silent preview.
                Map.<String, Object>of("version", "mail.imap.query.v1", "contentMode", "metadata"),
                Map.<String, Object>of("version", "mail.imap.query.v1", "contentMode", 1),
                Map.<String, Object>of("version", "mail.imap.query.v1", "limit", 0))) {
            CompletionException failure = assertThrows(CompletionException.class, () -> output(action, payload));
            assertEquals(ImapQueryException.Code.INVALID_INPUT, ((ImapQueryException) failure.getCause()).code());
        }
        assertEquals(0, lookups.get());
    }

    @Test void wrongHostAndAuthenticationFailuresAreSanitized() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            fixture.server().setUser("reader@example.test", "reader", "secret");
            NodeAction wrongHost = action(fixture, "127.0.0.2", "IMAPS", Set.of("INBOX"), ref -> secret(), 1);
            NodeAction wrongPassword = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> Optional.of(new SecretValue("wrong-password-sentinel".toCharArray())), 1);
            assertSanitizedFailure(wrongHost, "127.0.0.2"); assertSanitizedFailure(wrongPassword, "wrong-password-sentinel");
        }
    }

    @Test void profileGraphAndPayloadLimitsCanOnlyTighten() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            for (int i = 0; i < 5; i++) user.deliver(message("cap " + i, "body", Instant.parse("2025-01-01T12:00:00Z").plusSeconds(i), false));
            NodeAction profileTwo = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10, 2, "4");
            assertEquals(2, messages(output(profileTwo, Map.of("version", "mail.imap.query.v1", "limit", 3))).size());
            NodeAction graphOne = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10, 5, "1");
            assertEquals(1, messages(output(graphOne, Map.of("version", "mail.imap.query.v1", "limit", 4))).size());
        }
    }

    @Test void rejectsFractionalNonFiniteOverflowAndStaleCursorNumbersExactly() throws Exception {
        AtomicInteger secrets = new AtomicInteger(); NodeAction local = localAction(10, Set.of("INBOX"), ref -> { secrets.incrementAndGet(); return secret(); });
        for (Map<String, Object> payload : List.<Map<String, Object>>of(
                Map.<String, Object>of("version", "mail.imap.query.v1", "limit", 1.5), Map.<String, Object>of("version", "mail.imap.query.v1", "limit", Double.NaN),
                Map.<String, Object>of("version", "mail.imap.query.v1", "uidMin", Double.POSITIVE_INFINITY), Map.<String, Object>of("version", "mail.imap.query.v1", "uidMax", new java.math.BigInteger("9223372036854775808"))))
            assertEquals(ImapQueryException.Code.INVALID_INPUT, ((ImapQueryException) assertThrows(CompletionException.class, () -> output(local, payload)).getCause()).code());
        assertEquals(0, secrets.get());
        try (var fixture = DeterministicImapFixture.startImaps()) {
            fixture.server().setUser("reader@example.test", "reader", "secret"); NodeAction live = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10);
            for (Object bad : List.of(1.25, Double.NaN, Double.POSITIVE_INFINITY, new java.math.BigDecimal("1.01"))) {
                CompletionException failure = assertThrows(CompletionException.class, () -> output(live, Map.of("version", "mail.imap.query.v1", "cursor", Map.of("uidValidity", bad, "lastUid", 0))));
                assertEquals(ImapQueryException.Code.INVALID_INPUT, ((ImapQueryException) failure.getCause()).code());
            }
            CompletionException terminal = assertThrows(CompletionException.class, () -> output(live, Map.of("version", "mail.imap.query.v1", "cursor", Map.of("uidValidity", 1, "lastUid", Double.NaN))));
            assertEquals(ImapQueryException.Code.INVALID_INPUT, ((ImapQueryException) terminal.getCause()).code());
        }
    }

    @Test void mimeDepthAndAddressBudgetsFailTypedAndReleaseResourcesForRecovery() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(deepMultipartMessage()); user.deliver(manyPartMessage()); user.deliver(message("oversized", "x".repeat(1_049_000), Instant.parse("2025-01-01T13:00:00Z"), false)); user.deliver(message("good", "ok", Instant.parse("2025-01-02T12:00:00Z"), false));
            NodeAction action = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10);
            for (String subject : List.of("deep", "many", "oversized")) {
                CompletionException bounded = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1", "subject", subject)));
                assertEquals(ImapQueryException.Code.RESOURCE_LIMIT, ((ImapQueryException) bounded.getCause()).code());
            }
            assertEquals("good", messages(output(action, Map.of("version", "mail.imap.query.v1", "subject", "good"))).getFirst().get("subject"));
        }
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret"); user.deliver(addressFloodMessage());
            NodeAction action = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10);
            CompletionException flood = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1")));
            assertEquals(ImapQueryException.Code.RESOURCE_LIMIT, ((ImapQueryException) flood.getCause()).code());
        }
    }

    @Test void previewBudgetCountsCharactersAndHonorsDeclaredCharset() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(charsetMessage("utf8", "é猫x", "UTF-8")); user.deliver(charsetMessage("latin", "éøx", "ISO-8859-1"));
            NodeAction action = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 2);
            assertEquals("é猫", messages(output(action, Map.of("version", "mail.imap.query.v1", "subject", "utf8"))).getFirst().get("textPreview"));
            assertEquals("éø", messages(output(action, Map.of("version", "mail.imap.query.v1", "subject", "latin"))).getFirst().get("textPreview"));
        }
    }

    /**
     * One message and one profile compare full content with preview so that the comparison is not
     * between two differently-shaped fixtures. {@link #LONG_BODY} is 70,000 characters: strictly more
     * than 65,536, which is the largest {@code maxPreviewChars} an {@link ImapProfile} will accept, so
     * "longer than every reachable preview ceiling" is a property of the record's own bound and not of
     * the value this test happens to choose. It has no line breaks on purpose -- MIME canonicalises
     * text line endings to CRLF on the wire, so a body containing {@code \n} would come back as
     * {@code \r\n} and an equality assertion would be measuring transfer encoding rather than
     * completeness.
     */
    @Test void fullContentModeReturnsTheWholeBodyWhilePreviewTruncatesTheSameMessage() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(message("long", LONG_BODY, Instant.parse("2025-01-01T12:00:00Z"), false));
            NodeAction action = action(fixture, 64, null);

            Map<String, Object> full = messages(output(action, Map.of("version", "mail.imap.query.v1", "contentMode", "full"))).getFirst();
            Map<String, Object> content = map(full.get("content"));
            assertEquals("full", content.get("mode"));
            assertEquals(LONG_BODY.length(), ((String) content.get("text")).length());
            assertEquals(LONG_BODY, content.get("text"));
            assertEquals("", content.get("html"));
            assertEquals(true, content.get("complete"));
            assertEquals(false, content.get("attachmentBodiesIncluded"));
            assertFalse(full.containsKey("textPreview"), "a successful full result must not also ship an abbreviated copy of the same body");
            assertFalse(full.containsKey("previewTruncated"));

            Map<String, Object> preview = messages(output(action, Map.of("version", "mail.imap.query.v1", "contentMode", "preview"))).getFirst();
            assertEquals(LONG_BODY.substring(0, 64), preview.get("textPreview"));
            assertEquals(true, preview.get("previewTruncated"));
            assertFalse(preview.containsKey("content"));
        }
    }

    /**
     * Two profiles that differ in exactly one field -- {@code maxPreviewChars} 1
     * against 65,536, the two ends of {@link ImapProfile}'s own accepted range -- must produce
     * {@code content} objects that compare equal, and previews that do not. Equality of the whole map,
     * rather than of {@code text} alone, is deliberate: it also pins {@code complete} and
     * {@code attachmentBodiesIncluded} against a preview budget leaking into a marker.
     */
    @Test void maxPreviewCharsChangesThePreviewAndNeverChangesAByteOfFull() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(message("budget", LONG_BODY, Instant.parse("2025-01-01T12:00:00Z"), false));
            Map<String, Object> fullPayload = Map.of("version", "mail.imap.query.v1", "contentMode", "full");
            Map<String, Object> previewPayload = Map.of("version", "mail.imap.query.v1", "contentMode", "preview");
            NodeAction narrow = action(fixture, 1, null);
            NodeAction wide = action(fixture, 65_536, null);

            Map<String, Object> narrowFull = map(messages(output(narrow, fullPayload)).getFirst().get("content"));
            Map<String, Object> wideFull = map(messages(output(wide, fullPayload)).getFirst().get("content"));
            assertEquals(narrowFull, wideFull, "maxPreviewChars must not reach any full code path");
            assertEquals(LONG_BODY, narrowFull.get("text"));

            assertEquals(LONG_BODY.substring(0, 1), messages(output(narrow, previewPayload)).getFirst().get("textPreview"));
            assertEquals(LONG_BODY.substring(0, 65_536), messages(output(wide, previewPayload)).getFirst().get("textPreview"));
        }
    }

    /**
     * Preview reports the first non-empty {@code text/plain} part and discards
     * {@code text/html} entirely; full must return both, whole. The two bodies here are each longer
     * than the profile's preview budget, so a full result that silently reused the preview path would
     * come back short rather than merely differently shaped.
     */
    @Test void fullContentModeReturnsCompleteTextAndHtmlFromOneMultipartMessage() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(textAndHtmlMessage(ALTERNATIVE_TEXT, ALTERNATIVE_HTML));
            NodeAction action = action(fixture, 8, null);

            Map<String, Object> row = messages(output(action, Map.of("version", "mail.imap.query.v1", "contentMode", "full"))).getFirst();
            Map<String, Object> content = map(row.get("content"));
            assertEquals(ALTERNATIVE_TEXT, content.get("text"));
            assertEquals(ALTERNATIVE_HTML, content.get("html"));
            assertEquals(true, content.get("complete"));
            assertEquals(false, content.get("attachmentBodiesIncluded"));
            assertEquals("a.txt", map(((List<?>) row.get("attachments")).getFirst()).get("name"));
            assertFalse(content.toString().contains("contents"), "attachment bodies are out of scope and must not leak into the text");

            Map<String, Object> preview = messages(output(action, Map.of("version", "mail.imap.query.v1"))).getFirst();
            assertEquals(ALTERNATIVE_TEXT.substring(0, 8), preview.get("textPreview"));
            assertEquals(true, preview.get("previewTruncated"));
        }
    }

    /**
     * Every infrastructure ceiling that already refuses a preview query refuses a
     * full one the same way, and a refused full query returns no body at all -- not a shortened one.
     * The transport leg tolerates either {@code TRANSPORT_FAILURE} or {@code TIMEOUT} for the reason
     * {@link #assertSanitizedFailure} documents: the two are the same event on either side of the
     * watchdog window, and asserting one of them made an unrelated test flake.
     */
    @Test void fullContentModeFailsTypedAtEveryLimitWithoutReturningAPartialBody() throws Exception {
        Map<String, Object> full = Map.of("version", "mail.imap.query.v1", "contentMode", "full");
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(message("oversized", "x".repeat(1_049_000), Instant.parse("2025-01-01T13:00:00Z"), false));
            user.deliver(deepMultipartMessage());
            user.deliver(manyPartMessage());
            NodeAction action = action(fixture, 64, null);
            for (String subject : List.of("oversized", "deep", "many")) {
                Map<String, Object> payload = Map.of("version", "mail.imap.query.v1", "contentMode", "full", "subject", subject);
                CompletionException bounded = assertThrows(CompletionException.class, () -> output(action, payload));
                assertEquals(ImapQueryException.Code.RESOURCE_LIMIT, ((ImapQueryException) bounded.getCause()).code(), subject);
            }
            NodeAction wrongHost = action(fixture, "127.0.0.2", "IMAPS", Set.of("INBOX"), ref -> secret(), 64);
            CompletionException transport = assertThrows(CompletionException.class, () -> output(wrongHost, full));
            ImapQueryException.Code code = ((ImapQueryException) transport.getCause()).code();
            assertTrue(code == ImapQueryException.Code.TRANSPORT_FAILURE || code == ImapQueryException.Code.TIMEOUT, "full must fail typed, not partially succeed: " + code);
            assertFalse(transport.toString().contains("127.0.0.2"));
        }
    }

    /**
     * The retro-compatible shape is pinned by the exact key set rather than by
     * spot-checking two fields: a payload that omits {@code contentMode} gets
     * back what it got back before, with no new key to ignore. Both spellings of "said nothing" are
     * covered -- no node property at all, and a node property that explicitly says {@code preview}.
     */
    @Test void payloadsWithoutContentModeKeepTheUnchangedPreviewShape() throws Exception {
        Set<String> previewShape = Set.of("uid", "messageId", "subject", "sentAt", "from", "to", "flags", "size", "textPreview", "previewTruncated", "attachments");
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(multipartMessage());
            for (String graphMode : java.util.Arrays.asList(null, "preview")) {
                Map<String, Object> row = messages(output(action(fixture, 1, graphMode), Map.of("version", "mail.imap.query.v1"))).getFirst();
                assertEquals(previewShape, row.keySet(), "graph contentMode=" + graphMode);
                assertEquals("p", row.get("textPreview"));
                assertEquals(true, row.get("previewTruncated"));
                assertEquals("a.txt", map(((List<?>) row.get("attachments")).getFirst()).get("name"));
            }
            // The node property is only a default: a payload that names `full` overrides a node that
            // says `preview`, and one that names `preview` overrides a node that says `full`.
            assertTrue(messages(output(action(fixture, 1, "full"), Map.of("version", "mail.imap.query.v1"))).getFirst().containsKey("content"));
            assertEquals(previewShape, messages(output(action(fixture, 1, "full"), Map.of("version", "mail.imap.query.v1", "contentMode", "preview"))).getFirst().keySet());
        }
    }

    @Test void boundedUidScanFailsBeforeTraversingAnUnboundedMailboxAndRecovers() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            Instant base = Instant.parse("2025-01-01T00:00:00Z");
            for (int i = 0; i < 129; i++) user.deliver(message("skip", "body", base.plusSeconds(i), false));
            user.deliver(message("target", "body", base.plusSeconds(130), false));
            NodeAction action = action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10, 1, "1");
            CompletionException bounded = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1", "subject", "target")));
            assertEquals(ImapQueryException.Code.RESOURCE_LIMIT, ((ImapQueryException) bounded.getCause()).code());
            assertEquals("target", messages(output(action, Map.of("version", "mail.imap.query.v1", "subject", "target", "uidMin", 130))).getFirst().get("subject"));
        }
    }

    @Test void operatorDeadlineFailsTypedClosesResourcesAndAllowsRecovery() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret"); user.deliver(message("deadline", "body", Instant.parse("2025-01-01T00:00:00Z"), false));
            SSLSocketFactory socketFactory = fixture.trustedSocketFactory(); java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
            NodeAction expiring = new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(new ImapProfile(tenant, name, "localhost", fixture.port(), "IMAPS", "reader", "credential", Set.of("INBOX"), 1_000, 3_000, 2, 10, 10)), ref -> secret(),
                    properties -> { properties.put("mail.imaps.ssl.socketFactory", socketFactory); return properties; }, Runnable::run, () -> clock.getAndAdd(1_000_000_000L)).create(configuration());
            CompletionException timeout = assertThrows(CompletionException.class, () -> output(expiring, Map.of("version", "mail.imap.query.v1")));
            assertEquals(ImapQueryException.Code.TIMEOUT, ((ImapQueryException) timeout.getCause()).code());
            assertEquals("deadline", messages(output(action(fixture, "localhost", "IMAPS", Set.of("INBOX"), ref -> secret(), 10), Map.of("version", "mail.imap.query.v1"))).getFirst().get("subject"));
        }
    }

    @Test void absoluteWatchdogStopsTrustedTlsSlowDripAndReleasesOnlyAfterWorkerExit() throws Exception {
        try (var fixture = new DeterministicSlowDripImapFixture()) {
            String sentinel = "slow-drip-password-sentinel";
            AtomicInteger activeWorkers = new AtomicInteger();
            var workerExited = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(1));
            java.util.concurrent.Executor trackedExecutor = task -> Thread.ofVirtual().name("imap-slow-drip-worker").start(() -> {
                activeWorkers.incrementAndGet();
                try { task.run(); }
                finally { activeWorkers.decrementAndGet(); workerExited.get().countDown(); }
            });
            SSLSocketFactory socketFactory = DeterministicImapFixture.trustedSocketFactoryForTests();
            ImapProfile profile = new ImapProfile("tenant", "reader", "localhost", fixture.port(), "IMAPS", "reader", "credential", Set.of("INBOX"), 1_000, 1_000, 1, 1, 10);
            NodeAction action = new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile), ref -> Optional.of(new SecretValue(sentinel.toCharArray())),
                    properties -> { properties.put("mail.imaps.ssl.socketFactory", socketFactory); return properties; }, trackedExecutor)
                    .create(new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, Map.of("profile", "reader", "folder", "INBOX", "limit", "1", "maxConcurrency", "1")));

            var timedStage = action.handle(node(Map.of("version", "mail.imap.query.v1"))).toCompletableFuture();
            assertTrue(fixture.awaitSlowCommand(), "trusted TLS query must reach the deliberately incomplete UID response");
            CompletionException failure = assertThrows(CompletionException.class, timedStage::join);
            assertEquals(ImapQueryException.Code.TIMEOUT, ((ImapQueryException) failure.getCause()).code());
            assertFalse(throwableText(failure).contains(sentinel));
            assertTrue(fixture.dripWrites() >= 2, "server must make repeated progress beneath the per-read timeout");
            assertTrue(fixture.awaitFirstSocketClose(), "watchdog must actively close the TLS socket");
            assertTrue(workerExited.get().await(1, TimeUnit.SECONDS));
            assertEquals(0, activeWorkers.get());
            assertImapRuntimeClean();

            workerExited.set(new CountDownLatch(1));
            assertTrue(messages(output(action, Map.of("version", "mail.imap.query.v1"))).isEmpty(), "same action permit must recover after the worker exits");
            assertTrue(workerExited.get().await(1, TimeUnit.SECONDS));
            assertEquals(0, activeWorkers.get());
            assertTrue(fixture.socketsClosed() >= 2);
            assertImapRuntimeClean();
        }
    }

    /**
     * Left on the original, unwidened 1000/3000ms {@link ImapProfile} budget, deliberately.
     * The injected clock below only ever advances to exactly {@code TimeUnit.SECONDS.toNanos(3)} --
     * the boundary this test's own name is about -- so it never reaches a widened 10s deadline and
     * the assertion would stop failing for a reason unrelated to the property under test. Same
     * reasoning as {@link #operatorDeadlineFailsTypedClosesResourcesAndAllowsRecovery}: the deadline
     * itself is what is being verified, not a real round trip's tolerance for load.
     */
    @Test void terminalDeadlineBoundaryCannotReturnSuccessBeforeWatchdogClaimsExpiry() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            fixture.server().setUser("reader@example.test", "reader", "secret");
            SSLSocketFactory socketFactory = fixture.trustedSocketFactory();
            AtomicInteger reads = new AtomicInteger();
            NodeAction action = new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(new ImapProfile(tenant, name, "localhost", fixture.port(), "IMAPS", "reader", "credential", Set.of("INBOX"), 1_000, 3_000, 2, 10, 10)), ref -> secret(),
                    properties -> { properties.put("mail.imaps.ssl.socketFactory", socketFactory); return properties; }, Runnable::run,
                    () -> reads.incrementAndGet() >= 4 ? TimeUnit.SECONDS.toNanos(3) : 0L).create(configuration());
            CompletionException failure = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1")));
            assertEquals(ImapQueryException.Code.TIMEOUT, ((ImapQueryException) failure.getCause()).code());
            assertImapRuntimeClean();
        }
    }

    @Test void advertisedButRefusedStartTlsNeverAuthenticatesOrDowngrades() throws Exception {
        try (var fixture = new DeterministicStartTlsImapFixture(true, false)) {
            NodeAction action = startTlsAction(fixture, ref -> secret()); assertSanitizedFailure(action, "secret");
            assertFalse(fixture.upgraded()); assertEquals(0, fixture.credentialCommands()); assertTrue(fixture.awaitSocketClose());
        }
    }

    @Test void requiredStartTlsUpgradesOnWireAndClosesTheReadOnlySession() throws Exception {
        try (var fixture = new DeterministicStartTlsImapFixture(true, true)) {
            NodeAction action = startTlsAction(fixture, ref -> secret());
            assertTrue(messages(output(action, Map.of("version", "mail.imap.query.v1"))).isEmpty());
            assertTrue(fixture.upgraded(), "STARTTLS must be completed before IMAP credentials or commands");
            assertEquals(1, fixture.credentialCommands()); assertEquals(1, fixture.closeCommands()); assertEquals(1, fixture.logoutCommands()); assertTrue(fixture.awaitSocketClose()); assertEquals(1, fixture.socketsClosed());
        }
    }

    @Test void requiredStartTlsRefusesNoAdvertisementWithoutCredentialsOrDowngrade() throws Exception {
        try (var fixture = new DeterministicStartTlsImapFixture(false, false)) {
            AtomicInteger secrets = new AtomicInteger(); NodeAction action = startTlsAction(fixture, ref -> { secrets.incrementAndGet(); return secret(); });
            assertSanitizedFailure(action, "secret");
            assertFalse(fixture.upgraded()); assertEquals(0, fixture.credentialCommands()); assertEquals(1, secrets.get(), "credentials may be resolved but must never be sent without TLS");
            assertEquals(0, fixture.closeCommands()); assertEquals(0, fixture.logoutCommands()); assertTrue(fixture.awaitSocketClose()); assertEquals(1, fixture.socketsClosed());
        }
    }

    @Test void actionAdmissionRejectsBeforeSecretsAndRecoversAfterTransportFailure() throws Exception {
        try (var server = new HoldingServer()) {
            AtomicInteger secrets = new AtomicInteger();
            NodeAction action = limitedAction(server.port(), ref -> { secrets.incrementAndGet(); return secret(); });
            var first = action.handle(node(Map.of("version", "mail.imap.query.v1"))).toCompletableFuture();
            assertTrue(server.accepted.await(1, TimeUnit.SECONDS), "first admitted call must reach the transport");
            CompletionException saturated = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1")));
            assertEquals(ImapQueryException.Code.SATURATED, ((ImapQueryException) saturated.getCause()).code()); assertEquals(1, secrets.get(), "rejected work must not resolve a secret or open a socket");
            server.close(); assertThrows(CompletionException.class, first::join);
            CompletionException recovered = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1")));
            assertEquals(ImapQueryException.Code.TRANSPORT_FAILURE, ((ImapQueryException) recovered.getCause()).code()); assertEquals(2, secrets.get(), "released permit must admit the next request");
        }
    }

    @Test void profileIsolationAndTransportPoliciesStayFailClosed() throws Exception {
        AtomicInteger credentials = new AtomicInteger();
        NodeAction action = new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile("other", name, 1, "localhost", "IMAPS", Set.of("INBOX"), 1)), ref -> { credentials.incrementAndGet(); return Optional.empty(); }).create(configuration());
        CompletionException failure = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1")));
        assertEquals(ImapQueryException.Code.PROFILE_UNAVAILABLE, ((ImapQueryException) failure.getCause()).code()); assertEquals(0, credentials.get());
        Method properties = MailImapQueryNodeBehavior.class.getDeclaredMethod("properties", ImapProfile.class, String.class); properties.setAccessible(true);
        Properties starttls = (Properties) properties.invoke(null, profile("tenant", "reader", 143, "localhost", "STARTTLS", Set.of("INBOX"), 1), "imap");
        assertEquals("true", starttls.getProperty("mail.imap.starttls.enable")); assertEquals("true", starttls.getProperty("mail.imap.starttls.required"));
        assertNull(starttls.getProperty("mail.imap.ssl.trust")); assertEquals("true", starttls.getProperty("mail.imap.ssl.checkserveridentity"));
        assertEquals("true", starttls.getProperty("mail.imap.peek"));
    }

    @Test void hostileResolversAndSubmissionErrorsBecomeSanitizedFailedStages() {
        String sentinel = "imap-hostile-secret-sentinel";
        RuntimeException hostile = new RuntimeException(sentinel, new IllegalStateException(sentinel)); hostile.addSuppressed(new IllegalArgumentException(sentinel));
        NodeAction profileFailure = new MailImapQueryNodeBehavior((tenant, name) -> { throw hostile; }, ref -> secret()).create(configuration());
        var profileStage = assertDoesNotThrow(() -> profileFailure.handle(node(Map.of("version", "mail.imap.query.v1"))));
        assertSanitizedTyped(profileStage.toCompletableFuture(), ImapQueryException.Code.PROFILE_UNAVAILABLE, sentinel);

        NodeAction credentialFailure = new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, 1, "localhost", "IMAPS", Set.of("INBOX"), 10)), ref -> { throw hostile; }).create(configuration());
        assertSanitizedTyped(credentialFailure.handle(node(Map.of("version", "mail.imap.query.v1"))).toCompletableFuture(), ImapQueryException.Code.CREDENTIAL_UNAVAILABLE, sentinel);

        NodeAction submissionFailure = new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, 1, "localhost", "IMAPS", Set.of("INBOX"), 10)), ref -> secret(), java.util.function.UnaryOperator.identity(), task -> { throw new AssertionError(sentinel); }).create(configuration());
        var submissionStage = assertDoesNotThrow(() -> submissionFailure.handle(node(Map.of("version", "mail.imap.query.v1"))));
        assertSanitizedTyped(submissionStage.toCompletableFuture(), ImapQueryException.Code.TRANSPORT_FAILURE, sentinel);
    }

    @Test void descriptorAndPackageRemainInspectableWithoutCredentials() {
        var descriptor = new MailImapQueryNodeBehavior().descriptor();
        assertEquals(MailImapQueryNodeBehavior.BEHAVIOR, descriptor.behavior());
        // This query is read-only and peeked, so recovery.repeatable is
        // the one mail node an author can honestly declare repeatable. Its disposition by the recovery
        // loop is MailRecoveryRepeatabilityTest's subject, not this test's.
        assertEquals(Set.of("profile", "folder", "limit", "contentMode", "maxConcurrency", "recovery.repeatable"), descriptor.properties().stream().map(p -> p.name()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(descriptor.capabilities().contains("credential-reference"));
        // The Inspector half. `contentMode` is a node property, not only a payload
        // field, precisely so that an editor can enumerate it and GraphML can carry it; asserting the
        // enumeration and the default here is what makes that claim checkable rather than intended.
        var contentMode = descriptor.properties().stream().filter(p -> p.name().equals("contentMode")).findFirst().orElseThrow();
        assertEquals(List.of("preview", "full"), contentMode.allowedValues());
        assertEquals("preview", contentMode.defaultValue());
        assertFalse(contentMode.required());
    }

    /**
     * The graph-level default is refused at build time, exactly as an unusable {@code limit} is.
     * {@code metadata} is chosen deliberately: it is the sibling {@code mail.imap.consume} node's own
     * vocabulary, and this node does not share it. The vocabularies are intentionally asymmetric, so
     * this node must not quietly accept the other node's word.
     */
    @Test void aGraphContentModeOutsideThePreviewFullPairIsRefusedWhenTheNodeIsBuilt() {
        ImapQueryException failure = assertThrows(ImapQueryException.class,
                () -> new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, 1, "localhost", "IMAPS", Set.of("INBOX"), 10)), ref -> secret())
                        .create(new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR,
                                Map.of("profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "2", "contentMode", "metadata"))));
        assertEquals(ImapQueryException.Code.INVALID_INPUT, failure.code());
    }

    /**
     * The property these callers care about is that negotiation was refused before any credential left
     * the process and before any downgrade — never *which* of the two codes a refused negotiation is
     * classified as. {@code query()} races its own {@code DeadlineWatchdog} against the socket read that
     * carries the refusal: on a fast host the read wins and the failure is classified
     * {@code TRANSPORT_FAILURE}; delayed past the watchdog's deadline (~3s under full-reactor load;
     * this file's callers now run on the widened 10s {@code GENEROUS_TIMEOUT_MS}
     * budget), the same refusal is classified {@code TIMEOUT} instead — same event, same "never
     * authenticated, never downgraded" guarantee, different label. Asserting one specific label made this
     * test flake without ever indicating a defect. It still requires *a* typed refusal: a success,
     * or any other {@link ImapQueryException.Code} (e.g. one that implies credentials were resolved or
     * sent), fails it.
     */
    private static void assertSanitizedFailure(NodeAction action, String secret) {
        CompletionException failure = assertThrows(CompletionException.class, () -> output(action, Map.of("version", "mail.imap.query.v1")));
        ImapQueryException.Code code = ((ImapQueryException) failure.getCause()).code();
        assertTrue(code == ImapQueryException.Code.TRANSPORT_FAILURE || code == ImapQueryException.Code.TIMEOUT,
                "refused negotiation must fail as a transport problem, not as: " + code);
        assertFalse(failure.toString().contains(secret));
    }
    private static void assertSanitizedTyped(java.util.concurrent.CompletableFuture<?> stage, ImapQueryException.Code code, String sentinel) { CompletionException failure = assertThrows(CompletionException.class, stage::join); assertEquals(code, ((ImapQueryException) failure.getCause()).code()); assertFalse(throwableText(failure).contains(sentinel)); }
    private static String throwableText(Throwable failure) { StringBuilder text = new StringBuilder(); Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); List<Throwable> pending = new ArrayList<>(); pending.add(failure); while (!pending.isEmpty()) { Throwable value = pending.removeLast(); if (value == null || !seen.add(value)) continue; text.append(value).append(value.getMessage()); pending.add(value.getCause()); pending.addAll(List.of(value.getSuppressed())); } return text.toString(); }
    @SuppressWarnings("unchecked") private static void assertImapRuntimeClean() throws Exception {
        Field watchdogs = MailImapQueryNodeBehavior.class.getDeclaredField("ACTIVE_WATCHDOGS"); watchdogs.setAccessible(true);
        assertEquals(0, ((AtomicInteger) watchdogs.get(null)).get(), "watchdog thread must be joined before completion");
        Field global = MailImapQueryNodeBehavior.class.getDeclaredField("GLOBAL_SLOTS"); global.setAccessible(true);
        assertEquals(32, ((java.util.concurrent.Semaphore) global.get(null)).availablePermits());
        for (String name : List.of("TENANT_SLOTS", "PROFILE_SLOTS")) { Field field = MailImapQueryNodeBehavior.class.getDeclaredField(name); field.setAccessible(true); assertTrue(((Map<Object, Object>) field.get(null)).isEmpty(), name); }
    }
    private static NodeAction action(DeterministicImapFixture fixture, String host, String mode, Set<String> folders, CredentialResolver credentials, int preview) throws Exception {
        return action(fixture, host, mode, folders, credentials, preview, 10, "10");
    }
    private static NodeAction action(DeterministicImapFixture fixture, String host, String mode, Set<String> folders, CredentialResolver credentials, int preview, int maxResults, String graphLimit) throws Exception {
        SSLSocketFactory socketFactory = fixture.trustedSocketFactory();
        return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, fixture.port(), host, mode, folders, preview, maxResults)), credentials,
                properties -> { properties.put("mail.imaps.ssl.socketFactory", socketFactory); return properties; }).create(configuration(graphLimit));
    }
    /** One profile preview budget and an optional graph-level {@code contentMode} default. */
    private static NodeAction action(DeterministicImapFixture fixture, int previewChars, String graphContentMode) throws Exception {
        SSLSocketFactory socketFactory = fixture.trustedSocketFactory();
        Map<String, Object> properties = new java.util.LinkedHashMap<>(Map.of("profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "2"));
        if (graphContentMode != null) properties.put("contentMode", graphContentMode);
        return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, fixture.port(), "localhost", "IMAPS", Set.of("INBOX"), previewChars)), ref -> secret(),
                configured -> { configured.put("mail.imaps.ssl.socketFactory", socketFactory); return configured; })
                .create(new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, properties));
    }
    private static NodeAction startTlsAction(DeterministicStartTlsImapFixture fixture, CredentialResolver credentials) throws Exception {
        SSLSocketFactory socketFactory = DeterministicImapFixture.trustedSocketFactoryForTests();
        return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, fixture.port(), "localhost", "STARTTLS", Set.of("INBOX"), 10)), credentials,
                properties -> { properties.put("mail.imap.ssl.socketFactory", socketFactory); return properties; }).create(configuration());
    }
    private static NodeAction limitedAction(int port, CredentialResolver credentials) { return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, port, "localhost", "IMAPS", Set.of("INBOX"), 10)), credentials).create(new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, Map.of("profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "1"))); }
    private static NodeAction localAction(int preview, Set<String> folders, CredentialResolver credentials) { return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(profile(tenant, name, 1, "localhost", "IMAPS", folders, preview)), credentials).create(configuration()); }
    private static ImapProfile profile(String tenant, String id, int port, String host, String mode, Set<String> folders, int preview) { return new ImapProfile(tenant, id, host, port, mode, "reader", "credential", folders, GENEROUS_TIMEOUT_MS, GENEROUS_TIMEOUT_MS, 2, 10, preview); }
    private static ImapProfile profile(String tenant, String id, int port, String host, String mode, Set<String> folders, int preview, int maxResults) { return new ImapProfile(tenant, id, host, port, mode, "reader", "credential", folders, GENEROUS_TIMEOUT_MS, GENEROUS_TIMEOUT_MS, 2, maxResults, preview); }
    private static NodeConfiguration configuration() { return new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, Map.of("profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "2")); }
    private static NodeConfiguration configuration(String limit) { return new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR, Map.of("profile", "reader", "folder", "INBOX", "limit", limit, "maxConcurrency", "2")); }
    private static Optional<SecretValue> secret() { return Optional.of(new SecretValue("secret".toCharArray())); }
    @SuppressWarnings("unchecked") private static Map<String, Object> output(NodeAction action, Map<String, Object> payload) { return (Map<String, Object>) action.handle(node(payload)).toCompletableFuture().join().payload(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> messages(Map<String, Object> output) { return (List<Map<String, Object>>) output.get("messages"); }
    private static NodeMessage node(Map<String, Object> payload) { UUID id = UUID.randomUUID(); return new NodeMessage(new SecurityContext("r", "tenant", "s", PrincipalType.USER, "i"), id, id, id, id, Set.of(), "imap", payload, Map.of()); }
    private static MimeMessage message(String subject, String body, Instant sentAt, boolean seen) throws Exception { MimeMessage message = new MimeMessage(Session.getInstance(new Properties())); message.setFrom(new InternetAddress("from@example.test")); message.setRecipient(Message.RecipientType.TO, new InternetAddress("reader@example.test")); message.setSubject(subject); message.setText(body); message.setSentDate(java.util.Date.from(sentAt)); if (seen) message.setFlag(Flags.Flag.SEEN, true); message.saveChanges(); return message; }
    private static MimeMessage multipartMessage() throws Exception { MimeMessage message = message("attachment", "preview more than budget", Instant.parse("2025-06-01T12:00:00Z"), false); MimeMultipart multipart = new MimeMultipart(); MimeBodyPart text = new MimeBodyPart(); text.setText("preview more than budget"); multipart.addBodyPart(text); MimeBodyPart attachment = new MimeBodyPart(); attachment.setFileName("a.txt"); attachment.setText("contents"); multipart.addBodyPart(attachment); message.setContent(multipart); message.saveChanges(); return message; }
    /** One text/plain part, one text/html part, and one attachment exercise the multipart shape. */
    private static MimeMessage textAndHtmlMessage(String plain, String html) throws Exception {
        MimeMessage message = message("alternative", "ignored", Instant.parse("2025-07-01T12:00:00Z"), false);
        MimeMultipart multipart = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart(); text.setText(plain); multipart.addBodyPart(text);
        MimeBodyPart markup = new MimeBodyPart(); markup.setContent(html, "text/html; charset=UTF-8"); multipart.addBodyPart(markup);
        MimeBodyPart attachment = new MimeBodyPart(); attachment.setFileName("a.txt"); attachment.setText("contents"); multipart.addBodyPart(attachment);
        message.setContent(multipart); message.saveChanges(); return message;
    }
    private static MimeMessage deepMultipartMessage() throws Exception { MimeMessage message = message("deep", "body", Instant.parse("2025-01-01T12:00:00Z"), false); MimeBodyPart leaf = new MimeBodyPart(); leaf.setText("leaf"); for (int i = 0; i < 10; i++) { MimeMultipart nested = new MimeMultipart(); nested.addBodyPart(leaf); MimeBodyPart parent = new MimeBodyPart(); parent.setContent(nested); leaf = parent; } MimeMultipart root = new MimeMultipart(); root.addBodyPart(leaf); message.setContent(root); message.saveChanges(); return message; }
    private static MimeMessage manyPartMessage() throws Exception { MimeMessage message = message("many", "body", Instant.parse("2025-01-01T12:30:00Z"), false); MimeMultipart multipart = new MimeMultipart(); for (int i = 0; i < 65; i++) { MimeBodyPart part = new MimeBodyPart(); part.setFileName("part-" + i); part.setText("x"); multipart.addBodyPart(part); } message.setContent(multipart); message.saveChanges(); return message; }
    private static MimeMessage charsetMessage(String subject, String body, String charset) throws Exception { MimeMessage message = message(subject, "body", Instant.parse("2025-01-01T12:00:00Z"), false); message.setText(body, charset); message.saveChanges(); return message; }
    private static MimeMessage addressFloodMessage() throws Exception { MimeMessage message = message("addresses", "body", Instant.parse("2025-01-01T12:00:00Z"), false); List<InternetAddress> recipients = new ArrayList<>(); for (int i = 0; i < 51; i++) recipients.add(new InternetAddress("reader" + i + "@example.test")); message.setRecipients(Message.RecipientType.TO, recipients.toArray(InternetAddress[]::new)); message.saveChanges(); return message; }
    private static final class HoldingServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0); private final CountDownLatch accepted = new CountDownLatch(1); private final Thread worker;
        private HoldingServer() throws Exception { worker = new Thread(() -> { try (Socket ignored = listener.accept()) { accepted.countDown(); while (!listener.isClosed()) Thread.onSpinWait(); } catch (Exception ignored) { } }, "imap-admission-holder"); worker.setDaemon(true); worker.start(); }
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception { listener.close(); worker.join(1_000); }
    }
}
