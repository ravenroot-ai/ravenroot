package ai.ravenroot.core.humantask;

import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskExecutionLimits;
import ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Trusted, validated configuration captured from one {@code human-task} graph node. */
public record HumanTaskDefinition(HumanTaskMetadata metadata,
                                  HumanTaskResponseSchema responseSchema,
                                  HandlerAuthorization responderRequirements,
                                  Optional<Duration> escalationDelay,
                                  Duration expiryDelay,
                                  HumanTaskReentryMapping reentryMapping,
                                  HumanTaskExecutionLimits executionLimits,
                                  HumanTaskConfirmationPresentation confirmationPresentation) {
    /** Compatibility constructor using the legacy response and retry budgets. */
    public HumanTaskDefinition(HumanTaskMetadata metadata,
                               HumanTaskResponseSchema responseSchema,
                               HandlerAuthorization responderRequirements,
                               Optional<Duration> escalationDelay,
                               Duration expiryDelay,
                               HumanTaskReentryMapping reentryMapping) {
        this(metadata, responseSchema, responderRequirements, escalationDelay, expiryDelay,
                reentryMapping, HumanTaskExecutionLimits.legacy(responseSchema.maxBytes()),
                HumanTaskConfirmationPresentation.none());
    }

    /** Compatibility constructor retaining execution limits before embedded confirmations. */
    public HumanTaskDefinition(HumanTaskMetadata metadata,
                               HumanTaskResponseSchema responseSchema,
                               HandlerAuthorization responderRequirements,
                               Optional<Duration> escalationDelay,
                               Duration expiryDelay,
                               HumanTaskReentryMapping reentryMapping,
                               HumanTaskExecutionLimits executionLimits) {
        this(metadata, responseSchema, responderRequirements, escalationDelay, expiryDelay,
                reentryMapping, executionLimits, HumanTaskConfirmationPresentation.none());
    }

    public HumanTaskDefinition {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(responseSchema, "responseSchema");
        Objects.requireNonNull(responderRequirements, "responderRequirements");
        escalationDelay = escalationDelay == null ? Optional.empty() : escalationDelay;
        escalationDelay.ifPresent(delay -> requirePositive(delay, "escalationDelay"));
        requirePositive(expiryDelay, "expiryDelay");
        if (escalationDelay.isPresent() && !escalationDelay.orElseThrow().minus(expiryDelay).isNegative()) {
            throw new IllegalArgumentException("escalationDelay must be shorter than expiryDelay");
        }
        Objects.requireNonNull(reentryMapping, "reentryMapping");
        executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
        confirmationPresentation = Objects.requireNonNull(confirmationPresentation,
                "confirmationPresentation");
        if (executionLimits.responsePayload().maxEncodedBytes() != responseSchema.maxBytes()) {
            throw new IllegalArgumentException(
                    "response payload encoded-byte limit must match response schema maxBytes");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
