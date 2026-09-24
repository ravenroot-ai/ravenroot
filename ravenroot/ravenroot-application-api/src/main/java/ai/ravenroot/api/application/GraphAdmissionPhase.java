package ai.ravenroot.api.application;

/** Closed phase vocabulary for public graph and source-start diagnostics. */
public enum GraphAdmissionPhase {
    GRAPHML_PARSE,
    SEMANTIC_STRUCTURE,
    PROPERTY_SCHEMA,
    CAPABILITY,
    RUNTIME_NATURE,
    BYPASS,
    RUNTIME_CONCURRENCY,
    RESOURCE_LIMIT,
    SOURCE_REQUIREMENT,
    SOURCE_CONSTRUCTION,
    SOURCE_START,
    MANAGED_INGRESS,
    STARTUP
}
