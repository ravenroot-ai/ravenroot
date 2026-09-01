package ai.ravenroot.extensions.mail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the public mail-profile fixture as something {@link EnvironmentMailProfileResolver} actually accepts, field for
 * field -- so the documented format and the parser cannot silently diverge again the way the class
 * comment and the parser already had (nine documented fields, ten required).
 *
 * <p>The optional eleventh field, {@code allowedReplyTo}, is present in the documented example. This
 * test accepts either count, exactly as the parser does, and checks the Reply-To
 * allow-list against whichever provenance the example's own field count selects -- so it keeps
 * failing on drift without pinning one of the two shapes as the only documentable one.
 *
 * <h2>Why this test reads a fixture instead of holding the value in Java</h2>
 * <p>A test that re-typed the example string as a Java constant would prove nothing about drift: the
 * constant is itself a copy, and a copy can go stale exactly the way the class comment did -- edited
 * once, then never touched again while the thing it describes moved on. Reading {@code
 * test fixture at test time and parsing whatever string is actually there closes that gap:
 * change the documented example without updating what it is supposed to prove, or change the parser
 * without updating the example, and this test fails against the file as it stands, not against a
 * snapshot of it. The literal in {@link #EXAMPLE_ANCHOR} is a search key for locating that line, never
 * a copy of the value under test -- no assertion below compares against a second, hand-written value;
 * every expected value is derived from splitting the same string this test just read.
 *
 * <h2>The honest limit of what this ties together</h2>
 * <p>This cannot force a future editor to keep the surrounding prose paragraph in sync with the
 * fenced example -- only the example with the parser. If the prose drifts from the example while the
 * example keeps resolving, this test stays green. {@link EnvironmentMailProfileResolver}'s own class
 * comment limits that gap by naming this section as the single
 * canonical description rather than holding a second copy that could drift from it independently.
 */
class MailProfileDocumentedExampleTest {
    private static final Path DEPLOYMENT_DOC = Path.of("src/test/resources/documentation/mail-profile.md");
    private static final String EXAMPLE_ANCHOR = "Conformant value example";
    private static final Pattern EXAMPLE_BLOCK =
            Pattern.compile(Pattern.quote(EXAMPLE_ANCHOR) + "(?s).*?```\\R(.*?)\\R```");
    private static final String TENANT = "default";
    private static final String PROFILE_NAME = "operator-example";

    @Test void documentedExampleFieldCountIsOneTheParserAccepts() throws IOException {
        String[] fields = documentedExampleFields();
        assertTrue(fields.length == 10 || fields.length == 11,
                "the mail-profile fixture advertises ten mandatory fields plus an optional eleventh; the "
                        + "example it gives has " + fields.length + ", so EnvironmentMailProfileResolver's "
                        + "own FIELD_COUNT check would reject it -- fix the example in the doc, not this test");
    }

    /**
     * Resolves the documented example through the real resolver and checks that every field landed on
     * the {@link MailProfile} property its documented name says it becomes -- the seventh field on
     * both {@code defaultFrom} and {@code allowedFrom}, the eighth and ninth as multi-element sets
     * rather than single values, and {@code allowedReplyTo} on whichever source the example's own
     * field count selects: the eleventh field when it is there, the sender allow-list when it
     * is not).
     */
    @Test void documentedExampleResolvesAndEachFieldLandsWhereItsNameSays() throws IOException {
        String[] p = documentedExampleFields();
        assertTrue(p.length == 10 || p.length == 11,
                "example must have ten or eleven fields for the rest of this test to be meaningful");

        var resolver = new EnvironmentMailProfileResolver(Map.of(
                EnvironmentMailProfileResolver.environmentVariableName(TENANT, PROFILE_NAME),
                String.join(";", p)));
        Optional<MailProfile> resolved = resolver.resolve(TENANT, PROFILE_NAME);
        assertTrue(resolved.isPresent(),
                "the example documented as conformant was rejected by the resolver: "
                        + String.join(";", p));
        MailProfile profile = resolved.get();

        assertEquals(p[0], profile.host(), "field 1 (host)");
        assertEquals(Integer.parseInt(p[1]), profile.port(), "field 2 (port)");
        assertEquals(p[2].toUpperCase(Locale.ROOT), profile.securityMode(), "field 3 (securityMode)");
        assertEquals(Boolean.parseBoolean(p[3]), profile.allowPlaintext(), "field 4 (allowPlaintext)");
        assertEquals(p[4], profile.authUsername(), "field 5 (authUsername)");
        assertEquals(p[5], profile.credentialRef(), "field 6 (credentialRef)");
        assertEquals(p[6], profile.defaultFrom(), "field 7 (defaultFrom), first of its three uses");

        Set<String> fromAsSet = lowered(EnvironmentMailProfileResolver.csv(p[6]));
        assertEquals(fromAsSet, profile.allowedFrom(),
                "field 7 (defaultFrom) must also seed allowedFrom -- that is its second use");
        if (p.length == 11) {
            assertEquals(lowered(EnvironmentMailProfileResolver.csv(p[10])), profile.allowedReplyTo(),
                    "field 11 (allowedReplyTo) is the only source of the Reply-To allow-list when the "
                            + "documented example carries it");
            assertNotEquals(profile.allowedFrom(), profile.allowedReplyTo(),
                    "the documented example is only a useful demonstration of the eleventh field if its "
                            + "Reply-To allow-list actually differs from its sender allow-list -- otherwise "
                            + "an operator copying it learns nothing the ten-field form would not give them");
        } else {
            assertEquals(fromAsSet, profile.allowedReplyTo(),
                    "with no eleventh field, allowedReplyTo is allowedFrom by the compatibility rule");
        }

        assertEquals(lowered(EnvironmentMailProfileResolver.csv(p[7])), profile.allowedRecipients(),
                "field 8 (allowedRecipients) must be parsed as a comma-separated set, not a single value");
        assertTrue(profile.allowedRecipients().size() >= 2,
                "the documented example is only a meaningful pin of the set semantics if field 8 "
                        + "actually has more than one element -- got " + profile.allowedRecipients());

        assertEquals(lowered(EnvironmentMailProfileResolver.csv(p[8])), profile.allowedHeaders(),
                "field 9 (allowedHeaders) must be parsed as a comma-separated set, not a single value");
        assertTrue(profile.allowedHeaders().size() >= 2,
                "the documented example is only a meaningful pin of the set semantics if field 9 "
                        + "actually has more than one element -- got " + profile.allowedHeaders());

        assertEquals(Integer.parseInt(p[9]), profile.maxConcurrency(), "field 10 (maxConcurrency)");
    }

    private static Set<String> lowered(Set<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Reads the publicable fixture and splits the fenced example under {@link #EXAMPLE_ANCHOR} exactly
     * as {@link EnvironmentMailProfileResolver#resolve} splits any profile value. */
    private static String[] documentedExampleFields() throws IOException {
        if (!Files.isRegularFile(DEPLOYMENT_DOC)) {
            fail("mail-profile fixture not found at " + DEPLOYMENT_DOC.toAbsolutePath()
                    + " -- this test's relative path assumes the module is built from its own "
                    + "directory inside the reactor; adjust the path if that layout changed");
        }
        String document = Files.readString(DEPLOYMENT_DOC, StandardCharsets.UTF_8);
        Matcher matcher = EXAMPLE_BLOCK.matcher(document);
        assertTrue(matcher.find(),
                "mail-profile fixture no longer has a fenced example under \"" + EXAMPLE_ANCHOR
                        + "\" in the mail profile section -- this test has nothing to pin");
        String example = matcher.group(1).strip();
        return example.split(";", -1);
    }
}
