package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression control for FIX-02.
 *
 * <p><b>The pattern this pins down.</b> {@link GraphMlDocument#elements} used to iterate a
 * <em>live</em> {@link org.w3c.dom.NodeList} — the one {@code Document.getElementsByTagNameNS}
 * returns — by re-reading {@code NodeList.getLength()} in the loop condition:
 *
 * <pre>{@code
 * var nodes = document.getElementsByTagNameNS(namespace, localName);
 * for (int index = 0; index < nodes.getLength(); index++) { ... }
 * }</pre>
 *
 * <p>Xerces does not cache that call across the loop the way a plain {@code List.size()} would:
 * each {@code getLength()} on a live, narrowly-matching NodeList re-walks the subtree. The loop
 * body itself never mutates the document, so nothing here is a document-mutation bug — the cost is
 * entirely inside the repeated rescans, which makes the read quadratic in element count. SEC-08 is
 * enforced by snapshotting the length once before the loop in
 * {@link GraphMlDocument#elements}. This test exists so a future edit that reintroduces the same
 * shape — {@code getLength()} back inside a loop condition on a live NodeList anywhere in this
 * class — fails loudly instead of quietly shipping a resource-exhaustion vector: {@code
 * SecureGraphMlParser} already admits the bytes cheaply (a linear SAX scan under the configured
 * budget), so a quadratic compatibility-layer read is CPU burned <em>inside</em> the component that
 * exists to bound resource consumption. The measured cost was 50.2s of a
 * 60.8s total on the default-budget document, compared with 660ms when the length is read once.
 *
 * <p><b>Why this measures a growth ratio and not an absolute time.</b> Assertions that "operation X
 * completes within N seconds" become flaky under CI load. A stable test avoids wall-clock ceilings
 * rather than widening them, so this test does not assert "reading document A
 * takes less than T". It reads the same shape of document at a base size and at
 * {@value #SCALE_FACTOR}&times; that size, and asserts the <em>ratio</em> of the two durations
 * stays close to the size ratio rather than close to its square. A uniform slowdown from a busy
 * machine multiplies both measurements by the same factor and cancels out of the ratio; it cannot
 * turn this test red. Only an algorithmic change in how {@link GraphMlDocument#read} scales with
 * element count can.
 *
 * <p>Counting DOM operations directly (comparisons, {@code getLength()} calls) was considered and
 * rejected, for a stronger reason than the seam being brittle to build: the syntactic shape is not
 * what discriminates the defect, so a guard built on that shape would misclassify code that is
 * actually fine. {@link GraphMlDocument#preparePropertyGraphView} still calls
 * {@code allElements.getLength()} inside a loop condition today, on the live NodeList
 * {@code document.getElementsByTagName("*")} — the identical syntactic pattern — and is provably
 * benign: measured at 115,212 elements, that loop costs about the same order as a hoisted-length
 * version (roughly 20ms live vs 14ms hoisted); <b>both scale linearly</b>, which is the operative
 * fact, not that the two absolute numbers are close. The mechanism is not match density. Five
 * variants of this exact loop shape (same generated document, varying only which live NodeList and
 * which local name is scanned; measured here at n=600..4800 nodes, thread CPU time, growth per
 * doubling of n): a namespaced match on the dense {@code data} local name (~83% of elements) is
 * quadratic (~16ms at n=600, ~3.9&ndash;4.2&times; per doubling); a namespaced match on the sparse
 * {@code node} local name (~8% of elements) is <em>also</em> quadratic (~2.6ms at n=600,
 * ~3.3&ndash;3.8&times; per doubling) — cheaper in absolute terms because it has fewer matches to
 * find, but the same quadratic shape, not a safer one. Dropping the namespace does not help either:
 * plain {@code getElementsByTagName("data")} is quadratic identically to the namespaced form
 * (~22ms at n=600, ~3.9&ndash;4.0&times; per doubling). A single-match query ({@code graph},
 * matched once) is linear only because the loop body then runs once regardless of per-call cost.
 * The model that fits all five measurements is <b>cost proportional to matches &times; total
 * elements</b>: a <em>named</em> live NodeList rescans the subtree on every {@code getLength()}
 * call, so cost tracks how many matches there are to find, not what fraction of the tree they are —
 * the sparse {@code node} match above costs roughly an order of magnitude less than the dense
 * {@code data} match at the same document size, tracking their ~10x difference in match count, not
 * their densities. {@code getElementsByTagName("*")} is the one case that stays cheap and linear at
 * 100% density (~0.4ms at n=600, ~1.8&ndash;2.0&times; per doubling), and it is safe because Xerces
 * special-cases the wildcard local name, not because a dense match is inherently safer than a
 * sparse one. A guard reasoning from density would judge {@code getElementsByTagNameNS(NS, "data")}
 * inside a loop condition <em>safe</em> because it matches most elements — which is backwards, and
 * is the original defect at its worst measured magnitude, reasoned about incorrectly inside the
 * test that exists to catch it. A guard that instead counted {@code getLength()} calls or matched
 * the loop-condition syntax would flag the provably-benign wildcard line as broken. Only the
 * observed cost tells a benign live-NodeList loop from a quadratic one, independent of namespace,
 * syntax, or density — which is exactly what this test measures. Wrapping
 * {@code org.w3c.dom.Document} in a counting proxy to observe DOM-internal calls directly would
 * also be a large, brittle, implementation-coupled surface for a production API that is otherwise
 * reused as-is here (see the direct {@link GraphMlDocument#read} call below, the same
 * package-private entry point production code uses). Timing, made load-insensitive by measuring a
 * ratio instead of an absolute value, is the smaller and more faithful instrument.
 *
 * <p><b>How the threshold was picked.</b> With a linear scan, total time is
 * {@code overhead + k * n}; growing {@code n} by {@value #SCALE_FACTOR}x should move the ratio
 * <em>towards</em> {@value #SCALE_FACTOR} but never above it (fixed per-call overhead only pulls the
 * observed ratio down, by contributing proportionally more at the smaller size). With a quadratic
 * scan the ratio moves towards {@value #SCALE_FACTOR}<sup>2</sup>. Measured on the workstation that
 * wrote this test (best-of-{@value #TRIALS} per size, thread CPU time): the fixed implementation
 * scored 3.5&ndash;4.5&times; in situ across repeated fresh-JVM runs, approaching ~6&times; — the
 * element-count ratio itself, 5.992 measured — once fully warmed; the vulnerable quadratic loop
 * scored ~28&ndash;32&times;. {@link #MAX_ACCEPTABLE_GROWTH_RATIO} sits at
 * {@value #MAX_ACCEPTABLE_GROWTH_RATIO}. The asymptotic margin between that threshold and a fully
 * warmed correct implementation is only about 1.35&times;, so <b>the threshold's safety does not
 * rest on the observed linear score</b> — it rests on the theoretical ceiling: a linear
 * implementation's ratio cannot exceed {@code SCALE_FACTOR} ({@value #SCALE_FACTOR}, 5.992
 * measured), so {@value #MAX_ACCEPTABLE_GROWTH_RATIO} sits above that ceiling by construction and
 * roughly 4&times; below the observed quadratic failure. Do not tighten this threshold on the
 * strength of a single observed linear run: a colder or busier measurement can land anywhere up to
 * {@code SCALE_FACTOR}, and a threshold chosen to sit just above one observed number rather than
 * above the ceiling would eventually go red on a correct implementation — the same failure mode
 * this suite has already had to repair four times elsewhere.
 */
class GraphMlDocumentQuadraticScanRegressionTest {

    /** Mirrors the property-per-node proportion baked into {@link GraphMlLimits#DEFAULTS}
     * (100_000 properties / 10_000 nodes = 10), so the generated shape matches the real
     * default-budget document rather than an arbitrary one. */
    private static final int PROPERTIES_PER_NODE = 10;
    private static final int BASE_NODES = 600;
    private static final int SCALE_FACTOR = 6;
    private static final int SCALED_NODES = BASE_NODES * SCALE_FACTOR;
    private static final int TRIALS = 3;
    private static final double MAX_ACCEPTABLE_GROWTH_RATIO = 8.0;

    @Test
    void readTimeGrowsLinearlyNotQuadraticallyWithElementCount() {
        // Warm up class loading and JIT on both sizes before any measurement is kept, so neither
        // measured trial below pays a one-off cold-start cost that would distort the ratio.
        timeRead(document(BASE_NODES, BASE_NODES));
        timeRead(document(SCALED_NODES, SCALED_NODES));

        long baseNanos = bestOf(BASE_NODES);
        long scaledNanos = bestOf(SCALED_NODES);
        assertTrue(baseNanos > 0, "measured zero time at the base size; the clock/harness is not "
                + "actually timing the scan, so this test cannot tell a pass from a false positive");

        double ratio = (double) scaledNanos / (double) baseNanos;
        assertTrue(ratio < MAX_ACCEPTABLE_GROWTH_RATIO, () -> String.format(
                "GraphMlDocument.read() grew %.2fx in time for a %dx increase in element count "
                        + "(base=%dns, scaled=%dns). Linear growth stays near %dx; this is closer to "
                        + "the %dx a quadratic scan would produce. This is the exact regression "
                        + "SEC-08 regression: a live org.w3c.dom.NodeList's getLength() re-read "
                        + "inside a loop condition instead of snapshotted once beforehand (see "
                        + "GraphMlDocument#elements and this test's class Javadoc).",
                ratio, SCALE_FACTOR, baseNanos, scaledNanos, SCALE_FACTOR,
                SCALE_FACTOR * SCALE_FACTOR));
    }

    /**
     * Literal coverage that the document reads at the default budget:
     * exercises {@link GraphMlDocument#read} directly (bypassing {@code SecureGraphMlParser}, which
     * is not where the bug lived — see the class Javadoc) on a document at the exact default
     * topology budget, with the same 15s ceiling and margin already established for this shape by
     * {@code GraphManagerSecurityTest#materializesACombinedGraphAtTheDefaultTopologyBudgetsWithinBoundedTime}
     * (current measured cost ~1.3s on this workstation, against the 15s budget). This is a secondary,
     * defense-in-depth check: an absolute ceiling this generous cannot itself go flaky under load,
     * and it exists mainly to bound worst-case CI time if the growth-ratio test above ever does go
     * red, rather than to be the primary discriminator.
     */
    @Test
    void readsTheDefaultTopologyBudgetDocumentWithinAGenerousAbsoluteCeiling() {
        byte[] xml = document(GraphMlLimits.DEFAULTS.maxNodes(), GraphMlLimits.DEFAULTS.maxEdges())
                .getBytes(StandardCharsets.UTF_8);
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            GraphMlDocument parsed = GraphMlDocument.read(xml);
            assertEquals(xml.length, parsed.original().length);
        });
    }

    private static long bestOf(int nodes) {
        long best = Long.MAX_VALUE;
        for (int trial = 0; trial < TRIALS; trial++) {
            best = Math.min(best, timeRead(document(nodes, nodes)));
        }
        return best;
    }

    /**
     * Prefers the calling thread's CPU time over wall-clock: this scan is CPU-bound, so measuring
     * CPU time is immune to the thread merely being descheduled by a busy host, which wall-clock
     * would count against it. Falls back to {@code nanoTime} only if the JVM cannot report thread
     * CPU time.
     */
    private static long timeRead(String documentXml) {
        byte[] xml = documentXml.getBytes(StandardCharsets.UTF_8);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuTimeAvailable = threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        long start = cpuTimeAvailable ? threads.getCurrentThreadCpuTime() : System.nanoTime();
        GraphMlDocument.read(xml);
        long end = cpuTimeAvailable ? threads.getCurrentThreadCpuTime() : System.nanoTime();
        return end - start;
    }

    /**
     * Same element shape (one {@code key} per property, one {@code data} per property per node,
     * self-looping {@code edge}s) as
     * {@code GraphManagerSecurityTest#graphMlAtDefaultTopologyBudgets()}, parameterized by node and
     * edge count so it can be run at both the scaled-down sizes used for the ratio measurement and
     * at the real default budget (10k nodes / 25k edges / 100k properties).
     */
    private static String document(int nodes, int edges) {
        var xml = new StringBuilder("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">");
        for (int key = 0; key < PROPERTIES_PER_NODE; key++) {
            xml.append("<key id=\"k").append(key).append("\" for=\"node\" attr.name=\"p")
                    .append(key).append("\" attr.type=\"string\"/>");
        }
        xml.append("<graph id=\"g\" edgedefault=\"directed\">");
        for (int node = 0; node < nodes; node++) {
            xml.append("<node id=\"n").append(node).append("\">");
            for (int key = 0; key < PROPERTIES_PER_NODE; key++) {
                xml.append("<data key=\"k").append(key).append("\">v</data>");
            }
            xml.append("</node>");
        }
        for (int edge = 0; edge < edges; edge++) {
            xml.append("<edge id=\"e").append(edge).append("\" source=\"n0\" target=\"n0\"/>");
        }
        return xml.append("</graph></graphml>").toString();
    }
}
