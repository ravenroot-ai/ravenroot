# Build, local harness, and test strategy

Build the complete distribution with JDK 21, Maven, Node.js 20.19 or later, and npm, then verify contracts at their natural boundaries.

## Required practice

- Run `./ravenroot/scripts/build-release.sh` to assemble server, CLI, UI, and distribution artifacts.
- Use unit tests for parsers and state transitions, contract tests for SPIs and transports, integration tests for engine and persistence boundaries, and end-to-end tests for packaged launch.
- Exercise both Pekko and any installed Akka adapter, Test and Run modes, restart recovery, authentication refusal, and extension absence.
- Reproduce UI behavior against the live node catalog rather than a separately maintained palette fixture.

Use the complete [command-line tools reference](../reference/command-line-tools.md) for `dev.sh`,
`service.sh`, `plugin.sh`, and the application CLI. Bundle-reference changes begin in the extension
module README and are published with `python3 scripts/publish_bundle_reference.py`; check mode is a
required drift gate.

## Tests that need a database

The shared PostgreSQL adapter's conformance suite starts a real PostgreSQL server in a container, so
running `ravenroot-persistence-postgresql` needs a working Docker daemon. The container is started by
the test code rather than by a workflow, so a developer and continuous integration provision it the
same way, and an absent daemon fails the run rather than silently skipping the assertions the adapter
exists to prove.

## Sandbox supervisor CPU red control

The sandbox-supervisor testkit keeps two CPU checks separate. The required red control uses a
policy-and-recipe-specific test double to prove deterministically that the reusable contract rejects
`COMPLETED` for an 8-second deadline, 250ms CPU limit and single-threaded `BUSY 4000` recipe. The real
process probe for a deliberately CPU-noncompliant supervisor remains available only to the explicitly
selected measurement carrier; it measures how host scheduling affects the deadline and is not evidence
supplied by the deterministic double. The ordinary compliant CPU contract and the other ten inherited
paths continue to launch real processes in the default suite.

Use `ravenroot-sandbox-supervisor-testkit/src/test/scripts/measure-cpu-red-control.sh` only after
`test-compile`, with an exact 40-character source commit and a fresh output directory. The arguments
are `CHECKOUT MODE CONDITION START_INDEX COUNT COMMIT OUTPUT_DIR`. `MODE` is `base`, `candidate`, or
`candidate-siblings`; `CONDITION` is `isolated` or `contended`. Each scheduled target gets a fresh
Maven and Surefire process, explicitly sets `surefire.rerunFailingTestsCount=0`, and remains in the raw
JSONL output even when it fails or reaches the harness safety ceiling.

Predeclare and run this matrix without changing its denominators:

- base: 20 exact original CPU outer-control executions isolated and 20 contended, ordered as 10
  isolated, 20 contended, 10 isolated;
- candidate: the same 20 plus 20 executions of the strengthened CPU outer control;
- candidate siblings: the complete nine-control class five times isolated and five times contended;
- base diagnostic auxiliary: three isolated and three contended real-process observations at outer
  indexes 1, 10 and 20. These six are a separate denominator and never identify the outcome of a
  different outer invocation.

The contended mode starts two synchronized CPU workers per available processor. Every worker must
report readiness before the shared start signal and a larger iteration count across every measured
sample. Base samples also run a separate process that accumulates 4,000ms of current-thread CPU time.
The portable premise is RUNNABLE only when that process finishes in at most 6,000ms, leaving a
predeclared two-second margin below the supervisor policy deadline. Keep low-share and unsupported
calibrations as NOT_RUNNABLE records. They may explain the deadline race in a separate causal
characterization, but they do not enter a valid product-conformance denominator.

Retain the exact commits, module tree, runner image, JDK, available processors, cgroup CPU quota,
pressure data, calibration result, per-worker progress, raw outer Maven/Surefire result, nested
counts, failed descriptor and throwable classification. A valid candidate CPU sample has 11 nested
tests started, ten succeeded, exactly one failed, and the direct CPU assertion reports actual
`COMPLETED`. Timeouts, wrong causes, invalid load overlap, skipped samples, and workflow reruns are not
successes. Also retain the ordinary clean reactor result, the load-interleaved 10/10 result and all
three load-wrapper guards.

## Boundary

A change is complete when its machine contract, executable test, and user-facing English documentation agree. Build success alone does not establish compatibility.

## References

- [Related contract](../reference/api-cli.md)
- [Related guide](api-doc-release.md)
