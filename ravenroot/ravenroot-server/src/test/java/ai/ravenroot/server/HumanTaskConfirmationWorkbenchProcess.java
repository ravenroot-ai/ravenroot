package ai.ravenroot.server;

/**
 * Test-only child-JVM boundary for the Human Task confirmation workbench restart harness.
 *
 * <p>The parent supplies the fixed backend port and SQLite directories through the ordinary server
 * environment. This class deliberately adds no product hook or test route: it only makes the child
 * main identity explicit in the transcript and then enters the packaged server composition.</p>
 */
public final class HumanTaskConfirmationWorkbenchProcess {
    /** First-child readiness marker, emitted only by the completed fixture composition. */
    public static final String TASKS_READY = "HUMAN_TASK_CONFIRMATION_TASKS_READY";
    /** Recovery-child readiness marker, emitted only after durable discovery completes. */
    public static final String RECOVERY_READY = "HUMAN_TASK_CONFIRMATION_RECOVERY_READY";

    private HumanTaskConfirmationWorkbenchProcess() {
    }

    /** Launches the unmodified packaged server in a dedicated JVM. */
    public static void main(String[] args) throws InterruptedException {
        RavenrootServerMain.main(args);
    }
}
