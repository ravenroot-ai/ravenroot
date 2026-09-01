package ai.ravenroot.api.execution;

/** Framework interpretation of an incoming node command. */
public enum NodeDirective {
    /** Execute the target normally. */
    PROCESS,
    /** Traverse the target without constructing or invoking its behavior. */
    PASSTHROUGH,
    /** Deliver an explicitly catalog-admitted application command. */
    APPLICATION
}
