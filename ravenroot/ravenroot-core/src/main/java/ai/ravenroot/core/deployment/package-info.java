/**
 * The fenced deployment lifecycle authority: one coordinator that answers commands, one reconciler
 * that finishes the ones a crash interrupted (ADR 0038, ADR 0023).
 *
 * <h2>What lives here and what deliberately does not</h2>
 * <p>{@link ai.ravenroot.core.deployment.DeploymentCoordinator} is the only place a
 * {@link ai.ravenroot.api.deployment.lifecycle.LifecycleCommand} becomes a durable decision, and
 * {@link ai.ravenroot.core.deployment.DeploymentReconciler} is the only place a decision that was
 * recorded but never carried out is finished. Neither of them knows how a command arrived: there is
 * no HTTP route, CLI verb or OpenAPI schema in this package, and the external surface keeps exactly
 * the behaviour it had. The two are separated because they answer different questions — "may this
 * command be applied now" and "was an earlier command left half-applied" — and folding the second
 * into the first would make every submission pay for a sweep of everything else.</p>
 *
 * <h2>The ordering everything rests on</h2>
 * <p><b>Intent is durable before any effect runs.</b> That single rule is what makes a crash
 * recoverable at all: after it, the durable record either names a lifecycle move whose effect may or
 * may not have happened, or names nothing. There is no third state in which an effect happened that
 * nothing recorded. The reconciler exists to resolve the first case, and
 * {@link ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget}'s per-generation
 * idempotence is what lets it do so without duplicating the action.</p>
 *
 * <h2>Two layers of exclusion, never one</h2>
 * <p>In-process single-flight per {@code (tenant, deployment)} sits <em>above</em> the registry's
 * durable compare-and-set and never replaces it. The single-flight collapses concurrent callers
 * inside one JVM, which is what stops a burst of retries from becoming a burst of losing CAS
 * attempts; the compare-and-set and the lease fence are what make two JVMs safe, which no in-process
 * lock can do. Removing either one leaves a real defect: without the lock, correctness survives but
 * throughput collapses into retry storms; without the CAS, nothing is safe at all (ADR 0038 D8).</p>
 */
package ai.ravenroot.core.deployment;
