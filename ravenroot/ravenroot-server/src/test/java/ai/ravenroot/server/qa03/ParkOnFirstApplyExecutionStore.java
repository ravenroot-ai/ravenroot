package ai.ravenroot.server.qa03;

import ai.ravenroot.api.persistence.ExecutionStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only exact port delegation, perturbing only the first process-state apply after custody/inbox.
 * The real adapter receives every current and future typed store method; advertised capabilities
 * cannot accidentally inherit the interface's Unsupported defaults. No production fault seam exists.
 */
final class ParkOnFirstApplyExecutionStore {
    private ParkOnFirstApplyExecutionStore() { }

    static ExecutionStore wrap(ExecutionStore delegate, Runnable onFirstApply) {
        var reached = new AtomicBoolean();
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                new Class<?>[]{ExecutionStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("apply") && reached.compareAndSet(false, true)) {
                        onFirstApply.run();
                        return new CompletableFuture<>(); // SIGKILL owns this unresolved first write.
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
}
