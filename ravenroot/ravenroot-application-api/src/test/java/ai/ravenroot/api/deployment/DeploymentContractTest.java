package ai.ravenroot.api.deployment;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariants of the deployment contract types.
 *
 * <p>These are pure types, so the only things that can be wrong are the rules they enforce and the
 * shape they publish. Both are asserted here, and each rule is mutation-checked: removing it turns
 * exactly one case red.
 */
class DeploymentContractTest {
    private static final DeploymentId ID = DeploymentId.of("orders-consumer");

    /**
     * The seam with the operational surface, pinned. {@code DeploymentCapConfiguration} and the
     * runbook's alerting both key on these constants, so adding, removing or renaming one is a
     * contract change to another owner's code and should fail here rather than there.
     */
    @Test
    void publishesExactlyTheSevenAgreedStates() {
        assertEquals(
                List.of("COLD", "STARTING", "READY", "DEGRADED", "STOPPING", "STOPPED", "FAILED"),
                Arrays.stream(DeploymentState.values()).map(Enum::name).toList(),
                "the seven states are the agreed seam with the cap configuration and the operator "
                        + "runbook; changing the set changes another owner's contract");
    }

    /**
     * The cause is for a person, the state is for a probe. A cause on a state that has nothing to
     * explain would invite consumers to read it as significant, which is how a text field becomes a
     * parsed protocol.
     */
    @Test
    void onlyDegradedAndFailedMayCarryACause() {
        assertEquals(Optional.of("broker unreachable"),
                DeploymentStatus.of(ID, DeploymentState.DEGRADED, "broker unreachable").cause());
        assertEquals(Optional.of("startup rolled back"),
                DeploymentStatus.of(ID, DeploymentState.FAILED, "startup rolled back").cause());

        for (DeploymentState state : DeploymentState.values()) {
            if (state == DeploymentState.DEGRADED || state == DeploymentState.FAILED) {
                continue;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> DeploymentStatus.of(ID, state, "should not be accepted"),
                    state + " has nothing to explain and must refuse a cause");
        }
    }

    /** A blank cause is absence, not an empty explanation that a dashboard would render as a gap. */
    @Test
    void treatsABlankCauseAsAbsent() {
        assertEquals(Optional.empty(), DeploymentStatus.of(ID, DeploymentState.FAILED, "   ").cause());
        assertEquals(Optional.empty(), DeploymentStatus.of(ID, DeploymentState.FAILED, null).cause());
        assertEquals(Optional.empty(),
                new DeploymentStatus(ID, DeploymentState.COLD, null).cause());
    }

    /** Exactly one state admits work, and a probe that got this wrong would route into a dead pod. */
    @Test
    void onlyReadyAdmitsIngress() {
        assertTrue(DeploymentStatus.of(ID, DeploymentState.READY).admitting());
        for (DeploymentState state : DeploymentState.values()) {
            if (state == DeploymentState.READY) {
                continue;
            }
            DeploymentStatus status = state == DeploymentState.DEGRADED || state == DeploymentState.FAILED
                    ? DeploymentStatus.of(ID, state, "cause")
                    : DeploymentStatus.of(ID, state);
            assertFalse(status.admitting(), state + " must not admit ingress");
        }
    }

    @Test
    void refusesAnAbsentDeploymentIdentity() {
        assertThrows(IllegalArgumentException.class, () -> DeploymentId.of(""));
        assertThrows(IllegalArgumentException.class, () -> DeploymentId.of("   "));
        assertThrows(NullPointerException.class, () -> DeploymentId.of(null));
    }

    /**
     * The open ingress-topology decision stays open: both shapes are expressible and neither is the
     * default. A {@code start()} that were merely {@code node("start")} would have quietly chosen
     * the arbitrary-node topology.
     */
    @Test
    void expressesBothIngressTopologiesWithoutPrivilegingEither() {
        assertEquals(Optional.empty(), IngressTarget.start().nodeId(),
                "the start target names no node -- the deployment resolves the graph's own start");
        assertEquals(Optional.of("inbound"), IngressTarget.node("inbound").nodeId());
        assertFalse(IngressTarget.start().equals(IngressTarget.node("start")),
                "targeting the graph's start node is not the same as naming a node called 'start'; "
                        + "collapsing them would decide the open topology by accident");
        assertEquals(IngressTarget.node("inbound"), IngressTarget.node("inbound"));
        assertEquals(IngressTarget.node("inbound").hashCode(), IngressTarget.node("inbound").hashCode());
    }

    @Test
    void refusesABlankIngressTargetNode() {
        assertThrows(IllegalArgumentException.class, () -> IngressTarget.node(""));
        assertThrows(IllegalArgumentException.class, () -> IngressTarget.node("  "));
        assertThrows(NullPointerException.class, () -> IngressTarget.node(null));
    }

    /**
     * Phase A declares exactly one overflow policy. The absence of drop-oldest and drop-newest is
     * the contract, not an oversight: dropping presumes redelivery, which is the at-least-once
     * guarantee Phase A does not have.
     */
    @Test
    void declaresRejectAsTheOnlyOverflowPolicyPhaseACanHonour() {
        assertEquals(List.of("REJECT"),
                Arrays.stream(IngressOverflowPolicy.values()).map(Enum::name).toList(),
                "adding a dropping policy here claims redelivery that ADR 0021 D6 places in Phase B");
    }

    /**
     * Every refusal is distinguishable, because the source's remedy differs for each.
     *
     * <p>{@code REJECTED_INSTANCE_BUSY} is <b>unreachable through the
     * product today</b> — every deployment ingress event mints a fresh process instance identifier,
     * so nothing can already hold it. It is listed here anyway, and must stay listed: the remedy for
     * a busy instance differs from every other refusal, and the constant exists so that the branch
     * fails closed rather than executing an instance this runtime does not own. See
     * {@link IngressDisposition#REJECTED_INSTANCE_BUSY} for why it is not dead code.
     */
    @Test
    void distinguishesEveryIngressRefusal() {
        assertEquals(List.of("ACCEPTED", "REJECTED_BUFFER_FULL", "REJECTED_NOT_READY",
                        "REJECTED_ADMISSION_CLOSED", "REJECTED_INSTANCE_BUSY"),
                Arrays.stream(IngressDisposition.values()).map(Enum::name).toList());
    }

    /** Alerting keys on the type; the numbers are for the operator reading it. */
    @Test
    void carriesTheCapAndTheActiveCountOnTheAdmissionRefusal() {
        var refusal = new DeploymentAdmissionException(ID, 8, 8);

        assertEquals(8, refusal.active());
        assertEquals(8, refusal.cap());
        assertTrue(refusal.getMessage().contains("orders-consumer"));
    }
}
