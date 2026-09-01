package ai.ravenroot.extensions.mail;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EnvironmentMailProfileResolver.resolve} pre-checks nine of
 * {@code MailProfile}'s constraints by name, before construction, so a rejection can say which one
 * fired. Nine checks that duplicate a validation written elsewhere are nine occasions to diverge from
 * it -- and the first divergence was real: the pre-check for {@code DEFAULT_FROM_NOT_ALLOWED} compared
 * against the raw, case-preserving {@code csv(p[6])} set, while {@code MailProfile} compares against
 * that set <em>after</em> its constructor lower-cases every element ({@code normalized(...)}). An
 * operator whose {@code defaultFrom} differed from their own allow-list only in case -- the exact
 * field their allow-list is derived from -- lost mail delivery on upgrade, and the diagnostic told
 * them their default sender was not in their own allow-list, which is nonsensical on its face.
 *
 * <h2>Why a differential, not just a regression case</h2>
 * <p>{@link #uppercaseDefaultFromAgainstItsOwnAllowListStillResolves} pins the specific uppercase
 * case. It does not, by itself, rule out the next one: a duplicated shape check is a standing
 * invitation to drift, not a one-time mistake. {@link #resolverAcceptsIffRecordConstructionAccepts}
 * closes the class of bug rather than the instance: it builds a random profile string,
 * derives {@code MailProfile}'s constructor arguments from it exactly as {@code resolve} does, and
 * asserts that the resolver returns a present {@code Optional} <em>if and only if</em> constructing
 * {@code MailProfile} from those same arguments succeeds. Any future pre-check that is stricter or
 * looser than the constraint it stands in for -- whatever field or comparison causes it -- shows up
 * here as a counter-example, without this test needing to know what that future check looks like.
 *
 * <p>This is honest to write because the resolver's derivation is directly reusable: {@link
 * EnvironmentMailProfileResolver#csv} is package-private for exactly this purpose, so the "record
 * side" of the comparison calls the identical method the resolver calls, rather than a second copy
 * that could itself quietly drift from the production one. The one place the two sides are
 * deliberately <em>not</em> compared is the field count: {@code resolve} rejects a wrong count before
 * deriving anything (there is no {@code MailProfile} argument list to attempt), so this test only
 * generates strings of a count the parser accepts. That is a stated scope limit, not a gap papered
 * over -- {@code FIELD_COUNT} has its own direct coverage in {@code MailProfileRejectionDiagnosticsTest}.
 *
 * <p>The accepted count has two values: ten fields, or eleven with an explicit {@code allowedReplyTo}.
 * Both shapes are generated here, and both sides of the comparison derive {@code allowedReplyTo} the
 * same way -- from the eleventh field when it is present, from {@code allowedFrom} when it is not.
 * Without the eleven-field shape in the corpus the differential would keep asserting equivalence over
 * a format the parser no longer only has.
 */
class MailProfileResolverRecordDifferentialTest {
    private static final String TENANT = "tenant";
    private static final String PROFILE_NAME = "profile";

    /** A default sender accepted by {@link MailProfile} after case-folding must also resolve. */
    @Test void uppercaseDefaultFromAgainstItsOwnAllowListStillResolves() {
        var resolver = new EnvironmentMailProfileResolver(Map.of(
                EnvironmentMailProfileResolver.environmentVariableName("default", "primary"),
                "smtp.example.test;587;STARTTLS;false;mailer;primary;From@X.test;to@example.test;X-Trace;2"));
        var profile = resolver.resolve("default", "primary");
        assertTrue(profile.isPresent(), "a defaultFrom that matches its own allow-list only after "
                + "case-folding must resolve, exactly as MailProfile itself would accept it");
        assertEquals("From@X.test", profile.get().defaultFrom());
    }

    /** Marks "this generated profile has no eleventh field at all", which is not the same input as an
     *  eleventh field that is present and blank -- the compatibility rule must keep the two apart. */
    private static final String NO_ELEVENTH_FIELD = "\0absent";

    @Property(tries = 1000)
    void resolverAcceptsIffRecordConstructionAccepts(
            @ForAll("hosts") String host, @ForAll("ports") String port, @ForAll("modes") String mode,
            @ForAll("bools") String allowPlaintext, @ForAll("users") String user, @ForAll("credentialRefs") String credentialRef,
            @ForAll("froms") String from, @ForAll("recipientLists") String recipients, @ForAll("headerLists") String headers,
            @ForAll("concurrencies") String maxConcurrency, @ForAll("replyToLists") String replyTo) {
        String[] p = NO_ELEVENTH_FIELD.equals(replyTo)
                ? new String[]{host, port, mode, allowPlaintext, user, credentialRef, from, recipients, headers, maxConcurrency}
                : new String[]{host, port, mode, allowPlaintext, user, credentialRef, from, recipients, headers, maxConcurrency, replyTo};
        String raw = String.join(";", p);
        var resolver = new EnvironmentMailProfileResolver(Map.of(
                EnvironmentMailProfileResolver.environmentVariableName(TENANT, PROFILE_NAME), raw));
        boolean resolverAccepted = resolver.resolve(TENANT, PROFILE_NAME).isPresent();
        boolean recordAccepted = recordAccepts(p);
        assertEquals(recordAccepted, resolverAccepted, () -> "resolver and MailProfile disagreed for raw=\"" + raw + "\"");
    }

    /**
     * The "record side" of the comparison: derive {@code MailProfile}'s constructor arguments from
     * the same fields, the same way {@code EnvironmentMailProfileResolver.resolve} does -- same
     * indices, same {@link EnvironmentMailProfileResolver#csv}, same fixed constants for the fields
     * the resolver never varies -- with no pre-check standing in front of it. Any {@code
     * RuntimeException}, whether from argument derivation (an unparsable integer, a duplicate list
     * entry) or from the record's own constructor, counts as "rejected", matching what an
     * unintercepted rejection from any of those sites would look like to a caller of {@code resolve}.
     */
    private static boolean recordAccepts(String[] p) {
        try {
            int port = Integer.parseInt(p[1]);
            int maxConcurrency = Integer.parseInt(p[9]);
            Set<String> allowedFrom = EnvironmentMailProfileResolver.csv(p[6]);
            Set<String> allowedReplyTo = p.length == 11 ? EnvironmentMailProfileResolver.csv(p[10]) : allowedFrom;
            Set<String> allowedRecipients = EnvironmentMailProfileResolver.csv(p[7]);
            Set<String> allowedHeaders = EnvironmentMailProfileResolver.csv(p[8]);
            new MailProfile(TENANT, PROFILE_NAME, p[0], port, p[2], Boolean.parseBoolean(p[3]), p[4], p[5], p[6],
                    allowedFrom, allowedReplyTo, allowedRecipients, allowedHeaders, 100, 40, 8192, 1_048_576, 10, 5_242_880,
                    10_485_760, 13_981_016, 10_000, 30_000, 30_000, 0, maxConcurrency);
            return true;
        } catch (RuntimeException notAccepted) { return false; }
    }

    @Provide Arbitrary<String> hosts() { return Arbitraries.of("smtp.example.test", "SMTP.EXAMPLE.TEST", "", " ", " smtp.example.test"); }
    @Provide Arbitrary<String> ports() { return Arbitraries.of("587", "1", "65535", "65536", "0", "-1", "abc", "", "007", "2147483648"); }
    @Provide Arbitrary<String> modes() { return Arbitraries.of("SMTP", "SMTPS", "STARTTLS", "smtp", "Smtps", "startTLS", "CARRIER-PIGEON", "", " "); }
    @Provide Arbitrary<String> bools() { return Arbitraries.of("true", "false", "TRUE", "yes", ""); }
    @Provide Arbitrary<String> users() { return Arbitraries.of("", "mailer", "Mailer"); }
    @Provide Arbitrary<String> credentialRefs() { return Arbitraries.of("", "primary", "PRIMARY", "sentinel-ref"); }
    @Provide Arbitrary<String> froms() {
        return Arbitraries.of("from@example.test", "From@Example.TEST", "", "*", "a@example.test,b@example.test",
                "a@example.test,a@example.test", "a@example.test,A@example.test", " from@example.test", "from@example.test ");
    }
    @Provide Arbitrary<String> recipientLists() {
        return Arbitraries.of("to@example.test", "TO@EXAMPLE.test", "", "*", "to@example.test,to@example.test", "a@example.test,b@example.test");
    }
    @Provide Arbitrary<String> headerLists() { return Arbitraries.of("X-Trace", "x-trace", "", "*", "X-Trace,X-Trace", "X-Trace,X-Other"); }
    /** The eleventh field, plus the sentinel for generating a ten-field value with no such
     *  field. A blank entry is deliberate and distinct from the sentinel: it is how an operator
     *  denies Reply-To outright, and it must be accepted, not rejected as an empty allow-list the
     *  way an empty allowedFrom or allowedRecipients is. */
    @Provide Arbitrary<String> replyToLists() {
        return Arbitraries.of(NO_ELEVENTH_FIELD, "", "reply@example.test", "Reply@Example.TEST", "*",
                "a@example.test,b@example.test", "a@example.test,a@example.test", " reply@example.test");
    }
    @Provide Arbitrary<String> concurrencies() { return Arbitraries.of("1", "16", "17", "0", "-1", "many", "", "008"); }
}
