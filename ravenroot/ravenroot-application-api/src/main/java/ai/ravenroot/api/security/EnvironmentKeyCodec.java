package ai.ravenroot.api.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * The one derivation of an environment-variable key component from an operator identifier.
 *
 * <p>The encoding is {@code hex(value.getBytes(UTF_8))}, upper case, one {@code %02X} pair per byte,
 * no separator and no case normalization. It is injective in two composed steps — UTF-8 encoding is a
 * bijection between well-formed strings and byte sequences, fixed-width hex is a bijection between
 * byte sequences and hex strings — so two distinct identifiers always produce two distinct key
 * components, and a component can be split back into bytes in 2-character chunks.
 *
 * <h2>Why this class exists at all</h2>
 * <p>Previously the same operation had six implementations across four connectors, in two families
 * of severity and three postures of failure. The divergence was not observable, because every
 * resolver filters tenant and profile with {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}} — pure ASCII —
 * before deriving. That filter is the mask, not the remedy: the day it widens, for a Unicode tenant
 * or an identifier arriving from an identity provider, three connectors would silently stop behaving
 * like the other three. One implementation removes the divergence while the mask is still in place,
 * which is the only moment at which removing it costs nothing.
 *
 * <h2>Severity: report, never substitute — and why</h2>
 * <p>{@code String.getBytes(UTF_8)} substitutes for an unpaired UTF-16 surrogate instead of failing,
 * and the substitute is not what the surrounding documentation long claimed. It is not U+FFFD: the
 * encoder's replacement is a single byte {@code 0x3F}, an ASCII question mark. Measured on JDK 21 for
 * {@code \uD800}, {@code \uD801}, {@code \uDBFF}, {@code \uDC00} and {@code \uDFFF} — every one of
 * them encodes to {@code 3F}.
 *
 * <p>Substitution is therefore <em>many-to-one</em>, and its collision class is wider than a U+FFFD
 * reading suggests. Under U+FFFD only malformed identifiers would collide with each other. Under
 * {@code 0x3F} they also collide with a perfectly <strong>well-formed</strong> one: the literal
 * string {@code "?"} encodes to {@code 3F} as well. A malformed reference does not merely alias onto
 * other malformed references, it aliases onto a legitimate, operator-provisionable name. For a
 * credential reference that means one reference resolving to another reference's secret; for a
 * tenant component it means two tenants sharing a profile. That is a confidentiality failure, not a
 * formatting nuisance, so it is worth more than the convenience of always returning a key.
 *
 * <p>The two contracts on offer differ in what they promise the operator. Substituting promises a key
 * always, and silently trades injectivity — the property the whole scheme exists to provide — for
 * that promise. Reporting promises that any key it returns is unambiguous, and refuses rather than
 * returning an ambiguous one. This class chooses reporting: an identifier that cannot be encoded
 * without collapsing onto another identifier has no correct key, and inventing one is worse than
 * having none. The encoder is therefore configured with {@link CodingErrorAction#REPORT} on both
 * malformed input and unmappable characters, and the failure is raised, not absorbed.
 *
 * <h2>Failure posture: throw here, fail closed at the resolver</h2>
 * <p>This class raises {@link IllegalArgumentException}; every caller catches it and returns an empty
 * {@link java.util.Optional}. The split is deliberate. Raising here keeps the codec total in its
 * postcondition — it either returns an unambiguous key or returns nothing at all — and keeps the
 * reason for the refusal attached to the exception rather than erased at the point it is detected.
 * Absorbing at the resolver is what {@code resolve} already promises: its return type says "there may
 * be no profile or credential for this identity", and every other rejection in those methods — a
 * filtered identifier, an absent variable, a wrong field count, an unparseable number — already
 * answers with an empty result. Making one rejection reason escape as an exception while its
 * neighbours return empty would be an inconsistency inside a single method, and would turn a
 * malformed identifier into a fault crossing a connector boundary at admission time.
 *
 * <p>Note which way this cuts, and count it properly. There were <em>six implementations</em> of
 * the derivation; they are reached from <em>eleven</em> resolver call sites. Of those eleven, six
 * already absorbed the refusal and answered empty, three derived outside any {@code try} and would
 * have propagated ({@code EnvironmentKafkaProfileResolver},
 * {@code EnvironmentKafkaConsumerProfileResolver}, {@code EnvironmentTelegramProfileResolver}), and
 * two could not fail at all because they were the permissive pair. Choosing to absorb therefore
 * changes observable behaviour at three sites; choosing to propagate would have changed six. Either
 * way the change is invisible today, since every input that could reach it is one the ASCII filter
 * already rejects. So the tally is a consequence of the choice, not its reason: the reason is the
 * return type of {@code resolve}.
 *
 * <h2>What this class does not do</h2>
 * <p>It does not filter. The {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}} guard on tenant and profile stays
 * where it is, in each resolver, and must not be widened here — the guard is admission policy, this
 * is encoding, and collapsing the two would make the encoding's strictness unobservable again.
 */
public final class EnvironmentKeyCodec {
    private EnvironmentKeyCodec() {
    }

    /**
     * The key component a single operator identifier encodes to.
     *
     * @param value the identifier; a tenant, a profile name or a credential reference
     * @return upper-case hex of the UTF-8 bytes of {@code value}
     * @throws IllegalArgumentException if {@code value} is {@code null}, or contains an unpaired
     *     surrogate or any other UTF-16 sequence that cannot be encoded to UTF-8 without substitution
     */
    public static String hex(String value) {
        if (value == null) {
            throw new IllegalArgumentException("environment key component must be non-null");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return HexFormat.of().withUpperCase().formatHex(bytes);
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(
                    "environment key component contains malformed UTF-16 and has no unambiguous encoding",
                    malformed);
        }
    }
}
