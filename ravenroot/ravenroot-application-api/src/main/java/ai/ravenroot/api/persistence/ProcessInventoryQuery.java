package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ProcessInstanceStatus;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * What one page of {@link ExecutionStore#listProcessInstances(String, ProcessInventoryQuery)} asks for.
 *
 * <h2>Filters compose as a conjunction</h2>
 * <p>A row is returned when it satisfies every stated filter. An empty {@link #statuses()} means "any
 * status" rather than "no status", because a query that named nothing and returned nothing would be
 * a silent empty page that reads exactly like an empty tenant.</p>
 *
 * <p>{@link #includeTerminal()} is a separate axis from {@link #statuses()} and not a shorthand for
 * one, because the commonest question — "what is outstanding" — has to be expressible without
 * enumerating the non-terminal statuses and then having to revisit every call site when one is added.
 * The two do interact: a query that names only terminal statuses while excluding terminal rows is
 * self-contradictory, and the store rejects it with
 * {@link ExecutionStoreFailure.InvalidRequest} rather than returning an empty page that a caller
 * would read as "there is none".</p>
 *
 * <h2>What this type validates, and what it does not</h2>
 * <p>It normalises nulls and nothing else. The bounds — a positive limit, a limit within
 * {@link ExecutionStore#maxInventoryPageSize()}, a cursor that decodes and belongs to the calling
 * tenant, and the contradiction above — are all rejected by the <em>store</em>, with
 * {@link ExecutionStoreFailure.InvalidRequest}. That follows the reasoning
 * {@link ExecutionBatch#fencingToken()} already records: the rejection is a property of the store, so
 * a conformance suite must be able to construct the offending query in order to assert that every
 * adapter rejects it, and an adapter receiving a query it did not build locally — as a remote adapter
 * does — would be unguarded by a builder check anyway.</p>
 *
 * @param statuses        lifecycle statuses to include; empty means every status
 * @param ownerWorkerId   restrict to instances whose live lease is held by this worker
 * @param deploymentId    restrict to instances hosted by this deployment
 * @param includeTerminal whether {@code COMPLETED} and {@code FAILED} rows are returned at all
 * @param cursor          opaque page cursor from a previous {@link ProcessInventoryPage#nextCursor()}
 * @param limit           maximum rows in the page
 */
public record ProcessInventoryQuery(Set<ProcessInstanceStatus> statuses, Optional<String> ownerWorkerId,
                                    Optional<String> deploymentId, boolean includeTerminal,
                                    Optional<String> cursor, int limit) {

    /** Copies the status set and normalises absent filters; every bound is the store's to enforce. */
    public ProcessInventoryQuery {
        statuses = statuses == null || statuses.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(statuses));
        ownerWorkerId = ownerWorkerId == null ? Optional.empty() : ownerWorkerId;
        deploymentId = deploymentId == null ? Optional.empty() : deploymentId;
        cursor = cursor == null ? Optional.empty() : cursor;
    }

    /**
     * The first page of everything still outstanding: every status, terminal rows excluded.
     * @param limit maximum rows in the page.
     * @return a query for the first page of non-terminal work.
     */
    public static ProcessInventoryQuery outstanding(int limit) {
        return builder().limit(limit).build();
    }

    /**
     * The first page of everything the tenant retains, terminal rows included.
     * @param limit maximum rows in the page.
     * @return a query for the first page of all retained work.
     */
    public static ProcessInventoryQuery everything(int limit) {
        return builder().includeTerminal(true).limit(limit).build();
    }

    /**
     * The same query, continued after a page cursor.
     * @param nextCursor cursor taken from a previous page.
     * @return a copy of this query positioned after {@code nextCursor}.
     */
    public ProcessInventoryQuery after(String nextCursor) {
        return new ProcessInventoryQuery(statuses, ownerWorkerId, deploymentId, includeTerminal,
                Optional.ofNullable(nextCursor), limit);
    }

    /**
     * Whether the conjunction of {@link #statuses()} and {@link #includeTerminal()} can match nothing.
     *
     * <p>Exposed so an adapter states the contradiction the same way rather than each re-deriving it.
     * True only when every named status is terminal <em>and</em> terminal rows are excluded; a mixed
     * filter such as {@code {RUNNING, COMPLETED}} with terminal rows excluded is meaningful and
     * returns the running ones.</p>
     * @return {@code true} when no row could ever satisfy this query.
     */
    public boolean isSelfContradictory() {
        return !includeTerminal && !statuses.isEmpty()
                && statuses.stream().allMatch(ProcessInstanceStatus::terminal);
    }

    /**
     * Whether {@code status} passes this query's status axis.
     * @param status the candidate row's authoritative status.
     * @return whether the row survives the status and terminal filters.
     */
    public boolean admits(ProcessInstanceStatus status) {
        if (!includeTerminal && status.terminal()) {
            return false;
        }
        return statuses.isEmpty() || statuses.contains(status);
    }

    /**
     * A mutable builder, so adding a filter later stays source-compatible for every call site.
     * @return a builder whose defaults are "every status, no terminal rows, first page".
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates one query. Not thread-safe, and not intended to be reused across calls. */
    public static final class Builder {
        private final EnumSet<ProcessInstanceStatus> statuses = EnumSet.noneOf(ProcessInstanceStatus.class);
        private String ownerWorkerId;
        private String deploymentId;
        private boolean includeTerminal;
        private String cursor;
        private int limit = 50;

        private Builder() {
        }

        /**
         * Restricts the page to one lifecycle status; repeatable.
         * @param status status to admit.
         * @return this builder.
         */
        public Builder status(ProcessInstanceStatus status) {
            statuses.add(status);
            return this;
        }

        /**
         * Restricts the page to several lifecycle statuses.
         * @param values statuses to admit.
         * @return this builder.
         */
        public Builder statuses(Set<ProcessInstanceStatus> values) {
            if (values != null) {
                statuses.addAll(values);
            }
            return this;
        }

        /**
         * Restricts the page to instances leased by one worker.
         * @param workerId the lease holder to match.
         * @return this builder.
         */
        public Builder ownedBy(String workerId) {
            this.ownerWorkerId = workerId;
            return this;
        }

        /**
         * Restricts the page to instances hosted by one deployment.
         * @param deployment the hosting deployment to match.
         * @return this builder.
         */
        public Builder hostedBy(String deployment) {
            this.deploymentId = deployment;
            return this;
        }

        /**
         * Includes retained terminal rows, which are excluded by default.
         * @param include whether terminal rows are returned.
         * @return this builder.
         */
        public Builder includeTerminal(boolean include) {
            this.includeTerminal = include;
            return this;
        }

        /**
         * Continues after a previous page's cursor.
         * @param value opaque cursor, or {@code null} for the first page.
         * @return this builder.
         */
        public Builder cursor(String value) {
            this.cursor = value;
            return this;
        }

        /**
         * Sets the page size. The store rejects a value outside its own bound.
         * @param value maximum rows in the page.
         * @return this builder.
         */
        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        /**
         * Freezes the accumulated filters.
         * @return the immutable query.
         */
        public ProcessInventoryQuery build() {
            return new ProcessInventoryQuery(statuses, Optional.ofNullable(ownerWorkerId),
                    Optional.ofNullable(deploymentId), includeTerminal, Optional.ofNullable(cursor),
                    limit);
        }
    }
}
