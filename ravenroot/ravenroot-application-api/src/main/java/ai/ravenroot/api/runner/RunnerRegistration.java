package ai.ravenroot.api.runner;

import java.util.Objects;
import java.util.Set;

/**
 * Version-one runner capability advertisement. The control plane authenticates the tenant and
 * runner identity and approves the trust profile separately; self-registration grants no trust.
 * Profiles are exact operator-managed identifiers, including sandboxed or explicitly trusted hosts.
 * @param protocolVersion exact protocol version
 * @param tenantId authenticated tenant
 * @param runnerId authenticated runner identifier
 * @param trustProfile approved trust profile identifier
 * @param labels advertised scheduling labels
 * @param capabilities restrictions the driver can actually enforce
 */
public record RunnerRegistration(int protocolVersion, String tenantId, String runnerId, String trustProfile,
                                 Set<String> labels, RunnerPolicy capabilities) {
    /** Current wire protocol version. Unsupported versions fail closed. */
    public static final int PROTOCOL_VERSION = 1;

    /** Validates a bounded, versioned advertisement. */
    public RunnerRegistration {
        if (protocolVersion != PROTOCOL_VERSION || tenantId == null || tenantId.isBlank()
                || tenantId.length() > 256) {
            throw new IllegalArgumentException("unsupported or invalid runner registration");
        }
        runnerId = RunnerPolicy.identifier(runnerId);
        trustProfile = RunnerPolicy.identifier(trustProfile);
        labels = RunnerPolicy.identifiers(labels);
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
