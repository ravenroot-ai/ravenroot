package ai.ravenroot.api.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * Versioned transport-neutral request to settle one durable Human Task.
 *
 * <p>The action selects workflow semantics and re-entry routing. The response is the bounded,
 * schema-validated operational value emitted by a resolved Human Task. The comment is separate
 * immutable audit metadata and is never used as the operational response.</p>
 *
 * @param version settlement document version.
 * @param action semantic action to apply.
 * @param response operational response, required only for {@code RESOLVE}.
 * @param comment optional audit comment, subject to the task's pinned policy.
 */
public record HumanTaskSettlement(int version, HumanTaskConfirmationAction action,
                                  Optional<OpaquePayload> response, String comment) {
    /** Current settlement contract version. */
    public static final int VERSION = 1;

    /** Validates the closed settlement shape without applying task-specific policy. */
    public HumanTaskSettlement {
        if (version != VERSION) throw new IllegalArgumentException("unsupported settlement version");
        action = Objects.requireNonNull(action, "action");
        response = response == null ? Optional.empty() : response;
        comment = comment == null ? "" : comment;
        if ((action == HumanTaskConfirmationAction.RESOLVE) != response.isPresent()) {
            throw new IllegalArgumentException("only RESOLVE carries an operational response");
        }
    }

    /** Creates a resolved settlement with a schema-defined operational response. */
    public static HumanTaskSettlement resolve(OpaquePayload response, String comment) {
        return new HumanTaskSettlement(VERSION, HumanTaskConfirmationAction.RESOLVE,
                Optional.of(Objects.requireNonNull(response, "response")), comment);
    }

    /** Creates a denial with audit metadata and no operational response. */
    public static HumanTaskSettlement deny(String comment) {
        return new HumanTaskSettlement(VERSION, HumanTaskConfirmationAction.DENY,
                Optional.empty(), comment);
    }

    /** Creates a cancellation with audit metadata and no operational response. */
    public static HumanTaskSettlement cancel(String comment) {
        return new HumanTaskSettlement(VERSION, HumanTaskConfirmationAction.CANCEL,
                Optional.empty(), comment);
    }
}
