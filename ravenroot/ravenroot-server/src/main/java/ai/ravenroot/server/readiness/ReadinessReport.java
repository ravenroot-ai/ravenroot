package ai.ravenroot.server.readiness;

import java.util.List;
import java.util.Objects;

/** PLAT-02. One evaluation of {@link ReadinessGate#evaluate()}. */
public record ReadinessReport(boolean ready, ReadinessState state, List<DependencyStatus> dependencies) {
    public ReadinessReport {
        Objects.requireNonNull(state, "state");
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        if (ready != (state == ReadinessState.READY)) {
            throw new IllegalArgumentException(
                    "ready must be exactly (state == READY), got ready=" + ready + " state=" + state
                            + " -- a report where the two disagree is the one shape a caller reading "
                            + "only ready() would get a wrong answer from");
        }
    }
}
