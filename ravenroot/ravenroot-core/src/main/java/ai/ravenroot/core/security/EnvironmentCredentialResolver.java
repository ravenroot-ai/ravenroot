package ai.ravenroot.core.security;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.SecretProvider;
import ai.ravenroot.api.security.SecretValue;

import java.util.Map;
import java.util.Optional;

/**
 * Maps an opaque reference such as {@code openai-main} to a process secret (SEC-06).
 *
 * <p><strong>Known limitation:</strong> an environment variable has no revocation primitive short of
 * restarting the process, and this provider does not add one — {@link SecretProvider} caching, TTL
 * and revocation are provider-owned, not something applied to this class from outside it, and this
 * provider simply chooses not to implement them. A reference resolved once stays resolvable to the
 * same process-environment value for the life of the process; there is no cache or TTL layer in front
 * of it to bound that further. This is a deliberate, documented gap rather than a hidden one: do not
 * read this class as providing SEC-06 revocation on its own.
 */
public final class EnvironmentCredentialResolver implements SecretProvider {
    private static final String PREFIX = "RAVENROOT_CREDENTIAL_";
    private final Map<String, String> environment;

    public EnvironmentCredentialResolver() {
        this(System.getenv());
    }

    EnvironmentCredentialResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public String id() {
        return "environment";
    }

    /**
     * <strong>Total, and deliberately still total.</strong> The derivation below now
     * reports a reference it cannot encode unambiguously instead of substituting for it, and this
     * method absorbs that refusal and answers empty — the same answer it already gives for a blank
     * reference and for a variable that is not set.
     *
     * <p>The choice was between propagating and absorbing, and it is not the mechanical one it was at
     * the six connector sites that use strict encoding. The reference here can originate in <em>graph content</em>
     * — the {@code credentialRef} of an HTTP or LLM node — so letting the refusal escape would turn
     * attacker-influenced input into an exception thrown from inside a credential lookup, on a path
     * that has never thrown. Three reasons to absorb instead:
     *
     * <ul>
     *   <li><strong>The return type already says it.</strong> {@code Optional<SecretValue>} means
     *   "there may be no secret for this reference". A reference with no unambiguous key has no
     *   secret; that is the case the type was written for, not an exceptional condition.</li>
     *   <li><strong>Consistency inside one method.</strong> Every other rejection here — null, blank,
     *   absent variable — returns empty. Making one rejection reason escape as an exception while its
     *   neighbours return empty is an inconsistency a caller cannot reason about.</li>
     *   <li><strong>The callers already have a vocabulary for empty.</strong>
     *   {@code HttpRequestNodeBehaviorFactory} turns an empty result into
     *   {@code SecurityException("Credential reference cannot be resolved")}. Absorbing therefore
     *   reuses a failure mode that already exists and is already handled, instead of introducing a
     *   second, lower-level one for the same situation.</li>
     * </ul>
     *
     * <p>This mirrors {@link EnvironmentKeyCodec}'s own stated split — raise where the ambiguity is
     * detected so the reason stays attached to it, fail closed where the contract permits absence —
     * and matches what every connector resolver does. What changes observably is only
     * this: a reference that used to resolve to <em>someone else's</em> secret now resolves to
     * nothing.
     */
    @Override
    public Optional<SecretValue> get(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        final String key;
        try {
            key = environmentVariableName(reference);
        } catch (IllegalArgumentException ambiguous) {
            // No key is the correct answer: any key we could invent here is a key some other,
            // well-formed reference legitimately owns.
            return Optional.empty();
        }
        String value = environment.get(key);
        return value == null ? Optional.empty() : Optional.of(new SecretValue(value.toCharArray()));
    }

    /**
     * The environment variable name a non-blank reference resolves under (SEC-26).
     *
     * <h2>Injective by construction</h2>
     * <p>The encoding is {@code PREFIX + EnvironmentKeyCodec.hex(reference)}, one {@code %02X} pair
     * per byte — the single derivation shared by all connector sites, rather than a seventh private
     * copy. It is injective in two composed steps: UTF-8 encoding is a bijection
     * between well-formed Java strings and byte sequences, and fixed-width hex encoding is a bijection
     * between byte sequences and hex strings — every byte contributes exactly two output characters,
     * so the output length recovers the input length and the mapping is invertible by splitting into
     * 2-character chunks. No separator or case normalization is applied, so two references that only
     * differ in punctuation or letter case — the defect this replaces — now always produce different
     * keys.</p>
     *
     * <p>The reference is handed to the codec <strong>exactly as received</strong>: no {@code strip},
     * no case fold, no character replacement. That is a property of this site, not of the codec, and
     * no test written against the codec can observe it — a repository seam previously
     * folded case and substituted characters before calling a strict encoder, which reintroduces
     * many-to-one upstream of an injective step and leaves the encoder's strictness untouched and
     * useless. {@code theReferenceReachesTheDerivationUnmodified} in
     * {@code EnvironmentCredentialResolverMalformedReferenceTest} pins it with literal byte
     * expectations.</p>
     *
     * <p>Deliberately does not {@code trim()} before encoding, unlike the scheme it replaces: trimming
     * first is itself a many-to-one step ({@code "foo"} and {@code " foo "} would still collide), so
     * keeping it would preserve a miniature of the same defect. The empty/blank guard in
     * {@link #get} is unaffected — this method is only ever reached for a non-blank reference.</p>
     *
     * <h2>Malformed surrogates are refused</h2>
     * <p>{@code String.getBytes(UTF_8)} silently substitutes for an unpaired UTF-16 surrogate rather
     * than failing, so under the old inline derivation two strings malformed in different ways encoded
     * to the same bytes and therefore the same key. Measurement on JDK 21 shows that the encoder uses
     * a single {@code 0x3F}, an ASCII question mark, as the replacement. The collision class
     * therefore also contained the <em>well-formed</em> reference {@code "?"} — so the aliasing was
     * never confined to malformed input, and one graph-authored reference could read the secret
     * provisioned for another. Deriving through {@link EnvironmentKeyCodec}, which reports instead of
     * substituting, removes the substitution and with it the collision; {@link #get} absorbs the
     * report, for the reasons stated on that method.
     *
     * <p><strong>Whether the schema should also refuse a malformed {@code credentialRef} upstream: it
     * should, and not here.</strong> {@code BehaviorPropertySchema} today refuses a
     * {@code SECRET_REFERENCE} containing whitespace or ISO control characters, and forbids neither
     * half of this collision — an unpaired surrogate is neither, and {@code "?"} is an ordinary
     * printable character that an operator may legitimately want. The argument for refusing upstream
     * is that a reference which cannot be encoded is not a reference at all, and a graph carrying one
     * is better rejected at admission, with a node id and a property name in the message, than
     * accepted and silently unresolvable at execution — the two failures are far apart in time and
     * only the first is diagnosable. The argument for not doing it in <em>this resolver</em> is that it
     * is a different surface with a different blast radius: the schema gates every graph, including
     * graphs already stored, so tightening it can refuse documents that load today, and that belongs
     * separately with its own compatibility statement. Note also which one is the safety
     * property: strict encoding makes the collision unreachable, and a schema check would be
     * defence in depth and a better error message, not the remedy. Refusing upstream would also not
     * remove the need for strictness here, since this resolver is reachable from callers the schema
     * never sees.
     *
     * <p>What is deliberately <em>not</em> done, in either place, is widening the reference filter to
     * an ASCII allow-list to make the problem disappear. The filter is
     * admission policy, this is encoding, and a filter that hides the encoding's strictness makes the
     * strictness unobservable again — which is exactly the state the six connectors were in when their
     * three divergent postures went unnoticed.
     *
     * <h2>A limit that remains</h2>
     * <ul>
     *   <li><strong>This class only.</strong> A second, independent {@code CredentialResolver} —
     *   {@code EnvironmentMailCredentialResolver} in {@code ravenroot-extensions/ravenroot-mail} —
     *   normalized references the same lossy way this method used to
     *   ({@code reference.strip().toUpperCase().replaceAll("[^A-Z0-9]", "_")} under a different
     *   prefix) and was outside SEC-26's scope. It has since been made strict and derives through
     *   {@code EnvironmentKeyCodec} like every other connector site.
     *   The reference namespace is injective for credentials resolved through
     *   {@code EnvironmentCredentialResolver}; it is not injective repo-wide. The two resolvers also
     *   differ in reachability: this class's {@code reference} can originate in graph content (an
     *   HTTP or LLM node's {@code credentialRef}), so an aliasing collision here is attacker-reachable;
     *   the mail resolver's reference comes from an operator-provisioned profile string, and a
     *   graph-supplied {@code credentialRef} is rejected before it would reach it, so its exposure is
     *   an operator-misconfiguration hazard rather than an attacker-reachable one. Same defect class,
     *   different reachability.</li>
     * </ul>
     *
     * <p>Package-private so the injectivity properties in
     * {@code EnvironmentCredentialResolverInjectivityTest} exercise this method directly rather than
     * re-deriving the scheme, which would only prove two independent copies agree with each other.</p>
     *
     * @throws IllegalArgumentException if {@code reference} has no unambiguous encoding — an unpaired
     *     surrogate, or any other UTF-16 sequence UTF-8 could only render by substituting. Callers
     *     inside this class absorb it; see {@link #get}.
     */
    static String environmentVariableName(String reference) {
        return PREFIX + EnvironmentKeyCodec.hex(reference);
    }
}
