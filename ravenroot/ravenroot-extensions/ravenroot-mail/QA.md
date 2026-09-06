# Mail extension QA

The mail extension tests keep protocol coverage separate from tests of internal resource and
concurrency boundaries. Real GreenMail IMAPS/SMTP tests prove TLS setup, Jakarta Mail integration,
message projection, and cleanup. Controlled in-memory adapters and latch-gated sockets prove exact
failure classifications and ordering without asking a live transport or a deadline watchdog to win
a scheduler race.

## One-shot IMAP query boundaries

`BoundedUidScannerTest` drives the same package-private UID-window traversal used by
`MailImapQueryNodeBehavior`. Its controlled mailbox proves that a window which would exceed the
configured scan budget fails with `RESOURCE_LIMIT` before server search or row mapping, and that a
narrower UID range still reaches the target. If the resource check is removed or moved after search,
the test fails. The scanner tests also pin ascending result order, next-page detection, the sparse
mailbox window cap, and the `Long.MAX_VALUE` window boundary.

`MailImapQueryNodeBehaviorIntegrationTest` retains the real IMAPS adapter coverage. Its
`HoldingServer` transport test uses explicit connection and release gates and asserts
`TRANSPORT_FAILURE` separately. Large live mailboxes are not used to force the internal scan-budget
branch: transport and watchdog outcomes on that shape depend on CPU scheduling and do not identify a
resource-boundary regression.

## Sibling timing and race audit

The test sources were audited for sleeps, spin waits, latches, repeated/concurrent execution, and
timed future waits. These are the coordination patterns in the module:

| Pattern | Test sources | Assessment |
|---|---|---|
| Latch-gated admission and release | `ConcurrencyGateThresholdTest`, `MailAdmissionDoubleReleaseTest`, `MailConcurrencyAdmissionTest`, `MailImapCredentialIsolationTest` | State transitions are explicitly gated; every wait is bounded. |
| Latch-gated protocol fixtures | `DeterministicSmtpFixture`, `DeterministicMutationImapFixture`, `DeterministicSlowDripImapFixture`, `DeterministicStartTlsImapFixture`, `ImapConsumerTestSupport`, `MailImapQueryNodeBehaviorIntegrationTest` | Failure points are controlled by protocol events; waits are bounded. |
| Repeated concurrent transport test | `MailImapQueryNodeBehaviorIntegrationTest` | The `HoldingServer` test repeats eight times with a fixed connection gate and exact transport classification. |
| Deadline-bounded future waits | `AngusImapConsumerProtocolTest`, `InstalledMailImapConsumeContainerTest`, `MailImapConsumeContractTest`, `MailImapConsumeGreenMailTest`, plus the admission/query tests above | Timeouts bound test completion and do not retry an operation; exact-outcome policies are described below. |
| Deadline-bounded state observation | `InstalledMailImapConsumeContainerTest`, `MailImapConsumeContractTest`, `MailImapConsumeGreenMailTest` | Spin waits have explicit monotonic deadlines and final assertions. |
| Deliberate protocol delay | `DeterministicMutationImapFixture`, `DeterministicSlowDripImapFixture` | Sleeps generate a server-side delayed or slow-drip response; the client transition is synchronized by latches. |
| Interrupt-resistant resolver probe | `MailImapCredentialIsolationTest` | A 10 ms sleep occurs only inside a monotonic-deadline observation loop after latch-controlled resolver release. |

The audit also found exact classifications that intentionally remain coupled to real IMAPS:

| Test | Exact outcome | Why it remains live | Residual scheduling exposure |
|---|---|---|---|
| `mimeDepthAndAddressBudgetsFailTypedAndReleaseResourcesForRecovery` | Four `RESOURCE_LIMIT` cases for MIME depth, part count, decoded bytes, and address count | The assertions cover Jakarta Mail decoding and prove that the live session can recover after the typed failure. | Severe host starvation can let the watchdog classify `TIMEOUT` before message projection reaches the resource check. The 10 second profile budget reduces that exposure but is not deterministic coordination. |
| `fullContentModeFailsTypedAtEveryLimitWithoutReturningAPartialBody` | Three `RESOURCE_LIMIT` cases for full-body bytes, depth, and part count | The assertions prove the real provider never returns partial full content at those limits. | It has the same remaining watchdog-before-projection exposure. |
| `rejectsFractionalNonFiniteOverflowAndStaleCursorNumbersExactly` | `INVALID_INPUT` for live cursor fields | The live cases combine numeric validation with the folder's actual UID validity; the payload-only numeric cases in the same test are transport-free. | The live cursor cases can fail at transport setup before cursor validation under severe starvation. |

These retained cases test provider integration, while `BoundedUidScannerTest` removes transport from
the exact scan-budget classification addressed by issue 142. The wrong-host and sanitized transport
tests deliberately accept either `TRANSPORT_FAILURE` or `TIMEOUT`: both labels describe the same
refused or delayed transport event at opposite sides of the watchdog boundary. No test accepts a
transport/deadline code as an alternative to `RESOURCE_LIMIT`, and no neighboring test uses an
unbounded sleep or an outcome-changing retry. New exact internal-classification tests should use a
controlled failure point and keep real protocol integration as separate evidence.
