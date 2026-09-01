package ai.ravenroot.api.catalog;

/**
 * Trusted provenance attached by the runtime to a catalog entry.
 * @param origin whether core or an installed bundle supplied the entry
 * @param bundleId installed-bundle identifier; empty for core entries
 */
public record NodeCatalogSource(Origin origin, String bundleId) {
    /** Provenance of a catalog entry. */
    public enum Origin {
        /** Entry supplied by Ravenroot core. */
        CORE,
        /** Entry supplied by an installed bundle. */
        BUNDLE
    }

/**
 * Normalizes provenance so core entries cannot claim a bundle identifier.
 */
    public NodeCatalogSource {
        origin = origin == null ? Origin.BUNDLE : origin;
        bundleId = bundleId == null || bundleId.isBlank() ? "" : bundleId;
        if (origin == Origin.CORE) bundleId = "";
    }

/**
 * Creates provenance for a node descriptor supplied by Ravenroot core.
 * @return a source with {@link Origin#CORE} and no bundle ID
 */
    public static NodeCatalogSource core() { return new NodeCatalogSource(Origin.CORE, ""); }
/**
 * Creates provenance for a descriptor supplied by an installed bundle.
 * @param id bundle identifier retained for diagnostics and catalog display
 * @return a source with {@link Origin#BUNDLE}
 */
    public static NodeCatalogSource bundle(String id) { return new NodeCatalogSource(Origin.BUNDLE, id); }
}
