package ai.ravenroot.core.graph;

import java.util.List;
import java.util.Objects;

/**
 * Explicit long-running execution decision.
 *
 * <p>Both choices preserve the original pin. Migration returns a new target
 * pin only after the complete plan succeeds.</p>
 */
public record GraphExecutionDecision(
        Mode mode,
        GraphExecutionPin original,
        GraphExecutionPin target,
        List<GraphMigrationDiagnostic> diagnostics) {

    public GraphExecutionDecision {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(target, "target");
        diagnostics = List.copyOf(diagnostics);
    }

    public static GraphExecutionDecision finish(GraphExecutionPin pin) {
        return new GraphExecutionDecision(Mode.FINISH, pin, pin, List.of());
    }

    public static GraphExecutionDecision migrate(GraphExecutionPin pin, GraphMigrationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!pin.metadata().equals(plan.source().metadata())) {
            throw new IllegalArgumentException("Migration plan source does not match the execution pin");
        }
        plan.requireMigratable();
        return new GraphExecutionDecision(Mode.MIGRATE, pin,
                GraphExecutionPin.from(plan.target()), plan.diagnostics());
    }

    public enum Mode {
        FINISH,
        MIGRATE
    }
}
