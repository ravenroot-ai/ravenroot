package ai.ravenroot.api.persistence;

/** Closed set of Human Task presentation authorities. */
public enum HumanTaskPresentationKind {
    /** Compatibility presentation without built-in or registered interaction metadata. */
    CLASSIC,
    /** Closed built-in resolve, deny, and cancel confirmation. */
    CONFIRMATION,
    /** Closed built-in typed form. */
    FORM,
    /** Operator-registered custom HTML rendered through the sandbox host protocol. */
    CUSTOM,
    /** Operator-configured external interaction provider. */
    EXTERNAL
}
