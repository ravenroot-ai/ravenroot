package ai.ravenroot.api.activity;

import java.util.concurrent.CompletionStage;

/** Consumer-supplied durable node-content archive, independent of the execution journal. */
public interface ActivityArchive extends AutoCloseable {
  /** @return maximum page size accepted by {@link #read} */
  int maximumPageSize();

  /**
   * Idempotently appends one event or fails on conflicting content under the same event id.
   * @param event bounded redacted event
   * @return asynchronous durable acknowledgement
   */
  CompletionStage<ActivityAppendResult> append(ActivityEvent event);

  /**
   * Reads only the named tenant; implementations must not fetch mixed-tenant rows then filter.
   * @param tenantId authenticated tenant identity
   * @param query bounded incremental query
   * @return asynchronous retained page
   */
  CompletionStage<ActivityPage> read(String tenantId, ActivityQuery query);

  /**
   * Physically removes expired content. Service adapters invoke this periodically while enabled;
   * custom archives should provide equivalent maintenance when retention is required.
   * @return number of physically deleted rows
   */
  default CompletionStage<Integer> pruneExpired() {
    return java.util.concurrent.CompletableFuture.completedFuture(0);
  }

  /** Releases archive-owned resources. */
  @Override
  default void close() {}
}
