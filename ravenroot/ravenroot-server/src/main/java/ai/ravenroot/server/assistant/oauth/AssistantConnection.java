package ai.ravenroot.server.assistant.oauth;

import java.net.URI;
import java.time.Duration;

/**
 * One author's connection to their model provider, as the HTTP route sees it.
 *
 * <h2>Why a port and not the device-flow class directly</h2>
 * <p>{@link AssistantDeviceAuthorization} speaks RFC 8628: it asks for a grant and it redeems one,
 * and it deliberately holds no state between the two — its own comment says the loop belongs to the
 * caller because how long to wait is a decision about a user's session. That leaves a gap between
 * the protocol and a route: something has to remember which grant belongs to which author, and to
 * put the redeemed token where the turn path will look for it. This interface is that gap, named.</p>
 *
 * <p>Separating it also keeps <b>the device code out of the route entirely</b>. The route asks this
 * port to begin a connection and gets back only what the author must be shown; the half that
 * redeems the grant never leaves the implementation, so there is no handler variable holding it and
 * nothing for a serializer to reach. That is a structural version of the redaction
 * {@code DeviceGrant#toString} already applies.</p>
 *
 * <h2>What no implementation of this can supply, and why that is on purpose</h2>
 * <p><b>The provider's endpoints.</b> They are constructor arguments of
 * {@link AssistantDeviceAuthorization}, they have no defaults because no
 * test in this repository has ever made a real request against them, so a default would be an
 * unverified constant that an operator reasonably assumes was tested. A deployment gets a working
 * connection when an operator supplies them; until then the composition
 * root wires no connection at all, {@code AssistantService} reports the operator-actionable reason
 * rather than offering the author a control that cannot work, and the route refuses.</p>
 */
public interface AssistantConnection {

    /**
     * What the author must be shown to complete a connection, and how long to wait between checks.
     *
     * <p>Note what is <em>not</em> here: the device code. See the type comment.</p>
     *
     * @param verificationUriComplete the same page with the code already filled in, when the
     *                                provider offers one. Null otherwise, and never synthesized by
     *                                appending a query string — a URL this build assembled is a URL
     *                                the provider never promised to honour.
     */
    record Prompt(String userCode, URI verificationUri, URI verificationUriComplete,
                  Duration pollInterval, Duration expiresIn) {

        /** Redacted for the same reason {@code DeviceGrant} is: the leak that happens is accidental. */
        @Override
        public String toString() {
            return "Prompt[verificationUri=" + verificationUri + ", userCode=redacted, expiresIn="
                    + expiresIn + "]";
        }
    }

    /** Where an author's connection attempt has got to. */
    sealed interface Progress {

        /** The author finished, and the token is where the turn path will find it. */
        record Linked() implements Progress { }

        /**
         * Not finished. Carries the mechanism's own named reason and the interval to use next.
         *
         * @param failure never {@code null}: an unnamed wait is indistinguishable from a lost
         *                connection, and the panel has a different sentence for each of the five
         */
        record Waiting(AssistantDeviceAuthorization.Failure failure,
                       Duration retryAfter) implements Progress { }

        /**
         * There is no attempt in flight for this author.
         *
         * <p>A distinct answer rather than a {@code Waiting} with a vague reason, because the two
         * need different things from the panel: waiting means keep watching, this means there is
         * nothing to watch and starting again is the only way forward.</p>
         */
        record None() implements Progress { }
    }

    /**
     * Begins a connection for this author, replacing any attempt already in flight for them.
     *
     * <p><b>Replacing, not adding.</b> Two live grants for one author means two codes, one of which
     * is dead, and no way for the author to tell which — and the panel would be polling for one
     * while the author typed the other.</p>
     *
     * @throws AssistantDeviceAuthorization.DeviceAuthorizationException when the provider cannot be
     *         asked: refused by the operator's egress policy, unreachable, or answering something
     *         this build cannot read. Never carries a provider-authored message.
     */
    Prompt begin(String subject);

    /**
     * Asks once whether this author has finished. Once, never in a loop: a method that blocked a
     * request thread until the author got round to it would be making a decision about someone
     * else's session, which is the same argument {@code AssistantDeviceAuthorization#redeem} makes.
     */
    Progress poll(String subject);

    /**
     * Forgets any attempt in flight for this author.
     *
     * <p>What the panel's stop control does, and what a closed panel does. It does not revoke a
     * token already obtained — that is a sign-out, a different act with a different consequence,
     * and conflating them would let "stop waiting" silently disconnect a working session.</p>
     */
    void abandon(String subject);
}
