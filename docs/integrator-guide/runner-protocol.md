# Governed runner protocol v1

`RunnerDriver`, `RunnerAssignment`, `RunnerRegistration`, `RunnerResult` and
`RunnerWorkspaceRelease` form the versioned runner SPI. `RemoteRunnerClient` and `RunnerWorker`
are the designated HTTP integration. No runner receives a graph checkpoint or chooses a tenant,
process, traversal, invocation, attempt or job ID.

The identity chain is:

`processInstanceId → traversalId → invocationId → attemptId → runnerJobId`

One process can own multiple graph-declared Workspaces, each identified by its Workspace node,
resource UUID and ownership generation. With `PROCESS_INSTANCE`, later traversals reuse that
resource while another process is isolated. `PER_WORKSPACE` retains one pinned runtime container;
`PER_INVOCATION` replaces the runtime while preserving filesystem state without recloning. Each
invocation retains its own durable job/fence and physical observation. Sequential Agents share the
Workspace only after the prior invocation is known quiescent. There is no second Agent-to-Agent lease.
Named Agent session identity is tenant/process/definition-version scoped, independent of traversal
and Workspace identity. See [ADR 0042](https://github.com/ravenroot-ai/ravenroot/blob/dev/adr/0042-explicit-workspaces-and-runner-coordinators.md).

## HTTP surface

All paths are under `/v1/runner-plane` and use the existing authenticated browser, authorization and
request-limiting boundary.
The prefix itself has no GET, PUT or POST operation; unknown paths return structured 404 errors.

| Method and path | Contract |
|---|---|
| GET `/catalog` | Bounded metadata inventory |
| GET `/catalog/{kind:name:version}` | One editable JSON resource |
| PUT `/catalog` | Immutable body or revisioned approval; explicit `expectedRevision` |
| POST `/register` | Workload advertisement; draft only |
| GET `/assignments?cursor=…` | Bounded page for the authenticated runner, including eligible cleanup |
| GET `/availability` | Tenant-scoped live worker incarnations, installed runtimes, capacity and availability |
| POST `/availability` | Approved workload renews its store-clock incarnation lease; never grants approval |
| GET `/health?cursor=…` | Operator-readable tenant health page and explicitly page-scoped gauges |
| GET `/audit?afterOffset=…` | Bounded tenant runner-journal history; offset advances across other events |
| GET `/workspaces/{processId}` | Operator workspace, job metadata and exact current process `revision` |
| GET `/workspaces/{processId}/worker-revision` | Pinned workload reads the revision needed for fenced stop reports |
| POST `/workspaces/{processId}/resources/{nodeId}/abort` | Operator requests one sticky Workspace stop at `expectedRevision` |
| POST `/workspaces/{processId}/resources/{nodeId}/stopped` | Pinned workload reports that exact stopped Workspace quiescent |
| POST `/workspaces/{processId}/resources/{nodeId}/release` | Pinned workload reserves terminal, retention-checked physical cleanup |
| POST `/workspaces/{processId}/resources/{nodeId}/released` | Revision-fenced acknowledgement releases that Workspace's reserved capacity |
| GET `/workspaces/{processId}/jobs/{jobId}` | Designated-runner binary assignment |
| POST job `/claim` | `{"ttlSeconds":30}`; QUEUED execution permission only |
| POST job `/heartbeat` | `{"ttlSeconds":30,"fence":1}` |
| POST job `/reconcile-report` | Fresh report-only claim after UNKNOWN |
| POST job `/complete` | Binary v1 result, with `X-Runner-Fence` |
| POST job `/cancel`, `/reconcile` | Operator stop request or store-clock liveness fold |
| POST job `/resolve-continuation` | User-only `{ "expectedRevision": 17, "resolution": "RESUME" }`; modes RESUME, ACKNOWLEDGE, ABANDON |
| POST job `/artifacts` | Bounded bytes; `X-Runner-Fence` and `X-Runner-Artifact-Kind` |
| GET job `/artifacts/{artifactId}` | Authorized retained evidence; JSON Accept requests a bounded text preview |
| POST `/workspaces/{processId}/release` | Terminal, retention-checked cleanup proof for the designated runner |

The binary format is the explicit length-bounded `RunnerCodec` envelope with version magic and
SHA-256 corruption detection. It is not Java serialization. Digests detect corruption, not malicious
issuers; transport authentication and runner fencing remain mandatory. Unknown protocol versions,
oversized envelopes, undeclared outcomes and mismatched identities are refused.

The existing binary envelope versions have a 16 MiB complete-document bound and a 1 MiB
payload/catalog-document field bound (`RunnerCodec.MAX_BYTES` and `MAX_PAYLOAD_BYTES`). They preserve
compatibility with existing bounded readers, not a universal job-count or worker-capacity limit.
Widening those wire bounds requires an explicit versioned reader migration, not a larger worker
advertisement. Operator payload budgets may independently select any positive size within the field
bound; admission enforces the effective intersection. Boundary tests round-trip the maximum payload,
reject its successor and reject an oversized encoded envelope. Metadata identifier/collection bounds
likewise belong to the versioned grammar, not concurrent work admission.

Artifact uploads require `Content-Type: application/octet-stream`, a live `X-Runner-Fence` and an
explicit `X-Runner-Artifact-Kind` enum. Missing or invalid kind/header/body is a structured 400;
a stale fence conflicts (409), absent or expired artifacts return 404, and an unconfigured plane
returns 501. Completion requires `application/vnd.ravenroot.runner-result.v1`; assignment responses
use `application/vnd.ravenroot.runner-assignment.v1`. JSON claim/heartbeat/resolution operations
require `application/json`. Binary artifact retrieval returns an attachment with `nosniff`;
`Accept: application/json` selects the bounded text preview instead.
The checked-in OpenAPI document specifies required headers, bodies, pagination queries and response
media types. The workspace storage envelope is independently versioned; version 2's process terminal
timestamp is never accepted as runner-provided authority.

Continuation resolution reconciles only accepted graph state. Zero successors may use RESUME;
complete successor multiplicity may use ACKNOWLEDGE; partial delivery requires explicit ABANDON
unless the operator first establishes the complete graph state. The transaction is tenant-authorized,
process-fenced and revision-CAS protected, and emits one audit event. Repeated/stale calls return 409.
It cannot replay, reclaim or modify a terminal runner job.

The deterministic conformance runtime image receives one bounded JSON document on stdin: protocol version, job and
workspace IDs, fence, frozen definition, effective authority, command and input. It returns exactly
`{"outcome":"…","payload":{…}}` on stdout. Diagnostics belong on bounded stderr. It must not log
credentials or echo arbitrary unbounded input. The driver proves container termination before
publishing a result and retains a no-redispatch marker before invoking it.

The real `agent_runtime.py` instead speaks a bounded line-delimited model/tool protocol to its trusted
supervisor. It consumes the resolved definition, command, effective authority, skills and budgets;
only the supervisor resolves an approved model profile and credential reference. The container has
no model credential or network. Model responses must include token usage; tool and model boundaries
check cancellation and finite budgets. The final structured outcome/payload becomes the direct
execution result. The deterministic fixture is never real-model acceptance evidence.

## Commands and graph routing

The standard commands are `plan`, `read`, `research`, `review`, `implement`, `test`,
`remediate`, `integrate`, `resume`, `summarize` and `handoff`. They are application commands,
not replacements for engine `process` or `passthrough`. Definitions explicitly declare commands and
outcomes. Reserved read-only commands are structurally attenuated even if a caller supplies a more
powerful definition or runner.
Custom command names come from the versioned tenant definition rather than a wildcard behavior
descriptor. Graph construction checks the named catalog declaration; actual delivery resolves the
authenticated tenant and requires approval before admitting a job. Retirement prevents new jobs,
but does not erase declarations needed to parse an already accepted continuation's pinned graph.

The [development-cycle graph](../examples/governed-runner/development-cycle.graphml) demonstrates
plan → read → resume → implement → test → review → handoff, with remediation routing on failed tests or requested
changes. The planner's `answered` edge explicitly invokes the reader with `read`; the reader's
`answered` edge invokes that same planner node again with `resume`. The return is a new invocation
in the same traversal, not a retry of the first caller attempt. The caller's completed visit holds no
engine worker while the reader is parked. Use [the input](../examples/governed-runner/input.json) as the graph payload and the
matching control-plane definitions. Graph traversal/amplification budgets still bound a remediation
cycle. Route `needs-input`, `blocked` or `escalation` to explicit human-task or failure behavior
according to the application's policy.

Ordinary human tasks and tool approvals remain their own trusted services. A remote advertisement
does not grant access to those services, a credential store or arbitrary tools. A driver that cannot
enforce a requested grant must advertise less capability or refuse the profile, never emulate
governance with prompt text.
