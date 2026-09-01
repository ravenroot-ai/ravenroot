package ai.ravenroot.server.credential;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identifier a stored credential is known by — <b>minted here and nowhere else</b>, as required
 * by the server-minted-reference contract.
 *
 * <h2>Why the server mints it</h2>
 * <p>the server-minted-reference contract states the rule and the reason: a port that accepted a caller-chosen name
 * "is a cross-tenant clobber primitive: tenant A binds {@code acme.key} and silently overwrites
 * tenant B's {@code acme-key}". The write path is therefore the only thing that decides a reference,
 * and the wire reader refuses a body that offers one — see {@link UserCredentialWire}.</p>
 *
 * <p>That decision also asks for references that are "minted tenant-scoped, opaque and injectively
 * encoded". Opaque and injective are properties of the string and are satisfied here: 128 bits from
 * {@link SecureRandom}, rendered as lowercase hex, so two mints collide with probability that is not
 * worth a sentence and no reference is derived from anything an author supplied. <b>Tenant-scoped is
 * a property of the record, not of the string</b>, and it lives in the store's columns — see
 * {@link UserCredentialStore} for why encoding the tenant into the reference would have been worse
 * rather than better.</p>
 *
 * <h2>The prefix is load-bearing</h2>
 * <p>{@link #PREFIX} makes the user-authored namespace <b>disjoint</b> from the operator-provisioned
 * one that {@code EnvironmentCredentialResolver} serves. Two things depend on that disjointness and
 * neither would work without it:</p>
 * <ul>
 *   <li>{@link CredentialAdmission} refuses a submitted graph that names a reference belonging to
 *       somebody else. It can only apply that rule to references it can recognise, and it must
 *       <em>not</em> apply it to an operator's {@code openai-main}, which has no owner at all.</li>
 *   <li>The resolver chain tries the user store first and falls through to the environment. Without a
 *       recognisable shape, every operator reference would take a database round trip, and — worse —
 *       an author could mint a credential whose reference collided with an operator's binding.</li>
 * </ul>
 *
 * <p>It also passes {@code BehaviorPropertySchema}'s {@code SECRET_REFERENCE} check, which refuses
 * whitespace and control characters: hex and one underscore contain neither.</p>
 */
public final class CredentialReference {

    /**
     * The marker that says "this reference has an owner".
     *
     * <p>Short and unpronounceable on purpose. It appears in GraphML documents authors read, and a
     * longer prefix would be a longer thing to mistake for the meaningful part.</p>
     */
    public static final String PREFIX = "rrc_";

    /** 128 bits. Enough that guessing is not an attack, which is the only property asked of it. */
    private static final int ENTROPY_BYTES = 16;

    private static final Pattern SHAPE =
            Pattern.compile(PREFIX + "[0-9a-f]{" + (ENTROPY_BYTES * 2) + "}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private CredentialReference() {
    }

    /** A reference no caller could have proposed. */
    public static String mint() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return PREFIX + HexFormat.of().formatHex(entropy);
    }

    /** Whether this string is one of ours, as opposed to an operator-provisioned reference. */
    public static boolean isMinted(String reference) {
        return reference != null && SHAPE.matcher(reference).matches();
    }

    /**
     * Every distinct minted reference that appears anywhere in a document, as text.
     *
     * <h4>Why a textual scan and not a GraphML parse</h4>
     * <p>Because the property wanted is <b>"no minted reference reaches execution unless its owner
     * submitted the document"</b>, and a scan of the bytes cannot miss one. A parse could: it would
     * have to enumerate the places a reference may legitimately appear — node properties today,
     * whatever a future node type declares tomorrow — and a place nobody thought of is a place the
     * check does not cover. The scan has no such list.</p>
     *
     * <p>The cost is the opposite error: it matches a reference-shaped string in a prompt or a
     * description, where it is not being used as a credential at all. That is the safe direction.
     * Refusing a graph that quotes somebody else's reference in prose costs an author one edit;
     * admitting one that uses it costs the other author their credential. And a reference that
     * appears nowhere in the document cannot be resolved during its execution, so nothing legitimate
     * is refused by the over-match that would otherwise have worked.</p>
     *
     * <p>Case-insensitive on the hex, then lowercased, because a document may have been round-tripped
     * through something that changed case; the store's keys are lowercase.</p>
     */
    public static Set<String> foundIn(byte[] document) {
        if (document == null || document.length == 0) {
            return Set.of();
        }
        // ISO-8859-1 rather than UTF-8, deliberately: this is a byte-oriented search for an
        // ASCII-only pattern, and decoding as Latin-1 is total -- it cannot throw and cannot drop a
        // byte, whereas a malformed UTF-8 sequence would be replaced and could, in principle, swallow
        // the byte that starts a match. The pattern contains no character above U+007F, so the two
        // decodings agree everywhere a match can occur.
        String text = new String(document, java.nio.charset.StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile(PREFIX + "[0-9a-fA-F]{" + (ENTROPY_BYTES * 2) + "}")
                .matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return found;
    }
}
