package ai.ravenroot.core.plugin.fixture;

/**
 * Test fixture only (PLAT-12): a compiled class deliberately placed under
 * {@code ai.ravenroot.core.}, a reserved root, so {@code ClassFileOwnNameTest} can read its real
 * compiled bytes and prove the reserved-namespace check rejects it — without ever loading it.
 */
public final class ReservedFixtureBehavior {
    private ReservedFixtureBehavior() {
    }
}
