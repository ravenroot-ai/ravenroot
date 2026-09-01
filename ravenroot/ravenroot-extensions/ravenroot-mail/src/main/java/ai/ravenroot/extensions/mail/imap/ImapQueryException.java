package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelopeSource;

/**
 * A typed IMAP query failure, and the first connector failure that also reports itself through the
 * product's common error form.
 *
 * <h2>The mapping, code by code</h2>
 * <p>{@link #errorCode()} is an exhaustive {@code switch} with no {@code default}: a new {@link Code}
 * stops the build until somebody declares what represents it, which is the point — an implicit
 * mapping is a loss of information nobody notices.</p>
 *
 * <pre>
 *   INVALID_INPUT          -&gt; INVALID_REQUEST        400   faithful
 *   SATURATED              -&gt; REQUEST_LIMIT_EXCEEDED 429   faithful
 *   TIMEOUT                -&gt; REQUEST_INTERRUPTED    503  \ collapse A
 *   TRANSPORT_FAILURE      -&gt; REQUEST_INTERRUPTED    503  /
 *   PROFILE_UNAVAILABLE    -&gt; INTERNAL_ERROR         500  \
 *   CREDENTIAL_UNAVAILABLE -&gt; INTERNAL_ERROR         500   &gt; collapse B
 *   RESOURCE_LIMIT         -&gt; INTERNAL_ERROR         500  /
 * </pre>
 *
 * <p><b>Collapse A costs almost nothing, and that is measured rather than assumed.</b> These two are
 * not alternative outcomes of one check but the same event observed on either side of
 * the query watchdog's window: a refused negotiation that arrives late is reclassified from
 * {@code TRANSPORT_FAILURE} to {@code TIMEOUT}, which is why asserting one of them made a test flake
 * under full-reactor load without ever indicating a defect. A consumer that treated them as different
 * conditions was reading load, not cause. {@code REQUEST_INTERRUPTED} is the honest joint statement —
 * the operation did not complete, and retrying is reasonable — and the server already uses it for a
 * downstream system being unavailable ({@code AssistantReason.PROVIDER_UNAVAILABLE}).</p>
 *
 * <p><b>Collapse B is the expensive one and it is not repaired here.</b> It merges two faults that are
 * <em>permanent until an operator acts</em> — a profile nobody configured, a credential the deployment
 * cannot resolve — with one that is <em>a property of the message being read, not of the deployment</em>:
 * an IMAP response that exceeded a MIME depth, part count, address count or byte budget. Repeating the
 * <em>same</em> query re-fails deterministically, but a narrower one, or the same folder once that
 * message is gone, succeeds — where the other two refuse everything until somebody edits an
 * environment variable. A consumer reading the envelope cannot tell "stop retrying, somebody must fix
 * the deployment" from "narrow the query and try again", and a client that retries on 500 loops for
 * ever on the first two. That distinction exists in the connector and has no member of the common
 * vocabulary to land in.</p>
 *
 * <p><b>The operator diagnostic channel does not make up for it, and the earlier claim that it did was
 * wrong.</b> The <em>profile</em> resolvers have a named-constraint logger that names the
 * tenant, the profile and which constraint refused it — never the value. Measured against the three
 * codes collapse B merges, that channel covers a fraction of one of them:</p>
 * <ul>
 *   <li>a <em>malformed</em> profile is covered;</li>
 *   <li>an <em>absent</em> profile is not, and must not be: {@code EnvironmentImapProfileResolver}
 *       returns {@code Optional.empty()} with no log when the variable is unset, and
 *       {@code absentProfileLogsNothingAndAWellFormedOneResolvesSilently} pins that silence —
 *       the presence of a line is what makes it mean "somebody wrote this and got it wrong". So this
 *       half can never be covered by that channel without reversing that deliberate property;</li>
 *   <li>{@code CREDENTIAL_UNAVAILABLE} has no channel at all: the module declares exactly two
 *       {@code System.getLogger} calls and both are in profile resolvers, while
 *       {@code EnvironmentMailCredentialResolver} returns {@code Optional.empty()} silently on all
 *       three of its refusal paths;</li>
 *   <li>{@code RESOURCE_LIMIT} has none either.</li>
 * </ul>
 *
 * <p>So the two channels are still not competing versions of the same answer — what a consumer
 * receives stays coarse on purpose — but for two of collapse B's three codes there is <em>no</em>
 * second channel, and the loss described above is the whole of what an operator gets too. The coarse
 * envelope is therefore documented here as an explicit limitation.</p>
 *
 * <h2>What no mapping could express</h2>
 * <p>The first distinction to preserve — "the remote system refused us" against "we could not reach
 * it" — is <b>not representable</b>, at either end. {@link Code} does not draw it: an IMAP
 * authentication refusal is caught with every other protocol failure and reported as
 * {@code TRANSPORT_FAILURE}. Neither does {@link ErrorCode}, whose 4xx members all attribute the
 * failure to the caller and whose 5xx members all describe this server; it has no member describing a
 * <em>third</em> system. The two that look close are traps: {@code AUTHENTICATION_REQUIRED} and
 * {@code ACCESS_DENIED} would tell a caller to present credentials, or that it is denied, when the
 * credential at issue is the deployment's toward the mail host and nothing the caller holds could
 * change the outcome.</p>
 */
public final class ImapQueryException extends RuntimeException implements ErrorEnvelopeSource {

    public enum Code { INVALID_INPUT, PROFILE_UNAVAILABLE, CREDENTIAL_UNAVAILABLE, SATURATED, RESOURCE_LIMIT, TIMEOUT, TRANSPORT_FAILURE }

    private final Code code;

    public ImapQueryException(Code c, String m) { super(m); code = c; }

    public Code code() { return code; }

    /**
     * The common code representing this failure. See the table on the class.
     *
     * <p>Exhaustive on purpose, with no {@code default} arm: the compiler is what keeps a code added
     * later from silently inheriting somebody else's meaning.</p>
     */
    @Override public ErrorCode errorCode() {
        return switch (code) {
            case INVALID_INPUT -> ErrorCode.INVALID_REQUEST;
            case SATURATED -> ErrorCode.REQUEST_LIMIT_EXCEEDED;
            case TIMEOUT, TRANSPORT_FAILURE -> ErrorCode.REQUEST_INTERRUPTED;
            case PROFILE_UNAVAILABLE, CREDENTIAL_UNAVAILABLE, RESOURCE_LIMIT -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
