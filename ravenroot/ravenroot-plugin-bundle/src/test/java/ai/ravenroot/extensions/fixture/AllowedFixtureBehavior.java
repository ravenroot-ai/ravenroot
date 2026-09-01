package ai.ravenroot.extensions.fixture;

/**
 * Test fixture only (PLAT-12): a compiled class placed under {@code ai.ravenroot.extensions.},
 * the same root {@code ravenroot-mail} already uses and which PLAT-12 deliberately leaves open, so
 * {@code ClassFileOwnNameTest} can prove a legitimate bundle class is accepted.
 */
public final class AllowedFixtureBehavior {
    private AllowedFixtureBehavior() {
    }
}
