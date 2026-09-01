package ai.ravenroot.extensions.mail;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MailSendNodeBehavior.Admission.release()} was safe only by an accident of call
 * order -- exactly one caller, reached through a {@code supplyAsync} capture that happens to
 * establish a happens-before. It carried none of the synchronization its siblings
 * ({@code KafkaRuntimeControls.Admission}, {@code AmqpRuntimeControls.Admission}) have. This test
 * exercises the property that synchronization is supposed to guarantee directly: releasing the
 * same {@code Admission} many times concurrently must still release each underlying permit
 * exactly once. With plain, non-synchronized fields cleared individually, this is a genuine data
 * race and can over-release a semaphore under concurrent callers. A single {@code released} guard
 * under {@code synchronized}, mirroring the siblings, prevents that
 * regardless of scheduling.
 */
class MailAdmissionDoubleReleaseTest {

    @Test void concurrentDoubleReleaseNeverOverReleasesAnyPermit() throws Exception {
        Class<?> behaviorClass = MailSendNodeBehavior.class;
        Class<?> gateLeaseClass = Class.forName("ai.ravenroot.extensions.mail.MailSendNodeBehavior$GateLease");
        Class<?> gateClass = Class.forName("ai.ravenroot.extensions.mail.MailSendNodeBehavior$Gate");
        Class<?> admissionClass = Class.forName("ai.ravenroot.extensions.mail.MailSendNodeBehavior$Admission");

        Method leaseMethod = behaviorClass.getDeclaredMethod("lease", ConcurrentHashMap.class, String.class, int.class);
        leaseMethod.setAccessible(true);
        Field gateField = gateLeaseClass.getDeclaredField("gate");
        gateField.setAccessible(true);
        Field slotsField = gateClass.getDeclaredField("slots");
        slotsField.setAccessible(true);

        Object tenantLease = leaseMethod.invoke(null, new ConcurrentHashMap<String, Object>(), "tenant", 1);
        Object profileLease = leaseMethod.invoke(null, new ConcurrentHashMap<String, Object>(), "profile", 1);
        Object actionLease = leaseMethod.invoke(null, new ConcurrentHashMap<String, Object>(), "action", 1);

        Semaphore tenantSlots = (Semaphore) slotsField.get(gateField.get(tenantLease));
        Semaphore profileSlots = (Semaphore) slotsField.get(gateField.get(profileLease));
        Semaphore actionSlots = (Semaphore) slotsField.get(gateField.get(actionLease));
        // Mirror what Admission.acquire() does before returning an admitted Admission: each gate
        // starts with a single permit and that permit is held for the duration of the call.
        tenantSlots.acquire();
        profileSlots.acquire();
        actionSlots.acquire();

        Constructor<?> admissionCtor = admissionClass.getDeclaredConstructor(gateLeaseClass, gateLeaseClass, gateLeaseClass);
        admissionCtor.setAccessible(true);
        Object admission = admissionCtor.newInstance(tenantLease, profileLease, actionLease);
        for (String held : new String[] {"tenantHeld", "profileHeld", "actionHeld"}) {
            Field field = admissionClass.getDeclaredField(held);
            field.setAccessible(true);
            field.setBoolean(admission, true);
        }

        Method releaseMethod = admissionClass.getDeclaredMethod("release");
        releaseMethod.setAccessible(true);

        int racers = 32;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[racers];
        for (int i = 0; i < racers; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    releaseMethod.invoke(admission);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            });
            threads[i].start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "all racing threads must be lined up before release() is invoked");
        go.countDown();
        for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(1, tenantSlots.availablePermits(), "32 concurrent releases must release the tenant permit exactly once");
        assertEquals(1, profileSlots.availablePermits(), "32 concurrent releases must release the profile permit exactly once");
        assertEquals(1, actionSlots.availablePermits(), "32 concurrent releases must release the action permit exactly once");

        // A further sequential release (as GraphRunner's error/finally paths could still trigger,
        // e.g. a caller bug or a future second release site) must remain a no-op too.
        releaseMethod.invoke(admission);
        assertEquals(1, tenantSlots.availablePermits());
        assertEquals(1, profileSlots.availablePermits());
        assertEquals(1, actionSlots.availablePermits());
    }
}
