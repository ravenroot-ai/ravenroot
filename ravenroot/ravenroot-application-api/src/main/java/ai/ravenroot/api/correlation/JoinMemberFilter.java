package ai.ravenroot.api.correlation;

/** Scalar membership selection; it never changes the join's completion policy. */
public enum JoinMemberFilter {
    /** Every retained expected or received member. */ ALL,
    /** Declared expected manifest members, whether received or missing. */ EXPECTED,
    /** Distinct accepted business contributions. */ RECEIVED,
    /** Expected manifest members with no accepted contribution. */ MISSING
}
