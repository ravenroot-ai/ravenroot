package ai.ravenroot.core.runtime;

/** Explicit refusal of personal authority on an external runtime that cannot carry it. */
public final class PersonalExecutionAdmissionException extends IllegalArgumentException {
    public PersonalExecutionAdmissionException() { super("personal-workspace-agent-unavailable"); }
}
