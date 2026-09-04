package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an opaque profile token from the operator environment. The value is a semicolon-delimited
 * operator secret, never graph content, of ten mandatory fields in order:
 * {@code host;port;securityMode;allowPlaintext;authUsername;credentialRef;defaultFrom;
 * allowedRecipients;allowedHeaders;maxConcurrency} -- the same ten names {@link MailProfile}'s own
 * constructor uses, since that is what each field actually becomes -- followed by an
 * <em>optional</em> eleventh, {@code allowedReplyTo}.
 *
 * <p>{@code allowedRecipients}, {@code allowedHeaders} and {@code allowedReplyTo} are each a
 * comma-separated set, not a single value ({@link #csv}). {@code defaultFrom} (field seven) is read
 * twice: as itself, and as the seed for {@code allowedFrom}.
 *
 * <h2>Where {@code allowedReplyTo} comes from, and why the count decides it</h2>
 * <p>The ten-field format has no field for Reply-To and {@code csv(p[6])} is passed to
 * <em>both</em> {@code allowedFrom} and {@code allowedReplyTo}, so an operator who wrote {@code *} in
 * the sender field to authorise senders also opened Reply-To to {@code *} -- silently, and with no way
 * to narrow it. The two properties are enforced separately ({@code MailSendNodeBehavior} refuses a
 * Reply-To outside {@code allowedReplyTo}), so the allow-list existed and was applied; it just was not
 * configurable. Reply-To is the field that redirects a reply to an address the profile never
 * authorised, which is why "the same value happens to be right" is not an answer here.
 *
 * <p>The eleventh field is <em>appended</em>, never inserted, so fields one to ten keep their meaning
 * byte for byte. That makes the field count an unambiguous discriminator: the value is split on
 * {@code ;} with no escape syntax, so no field can contain a separator and no ten-field value can be
 * read as eleven, or the reverse. {@link #resolve} therefore accepts both shapes and derives:
 * <ul>
 *   <li><b>eleven fields</b> -- {@code allowedReplyTo = csv(p[10])}, independent of the sender policy.
 *       A <em>present but blank</em> eleventh field is an empty set, which refuses every non-blank
 *       Reply-To: that is the way to deny Reply-To outright, and it is distinguishable from omitting
 *       the field.</li>
 *   <li><b>ten fields</b> -- {@code allowedReplyTo = allowedFrom}, which is exactly what an existing
 *       ten-field profile resolves to. This is a declared compatibility shape, not an
 *       old indexing coincidence: it is written down here and in {@code docs/deployment.md}, and
 *       {@code MailProfileReplyToPolicyTest} pins it explicitly. Keeping it is what stops every
 *       already-provisioned profile from becoming unresolvable on upgrade -- measured: a field count
 *       the parser does not expect is rejected with {@code FIELD_COUNT}, and the profile's one
 *       production caller turns that empty {@code Optional} into a {@code CONFIGURATION} failure of
 *       every send. Retiring the ten-field shape would therefore be a compatibility change, not a
 *       parser cleanup.</li>
 * </ul>
 *
 * <p>{@code MailProfileDocumentedExampleTest} carries a conformant example and pins the rejection
 * contract against this parser.
 */
public final class EnvironmentMailProfileResolver implements MailProfileResolver {
    /**
     * Names, on a channel separate from the return value, which constraint rejected a
     * <em>present</em> profile -- never the field or the raw environment string that violated it.
     *
     * <h2>Why {@link #resolve} itself stays a total {@code Optional}, unchanged</h2>
     * <p>This resolver has exactly one production caller, {@code MailSendNodeBehavior.Settings.from},
     * and it already converts every empty result into one {@code MailSendException} with one message,
     * uniformly, regardless of which failure mode produced it. {@code CredentialResolver} stays
     * total because its callers are <em>not</em> uniform.
     * Widening the return type here would still have to change that one call site although it adds
     * nothing there: the caller already cannot use, and must not surface to a graph author, which
     * constraint failed. The distinction is for the operator reading logs, not for the caller's
     * control flow, so it belongs on a separate channel instead of in the type callers must handle.
     *
     * <h2>Why a log, and why this shape</h2>
     * <p>{@code tenant} and {@code profile} have already passed {@link #safeId} by the time any
     * caller reaches this method, so they are printable identifiers, not operator secret material --
     * identifiers only, never a fragment of the value that was refused. That discipline is not local
     * to this class: it is what every refusal line in this product is held to, because a refusal log
     * is a data flow that did not exist while the refusal happened before execution, and server logs
     * are structured and persisted outside the runtime. It was previously cited here by pointing at
     * {@code UnconfiguredAdapterRefusal}, which left the core with the AI nodes; the rule is
     * therefore stated rather than referred to.
     * {@link Rejection} is a fixed, closed set of symbolic names. Neither argument is, or is derived
     * from, a field of the rejected value: this line can never carry the SMTP host, port, credential
     * reference or any other part of the profile it refuses.
     *
     * <p>An absent profile -- unset variable, or an identifier that never reached a lookup at all --
     * never calls this. Only a variable that <em>is</em> set and <em>is</em> rejected logs anything,
     * so the absence of a line stays a reliable signal that nothing was created.
     *
     * <h2>Naming the constraint, not the act of rejecting</h2>
     * <p>Catching {@code new MailProfile(...)}'s single {@code RuntimeException} can name only the
     * catch -- {@code RECORD_POLICY} -- rather than the constraint the record had
     * actually enforced. Ten different malformed profiles (an unrecognised security mode, an
     * out-of-range port, a username with no credential reference, an empty recipient policy, ...)
     * all produced that same word, which tells an operator only that construction was attempted and
     * failed -- exactly the fact they already had from an empty {@code Optional}. {@code MailProfile}
     * itself throws one exception for many distinct conditions (its own validation is a single
     * disjunction, by design -- see {@code MailProfile}'s constructor), so catching what it throws
     * can never recover which one fired.
     *
     * <p>The resolver pre-checks, by name, every one of {@code MailProfile}'s active constraints that this
     * resolver's inputs can actually reach -- mirroring the technique already used above for the
     * field count and the two integer formats -- so the branch that rejects <em>is</em> the branch
     * that names the constraint. Each check compares only shape (blank, membership in a fixed set,
     * numeric range, an emptiness or a pairing test): none of it is, or is derived from, the
     * rejected value.
     *
     * <p>{@code RECORD_POLICY} remains, as a residual net rather than a live path: under today's
     * fixed constants (maxRecipients, maxHeaders, the attachment and timeout ceilings, retries) every
     * one of {@code MailProfile}'s constraints that this resolver can trigger is named above, so
     * construction should never reach it. It exists so a validation rule added to {@code MailProfile}
     * later -- one this resolver has not yet been taught to pre-check and name -- still fails closed
     * with a labelled-if-generic reason instead of reverting silently to the previous behaviour.
     */
    enum Rejection {
        FIELD_COUNT, PORT_FORMAT, CONCURRENCY_FORMAT, HOST_BLANK, UNKNOWN_SECURITY_MODE, PORT_RANGE,
        CONCURRENCY_RANGE, CREDENTIAL_PAIRING, DUPLICATE_LIST_ENTRY, ALLOWED_FROM_EMPTY,
        ALLOWED_RECIPIENTS_EMPTY, DEFAULT_FROM_NOT_ALLOWED, RESERVED_DESTINATION, RECORD_POLICY
    }
    private static final Set<String> SECURITY_MODES = Set.of("SMTPS", "STARTTLS", "SMTP");
    private static final System.Logger LOGGER = System.getLogger("ai.ravenroot.mail.profile.rejected");
    private final Map<String, String> environment;
    private final ReservedNetworkPolicy destinationPolicy;
    public EnvironmentMailProfileResolver() { this(System.getenv()); }
    EnvironmentMailProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.destinationPolicy = ReservedNetworkPolicy.fromEnvironment(environment);
    }
    @Override public Optional<MailProfile> resolve(String tenant, String profile) {
        if (!safeId(tenant) || !safeId(profile)) return Optional.empty();
        final String key;
        try { key = environmentVariableName(tenant, profile); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != 10 && p.length != 11) return rejected(tenant, profile, Rejection.FIELD_COUNT);
        if (p[0].isBlank()) return rejected(tenant, profile, Rejection.HOST_BLANK);
        try { destinationPolicy.requireAllowedLiteral(p[0]); }
        catch (SecurityException refused) { return rejected(tenant, profile, Rejection.RESERVED_DESTINATION); }
        if (!SECURITY_MODES.contains(p[2].toUpperCase(Locale.ROOT))) return rejected(tenant, profile, Rejection.UNKNOWN_SECURITY_MODE);
        int port;
        try { port = Integer.parseInt(p[1]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.PORT_FORMAT); }
        if (port < 1 || port > 65535) return rejected(tenant, profile, Rejection.PORT_RANGE);
        int maxConcurrency;
        try { maxConcurrency = Integer.parseInt(p[9]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.CONCURRENCY_FORMAT); }
        if (maxConcurrency < 1 || maxConcurrency > 16) return rejected(tenant, profile, Rejection.CONCURRENCY_RANGE);
        if (p[4].isBlank() != p[5].isBlank()) return rejected(tenant, profile, Rejection.CREDENTIAL_PAIRING);
        // MailProfile's own Set.of(String...) throws IllegalArgumentException("duplicate element: <value>")
        // on a repeated entry -- a message that carries the profile's own field. This is the real
        // reason its message is never forwarded. The two Integer.parseInt calls above, after this
        // extraction, can no longer reach any catch here.
        final Set<String> allowedFrom, allowedReplyTo, allowedRecipients, allowedHeaders;
        // The eleventh field, when present, is the only source of allowedReplyTo; when absent the
        // sender allow-list stands in for it for ten-field compatibility (see this class's comment). The
        // ternary reads p[10] only inside the p.length == 11 branch, so a ten-field value never
        // indexes past its own end.
        try { allowedFrom = csv(p[6]); allowedReplyTo = p.length == 11 ? csv(p[10]) : allowedFrom;
              allowedRecipients = csv(p[7]); allowedHeaders = csv(p[8]); }
        catch (IllegalArgumentException duplicate) { return rejected(tenant, profile, Rejection.DUPLICATE_LIST_ENTRY); }
        if (allowedFrom.isEmpty()) return rejected(tenant, profile, Rejection.ALLOWED_FROM_EMPTY);
        if (allowedRecipients.isEmpty()) return rejected(tenant, profile, Rejection.ALLOWED_RECIPIENTS_EMPTY);
        // Mirrors MailProfile.allowsAddress(allowedFrom, defaultFrom) -- an instance method, so it
        // cannot be called before an instance exists -- without lowering the value into the log.
        //
        // MailProfile compares against normalized(allowedFrom) (its constructor lower-cases every
        // element before storing it), not against the raw, case-preserving set csv() just produced.
        // Comparing against the raw set here made this pre-check strictly stricter than the
        // constraint it replaces: a defaultFrom that differed from its own allow-list only in case
        // (e.g. "From@x.test" against an allow-list built from that very field) was rejected here
        // even though MailProfile would have accepted it. Lower-casing allowedFrom before the
        // membership test, exactly as normalized() does, keeps this check equivalent to -- not
        // tighter than -- MailProfile's own.
        Set<String> allowedFromLower = allowedFrom.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!p[6].isBlank() && !allowedFromLower.contains("*") && !allowedFromLower.contains(p[6].toLowerCase(Locale.ROOT)))
            return rejected(tenant, profile, Rejection.DEFAULT_FROM_NOT_ALLOWED);
        try { return Optional.of(new MailProfile(tenant, profile, p[0], port, p[2], Boolean.parseBoolean(p[3]), p[4], p[5], p[6],
                allowedFrom, allowedReplyTo, allowedRecipients, allowedHeaders, 100, 40, 8192, 1_048_576, 10, 5_242_880,
                10_485_760, 13_981_016, 10_000, 30_000, 30_000, 0, maxConcurrency)); }
        catch (RuntimeException invalid) { return rejected(tenant, profile, Rejection.RECORD_POLICY); }
    }
    private static Optional<MailProfile> rejected(String tenant, String profile, Rejection constraint) {
        LOGGER.log(System.Logger.Level.WARNING,
                "ravenroot_mail_profile_rejected tenant={0} profile={1} constraint={2}", tenant, profile, constraint);
        return Optional.empty();
    }
    /**
     * Derivation only, with no {@link #safeId} re-check — the guard is an early exit in
     * {@code resolve} and the seam exists so the derivation properties can reach the codec past it.
     *
     * <p>This site was one of two <em>permissive</em> derivations: it encoded with
     * {@code String.getBytes(UTF_8)}, which substitutes a single {@code 0x3F} — an ASCII question
     * mark, not U+FFFD — for an unpaired surrogate rather than refusing. Every unpaired surrogate,
     * and the well-formed identifier {@code "?"}, therefore produced the same profile key. It now
     * reports, like the other five. {@link EnvironmentKeyCodec} carries the reasoning for that choice.
     */
    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_MAIL_PROFILE_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profile);
    }
    private static boolean safeId(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"); }
    /** Package-private, not private, so {@code MailProfileResolverRecordDifferentialTest} can reuse
     * this exact derivation instead of a second copy that could quietly drift from it. */
    static Set<String> csv(String value) { return value.isBlank() ? Set.of() : Set.of(value.split(",")); }
}
