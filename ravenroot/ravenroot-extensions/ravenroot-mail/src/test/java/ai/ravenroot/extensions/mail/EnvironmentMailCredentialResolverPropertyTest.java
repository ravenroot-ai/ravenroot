package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.security.SecretValue;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentMailCredentialResolverPropertyTest {
    private static final String SEPARATORS = "-_./:@ +";

    @Property(tries = 300)
    void differentSeparatorsProduceDifferentKeysAndSecrets(
            @ForAll("tokens") List<String> tokens,
            @ForAll("separators") char leftSeparator,
            @ForAll("separators") char rightSeparator) {
        Assume.that(leftSeparator != rightSeparator);
        assertDistinctAndResolves(String.join(String.valueOf(leftSeparator), tokens),
                String.join(String.valueOf(rightSeparator), tokens));
    }

    @Property(tries = 300)
    void differentCasingProducesDifferentKeysAndSecrets(@ForAll("skeletons") String skeleton) {
        String lower = skeleton.toLowerCase(Locale.ROOT);
        String upper = skeleton.toUpperCase(Locale.ROOT);
        Assume.that(!lower.equals(upper));
        assertDistinctAndResolves(lower, upper);
    }

    @Property(tries = 500)
    void distinctValidUnicodeScalarStringsProduceDifferentKeysAndSecrets(
            @ForAll("validUnicodeReferences") String left,
            @ForAll("validUnicodeReferences") String right) {
        Assume.that(!left.equals(right));
        assertDistinctAndResolves(left, right);
    }

    @Test void isolatedSurrogatesAreRejectedInsteadOfAliasing() {
        for (String malformed : List.of("\uD800", "\uD801", "\uDBFF", "\uDC00", "\uDFFF")) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentMailCredentialResolver.environmentVariableName(malformed));
            assertTrue(new EnvironmentMailCredentialResolver(Map.of()).resolve(malformed).isEmpty());
        }
    }

    @Test void legacyNormalizedKeyFailsAndDocumentedHexKeyResolves() {
        var legacy = new EnvironmentMailCredentialResolver(
                Map.of("RAVENROOT_MAIL_CREDENTIAL_PRIMARY", "legacy"));
        assertTrue(legacy.resolve("primary").isEmpty());

        var documented = new EnvironmentMailCredentialResolver(
                Map.of("RAVENROOT_MAIL_CREDENTIAL_7072696D617279", "documented"));
        assertEquals("documented", text(documented.resolve("primary").orElseThrow()));
    }

    private static void assertDistinctAndResolves(String left, String right) {
        String leftKey = EnvironmentMailCredentialResolver.environmentVariableName(left);
        String rightKey = EnvironmentMailCredentialResolver.environmentVariableName(right);
        assertNotEquals(leftKey, rightKey);
        var resolver = new EnvironmentMailCredentialResolver(
                Map.of(leftKey, "left-secret", rightKey, "right-secret"));
        assertEquals("left-secret", text(resolver.resolve(left).orElseThrow()));
        assertEquals("right-secret", text(resolver.resolve(right).orElseThrow()));
    }

    @Provide Arbitrary<List<String>> tokens() {
        Arbitrary<String> token = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
                .ofMinLength(1).ofMaxLength(6);
        return token.list().ofMinSize(2).ofMaxSize(4);
    }

    @Provide Arbitrary<Character> separators() {
        return Arbitraries.of(SEPARATORS.chars().mapToObj(value -> (char) value).toArray(Character[]::new));
    }

    @Provide Arbitrary<String> skeletons() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + SEPARATORS)
                .ofMinLength(1).ofMaxLength(24)
                .filter(value -> !value.isBlank());
    }

    @Provide Arbitrary<String> validUnicodeReferences() {
        Arbitrary<Integer> scalar = Arbitraries.oneOf(
                Arbitraries.integers().between(1, 0xD7FF),
                Arbitraries.integers().between(0xE000, 0x10FFFF));
        return scalar.list().ofMinSize(1).ofMaxSize(12).map(codePoints -> {
            StringBuilder value = new StringBuilder();
            codePoints.forEach(value::appendCodePoint);
            return value.toString();
        }).filter(value -> !value.isBlank());
    }

    private static String text(SecretValue value) {
        char[] copy = value.copy();
        try { return new String(copy); }
        finally { java.util.Arrays.fill(copy, '\0'); value.close(); }
    }
}
