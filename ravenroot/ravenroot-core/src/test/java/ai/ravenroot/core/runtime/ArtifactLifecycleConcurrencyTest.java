package ai.ravenroot.core.runtime;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactLifecycleConcurrencyTest {
    @Test
    void simultaneousIndependentApproversProduceExactlyOneApprovedRevisionAndEvidenceWinner() throws Exception {
        var registry = new InMemoryArtifactRegistry();
        var application = application(registry, new ControllableRuntime());
        GeneratedArtifact created = application.createProgramArtifact("javascript", "source", Map.of(
                AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a",
                AuthorizedRavenrootApplication.CREATOR_METADATA, "issuer|USER|alice"));
        registry.transition(created.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        GeneratedArtifact tested = registry.transition(created.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        var lifecycleAudit = new CopyOnWriteArrayList<ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent>();
        var facade = new AuthorizedRavenrootApplication(application, new DefaultAuthorizationService(event -> { }),
                lifecycleAudit::add, true);
        var start = new CyclicBarrier(2);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var bob = executor.submit(() -> approveAfter(start, facade, approver("bob"), tested.id(), "bob review"));
            var carol = executor.submit(() -> approveAfter(start, facade, approver("carol"), tested.id(), "carol review"));
            List<ApprovalAttempt> attempts = List.of(bob.get(5, TimeUnit.SECONDS), carol.get(5, TimeUnit.SECONDS));

            assertEquals(1, attempts.stream().filter(ApprovalAttempt::approved).count());
            assertEquals(1, attempts.stream().filter(attempt -> attempt.failure() instanceof IllegalStateException).count(),
                    "the loser must observe the completed TESTED-to-APPROVED transition, not succeed twice");
            GeneratedArtifact finalArtifact = registry.find(tested.id()).orElseThrow();
            assertEquals(ArtifactState.APPROVED, finalArtifact.state());
            assertEquals(tested.revision() + 1, finalArtifact.revision());
            String winningApprover = attempts.stream().filter(ApprovalAttempt::approved).findFirst().orElseThrow().subject();
            assertEquals("issuer|USER|" + winningApprover,
                    finalArtifact.metadata().get(AuthorizedRavenrootApplication.APPROVER_METADATA));
            assertEquals(winningApprover + " review", finalArtifact.metadata().get("evidence.approved.reason"));
            // Both calls are auditable attempts, but only one may mutate state. SEC-13 requires the
            // losing attempt to record its failure: leaving its ATTEMPT as the trail's last word makes
            // the audit sink report it as
            // ALLOWED: a permanent claim that an approval which never happened had taken effect.
            // SEC-12 closes both callers: the loser by FAILED and the winner by SUCCEEDED. Without
            // that transition, the
            // winner's approval is evidenced only by an ATTEMPT that
            // nothing contradicted — an inference from absence, and indistinguishable from a process
            // killed mid-approval. Binding an approver immutably to an
            // artifact cannot rest on that.
            assertEquals(4, lifecycleAudit.size(),
                    "two attempts, one terminal failure for the approver that lost the race, and one "
                            + "terminal success for the one that won");
            assertEquals(2, lifecycleAudit.stream().filter(event -> event.disposition()
                    == ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.ATTEMPT).count(),
                    "each caller's intent is recorded before the mutation is tried");
            assertEquals(1, lifecycleAudit.stream().filter(event -> event.disposition()
                    == ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.FAILED).count(),
                    "exactly one approval failed, and exactly one failure is on the trail");
            var succeeded = lifecycleAudit.stream().filter(event -> event.disposition()
                    == ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.SUCCEEDED).toList();
            assertEquals(1, succeeded.size(),
                    "exactly one approval took effect, and the trail must say so positively rather "
                            + "than leave it to be inferred from an unclosed attempt");
            assertEquals(winningApprover, succeeded.get(0).subject(),
                    "the success record must name the approver who actually won the race");
            assertEquals(ArtifactState.APPROVED, succeeded.get(0).state(),
                    "the terminal record carries the state reached, not the state on entry");
            assertTrue(lifecycleAudit.stream().noneMatch(event -> event.disposition()
                    == ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.DENIED),
                    "losing a race is a platform failure, not a policy denial: both approvers were "
                            + "independent actors and neither was refused by the dual-control gate");
        }
    }

    @Test
    void validationReservationPreventsDuplicateRuntimeSideEffects() {
        var registry = new InMemoryArtifactRegistry();
        var runtime = new ControllableRuntime();
        var application = application(registry, runtime);
        GeneratedArtifact artifact = application.createProgramArtifact("javascript", "source", Map.of());

        CompletionStage<GeneratedArtifact> first = application.validateProgramArtifact(artifact.id());
        assertThrows(IllegalStateException.class, () -> application.validateProgramArtifact(artifact.id()));
        assertEquals(1, runtime.validationCalls.get());

        runtime.validation.complete(null);
        assertEquals(ArtifactState.VALIDATED, first.toCompletableFuture().join().state());
    }

    @Test
    void failedValidationReleasesReservationForAControlledRetry() {
        var registry = new InMemoryArtifactRegistry();
        var runtime = new ControllableRuntime();
        var application = application(registry, runtime);
        GeneratedArtifact artifact = application.createProgramArtifact("javascript", "source", Map.of());

        CompletionStage<GeneratedArtifact> failed = application.validateProgramArtifact(artifact.id());
        runtime.validation.completeExceptionally(new IllegalArgumentException("invalid"));
        assertThrows(java.util.concurrent.CompletionException.class, () -> failed.toCompletableFuture().join());

        runtime.validation = new CompletableFuture<>();
        CompletionStage<GeneratedArtifact> retry = application.validateProgramArtifact(artifact.id());
        runtime.validation.complete(null);
        assertEquals(2, runtime.validationCalls.get());
        assertEquals(ArtifactState.VALIDATED, retry.toCompletableFuture().join().state());
    }

    @Test
    void testReservationPreventsDuplicateProgramExecution() {
        var registry = new InMemoryArtifactRegistry();
        var runtime = new ControllableRuntime();
        var application = application(registry, runtime);
        GeneratedArtifact artifact = application.createProgramArtifact("javascript", "source", Map.of());
        runtime.validation.complete(null);
        application.validateProgramArtifact(artifact.id()).toCompletableFuture().join();

        var first = application.testProgramArtifact(artifact.id(), "payload");
        assertThrows(IllegalStateException.class, () -> application.testProgramArtifact(artifact.id(), "payload"));
        assertEquals(1, runtime.testCalls.get());

        runtime.test.complete("output");
        assertEquals(ArtifactState.TESTED, first.toCompletableFuture().join().artifact().state());
    }

    @Test
    void synchronousValidationFailureReleasesReservationForRetry() {
        var registry = new InMemoryArtifactRegistry();
        var runtime = new ControllableRuntime();
        runtime.throwValidationSynchronously = true;
        var application = application(registry, runtime);
        GeneratedArtifact artifact = application.createProgramArtifact("javascript", "source", Map.of());

        assertThrows(IllegalStateException.class, () -> application.validateProgramArtifact(artifact.id()));
        runtime.throwValidationSynchronously = false;
        runtime.validation.complete(null);

        assertEquals(ArtifactState.VALIDATED,
                application.validateProgramArtifact(artifact.id()).toCompletableFuture().join().state());
    }

    @Test
    void synchronousTestFailureAllowsRetryOrRetirement() {
        var registry = new InMemoryArtifactRegistry();
        var runtime = new ControllableRuntime();
        var application = application(registry, runtime);
        GeneratedArtifact artifact = application.createProgramArtifact("javascript", "source", Map.of());
        runtime.validation.complete(null);
        application.validateProgramArtifact(artifact.id()).toCompletableFuture().join();
        runtime.throwTestSynchronously = true;

        assertThrows(IllegalStateException.class, () -> application.testProgramArtifact(artifact.id(), "payload"));
        runtime.throwTestSynchronously = false;
        runtime.test.complete("output");
        assertEquals(ArtifactState.TESTED,
                application.testProgramArtifact(artifact.id(), "payload").toCompletableFuture().join()
                        .artifact().state());

        GeneratedArtifact retireable = application.createProgramArtifact("javascript", "other", Map.of());
        application.validateProgramArtifact(retireable.id()).toCompletableFuture().join();
        runtime.throwTestSynchronously = true;
        assertThrows(IllegalStateException.class,
                () -> application.testProgramArtifact(retireable.id(), "payload"));
        assertEquals(ArtifactState.RETIRED, application.retireProgramArtifact(retireable.id(),
                Map.of("reason", "failed test")).state());
    }

    @Test
    void operationsOnDifferentArtifactsDoNotShareOneLifecycleMonitor() throws Exception {
        var registry = new InMemoryArtifactRegistry();
        GeneratedArtifact first = registry.create("javascript", "first", Map.of());
        GeneratedArtifact second;
        do {
            second = registry.create("javascript", java.util.UUID.randomUUID().toString(), Map.of());
        } while (stripe(first.id()) == stripe(second.id()));
        GeneratedArtifact independent = second;

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Map<String, String> blockingEvidence = new java.util.AbstractMap<>() {
            @Override public java.util.Set<Entry<String, String>> entrySet() {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return Set.of();
            }
        };
        var firstTransition = CompletableFuture.runAsync(() ->
                registry.transition(first.id(), ArtifactState.GENERATED, ArtifactState.RETIRED, blockingEvidence));
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(1, TimeUnit.SECONDS));

        var secondTransition = CompletableFuture.supplyAsync(() ->
                registry.transition(independent.id(), ArtifactState.GENERATED, ArtifactState.RETIRED));
        assertEquals(ArtifactState.RETIRED, secondTransition.get(1, TimeUnit.SECONDS).state());
        release.countDown();
        firstTransition.get(1, TimeUnit.SECONDS);
    }

    private static int stripe(String id) {
        return (id.hashCode() & Integer.MAX_VALUE) % 64;
    }

    private static ApprovalAttempt approveAfter(CyclicBarrier start, AuthorizedRavenrootApplication facade,
                                                RequestContext context, String id, String reason) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            facade.approveProgramArtifact(context, id, reason);
            return new ApprovalAttempt(context.subject(), null);
        } catch (RuntimeException failure) {
            return new ApprovalAttempt(context.subject(), failure);
        }
    }

    private static RequestContext approver(String subject) {
        return new RequestContext("request-" + subject, subject, PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.APPROVER), Set.of("ravenroot.artifact.approve"));
    }

    private record ApprovalAttempt(String subject, Throwable failure) {
        boolean approved() {
            return failure == null;
        }
    }

    private static DefaultRavenrootApplication application(InMemoryArtifactRegistry registry,
                                                            ProgramRuntime runtime) {
        return new DefaultRavenrootApplication(null, new ExecutionMonitor(), new BehaviorRegistry(),
                registry, runtime);
    }

    private static final class ControllableRuntime implements ProgramRuntime {
        private final AtomicInteger validationCalls = new AtomicInteger();
        private final AtomicInteger testCalls = new AtomicInteger();
        private CompletableFuture<Void> validation = new CompletableFuture<>();
        private CompletableFuture<Object> test = new CompletableFuture<>();
        private boolean throwValidationSynchronously;
        private boolean throwTestSynchronously;

        @Override public String id() { return "controlled-test-runtime"; }
        @Override public CompletionStage<Void> validate(GeneratedArtifact artifact) {
            validationCalls.incrementAndGet();
            if (throwValidationSynchronously) throw new IllegalStateException("synchronous validation failure");
            return validation;
        }
        @Override public CompletionStage<Object> test(GeneratedArtifact artifact, ProgramRequest request) {
            testCalls.incrementAndGet();
            if (throwTestSynchronously) throw new IllegalStateException("synchronous test failure");
            return test;
        }
        @Override public CompletionStage<Object> execute(ai.ravenroot.api.programming.ProgramAdmission admission,
                                                         ProgramRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
