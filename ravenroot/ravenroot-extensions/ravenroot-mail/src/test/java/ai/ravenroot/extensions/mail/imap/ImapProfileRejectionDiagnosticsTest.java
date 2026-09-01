package ai.ravenroot.extensions.mail.imap;

import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * An operator must be able to tell an IMAP profile nobody created (absent) apart from one
 * somebody wrote and got wrong (rejected), a rejection must name the constraint it tripped, and that
 * name must never carry the value that tripped it.
 *
 * <p>Previously, neither half existed on the IMAP side. The two cases were not merely hard to
 * tell apart: {@code p.length == 11 ? ... : Optional.empty()} inside a {@code catch (RuntimeException
 * e) { return Optional.empty(); }} made an unset environment variable and a miscounted one the
 * <em>identical</em> return value, with no logger and no constraint vocabulary anywhere.
 *
 * <h2>Why {@code resolve} is not exercised through a changed signature</h2>
 * <p>{@link EnvironmentImapProfileResolver#resolve} stays a total {@code Optional<ImapProfile>}. Its
 * one production caller, {@code MailImapQueryNodeBehavior.resolveProfile}, already turns every empty
 * result into a single {@code ImapQueryException(PROFILE_UNAVAILABLE, "IMAP profile unavailable")},
 * uniformly -- it neither needs nor is allowed to hand a graph-visible distinction between "unset"
 * and "malformed" back to a tenant. The distinction is for the operator reading logs, so it travels
 * on the separate channel the resolver gained.
 *
 * <h2>Why nineteen names and not one</h2>
 * <p>{@link ImapProfile}'s validation is one disjunction of thirteen conditions raising one generic
 * {@code IllegalArgumentException}, so catching it cannot recover which condition fired. On the SMTP
 * side, naming the <em>act</em> of rejecting sent
 * ten distinct causes to the same word. {@link #everyNamedConstraintIsReachableAndDistinct} pins the
 * opposite property directly: every name below is produced by some input, and no two inputs that
 * differ in cause share a name.
 *
 * <h2>What this test actually scans</h2>
 * <p>Every scenario plants {@link #SENTINEL} in a field of a profile string engineered to be rejected,
 * then scans <em>every</em> captured {@link LogRecord} -- rendered message, each raw positional
 * parameter, and any attached throwable -- for it. The scan covers all records captured on the
 * resolver's own logger, not only the ones {@link #ourRecords} recognises by message prefix, so a
 * value the resolver logged in some other shape -- one this test does not yet know to expect -- would
 * still be caught. See {@link #withCapturedLogs} for why capture is scoped to that one named logger
 * rather than the root.
 *
 * <p>{@link #noFieldOfTheValueEverReachesTheDiagnostic} goes past the SMTP side's single planting
 * site: it walks the sentinel through <em>each of the eleven fields in turn</em>. That matters here
 * because this format has more leak vectors than SMTP's, and two of them are measured, not assumed:
 * {@code Integer.parseInt} raises {@code NumberFormatException: For input string: "<value>"} and this
 * format has six numeric fields; {@code Set.of(String...)} raises {@code IllegalArgumentException:
 * duplicate element: <folder>}. Every one of those exceptions carries an operator field in its
 * message, and none of them may be forwarded.
 */
class ImapProfileRejectionDiagnosticsTest {
    private static final String SENTINEL = "sentinel-imap-secret-4c1d7b";
    private static final String TENANT = "default";

    /** host;port;securityMode;username;credentialRef;folders;connectMs;readMs;concurrency;results;previewChars */
    private static final String[] VALID =
            {"imap.example.test", "993", "IMAPS", "mailer", "primary", "INBOX", "10000", "30000", "4", "50", "2048"};

    private static String with(int field, String value) {
        String[] p = VALID.clone();
        p[field] = value;
        return String.join(";", p);
    }

    private static final int HOST = 0, PORT = 1, MODE = 2, USERNAME = 3, CREDENTIAL_REF = 4,
            FOLDERS = 5, CONNECT_MS = 6, READ_MS = 7, CONCURRENCY = 8, MAX_RESULTS = 9, PREVIEW_CHARS = 10;

    // ---------------------------------------------------------------- Absence, success, and named rejections

    @Test void absentProfileLogsNothingAndAWellFormedOneResolvesSilently() {
        withCapturedLogs(captured -> {
            // A profile nobody created reports nothing new. An identifier that never reaches a
            // lookup at all must be just as silent as an unset variable, so both are exercised.
            var empty = new EnvironmentImapProfileResolver(Map.of());
            assertTrue(empty.resolve(TENANT, "absent").isEmpty());
            assertTrue(ourRecords(captured).isEmpty(), "an unset profile must not produce any diagnostic at all");
            assertTrue(empty.resolve(TENANT, "not a legal identifier").isEmpty());
            assertTrue(ourRecords(captured).isEmpty(), "an identifier that never reaches a lookup must stay silent too");

            // The happy path completes the distinction: if a resolved profile logged, the
            // presence of a line would stop meaning "somebody wrote this and got it wrong".
            var valid = new EnvironmentImapProfileResolver(Map.of(
                    EnvironmentImapProfileResolver.environmentVariableName(TENANT, "good"), String.join(";", VALID)));
            assertTrue(valid.resolve(TENANT, "good").isPresent(), "the baseline profile must resolve");
            assertTrue(ourRecords(captured).isEmpty(), "a profile that resolves must not log a rejection");
        });
    }

    @Test void everyRejectionNamesItsOwnConstraint() {
        withCapturedLogs(captured -> {
            // No record-side counterpart: with the wrong count there is no argument list to attempt.
            // Both boundaries, because "the parser accepts eleven" must not decay into "the parser
            // stopped counting" -- ten is what the SMTP format uses, and is wrong here.
            assertRejected(captured, "tenfields",
                    "imap.example.test;993;IMAPS;mailer;" + SENTINEL + ";INBOX;10000;30000;4;50", "FIELD_COUNT");
            assertRejected(captured, "twelvefields",
                    "imap.example.test;993;IMAPS;mailer;" + SENTINEL + ";INBOX;10000;30000;4;50;2048;surplus", "FIELD_COUNT");

            assertRejected(captured, "blankhost", sentinelled(HOST, ""), "HOST_BLANK");
            assertRejected(captured, "badport", sentinelled(PORT, "not-a-port"), "PORT_FORMAT");
            assertRejected(captured, "outofrangeport", sentinelled(PORT, "70000"), "PORT_RANGE");
            assertRejected(captured, "badmode", sentinelled(MODE, "CARRIER-PIGEON"), "UNKNOWN_SECURITY_MODE");

            // ImapProfile compares securityMode against Set.of("IMAPS","STARTTLS") WITHOUT
            // normalising it, unlike MailProfile which upper-cases first. A pre-check copied from the
            // SMTP resolver would upper-case, accept this value, and hand it to a record that refuses
            // it -- looser than the constraint it stands in for, and landing in the residual net.
            assertRejected(captured, "lowercasemode", sentinelled(MODE, "imaps"), "UNKNOWN_SECURITY_MODE");

            // Two names, not SMTP's single CREDENTIAL_PAIRING: ImapProfile requires both fields
            // non-blank as two separate disjuncts, where MailProfile permits both blank together.
            // The sentinel moves to the username for the second case, since the field under test
            // must itself be blank.
            assertRejected(captured, "blankusername", sentinelled(USERNAME, ""), "USERNAME_BLANK");
            String[] blankRef = VALID.clone();
            blankRef[USERNAME] = SENTINEL;
            blankRef[CREDENTIAL_REF] = "";
            assertRejected(captured, "blankcredentialref", String.join(";", blankRef), "CREDENTIAL_REF_BLANK");

            // Set.of would have named the repeated folder in its own exception message.
            assertRejected(captured, "duplicatefolder", sentinelled(FOLDERS, SENTINEL + "," + SENTINEL), "DUPLICATE_FOLDER");
            // Measured: ",".split(",") is a zero-length array, so this is the input that actually
            // empties the set.
            assertRejected(captured, "emptyfolders", sentinelled(FOLDERS, ","), "FOLDERS_EMPTY");
            // A folders field that is blank, or whitespace-only, used to build a set of size one
            // -- {""} or {" "} -- which passed the old "is the raw set empty" test and resolved into a
            // profile that could never match any requested folder (a blank requested folder is itself
            // rejected upstream). It must now be named the same as the field of only commas above,
            // because both reduce to no real folder names.
            assertRejected(captured, "blankfolders", sentinelled(FOLDERS, ""), "FOLDERS_EMPTY");
            assertRejected(captured, "whitespaceonlyfolders", sentinelled(FOLDERS, " "), "FOLDERS_EMPTY");
            // The actual folder boundary, pinned by name so the README and this code cannot silently
            // diverge again: the raw-array duplicate check above runs first, before any name is
            // stripped or discarded, and compares entries byte-for-byte. Two blank entries are a
            // repeat -- the same way "INBOX,INBOX" is -- only when they are spelled identically: both
            // "" here. The whole profile is refused under DUPLICATE_FOLDER, not a name that mentions
            // folders being empty, even though no real folder name repeats. This is NOT a count of
            // blank entries -- see anyNumberOfDifferentlySpelledBlankFolderEntriesAreSilentlyDropped
            // below for the counter-example with two blank entries, spelled differently, that resolves.
            assertRejected(captured, "twoblankfolders", sentinelled(FOLDERS, "INBOX,,Archive,,Notes"), "DUPLICATE_FOLDER");
            assertRejected(captured, "leadingandmiddleblankfolders", sentinelled(FOLDERS, ",INBOX,,Archive"), "DUPLICATE_FOLDER");

            assertRejected(captured, "badconnect", sentinelled(CONNECT_MS, "soon"), "CONNECT_TIMEOUT_FORMAT");
            assertRejected(captured, "zeroconnect", sentinelled(CONNECT_MS, "0"), "CONNECT_TIMEOUT_RANGE");
            assertRejected(captured, "badread", sentinelled(READ_MS, "later"), "READ_TIMEOUT_FORMAT");
            assertRejected(captured, "zeroread", sentinelled(READ_MS, "0"), "READ_TIMEOUT_RANGE");
            assertRejected(captured, "badconcurrency", sentinelled(CONCURRENCY, "many"), "CONCURRENCY_FORMAT");
            assertRejected(captured, "outofrangeconcurrency", sentinelled(CONCURRENCY, "17"), "CONCURRENCY_RANGE");
            assertRejected(captured, "badresults", sentinelled(MAX_RESULTS, "lots"), "MAX_RESULTS_FORMAT");
            assertRejected(captured, "outofrangeresults", sentinelled(MAX_RESULTS, "501"), "MAX_RESULTS_RANGE");
            assertRejected(captured, "badpreview", sentinelled(PREVIEW_CHARS, "some"), "PREVIEW_CHARS_FORMAT");
            assertRejected(captured, "outofrangepreview", sentinelled(PREVIEW_CHARS, "65537"), "PREVIEW_CHARS_RANGE");
        });
    }

    /** Builds a rejected value carrying {@link #SENTINEL} in the credential reference, with one other
     *  field set to the value under test. Every case in {@link #everyRejectionNamesItsOwnConstraint}
     *  except the two credential cases rejects for a reason unrelated to the credential reference,
     *  so the sentinel is present, parsed past, and must still never appear in the diagnostic. */
    private static String sentinelled(int field, String value) {
        String[] p = VALID.clone();
        p[CREDENTIAL_REF] = SENTINEL;
        p[field] = value;
        return String.join(";", p);
    }

    /**
     * Pins the regression directly, in the form it would take here: the causes an operator
     * would confuse must each get a distinct word, every declared name must be produced by some
     * input, and none of them may be the residual {@code RECORD_POLICY}.
     */
    @Test void everyNamedConstraintIsReachableAndDistinct() {
        withCapturedLogs(captured -> {
            Map<String, String> causes = new java.util.LinkedHashMap<>();
            causes.put("FIELD_COUNT", "imap.example.test;993;IMAPS;mailer;primary;INBOX;10000;30000;4;50");
            causes.put("HOST_BLANK", with(HOST, ""));
            causes.put("PORT_FORMAT", with(PORT, "not-a-port"));
            causes.put("PORT_RANGE", with(PORT, "70000"));
            causes.put("UNKNOWN_SECURITY_MODE", with(MODE, "CARRIER-PIGEON"));
            causes.put("USERNAME_BLANK", with(USERNAME, ""));
            causes.put("CREDENTIAL_REF_BLANK", with(CREDENTIAL_REF, ""));
            causes.put("DUPLICATE_FOLDER", with(FOLDERS, "INBOX,INBOX"));
            causes.put("FOLDERS_EMPTY", with(FOLDERS, ","));
            causes.put("CONNECT_TIMEOUT_FORMAT", with(CONNECT_MS, "soon"));
            causes.put("CONNECT_TIMEOUT_RANGE", with(CONNECT_MS, "0"));
            causes.put("READ_TIMEOUT_FORMAT", with(READ_MS, "later"));
            causes.put("READ_TIMEOUT_RANGE", with(READ_MS, "0"));
            causes.put("CONCURRENCY_FORMAT", with(CONCURRENCY, "many"));
            causes.put("CONCURRENCY_RANGE", with(CONCURRENCY, "17"));
            causes.put("MAX_RESULTS_FORMAT", with(MAX_RESULTS, "lots"));
            causes.put("MAX_RESULTS_RANGE", with(MAX_RESULTS, "501"));
            causes.put("PREVIEW_CHARS_FORMAT", with(PREVIEW_CHARS, "some"));
            causes.put("PREVIEW_CHARS_RANGE", with(PREVIEW_CHARS, "65537"));

            Set<String> observed = new LinkedHashSet<>();
            int scenario = 0;
            for (Map.Entry<String, String> cause : causes.entrySet()) {
                String constraint = constraintOf(captured, "distinct" + scenario++, cause.getValue());
                assertEquals(cause.getKey(), constraint, () -> "wrong constraint for " + cause.getValue());
                assertNotEquals("RECORD_POLICY", constraint, "a named cause must not fall back to the residual net");
                assertTrue(observed.add(constraint), () -> "two distinct causes shared the name " + constraint);
            }

            // Every declared name except the residual net must have been produced by some input above.
            // A name nobody can reach is a name that tells an operator nothing, and would also hide a
            // pre-check that is dead because a check above it subsumes it.
            Set<String> declared = new LinkedHashSet<>();
            for (EnvironmentImapProfileResolver.Rejection r : EnvironmentImapProfileResolver.Rejection.values())
                if (r != EnvironmentImapProfileResolver.Rejection.RECORD_POLICY) declared.add(r.name());
            assertEquals(declared, observed, "declared constraint names and reachable ones must be the same set");
        });
    }

    // ---------------------------------------------------------------- The insidious folder shape

    /**
     * An entirely blank {@code folders} field is an obvious operator mistake, but a
     * single stray comma in an otherwise-correct list is not -- the profile already works for both
     * real folder names it names, and rejecting the whole profile over one phantom entry would force
     * an operator who already has a working profile to rewrite it. So this case must resolve, and the
     * blank entry the stray comma produced must not survive into the resolved profile's folder set.
     */
    @Test void strayCommaProducesABlankFolderEntryThatIsSilentlyDropped() {
        var resolver = new EnvironmentImapProfileResolver(Map.of(
                EnvironmentImapProfileResolver.environmentVariableName(TENANT, "straycomma"), with(FOLDERS, "INBOX,,Archive")));
        Optional<ImapProfile> resolved = resolver.resolve(TENANT, "straycomma");
        assertTrue(resolved.isPresent(), "a stray comma among otherwise-valid folder names must still resolve");
        assertEquals(Set.of("INBOX", "Archive"), resolved.get().folders(),
                "the blank entry the stray comma produced must not survive into the resolved profile");
    }

    /** The same shape with the stray comma leading, which is the one an operator produces by
     *  accidentally typing a separator before the first name rather than between two names. */
    @Test void leadingCommaProducesABlankFolderEntryThatIsSilentlyDropped() {
        var resolver = new EnvironmentImapProfileResolver(Map.of(
                EnvironmentImapProfileResolver.environmentVariableName(TENANT, "leadingcomma"), with(FOLDERS, ",INBOX")));
        Optional<ImapProfile> resolved = resolver.resolve(TENANT, "leadingcomma");
        assertTrue(resolved.isPresent(), "a leading stray comma before an otherwise-valid folder name must still resolve");
        assertEquals(Set.of("INBOX"), resolved.get().folders(),
                "the blank entry the leading comma produced must not survive into the resolved profile");
    }

    /**
     * The edge of the tolerance built above is NOT a count of blank entries -- it is "no two raw
     * entries are byte-identical". Two occurrences of the exact same blank spelling ({@code ""} twice)
     * are caught by the raw-array {@code DUPLICATE_FOLDER} check, which runs before any name is
     * stripped, the same way {@code "INBOX,INBOX"} is -- so the whole profile stops resolving, not
     * just the extra blank entry, reachable here from an input that otherwise names two real, distinct
     * folders. The rejection follows the raw duplicate rule even though the input also names real folders.
     */
    @Test void twoIdenticallySpelledBlankFolderEntriesRefuseTheWholeProfileNotJustTheExtraEntry() {
        var resolver = new EnvironmentImapProfileResolver(Map.of(
                EnvironmentImapProfileResolver.environmentVariableName(TENANT, "twoblanks"), with(FOLDERS, "INBOX,,Archive,,Notes")));
        Optional<ImapProfile> resolved = resolver.resolve(TENANT, "twoblanks");
        assertTrue(resolved.isEmpty(),
                "two identically-spelled blank folder entries must refuse the whole profile, even though it names two real, distinct folders");
    }

    /**
     * The counter-example that falsifies "at most one blank entry survives": {@code DUPLICATE_FOLDER}
     * compares raw, unstripped entries, so two blank entries spelled <em>differently</em> -- one
     * {@code ""} from a doubled comma, one {@code " "} from a comma followed by a space -- are not a
     * repeat of each other and both pass through to be discarded by {@code ImapProfile}. Any number of
     * blank entries survive, provided no two of them are spelled the same way; the profile resolves
     * with only the real folder names it names.
     */
    @Test void anyNumberOfDifferentlySpelledBlankFolderEntriesAreSilentlyDropped() {
        var middle = new EnvironmentImapProfileResolver(Map.of(
                EnvironmentImapProfileResolver.environmentVariableName(TENANT, "differentlyspelled"), with(FOLDERS, "INBOX, ,Archive,,Notes")));
        Optional<ImapProfile> resolvedMiddle = middle.resolve(TENANT, "differentlyspelled");
        assertTrue(resolvedMiddle.isPresent(), "two differently-spelled blank entries among valid names must still resolve");
        assertEquals(Set.of("INBOX", "Archive", "Notes"), resolvedMiddle.get().folders(),
                "both blank entries must be dropped, leaving only the three real folder names");

        var edges = new EnvironmentImapProfileResolver(Map.of(
                EnvironmentImapProfileResolver.environmentVariableName(TENANT, "leadingandtrailing"), with(FOLDERS, ",INBOX, ")));
        Optional<ImapProfile> resolvedEdges = edges.resolve(TENANT, "leadingandtrailing");
        assertTrue(resolvedEdges.isPresent(),
                "a leading comma plus a trailing comma-space -- the plainest typo an operator could make -- must still resolve");
        assertEquals(Set.of("INBOX"), resolvedEdges.get().folders(),
                "both blank entries (\"\" leading, \" \" trailing) must be dropped, leaving only INBOX");
    }

    // ---------------------------------------------------------------- Diagnostic redaction

    @Test void noFieldOfTheValueEverReachesTheDiagnostic() {
        withCapturedLogs(captured -> {
            // The forced rejection is on the LAST field checked, so for every other planting site the
            // field carrying the sentinel is actually reached and handled before the rejection fires
            // -- including the six numeric fields, where handling it means Integer.parseInt raises an
            // exception whose message quotes it verbatim.
            for (int field = 0; field <= PREVIEW_CHARS; field++) {
                String[] p = VALID.clone();
                p[PREVIEW_CHARS] = "65537";
                p[field] = SENTINEL;
                // constraintOf asserts the value was rejected, that a diagnostic was logged, and --
                // the point of this test -- that no captured record carries the sentinel anywhere.
                constraintOf(captured, "leak" + field, String.join(";", p));
            }
            // The one site whose exception names the value without any parsing involved.
            constraintOf(captured, "leakfolders", sentinelled(FOLDERS, SENTINEL + "," + SENTINEL));
            // ... and the credential-reference field.
            constraintOf(captured, "leakcredential", sentinelled(PORT, "not-a-port"));
        });
    }

    // ---------------------------------------------------------------- residual net

    /**
     * Measures, rather than asserts, the claim {@code EnvironmentImapProfileResolver} makes about
     * {@code RECORD_POLICY}: that under today's {@link ImapProfile} no input can reach it, because
     * eleven of the record's thirteen disjuncts are pre-checked by name and the other two are
     * excluded by the resolver's identifier guard.
     *
     * <p>One field wrong at a time, over an adversarial corpus per field, is where a missing or
     * subsumed pre-check would show: a value the resolver waves through and the record then refuses
     * has nowhere else to land. The differential property in {@code
     * ImapProfileResolverRecordDifferentialTest} covers combinations of several wrong fields at once.
     */
    @Test void noSingleFieldCorruptionCanReachTheResidualNet() {
        String[][] corpus = corpus();
        withCapturedLogs(captured -> {
            int scenario = 0;
            for (int field = 0; field < corpus.length; field++)
                for (String value : corpus[field]) {
                    String[] p = VALID.clone();
                    p[field] = value;
                    String raw = String.join(";", p);
                    String name = "net" + scenario++;
                    captured.get().clear();
                    var resolver = new EnvironmentImapProfileResolver(Map.of(
                            EnvironmentImapProfileResolver.environmentVariableName(TENANT, name), raw));
                    resolver.resolve(TENANT, name);
                    for (LogRecord record : ourRecords(captured))
                        assertNotEquals("RECORD_POLICY", renderedConstraint(record),
                                () -> "the residual net was reached by raw=\"" + raw + "\"");
                }
        });
    }

    /** Adversarial values per field, in field order; the shapes an operator actually mistypes. */
    private static String[][] corpus() {
        String[] blanks = {"", " "};
        String[] numeric = {"", " ", "abc", "-1", "0", "+7", "007", "2147483648", "1e3", "1 "};
        return new String[][]{
                {"", " ", "IMAP.EXAMPLE.TEST", " imap.example.test"},                       // host
                concat(numeric, new String[]{"1", "65535", "65536", "70000"}),               // port
                {"IMAPS", "STARTTLS", "imaps", "Imaps", "startTLS", "SMTPS", "SMTP", "", " ", "IMAPS "},
                blanks,                                                                      // username
                blanks,                                                                      // credentialRef
                {"", " ", ",", ",,", ",,,", "INBOX", "INBOX,", ",INBOX", "INBOX,INBOX", "INBOX, INBOX", "INBOX,Archive", " , "},
                concat(numeric, new String[]{"1", "120000", "300000"}),                      // connectMs
                concat(numeric, new String[]{"1", "300000", "600000"}),                      // readMs
                concat(numeric, new String[]{"1", "16", "17", "100"}),                       // concurrency
                concat(numeric, new String[]{"1", "500", "501", "1000"}),                    // maxResults
                concat(numeric, new String[]{"0", "65536", "65537", "100000"})               // previewChars
        };
    }

    private static String[] concat(String[] a, String[] b) {
        String[] both = new String[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }

    // ---------------------------------------------------------------- capture plumbing

    @FunctionalInterface private interface LogScan { void run(java.util.function.Supplier<List<LogRecord>> captured); }

    /**
     * This used to attach to the root logger, so every JUL record produced anywhere in the
     * fork -- including the SMTP/IMAP fixtures and Jakarta Mail's own {@code MailLogger}, both of
     * which write from background threads that can still be alive from a previous test -- landed in
     * {@code captured} while this method iterated it without a snapshot, an unsynchronized {@link
     * ArrayList} mutated from two threads at once. Seen red once in thirteen runs, as a {@link
     * java.util.ConcurrentModificationException} in {@code constraintOf}.
     *
     * <p>The named-logger pattern is already in use, unflaked, in the sibling {@link
     * ImapProfileResolverRecordDifferentialTest#resolve}: attach to the exact named logger production
     * writes to ({@code ai.ravenroot.mail.imap.profile.rejected}, see {@code
     * EnvironmentImapProfileResolver}'s {@code LOGGER} field) with {@code setUseParentHandlers(false)},
     * instead of the root. That is not a narrower substitute for scanning every captured
     * record, not only the ones filtered by message prefix" -- {@link EnvironmentImapProfileResolver}
     * has exactly one call site that logs, on exactly this logger (verified by inspection: no other
     * {@code Logger}/{@code System.Logger} use in that class), so every record this handler can ever
     * see is one the resolver itself produced. Nothing unrelated can write here, so nothing unrelated
     * can race with the read.
     */
    private static void withCapturedLogs(LogScan scenario) {
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { captured.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger("ai.ravenroot.mail.imap.profile.rejected");
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

    /** The resolver's own records. Constraint extraction reads only these; the sentinel scan in
     *  {@link #constraintOf} deliberately reads every captured record instead, so a value leaking
     *  through some other logger on the same call would still fail the test. */
    private static List<LogRecord> ourRecords(java.util.function.Supplier<List<LogRecord>> captured) {
        return captured.get().stream()
                .filter(r -> r.getMessage() != null && r.getMessage().startsWith("ravenroot_imap_profile_rejected"))
                .toList();
    }

    private static void assertRejected(java.util.function.Supplier<List<LogRecord>> captured, String profileName,
                                       String rawValue, String expectedConstraint) {
        assertEquals(expectedConstraint, constraintOf(captured, profileName, rawValue),
                () -> profileName + " named the wrong constraint for: " + rawValue);
    }

    private static String constraintOf(java.util.function.Supplier<List<LogRecord>> captured, String profileName,
                                       String rawValue) {
        captured.get().clear();
        var resolver = new EnvironmentImapProfileResolver(Map.of(
                EnvironmentImapProfileResolver.environmentVariableName(TENANT, profileName), rawValue));
        assertTrue(resolver.resolve(TENANT, profileName).isEmpty(), () -> profileName + " must be rejected, not resolved");
        for (LogRecord record : captured.get()) assertSentinelAbsent(record);
        List<LogRecord> ours = ourRecords(captured);
        assertFalse(ours.isEmpty(), () -> profileName + " must log a diagnostic");
        List<String> constraints = ours.stream().map(ImapProfileRejectionDiagnosticsTest::renderedConstraint).distinct().toList();
        assertEquals(1, constraints.size(), () -> profileName + " logged more than one distinct constraint: " + constraints);
        assertTrue(ours.stream().allMatch(r -> Level.WARNING.equals(r.getLevel())),
                () -> profileName + " must be reported at WARNING -- the level that clears the default "
                        + "JUL console handler's INFO threshold, which is what makes this line visible "
                        + "in the real configuration and not only under a handler a test attached");
        return constraints.get(0);
    }

    /** Pulls the {@code constraint=...} token out of the rendered line without assuming its format. */
    private static String renderedConstraint(LogRecord record) {
        String text = rendered(record);
        int at = text.indexOf("constraint=");
        assertTrue(at >= 0, () -> "no constraint token in: " + text);
        return text.substring(at + "constraint=".length()).trim();
    }

    /** Applied to everything the logging subsystem produced: the rendered line, every
     *  raw positional parameter (a value can be present as a parameter and absent from the rendered
     *  text if the pattern has fewer placeholders), and any attached throwable -- whose message is
     *  the vector that actually carries operator fields here. */
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
