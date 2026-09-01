package ai.ravenroot.api.embed;

import java.util.Objects;

/**
 * Closed read vocabulary for {@link EmbedRegistrationAuthority#resolveCurrent}.
 *
 * <p>{@link Unavailable} covers absent, revoked, foreign-tenant and workload-mismatch alike: the
 * caller is an HTTP adapter answering an unauthenticated-to-this-registration party, and telling it
 * <em>which</em> of those four it was is an oracle for enumerating registration ids.
 * {@link Temporary} means the store could not answer and is the only member that invites a retry.</p>
 */
public sealed interface EmbedRegistrationResolution {

    /** A current aggregate the workload may use to create a session.
     * @param aggregate non-null current registration aggregate
     */
    record Available(EmbedRegistrationAggregate aggregate) implements EmbedRegistrationResolution {
        /** Rejects a missing aggregate. */
        public Available {
            Objects.requireNonNull(aggregate, "aggregate");
        }
    }

    /** Non-disclosing result for absent, revoked, foreign, or mismatched registrations. */
    enum Unavailable implements EmbedRegistrationResolution { /** Singleton result. */ INSTANCE }

    /** Store could not answer; callers may retry without inferring registration state. */
    enum Temporary implements EmbedRegistrationResolution { /** Singleton result. */ INSTANCE }
}
