package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral resource-exhaustion regression control for FIX-02 / SEC-08.
 *
 * <p>The original defect re-read a named live NodeList's length in the loop condition in
 * {@link GraphMlDocument#elements}. In the JDK Xerces implementation, {@code getLength()} asks
 * for {@code item(Integer.MAX_VALUE)}. That lookup resumes after the last cached match without
 * remembering that the subtree was exhausted. This fixture places a growing edge suffix after
 * the node/data matches, so repeated named length calls repeatedly scan that unmatched suffix:
 * both the number of matches and suffix length grow with the document. Snapshotting the length
 * before copying the matches avoids that quadratic work.
 *
 * <p>The wildcard loop in {@link GraphMlDocument#preparePropertyGraphView} is deliberately retained.
 * Its matches reach the final element, so it does not repeat the same growing unmatched suffix
 * scan. A syntax-only ban on {@code getLength()} in loop conditions, or a counter treating every
 * such call alike, would reject this benign case. This guard instead measures the real complete
 * {@link GraphMlDocument#read} workload, including parsing, validation, cloning and serialization.
 * Its discriminator is also checked by restoring the actual named-loop defect in an isolated
 * negative-control classpath, without changing the wildcard or the measured input.
 *
 * <p>Asymptotic linear complexity does not impose a finite JVM timing ceiling of exactly the size
 * ratio. Compilation/profile changes, allocation, GC and cache effects can change the effective
 * cost per element between samples. Current-thread CPU time excludes pure descheduling, but does
 * not make those costs disappear; the monotonic wall-clock fallback is also load-sensitive.
 * A single warm-up and independently chosen minima from separate small/large phases therefore
 * cannot establish a load-independent complexity proof.
 *
 * <p>Both immutable byte inputs are prepared before timing. A fixed number of alternating paired
 * warm-ups precedes three adjacent base/scaled measurement pairs, whose order also alternates.
 * The median paired ratio is compared with the unchanged {@link #MAX_ACCEPTABLE_GROWTH_RATIO}
 * threshold. Warm-up is never extended until a ratio passes, and no measured pair is filtered or
 * retried. All CPU/fallback and wall timings, plus VM compilation/GC context, are printed on both
 * success and failure. Those diagnostics explain the measurement context; they never waive a
 * failed assertion. The separate default-topology-budget read retains its 15-second outer limit.
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
    private static final int WARMUP_PAIRS = 4;
    private static final double MAX_ACCEPTABLE_GROWTH_RATIO = 8.0;

    @Test
    void readTimeGrowsLinearlyNotQuadraticallyWithElementCount() {
        byte[] base = document(BASE_NODES, BASE_NODES).getBytes(StandardCharsets.UTF_8);
        byte[] scaled = document(SCALED_NODES, SCALED_NODES).getBytes(StandardCharsets.UTF_8);
        var threads = ManagementFactory.getThreadMXBean();
        var clock = new ReadClock(threads,
                threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        VmState before = vmState();
        var warmups = new ArrayList<Pair>();
        for (int pair = 0; pair < WARMUP_PAIRS; pair++) {
            warmups.add(timePair(base, scaled, clock, pair % 2 != 0));
        }
        var measured = new ArrayList<Pair>();
        for (int pair = 0; pair < TRIALS; pair++) {
            measured.add(timePair(base, scaled, clock, pair % 2 != 0));
        }
        double[] ratios = measured.stream().mapToDouble(Pair::ratio).sorted().toArray();
        double medianRatio = ratios[TRIALS / 2];
        String diagnostic = String.format(Locale.ROOT,
                "GraphMlDocument.read growth: clock=%s, factor=%d, limit=%.2f, median=%.4f, "
                        + "warmups=%s, measured=%s, vmBefore=%s, vmAfter=%s",
                clock.cpuTime() ? "thread-cpu" : "wall-clock", SCALE_FACTOR,
                MAX_ACCEPTABLE_GROWTH_RATIO, medianRatio, warmups, measured, before, vmState());
        System.out.println(diagnostic);
        for (Pair pair : warmups) assertPositive(pair, diagnostic);
        for (Pair pair : measured) assertPositive(pair, diagnostic);
        assertTrue(medianRatio < MAX_ACCEPTABLE_GROWTH_RATIO, () ->
                "GraphMlDocument.read() exceeded the unchanged growth bound; " + diagnostic);
    }

    /**
     * Literal coverage that the document reads at the default budget:
     * exercises {@link GraphMlDocument#read} directly (bypassing {@code SecureGraphMlParser}, which
     * is not where the bug lived — see the class Javadoc) on a document at the exact default
     * topology budget, with the same 15s ceiling and margin already established for this shape by
     * {@code GraphManagerSecurityTest#materializesACombinedGraphAtTheDefaultTopologyBudgetsWithinBoundedTime}
     * without changing that established outer limit. This secondary end-to-end budget does not
     * replace the growth discriminator. Like any wall-clock timeout, it can still be affected by
     * severe host contention; neither timing assertion is an unconditional load-immunity proof.
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

    private record ReadClock(ThreadMXBean threads, boolean cpuTime) {
        long now() { return cpuTime ? threads.getCurrentThreadCpuTime() : System.nanoTime(); }
    }

    private record Sample(long elapsedNanos, long wallNanos) { }

    private record Pair(boolean scaledFirst, Sample base, Sample scaled) {
        double ratio() { return (double) scaled.elapsedNanos() / base.elapsedNanos(); }
        @Override public String toString() {
            return String.format(Locale.ROOT, "{order=%s,base=%s,scaled=%s,ratio=%.4f}",
                    scaledFirst ? "scaled/base" : "base/scaled", base, scaled, ratio());
        }
    }

    private record VmState(long compilationMillis, List<String> collectors) { }

    private static VmState vmState() {
        var compiler = ManagementFactory.getCompilationMXBean();
        long compilationMillis = compiler != null && compiler.isCompilationTimeMonitoringSupported()
                ? compiler.getTotalCompilationTime() : -1;
        var collectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(bean -> bean.getName() + ":count=" + bean.getCollectionCount()
                        + ",millis=" + bean.getCollectionTime()).toList();
        return new VmState(compilationMillis, collectors);
    }

    private static Pair timePair(byte[] base, byte[] scaled, ReadClock clock, boolean scaledFirst) {
        if (scaledFirst) {
            Sample large = timeRead(scaled, clock);
            return new Pair(true, timeRead(base, clock), large);
        }
        Sample small = timeRead(base, clock);
        return new Pair(false, small, timeRead(scaled, clock));
    }

    private static void assertPositive(Pair pair, String diagnostic) {
        assertTrue(pair.base().elapsedNanos() > 0 && pair.scaled().elapsedNanos() > 0
                        && pair.base().wallNanos() > 0 && pair.scaled().wallNanos() > 0,
                () -> "Nonpositive elapsed time cannot discriminate growth; " + diagnostic);
    }

    private static Sample timeRead(byte[] xml, ReadClock clock) {
        long wallStart = System.nanoTime();
        long start = clock.now();
        GraphMlDocument.read(xml);
        long elapsed = clock.now() - start;
        return new Sample(elapsed, System.nanoTime() - wallStart);
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
