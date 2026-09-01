package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedRevokeOutcome;
import ai.ravenroot.api.security.RequestContext;

/**
 * A registration authority whose only interesting behaviour is which revision is current.
 *
 * <p>These suites are about what the ticket, session and proof authorities do when currency changes
 * underneath a credential already in flight; the store semantics that decide currency are asserted
 * against the real adapters in {@code EmbedRegistrationAuthorityTest} and
 * {@code SqliteEmbedRegistrationStoreTest}. Using a double here keeps a failure in one from being
 * reported as a failure in the other.</p>
 *
 * <p>{@link #withdraw()} is the revocation seam. It is a method rather than a flag because the tests
 * that matter most call it <em>during</em> an operation — from inside the audit callback
 * {@code acknowledge} invokes — to reproduce a revocation landing in the window between the audit
 * write and the commit.</p>
 */
final class MutableEmbedRegistrations implements EmbedRegistrationAuthority {

    private EmbedRegistrationAggregate current;

    MutableEmbedRegistrations(EmbedRegistrationAggregate current) {
        this.current = current;
    }

    void current(EmbedRegistrationAggregate replacement) {
        current = replacement;
    }

    void withdraw() {
        current = null;
    }

    @Override
    public EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
        throw new UnsupportedOperationException("this double is read-only");
    }

    @Override
    public EmbedRevokeOutcome revoke(EmbedRevokeCommand command) {
        throw new UnsupportedOperationException("this double is read-only");
    }

    @Override
    public EmbedRegistrationResolution resolveCurrent(RequestContext workload, String registrationId) {
        return current == null || !current.registrationId().equals(registrationId)
                ? EmbedRegistrationResolution.Unavailable.INSTANCE
                : new EmbedRegistrationResolution.Available(current);
    }

    @Override
    public boolean isCurrent(EmbedRegistrationAggregate captured) {
        return captured != null && captured.equals(current);
    }
}
