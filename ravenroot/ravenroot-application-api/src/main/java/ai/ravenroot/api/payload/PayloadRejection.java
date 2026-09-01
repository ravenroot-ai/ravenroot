package ai.ravenroot.api.payload;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single declared sanitisation policy for every payload rejection (API-01).
 *
 * <p>This mirrors {@code GraphMlRejection}, and deliberately so: the repository already learned that
 * two rejection paths which merely <em>happen to agree</em> will eventually stop agreeing, and that
 * the fix is one file that owns the wording. The rules are the same three:</p>
 *
 * <ol>
 *   <li><b>A public message is assembled exclusively from text authored in this file</b> and from
 *       server-side limit numbers. No factory here accepts a caller {@code String}, so interpolating
 *       payload content into a public message is not something a call site can get wrong — it is not
 *       expressible.</li>
 *   <li><b>Everything derived from the payload is a diagnostic detail.</b> Offsets, observed sizes,
 *       key names and token text reach {@link PayloadException#diagnosticDetail()} and never
 *       {@link Throwable#getMessage()}. A key name in a <em>rejected</em> payload is a string the
 *       submitter wrote which nothing has validated.</li>
 *   <li><b>Every rejection carries an incident id</b>, present in the response and in the server-side
 *       record, so diagnosis stays possible without the response carrying the payload.</li>
 * </ol>
 *
 * <p>Limit numbers are the one thing that is safe to state: they are the server's own configuration,
 * they are already documented, and a caller that cannot see them can only discover them by binary
 * search — which is a worse outcome for everyone than saying so.</p>
 */
final class PayloadRejection {
    private static final SecureRandom INCIDENTS = new SecureRandom();

    private PayloadRejection() {
    }

    static PayloadException tooLarge(int observedBytes, int limitBytes) {
        return build(PayloadException.Reason.TOO_LARGE,
                "Payload exceeds the configured encoded size limit of " + limitBytes + " bytes",
                detail("observedBytes", Integer.toString(observedBytes)));
    }

    static PayloadException depthExceeded(int limit) {
        return build(PayloadException.Reason.DEPTH_LIMIT_EXCEEDED,
                "Payload nesting exceeds the configured depth limit of " + limit, Map.of());
    }

    static PayloadException collectionExceeded(int limit) {
        return build(PayloadException.Reason.COLLECTION_LIMIT_EXCEEDED,
                "A payload list or map exceeds the configured element limit of " + limit, Map.of());
    }

    static PayloadException valueCountExceeded(int limit) {
        return build(PayloadException.Reason.VALUE_COUNT_LIMIT_EXCEEDED,
                "Payload exceeds the configured total value limit of " + limit, Map.of());
    }

    static PayloadException textTooLong(int limit) {
        return build(PayloadException.Reason.TEXT_TOO_LONG,
                "A payload text value exceeds the configured length limit of " + limit, Map.of());
    }

    static PayloadException keyTooLong(int limit) {
        return build(PayloadException.Reason.KEY_TOO_LONG,
                "A payload key exceeds the configured length limit of " + limit, Map.of());
    }

    /** The offending key is a detail, not a message: it is submitter-authored, unvalidated text. */
    static PayloadException duplicateKey(String observedKey) {
        return build(PayloadException.Reason.DUPLICATE_KEY,
                "A payload map declares the same key twice", detail("key", observedKey));
    }

    static PayloadException malformed(int offset, String observedToken) {
        var details = new LinkedHashMap<String, String>();
        details.put("offset", Integer.toString(offset));
        if (observedToken != null) {
            details.put("token", truncate(observedToken));
        }
        return build(PayloadException.Reason.MALFORMED, "Payload is not well-formed", details);
    }

    static PayloadException unsupportedType() {
        return build(PayloadException.Reason.UNSUPPORTED_TYPE,
                "Payload contains a value outside the supported scalar, list and map model", Map.of());
    }

    static PayloadException kindMismatch(PayloadKind declared, PayloadKind actual) {
        return build(PayloadException.Reason.KIND_MISMATCH,
                "Payload does not match its declared kind", Map.of("declared", declared.name(),
                        "actual", actual.name()));
    }

    static PayloadException unsupportedContractVersion(String observedContract) {
        return build(PayloadException.Reason.UNSUPPORTED_CONTRACT_VERSION,
                "Payload declares a contract version this server does not implement",
                detail("contract", truncate(observedContract)));
    }

    static PayloadException reservedKey() {
        return build(PayloadException.Reason.RESERVED_KEY,
                "Reserved security keys must not be supplied in a payload", Map.of());
    }

    private static Map<String, String> detail(String key, String value) {
        return value == null ? Map.of() : Map.of(key, value);
    }

    /**
     * Bounds a diagnostic value.
     *
     * <p>Truncation is acceptable <em>here</em> and nowhere else: this string is going to a
     * server-side sink, not to the submitter, so the objection to truncation — that it leaves
     * attacker content in the response — does not apply. What it does prevent is a rejected megabyte
     * of text being copied verbatim into an audit stream.</p>
     */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 64 ? value : value.substring(0, 64) + "<truncated>";
    }

    private static PayloadException build(PayloadException.Reason reason, String message,
                                          Map<String, String> diagnosticDetail) {
        byte[] handle = new byte[8];
        INCIDENTS.nextBytes(handle);
        return new PayloadException(reason, message, HexFormat.of().formatHex(handle),
                Map.copyOf(diagnosticDetail));
    }
}
