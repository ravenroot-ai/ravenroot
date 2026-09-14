# Governed runner protocol v1

`RunnerDriver`, `RunnerAssignment`, `RunnerRegistration`, `RunnerResult` and
`RunnerWorkspaceRelease` form the versioned runner SPI. `RemoteRunnerClient` and `RunnerWorker`
are the designated HTTP integration. No runner receives a graph checkpoint or chooses a tenant,
process, traversal, invocation, attempt or job ID.

The identity chain is:

`processInstanceId → traversalId → invocationId → attemptId → runnerJobId`

One process owns one pinned workspace. A new traversal of that process uses that workspace, while
an independent process receives a different workspace. Sequential agents share the workspace only
after the prior runner process tree is known quiescent. There is no independent agent-session lease.

## HTTP surface

All paths are under `/v1/runner-plane` and use the existing authenticated browser, authorization and
request-limiting boundary.

| Method and path | Contract |
|---|---|
| GET `/catalog` | Bounded metadata inventory |
| GET `/catalog/{kind:name:version}` | One editable JSON resource |
| PUT `/catalog` | Immutable body or revisioned approval; explicit `expectedRevision` |
| POST `/register` | Workload advertisement; draft only |
| GET `/assignments?cursor=…` | Bounded page for the authenticated runner, including eligible cleanup |
| GET `/health?cursor=…` | Operator-readable tenant health page and explicitly page-scoped gauges |
| GET `/audit?afterOffset=…` | Bounded tenant runner-journal history; offset advances across other events |
| GET `/workspaces/{processId}` | Operator workspace and job metadata |
| GET `/workspaces/{processId}/jobs/{jobId}` | Designated-runner binary assignment |
| POST job `/claim` | `{"ttlSeconds":30}`; QUEUED execution permission only |
| POST job `/heartbeat` | `{"ttlSeconds":30,"fence":1}` |
| POST job `/reconcile-report` | Fresh report-only claim after UNKNOWN |
| POST job `/complete` | Binary v1 result, with `X-Runner-Fence` |
| POST job `/cancel`, `/reconcile` | Operator stop request or store-clock liveness fold |
| POST job `/artifacts` | Bounded bytes; `X-Runner-Fence` and `X-Runner-Artifact-Kind` |
| GET job `/artifacts/{artifactId}` | Authorized retained evidence; JSON Accept requests a bounded text preview |
| POST `/workspaces/{processId}/release` | Terminal, retention-checked cleanup proof for the designated runner |

The binary format is the explicit length-bounded `RunnerCodec` envelope with version magic and
SHA-256 corruption detection. It is not Java serialization. Digests detect corruption, not malicious
issuers; transport authentication and runner fencing remain mandatory. Unknown protocol versions,
oversized envelopes, undeclared outcomes and mismatched identities are refused.

The reference runtime image receives one bounded JSON document on stdin: protocol version, job and
workspace IDs, fence, frozen definition, effective authority, command and input. It returns exactly
`{"outcome":"…","payload":{…}}` on stdout. Diagnostics belong on bounded stderr. It must not log
credentials or echo arbitrary unbounded input. The driver proves container termination before
publishing a result and retains a no-redispatch marker before invoking it.

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
