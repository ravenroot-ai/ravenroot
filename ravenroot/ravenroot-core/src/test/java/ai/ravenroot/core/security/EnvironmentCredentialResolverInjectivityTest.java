package ai.ravenroot.core.security;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link EnvironmentCredentialResolver} must map distinct references to distinct process
 * secrets. The lossy derivation {@code trim().toUpperCase().replaceAll("[^A-Z0-9]", "_")}
 * folds every non-alphanumeric separator onto {@code '_'} and folds case, so
 * {@code tenant-a/mail}, {@code tenant_a-mail}, {@code TENANT.A.MAIL} and {@code tenant+a+mail} all
 * produced the same environment key and therefore the same secret.
 *
 * <h2>Why a property, not a list</h2>
 * <p>A hand-picked list of colliding pairs proves only that the list was transcribed correctly. The
 * actual requirement is that <em>no</em> pair of distinct references collides.</p>
 *
 * <h2>Why the generators are shaped around separator and case, not fully independent strings</h2>
 * <p>Generating two fully independent random strings and asserting
 * their keys differed. That version passed even against the unfixed, colliding implementation: two
 * independently-drawn random strings almost never land in the same fold-equivalence class by chance
 * — the old scheme's equivalence classes are narrow (each letter collides with exactly one other
 * case, each of the seven separators collides with the other six), so uniform random sampling over a
 * ~70-character alphabet essentially never explores them. A property that cannot fail against the
 * defect it exists to catch is worse than no property: it looks like coverage. The generators below
 * instead construct pairs that are <em>designed</em> to be fold-equivalent under the old scheme — the
 * same alphanumeric tokens rendered with two different separators, or the same skeleton rendered with
 * different casing — which is what "property-based over the separator set" means concretely: the
 * separator (or the casing) is the generated parameter, not the whole string.</p>
 *
 * <h2>Why the property calls the real method rather than re-deriving the scheme</h2>
 * <p>{@link EnvironmentCredentialResolver#environmentVariableName} is package-private specifically so
 * this test exercises the production encoding directly, rather than reimplementing it and only
 * proving the two copies agree with each other.</p>
 */
class EnvironmentCredentialResolverInjectivityTest {

    private static final String SEPARATORS = "-_.+/: ";

    /**
     * The exact five references named in SEC-26's reproduction, minus the one containing embedded
     * whitespace ({@code "tenant a mail"}) — {@code BehaviorPropertySchema} already refuses embedded
     * whitespace in a {@code SECRET_REFERENCE} value before a reference reaches this resolver, so it
     * is out of the schema-valid domain. The other four are schema-valid today and still collided.
     */
    private static final List<String> KNOWN_ALIASES =
            List.of("tenant-a/mail", "tenant_a-mail", "TENANT.A.MAIL", "tenant+a+mail");

    /**
     * The same alphanumeric tokens joined by two different separators must never collapse onto the
     * same environment key. This is the direct generalisation of the old scheme's defect: it folded
     * every one of {@link #SEPARATORS} onto {@code '_'}, so any two of them were interchangeable.
     */
    @Property(tries = 500)
    void sameTokensWithDifferentSeparatorsNeverCollide(
            @ForAll("tokenLists") List<String> tokens,
            @ForAll("separators") char separatorA, @ForAll("separators") char separatorB) {
        Assume.that(tokens.size() >= 2);
        Assume.that(separatorA != separatorB);

        String r1 = String.join(String.valueOf(separatorA), tokens);
        String r2 = String.join(String.valueOf(separatorB), tokens);
        Assume.that(!r1.equals(r2));

        assertNotEquals(EnvironmentCredentialResolver.environmentVariableName(r1),
                EnvironmentCredentialResolver.environmentVariableName(r2),
                () -> "'" + r1 + "' and '" + r2 + "' (same tokens, different separator) produced the "
                        + "same environment variable name");
    }

    /**
     * The same skeleton (alphanumerics plus separators) rendered with a different letter-casing must
     * never collapse onto the same environment key. Generalises the old scheme's
     * {@code toUpperCase()} fold the same way the property above generalises its separator fold.
     */
    @Property(tries = 500)
    void sameSkeletonWithDifferentCasingNeverCollides(@ForAll("skeletons") String skeleton) {
        String upper = skeleton.toUpperCase(java.util.Locale.ROOT);
        String lower = skeleton.toLowerCase(java.util.Locale.ROOT);
        Assume.that(!upper.equals(lower)); // requires at least one letter to actually vary

        assertNotEquals(EnvironmentCredentialResolver.environmentVariableName(upper),
                EnvironmentCredentialResolver.environmentVariableName(lower),
                () -> "'" + upper + "' and '" + lower + "' (same skeleton, different case) produced "
                        + "the same environment variable name");
    }

    /**
     * End-to-end version: two references built from the same tokens with different separators, each
     * with its own provisioned secret, must each resolve to their own secret rather than to the
     * other's. Uses the same generator as {@link #sameTokensWithDifferentSeparatorsNeverCollide} so
     * it targets the same defect shape rather than independent random noise.
     */
    @Property(tries = 200)
    void sameTokensWithDifferentSeparatorsResolveToTheirOwnSecret(
            @ForAll("tokenLists") List<String> tokens,
            @ForAll("separators") char separatorA, @ForAll("separators") char separatorB) {
        Assume.that(tokens.size() >= 2);
        Assume.that(separatorA != separatorB);

        String r1 = String.join(String.valueOf(separatorA), tokens);
        String r2 = String.join(String.valueOf(separatorB), tokens);
        Assume.that(!r1.equals(r2));

        String key1 = EnvironmentCredentialResolver.environmentVariableName(r1);
        String key2 = EnvironmentCredentialResolver.environmentVariableName(r2);
        assertNotEquals(key1, key2, () -> "'" + r1 + "' and '" + r2 + "' produced the same environment "
                + "variable name, so only one secret could ever be provisioned for both");

        var resolver = new EnvironmentCredentialResolver(Map.of(key1, "secret-one", key2, "secret-two"));

        try (var first = resolver.get(r1).orElseThrow();
             var second = resolver.get(r2).orElseThrow()) {
            assertEquals("secret-one", new String(first.copy()));
            assertEquals("secret-two", new String(second.copy()));
        }
    }

    /**
     * The concrete reproduction: four distinct, schema-valid references, one shared
     * secret, direct construction and resolution — no property machinery, so a reader can see the
     * exact defect without unpacking a generator.
     */
    @org.junit.jupiter.api.Test
    void theFourKnownAliasesNoLongerCollapseOntoTheSameSecret() {
        var environment = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < KNOWN_ALIASES.size(); i++) {
            environment.put(EnvironmentCredentialResolver.environmentVariableName(KNOWN_ALIASES.get(i)),
                    "secret-" + i);
        }
        var resolver = new EnvironmentCredentialResolver(environment);

        var seen = new java.util.HashSet<String>();
        for (String reference : KNOWN_ALIASES) {
            try (var secret = resolver.get(reference).orElseThrow(
                    () -> new AssertionError("no secret resolved for '" + reference + "'"))) {
                seen.add(new String(secret.copy()));
            }
        }
        assertEquals(KNOWN_ALIASES.size(), seen.size(),
                "expected " + KNOWN_ALIASES.size() + " distinct secrets, one per reference, but only "
                        + "saw " + seen.size() + " — some references are still resolving to the same "
                        + "secret as another: " + seen);
    }

    // ------------------------------------------------------------------ generators

    @Provide
    Arbitrary<List<String>> tokenLists() {
        Arbitrary<String> token = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
                .ofMinLength(1).ofMaxLength(6);
        return token.list().ofMinSize(2).ofMaxSize(4);
    }

    @Provide
    Arbitrary<Character> separators() {
        return Arbitraries.of(SEPARATORS.chars().mapToObj(c -> (char) c).toArray(Character[]::new));
    }

    @Provide
    Arbitrary<String> skeletons() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + SEPARATORS)
                .ofMinLength(1).ofMaxLength(24)
                .filter(value -> !value.isBlank());
    }
}
