# Impact-aware maintenance verification

The maintained `release/0.8` line starts at the published `v0.8.0-alpha.1` commit
`c86fd4eb16849ce3b899200d14290be9a78014a5`. Select this exact destination for a
maintenance contribution; ordinary contributions continue to target `dev`. A maintenance
change never imports the 0.9 development history. Both lines require independent review,
protected pull requests, trusted `ci-required`, and merge-queue verification.

## Read or reproduce a plan

`ci.yml` archives `impact-plan-<run>-<attempt>` and writes the readable plan to the run summary.
The JSON records immutable baseline, candidate and tree IDs, paths, ownership, downstream
test closure, build prerequisites, integration contracts, selected jobs and positive omission
reasons. `full` records conservative fallback. Review compilation and test selections separately.

```sh
python3 scripts/impact_plan.py --event pull_request --lane release/0.8 \
  --base BASE_COMMIT --candidate CANDIDATE_COMMIT --output impact-plan.json
```

PR and queue plans compare the event's exact base against the actual checked-out merge
candidate, rather than assuming the topic head equals that integration. Dispatches resolve the
merge base of the explicitly allowlisted `integration_ref`; missing history, an empty range,
unknown ownership or unsupported Maven relationships select the complete suite. Deletions and
both sides of renames are included. Removed/renamed Maven modules require complete verification.

Maven relationships are derived from both Git trees: project coordinates, parents, dependencies
(including test, managed and profile dependencies) and plugin dependencies. A parent relationship
carries inherited dependency/build changes to its descendants. Dynamic internal coordinates fail
closed. Version-managed dependencies are selected by group/artifact identity regardless of the
managed version; external dependency or plugin changes inside a module select that module and
its transitive consumers. Shared root build changes select everything. This deliberately
conservative model over-selects profile/managed relationships rather than guessing an active
profile or downloading a mutable effective model.

`CONTRACTS` in `scripts/impact_plan.py` supplements Maven with persistence/server composition,
service-loaded extensions, runtime packaging, UI/backend interfaces, native runner acceptance and
support consumers. API/runtime changes select their transitive consumers and relevant native
integration checks. Add explicit relationships and positive/negative tests when introducing a
new discovery mechanism. Adding/removing a Maven module is automatically detected; review its
integration contracts before expecting a narrow plan.

## Execution and provenance

The plan records a concrete reason for every retained job, plus independent compilation scopes
for documentation and the browser harness. The plan gates compilation, packaging, backend tests, support modules, UI audit/unit/build/browser
checks, docs, API docs, plugin boundary and runtime smoke checks. Policy validation always
remains selected. `ci-required` reconstructs the plan from Git, checks it is bound to this candidate,
and refuses a missing selected job, a selected job that skipped/failed, or an unexplained job.
Configurator resolver/core-carrier contracts use the reusable Configurator workflow, including its
real AMQP/Compose exercise; complete fallback includes that job and its independent builds.
The advisory feature-push `ci-fast` workflow retains its compile/build feedback because a branch
push has no trusted issue-lane selection. It publishes only `ci-fast` and can never stand in for
`ci-required`; this remaining repeated compilation is explicit.
Dev retains its complete verification policy. A queue candidate reports its own native check;
no first-PR or single-entry-queue exception exists.

The build and independent Maven validation jobs remove cached `ai.ravenroot` artifacts before installation. Downloaded same-run
artifacts must match repository, exact commit/tree, Actions run/attempt, and every file's SHA256
manifest. Consumers clear cached project artifacts before restoring these verified prerequisites.
The dependency cache accelerates third-party downloads; it never proves project provenance.
Backend tests reuse installed current-candidate prerequisites and compile only selected test
projects without `-am`; backend packaging uses the prerequisite closure. UI and plugin outputs
also have verified same-run manifests. Manifests are checked before destructive staging changes.

Some compilation remains necessary: the all-extension pack requires every connector to compile,
its documentation validates every discovered node, and the runtime image combines current UI,
JAR and plugin outputs. The plan keeps these consumers even for a leaf connector. API Javadoc,
real-process browser harnesses and isolated extension consumers still compile their own scopes;
these are independent validation boundaries and have not been claimed as eliminated work.

Cross-event functional reuse is **disabled**. `scripts/ci_evidence.py` records/tests the necessary
proof: trusted successful complete producer, exact full tree, integration base, plan, toolchain,
runner image, dependency inputs and age at most 24 hours. Current hosted runner image identity and
complete immutable producer receipts are not established, so candidate, PR and queue execute
the applicable plan. A base advancement or combined feature tree invalidates equivalence even
without conflicts. A source-only fingerprint or successful dispatch's presence in REST does not
satisfy a PR's own status rollup. This conservative outcome avoids an unproven skipped gate.

## Complete regression and evidence comparison

Dispatch `ci.yml` on the exact candidate with `tier=full`, `integration_ref=release/0.8` and
`force_complete=true` to force every functional and policy job, including native XFS/Kubernetes
acceptance and full Maven regression. `maintenance-regression.yml` dispatches this complete run
weekly on the maintenance branch. GitHub schedules are loaded from the default branch: this
scheduler becomes active only after the reviewed workflow is present on `main`; until that
activation, maintainers must arrange the same weekly explicit dispatch and observe its result.
The existing default-branch schedule remains intact. A workflow file on a maintenance branch
alone does not establish a running scheduler.

Compare the archived narrow plan with that complete candidate run, including jobs, Maven modules,
unit/integration counts and outcomes. Use actual Actions job durations to calculate wall-clock
and summed runner time; retain runner image/toolchain, cache and contention conditions. No speedup
is inferred from module counts or local timings. Missing infrastructure or failed complete gates
remain failures and are not a valid performance result.

Any source, test, resource, dependency, toolchain or check-configuration change invalidates the
corresponding artifact/evidence inputs. Same-run manifests also invalidate on reruns. Recreate the
plan after every candidate change and repeat independent review on the exact verified head.

## Promotion and forward-port

Only the exact internal `dev` and `release/0.8` branches, and protected `hotfix/*`, may target
`main`; arbitrary `release/*` and fork heads are refused. Release preparation accepts
`--source dev` (default) or `--source release/0.8`. The chosen source, its latest immutable
release and owner's explicit release intent determine preparation; a branch name or CI-only
change does not authorize a version bump. No release label, tag or publication is created by
impact verification. Publication retains the release-environment approval.

A maintenance promotion needs its own source-line preparation PR, complete applicable verification,
independent exact-source release review, and the owner's release label. Promotion evidence must
come from this repository's trusted CI run on the exact source, completed successfully within
the preceding 24 hours; stale, failed or foreign evidence requires a fresh source run. Sync `main` into the chosen
source only through a reviewed PR when the diff introduces no other line's content. If `main`
has advanced incompatibly, stop the promotion and decide a release strategy explicitly; never
resolve it by importing `dev` into 0.8. Promotion reuse additionally requires the proposed/main
merge tree to equal the verified source tree. Otherwise align and verify the source first.
Release-specific version, notes, source and authorization validation still run.

The baseline `main` source-policy workflow accepts `dev`/protected hotfixes only. Because
`pull_request_target` runs the base branch's trusted workflow, the new maintenance allowlist
must first reach `main` through an independently reviewed, owner-authorized release-policy
activation under that existing policy. Until then a direct maintenance promotion correctly
fails closed. Do not bypass that gate, switch to an arbitrary release branch, or publish to
activate it. This issue configures and tests the future path; it does not activate a promotion.

Forward-port compatible fixes by creating a new topic from current `dev`, applying the minimal
reviewed commits, resolving 0.9-specific differences, then running fresh dev validation and
independent review before a dev PR. Record the source maintenance commits in the PR. Do not merge
dev backwards, claim maintenance evidence verifies dev, or forward-port automatically.
