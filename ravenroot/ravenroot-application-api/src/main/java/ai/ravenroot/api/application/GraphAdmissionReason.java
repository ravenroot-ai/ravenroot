package ai.ravenroot.api.application;

/** Closed platform-owned reason vocabulary. Extension source codes use their separately declared registry. */
public enum GraphAdmissionReason {
    /** GraphML could not be parsed under the secure profile. */
    GRAPHML_REJECTED,
    /** The graph violates a general structural invariant. */
    INVALID_STRUCTURE,
    /** A graph element identifier is duplicated. */
    DUPLICATE_ELEMENT,
    /** No start node is present. */
    MISSING_START,
    /** No end node is present. */
    MISSING_END,
    /** The graph declares an invalid number of terminal nodes. */
    INVALID_TERMINAL_COUNT,
    /** An edge refers to a missing endpoint. */
    DANGLING_EDGE,
    /** A submitted property is not accepted by the trusted schema. */
    INVALID_PROPERTY,
    /** A required trusted-catalog property is absent. */
    REQUIRED_PROPERTY_MISSING,
    /** A submitted property has the wrong value type. */
    PROPERTY_TYPE_INVALID,
    /** A submitted property is outside its closed set of allowed values. */
    PROPERTY_VALUE_NOT_ALLOWED,
    /** A submitted property is outside its numeric range. */
    PROPERTY_OUT_OF_RANGE,
    /** A submitted property exceeds its size bound. */
    PROPERTY_TOO_LARGE,
    /** A submitted collection property violates its element or cardinality contract. */
    PROPERTY_COLLECTION_INVALID,
    /** A submitted property resembles, but does not equal, a declared property name. */
    PROPERTY_NAME_NEAR_MISS,
    /** A submitted property name is reserved by the GraphML or runtime contract. */
    RESERVED_PROPERTY,
    /** A workspace reference is malformed or unavailable. */
    WORKSPACE_REFERENCE_INVALID,
    /** The graph refers to behavior removed from the trusted catalog. */
    REMOVED_BEHAVIOR,
    /** The runtime lacks a capability required by the graph. */
    CAPABILITY_UNAVAILABLE,
    /** A node declares an invalid runtime nature. */
    RUNTIME_NATURE_INVALID,
    /** A node runtime nature is not permitted for the requested purpose. */
    RUNTIME_NATURE_NOT_ALLOWED,
    /** The selected runtime does not support a node runtime nature. */
    RUNTIME_NATURE_UNSUPPORTED,
    /** An authored bypass declaration is invalid. */
    BYPASS_INVALID,
    /** Runtime concurrency configuration is invalid. */
    RUNTIME_CONCURRENCY_INVALID,
    /** The graph exceeds a bounded admission limit. */
    GRAPH_LIMIT_EXCEEDED,
    /** A source node lacks the trusted inbound-source capability. */
    SOURCE_CAPABILITY_MISMATCH,
    /** The requested source purpose has no effective source node. */
    SOURCE_REQUIRED,
    /** A source refused listener startup with a declared code. */
    SOURCE_START_REFUSED,
    /** Startup failed without a safe declared classifier. */
    STARTUP_FAILED
}
