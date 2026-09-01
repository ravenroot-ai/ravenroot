package ai.ravenroot.extensions.mail;

import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An operator must be able to tell a profile nobody created (absent) apart from one somebody
 * wrote and got wrong (rejected), a rejection must name the constraint it tripped, and that name
 * must never carry the value that tripped it.
 *
 * <h2>Why {@code resolve} is not exercised through a changed signature</h2>
 * <p>{@link EnvironmentMailProfileResolver#resolve} stays exactly what it was: a total
 * {@code Optional<MailProfile>}. Its one production caller, {@code MailSendNodeBehavior.Settings.from},
 * already turns every empty result into a single {@code MailSendException} with a single message,
 * uniformly -- it neither needs nor is allowed to hand a graph-visible distinction between "unset"
 * and "malformed" back to a tenant. The distinction is for the operator reading
 * logs, not for that caller's control flow, so it is carried on the separate channel {@code
 * EnvironmentMailProfileResolver} already gained: a {@link System.Logger} warning, following the
 * identifiers-only discipline that {@code EnvironmentMailProfileResolver#logRejection} states in its
 * own Javadoc -- where it was restated when {@code UnconfiguredAdapterRefusal}, the class this line
 * used to cite, left the core with the AI nodes.
 *
 * <h2>Why one word is not enough</h2>
 * <p>Naming the <em>act</em> of rejecting ({@code RECORD_POLICY}) rather
 * than the constraint that caused it, because it caught {@code MailProfile}'s single, generic
 * validation exception without distinguishing which of its many disjuncts had fired. Every scenario
 * below except the last therefore pins a <em>specific</em> constraint name -- one that {@code
 * EnvironmentMailProfileResolver} now determines by pre-checking {@code MailProfile}'s constraints
 * itself, before construction, rather than by guessing from what construction throws.
 * {@link #distinctIssueNamedCausesNeverShareAConstraintName} pins that regression directly: an
 * unrecognised security mode, an out-of-range limit, and a
 * username with no credential reference -- must each produce a different word, and none of them may
 * be the residual {@code RECORD_POLICY}.
 *
 * <h2>What this test actually scans</h2>
 * <p>Every case below plants {@link #SENTINEL} in the credential-reference field of a profile string
 * engineered to be rejected for one specific, different reason, then scans <em>every</em> captured
 * {@link LogRecord} -- rendered message, raw parameters, and any attached throwable -- for it. A
 * sentinel that leaked only because nothing today happens to print that particular field would still
 * be caught here, because the scan is over every record captured on the resolver's own logger, not
 * over the one string this test's own assertions expected. See {@link #withCapturedLogs} for why
 * capture is scoped to that one named logger rather than the root.
 */
class MailProfileRejectionDiagnosticsTest {
    private static final String SENTINEL = "sentinel-credential-ref-98a6f1";

    @Test void absentProfileLogsNothingAndRejectedProfilesNameTheirConstraintWithoutTheValue() {
        withCapturedLogs(captured -> {
            // A profile nobody created reports nothing new.
            var resolver = new EnvironmentMailProfileResolver(Map.of());
            assertTrue(resolver.resolve("default", "absent").isEmpty());
            assertTrue(captured.get().isEmpty(), "an absent profile must not produce any diagnostic at all");

            // Present, but only eight of the ten mandatory fields.
            assertRejected(captured, "fieldcount",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test",
                    "FIELD_COUNT");

            // The accepted counts are ten or eleven, so the upper boundary needs its own
            // case: a twelfth field is still FIELD_COUNT, and "the parser accepts more than it used
            // to" must not decay into "the parser stopped counting".
            assertRejected(captured, "toomanyfields",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL
                            + ";from@example.test;to@example.test;X-Trace;2;reply@example.test;surplus",
                    "FIELD_COUNT");

            // Present, ten fields, a port that is not an integer.
            assertRejected(captured, "badport",
                    "smtp.example.test;not-a-port;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2",
                    "PORT_FORMAT");

            // Present, ten fields, valid port, a concurrency ceiling that is not an integer.
            assertRejected(captured, "badconcurrency",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;many",
                    "CONCURRENCY_FORMAT");

            // Present, well-formed, but the host is blank.
            assertRejected(captured, "blankhost",
                    ";587;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2",
                    "HOST_BLANK");

            // Present, well-formed, but the security mode is not one MailProfile recognises.
            assertRejected(captured, "badmode",
                    "smtp.example.test;587;CARRIER-PIGEON;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2",
                    "UNKNOWN_SECURITY_MODE");

            // Present, well-formed, a syntactically valid but out-of-range port.
            assertRejected(captured, "outofrangeport",
                    "smtp.example.test;70000;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2",
                    "PORT_RANGE");

            // Present, well-formed, a syntactically valid but out-of-range concurrency ceiling.
            assertRejected(captured, "outofrangeconcurrency",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;17",
                    "CONCURRENCY_RANGE");

            // Present, well-formed, a username with no paired credential reference (the credential
            // reference itself carries the sentinel, so the mismatch is the other direction: a
            // non-blank credential reference with a blank username).
            assertRejected(captured, "unpairedcredential",
                    "smtp.example.test;587;STARTTLS;false;;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2",
                    "CREDENTIAL_PAIRING");

            // Present, well-formed, a recipient policy field with a repeated entry.
            assertRejected(captured, "duplicaterecipient",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test,to@example.test;X-Trace;2",
                    "DUPLICATE_LIST_ENTRY");

            // Present, well-formed, an empty sender allow-list.
            assertRejected(captured, "emptyfrom",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";;to@example.test;X-Trace;2",
                    "ALLOWED_FROM_EMPTY");

            // Present, well-formed, an empty recipient allow-list.
            assertRejected(captured, "emptyrecipients",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;;X-Trace;2",
                    "ALLOWED_RECIPIENTS_EMPTY");

            // Present, well-formed, a non-empty sender allow-list whose default sender is not a
            // member of it (the shared field carries two addresses, so the unsplit default matches
            // neither split element).
            assertRejected(captured, "defaultfromnotallowed",
                    "smtp.example.test;587;STARTTLS;false;mailer;" + SENTINEL + ";a@example.test,b@example.test;to@example.test;X-Trace;2",
                    "DEFAULT_FROM_NOT_ALLOWED");
        });
    }

    /**
     * An unrecognised security mode, an out-of-range limit, and an unpaired credential must each produce a
     * distinct word, and none of the three may collapse into the residual {@code RECORD_POLICY}; a
     * generic catch would collapse all three at once.
     */
    @Test void distinctIssueNamedCausesNeverShareAConstraintName() {
        withCapturedLogs(captured -> {
            String unknownMode = constraintOf(captured, "modeconstraint",
                    "smtp.example.test;587;CARRIER-PIGEON;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2");
            String outOfRange = constraintOf(captured, "rangeconstraint",
                    "smtp.example.test;70000;STARTTLS;false;mailer;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2");
            String unpairedCredential = constraintOf(captured, "pairingconstraint",
                    "smtp.example.test;587;STARTTLS;false;;" + SENTINEL + ";from@example.test;to@example.test;X-Trace;2");

            assertEquals("UNKNOWN_SECURITY_MODE", unknownMode);
            assertEquals("PORT_RANGE", outOfRange);
            assertEquals("CREDENTIAL_PAIRING", unpairedCredential);
            assertNotEquals(unknownMode, outOfRange);
            assertNotEquals(unknownMode, unpairedCredential);
            assertNotEquals(outOfRange, unpairedCredential);
            for (String constraint : List.of(unknownMode, outOfRange, unpairedCredential))
                assertNotEquals("RECORD_POLICY", constraint, "a specific cause must not fall back to the residual net");
        });
    }

    @FunctionalInterface private interface LogScan { void run(java.util.function.Supplier<List<LogRecord>> captured); }

    /**
     * This used to attach to the root logger, the exact defect measured on the IMAP-side sibling
     * {@code ImapProfileRejectionDiagnosticsTest} -- found there once in thirteen runs as a {@code
     * ConcurrentModificationException}, because every JUL record produced anywhere in the fork,
     * including background threads outliving some other test's fixture, landed in an unsynchronized
     * {@link ArrayList} that this method's caller iterates without a snapshot. This class is exposed to
     * the identical shape: it runs no live socket fixture of its own, but shares a JVM with tests that
     * do, and root-attachment does not scope by caller.
     *
     * <p>The named-logger approach follows the pattern already proven in
     * {@code ImapProfileResolverRecordDifferentialTest#resolve}: attach to
     * the exact named logger production writes to ({@code ai.ravenroot.mail.profile.rejected}, see
     * {@code EnvironmentMailProfileResolver}'s {@code LOGGER} field) with {@code
     * setUseParentHandlers(false)}, rather than the root. {@code EnvironmentMailProfileResolver} has
     * exactly one call site that logs, on exactly this logger (verified by inspection), so every record
     * this handler can see is one this resolver itself produced -- nothing unrelated can write here, so
     * nothing unrelated can race with the read.
     */
    private static void withCapturedLogs(LogScan scenario) {
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { captured.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger("ai.ravenroot.mail.profile.rejected");
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try { scenario.run(() -> captured); }
        finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    private static void assertRejected(java.util.function.Supplier<List<LogRecord>> captured, String profileName,
                                        String rawValue, String expectedConstraint) {
        assertEquals(expectedConstraint, constraintOf(captured, profileName, rawValue));
    }

    private static String constraintOf(java.util.function.Supplier<List<LogRecord>> captured, String profileName, String rawValue) {
        captured.get().clear();
        var resolver = new EnvironmentMailProfileResolver(Map.of(
                EnvironmentMailProfileResolver.environmentVariableName("default", profileName), rawValue));
        assertTrue(resolver.resolve("default", profileName).isEmpty(), () -> profileName + " must be rejected, not resolved");
        List<LogRecord> records = captured.get();
        assertFalse(records.isEmpty(), () -> profileName + " must log a diagnostic");
        for (LogRecord record : records) assertSentinelAbsent(record);
        List<String> constraints = records.stream().map(MailProfileRejectionDiagnosticsTest::renderedConstraint).distinct().toList();
        assertEquals(1, constraints.size(), () -> profileName + " logged more than one distinct constraint: " + constraints);
        return constraints.get(0);
    }

    /** Pulls the {@code constraint=...} token out of the rendered line without assuming its format. */
    private static String renderedConstraint(LogRecord record) {
        String text = rendered(record);
        int at = text.indexOf("constraint=");
        assertTrue(at >= 0, () -> "no constraint token in: " + text);
        return text.substring(at + "constraint=".length()).trim();
    }

    private static void assertSentinelAbsent(LogRecord record) {
        assertFalse(rendered(record).contains(SENTINEL), () -> "log line leaked the profile value: " + rendered(record));
        if (record.getParameters() != null)
            for (Object param : record.getParameters())
                assertFalse(String.valueOf(param).contains(SENTINEL), "a raw log parameter carried the profile value");
        assertNull(record.getThrown(), "a rejection diagnostic must not attach a throwable");
    }

    private static String rendered(LogRecord record) {
        String pattern = record.getMessage();
        Object[] params = record.getParameters();
        return params == null || params.length == 0 ? pattern : MessageFormat.format(pattern, params);
    }
}
