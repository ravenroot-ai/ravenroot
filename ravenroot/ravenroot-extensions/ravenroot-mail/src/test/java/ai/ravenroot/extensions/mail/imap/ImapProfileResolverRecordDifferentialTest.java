package ai.ravenroot.extensions.mail.imap;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EnvironmentImapProfileResolver.resolve} pre-checks eleven of {@link ImapProfile}'s
 * thirteen constraints by name, before construction, so a rejection can say which one fired. Eleven
 * checks that duplicate a validation written elsewhere are eleven occasions to diverge from it. This
 * is not hypothetical: an earlier SMTP pre-check was
 * pre-check <em>stricter</em> than the constraint it stood in for, and every test in that suite
 * passed anyway.
 *
 * <h2>The two directions of divergence, and why they need two different sensors</h2>
 * <p>A pre-check can drift either way, and the two are <em>not</em> symmetric here.
 *
 * <p><b>Stricter</b> -- the pre-check refuses what the record would accept. The resolver returns
 * empty while construction would have succeeded, so the equality {@code resolverAccepted ==
 * recordAccepted} breaks. This is the direction {@link #resolverAcceptsIffRecordConstructionAccepts}
 * and {@link #everySingleFieldMutationAgreesWithTheRecord} observe directly.
 *
 * <p><b>Looser</b> -- the pre-check waves through what the record refuses. The equality is
 * <em>structurally blind</em> to this: the value enters the {@code try}, {@code ImapProfile} throws,
 * the residual net returns {@code rejected(..., RECORD_POLICY)}, and so {@code resolverAccepted ==
 * false == recordAccepted}. The equality holds for every permissive drift there could ever be. A
 * transplanted {@code toUpperCase} on the security-mode check -- the exact trap this class warns
 * about below -- passes the equality, so a separate residual-net assertion is required.
 *
 * <p>What makes the second direction observable is the residual net itself. It is not only a
 * fail-closed safety device: because it labels its rejection differently from every pre-check, it is
 * also the <em>sensor</em> for the drift that produced it. Under a correct resolver
 * {@code RECORD_POLICY} is unreachable, so a rejection carrying that name is proof that some
 * pre-check let a value past that the record then refused. Both tests below therefore capture the
 * logged constraint and assert that a rejection is never named {@code RECORD_POLICY}. That turns the
 * residual net's unreachability from a list of cases into a property.
 *
 * <h2>Why the corpus is near-valid, and why coverage is asserted rather than assumed</h2>
 * <p>The obvious way to write this property -- eleven independent adversarial arbitraries -- is
 * almost vacuous. Nearly every tuple is invalid on several fields at once, both sides answer
 * "rejected", and the assertion is satisfied by {@code false == false} without comparing anything.
 * With eleven independent adversarial arbitraries, P(record accepts) measured 0.000129, about
 * <em>one eighth of one sample per thousand-try run</em>, and confirmed it by injecting that same
 * regression ({@code maxPreviewChars < 1} for {@code < 0}) and watching the property pass with
 * probability 0.96.
 *
 * <p>So generation starts from {@link #VALID} and corrupts nought to two fields drawn from the
 * per-field corpora, which each carry a deliberate mixture of accepted and refused values. That puts
 * the interesting region -- profiles wrong in one respect and right in the other ten -- where the
 * samples actually land. {@link #resolverAcceptsIffRecordConstructionAccepts} then asserts jqwik
 * <em>coverage</em> on the fraction of tries the record accepts, so that a future edit which quietly
 * makes the corpus adversarial again fails the build instead of hollowing the property out in
 * silence. A comment could not have done that.
 *
 * <p>Randomised sampling still cannot promise that any particular counter-example appears in a given
 * run, so {@link #everySingleFieldMutationAgreesWithTheRecord} sweeps <em>every</em> value of
 * <em>every</em> field corpus exhaustively and deterministically. That is the test that pins the two
 * injected regressions on every run rather than on 4% of them.
 *
 * <h2>The scope limit</h2>
 * <p>The one place the two sides are deliberately not compared is the field count: {@code resolve}
 * rejects a wrong count before deriving anything, so there is no argument list to attempt and no
 * record-side counterpart. {@code FIELD_COUNT} has direct coverage at both boundaries in {@code
 * ImapProfileRejectionDiagnosticsTest}. Everything else is compared, and the record side reuses
 * {@link EnvironmentImapProfileResolver#folders} -- package-private for exactly this purpose --
 * rather than a second copy that could itself drift.
 */
class ImapProfileResolverRecordDifferentialTest {
    private static final String TENANT = "tenant";
    private static final String PROFILE_NAME = "profile";
    private static final int FIELDS = 11;

    /** host;port;securityMode;username;credentialRef;folders;connectMs;readMs;concurrency;results;previewChars */
    private static final String[] VALID =
            {"imap.example.test", "993", "IMAPS", "mailer", "primary", "INBOX", "10000", "30000", "4", "50", "2048"};

    /**
     * Per-field values, each corpus deliberately mixing values {@link ImapProfile} accepts with values
     * it refuses. The accepted ones are what keep the property non-vacuous, and they are also the only
     * way a <em>stricter</em> pre-check can be caught -- a corpus of nothing but garbage can never
     * demonstrate that the resolver refuses something the record would have taken.
     */
    private static final String[][] CORPUS = {
            {"imap.example.test", "IMAP.EXAMPLE.TEST", "another.host.test", "", " "},
            {"993", "143", "1", "65535", "+993", "65536", "0", "-1", "abc", "", "2147483648"},
            // No case folding on this record, so every case variant here is a refused value.
            {"IMAPS", "STARTTLS", "imaps", "Imaps", "startTLS", "StartTls", "SMTPS", "CARRIER-PIGEON", "", " ", "IMAPS "},
            {"mailer", "Mailer", "reader@example.test", "", " "},
            {"primary", "PRIMARY", "sentinel-ref", "", " "},
            // "" and " " used to be ONE blank-named folder (accepted); ImapProfile now strips
            // every name and discards the blank ones before testing emptiness, so both reduce to no
            // folders at all and are refused, exactly like "," and ",," (the empty array). " , " is
            // two identical " " entries and so a duplicate (refused) before either side ever reaches
            // the strip. "INBOX, INBOX" still collides after the record's own strip -- which now runs
            // before, not after, its emptiness test -- so it is still accepted as {"INBOX"}.
            //
            // The DUPLICATE_FOLDER boundary is NOT a count of blank entries: it is decided on raw,
            // unstripped entries, so it only fires when two of them are byte-identical. "INBOX,,Archive"
            // and ",INBOX" (one blank entry) are accepted, as is "INBOX, ,Archive,,Notes" (TWO blank
            // entries, spelled differently -- " " and "" -- so neither raw-duplicate check fires; both
            // sides accept it, contributing real discriminating power in the accepting region, unlike
            // the rejected forms below). "INBOX,,Archive,,Notes" and ",INBOX,,Archive" each repeat the
            // identical raw "" twice, the same way "INBOX,INBOX" repeats "INBOX" -- Set.of(value.split(
            // ",")) itself throws on it, so recordAccepts sees the record side refuse it too, for the
            // same underlying reason the resolver's pre-check does.
            {"INBOX", "inbox", "", " ", "INBOX,Archive", "INBOX,", ",INBOX", "INBOX, INBOX", "INBOX,inbox",
                    ",", ",,", " , ", "INBOX,INBOX", "INBOX,,Archive", "INBOX,,Archive,,Notes", ",INBOX,,Archive",
                    "INBOX, ,Archive,,Notes"},
            {"10000", "1", "120000", "+5", "0", "-1", "abc", "", " "},
            {"30000", "1", "300000", "+5", "0", "-1", "abc", "", " "},
            {"4", "1", "16", "008", "17", "0", "-1", "many", ""},
            {"50", "1", "500", "501", "0", "-1", "lots", ""},
            // "0" is accepted here and nowhere else in this format: maxPreviewChars is the one bound
            // whose floor is < 0 rather than < 1. It is also the exact value that catches the strict
            // regression if it is ever transplanted onto this field.
            {"2048", "0", "65536", "65537", "-1", "some", "", "100000"}
    };

    // ---------------------------------------------------------------- deterministic, exhaustive

    /**
     * Every value of every field corpus, one corrupted field at a time, against the valid baseline.
     * Deterministic and exhaustive over the single-fault region, which is where a drifted pre-check
     * shows first and where both mutation injections land:
     * <ul>
     *   <li>the <em>permissive</em> one -- {@code SECURITY_MODES.contains(p[2].toUpperCase(...))},
     *       transplanted from the SMTP resolver -- is caught on {@code securityMode = "imaps"}: the
     *       pre-check passes, {@code ImapProfile} refuses, and the rejection comes back named
     *       {@code RECORD_POLICY};</li>
     *   <li>the <em>strict</em> one -- {@code maxPreviewChars < 1} for {@code < 0}, aimed at this
     *       format's one zero-floor field -- is caught on
     *       {@code maxPreviewChars = "0"}: the resolver refuses a profile the record accepts, so the
     *       equality breaks.</li>
     * </ul>
     */
    @Test void everySingleFieldMutationAgreesWithTheRecord() {
        assertDifferential(VALID.clone());
        for (int field = 0; field < FIELDS; field++)
            for (String value : CORPUS[field]) {
                String[] p = VALID.clone();
                p[field] = value;
                assertDifferential(p);
            }
    }

    // ---------------------------------------------------------------- randomised, near-valid

    @Property(tries = 1000)
    void resolverAcceptsIffRecordConstructionAccepts(@ForAll("nearValidProfiles") String[] profile) {
        Statistics.label("record accepts").collect(recordAccepts(profile));
        // Asserted, not hoped for. The earlier version of this property sampled the accepting region
        // with probability 0.000129; anything that drives it back down there fails here instead of
        // passing quietly. The floor is far below the ~55% this generator actually produces, so it
        // fails on a real collapse rather than on sampling noise.
        // Bound to a typed variable rather than written inline: StatisticsCoverage.CoverageChecker
        // overloads percentage() on both Predicate<Double> and Consumer<Double>, and an implicitly
        // typed lambda matches both, so the call is ambiguous without this.
        java.util.function.Consumer<Double> requireAcceptingRegion = percentage ->
                assertTrue(percentage >= 20.0, "the corpus stopped exercising the accepting region: only "
                        + percentage + "% of tries were accepted by ImapProfile, so the differential is "
                        + "being satisfied by rejected == rejected instead of by comparing anything");
        Statistics.label("record accepts").coverage(coverage -> coverage.check(true).percentage(requireAcceptingRegion));
        assertDifferential(profile);
    }

    /** The valid baseline with nought to two fields replaced by a value from that field's corpus. */
    @Provide Arbitrary<String[]> nearValidProfiles() {
        Arbitrary<Mutation> mutations = Arbitraries.integers().between(0, FIELDS - 1)
                .flatMap(field -> Arbitraries.of(CORPUS[field]).map(value -> new Mutation(field, value)));
        return mutations.list().ofMinSize(0).ofMaxSize(2).map(list -> {
            String[] p = VALID.clone();
            for (Mutation mutation : list) p[mutation.field()] = mutation.value();
            return p;
        });
    }

    private record Mutation(int field, String value) { }

    // ---------------------------------------------------------------- the two named cases

    /**
     * The trap of transferring the SMTP rule to the IMAP side, pinned as a case. Asserting only that
     * both sides refuse this value is vacuous: the residual net guarantees it whatever the pre-check
     * does, so that assertion passes with the permissive defect installed. What actually
     * distinguishes a correct resolver here is the <em>name</em>: {@code UNKNOWN_SECURITY_MODE}
     * because a pre-check decided it, not {@code RECORD_POLICY} because the record did.
     */
    @Test void lowercaseSecurityModeIsRefusedByNameAndNotByTheResidualNet() {
        String[] p = VALID.clone();
        p[2] = "imaps";
        Outcome outcome = resolve(p);
        assertEquals(false, outcome.accepted(),
                "ImapProfile compares securityMode without normalising it, unlike MailProfile");
        assertEquals(false, recordAccepts(p), "the record side must agree it is refused");
        assertEquals("UNKNOWN_SECURITY_MODE", outcome.constraint(),
                "a pre-check must decide this, by name; RECORD_POLICY here would mean the pre-check is "
                        + "looser than the constraint it stands in for");
    }

    /** The upper-case spelling is the accepted one on both sides, so the case above pins a real
     *  distinction rather than a profile broken for some unrelated reason. */
    @Test void uppercaseSecurityModeIsAcceptedByBothSides() {
        assertTrue(resolve(VALID.clone()).accepted());
        assertTrue(recordAccepts(VALID.clone()));
    }

    // ---------------------------------------------------------------- the comparison itself

    /**
     * Both sensors, applied to one profile: the equality catches a pre-check stricter than the
     * constraint it replaces, and the constraint name catches one looser than it. Neither alone is
     * sufficient -- see this class's comment for why the second direction is invisible to the first.
     */
    private static void assertDifferential(String[] p) {
        String raw = String.join(";", p);
        Outcome outcome = resolve(p);
        assertEquals(recordAccepts(p), outcome.accepted(),
                () -> "resolver and ImapProfile disagreed for raw=\"" + raw + "\"");
        if (!outcome.accepted())
            assertNotEquals("RECORD_POLICY", outcome.constraint(),
                    () -> "a pre-check let this value reach ImapProfile, which then refused it, so some "
                            + "pre-check is looser than the constraint it stands in for: raw=\"" + raw + "\"");
    }

    /**
     * The "record side" of the comparison: derive {@link ImapProfile}'s constructor arguments from the
     * same fields, the same way {@code EnvironmentImapProfileResolver.resolve} does -- same indices,
     * same {@link EnvironmentImapProfileResolver#folders} -- with no pre-check standing in front of
     * it. Any {@code RuntimeException}, whether from argument derivation (one of the six unparsable
     * integers, or a duplicate folder) or from the record's own constructor, counts as "rejected".
     */
    private static boolean recordAccepts(String[] p) {
        try {
            int port = Integer.parseInt(p[1]);
            Set<String> folders = EnvironmentImapProfileResolver.folders(p[5]);
            int connectTimeoutMs = Integer.parseInt(p[6]);
            int readTimeoutMs = Integer.parseInt(p[7]);
            int maxConcurrency = Integer.parseInt(p[8]);
            int maxResults = Integer.parseInt(p[9]);
            int maxPreviewChars = Integer.parseInt(p[10]);
            new ImapProfile(TENANT, PROFILE_NAME, p[0], port, p[2], p[3], p[4], folders,
                    connectTimeoutMs, readTimeoutMs, maxConcurrency, maxResults, maxPreviewChars);
            return true;
        } catch (RuntimeException notAccepted) { return false; }
    }

    /** Whether the resolver accepted, and -- when it did not -- the constraint name it logged. */
    private record Outcome(boolean accepted, String constraint) { }

    /**
     * Runs the resolver with the diagnostic captured off the named logger rather than the root one,
     * with parent handlers detached for the duration. That keeps the JUL default console handler from
     * printing a line for every rejected try (this class produces upwards of a thousand) while still
     * reading the exact channel production writes to. Every attachment is undone in a {@code finally},
     * so no state survives the call.
     */
    private static Outcome resolve(String[] p) {
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
        try {
            var resolver = new EnvironmentImapProfileResolver(Map.of(
                    EnvironmentImapProfileResolver.environmentVariableName(TENANT, PROFILE_NAME), String.join(";", p)));
            boolean accepted = resolver.resolve(TENANT, PROFILE_NAME).isPresent();
            return new Outcome(accepted, captured.isEmpty() ? null : constraintOf(captured.get(0)));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    /** Pulls the {@code constraint=...} token out of the rendered line without assuming its format. */
    private static String constraintOf(LogRecord record) {
        Object[] params = record.getParameters();
        String text = params == null || params.length == 0
                ? record.getMessage() : MessageFormat.format(record.getMessage(), params);
        int at = text.indexOf("constraint=");
        assertTrue(at >= 0, () -> "no constraint token in: " + text);
        return text.substring(at + "constraint=".length()).trim();
    }
}
