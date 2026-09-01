package ai.ravenroot.api.embed;

import java.util.Objects;

/** Closed resolution vocabulary; implementation details never cross the application boundary. */
public sealed interface EmbedProjectionResolution {
/**
 * A projection that may be returned to the authorized embedded client.
 * @param projection render-only projection available to the authorized browser session
 */
    record Available(EmbedGraphProjection projection) implements EmbedProjectionResolution {
        /**
         * Creates an available projection resolution.
         * @param projection the authorized projection.
         */
        public Available {
            Objects.requireNonNull(projection, "projection");
        }
    }

    /** Indicates that the requested projection is unavailable. */
    enum Unavailable implements EmbedProjectionResolution {
        /** The singleton unavailable resolution. */
        INSTANCE
    }

    /** Indicates that the requested projection exceeds an allowed size. */
    enum DataTooLarge implements EmbedProjectionResolution {
        /** The singleton too-large resolution. */
        INSTANCE
    }

    /** Indicates that the caller may retry after a transient condition. */
    enum TemporarilyUnavailable implements EmbedProjectionResolution {
        /** The singleton temporary-unavailability resolution. */
        INSTANCE
    }
}
