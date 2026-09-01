package ai.ravenroot.server.assistant.oauth;


import ai.ravenroot.server.assistant.InMemoryAssistantTokenStore;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The device grant, held between its two halves.
 *
 * <p>{@link AssistantDeviceAuthorization} asks for a grant and redeems one, and keeps nothing in
 * between. This class is what remembers: which grant belongs to which author, and where the
 * redeemed token goes. That is all it is — it speaks no protocol of its own, decides no destination,
 * and builds no HTTP client.</p>
 *
 * <h2>It chooses no endpoints, and cannot</h2>
 * <p>It receives an {@link AssistantDeviceAuthorization} already constructed. Whoever builds that
 * one supplies the provider's device-authorization and token endpoints, which have no defaults:
 * nothing here names a URL, so there
 * is no constant in this file for an operator to mistake for a verified one.</p>
 *
 * <h2>Why the token lifetime is a constructor argument</h2>
 * <p>Because the redemption does not carry one. {@code Redemption.Token} yields a credential and no
 * expiry, and the store needs a lifetime — so the alternative to asking for it here is inventing
 * one, which would mean this file deciding how long an author stays connected. It belongs to
 * whoever wires the deployment. The assistant token-persistence contract permits signing in "even
 * on-demand every time the app opens", so a short session-scoped lifetime is an explicit tradeoff.
 * Nothing here refreshes, rotates or
 * revokes, which is what keeps it inside the credential-lifecycle contract.</p>
 *
 * <h2>The pending register forgets, on purpose</h2>
 * <p>Grants live in memory and vanish with the process, exactly like {@link
 * InMemoryAssistantTokenStore}'s tokens and for the same reason: a pending grant that outlived a
 * restart would be a credential-bearing value written somewhere, which the per-author credential
 * contract keeps out of this product. An author whose grant is forgotten sees {@code None} and
 * starts again, which costs a few seconds.</p>
 */
public final class DeviceFlowAssistantConnection implements AssistantConnection {

    private final AssistantDeviceAuthorization authorization;
    private final InMemoryAssistantTokenStore tokens;
    private final Duration sessionLifetime;

    /**
     * One grant per author. {@code ConcurrentHashMap} because two requests from the same author can
     * land on different threads -- the panel's poll and its stop control, most obviously.
     */
    private final Map<String, AssistantDeviceAuthorization.DeviceGrant> pending =
            new ConcurrentHashMap<>();

    /**
     * @param sessionLifetime how long a redeemed token is honoured. Must be positive: a
     *                        non-positive lifetime would store nothing, and the author would appear
     *                        to connect and then be refused on every turn -- a state that reports
     *                        success and behaves like failure
     */
    public DeviceFlowAssistantConnection(AssistantDeviceAuthorization authorization,
                                         InMemoryAssistantTokenStore tokens,
                                         Duration sessionLifetime) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime, "sessionLifetime");
        if (sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalArgumentException("sessionLifetime must be positive");
        }
    }

    @Override
    public Prompt begin(String subject) {
        Objects.requireNonNull(subject, "subject");
        AssistantDeviceAuthorization.DeviceGrant grant = authorization.begin();
        // Replaces rather than accumulates -- see the port's comment on why two live grants for one
        // author is worse than none. Written only after `begin()` returned: a failed request must
        // not discard a grant the author may still be part-way through using.
        pending.put(subject, grant);
        return new Prompt(grant.userCode(), grant.verificationUri(), grant.verificationUriComplete(),
                grant.pollInterval(), grant.expiresIn());
    }

    @Override
    public Progress poll(String subject) {
        if (subject == null) {
            return new Progress.None();
        }
        AssistantDeviceAuthorization.DeviceGrant grant = pending.get(subject);
        if (grant == null) {
            return new Progress.None();
        }
        AssistantDeviceAuthorization.Redemption redemption = authorization.redeem(grant);
        if (redemption instanceof AssistantDeviceAuthorization.Redemption.Token token) {
            // Removed BEFORE the store is written, so there is no window in which a redeemed grant
            // is still pending -- redeeming twice is a request the provider answers with
            // `expired_token`, which would report a failure to an author who had just succeeded.
            pending.remove(subject, grant);
            tokens.signIn(subject, token.credential(), sessionLifetime);
            return new Progress.Linked();
        }
        var notYet = (AssistantDeviceAuthorization.Redemption.NotYet) redemption;
        if (notYet.failure() == AssistantDeviceAuthorization.Failure.ACCESS_DENIED
                || notYet.failure() == AssistantDeviceAuthorization.Failure.EXPIRED_TOKEN) {
            // Terminal. Dropping the grant here is what stops a later poll from asking a question
            // the provider has already answered -- and, for a denial, from re-presenting a decision
            // the author made on the provider's own page.
            pending.remove(subject, grant);
        } else if (notYet.failure() == AssistantDeviceAuthorization.Failure.SLOW_DOWN) {
            // RFC 8628 section 3.5: the lengthened interval is kept for the REST of this grant, not
            // just reported once. `redeem` computed it; storing it is what makes the next poll obey
            // it, and without this the panel would slow down for one round and then speed back up.
            pending.put(subject, withInterval(grant, notYet.retryAfter()));
        }
        return new Progress.Waiting(notYet.failure(), notYet.retryAfter());
    }

    @Override
    public void abandon(String subject) {
        if (subject != null) {
            pending.remove(subject);
        }
    }

    /** How many authors have an attempt in flight. Diagnostic only; never the grants themselves. */
    public int pendingCount() {
        return pending.size();
    }

    private static AssistantDeviceAuthorization.DeviceGrant withInterval(
            AssistantDeviceAuthorization.DeviceGrant grant, Duration interval) {
        return new AssistantDeviceAuthorization.DeviceGrant(grant.deviceCode(), grant.userCode(),
                grant.verificationUri(), grant.verificationUriComplete(), interval,
                grant.expiresIn());
    }
}
