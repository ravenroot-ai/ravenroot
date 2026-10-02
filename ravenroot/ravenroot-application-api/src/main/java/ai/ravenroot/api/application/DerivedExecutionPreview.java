package ai.ravenroot.api.application;

import ai.ravenroot.api.persistence.ExecutionManifestDigest;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.ReplayBoundarySeed;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact, non-mutating selective replay plan or bounded refusal diagnostics.
 * @param admissible whether the request currently passes every admission check
 * @param refusalCodes stable refusal codes
 * @param boundaries exact requested boundaries
 * @param inheritedEvidence exact retained causal closure
 * @param scopeNodeIds possible downstream nodes, including conditional routes
 * @param missingInputs retained predecessor identities that are unavailable
 * @param externalEffectNodes possible downstream behavior nodes
 * @param graphContentId verified source graph content identity
 * @param manifestDigest verified source execution manifest digest
 * @param compatibilityDimensions incompatible runtime dimensions, without secret values
 */
public record DerivedExecutionPreview(boolean admissible, List<String> refusalCodes,
                                      List<ReplayBoundarySeed> boundaries, Set<UUID> inheritedEvidence,
                                      List<String> scopeNodeIds, List<String> missingInputs,
                                      List<String> externalEffectNodes, GraphContentId graphContentId,
                                      ExecutionManifestDigest manifestDigest,
                                      Set<String> compatibilityDimensions) {
    /** Defensively copies preview collections. */
    public DerivedExecutionPreview {
        refusalCodes = List.copyOf(refusalCodes == null ? List.of() : refusalCodes);
        boundaries = List.copyOf(boundaries == null ? List.of() : boundaries);
        inheritedEvidence = Set.copyOf(inheritedEvidence == null ? Set.of() : inheritedEvidence);
        scopeNodeIds = List.copyOf(scopeNodeIds == null ? List.of() : scopeNodeIds);
        missingInputs = List.copyOf(missingInputs == null ? List.of() : missingInputs);
        externalEffectNodes = List.copyOf(externalEffectNodes == null ? List.of() : externalEffectNodes);
        compatibilityDimensions = Set.copyOf(compatibilityDimensions == null ? Set.of() : compatibilityDimensions);
    }
}
