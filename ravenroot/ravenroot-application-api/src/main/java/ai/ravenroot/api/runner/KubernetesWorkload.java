package ai.ravenroot.api.runner;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted, bounded physical evidence, separate from Agent output and graph placement policy.
 * Concrete API endpoints and credentials are deliberately absent.
 * @param protocolVersion physical identity protocol
 * @param cluster logical operator-owned cluster name
 * @param namespace operator-owned workload namespace
 * @param podName physical Pod name, or null before materialization
 * @param podUid authoritative Pod UID, or null before materialization
 * @param claimName Workspace PVC name
 * @param claimUid authoritative PVC UID
 * @param volumeName bound volume name
 * @param generation Workspace ownership generation
 * @param phase bounded observed Pod phase
 * @param requestedBytes requested writable storage ceiling
 * @param enforcedBytes positively attested filesystem hard ceiling
 * @param attestationDigest digest of retained quota/security evidence
 * @param condition bounded readiness observation
 * @param reason closed scheduling or termination reason, never raw Kubernetes text
 * @param exitCode observed Agent container exit status, or null
 * @param modelTurns manager-dispatched model turns
 * @param toolCalls manager-permitted tool calls
 * @param modelTokens observed provider token consumption
 */
public record KubernetesWorkload(int protocolVersion, String cluster, String namespace, String podName,
                                 UUID podUid, String claimName, UUID claimUid, String volumeName,
                                 long generation, Phase phase, long requestedBytes, long enforcedBytes,
                                 String attestationDigest, Condition condition, Reason reason, Integer exitCode,
                                 int modelTurns, int toolCalls, long modelTokens) {
    /** Closed Kubernetes condition vocabulary. */
    public enum Condition { /** Ready container. */ READY, /** Pending scheduling. */ UNSCHEDULED,
        /** Not ready. */ UNHEALTHY, /** Termination in progress. */ TERMINATING, /** No current proof. */ UNKNOWN }
    /** Closed failure vocabulary; untrusted event messages are never exposed as technical labels. */
    public enum Reason { /** No observed failure. */ NONE, /** Quota exhausted. */ QUOTA,
        /** Image unavailable. */ IMAGE_PULL, /** Placement unavailable. */ PLACEMENT, /** Volume unavailable. */ VOLUME,
        /** Admission rejected. */ ADMISSION, /** Kubelet eviction. */ EVICTED, /** Memory boundary reached. */ OOM_KILLED,
        /** Container completed. */ COMPLETED, /** Container error. */ ERROR, /** Node unavailable. */ NODE_LOST,
        /** Unclassified or unavailable evidence. */ UNKNOWN }

    /**
     * Constructs identity evidence before detailed observations are available.
     * @param protocolVersion physical protocol
     * @param cluster logical cluster
     * @param namespace workload namespace
     * @param podName physical name
     * @param podUid physical UID
     * @param claimName claim name
     * @param claimUid claim UID
     * @param volumeName volume name
     * @param generation ownership generation
     * @param phase observed phase
     * @param requestedBytes requested storage ceiling
     * @param enforcedBytes proven storage ceiling, zero before attestation
     * @param attestationDigest attestation digest, null before attestation
     */
    public KubernetesWorkload(int protocolVersion, String cluster, String namespace, String podName, UUID podUid,
            String claimName, UUID claimUid, String volumeName, long generation, Phase phase, long requestedBytes,
            long enforcedBytes, String attestationDigest) {
        this(protocolVersion, cluster, namespace, podName, podUid, claimName, claimUid, volumeName, generation, phase,
                requestedBytes, enforcedBytes, attestationDigest, Condition.UNKNOWN, Reason.UNKNOWN, null, 0, 0, 0);
    }
    /** Closed workload phase vocabulary; observations never grant logical execution authority. */
    public enum Phase {
        /** Creation intent is durable but not acknowledged. */ CREATING,
        /** Scheduling, image or volume prerequisites are pending. */ PENDING,
        /** The exact Pod UID is running. */ RUNNING,
        /** Termination requested but quiescence not proven. */ TERMINATING,
        /** The exact Pod UID has a successful terminal container state. */ SUCCEEDED,
        /** The exact Pod UID has a failed terminal container state. */ FAILED,
        /** API uncertainty, identity mismatch or missing evidence. */ UNKNOWN,
        /** The previously recorded UID is authoritatively absent. */ ABSENT
    }

    /** Rejects incomplete physical identities, unknown versions and unbounded technical strings. */
    public KubernetesWorkload {
        if (protocolVersion != 1 || generation < 1 || requestedBytes < 1 || enforcedBytes < 0
                || enforcedBytes > requestedBytes) throw new IllegalArgumentException("invalid Kubernetes authority evidence");
        cluster = RunnerPolicy.identifier(cluster);
        namespace = name(namespace); claimName = name(claimName); if (volumeName != null) volumeName = name(volumeName);
        if ((podName == null) != (podUid == null)) throw new IllegalArgumentException("incomplete Kubernetes Pod identity");
        if (podName != null) podName = name(podName);
        Objects.requireNonNull(claimUid); Objects.requireNonNull(phase); Objects.requireNonNull(condition); Objects.requireNonNull(reason);
        if (modelTurns < 0 || toolCalls < 0 || modelTokens < 0 || exitCode != null && (exitCode < 0 || exitCode > 255))
            throw new IllegalArgumentException("invalid bounded Kubernetes execution observation");
        if ((enforcedBytes == 0) != (attestationDigest == null)
                || attestationDigest != null && !attestationDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Kubernetes attestation digest required");
    }

    private static String name(String value) {
        if (value == null || !value.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))
            throw new IllegalArgumentException("bounded Kubernetes resource name required");
        return value;
    }
}
