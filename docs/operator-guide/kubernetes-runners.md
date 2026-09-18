# Kubernetes-native governed runners

## Attestation environment

The manager generates the closed Agent environment: `RAVENROOT_POD_UID` from the
Downward API, `RAVENROOT_NETWORK_CONTROL_HOST` from the operator control Service,
and `RAVENROOT_WORKSPACE_LIMIT_BYTES`, `RAVENROOT_MEMORY_LIMIT_BYTES`,
`RAVENROOT_CPU_MILLICORES`, and `RAVENROOT_PROCESS_LIMIT` from approved policy.
These are required attestation inputs, not graph inputs or operator environment
overrides. They grant no authority: actual filesystem, cgroup, PID, UID and
network enforcement must pass the positive probes before Agent execution.

`RAVENROOT_RUNNER_READINESS_FILE` optionally selects the manager's owner-written,
expiring readiness file (`/tmp/ravenroot-runner-ready` in Helm). The Java readiness probe
rejects missing, stale, oversized or symlink files. Only a completed positive
preflight and current worker availability renew it; it contains no credentials.

A **runner manager** claims governed work and calls the Kubernetes API. Agent containers run in
visible Kubernetes Pods on a containerd node, not inside a Docker daemon selected by a host socket.
This follows the manager/workload separation familiar from GitLab Runner's Kubernetes executor;
Ravenroot additionally preserves independent Workspaces, named Agent sessions and fenced graph
continuations. See [ADR 0043](https://github.com/ravenroot-ai/ravenroot/blob/dev/adr/0043-kubernetes-native-governed-runner.md).

## Choose the right path first

| Path | Required substrate |
|---|---|
| UI, graph authoring, ordinary Ravenroot containers | No Workspace executor |
| Conversational Agent without `workspaceRef` | Approved managed model integration; no repository Pod |
| Docker driver, including a Docker supervisor deployed on Kubernetes | Dedicated supported Linux Docker daemon and positively enforced writable quota |
| Kubernetes driver | Approved Linux Kubernetes/runtime/storage/security combination below; no Docker/CRI socket |

macOS can host the UI or a qualifying Linux VM. Docker Desktop, Kind and Minikube are not automatic
quota/security approvals. Run preflight before provider requests; do not disable enforcement to make
an unsupported local cluster appear ready. `service.sh --runner` remains the trusted-local **Docker**
supervisor path. The Kubernetes manager uses the standalone worker entry point and its own identity.

## Initial support matrix

| Component | Supported/verified boundary |
|---|---|
| Kubernetes | Native acceptance target 1.35.4; ValidatingAdmissionPolicy v1 and restricted Pod Security Admission required |
| Runtime | Linux containerd, cgroup v2, seccomp RuntimeDefault, Landlock ABI 3 or newer |
| Network | Enforcing Calico NetworkPolicy; positive reachable/blocked control required |
| Storage | Operator-preprovisioned fixed-size ext4 local PV, RWO, `Retain`, `WaitForFirstConsumer`, no expansion |
| CSI, snapshots, `emptyDir`, hostPath provisioner | Not approved by this release; accepting a PVC request is insufficient |
| Checkpoint/restore or cross-driver migration | Unsupported for this backend; checkpoint admission is refused before dispatch |
| Agent image | Approved registry digest containing the fixed Agent/attestation entry points |

The Agent only mounts its PVC. Local PV node paths are infrastructure provisioned by the operator,
not Agent `hostPath` mounts. Provision finite filesystems whose actual `statvfs` capacity is no larger
than the profile ceiling; merely labeling a larger directory as a 64 MiB PV fails preflight. Configure
the kubelet PID limit to fit the approved profile (native CI uses 256); a larger unenforced allowance
fails the positive fork boundary. Reference profiles use 500 millicores and 128 MiB memory.

## Install and preflight

1. Provision a dedicated workload namespace per pool/tenant trust boundary and the finite approved
   PV pool. Keep graph-server, runner-coordinator, manager and Agent identities separate. The Helm
   template creates the workload namespace with restricted PSA and retained protection policies.
2. Build [Agent.Dockerfile](../examples/governed-runner/Agent.Dockerfile) from an operator-verified
   digest-pinned Python/Alpine base. Publish its immutable registry digest. Build
   [KubernetesManager.Dockerfile](../examples/governed-runner/KubernetesManager.Dockerfile) using
   digest-pinned Ravenroot and compatible kubectl images. The manager needs Java and kubectl, not a
   Docker CLI. Native CI pins kubectl 1.35.4; follow Kubernetes client/server version-skew rules.
3. Adapt [kubernetes-values.yaml](../examples/governed-runner/kubernetes-values.yaml). Every pool
   declares `driver`; native pools forbid `socketHostPath`. Replace placeholder digests, ConfigMaps,
   manager/control-plane identity Secrets, shared artifact PVC and manager state StorageClass.
   Never use the Workspace PVC as the manager's state or artifact store.
4. Mount [worker-kubernetes.json](../examples/governed-runner/worker-kubernetes.json) as the pool's
   `worker.json`. Its closed `kubernetes` object must match the workload namespace, Agent
   ServiceAccount, storage/runtime class and image allowlist. Set `networkControlHost` to
   `<release>-ravenroot-agent-<pool>-network-control.<release-namespace>.svc`. The manager resolves
   that Service; the Agent receives only its numeric control address, not Kubernetes credentials.
   Use the actual rendered name if a chart name override is configured.
5. Mount workload identity files under `/run/identities/<StatefulSet-pod-name>`, owner-only. The
   runner identity uses `{instance}` expansion and must match its approved registration. The
   projected ServiceAccount token and CA authenticate the manager to Kubernetes; provider secret
   references resolve only in its gateway. No exec credential plugin, impersonation, client
   certificate, ambient kubeconfig or insecure TLS fallback is accepted.
6. Start the manager. Before advertisement it creates and positively attests a probe Pod and PVC
   for each runtime, then terminates the Pod. One probe PVC per runtime remains explicitly charged
   for reuse on restart. It tests real quota/PID refusal, non-root confinement, CPU throttling,
   memory cgroup limits and network isolation. No model request runs during this preflight.
   Readiness uses an expiring positive worker observation and is false on preflight/API failure.

`placement` is a closed operator setting: `priorityClass`, `requiredAffinity` (exact key/value
matches), `tolerations` (`key`, `value`, `effect`, `seconds`), `topologyKey` and `maxSkew`.
Only Equal tolerations and hard `DoNotSchedule` spread are supported. The independent `nodeSelector`
and `runtimeClass` are also operator-owned. These settings are never accepted in GraphML or an Agent
definition. Kubernetes does the actual scheduling; Pending does not authorize an alternative driver.

The namespace manager Role has Pods/PVC create/get/patch/delete plus constrained exec; inventory
listing and watches are not needed by the exact-object polling driver.
It cannot read Secrets or mutate Nodes, Namespaces, RBAC or admission configuration. The CONNECT
admission policy allows only fixed bounded Agent and attester commands; no shell, attach or
port-forward. An operator-only destructive `--memory-boundary` probe is reserved for sacrificial
Pods, never an active Workspace. The secretless network control Pod has no ServiceAccount token,
read-only root and no egress. Bootstrap network access is removed once before Agent input; it cannot
be restored by a Pod update. Agents independently verify isolation before processing input.

## Publish the graph actors

Bootstrap may contain empty `definitions`, `runners` and `workspaceProfiles` lists. Use the existing
authenticated catalog API, `runner publish`, or Workbench to publish/version and approve actors;
the worker image contains the runtime, not a fixed actor catalog.

For a fresh example tenant, adapt the existing
[control-plane catalog](../examples/governed-runner/control-plane.json): select `driver: KUBERNETES`
on the approved `development` Workspace profile, use `kubernetes-pod-v1` on the designated runner,
and use matching 256-process/500-millicore ceilings in the operator policy and relevant definitions.
For an existing tenant, publish a new immutable profile version and explicit runner approval; do
not rewrite an already approved version. Keep the same named Agent definitions and graph semantics.

The [literal three-Agent graph](../examples/governed-runner/three-agents.graphml) is
`START → Workspace → Betelgeuse → Polaris → Antares → Workspace → END`.
The [development cycle](../examples/governed-runner/development-cycle.graphml) additionally plans,
tests, remediates, retests, reviews and hands off through named Agents. The real runtime exchanges
model/tool messages with the manager's bounded gateway; Agent output is not workload metadata.

## Lifecycle, retention and capacity

| Scope/lifecycle | Physical behavior |
|---|---|
| PROCESS_INSTANCE + PER_WORKSPACE | Same Pod UID and PVC across Agents, remediation and later traversals until close/abort |
| PROCESS_INSTANCE + PER_INVOCATION | New Pod UID per invocation, same PVC and uncommitted files, no fresh clone |
| EPHEMERAL | Fresh Pod and PVC for each invocation; open allocates no unused base filesystem |
| NAMED | Tenant/profile/name logical identity; sequential generations reuse a quiescent PVC, never an old Pod |
| Multiple Workspaces | Independent PVCs, Pod lifetime, stop, retention and capacity within one process |

`close` seals evidence and terminates workloads; `abort` is sticky and prevents further use. Job
cancellation revokes new model/tool turns before exact-UID termination. Workspace stop affects only
that resource; process stop/cancel propagates through its independently owned resources. API timeout,
Terminating, NotReady and disconnected observation are not absence proofs. Cleanup never escalates
to name-only force deletion or removal of finalizers. An exceeded cleanup timeout remains UNKNOWN.

Keep manager intent/results, database authority, shared artifacts/logs and PVCs through unknown
effects and undelivered continuations. Terminal retention first reserves cleanup, proves Pod absence,
deletes the owned PVC with UID/resourceVersion preconditions and acknowledges release. It does not
erase the retained PV or its bytes. Reserve the **entire preprovisioned pool**, plus manager state,
artifacts/logs and probe claims; Released PV capacity is not free capacity. Operators reclaim an
exact volume only after quiescence and evidence retention, before reprovisioning a fresh filesystem.
Never clear claimRefs or prune volumes by a prefix as part of ordinary runner recovery.

Worker job slots, catalog reader/writer and fleet ceilings, model concurrency, namespace Pod/PVC/
storage quota, available fixed filesystems, API command timeouts and coordinator recovery-page/thread
capacity are separate bounds. QUEUE, REJECT and AUTOSCALE remain catalog policies. AUTOSCALE exposes
bounded demand for operator HPA/KEDA/node scaling; Ravenroot does not provision cluster nodes.
Pending/Unknown/Terminating ownership is charged, not recycled as a free execution slot.

## Recovery classification

| Observation/fault | Required disposition |
|---|---|
| No persisted dispatch intent and no accepted effect | Normal governed admission; only the control plane may grant a claim |
| Crash before/after create, create/patch timeout, Pod exists before UID receipt | Report-only identity recovery; stop required before any cleanup; never replay |
| Pending due to quota/image/placement/admission/PVC | Bounded wait, then UNKNOWN/stop required; retain ownership and repair operator configuration |
| Observation/API disconnect, database/control-plane outage, partial artifact upload | UNKNOWN, report-only; retain resources and result/intent evidence |
| Terminal result sealed before continuation | Report the same result under the current fence; continuation is idempotent |
| Agent exit without sealed result | UNKNOWN; no reexecution, even if Pod exit is successful |
| Eviction, node loss/return, manual deletion, namespace termination | Stop/retain evidence; missing PER_WORKSPACE runtime is not recreated from recoverable storage |
| PVC Pending/Lost/rebound/UID replacement or permanent storage loss | Fail closed; manual recovery, no fresh filesystem behind an old Workspace |
| Delete timeout | Reobserve exact UID; absence proves quiescence, API unavailability does not |
| Replacement manager while old incarnation/lock is valid | Refuse admission/claim; never run concurrent owners |
| Stale fence, old generation or replaced Pod UID | Reject report/cleanup without touching the current resource |
| CSI snapshot uncertainty, restore or cross-driver migration | Unsupported; no snapshot call or implicit fallback is performed |
| Lost manager state or inconsistent ownership evidence | Manual recovery; do not reconstruct execution permission from labels alone |

Recovery is deliberately conservative. Fixing connectivity does not change UNKNOWN into permission
to execute. Reconciliation can recover complete intent-correlated UIDs and terminate owned work;
operators resolve graph continuation only using the existing audited, revision-fenced controls.

## Observe and troubleshoot

Workbench's runner view and `runner workspace <process-id>` show the selected driver, named Agent,
Workspace/node, logical cluster and namespace, Pod/PVC UID, generation, phase/condition/reason/exit,
budget consumption, artifacts and direct result. The tenant-scoped API and OpenAPI expose the same
bounded `kubernetes` object on resources/jobs; no endpoint or credential is included. Heartbeats and
terminal reports persist physical evidence and add correlation to the existing event/audit stream.

OTel uses only closed enums or unlabelled gauges: `ravenroot.runner.observations`, worker configured/
available slots, recovery-page Pod phases and closed failure reasons, retained storage,
API quota/admission/authorization/conflict/unavailability classes, unknown/fence conflicts, and native
create/termination latency. Recovery-page gauges are **not fleet totals**. The conservative native
backend-reserved gauge includes retained claims even after PVC release while their authority rows
remain. Never label metrics with tenant/process/Pod IDs or graph names; use authorized inspection for
that correlation. Do not drive storage reclamation from a page gauge.

| Symptom | Check without weakening enforcement |
|---|---|
| Readiness never true | Manager identity, namespace admission/RBAC, available fixed PVs, immutable image, bounded probe failure |
| Positive network control fails | Control Service endpoints and CNI routing first; an unreachable service is not enforcement proof |
| Control remains reachable after isolation | Enforcing CNI and label/policy propagation; no Agent admission is allowed |
| Storage/PID attestation refuses | Actual filesystem capacity/kernel refusal and kubelet PID setting, not PVC declared size |
| Pod Pending | Approved image availability, placement/runtime class, ResourceQuota and PVC binding; retain intent |
| Pod lost, OOMKilled or evicted | Inspect retained result/usage/UID; never restart it as a duplicate Agent effect |
| API operation refused/unconfirmed | Consult operator Kubernetes audit with bounded job/Pod correlation; do not paste raw credentials or assignment input into logs |
| Unknown old-generation cleanup | Verify current generation/UID and prior retention; do not force deletion |

## Upgrade, rollback and uninstall

Drain admissions and prove existing workloads quiescent; preserve database, manager state, artifacts
and PV backups. Upgrade coordinators, servers and all designated workers together before publishing
native profiles. Binary workspace v4, assignment/result v3 and profile v2 preserve native identity;
old readers refuse their version magic. Do not run mixed old/new readers against new native state.
Legacy profiles default to Docker and are not silently migrated. Active migration needs an attested
checkpoint and a new generation; because this backend has no checkpoint contract, finish/close the
old Workspace and explicitly create new work instead.

Rollback to an older binary requires the untouched matching pre-upgrade state backup and proven
quiescence. Never drop native fields or relabel a live native registration as Docker to force rollback.
Helm retains workload namespaces, deny policies, quota and admission protections on uninstall so
retained Agent resources do not gain network/exec authority. Stop and release owned work before
uninstalling the manager; remove retained protections only after independently proving the namespace
empty and satisfying evidence retention.

## Native acceptance

`python3 scripts/fixtures/kubernetes_runner_acceptance.py` provisions an isolated Linux
containerd/Calico cluster and fixed ext4 PV pool, builds the immutable Agent, applies the actual chart
admission/network policies and runs real graph/lifecycle/recovery tests against a secretless model
endpoint. It verifies exact zero-skip test counts, model traffic and cleanup, then deletes only its
own unpredictable cluster profile. Missing Kubernetes/storage/security capability fails the full
CI tier. Fake API tests supplement this path; they do not substitute for native evidence. Real-provider
smoke remains explicit, protected and non-release-blocking.
