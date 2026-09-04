package ai.ravenroot.core.recovery;

import ai.ravenroot.api.persistence.PendingWork;

import java.util.List;
import java.util.Objects;

/**
 * Combines disjoint recovery dispatchers without allowing registration order to choose an owner.
 *
 * <p>Exactly one delegate must claim an item. No owner fails closed, leaving the item unacknowledged;
 * multiple owners are a configuration error because either one could otherwise perform the effect.</p>
 */
public final class CompositeRecoveryDispatcher implements RecoveryDispatcher {
    private final List<RecoveryDispatcher> delegates;

    public CompositeRecoveryDispatcher(List<RecoveryDispatcher> delegates) {
        Objects.requireNonNull(delegates, "delegates");
        if (delegates.isEmpty() || delegates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("at least one non-null recovery dispatcher is required");
        }
        this.delegates = List.copyOf(delegates);
    }

    public CompositeRecoveryDispatcher(RecoveryDispatcher... delegates) {
        this(List.of(delegates));
    }

    @Override
    public boolean canDispatch(PendingWork item) {
        return owner(item, false) != null;
    }

    @Override
    public void dispatch(PendingWork item, String idempotencyKey) {
        RecoveryDispatcher owner = owner(item, true);
        owner.dispatch(item, idempotencyKey);
    }

    @Override
    public void afterAcknowledged(PendingWork item) {
        RecoveryDispatcher owner = owner(item, false);
        if (owner != null) owner.afterAcknowledged(item);
    }

    private RecoveryDispatcher owner(PendingWork item, boolean required) {
        Objects.requireNonNull(item, "item");
        RecoveryDispatcher selected = null;
        for (RecoveryDispatcher candidate : delegates) {
            if (!candidate.canDispatch(item)) continue;
            if (selected != null) {
                throw new IllegalStateException("multiple recovery dispatchers claim work item "
                        + item.workItemId());
            }
            selected = candidate;
        }
        if (required && selected == null) {
            throw new IllegalStateException("no recovery dispatcher claims work item " + item.workItemId());
        }
        return selected;
    }
}
