package ai.ravenroot.core.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * The full identity one worker instance is bound to (ADR 0024 §1).
 *
 * <p>ADR 0024 lists these eight and says an actor reference is never a substitute for them:
 *
 * <pre>{@code
 * tenantId, deploymentId, graphVersion, processInstanceId, traversalId,
 * nodeId, invocationId, attemptId
 * }</pre>
 *
 * <h2>Why this exists as a value rather than as an actor name</h2>
 * <p>Because the actor name cannot carry it. Actor names are sanitised, length-constrained and
 * adapter-specific; encoding identity in one would make the identity depend on what a name is allowed
 * to contain, and would make two identities equal because their names collided after sanitisation.
 * {@link #actorName()} is derived <em>from</em> this record and is deliberately lossy — it exists so a
 * human reading an actor path can tell which node and which invocation they are looking at, and for
 * nothing else. Nothing reads identity back out of it.
 *
 * <h2>The retry rule is structural here (ADR 0024 §1)</h2>
 * <p>{@code invocationId} identifies one logical node invocation; {@code attemptId} identifies one
 * try at it. A retry is a new {@code attemptId} under the <em>same</em> {@code invocationId}, so it is
 * a new instance of this record that agrees on the invocation, and
 * {@link WorkerInstanceRegistry} keys on the invocation. Only a documented transition that mints a
 * fresh {@code invocationId} creates a second logical invocation.
 *
 * <p>Ravenroot has no retry today — {@code GraphRunner} mints exactly one invocation and one attempt
 * per dispatch — and this type deliberately does not add one. It makes the rule expressible so any
 * future retry implementation inherits it instead of choosing it again.
 *
 * @param tenantId          the owning tenant, from the ingress security context; never {@code null}
 * @param deploymentId      the owning deployment, or {@code null} for a one-shot or playground
 *                          submission, which never opened a deployment domain
 * @param graphVersion      the definition this traversal is running
 * @param processInstanceId PERS-01 process identity
 * @param traversalId       the traversal that reached this node
 * @param nodeId            the logical node, which is a definition and not a runtime entity
 * @param invocationId      this logical invocation of that node
 * @param attemptId         this try at that invocation
 */
record WorkerInstanceIdentity(String tenantId, String deploymentId, String graphVersion,
                              UUID processInstanceId, UUID traversalId, String nodeId,
                              UUID invocationId, UUID attemptId) {

    WorkerInstanceIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
    }

    /**
     * The logical name handed to the engine when this instance's actor is created.
     *
     * <p>Node and invocation only, and that is a decision rather than an omission. The adapter appends
     * its own uniqueness suffix and sanitises whatever it receives, so a longer name buys nothing an
     * operator can use and costs allocation on the dispatch path, which ADR 0024 already flags as the
     * cost this design moves onto the hot path. Tenant and deployment are not omitted from
     * observability by this choice: they are on every event and span already, and the invocation
     * identifier here joins the actor path to all of them.
     */
    String actorName() {
        return nodeId + "-" + invocationId;
    }
}
