/**
 * Scoped application identities, durable business history and explicit cross-traversal joins.
 *
 * <p>An application correlation ID names a business operation within a tenant, logical deployment
 * and exact graph version. It never substitutes for a transport message ID, request ID, process,
 * traversal, node visit or retry attempt. Original causal identities remain immutable; a complete
 * business join creates a separate deterministic continuation with its own process and traversal.
 * Ordinary joins within one traversal retain their existing independent scope.</p>
 *
 * <p>Authored strict JSON Pointers select from the actual exposed input under operator-owned bounds.
 * A selector is not authority to join a history or announce an expectation. Admission and explicit
 * closure are atomic store decisions, and trusted producer configuration controls manifest/seal
 * announcements. Quiet periods do not imply business completion.</p>
 */
package ai.ravenroot.api.correlation;
