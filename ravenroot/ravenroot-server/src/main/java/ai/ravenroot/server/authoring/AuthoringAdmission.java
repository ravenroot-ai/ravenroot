package ai.ravenroot.server.authoring;

import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps an admitted slot until work terminates, including interrupt-delayed cancellation. */
final class AuthoringAdmission extends FutureTask<Void> {
    private final Semaphore admission;
    // 0: not started; 1: running; 2: slot released.
    private final AtomicInteger state = new AtomicInteger();

    AuthoringAdmission(Semaphore admission, Runnable work) {
        super(work, null);
        this.admission = admission;
    }

    @Override
    public void run() {
        if (!state.compareAndSet(0, 1)) return;
        try {
            super.run();
        } finally {
            state.set(2);
            admission.release();
        }
    }

    @Override
    protected void done() {
        // FutureTask calls done at cancellation time, potentially before its runner exits.
        // A running task owns release in finally; an unstarted cancelled task will never run.
        if (isCancelled() && state.compareAndSet(0, 2)) admission.release();
    }
}
