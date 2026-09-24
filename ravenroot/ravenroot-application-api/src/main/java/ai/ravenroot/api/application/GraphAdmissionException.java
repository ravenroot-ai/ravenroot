package ai.ravenroot.api.application;

import java.util.List;
import java.util.Objects;

/** Safe typed refusal. Its message is fixed; submitted text is available only through bounded findings. */
public final class GraphAdmissionException extends IllegalStateException {
    private final List<GraphAdmissionFinding> findings;

    public GraphAdmissionException(GraphAdmissionFinding finding) {
        super("The graph was not admitted");
        this.findings = List.of(Objects.requireNonNull(finding, "finding"));
    }

    public List<GraphAdmissionFinding> findings() {
        return findings;
    }
}
