package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.PayloadValue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared deterministic mapping used by ingress, explicit context establishment and author preview. */
public record CorrelationMapping(Mode mode, List<CorrelationSelector> applicationSelectors,
                                 Optional<CorrelationSelector> contribution,
                                 Optional<CorrelationSelector> kind,
                                 Optional<CorrelationSelector> round,
                                 Optional<CorrelationSelector> publicReference,
                                 CorrelationLimits limits) {
    /** Creation policy; REQUIRE_EXISTING is atomically enforced by the scoped history store. */
    public enum Mode { GENERATE_NEW, ACCEPT_OR_CREATE, REQUIRE_EXISTING }

    /** Validates mapping shape independently of sample/event values. */
    public CorrelationMapping {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(limits, "limits");
        applicationSelectors = List.copyOf(applicationSelectors == null ? List.of() : applicationSelectors);
        if (applicationSelectors.size() > 4) throw new IllegalArgumentException("at most four explicit ID mappings");
        if (mode == Mode.GENERATE_NEW && !applicationSelectors.isEmpty())
            throw new IllegalArgumentException("generate-new cannot also map an external application ID");
        if (mode == Mode.REQUIRE_EXISTING && applicationSelectors.isEmpty())
            throw new IllegalArgumentException("require-existing needs an application ID selector");
        contribution = contribution == null ? Optional.empty() : contribution;
        kind = kind == null ? Optional.empty() : kind;
        round = round == null ? Optional.empty() : round;
        publicReference = publicReference == null ? Optional.empty() : publicReference;
    }

    /**
     * Extracts immutable context. Only missing mappings may generate a new ID in ACCEPT_OR_CREATE;
     * invalid values and conflicting mappings always refuse. A caller supplies one persisted or
     * domain-separated stable generated identity for retry reconciliation, never a transport ID.
     * @param input exposed event structure
     * @param generated identity supplier used only when policy permits a new operation
     * @return extracted context; this result grants no authority to an existing history
     */
    public ApplicationCorrelationContext extract(PayloadValue input, Supplier<UUID> generated) {
        limits.input().enforce(input);
        String applicationId = null;
        for (CorrelationSelector selector : applicationSelectors) {
            final String selected;
            try { selected = selector.selectId(input); }
            catch (CorrelationSelector.Invalid failure) {
                if (failure.reason() == CorrelationSelector.Reason.MISSING && mode == Mode.ACCEPT_OR_CREATE) continue;
                throw failure;
            }
            if (applicationId != null && !applicationId.equals(selected))
                throw new IllegalArgumentException("conflicting application correlation ID mappings");
            applicationId = selected;
        }
        if (applicationId == null) {
            if (mode == Mode.REQUIRE_EXISTING) throw new IllegalArgumentException("required application correlation ID missing");
            applicationId = limits.requireId(Objects.requireNonNull(generated.get(), "generated identity").toString(),
                    "generated applicationCorrelationId");
        }
        return new ApplicationCorrelationContext(applicationId, select(contribution, input), select(kind, input),
                round.map(selector -> selector.selectId(input)).orElse("0"), select(publicReference, input));
    }

    private static Optional<String> select(Optional<CorrelationSelector> selector, PayloadValue input) {
        return selector.map(value -> value.selectId(input));
    }
}
