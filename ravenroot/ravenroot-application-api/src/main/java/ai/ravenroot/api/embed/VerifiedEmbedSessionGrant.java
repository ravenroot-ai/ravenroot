package ai.ravenroot.api.embed;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side registered authority for one embed session family.
 *
 * <p>The HTTP create-session request supplies only {@link #registrationId()}. Issuer, subject,
 * tenant, parent origin and graph coordinates are all provisioned out of band and are compared to
 * the authenticated workload before the grant can be resolved.</p>
 * @param registrationId operator-created registration used to resolve this session family
 * @param revision positive registration revision captured for revocation checks
 * @param workloadIssuer verified workload issuer allowed to redeem the registration
 * @param workloadSubject verified workload subject allowed to redeem the registration
 * @param tenantId tenant shared by the session and graph grants
 * @param parentOrigin exact parent origin accepted by the embedded-browser boundary
 * @param capabilities immutable capabilities granted to the session
 * @param graphGrant server-verified graph projection authority
 * @param themeOverride optional server-selected presentation override
 */
public record VerifiedEmbedSessionGrant(String registrationId, long revision, String workloadIssuer,
                                        String workloadSubject, String tenantId, String parentOrigin,
                                        Set<EmbedCapability> capabilities,
                                        VerifiedEmbedGraphGrant graphGrant,
                                        Optional<EmbedTheme> themeOverride) {
/**
 * Compatibility constructor for registrations that follow system/default theme precedence.
 * @param registrationId operator-created registration used to resolve this session family
 * @param revision positive registration revision captured for revocation checks
 * @param workloadIssuer verified workload issuer allowed to redeem the registration
 * @param workloadSubject verified workload subject allowed to redeem the registration
 * @param tenantId tenant shared by the session and graph grants
 * @param parentOrigin exact parent origin accepted by the embedded-browser boundary
 * @param capabilities immutable capabilities granted to the session
 * @param graphGrant server-verified graph projection authority
 */
    public VerifiedEmbedSessionGrant(String registrationId, long revision, String workloadIssuer,
                                     String workloadSubject, String tenantId, String parentOrigin,
                                     Set<EmbedCapability> capabilities,
                                     VerifiedEmbedGraphGrant graphGrant) {
        this(registrationId, revision, workloadIssuer, workloadSubject, tenantId, parentOrigin,
                capabilities, graphGrant, Optional.empty());
    }

/**
 * Validates the immutable identity and capability pairing required for browser-session resolution.
 */
    public VerifiedEmbedSessionGrant {
        registrationId = requireText(registrationId, "registrationId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        workloadIssuer = requireText(workloadIssuer, "workloadIssuer");
        workloadSubject = requireText(workloadSubject, "workloadSubject");
        tenantId = requireText(tenantId, "tenantId");
        parentOrigin = requireText(parentOrigin, "parentOrigin");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (!capabilities.contains(EmbedCapability.GRAPH_READ)) {
            throw new IllegalArgumentException("GRAPH_READ capability is required");
        }
        graphGrant = Objects.requireNonNull(graphGrant, "graphGrant");
        themeOverride = Objects.requireNonNull(themeOverride, "themeOverride");
        if (!tenantId.equals(graphGrant.tenantId())) {
            throw new IllegalArgumentException("session and graph grant tenants must match");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
