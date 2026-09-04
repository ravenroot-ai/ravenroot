package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an opaque IMAP profile token from the operator environment. The value is a
 * semicolon-delimited operator secret, never graph content, of <em>eleven</em> mandatory fields in
 * order: {@code host;port;securityMode;username;credentialRef;folders;connectTimeoutMs;
 * readTimeoutMs;maxConcurrency;maxResults;maxPreviewChars} -- the same eleven names
 * {@link ImapProfile}'s own constructor uses, since that is what each field actually becomes.
 * {@code folders} (field six) is a comma-separated set, not a single value; the other ten are
 * single values. The field-by-field description lives in the module {@code README.md}.
 *
 * <p>Unlike the SMTP format ({@code EnvironmentMailProfileResolver}) there is exactly one accepted
 * field count. Nothing has been appended to this format, so ten fields, or twelve, are simply wrong,
 * not a compatibility shape.
 */
public final class EnvironmentImapProfileResolver implements ImapProfileResolver {
    /**
     * Names, on a channel separate from the return value, which constraint rejected a
     * <em>present</em> IMAP profile -- never the field or the raw environment string that violated
     * it. This matches the SMTP-side contract and previously did not exist here at all: a wrong
     * field count and an unset environment variable were not merely
     * indistinguishable to an operator, they were the identical {@code Optional.empty()}.
     *
     * <h2>Why {@link #resolve} itself stays a total {@code Optional}, unchanged</h2>
     * <p>This resolver has exactly one production caller, {@code MailImapQueryNodeBehavior
     * .resolveProfile}, and it already converts every empty result into one {@code ImapQueryException}
     * with code {@code PROFILE_UNAVAILABLE} and one message, uniformly, regardless of which failure
     * mode produced it. Widening the return type would still have to change that one call site
     * although it adds nothing there: the caller already cannot use, and must not surface to a graph
     * author, which constraint failed. The distinction is for the operator reading logs, not for the
     * caller's control flow, so it belongs on a separate channel instead of in the type callers must
     * handle. Same reasoning, and same outcome, as the SMTP side.
     *
     * <h2>Why a log, and why this shape</h2>
     * <p>{@code tenant} and {@code profile} have already passed {@link #safe} by the time any caller
     * reaches this method, so they are printable identifiers, not operator secret material.
     * {@link Rejection} is a fixed, closed set of symbolic names. Neither argument is, or is derived
     * from, a field of the rejected value: this line can never carry the IMAP host, port, username,
     * credential reference, folder names or any other part of the profile it refuses.
     *
     * <p>An absent profile -- unset variable, or an identifier that never reached a lookup at all --
     * never calls this. Only a variable that <em>is</em> set and <em>is</em> rejected logs anything,
     * so the absence of a line stays a reliable signal that nothing was created.
     *
     * <h2>Naming the constraint, not the act of rejecting</h2>
     * <p>{@link ImapProfile}'s validation is a <em>single disjunction of thirteen conditions raising
     * a single generic exception</em>, so catching what it throws can never recover which condition
     * fired. Catching only that exception would collapse the distinct causes into one word that
     * told an operator only that construction had been attempted and had
     * failed, which is exactly the fact an empty {@code Optional} already gave them. Every name below
     * is therefore decided by a pre-check, in the branch that rejects, before construction.
     *
     * <p>Two of {@link ImapProfile}'s thirteen disjuncts -- {@code tenant} blank and {@code id} blank
     * -- are unreachable from here: {@link #safe} rejects both arguments before any lookup, and its
     * pattern requires at least one leading alphanumeric, so neither can be null or blank at the
     * constructor. They have no name because they have no input. The remaining eleven map to the
     * eleven fields, and six of those fields are parsed as integers, so each of the six carries both
     * a format name and a range name.
     *
     * <h2>Why the values themselves are never forwarded</h2>
     * <p>Not a general precaution -- two specific sites here raise exceptions whose <em>message text
     * carries an operator field</em>, both measured on JDK 21:
     * <ul>
     *   <li>{@code Set.of(String...)} raises {@code IllegalArgumentException: duplicate element:
     *       <folder>} on a repeated folder, naming the folder.</li>
     *   <li>{@code Integer.parseInt} raises {@code NumberFormatException: For input string: "<value>"},
     *       naming the field -- and this format has six numeric fields, so six such sites.</li>
     * </ul>
     * Each is pre-checked by name ({@code DUPLICATE_FOLDER}, the six {@code *_FORMAT} names) rather
     * than caught and relabelled, which is what keeps the value out of the diagnostic.
     *
     * <h2>What must not be copied from the SMTP side</h2>
     * <p>The two records differ where a transplanted check would silently diverge, in <em>opposite</em>
     * directions, so neither list of names could be reused wholesale:
     * <ul>
     *   <li><b>Security mode is compared case-sensitively here.</b> {@code MailProfile}'s constructor
     *       upper-cases {@code securityMode} before testing membership, so the SMTP pre-check must
     *       upper-case too in order to stay equivalent. {@link ImapProfile} does <em>not</em>
     *       normalise: it tests the raw string against {@code Set.of("IMAPS", "STARTTLS")}. Copying
     *       the SMTP line with its {@code toUpperCase} would make this pre-check <em>looser</em> than
     *       the constraint it stands in for -- {@code imaps} would pass the pre-check and then be
     *       refused by the record -- the permissive counterpart to a pre-check that is too strict.
     *       {@code ImapProfileResolverRecordDifferentialTest} catches
     *       both directions, but not by the same means, and the difference matters: a <em>stricter</em>
     *       pre-check breaks its accept-iff-accept equality, whereas a <em>looser</em> one cannot,
     *       because the value reaches the record, the record throws, and {@code RECORD_POLICY} returns
     *       the same empty {@code Optional} construction would have refused anyway. The permissive
     *       direction is caught instead by the constraint <em>name</em> -- see the residual net's own
     *       heading below, which is the sensor for it.</li>
     *   <li><b>Username and credential reference are two constraints, not one pairing.</b> SMTP
     *       allows both blank together (unauthenticated submission) and only refuses the mismatch,
     *       hence its single {@code CREDENTIAL_PAIRING}. {@link ImapProfile} requires both non-blank
     *       unconditionally, as two separate disjuncts, so they get two separate names.</li>
     *   <li><b>There is no address policy to name.</b> {@link ImapProfile} has no sender, recipient,
     *       Reply-To or header allow-list, so SMTP's {@code ALLOWED_FROM_EMPTY},
     *       {@code ALLOWED_RECIPIENTS_EMPTY} and {@code DEFAULT_FROM_NOT_ALLOWED} have no counterpart
     *       and must not appear. The Reply-To policy has no IMAP counterpart.</li>
     * </ul>
     *
     * <h2>{@code RECORD_POLICY}: a residual net, measured unreachable -- and the sensor for
     * permissive drift</h2>
     * <p>Kept for the same reason as on the SMTP side -- so a constraint added to {@link ImapProfile}
     * later, one this resolver has not been taught to pre-check, still fails closed with a
     * labelled-if-generic reason instead of reverting to the previous silence -- and, unlike a
     * comment that merely asserts it, its unreachability today is measured, in two independent ways:
     * <ul>
     *   <li><em>By enumeration.</em> All thirteen of {@link ImapProfile}'s disjuncts are accounted
     *       for above: two excluded by {@link #safe}, eleven pre-checked by name. Every site that can
     *       raise while <em>deriving</em> the constructor arguments (the six {@code Integer.parseInt}
     *       calls, the one {@code Set.of}) is likewise pre-checked, and each parsed integer is reused
     *       rather than parsed a second time, so no derivation survives to throw inside the
     *       {@code try}.</li>
     *   <li><em>By property, not by a list of cases.</em> Both tests in {@code
     *       ImapProfileResolverRecordDifferentialTest} -- the exhaustive single-field sweep and the
     *       near-valid randomised property -- assert of <em>every</em> rejection that its name is not
     *       {@code RECORD_POLICY}, and {@code ImapProfileRejectionDiagnosticsTest} sweeps each field
     *       through an adversarial corpus for the same conclusion.</li>
     * </ul>
     *
     * <p>That second measurement is doing more work than it appears to. Because the net is
     * unreachable while every pre-check is faithful, a rejection that <em>does</em> carry this name is
     * proof that some pre-check admitted a value the record then refused -- which is the one form of
     * drift an accept-iff-accept comparison can never see, since the net makes the resolver's answer
     * agree with the record's for the wrong reason. So the residual net is not only a fail-closed
     * safety device: it is the instrument that makes permissive drift observable at all, and removing
     * it would take that observability with it.
     */
    enum Rejection {
        FIELD_COUNT, HOST_BLANK, PORT_FORMAT, PORT_RANGE, UNKNOWN_SECURITY_MODE, USERNAME_BLANK,
        CREDENTIAL_REF_BLANK, DUPLICATE_FOLDER, FOLDERS_EMPTY, CONNECT_TIMEOUT_FORMAT,
        CONNECT_TIMEOUT_RANGE, READ_TIMEOUT_FORMAT, READ_TIMEOUT_RANGE, CONCURRENCY_FORMAT,
        CONCURRENCY_RANGE, MAX_RESULTS_FORMAT, MAX_RESULTS_RANGE, PREVIEW_CHARS_FORMAT,
        PREVIEW_CHARS_RANGE, RESERVED_DESTINATION, RECORD_POLICY
    }

    /** Mirrors {@link ImapProfile}'s own membership test exactly, including its case sensitivity. */
    private static final Set<String> SECURITY_MODES = Set.of("IMAPS", "STARTTLS");
    private static final int FIELDS = 11;
    private static final System.Logger LOGGER = System.getLogger("ai.ravenroot.mail.imap.profile.rejected");

    private final Map<String, String> env;
    private final ReservedNetworkPolicy destinationPolicy;

    public EnvironmentImapProfileResolver() { this(System.getenv()); }

    EnvironmentImapProfileResolver(Map<String, String> env) {
        this.env = Map.copyOf(env);
        this.destinationPolicy = ReservedNetworkPolicy.fromEnvironment(env);
    }

    @Override public Optional<ImapProfile> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        final String key;
        try { key = environmentVariableName(tenant, profile); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String raw = env.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        // Checked before anything is derived: with the wrong count there is no ImapProfile argument
        // list to attempt, so this constraint has no record-side counterpart to be differential with.
        if (p.length != FIELDS) return rejected(tenant, profile, Rejection.FIELD_COUNT);

        if (p[0].isBlank()) return rejected(tenant, profile, Rejection.HOST_BLANK);
        try { destinationPolicy.requireAllowedLiteral(p[0]); }
        catch (SecurityException refused) { return rejected(tenant, profile, Rejection.RESERVED_DESTINATION); }
        int port;
        try { port = Integer.parseInt(p[1]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.PORT_FORMAT); }
        if (port < 1 || port > 65535) return rejected(tenant, profile, Rejection.PORT_RANGE);
        // Deliberately not upper-cased: ImapProfile compares the raw string. See this enum's comment.
        if (!SECURITY_MODES.contains(p[2])) return rejected(tenant, profile, Rejection.UNKNOWN_SECURITY_MODE);
        if (p[3].isBlank()) return rejected(tenant, profile, Rejection.USERNAME_BLANK);
        if (p[4].isBlank()) return rejected(tenant, profile, Rejection.CREDENTIAL_REF_BLANK);

        // Both folder constraints are decided on the split array, not by catching Set.of: its
        // IllegalArgumentException names the repeated folder. The two are disjoint -- a zero-length
        // array has no duplicates -- so their order does not decide any input's name.
        String[] folderNames = p[5].split(",");
        if (new HashSet<>(Arrays.asList(folderNames)).size() != folderNames.length)
            return rejected(tenant, profile, Rejection.DUPLICATE_FOLDER);
        Set<String> folders = folders(p[5]);
        // ImapProfile now strips every name and discards the blank ones before testing
        // emptiness, so a field that is blank throughout, or one that reduces to nothing but blanks
        // after a stray comma, must not be confused with a genuinely non-empty set -- both refuse the
        // same way the record does. This pre-check mirrors that by testing "empty after stripping",
        // not "empty as the raw set": "" and " " both strip to nothing and are refused here, while
        // ",INBOX" and "INBOX,,Archive" still have a real name left and pass through to the record,
        // which is the one that actually drops the blank entry (see ImapProfile's own comment).
        if (folders.stream().allMatch(name -> name.strip().isEmpty())) return rejected(tenant, profile, Rejection.FOLDERS_EMPTY);

        int connectTimeoutMs;
        try { connectTimeoutMs = Integer.parseInt(p[6]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.CONNECT_TIMEOUT_FORMAT); }
        if (connectTimeoutMs < 1) return rejected(tenant, profile, Rejection.CONNECT_TIMEOUT_RANGE);
        int readTimeoutMs;
        try { readTimeoutMs = Integer.parseInt(p[7]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.READ_TIMEOUT_FORMAT); }
        if (readTimeoutMs < 1) return rejected(tenant, profile, Rejection.READ_TIMEOUT_RANGE);
        int maxConcurrency;
        try { maxConcurrency = Integer.parseInt(p[8]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.CONCURRENCY_FORMAT); }
        if (maxConcurrency < 1 || maxConcurrency > 16) return rejected(tenant, profile, Rejection.CONCURRENCY_RANGE);
        int maxResults;
        try { maxResults = Integer.parseInt(p[9]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.MAX_RESULTS_FORMAT); }
        if (maxResults < 1 || maxResults > 500) return rejected(tenant, profile, Rejection.MAX_RESULTS_RANGE);
        int maxPreviewChars;
        try { maxPreviewChars = Integer.parseInt(p[10]); }
        catch (NumberFormatException notNumeric) { return rejected(tenant, profile, Rejection.PREVIEW_CHARS_FORMAT); }
        if (maxPreviewChars < 0 || maxPreviewChars > 65536) return rejected(tenant, profile, Rejection.PREVIEW_CHARS_RANGE);

        try { return Optional.of(new ImapProfile(tenant, profile, p[0], port, p[2], p[3], p[4], folders,
                connectTimeoutMs, readTimeoutMs, maxConcurrency, maxResults, maxPreviewChars)); }
        catch (RuntimeException invalid) { return rejected(tenant, profile, Rejection.RECORD_POLICY); }
    }

    private static Optional<ImapProfile> rejected(String tenant, String profile, Rejection constraint) {
        LOGGER.log(System.Logger.Level.WARNING,
                "ravenroot_imap_profile_rejected tenant={0} profile={1} constraint={2}", tenant, profile, constraint);
        return Optional.empty();
    }

    /**
     * Derivation only, with no {@link #safe} re-check — the guard is an early exit in {@code resolve}
     * and the seam exists so the derivation properties can reach the codec past it.
     *
     * <p>This was the second of two <em>permissive</em> derivations: it encoded with
     * {@code String.getBytes(UTF_8)}, which substitutes a single {@code 0x3F} — an ASCII question mark,
     * not U+FFFD — for an unpaired surrogate instead of refusing, so every unpaired surrogate and the
     * well-formed identifier {@code "?"} collided on one profile key. It now reports, like the other
     * five. {@link EnvironmentKeyCodec} carries the reasoning for that choice.
     */
    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_IMAP_PROFILE_" + EnvironmentKeyCodec.hex(tenant) + "_" + EnvironmentKeyCodec.hex(profile);
    }

    private static boolean safe(String v) { return v != null && v.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }

    /** Package-private, not private, so {@code ImapProfileResolverRecordDifferentialTest} can reuse
     *  this exact derivation instead of a second copy that could quietly drift from it. */
    static Set<String> folders(String value) { return Set.of(value.split(",")); }
}
