package ai.ravenroot.api.activity;

/** One independently selectable part of a node delivery or result. */
public enum ActivityContentKind {
  /** Delivered node payload. */
  INPUT_PAYLOAD,
  /** Delivered node attributes. */
  INPUT_ATTRIBUTES,
  /** Result payload. */
  OUTPUT_PAYLOAD,
  /** Result attributes. */
  OUTPUT_ATTRIBUTES;

  /**
   * @return whether this content is observed before the node is invoked.
   */
  public boolean input() {
    return this == INPUT_PAYLOAD || this == INPUT_ATTRIBUTES;
  }
}
