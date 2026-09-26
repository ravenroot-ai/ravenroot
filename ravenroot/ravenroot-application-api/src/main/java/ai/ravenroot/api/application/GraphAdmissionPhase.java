package ai.ravenroot.api.application;

/** Closed phase vocabulary for public graph and source-start diagnostics. */
public enum GraphAdmissionPhase {
    /** Secure GraphML parsing and profile validation. */
    GRAPHML_PARSE,
    /** Graph topology and required structural elements. */
    SEMANTIC_STRUCTURE,
    /** Trusted catalog property-name and property-value schema checks. */
    PROPERTY_SCHEMA,
    /** Trusted behavior and runtime capability availability. */
    CAPABILITY,
    /** Node runtime-nature compatibility. */
    RUNTIME_NATURE,
    /** Authored bypass configuration. */
    BYPASS,
    /** Runtime concurrency configuration. */
    RUNTIME_CONCURRENCY,
    /** Bounded graph resource limits. */
    RESOURCE_LIMIT,
    /** Purpose-specific inbound-source presence. */
    SOURCE_REQUIREMENT,
    /** Inbound-source construction before listener startup. */
    SOURCE_CONSTRUCTION,
    /** Inbound-source listener startup. */
    SOURCE_START,
    /** Registration of the started source with managed ingress. */
    MANAGED_INGRESS,
    /** Unclassified deployment startup processing. */
    STARTUP
}
