package ai.ravenroot.api.activity;

/** What graph execution does when selected activity content cannot be archived. */
public enum ActivityFailurePolicy {
  /** Continue processing and emit a payload-safe warning. */
  BEST_EFFORT,
  /** Fail the bounded capture boundary instead of claiming successful persistence. */
  STRICT
}
