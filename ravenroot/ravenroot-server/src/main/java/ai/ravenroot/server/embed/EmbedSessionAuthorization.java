package ai.ravenroot.server.embed;

import ai.ravenroot.api.application.DeploymentViewerView;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.EmbedViewerSource;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Common immutable authority captured by every browser credential. */
public sealed interface EmbedSessionAuthorization
        permits EmbedSessionAuthorization.Registered, EmbedSessionAuthorization.Dynamic {

    String authorityId();
    long revision();
    String workloadIssuer();
    String workloadSubject();
    String tenantId();
    String parentOrigin();
    Set<EmbedCapability> capabilities();
    Optional<EmbedTheme> themeOverride();
    EmbedViewerSource source();
    Optional<DeploymentViewerView> pinnedDeployment();
    Optional<Instant> expiresAt();

    /** Existing durable operator registration, unchanged and still governed by its revision. */
    record Registered(EmbedRegistrationAggregate registration) implements EmbedSessionAuthorization {
        public Registered { Objects.requireNonNull(registration, "registration"); }
        @Override public String authorityId() { return registration.registrationId(); }
        @Override public long revision() { return registration.revision(); }
        @Override public String workloadIssuer() { return registration.sessionGrant().workloadIssuer(); }
        @Override public String workloadSubject() { return registration.sessionGrant().workloadSubject(); }
        @Override public String tenantId() { return registration.tenantId(); }
        @Override public String parentOrigin() { return registration.sessionGrant().parentOrigin(); }
        @Override public Set<EmbedCapability> capabilities() { return registration.sessionGrant().capabilities(); }
        @Override public Optional<EmbedTheme> themeOverride() { return registration.sessionGrant().themeOverride(); }
        @Override public EmbedViewerSource source() { return registration.source(); }
        @Override public Optional<DeploymentViewerView> pinnedDeployment() { return Optional.empty(); }
        @Override public Optional<Instant> expiresAt() { return Optional.empty(); }
    }

    /** Short-lived server-issued grant for one exact READY deployment incarnation. */
    record Dynamic(String authorityId, long revision, String workloadIssuer, String workloadSubject,
                   String tenantId, String parentOrigin, Set<EmbedCapability> capabilities,
                   Optional<EmbedTheme> themeOverride, EmbedViewerSource.DeploymentV2 source,
                   DeploymentViewerView deployment, String policyRevision,
                   Instant issuedAt, Instant expiresAtInstant) implements EmbedSessionAuthorization {
        public Dynamic {
            authorityId = text(authorityId, "authorityId");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
            workloadIssuer = text(workloadIssuer, "workloadIssuer");
            workloadSubject = text(workloadSubject, "workloadSubject");
            tenantId = text(tenantId, "tenantId");
            parentOrigin = text(parentOrigin, "parentOrigin");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            themeOverride = Objects.requireNonNull(themeOverride, "themeOverride");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(deployment, "deployment");
            policyRevision = text(policyRevision, "policyRevision");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAtInstant, "expiresAtInstant");
            if (!expiresAtInstant.isAfter(issuedAt)) throw new IllegalArgumentException("grant must expire after issue");
            if (!source.deploymentId().equals(deployment.source().deploymentId())) {
                throw new IllegalArgumentException("grant source and deployment must match");
            }
            Set<EmbedCapability> expected = Set.of(EmbedCapability.GRAPH_READ,
                    EmbedCapability.DEPLOYMENT_OBSERVE, EmbedCapability.DEPLOYMENT_RUN_READ);
            if (!capabilities.equals(expected)) {
                throw new IllegalArgumentException("dynamic grants are fixed read-only grants");
            }
        }

        @Override public Optional<DeploymentViewerView> pinnedDeployment() { return Optional.of(deployment); }
        @Override public Optional<Instant> expiresAt() { return Optional.of(expiresAtInstant); }

        private static String text(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
