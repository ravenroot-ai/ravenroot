package ai.ravenroot.api.application;

import java.util.List;
import java.util.Objects;

/** Safe typed refusal. Its message is fixed; submitted text is available only through bounded findings. */
public final class GraphAdmissionException extends IllegalStateException {
    /** Immutable deterministic public findings. */
    private final List<GraphAdmissionFinding> findings;

    /**
     * Creates a refusal containing one deterministic primary finding.
     * @param finding bounded public finding
     */
    public GraphAdmissionException(GraphAdmissionFinding finding) {
        super("The graph was not admitted");
        this.findings = List.of(Objects.requireNonNull(finding, "finding"));
    }

    /**
     * Returns the deterministic bounded findings.
     * @return immutable non-empty finding list
     */
    public List<GraphAdmissionFinding> findings() {
        return findings;
    }
}
