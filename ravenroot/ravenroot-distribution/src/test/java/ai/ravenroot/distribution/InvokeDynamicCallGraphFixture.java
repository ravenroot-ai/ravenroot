package ai.ravenroot.distribution;

import java.util.Optional;

/**
 * Fixture for the {@code invokedynamic} call-graph regression. Deliberately not shaped like
 * production code: two entry points, each reaching {@link
 * #markerMethod()} through a different {@code invokedynamic} mechanism, so {@link
 * ClassGraphIndexInvokeDynamicRegressionTest} can assert {@link ClassGraphIndex#registerCallPathFrom}
 * follows both - a lambda (whose body javac compiles to a synthetic method on this class, reached via
 * {@code invokedynamic} at the call site) and a method reference (whose {@code invokedynamic}
 * bootstrap {@code Handle} points directly at an existing method, no synthetic wrapper). This class
 * is never instantiated or run; only its compiled bytecode is scanned.
 */
final class InvokeDynamicCallGraphFixture {

    private InvokeDynamicCallGraphFixture() {
    }

    /** Reaches {@link #markerMethod()} through a lambda - the exact shape of the shutdown-hook
     * lambda the regression analysis injected into {@code RavenrootServerMain#main}. */
    static void viaLambda(Optional<String> value) {
        value.ifPresent(v -> markerMethod());
    }

    /** Reaches {@link #markerMethod()} through a method reference - no synthetic lambda body; the
     * invokedynamic's bootstrap Handle names {@link #consumeAndMark(String)} directly. */
    static void viaMethodReference(Optional<String> value) {
        value.ifPresent(InvokeDynamicCallGraphFixture::consumeAndMark);
    }

    private static void consumeAndMark(String value) {
        markerMethod();
    }

    static void markerMethod() {
    }
}
