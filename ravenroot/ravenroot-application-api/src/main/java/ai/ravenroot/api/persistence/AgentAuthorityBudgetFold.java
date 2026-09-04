package ai.ravenroot.api.persistence;

import ai.ravenroot.api.persistence.DurableAgentAuthorityBudget.DurableAgentGrant;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared, deterministic fold used by every execution-store adapter. */
public final class AgentAuthorityBudgetFold {
    private AgentAuthorityBudgetFold() { }

    public static DurableAgentAuthorityBudget apply(ExecutionKey key,
                                                     DurableAgentAuthorityBudget current,
                                                     AgentBudgetOperation operation,
                                                     Instant storeNow) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(storeNow, "storeNow");
        if (operation instanceof AgentBudgetOperation.RegisterRoot register) {
            if (current == null) {
                return new DurableAgentAuthorityBudget(key, register.root(), AgentAuthorityState.ACTIVE, 0,
                        AgentBudgetVector.ZERO, AgentBudgetVector.ZERO, Map.of(), Map.of());
            }
            if (current.root().equals(register.root())) return current;
            throw new IllegalStateException("agent authority root is immutable");
        }
        if (current == null) throw new IllegalStateException("agent authority root is absent");
        if (operation instanceof AgentBudgetOperation.RegisterGrant register) {
            requireEpoch(current, register.bootEpoch(), register.controlEpoch());
            return registerGrant(current, register, storeNow);
        }
        if (operation instanceof AgentBudgetOperation.Hold hold) {
            requireEpoch(current, hold.bootEpoch(), hold.controlEpoch());
            return hold(current, hold.reservation(), storeNow);
        }
        if (operation instanceof AgentBudgetOperation.Dispatch dispatch) {
            requireEpoch(current, dispatch.bootEpoch(), dispatch.controlEpoch());
            requireActive(current, storeNow);
            AgentBudgetReservation held = reservation(current, dispatch.reservationId());
            activeGrant(current, held.grantId(), storeNow);
            return transitionReservation(current, dispatch.reservationId(), AgentReservationState.DISPATCHED, null);
        }
        if (operation instanceof AgentBudgetOperation.Settle settle) {
            return transitionReservation(current, settle.reservationId(), AgentReservationState.SETTLED,
                    settle.actual());
        }
        if (operation instanceof AgentBudgetOperation.MarkIndeterminate indeterminate) {
            AgentBudgetReservation existing = reservation(current, indeterminate.reservationId());
            return transitionReservation(current, indeterminate.reservationId(),
                    AgentReservationState.INDETERMINATE, existing.requested());
        }
        if (operation instanceof AgentBudgetOperation.Release release) {
            return transitionReservation(current, release.reservationId(), AgentReservationState.RELEASED,
                    AgentBudgetVector.ZERO);
        }
        if (operation instanceof AgentBudgetOperation.CancelGrant cancel) {
            return terminateGrant(current, cancel.grantId(), AgentGrantState.CANCELLED);
        }
        if (operation instanceof AgentBudgetOperation.ExhaustGrant exhaust) {
            return terminateGrant(current, exhaust.grantId(), AgentGrantState.EXHAUSTED);
        }
        if (operation instanceof AgentBudgetOperation.CancelRoot) return cancelRoot(current, AgentAuthorityState.CANCELLED);
        if (operation instanceof AgentBudgetOperation.KillRoot kill) {
            if (kill.expectedControlEpoch() != current.controlEpoch()) throw new IllegalStateException("stale control epoch");
            return withEpoch(cancelRoot(current, AgentAuthorityState.KILLED), current.controlEpoch() + 1,
                    current.root());
        }
        if (operation instanceof AgentBudgetOperation.ResetRoot reset) {
            if (reset.expectedControlEpoch() != current.controlEpoch()
                    || current.state() != AgentAuthorityState.KILLED
                    || !reset.replacement().security().tenantId().equals(current.key().tenantId())
                    || !reset.replacement().runtimeInstanceId().equals(current.root().runtimeInstanceId())) {
                throw new IllegalStateException("agent authority reset is stale or invalid");
            }
            DurableAgentAuthorityBudget cancelled = cancelRoot(current, AgentAuthorityState.KILLED);
            return new DurableAgentAuthorityBudget(current.key(), reset.replacement(), AgentAuthorityState.ACTIVE,
                    current.controlEpoch() + 1, cancelled.spent(), cancelled.reserved(),
                    cancelled.grants(), cancelled.reservations());
        }
        if (operation instanceof AgentBudgetOperation.RebootRoot reboot) {
            AgentAuthorityRootRegistration replacement = reboot.replacement();
            if (reboot.expectedControlEpoch() != current.controlEpoch()
                    || current.state() != AgentAuthorityState.ACTIVE
                    || replacement.bootEpoch() == current.root().bootEpoch()
                    || !replacement.runtimeInstanceId().equals(current.root().runtimeInstanceId())
                    || !replacement.security().equals(current.root().security())
                    || !current.root().dataScopes().containsAll(replacement.dataScopes())
                    || !current.root().authorityScopes().containsAll(replacement.authorityScopes())
                    || !replacement.maxima().componentwiseAtMost(current.root().maxima())
                    || replacement.absoluteDeadline().isAfter(current.root().absoluteDeadline())) {
                throw new IllegalStateException("agent authority reboot is stale or expands authority");
            }
            for (DurableAgentGrant grant : current.grants().values()) {
                if (grant.state() == AgentGrantState.ACTIVE
                        && (!replacement.dataScopes().containsAll(grant.registration().dataScopes())
                        || !replacement.authorityScopes().containsAll(grant.registration().authorityScopes())
                        || !grant.registration().ceilings().componentwiseAtMost(replacement.maxima())
                        || grant.registration().maximumTotalTokens() > combinedCeiling(
                                replacement.maxima().inputTokens(), replacement.maxima().outputTokens())
                        || grant.registration().absoluteDeadline().isAfter(replacement.absoluteDeadline()))) {
                    throw new IllegalStateException("active grant is not valid after reboot");
                }
            }
            DurableAgentAuthorityBudget retired = retireAllGrants(current);
            return new DurableAgentAuthorityBudget(current.key(), replacement, AgentAuthorityState.ACTIVE,
                    current.controlEpoch() + 1, retired.spent(), retired.reserved(),
                    retired.grants(), retired.reservations());
        }
        throw new IllegalStateException("unknown agent budget operation");
    }

    private static DurableAgentAuthorityBudget registerGrant(DurableAgentAuthorityBudget current,
            AgentBudgetOperation.RegisterGrant operation, Instant now) {
        AgentAuthorityGrantRegistration registration = operation.grant();
        DurableAgentGrant existing = current.grants().get(registration.grantId());
        if (existing != null) {
            if (existing.registration().equals(registration) && existing.binding().equals(operation.binding())) {
                return current;
            }
            throw new IllegalStateException("grant id was reused with different authority");
        }
        requireActive(current, now);
        Set<UUID> parents = registration.contributingParentGrantIds();
        if (!operation.binding().grantId().equals(registration.grantId())) {
            throw new IllegalStateException("agent grant binding does not match registration");
        }
        AgentBudgetVector parentCeiling = current.root().maxima();
        long parentTotalTokens = combinedCeiling(parentCeiling.inputTokens(), parentCeiling.outputTokens());
        Set<String> data = current.root().dataScopes();
        Set<String> authority = current.root().authorityScopes();
        Instant parentDeadline = current.root().absoluteDeadline();
        long expectedDepth = 1;
        if (!parents.isEmpty()) {
            for (UUID parentId : parents) {
                DurableAgentGrant parent = current.grants().get(parentId);
                if (parent == null || parent.state() != AgentGrantState.ACTIVE) {
                    throw new IllegalStateException("contributing parent grant is not active");
                }
                if (!operation.binding().causalParentInvocationIds().contains(parent.binding().invocationId())) {
                    throw new IllegalStateException("contributing parent grant is not a causal parent invocation");
                }
                parentCeiling = componentMinimum(parentCeiling, parent.registration().ceilings());
                parentTotalTokens = Math.min(parentTotalTokens, parent.registration().maximumTotalTokens());
                data = intersection(data, parent.registration().dataScopes());
                authority = intersection(authority, parent.registration().authorityScopes());
                if (parent.registration().absoluteDeadline().isBefore(parentDeadline)) {
                    parentDeadline = parent.registration().absoluteDeadline();
                }
                expectedDepth = Math.max(expectedDepth, parent.registration().depth() + 1);
            }
        }
        if (registration.depth() != expectedDepth
                || registration.depth() > current.root().maxima().delegationDepth()) {
            throw new IllegalStateException("grant depth does not match its parents");
        }
        if (!data.containsAll(registration.dataScopes())
                || !authority.containsAll(registration.authorityScopes())
                || !registration.ceilings().componentwiseAtMost(parentCeiling)
                || registration.maximumTotalTokens() > parentTotalTokens
                || registration.absoluteDeadline().isAfter(parentDeadline)) {
            throw new IllegalStateException("child grant expands parent authority");
        }
        boolean attenuated = !registration.dataScopes().equals(data)
                || !registration.authorityScopes().equals(authority)
                || !registration.ceilings().equals(parentCeiling)
                || registration.maximumTotalTokens() != parentTotalTokens
                || registration.absoluteDeadline().isBefore(parentDeadline);
        if (!attenuated) throw new IllegalStateException("child grant must be strictly attenuated overall");
        AgentBudgetVector teamCharge = new AgentBudgetVector(0, 0, 0, 0, 0, 0,
                registration.depth(), 1, 1);
        if (!current.root().maxima().contains(current.spent().plus(current.reserved()), teamCharge)) {
            throw new IllegalStateException("agent team budget exhausted");
        }
        var grants = new LinkedHashMap<>(current.grants());
        var chargedAncestors = new LinkedHashMap<UUID, DurableAgentGrant>();
        for (UUID parentId : parents) {
            for (DurableAgentGrant ancestor : ancestors(current, activeOrTerminalGrant(current, parentId))) {
                chargedAncestors.putIfAbsent(ancestor.registration().grantId(), ancestor);
            }
        }
        AgentBudgetVector parentSpentCharge = new AgentBudgetVector(0, 0, 0, 0, 0, 0,
                registration.depth(), 1, 0);
        AgentBudgetVector parentReservedCharge = new AgentBudgetVector(0, 0, 0, 0, 0, 0, 0, 0, 1);
        for (DurableAgentGrant ancestor : chargedAncestors.values()) {
            AgentBudgetVector combined = ancestor.spent().plus(ancestor.reserved());
            if (!ancestor.registration().ceilings().contains(combined, teamCharge)) {
                throw new IllegalStateException("parent team budget exhausted");
            }
            grants.put(ancestor.registration().grantId(), new DurableAgentGrant(ancestor.registration(),
                    ancestor.binding(), ancestor.state(), ancestor.spent().plus(parentSpentCharge),
                    ancestor.reserved().plus(parentReservedCharge)));
        }
        grants.put(registration.grantId(), new DurableAgentGrant(registration, operation.binding(),
                AgentGrantState.ACTIVE, AgentBudgetVector.ZERO, AgentBudgetVector.ZERO));
        AgentBudgetVector spent = current.spent().plus(new AgentBudgetVector(0, 0, 0, 0, 0, 0,
                registration.depth(), 1, 0));
        AgentBudgetVector reserved = current.reserved().plus(new AgentBudgetVector(0, 0, 0, 0, 0, 0,
                0, 0, 1));
        return copy(current, current.state(), spent, reserved, grants, current.reservations());
    }

    private static DurableAgentAuthorityBudget hold(DurableAgentAuthorityBudget current,
                                                     AgentBudgetReservation reservation, Instant now) {
        AgentBudgetReservation sameId = current.reservations().get(reservation.reservationId());
        AgentBudgetReservation sameKey = current.reservations().values().stream()
                .filter(value -> value.operationKey().equals(reservation.operationKey())).findFirst().orElse(null);
        if (sameId != null || sameKey != null) {
            AgentBudgetReservation existing = sameId == null ? sameKey : sameId;
            if (existing.equals(reservation)) return current;
            throw new IllegalStateException("agent operation key conflicts with an existing reservation");
        }
        requireActive(current, now);
        DurableAgentGrant grant = activeGrant(current, reservation.grantId(), now);
        for (DurableAgentGrant ancestor : ancestors(current, grant)) {
            if (!ancestor.registration().ceilings().contains(
                    ancestor.spent().plus(ancestor.reserved()), reservation.requested())) {
                throw new IllegalStateException("agent grant budget exhausted");
            }
            long usedTokens = combinedTokens(
                    ancestor.spent().inputTokens(), ancestor.spent().outputTokens(),
                    ancestor.reserved().inputTokens(), ancestor.reserved().outputTokens());
            long requestedTokens = combinedTokens(
                    reservation.requested().inputTokens(), reservation.requested().outputTokens());
            if (usedTokens > ancestor.registration().maximumTotalTokens()
                    || requestedTokens > ancestor.registration().maximumTotalTokens() - usedTokens) {
                throw new IllegalStateException("agent grant combined token budget exhausted");
            }
        }
        if (!current.root().maxima().contains(current.spent().plus(current.reserved()), reservation.requested())) {
            throw new IllegalStateException("agent root budget exhausted");
        }
        var grants = new LinkedHashMap<>(current.grants());
        for (DurableAgentGrant ancestor : ancestors(current, grant)) {
            grants.put(ancestor.registration().grantId(), new DurableAgentGrant(ancestor.registration(),
                    ancestor.binding(), ancestor.state(), ancestor.spent(),
                    ancestor.reserved().plus(reservation.requested())));
        }
        var reservations = new LinkedHashMap<>(current.reservations());
        reservations.put(reservation.reservationId(), reservation);
        return copy(current, current.state(), current.spent(), current.reserved().plus(reservation.requested()),
                grants, reservations);
    }

    private static DurableAgentAuthorityBudget transitionReservation(DurableAgentAuthorityBudget current,
            UUID reservationId, AgentReservationState target, AgentBudgetVector actual) {
        AgentBudgetReservation existing = reservation(current, reservationId);
        if (existing.state() == target && (actual == null || existing.actual().equals(actual))) return current;
        if (target == AgentReservationState.DISPATCHED) {
            if (existing.state() != AgentReservationState.HELD) throw new IllegalStateException("reservation is not held");
            AgentBudgetVector charged = nonRefundable(existing.requested());
            return dispatchReservation(current, new AgentBudgetReservation(existing.reservationId(),
                    existing.grantId(), existing.operationKey(), existing.requested(), charged, target));
        }
        if (existing.state() != AgentReservationState.HELD
                && existing.state() != AgentReservationState.DISPATCHED) {
            throw new IllegalStateException("reservation is already terminal");
        }
        AgentBudgetVector charged = Objects.requireNonNull(actual, "actual");
        if (!charged.componentwiseAtMost(existing.requested())) {
            throw new IllegalStateException("usage exceeds the held reservation");
        }
        if (!existing.actual().componentwiseAtMost(charged)) {
            throw new IllegalStateException("usage cannot refund a dispatched turn or tool proposal");
        }
        return replaceReservation(current, new AgentBudgetReservation(existing.reservationId(), existing.grantId(),
                existing.operationKey(), existing.requested(), charged, target), true);
    }

    private static DurableAgentAuthorityBudget dispatchReservation(DurableAgentAuthorityBudget current,
            AgentBudgetReservation next) {
        var reservations = new LinkedHashMap<>(current.reservations());
        AgentBudgetReservation previous = reservations.put(next.reservationId(), next);
        AgentBudgetVector charged = next.actual();
        var grants = new LinkedHashMap<>(current.grants());
        for (DurableAgentGrant ancestor : ancestors(current, activeOrTerminalGrant(current, next.grantId()))) {
            grants.put(ancestor.registration().grantId(), new DurableAgentGrant(ancestor.registration(),
                    ancestor.binding(), ancestor.state(), ancestor.spent().plus(charged),
                    ancestor.reserved().minus(charged)));
        }
        return copy(current, current.state(), current.spent().plus(charged),
                current.reserved().minus(charged), grants, reservations);
    }

    private static DurableAgentAuthorityBudget replaceReservation(DurableAgentAuthorityBudget current,
            AgentBudgetReservation next, boolean terminal) {
        var reservations = new LinkedHashMap<>(current.reservations());
        AgentBudgetReservation previous = reservations.put(next.reservationId(), next);
        if (!terminal) return copy(current, current.state(), current.spent(), current.reserved(),
                current.grants(), reservations);
        var grants = new LinkedHashMap<>(current.grants());
        for (DurableAgentGrant ancestor : ancestors(current, activeOrTerminalGrant(current, next.grantId()))) {
            AgentBudgetVector reserved = ancestor.reserved().minus(
                    previous.requested().minus(previous.actual()));
            AgentBudgetVector spent = ancestor.spent().plus(next.actual().minus(previous.actual()));
            AgentGrantState state = spent.equals(ancestor.registration().ceilings())
                    ? AgentGrantState.EXHAUSTED : ancestor.state();
            grants.put(ancestor.registration().grantId(), new DurableAgentGrant(ancestor.registration(),
                    ancestor.binding(), state, spent, reserved));
        }
        return copy(current, current.state(), current.spent().plus(next.actual().minus(previous.actual())),
                current.reserved().minus(previous.requested().minus(previous.actual())), grants, reservations);
    }

    private static AgentBudgetVector nonRefundable(AgentBudgetVector requested) {
        return new AgentBudgetVector(requested.turns(), 0, 0, 0, 0, requested.toolCalls(), 0, 0, 0);
    }

    private static DurableAgentAuthorityBudget terminateGrant(DurableAgentAuthorityBudget current, UUID grantId,
                                                                AgentGrantState targetState) {
        if (!current.grants().containsKey(grantId)) throw new IllegalStateException("unknown agent grant");
        Set<UUID> subtree = new LinkedHashSet<>();
        var queue = new ArrayDeque<UUID>(); queue.add(grantId);
        while (!queue.isEmpty()) {
            UUID next = queue.remove();
            if (!subtree.add(next)) continue;
            current.grants().values().stream()
                    .filter(grant -> grant.registration().contributingParentGrantIds().contains(next))
                    .map(grant -> grant.registration().grantId()).forEach(queue::add);
        }
        DurableAgentAuthorityBudget result = current;
        for (AgentBudgetReservation reservation : current.reservations().values()) {
            if (!subtree.contains(reservation.grantId())) continue;
            if (reservation.state() == AgentReservationState.HELD) {
                result = transitionReservation(result, reservation.reservationId(),
                        AgentReservationState.RELEASED, AgentBudgetVector.ZERO);
            } else if (reservation.state() == AgentReservationState.DISPATCHED) {
                result = transitionReservation(result, reservation.reservationId(),
                        AgentReservationState.INDETERMINATE, reservation.requested());
            }
        }
        var grants = new LinkedHashMap<>(result.grants());
        var newlyTerminated = new LinkedHashSet<UUID>();
        for (UUID id : subtree) {
            DurableAgentGrant grant = grants.get(id);
            if (grant.state() == AgentGrantState.ACTIVE) newlyTerminated.add(id);
            AgentGrantState state = id.equals(grantId) ? targetState : AgentGrantState.CANCELLED;
            grants.put(id, new DurableAgentGrant(grant.registration(), grant.binding(),
                    grant.state() == AgentGrantState.ACTIVE ? state : grant.state(),
                    grant.spent(), grant.reserved()));
        }
        long releasedSlots = newlyTerminated.size();
        AgentBudgetVector release = new AgentBudgetVector(0, 0, 0, 0, 0, 0, 0, 0, releasedSlots);
        for (UUID id : newlyTerminated) {
            DurableAgentGrant original = result.grants().get(id);
            var chargedAncestors = new LinkedHashMap<UUID, DurableAgentGrant>();
            for (UUID parentId : original.registration().contributingParentGrantIds()) {
                for (DurableAgentGrant ancestor : ancestors(result, activeOrTerminalGrant(result, parentId))) {
                    if (!subtree.contains(ancestor.registration().grantId())) {
                        chargedAncestors.putIfAbsent(ancestor.registration().grantId(), ancestor);
                    }
                }
            }
            for (DurableAgentGrant ancestor : chargedAncestors.values()) {
                DurableAgentGrant latest = grants.get(ancestor.registration().grantId());
                grants.put(ancestor.registration().grantId(), new DurableAgentGrant(latest.registration(),
                        latest.binding(), latest.state(), latest.spent(), latest.reserved().minus(
                                new AgentBudgetVector(0, 0, 0, 0, 0, 0, 0, 0, 1))));
            }
        }
        return copy(result, result.state(), result.spent(), result.reserved().minus(release),
                grants, result.reservations());
    }

    private static DurableAgentAuthorityBudget cancelRoot(DurableAgentAuthorityBudget current,
                                                            AgentAuthorityState state) {
        if (current.state() == state) return current;
        DurableAgentAuthorityBudget result = retireAllGrants(current);
        return copy(result, state, result.spent(), result.reserved(), result.grants(), result.reservations());
    }

    private static DurableAgentAuthorityBudget retireAllGrants(DurableAgentAuthorityBudget current) {
        DurableAgentAuthorityBudget result = current;
        for (UUID grantId : current.grants().keySet()) {
            result = terminateGrant(result, grantId, AgentGrantState.CANCELLED);
        }
        return result;
    }

    private static void requireActive(DurableAgentAuthorityBudget current, Instant now) {
        if (current.state() != AgentAuthorityState.ACTIVE) throw new IllegalStateException("agent root is not active");
        if (!now.isBefore(current.root().absoluteDeadline())) throw new IllegalStateException("agent deadline expired");
    }

    private static void requireEpoch(DurableAgentAuthorityBudget current, long bootEpoch, long controlEpoch) {
        if (bootEpoch != current.root().bootEpoch() || controlEpoch != current.controlEpoch()) {
            throw new IllegalStateException("stale agent authority epoch");
        }
    }

    private static DurableAgentGrant activeGrant(DurableAgentAuthorityBudget current, UUID grantId, Instant now) {
        DurableAgentGrant grant = activeOrTerminalGrant(current, grantId);
        if (grant.state() != AgentGrantState.ACTIVE || !now.isBefore(grant.registration().absoluteDeadline())) {
            throw new IllegalStateException("agent grant is not active");
        }
        return grant;
    }

    private static DurableAgentGrant activeOrTerminalGrant(DurableAgentAuthorityBudget current, UUID grantId) {
        DurableAgentGrant grant = current.grants().get(grantId);
        if (grant == null) throw new IllegalStateException("unknown agent grant");
        return grant;
    }

    private static AgentBudgetReservation reservation(DurableAgentAuthorityBudget current, UUID id) {
        AgentBudgetReservation reservation = current.reservations().get(id);
        if (reservation == null) throw new IllegalStateException("unknown agent reservation");
        return reservation;
    }

    private static java.util.List<DurableAgentGrant> ancestors(DurableAgentAuthorityBudget current,
                                                                DurableAgentGrant leaf) {
        var found = new LinkedHashMap<UUID, DurableAgentGrant>();
        var queue = new ArrayDeque<UUID>(); queue.add(leaf.registration().grantId());
        while (!queue.isEmpty()) {
            UUID id = queue.remove();
            DurableAgentGrant grant = activeOrTerminalGrant(current, id);
            if (found.putIfAbsent(id, grant) == null) queue.addAll(grant.registration().contributingParentGrantIds());
        }
        return java.util.List.copyOf(found.values());
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        var result = new LinkedHashSet<>(left); result.retainAll(right); return Set.copyOf(result);
    }

    private static AgentBudgetVector componentMinimum(AgentBudgetVector a, AgentBudgetVector b) {
        return new AgentBudgetVector(Math.min(a.turns(), b.turns()), Math.min(a.inputTokens(), b.inputTokens()),
                Math.min(a.outputTokens(), b.outputTokens()), Math.min(a.elapsedMillis(), b.elapsedMillis()),
                Math.min(a.costMicros(), b.costMicros()), Math.min(a.toolCalls(), b.toolCalls()),
                Math.min(a.delegationDepth(), b.delegationDepth()),
                Math.min(a.teamCumulative(), b.teamCumulative()), Math.min(a.teamActive(), b.teamActive()));
    }

    private static long combinedTokens(long... values) {
        long total = 0;
        try {
            for (long value : values) total = Math.addExact(total, value);
            return total;
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("agent combined token accounting overflow", overflow);
        }
    }

    private static long combinedCeiling(long inputTokens, long outputTokens) {
        return inputTokens > Long.MAX_VALUE - outputTokens
                ? Long.MAX_VALUE : inputTokens + outputTokens;
    }

    private static DurableAgentAuthorityBudget copy(DurableAgentAuthorityBudget current,
            AgentAuthorityState state, AgentBudgetVector spent, AgentBudgetVector reserved,
            Map<UUID, DurableAgentGrant> grants, Map<UUID, AgentBudgetReservation> reservations) {
        return new DurableAgentAuthorityBudget(current.key(), current.root(), state, current.controlEpoch(), spent, reserved,
                grants, reservations);
    }

    private static DurableAgentAuthorityBudget withEpoch(DurableAgentAuthorityBudget current, long epoch,
                                                          AgentAuthorityRootRegistration root) {
        return new DurableAgentAuthorityBudget(current.key(), root, current.state(), epoch, current.spent(),
                current.reserved(), current.grants(), current.reservations());
    }
}
