package ai.ravenroot.api.publication;

import java.util.List;
import java.util.Objects;

/**
 * Typed, immutable proposal presented to a publication policy before an external effect.
 *
 * @param contract candidate representation version
 * @param destination requested provider-neutral destination
 * @param resources ordered immutable resources proposed for publication
 * @param provenance origin and exact-content binding, or {@code null} when incomplete
 */
public record PublicationCandidate(String contract, PublicationDestination destination,
                                   List<PublicationResource> resources, PublicationProvenance provenance) {
    /** Current candidate representation understood by the built-in guard. */
    public static final String CONTRACT = "ravenroot.publication-candidate/1";

    /** Validates the version and takes an immutable resource snapshot. */
    public PublicationCandidate {
        if (!CONTRACT.equals(contract)) {
            throw new IllegalArgumentException("publication candidate contract is unsupported");
        }
        Objects.requireNonNull(destination, "destination");
        resources = List.copyOf(resources == null ? List.of() : resources);
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("publication candidate requires at least one resource");
        }
        if (resources.size() > 1_024) {
            throw new IllegalArgumentException("publication candidate has too many resources");
        }
    }

    /**
     * Creates a candidate using the current contract.
     *
     * @param destination requested provider-neutral destination
     * @param resources ordered immutable resources proposed for publication
     * @param provenance origin and exact-content binding, or {@code null} when incomplete
     */
    public PublicationCandidate(PublicationDestination destination, List<PublicationResource> resources,
                                PublicationProvenance provenance) {
        this(CONTRACT, destination, resources, provenance);
    }
}
