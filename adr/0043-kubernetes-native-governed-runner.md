# ADR 0043: Kubernetes-native governed runner

- Date: 2026-09-18
- Status: Accepted
- Extends: ADR 0042; issue #446

## Context

Deploying a Docker-socket supervisor with Helm does not make its Agent containers Kubernetes
workloads. Native scheduling and isolation require a different driver while preserving the accepted
Workspace, named Agent, quota, fencing and unknown-effect contracts.

## Decision

`KubernetesPodRunner` implements the existing `RunnerDriver` beside `LocalContainerRunner`.
An immutable approved Workspace profile selects `KUBERNETES` or `DOCKER`. GraphML selects only the
logical profile and named/versioned Agents. No live Workspace changes driver, pool, cluster,
namespace, image, storage, security profile or ownership generation through graph input.

Like GitLab Runner's Kubernetes executor, a persistent **runner manager** receives work and creates
native workloads. Unlike a CI job executor, Ravenroot owns graph continuations, named Agent sessions,
independent Workspace lifetimes, effect uncertainty and report-only recovery. Kubernetes is the
physical scheduling/policy authority, not the authority to retry a graph effect.

### Pods, volumes and protocol

Use bare Pods with `restartPolicy: Never`, not Jobs: automatic retries cannot establish whether an
Agent effect is safe to repeat. `PROCESS_INSTANCE/PER_WORKSPACE` keeps a Workspace Pod UID;
`PER_INVOCATION` keeps the PVC but creates new Invocation Pod UIDs. `EPHEMERAL` creates both per
invocation. `NAMED` reuses a claim only after old Pod absence, sealed results and a new durable
ownership generation. No ownerReference delegates evidence retention to garbage collection.

The Agent image is digest-pinned and starts an immutable idle process. The authenticated API TLS
connection upgrades to a bounded `pods/exec` stream for exactly the attester or Agent entry point.
An admission policy rejects arbitrary exec, attach, port-forward, TTY and extra containers. Exec
is chosen instead of attach so assignment input and tool/model traffic never become Pod logs.
The Agent checks its downward-API UID against the envelope. Model credentials and provider access
remain in the manager-owned gateway; the Agent has neither credentials nor direct network access.

The manager persists complete assignment intent before create, then Pod/PVC UIDs, enforcement proof,
budget usage and a sealed result using fsync and atomic replacement. A create acknowledgement lost
before UID persistence authorizes identity recovery and termination only, never execution. Deletes
carry UID and resourceVersion preconditions; authoritative UID absence is required before cleanup.

### Ownership and isolation

Each pool uses a dedicated workload namespace, a dedicated manager ServiceAccount and a separate
tokenless Agent ServiceAccount. The manager is not cluster-admin and cannot change nodes,
namespaces, RBAC, admission policies or Secrets. It may manage Pods/PVCs and the constrained exec
subresource only inside that namespace. Separate tenant trust boundaries require separate pool
namespaces and identities; standard RBAC cannot express arbitrary label-scoped ownership.

There is no shared Kubernetes watcher or elected global leader. Bounded point observations are owned
by the pinned worker incarnation. A file lock excludes concurrent managers on the same durable
state; control-plane availability leases and job fences exclude a replacement while the previous
incarnation is live. Different manager identities cannot claim the same accepted assignment.
Loss of the state volume is manual recovery, not authorization to infer ownership from a name.

### Positive attestation and support boundary

Attestation format 1 is a closed JSON object whose digest is retained with the authority snapshot.
It proves non-root identity, dropped capabilities, no-new-privileges, read-only root, no mounted
ServiceAccount token, Landlock ABI 3+, bounded cgroup CPU/memory/PIDs and fixed filesystem capacity.
Within-limit writes and allocations succeed; exceeding storage and PID ceilings is refused
by the kernel. Native acceptance additionally verifies memory OOM on a sacrificial owned Pod;
`memory.oom.group` can kill the entire container, so that destructive test is not a normal preflight.
Fractional CPU profiles positively observe throttling. A secretless control Pod is
reachable only during the immutable bootstrap phase; the manager removes that label once, and the
same endpoint must then be unreachable. The Agent independently repeats the isolation check before
accepting authority. Admission prevents restoring the bootstrap label.

The initial verified backend is a pre-provisioned, fixed-size ext4 local PV, RWO, no online expansion,
with `Retain` reclamation. The Agent mounts a PVC, never hostPath. Linux nodes/containerd, cgroup v2,
Landlock and enforcing Calico policy are required. Kubernetes 1.35.4 is the native CI target.
No CSI driver, snapshot API, generic dynamic provisioner, hostPath provisioner or `emptyDir` is
implicitly approved. Checkpoint/restore and cross-driver migration are explicitly unsupported for
this backend. A future backend needs an equally positive proof and atomic checkpoint contract.

One retained preflight claim per manager/runtime is charged to namespace quota. Released PVCs do
not imply reclaimed Retain PV storage: the finite operator-provisioned pool remains reserved until
independent quiescence, evidence retention and safe reprovisioning. The manager never clears PV
claimRef or global-prunes storage. Artifacts/logs remain on the bounded shared control-plane store.

## Consequences

Workspace envelope v4, assignment/result v3 and profile v2 retain native physical evidence. New
readers accept older Docker envelopes; older readers reject new magic rather than dropping UID or
generation. Upgrade the complete control-plane/worker fleet before publishing native profiles.
Rollback requires an untouched pre-upgrade database/state backup and proven quiescence; an old
binary must not read or resume native work. The external runner transport version remains 1.

Operator placement supports node selectors, required node affinity, equal tolerations,
RuntimeClass, PriorityClass and hard topology spread; graph authors cannot set them. Capacity remains
operator-owned: worker slots, model concurrency, catalog fleet ceilings, namespace quota and the
finite storage pool are separate bounds. AUTOSCALE exposes demand; no graph can create nodes.

The narrow initial support matrix is intentional. macOS UI/demo and conversational Agents do not
need this executor. A local Linux Kubernetes VM is suitable only after the same preflight; Docker
Desktop by itself proves neither writable quota nor network enforcement.
