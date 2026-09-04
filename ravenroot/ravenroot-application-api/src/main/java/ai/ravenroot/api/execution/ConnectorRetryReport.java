package ai.ravenroot.api.execution;

/**
 * Implemented by a failure that carries how many times a connector retried <em>inside</em> one
 * orchestration attempt.
 *
 * <h2>Why this exists as its own contract</h2>
 * <p>A connector that retries internally — a client with its own backoff, a broker producer's
 * {@code retries} setting, a rate limiter that re-sends after a 429 — performs work that looks
 * exactly like an orchestration retry from the outside and is not one: it produces no new durable
 * attempt, no new attempt id, and therefore no new effect identity. Without a declared channel the
 * two are indistinguishable in metrics, and a deployment measuring its own reliability would credit
 * the orchestrator for retries it never made, or conclude a node is failing rarely because most of
 * its failures were absorbed one layer down.</p>
 *
 * <p>The count is reported, never inferred. Nothing in the runtime can observe a connector's internal
 * loop, so a connector that does not implement this reports nothing and its events carry
 * {@link #NOT_REPORTED} — which is distinct from reporting {@code 1}, "I tried once and did not
 * retry". Collapsing those two would turn silence into a positive claim.</p>
 *
 * <p>The success path has its own carrier: {@link NodeResult#connectorAttempts()}. This interface is
 * the failure path, because a failure is a {@link Throwable} and cannot travel in a result.</p>
 */
public interface ConnectorRetryReport {

    /**
     * The value meaning "this connector said nothing about its internal attempts", distinct from a
     * connector that reported making exactly one.
     */
    int NOT_REPORTED = 0;

    /**
     * How many times the connector attempted the underlying operation within this one orchestration
     * attempt.
     *
     * <p>Counts attempts, not retries: a connector that succeeded first time reports {@code 1}, and
     * one that retried twice before failing reports {@code 3}. Counting retries instead would make
     * {@code 0} mean both "no internal retry" and "nothing reported".</p>
     *
     * @return the connector-level attempt count, at least {@code 1}, or {@link #NOT_REPORTED}
     */
    int connectorAttempts();
}
