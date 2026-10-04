package ai.ravenroot.core.activity;

import ai.ravenroot.api.activity.ActivityContentKind;
import java.util.UUID;

/** Metadata-only warning emitted when BEST_EFFORT capture loses or may lose content. */
public record ActivityCaptureWarning(
    ActivityCaptureException.Reason reason,
    String eventId,
    String tenantId,
    UUID processInstanceId,
    UUID traversalId,
    String nodeId,
    UUID invocationId,
    UUID attemptId,
    ActivityContentKind contentKind,
    String errorClass) {}
