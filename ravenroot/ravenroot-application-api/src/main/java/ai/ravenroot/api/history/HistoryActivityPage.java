package ai.ravenroot.api.history;

import ai.ravenroot.api.activity.ActivityPage;
import java.util.Objects;

/** Content availability is separate from successful metadata reads and empty retained content.
 * @param availability explicit metadata or content availability without inventing missing data
 * @param page retained content page, absent when capture is disabled
 */
public record HistoryActivityPage(Availability availability, ActivityPage page) {
    /** Capture availability without fabricating an input or output value. */
    public enum Availability {
        /** Retained redacted and bounded captured content is available. */
        AVAILABLE,
        /** The deployment composition has no enabled activity capture. */
        CAPTURE_DISABLED,
        /** No captured content remains in this selected page; this does not prove an empty payload. */
        NOT_RETAINED,
        /** The requested content cursor predates retained capture; reconcile the supplied continuation. */
        EXPIRED
    }
    /** Validates that disabled capture has no content page and retained availability has a page. */
    public HistoryActivityPage {
        Objects.requireNonNull(availability, "availability");
        if ((availability != Availability.CAPTURE_DISABLED) != (page != null))
            throw new IllegalArgumentException("content availability disagrees with page");
    }
}
