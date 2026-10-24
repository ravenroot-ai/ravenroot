/**
 * Bounded read-only views of retained business histories and actual execution structure.
 *
 * <p>A reader captures a logical deployment, physical incarnation and immutable graph version.
 * Every operation validates the authenticated scope and rechecks that captured source around
 * bounded storage access. Cursors retain the same selection and filters; they never grant access.</p>
 *
 * <p>Process, traversal, visit, retry-attempt and causal-parent identities are separate facts.
 * Metadata paging does not load a whole execution aggregate or retained join contribution data.
 * Captured input and output remain governed by the existing activity capture, redaction and
 * retention policy. Missing timing, classifiers or content are explicitly unavailable rather than
 * fabricated; legacy traversals do not acquire invented application identities.</p>
 */
package ai.ravenroot.api.history;
