package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semantics every {@link EmbedRegistrationAuthority} adapter owes, exercised against the
 * in-memory reference.
 *
 * <p>{@code SqliteEmbedRegistrationStoreTest} asserts the same properties against the durable
 * adapter. Two suites rather than one shared contract test because the durable one lives in a module
 * this one cannot see; where they overlap the assertions are deliberately worded the same, so a
 * divergence between the adapters shows up as one suite going red and not as a silent difference.</p>
 */
class EmbedRegistrationAuthorityTest {

    @Test
    void provisionAcceptsOnlyAPublishedOrActiveSnapshotWithCoherentIdentity() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var base = EmbedFixtures.command(0, "sha256:a", "start");

        assertInstanceOf(EmbedProvisionOutcome.Provisioned.class, authority.provision(base));

        // Every refusal below is a well-formed command whose content the rules reject.
        assertEquals(EmbedProvisionOutcome.Reason.ELIGIBILITY_DENIED, reasonOf(new InMemoryEmbedRegistrationAuthority()
                .provision(withEligibility(base, new EmbedProjectionEligibility(EmbedFixtures.POLICY,
                        true, true, true, true, true, false, true)))));
        assertEquals(EmbedProvisionOutcome.Reason.IDENTITY_INCOHERENT,
                reasonOf(new InMemoryEmbedRegistrationAuthority().provision(
                        withProjection(base, EmbedFixtures.projection("sha256:DIFFERENT", "start")))));
        assertEquals(EmbedProvisionOutcome.Reason.CAPABILITY_MISSING,
                reasonOf(new InMemoryEmbedRegistrationAuthority().provision(
                        withCapabilities(base, Set.of()))));
    }

    /**
     * {@link EmbedSnapshotLifecycle} has only the two admissible members, so «not PUBLISHED/ACTIVE» is
     * unrepresentable at the port. The gate that keeps a VALIDATED or RETIRED version from becoming one
     * of them lives in {@code EmbedSnapshotProjector}, which is where the control-plane type is known.
     */
    @Test
    void theLifecycleTypeAdmitsOnlyPublishedAndActive() {
        assertEquals(List.of(EmbedSnapshotLifecycle.PUBLISHED, EmbedSnapshotLifecycle.ACTIVE),
                List.of(EmbedSnapshotLifecycle.values()));
    }

    /**
     * The revision a command writes is always the one after the revision it expects.
     *
     * <p>Asserted directly on {@link EmbedProvisionCommand#nextRevision()}, and asserted <em>first</em>,
     * for a reason worth recording. The store-level monotonicity assertion further down does go red if
     * the expected-plus-one step is removed -- but not on its own assertion: with {@code nextRevision()} returning
     * the expected revision, a first provision produces revision 0 and {@code VerifiedEmbedSessionGrant}'s
     * "revision must be positive" guard throws before any assertion runs. That is a real consequence
     * and it is not the proof this test claims to give. Here the first statement to execute is the
     * assertion itself, so removing that step fails it rather than tripping a guard elsewhere.</p>
     */
    @Test
    void everyProvisionWritesTheRevisionAfterTheOneItExpects() {
        for (long expected : new long[] {0, 1, 2, 7, 41}) {
            var command = EmbedFixtures.command(expected, "sha256:r" + expected, "start");
            assertEquals(expected + 1, command.nextRevision(),
                    "a provision expecting revision " + expected + " must write " + (expected + 1));
        }
        // Only after the arithmetic is pinned: the same property observed through the aggregate the
        // command builds, which is what the store actually persists.
        var aggregate = EmbedFixtures.command(7, "sha256:r7", "start").aggregateAt(EmbedFixtures.AT);
        assertEquals(8, aggregate.revision());
        assertEquals(8, aggregate.sessionGrant().revision());
    }

    @Test
    void compareAndSetAdmitsOneWriterAndRejectsStaleOrDuplicateRevisions() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        assertInstanceOf(EmbedProvisionOutcome.Conflict.class,
                authority.provision(EmbedFixtures.command(1, "sha256:a", "start")),
                "a first provision must expect revision 0");

        var first = provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));
        assertEquals(1, first.revision());

        var stale = authority.provision(EmbedFixtures.command(0, "sha256:b", "start"));
        var conflict = assertInstanceOf(EmbedProvisionOutcome.Conflict.class, stale);
        assertEquals(0, conflict.expectedRevision());
        assertEquals(1, conflict.currentRevision());

        var second = provisioned(authority, EmbedFixtures.command(1, "sha256:b", "start"));
        assertEquals(2, second.revision());
        assertTrue(second.revision() > first.revision(), "revisions increase monotonically");
    }

    @Test
    void concurrentProvisionsOfTheSameRevisionProduceExactlyOneWriter() throws Exception {
        var authority = new InMemoryEmbedRegistrationAuthority();
        provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));

        int writers = 8;
        var barrier = new CyclicBarrier(writers);
        var tasks = new ArrayList<Callable<EmbedProvisionOutcome>>();
        for (int index = 0; index < writers; index++) {
            String digest = "sha256:w" + index;
            tasks.add(() -> {
                barrier.await();
                return authority.provision(EmbedFixtures.command(1, digest, "start"));
            });
        }
        List<EmbedProvisionOutcome> outcomes;
        try (var pool = Executors.newFixedThreadPool(writers)) {
            outcomes = new ArrayList<>();
            for (Future<EmbedProvisionOutcome> future : pool.invokeAll(tasks)) outcomes.add(future.get());
        }

        long winners = outcomes.stream().filter(EmbedProvisionOutcome.Provisioned.class::isInstance).count();
        assertEquals(1, winners, "exactly one writer may win a compare-and-set on revision 1");
        assertEquals(writers - 1,
                outcomes.stream().filter(EmbedProvisionOutcome.Conflict.class::isInstance).count());
    }

    @Test
    void revocationIsMonotoneTerminalAndImmediatelyVisibleToACapturedGrant() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var captured = provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));
        assertTrue(authority.isCurrent(captured));

        var revoked = assertInstanceOf(EmbedRevokeOutcome.Revoked.class,
                authority.revoke(new EmbedRevokeCommand(EmbedFixtures.REGISTRATION,
                        EmbedFixtures.TENANT, captured.revision())));
        assertEquals(captured.revision() + 1, revoked.revision());

        assertFalse(authority.isCurrent(captured),
                "a ticket, bearer or acknowledgement holding the pre-revocation aggregate is dead");
        assertInstanceOf(EmbedProjectionResolution.Unavailable.class,
                authority.resolveProjection(captured, EmbedProjectionBudget.DEFAULTS));
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION));

        // Terminal: neither a matching-revision provision nor a lower one brings it back.
        assertEquals(EmbedProvisionOutcome.Reason.REGISTRATION_REVOKED,
                reasonOf(authority.provision(EmbedFixtures.command(revoked.revision(), "sha256:c", "start"))));
        assertEquals(EmbedProvisionOutcome.Reason.REGISTRATION_REVOKED,
                reasonOf(authority.provision(EmbedFixtures.command(0, "sha256:c", "start"))));
        assertInstanceOf(EmbedRevokeOutcome.AlreadyRevoked.class,
                authority.revoke(new EmbedRevokeCommand(EmbedFixtures.REGISTRATION,
                        EmbedFixtures.TENANT, revoked.revision())));
    }

    @Test
    void revocationRefusesAStaleExpectedRevisionAndAForeignTenant() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var captured = provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));

        assertInstanceOf(EmbedRevokeOutcome.Conflict.class,
                authority.revoke(new EmbedRevokeCommand(EmbedFixtures.REGISTRATION,
                        EmbedFixtures.TENANT, captured.revision() + 5)));
        assertInstanceOf(EmbedRevokeOutcome.NotFound.class,
                authority.revoke(new EmbedRevokeCommand(EmbedFixtures.REGISTRATION,
                        "tenant-b", captured.revision())));
        assertTrue(authority.isCurrent(captured), "a refused revocation changed nothing");
    }

    /**
     * The property under test is not «the read is fast» but «the grant and the payload came from one
     * revision». A reader captures revision 1, a writer replaces it with revision 2 carrying a
     * different payload, and the reader then projects. Two answers are legal — the captured payload
     * (if the read won) or nothing (if the write did) — and exactly one is not: revision 1's grant
     * with revision 2's nodes.
     *
     * <p>This fails if {@code resolveProjection} is overridden to look the payload up again.</p>
     */
    @Test
    void aGrantIsNeverPairedWithAnotherRevisionsSnapshot() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var authority = new InMemoryEmbedRegistrationAuthority();
            var captured = provisioned(authority, EmbedFixtures.command(0, "sha256:a", "old-node"));
            var barrier = new CyclicBarrier(2);

            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<EmbedProjectionResolution> reader = pool.submit(() -> {
                    barrier.await();
                    return authority.resolveProjection(captured, EmbedProjectionBudget.DEFAULTS);
                });
                Future<EmbedProvisionOutcome> writer = pool.submit(() -> {
                    barrier.await();
                    return authority.provision(EmbedFixtures.command(1, "sha256:b", "new-node"));
                });
                assertInstanceOf(EmbedProvisionOutcome.Provisioned.class, writer.get());
                EmbedProjectionResolution read = reader.get();
                if (read instanceof EmbedProjectionResolution.Available available) {
                    assertEquals(captured.graphGrant().canonicalDigest(),
                            available.projection().canonicalDigest(),
                            "the payload served must belong to the captured revision");
                    assertEquals(List.of("old-node"), available.projection().nodes().stream()
                                    .map(EmbedGraphProjection.Node::id).toList(),
                            "revision 1's grant was served revision 2's nodes");
                } else {
                    assertInstanceOf(EmbedProjectionResolution.Unavailable.class, read);
                }
            }
        }
    }

    /** One identity travels from creation through acknowledgement to projection, unchanged. */
    @Test
    void issuerSubjectTenantRegistrationAndRevisionCoincideAcrossTheWholeFlow() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var provisioned = provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));

        var created = assertInstanceOf(EmbedRegistrationResolution.Available.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION)).aggregate();
        assertEquals(provisioned, created);

        var grant = created.sessionGrant();
        assertEquals(EmbedFixtures.ISSUER, grant.workloadIssuer());
        assertEquals(EmbedFixtures.SUBJECT, grant.workloadSubject());
        assertEquals(EmbedFixtures.TENANT, grant.tenantId());
        assertEquals(EmbedFixtures.REGISTRATION, grant.registrationId());
        assertEquals(created.revision(), grant.revision());
        assertEquals(created.graphGrant().canonicalDigest(), created.projection().canonicalDigest());

        // The acknowledgement and exchange stages re-check currency against this same instance.
        assertTrue(authority.isCurrent(created));
        var projection = assertInstanceOf(EmbedProjectionResolution.Available.class,
                authority.resolveProjection(created, EmbedProjectionBudget.DEFAULTS)).projection();
        assertEquals(grant.graphGrant().graphId(), projection.graphId());
        assertEquals(grant.graphGrant().graphVersionId(), projection.graphVersionId());
        assertEquals(grant.graphGrant().canonicalDigest(), projection.canonicalDigest());
    }

    @Test
    void aMismatchedWorkloadCannotResolveAnotherRegistration() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));

        for (RequestContext wrong : List.of(
                context("other-tenant", EmbedFixtures.ISSUER, EmbedFixtures.SUBJECT),
                context(EmbedFixtures.TENANT, "https://other-issuer.example", EmbedFixtures.SUBJECT),
                context(EmbedFixtures.TENANT, EmbedFixtures.ISSUER, "other-workload"))) {
            assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                    authority.resolveCurrent(wrong, EmbedFixtures.REGISTRATION));
        }
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), "some-other-registration"));
    }

    /** A captured aggregate whose revision number was forged does not satisfy the currency check. */
    @Test
    void currencyComparesTheWholeAggregateAndNotOnlyTheRevisionCounter() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var captured = provisioned(authority, EmbedFixtures.command(0, "sha256:a", "start"));

        var forged = EmbedFixtures.command(0, "sha256:forged", "start").aggregateAt(EmbedFixtures.AT);
        assertEquals(captured.revision(), forged.revision());
        assertNotEquals(captured, forged);
        assertFalse(authority.isCurrent(forged));
    }

    @Test
    void anAggregateCannotBeBuiltWithAPayloadItsGrantDoesNotPinDown() {
        var coherent = EmbedFixtures.aggregate(0, "sha256:a", "start");
        assertThrows(IllegalArgumentException.class, () -> new EmbedRegistrationAggregate(
                coherent.registrationId(), coherent.revision(), coherent.state(), coherent.sessionGrant(),
                coherent.snapshotLifecycle(), coherent.eligibility(),
                EmbedFixtures.projection("sha256:elsewhere", "start"), coherent.provisionedAt()));
        assertThrows(IllegalArgumentException.class, () -> new EmbedRegistrationAggregate(
                "another-registration", coherent.revision(), coherent.state(), coherent.sessionGrant(),
                coherent.snapshotLifecycle(), coherent.eligibility(), coherent.projection(),
                coherent.provisionedAt()));
        assertThrows(IllegalArgumentException.class, () -> new EmbedRegistrationAggregate(
                coherent.registrationId(), coherent.revision() + 1, coherent.state(),
                coherent.sessionGrant(), coherent.snapshotLifecycle(), coherent.eligibility(),
                coherent.projection(), coherent.provisionedAt()));
    }

    @Test
    void aRevokedAggregateCannotBeReanimatedByCallingRevokedAtBackwards() {
        var aggregate = EmbedFixtures.aggregate(0, "sha256:a", "start");
        assertThrows(IllegalArgumentException.class,
                () -> aggregate.revokedAt(aggregate.revision(), EmbedFixtures.AT));
        assertThrows(IllegalArgumentException.class,
                () -> aggregate.revokedAt(aggregate.revision() - 1, EmbedFixtures.AT));
    }

    private static EmbedRegistrationAggregate provisioned(EmbedRegistrationAuthority authority,
                                                          EmbedProvisionCommand command) {
        return assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                authority.provision(command)).aggregate();
    }

    private static EmbedProvisionOutcome.Reason reasonOf(EmbedProvisionOutcome outcome) {
        return assertInstanceOf(EmbedProvisionOutcome.Rejected.class, outcome).reason();
    }

    private static RequestContext context(String tenant, String issuer, String subject) {
        return new RequestContext("request", subject, ai.ravenroot.api.security.PrincipalType.WORKLOAD,
                issuer, tenant, Set.of(ai.ravenroot.api.security.Role.VIEWER),
                Set.of("ravenroot.embed.session.create"));
    }

    private static EmbedProvisionCommand withEligibility(EmbedProvisionCommand base,
                                                          EmbedProjectionEligibility eligibility) {
        return new EmbedProvisionCommand(base.registrationId(), base.expectedRevision(),
                base.workloadIssuer(), base.workloadSubject(), base.tenantId(), base.parentOrigin(),
                base.capabilities(), base.themeOverride(), base.graphGrant(), base.snapshotLifecycle(),
                eligibility, base.projection());
    }

    private static EmbedProvisionCommand withProjection(EmbedProvisionCommand base,
                                                         EmbedGraphProjection projection) {
        return new EmbedProvisionCommand(base.registrationId(), base.expectedRevision(),
                base.workloadIssuer(), base.workloadSubject(), base.tenantId(), base.parentOrigin(),
                base.capabilities(), base.themeOverride(), base.graphGrant(), base.snapshotLifecycle(),
                base.eligibility(), projection);
    }

    private static EmbedProvisionCommand withCapabilities(EmbedProvisionCommand base,
                                                           Set<EmbedCapability> capabilities) {
        return new EmbedProvisionCommand(base.registrationId(), base.expectedRevision(),
                base.workloadIssuer(), base.workloadSubject(), base.tenantId(), base.parentOrigin(),
                capabilities, Optional.empty(), base.graphGrant(), base.snapshotLifecycle(),
                base.eligibility(), base.projection());
    }
}
