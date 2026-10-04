package ai.ravenroot.api.activity;

/** Trusted policy that removes sensitive content before size validation and durable storage. */
@FunctionalInterface
public interface ActivityRedactor {
  /**
   * Produces the bounded value that may be encoded and persisted.
   * @param kind selected content slot
   * @param value original node value
   * @return a value accepted by Ravenroot's structured payload model
   */
  Object redact(ActivityContentKind kind, Object value);

  /**
   * Creates an explicit no-op for applications that accept storing selected content verbatim.
   * @return identity redactor
   */
  static ActivityRedactor none() {
    return (kind, value) -> value;
  }
}
