package ai.ravenroot.api.history;

import ai.ravenroot.api.activity.ActivityPage;
import java.util.Objects;

/** Content availability is separate from successful metadata reads and empty retained content. */
public record HistoryActivityPage(Availability availability, ActivityPage page) {
    public enum Availability { AVAILABLE, CAPTURE_DISABLED, NOT_RETAINED, EXPIRED }
    public HistoryActivityPage {
        Objects.requireNonNull(availability, "availability");
        if ((availability != Availability.CAPTURE_DISABLED) != (page != null))
            throw new IllegalArgumentException("content availability disagrees with page");
    }
}
