package ai.ravenroot.server.embed;

import ai.ravenroot.api.application.DeploymentViewerView;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedViewerSource;
import ai.ravenroot.api.security.RequestContext;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, expiring, explicitly revocable authority for dynamically selected deployments. */
public final class DynamicEmbedGrantAuthority {
    private final ConcurrentHashMap<String, EmbedSessionAuthorization.Dynamic> grants = new ConcurrentHashMap<>();
    private final DynamicEmbedAuthorizationPolicy policy;
    private final EmbedViewerOrigin viewerOrigin;
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();

    public DynamicEmbedGrantAuthority(DynamicEmbedAuthorizationPolicy policy, EmbedViewerOrigin viewerOrigin,
                                      Clock clock, Duration ttl, int capacity) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.viewerOrigin = java.util.Objects.requireNonNull(viewerOrigin, "viewerOrigin");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.ttl = EmbedLaunchTicketAuthority.boundedTtl(ttl, "dynamic grant");
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("invalid dynamic grant capacity");
        this.capacity = capacity;
    }

    public synchronized Issued issue(RequestContext context, String parentOrigin, DeploymentViewerView deployment) {
        if (!policy.enabled()) throw new IllegalStateException("dynamic embed policy is disabled");
        cleanup();
        if (grants.size() >= capacity) throw new CapacityExceededException();
        String canonicalOrigin = policy.requireAllowed(parentOrigin, viewerOrigin);
        Instant issuedAt = clock.instant();
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String digest = EmbedLaunchTicketAuthority.digest(id);
            var grant = new EmbedSessionAuthorization.Dynamic(digest, 1, context.issuer(), context.subject(),
                    context.tenantId(), canonicalOrigin,
                    Set.of(EmbedCapability.GRAPH_READ, EmbedCapability.DEPLOYMENT_OBSERVE,
                            EmbedCapability.DEPLOYMENT_RUN_READ), Optional.empty(),
                    new EmbedViewerSource.DeploymentV2(deployment.source().deploymentId(), false),
                    deployment, policy.revision(), issuedAt, issuedAt.plus(ttl));
            if (grants.putIfAbsent(digest, grant) == null) {
                return new Issued(id, grant);
            }
        }
        throw new IllegalStateException("secure dynamic grant source repeatedly collided");
    }

    public EmbedSessionAuthorization.Dynamic resolve(RequestContext context, String grantId) {
        EmbedSessionAuthorization.Dynamic grant = find(grantId);
        if (grant == null || !grant.tenantId().equals(context.tenantId())
                || !grant.workloadIssuer().equals(context.issuer())
                || !grant.workloadSubject().equals(context.subject())) return null;
        return grant;
    }

    public boolean isCurrent(EmbedSessionAuthorization authorization) {
        if (!(authorization instanceof EmbedSessionAuthorization.Dynamic dynamic)) return false;
        EmbedSessionAuthorization.Dynamic current = currentByDigest(dynamic.authorityId());
        return dynamic.equals(current) && policy.enabled() && policy.revision().equals(dynamic.policyRevision());
    }

    public synchronized boolean revoke(RequestContext context, String grantId) {
        EmbedSessionAuthorization.Dynamic grant = resolve(context, grantId);
        if (grant == null) return false;
        return grants.remove(EmbedLaunchTicketAuthority.digest(grantId), grant);
    }

    private EmbedSessionAuthorization.Dynamic find(String id) {
        if (id == null || id.isBlank()) return null;
        final String digest;
        try { digest = EmbedLaunchTicketAuthority.digest(id); }
        catch (IllegalArgumentException invalid) { return null; }
        return currentByDigest(digest);
    }

    private EmbedSessionAuthorization.Dynamic currentByDigest(String digest) {
        EmbedSessionAuthorization.Dynamic grant = grants.get(digest);
        if (grant != null && !clock.instant().isBefore(grant.expiresAtInstant())) {
            grants.remove(digest, grant);
            return null;
        }
        return grant;
    }

    private void cleanup() {
        Instant now = clock.instant();
        grants.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAtInstant()));
    }

    public record Issued(String value, EmbedSessionAuthorization.Dynamic authorization) { }
    public static final class CapacityExceededException extends RuntimeException {
        private CapacityExceededException() { super("dynamic embed grant capacity exhausted"); }
    }
}
