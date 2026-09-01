/**
 * The engine-neutral contract for long-lived graph deployments (ADR 0021).
 *
 * <h2>Phase A is one pod, and says so</h2>
 * <p>These types describe a deployment that starts, reaches readiness, accepts unsolicited inbound
 * events and stops deterministically <em>on a single pod</em>. Nothing here is cluster-aware: there
 * is no clustering, remoting or distributed-memory dependency in Phase A, and none of these types
 * implies one.
 *
 * <h2>The three things this package deliberately does not decide</h2>
 * <ol>
 *   <li><b>Durability</b> — checkpointing, deduplication, at-least-once and the acknowledgement
 *       boundary are outside this phase. Durable traversal acceptance needs the shared store and lands
 *       through separate distributed-ownership and replica-safety guarantees. {@link
 *       ai.ravenroot.api.deployment.TrustedIngress} states the
 *       consequence where an adapter author will meet it.</li>
 *   <li><b>Ingress topology</b> — whether a source targets the start node, a dedicated inbound node
 *       or an arbitrary node is unresolved, so
 *       {@link ai.ravenroot.api.deployment.IngressTarget} makes it a parameter and privileges
 *       neither.</li>
 *   <li><b>Deployment identity semantics</b> — minting, cross-pod uniqueness and survival across
 *       restart belong to the durable registry, which is not yet designed.
 *       {@link ai.ravenroot.api.deployment.DeploymentId} therefore carries a value and claims
 *       nothing about it.</li>
 * </ol>
 *
 * <p>Each of the three is a parameter or an opaque value rather than an assumption, so the decision
 * when it lands is a rule added over these types rather than a redesign of them.
 *
 * <h2>Where the boundaries sit</h2>
 * <p>Identity is mandatory at activation and at every ingress (SEC-07); there is no overload that
 * omits it. The per-pod activation cap is an operator setting owned by the server configuration
 * surface — core receives a plain integer and never reads the environment, because where platform
 * configuration lives remains deliberately unspecified, and this contract must not answer it
 * in passing. Refusal at the cap is
 * {@link ai.ravenroot.api.deployment.DeploymentAdmissionException}, fail-closed.
 */
package ai.ravenroot.api.deployment;
