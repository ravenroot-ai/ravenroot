# Business history acceptance and release qualification

This map identifies the responsible contracts and reproducible evidence for the integrated execution
and observation behavior. Test names identify maintained executable cases, not a substitute for the
candidate’s exact-commit CI result or independent review. The normal full workflow enables the real
first-Run, native/embedded history and executed lakehouse browser gates.

| ID | Contract and executable evidence | Reference |
|---|---|---|
| A01 | DefaultGraphDeployment listener activation; DeploymentInitialStartTest and AmqpMixedDeploymentExampleTest prove zero START/effects after Deploy. | [source entry contract](../reference/source-entry-contract.md) |
| A02 | deployment-run.js origin intent and runCapturedDocumentDeployment; deployment-first-run.spec.js and FirstRunPersistenceBrowserTest execute activation, binding and initial admission. | [api cli](../reference/api-cli.md) |
| A03 | DefaultRavenrootApplication.startLocalDeploymentExecution; DeploymentInitialStartTest and first-Run browser cases reuse a deploy-only binding. | [api cli](../reference/api-cli.md) |
| A04 | Permanent InitialStartRecord and atomic store arbitration; DeploymentInitialStartTest plus deployment-run.test.js cover concurrent/distinct requests and lost responses. | [api cli](../reference/api-cli.md) |
| A05 | DefaultGraphDeployment initial marker; DeploymentInitialStartTest, embed parity and AmqpMixedDeploymentExampleTest retain one START after completion/failure. | [execution identity](../reference/execution-identity.md) |
| A06 | Independent logical deployment/registration identity; DeploymentInitialStartTest and first-Run browser cases create an explicit fresh identical-file deployment. | [execution identity](../reference/execution-identity.md) |
| A07 | Stable registration reconciliation and retained marker; FirstRunPersistenceBrowserTest executes real JVM/SQLite restart and explicit verified restore. | [api cli](../reference/api-cli.md) |
| A08 | Permanent accepted input/IDs and recoverable execution; DeploymentInitialStartTest and FirstRunPersistenceBrowserTest cover admission/dispatch/response-loss windows. | [api cli](../reference/api-cli.md) |
| A09 | SourceContext activation generation and rollback; DefaultGraphDeploymentSourceAuthorityIntegrationTest, source startup tests and CoreScheduledSourceDeploymentTest cover provisional traffic/failure. | [source entry contract](../reference/source-entry-contract.md) |
| A10 | GraphRunner shared monotone budgets; AmqpMixedDeploymentExampleTest executes actual HTTP/delay cycle with four RabbitMQ listeners and budget failure. | [source entry contract](../reference/source-entry-contract.md) |
| S01 | GraphRunner.executeIngress at owner; SourceLocalTraversalTest, real managed lakehouse callbacks and mixed broker events avoid START/ancestors. | [source entry contract](../reference/source-entry-contract.md) |
| S02 | GraphRunner SOURCE_BOUNDARY; SourceLocalTraversalTest and mixed initial path stop before source descendants without orphan work. | [source entry contract](../reference/source-entry-contract.md) |
| S03 | Independent branch settlement; AmqpMixedDeploymentExampleTest observes actual SMTP sibling acceptance beside SOURCE boundaries. | [source entry contract](../reference/source-entry-contract.md) |
| S04 | Parented source arrival is a boundary; SourceLocalTraversalTest and mixed source-to-source paths create no synthetic event. | [source entry contract](../reference/source-entry-contract.md) |
| S05 | Per-source facade/independent ingress identities; SourceLocalTraversalTest and four actual broker queues select their exact owning entries. | [source entry contract](../reference/source-entry-contract.md) |
| S06 | BehaviorRegistry source nature/provenance plus common facade; compiled 15-source inventory, listed connector contracts and installed IMAP container gate. | [source entry contract](../reference/source-entry-contract.md) |
| S07 | SourceContext ownership/generation and ingressEntry validation; SourceAuthorityLifecycleTest, SourceLocalTraversalTest and request/reply authority tests refuse wrong targets. | [source entry contract](../reference/source-entry-contract.md) |
| S08 | Pinned retained source intent, reachability and budgets; DisconnectedSourceCommandTest, SourceCustodyRecoveryTest and hosted policy tests cover actual crash/reentry authority. | [source entry contract](../reference/source-entry-contract.md) |
| C01 | CorrelationMapping/InitialStartRecord/DeploymentEntryIntent; selector, initial admission and business-history adapter contracts persist one generated or explicit context. | [business correlation operations](../reference/business-correlation-operations.md) |
| C02 | Actual exposed event selectors; CorrelationSelectorTest, AmqpCorrelationHeaderProtocolTest and LakehouseExampleBrowserTest cover pointer/header/body agreement. | [business correlation operations](../reference/business-correlation-operations.md) |
| C03 | Bounded declared text-mail transform then audited derive; MailTextCorrelationRecipeTest executes real IMAPS, parser, SQLite and valid/refused cases. | [business correlation operations](../reference/business-correlation-operations.md) |
| C04 | CorrelationSelector and shared preview implementation; pure selector tests, DeploymentHistoryHttpTest and actual editor preview browser cover missing/null/object/oversize/conflict. | [business correlation operations](../reference/business-correlation-operations.md) |
| C05 | BusinessScope and scoped reads/cursors; BusinessHistoryContract, DeploymentHistoryHttpTest and source/embedded authority tests isolate tenants/deployments/versions. | [api cli](../reference/api-cli.md) |
| C06 | CorrelationHeaderMapping and explicit inbound selectors; real RabbitMQ/SMTP/HTTP protocol tests and actual lakehouse callbacks preserve separate transport identity. | [business correlation operations](../reference/business-correlation-operations.md) |
| C07 | Immutable NodeMessage context and explicit derived handoff; AuthoredCorrelationRuntimeTest, SagaApplicationContextRecoveryTest and continuation tests preserve current typed context over stale metadata. | [business correlation operations](../reference/business-correlation-operations.md) |
| C08 | Normalized scoped metadata and finite quotas; BusinessHistoryContract interleaves 1,000 identities; DeploymentHistoryBrowserTest uses 2,000 actual retained histories. | [configuration](../reference/configuration.md) |
| C09 | Open business history independent of active traversals; business-history adapter reopen tests and actual lakehouse reconnect retain waiting state and accept later same-story callbacks. | [execution identity](../reference/execution-identity.md) |
| J01 | CorrelatedJoinRecord distinct manifest/count and deterministic outbox; CorrelatedJoinRuntimeRecoveryTest and lakehouse actual six-result path release once. | [business correlation operations](../reference/business-correlation-operations.md) |
| J02 | Contribution identity/digest deduplication; CorrelatedJoinContract and five actual duplicate lakehouse transport deliveries cannot fill missing members. | [business correlation operations](../reference/business-correlation-operations.md) |
| J03 | Fenced CAS READY and deterministic continuation; CorrelatedJoinRuntimeRecoveryTest concurrently completes final arrivals and recovers a committed READY after response loss/reopen. | [business correlation operations](../reference/business-correlation-operations.md) |
| J04 | Trusted producer entry plus monotone manifest/seal; CorrelatedJoinRuntimeRecoveryTest covers results before manifest, delayed seal, forged authority and pre-manifest caps. | [business correlation operations](../reference/business-correlation-operations.md) |
| J05 | Immutable contribution digests/closed-history admission; correlated join and business closure contracts refuse conflicting manifests, duplicate content and late closed events. | [business correlation operations](../reference/business-correlation-operations.md) |
| J06 | CorrelatedJoinKey explicit round; adapter contracts keep old-round arrivals separate and retries retain original round/context. | [business correlation operations](../reference/business-correlation-operations.md) |
| J07 | Deployment lifecycle generation and durable join phase; CorrelatedJoinRuntimeRecoveryTest covers READY recovery, cancellation, hold/resume, deadline, drain and retired contexts. | [business correlation operations](../reference/business-correlation-operations.md) |
| J08 | Existing JoinCoordinator/iteration identity; engine TCK, fan-in/liveness and graph-runtime regression suites retain traversal-local join semantics. | [source entry contract](../reference/source-entry-contract.md) |
| O01 | DeploymentHistoryRequest shared projection; DeploymentHistoryHttpTest, DeploymentHistoryCliParityTest and signed three-origin embed browser expose equivalent authorized facts. | [api cli](../reference/api-cli.md) |
| O02 | History availability and ActivityCapturePolicy; history adapter/HTTP tests and actual inspector browser keep legacy, capture-disabled, redacted/expired content explicit. | [api cli](../reference/api-cli.md) |
| O03 | Shared persistent inspector; native deployment-history-monitor.spec.js and embedded browser select actual node, visit, attempt and causal parent independently. | [execution identity](../reference/execution-identity.md) |
| O04 | Shared pinned renderer and lazy traversal selection; actual lakehouse and native/embed browser show repeated original topology with independent highlights. | [execution identity](../reference/execution-identity.md) |
| O05 | Actual traversal/visit/attempt summaries and parent IDs; loop/retry fixtures, same-process distinct traversals and native/embed geometry tests avoid fabricated linearity. | [execution events](../reference/execution-events.md) |
| O06 | Captured source/selection generation and post-transport checks; deployment-history-model tests and hostile browser delayed-abort/focus cases fence stale decoration. | [execution identity](../reference/execution-identity.md) |
| O07 | SQL keyset pages/exact server search, max two previews and finite cursor trail; HTTP1,000-page, native2,000-history and 300-page GC browser measurements bound work. | [configuration](../reference/configuration.md) |
| O08 | Captured immutable deployment pin and explicit draft divergence; deployment-history-monitor.spec.js edits a draft and sees the notice inside Monitoring without redeploy or START. | [execution identity](../reference/execution-identity.md) |
| O09 | Origin document persistence and read-only reconcile/retry; first-Run restart/focus/managed successor browsers and initial503 retry cases preserve association without activation on reload. | [api cli](../reference/api-cli.md) |
| O10 | Shared localized controls, persistent inspector and responsive surface; native/embed browser asserts keyboard/touch hit targets, visible graph geometry, narrow contrast and locale refresh. | [execution identity](../reference/execution-identity.md) |
| D01 | LakehouseExampleBrowserTest executes managed HTTP, Pekko, SQLite and explicit external processor fixture with concurrent filenames, missing/complete results and actual browser capture. | [source entry contract](../reference/source-entry-contract.md) |
| D02 | Public mixed, text-mail and six-partition GraphML; actual RabbitMQ/SMTP, IMAPS and managed HTTP tests execute their declared behavior; lakehouse screenshots show the delivered UI. | [source entry contract](../reference/source-entry-contract.md) |
| R01 | SQLite41/PostgreSQL21 additive schema and adapter contracts; populated old40/20 upgrade/reopen/downgrade-refusal tests also restore real pre-upgrade SQLite/pg_dump backups. | [configuration](../reference/configuration.md) |
| R02 | Compiled SDK/strict DocLint, generated OpenAPI/catalog/source inventory, event/config references and configurator Java fixtures are explicit contract gates. ExecutionEventWireJsonTest and StableEdgeIdWireContractTest cover complete-frame bounds without truncating retained context or stable edges. | [api cli](../reference/api-cli.md) |
| R03 | The final candidate requires the repository full workflow on its exact SHA and independent review; feature tests do not substitute for release/merge gates. | [execution identity](../reference/execution-identity.md) |

## Executed examples and measurement boundaries

- [Six correlated callbacks](../examples/correlation/lakehouse.md): actual server/runtime/store and
  Chromium, with an explicit external HTTP processor fixture; it does not run Apache Spark.
- [Mixed startup and four AMQP sources](../examples/correlation/mixed.md): actual RabbitMQ, HTTP,
  SMTP, Pekko and SQLite, including source boundaries and finite traversal budgets.
- [Text email context](../user-guide/email-business-context.md): actual IMAPS, bounded explicit JSON
  parsing and audited derivation.
- [SOURCE inventory](../reference/source-entry-contract.md): compiled ownership/nature/emission gates
  plus connector-specific verification, custody, reply, checkpoint and lifecycle proof.
- [Populated migration and rollback](../operator-guide/correlation-migration.md): actual old-schema
  data and separate restored backup databases, rather than a version-number-only assertion.

The metadata API maximum-page test measures 1,000 projected rows (352,156 bytes) without aggregate
loading. Separate actual-server Chromium tests retain 2,000 histories, 1,000 loop visits and 2,000
attempts, including operator page cap one and reopened SQLite. Shared-component hostile transport
tests exercise 300 replaceable pages and garbage-collection checks for retired row references. These
are separate measurements with different purposes; a store-only page test is not browser proof.
Each browser harness writes request/page/DOM/latency observations alongside screenshots.

Exact server search supports application ID and non-secret public reference; it is not fuzzy text
search. The monitor keeps at most two lazy interactive previews and uses explicit bounded coverage
paging. Unloaded activity is unknown, not a fabricated zero. Optional activity capture/redaction and
retained input custody are separate contracts. Real POST/SMTP effects remain subject to existing
ambiguity/recovery rules; successful fixture execution is not an exactly-once external guarantee.

Pekko and engine-independent gates qualify the normal distribution. The optional licensed Akka
profile requires its declared dependency and its mirrored contracts to be available in the licensed
build environment; default-suite success does not claim that optional profile executed.

## Target 0.9.0-alpha.1

The breaking change fragment and [migration guide](../operator-guide/correlation-migration.md) are
inputs to normal release preparation. Do not increment versions or create a tag in a feature branch.
The owner’s release classification drives `scripts/prepare_release.py`; promotion, signing and
publication still follow the [release procedure](../governance/releasing.md). A feature merge or this
qualification map does not assert that 0.9.0-alpha.1 has shipped.
