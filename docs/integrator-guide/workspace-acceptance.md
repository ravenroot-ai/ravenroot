# Workspace/Agent acceptance map

Issue #423 is one atomic enhancement. ADR 0042 and owner clarification 5704464414 require all
thirteen scenarios below. Hermetic native evidence is mandatory; owner-only live-provider smoke is
separate, local, optional and never a PR/merge/release gate.

| Scenario | Automated evidence |
| --- | --- |
| 1. Literal minimal named-Agent graph | `WorkspaceAgentRuntimeTest` runs START → Workspace(open) → Betelgeuse → Polaris → Antares → Workspace(close) → END; typed-reference/browser contracts |
| 2. One process worktree and retained container | Native development cycle compares runtime ID, Git HEAD and uncommitted sentinel after every Agent/remediation/restart |
| 3. Later traversal reuse and other-process isolation | Native different-process/later-traversal assertions compare session, container, worktree and uncommitted state |
| 4. Independent runtime lifecycle | Native cycle runs PER_WORKSPACE and PER_INVOCATION, preserving filesystem without reclone |
| 5. Process/Agent isolation and parallel capacity | Session tests, worker capacities 2 and 37, store admission and native different-process/later-traversal assertions |
| 6. Independent Workspace-specific stop | Two-resource state/stop/store contracts, typed references, and native stop of an active Agent test child while the independent retained container and sentinel remain intact |
| 7. Process-wide cancellation | Process lifecycle HTTP/store contracts, worker/model dispatch fencing, native child termination and sticky restart refusal, and local-supervisor shutdown tests |
| 8. Configured capacity, metrics and admission | Worker capacities 2 and 37, atomic fleet/queue/reject/scheduling tests and configured metrics; four is not a maximum |
| 9. Durable crash/recovery/fencing | All three store adapters, pinned continuation recovery, `LocalContainerRunnerRecoveryTest` lock initialization/restart/replay non-interference, native receipt replay refusal and reconciliation after effects |
| 10. Security boundaries | Authority/protocol tests and native adversarial Landlock/seccomp probe; graph/input cannot select host paths, secrets, capabilities or worker identity |
| 11. Real runtime model/tool execution and native safety | `runner_quota_acceptance.py`: production gateway + unchanged bounded Agent runtime + secretless loopback model-protocol fixture; Linux Landlock/seccomp and effective XFS quotas |
| 12. Independent worker/control-plane scale | Distinct PostgreSQL-backed coordinator topology tests and Helm worker pools; full-server replica guard unchanged |
| 13. Ordinary Agent compatibility and removal | Ordinary governed/legacy Agent tests; no generic workspace-agent catalog entry or shim; documentation contracts |

The endpoint fixture proposes tools and derives test outcomes from returned tool results. It has
neither filesystem nor daemon access. It is deterministic protocol evidence, not trained inference.
The legacy reference runtime still only proves conformance and cannot replace the native fixture.
Missing native prerequisites or skipped native tests fail the full tier; no external provider,
secret, offline model weights, macOS or nono is required.

Additional trusted-local proof: `LocalRunnerSupervisorTest`, `test_service_runner.py` and the existing
authentication/service suites verify exact 127.0.0.1 exposure, public USER versus private WORKLOAD,
rejection of bearer/identity override fields, supervised lifecycle, sticky retained-Workspace stops
and unchanged remote/OIDC behavior. See [operator commands](../operator-guide/governed-runners.md).
