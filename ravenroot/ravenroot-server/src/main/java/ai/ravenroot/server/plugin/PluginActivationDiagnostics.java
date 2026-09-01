package ai.ravenroot.server.plugin;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.plugin.bundle.PluginBundleException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the sanitized console message, reason token and audit detail for one plugin activation
 * failure, all sharing one incident id (PLAT-12) -- pure functions, deliberately, so the
 * sanitization rule can be tested directly without a JVM exit or a real audit trail.
 *
 * <h2>Why one {@link #diagnose} call, not three independent calculations</h2>
 * <p>A {@link PluginBundleException} already carries an incident id, so repeated calculations would
 * read the same value. Every other failure type -- an
 * {@link IllegalArgumentException} from {@code NodePackages.register}, or a
 * {@link NodeSdk.IncompatibleNodePackageException} -- neither carries an incident id, so each of
 * those functions would mint a fresh random id independently. Computing the console message and
 * audit fields separately could then produce two incident ids for one event, breaking the exact
 * correlation this mechanism exists to provide. {@link #diagnose} computes the result once and
 * returns all of it together.</p>
 *
 * <h2>The sanitization rule, from {@code DESIGN.md}</h2>
 * <p>A piece of information belongs in the public message only if it is structurally bounded AND the
 * specific thing the operator needs to act. This class does not hand-classify which diagnostic detail
 * fields meet that bar per failure reason; {@link #neutralize} is the enforcement mechanism for the
 * whole rule, applied uniformly to every value: stripping control characters and non-printable/format
 * Unicode (which also removes bidirectional-override and zero-width code points a "legal Java
 * identifier" grammar alone would not exclude), capping length hard, and quoting the result
 * unambiguously. A bounded identifier passes through close to unchanged because it was already short
 * and clean; unbounded bundle-authored text is what the capping and stripping actually protect
 * against.</p>
 *
 * <h2>Operator remediation</h2>
 * <p>Two of the reasons this class emits are about operator service grants, and both add an
 * environment variable name to the message. That name is derived here rather than raised by the
 * component that refused, because {@code NodePackages} is in core and core must not know that a
 * server environment variable exists. Both values are derived from a bounded package id through the
 * one injective codec, and both still go through {@link #neutralize} like every other detail value:
 * an environment variable NAME is not a secret, but the rule is uniform on purpose.</p>
 *
 * <p>What must never appear, and does not: a grant's value. {@link NodePackageServiceGrantException}
 * carries the variable name and refuses to carry the value, so the credential references and
 * destinations inside a grant cannot reach a console line or an audit record through this path.</p>
 */
public final class PluginActivationDiagnostics {

    private static final int MAX_NEUTRALIZED_LENGTH = 200;
    private static final SecureRandom INCIDENTS = new SecureRandom();
    /** The exact refusal {@code NodePackages.registerAll} raises for an ungranted capability. */
    private static final java.util.regex.Pattern MISSING_SERVICES = java.util.regex.Pattern.compile(
            "^Node package '([^']+)' is missing required operator services: (.+)$");

    private PluginActivationDiagnostics() {
    }

    /** Everything needed for both the console message and the audit record of one failure, computed once. */
    public record Diagnosis(String consoleMessage, String reasonToken, String incidentId, String auditDetail) {
    }

    /**
     * Diagnoses {@code failure} once. Call this exactly once per failure and reuse the result for
     * both the console print and the audit write -- never call it twice for the same failure, or a
     * failure type with no incident id of its own will mint two different ones.
     */
    public static Diagnosis diagnose(RuntimeException failure) {
        String reasonToken;
        String baseMessage;
        String incidentId;
        Map<String, String> detail;
        String missingGrantVariable = missingGrantVariable(failure.getMessage());
        if (failure instanceof PluginBundleException rejection) {
            reasonToken = rejection.reason().name();
            baseMessage = rejection.getMessage();
            incidentId = rejection.incidentId();
            detail = rejection.diagnosticDetail();
        } else if (failure instanceof NodeSdk.IncompatibleNodePackageException incompatible) {
            reasonToken = "INCOMPATIBLE_NODE_SDK";
            baseMessage = incompatible.getMessage();
            incidentId = newIncidentId();
            detail = Map.of();
        } else if (failure instanceof NodePackageServiceGrantException malformedGrant) {
            reasonToken = "NODE_PACKAGE_SERVICE_GRANT_INVALID";
            baseMessage = malformedGrant.getMessage();
            incidentId = newIncidentId();
            detail = Map.of("grantVariable", malformedGrant.variableName());
        } else if (missingGrantVariable != null) {
            // The actionable half of a missing-grant refusal. NodePackages lives in core and
            // must not name a server environment variable, so its message stays exactly as it is --
            // package id and capability names, both bounded -- and the "and here is how to grant it"
            // half is added here, where server-specific knowledge belongs.
            reasonToken = "NODE_PACKAGE_SERVICES_NOT_GRANTED";
            baseMessage = failure.getMessage();
            incidentId = newIncidentId();
            detail = Map.of("setToGrant", missingGrantVariable);
        } else {
            // NodePackages.register's own IllegalArgumentException: duplicate behavior id, a null
            // behavior, a reserved property namespace, or any other package-registration refusal.
            // Its message is already built from bounded, author-chosen identifiers (package id,
            // behavior name) per that class's own design -- see DESIGN.md case 6 -- neutralized here
            // anyway, on principle, not because a specific gap was found in it.
            reasonToken = "PACKAGE_REGISTRATION_FAILED";
            baseMessage = failure.getMessage();
            incidentId = newIncidentId();
            detail = Map.of();
        }

        var console = new StringBuilder("Plugin activation refused: ").append(neutralize(baseMessage));
        // Sorted so the message is deterministic across runs with the same failure -- a diagnostic
        // whose field order changes randomly between otherwise-identical runs is a smaller version of
        // the same "sanitized but not quite actionable" problem this class exists to close.
        new TreeMap<>(detail).forEach((key, value) -> console.append(' ').append(key).append('=').append(neutralize(value)));
        console.append(" [reason=").append(reasonToken).append(" incident=").append(incidentId).append(']');

        String auditDetail = detail.isEmpty() ? baseMessage : baseMessage + " " + new TreeMap<>(detail);
        return new Diagnosis(console.toString(), reasonToken, incidentId, auditDetail);
    }

    /**
     * The environment variable that would have granted the services a package was refused for, or
     * {@code null} when {@code message} is not a missing-grant refusal.
     *
     * <h2>Why this reads a message instead of a typed exception</h2>
     * <p>{@code NodePackages.registerAll} throws a plain {@link IllegalArgumentException} for every
     * registration refusal, and that is core's contract with embedders, documented on
     * {@link PluginActivationOrchestrator#register}. Introducing a typed subclass in core so that the
     * server could recognise one of those refusals would put a server concern -- "which refusal has an
     * environment-variable remedy" -- into the module that is explicitly not allowed to know that
     * environment variable exists.</p>
     *
     * <p>The coupling this leaves is a string one, and it is pinned rather than trusted: {@code
     * PluginActivationDiagnosticsTest} provokes the real refusal through the real
     * {@code NodePackages.registerAll} and asserts the variable comes back, so a reworded core message
     * fails a test instead of silently dropping the actionable half of the diagnostic. The pattern is
     * anchored at both ends for the same reason -- a message that merely contains this sentence is not
     * this refusal.</p>
     *
     * <p>Failing to match is not a failure: the caller falls back to the generic registration reason,
     * which is exactly the diagnostic that was printed before this method existed.</p>
     */
    static String missingGrantVariable(String message) {
        if (message == null) {
            return null;
        }
        var match = MISSING_SERVICES.matcher(message);
        if (!match.matches()) {
            return null;
        }
        String packageId = match.group(1);
        // The same shape NodePackages.requireValidPackageId enforces. A message that got here with
        // anything else did not come from that refusal, whatever it otherwise looks like, and no
        // variable name is invented for it.
        if (!packageId.matches("[a-z0-9](?:[a-z0-9._-]{0,198}[a-z0-9])?")) {
            return null;
        }
        try {
            return EnvironmentNodePackageServiceGrants.environmentVariableName(packageId);
        } catch (IllegalArgumentException notEncodable) {
            return null;
        }
    }

    /**
     * Strips control characters and non-printable/format Unicode, caps length, and quotes the result.
     * Safe to call on already-clean text: it comes back effectively unchanged, plus quoting.
     */
    public static String neutralize(String raw) {
        if (raw == null) {
            return "''";
        }
        var cleaned = new StringBuilder();
        boolean truncated = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            int type = Character.getType(character);
            boolean stripped = type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.SURROGATE || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE;
            if (stripped) {
                continue;
            }
            if (cleaned.length() >= MAX_NEUTRALIZED_LENGTH) {
                truncated = true;
                break;
            }
            cleaned.append(character);
        }
        return "'" + cleaned + (truncated ? "...<truncated>'" : "'");
    }

    private static String newIncidentId() {
        byte[] handle = new byte[8];
        INCIDENTS.nextBytes(handle);
        return HexFormat.of().formatHex(handle);
    }
}
