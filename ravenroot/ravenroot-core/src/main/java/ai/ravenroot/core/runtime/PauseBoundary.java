package ai.ravenroot.core.runtime;

/**
 * The facts about the point a hold is withholding a hop at, and whether that point can be written
 * down.
 *
 * <h2>Why this is a value rather than six conditions inside the gate</h2>
 * <p>Every clause below excludes a shape whose continuation would need state this system does not
 * persist, and every one of them fails in the <em>unsafe</em> direction: dropping a clause does not
 * make a hold stop working, it makes the runtime write a record that is wrong on resume, and the
 * wrongness only shows up after a restart. Inline in the gate, each clause was reachable only
 * through whatever graph shape happened to produce it, and three of them were not reachable at all
 * from any graph a test could write — so a regression deleting one changed no test. Here each clause
 * is a fact with a name and can be asserted directly, and the shapes that <em>are</em> reachable
 * end-to-end are asserted that way as well.</p>
 *
 * @param storeKeepsHolds      whether a store that records holds is composed and this run holds its
 *                             fence; without both there is nothing to write to, and the
 *                             process-local hold is exactly the right behaviour
 * @param parentInvocations    how many completed invocations this hop was dispatched from. Anything
 *                             but one is excluded: zero is the traversal's first node, which has no
 *                             predecessor to anchor a continuation to, and more than one is a merge
 *                             whose several parents are a fan-in's business rather than a single
 *                             dispatch's
 * @param enteringFanIn        whether the withheld node is a declared fan-in. A hop entering one is
 *                             one arrival of a correlation the join store owns; continuing it alone
 *                             would present that arrival twice.
 *                             <p><strong>Redundant today, and kept deliberately.</strong> A hop
 *                             reaching the gate at a fan-in always carries a lap entry, because the
 *                             firing that let it through writes one, so {@code insideIteration}
 *                             already excludes it. That is a consequence of how laps happen to be
 *                             encoded rather than a statement about fan-ins, and a change to that
 *                             encoding would silently remove a guard nobody had decided to remove.
 *                             The intent is stated here instead of being derived.</p>
 * @param insideIteration      whether the withheld dispatch carries an iteration lap. A lap is what
 *                             keeps a retry and a second pass through the same node distinguishable,
 *                             and it lives only in the runner
 * @param everFannedOut        whether this traversal has ever dispatched more than one successor from
 *                             one node. A continuation resumes one hop, so writing one for a
 *                             traversal that has more than one branch would silently discard the
 *                             others on the restart the record exists to survive
 * @param unfinishedInvocation whether any invocation of this traversal is still non-terminal.
 *                             <p><strong>Not reachable from a graph today, and kept deliberately.</strong>
 *                             With {@code everFannedOut} false, the only invocation that could be
 *                             non-terminal at the gate is this hop's own predecessor, and it must
 *                             have completed in order to dispatch. It is the backstop for the case
 *                             {@code everFannedOut} is a conservative proxy for — a branch genuinely
 *                             still inside a node — and for a stored aggregate carrying an invocation
 *                             frozen non-terminal by an earlier run, which
 *                             {@code GraphRunner.ExecutionState#terminal} documents as a real
 *                             residue. Asserted here because no graph can produce it.</p>
 */
record PauseBoundary(boolean storeKeepsHolds, int parentInvocations, boolean enteringFanIn,
                     boolean insideIteration, boolean everFannedOut, boolean unfinishedInvocation) {

    /**
     * Whether the continuation of this boundary can be written down.
     *
     * <p>The payload is <em>not</em> a clause here, and that is deliberate: whether a value can be
     * represented is answered by the payload type model rather than by the runtime, so the caller
     * asks it separately and this record stays a statement about the traversal's shape.</p>
     *
     * @return {@code true} only when every clause admits the boundary.
     */
    boolean writable() {
        return storeKeepsHolds
                && parentInvocations == 1
                && !enteringFanIn
                && !insideIteration
                && !everFannedOut
                && !unfinishedInvocation;
    }
}
