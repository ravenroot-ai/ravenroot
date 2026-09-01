package jakarta.ravenroottest.fixture;

/**
 * Test fixture only (PLAT-12): a HOST-side class under {@code jakarta.}, compiled
 * normally as part of this module's own test sources -- used as the REGRESSION guard for the
 * runtime-preference split, not as a proof of anything new. {@code jakarta.} must stay child-first: a
 * bundle's own {@code jakarta.*} content (194 real classes in the dogfooding {@code ravenroot-mail}
 * bundle's Angus Mail dependency) must always win over anything the host happens to also define under
 * the same name, exactly like any other ordinary bundle-private third-party dependency.
 *
 * <p>{@link #origin()} distinguishes this HOST version from a differently-compiled BUNDLE version of
 * the same fully-qualified name. {@code PluginClassLoaderTest} asserts the BUNDLE version wins here,
 * proving the {@code javax.}-only addition to {@code ReservedPluginPackages.
 * isParentFirst} did not quietly also make {@code jakarta.} parent-first -- the same class of mistake
 * the original, since-corrected {@code javax.}/{@code jakarta.} reservation made by lumping both
 * together.</p>
 */
public final class ChildFirstShadowFixture {
    private ChildFirstShadowFixture() {
    }

    public static String origin() {
        return "host";
    }
}
