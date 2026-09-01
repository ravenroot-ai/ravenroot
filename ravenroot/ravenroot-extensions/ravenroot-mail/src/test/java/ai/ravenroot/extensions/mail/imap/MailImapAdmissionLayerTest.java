package ai.ravenroot.extensions.mail.imap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailImapAdmissionLayerTest {
    @Test void globalAndTenantCeilingsPreserveCrossTenantHeadroomAndCleanGates() throws Exception {
        List<Object> global = new ArrayList<>();
        for (int i = 0; i < 32; i++) { Object admission = acquire("tenant" + i, profile("tenant" + i, "profile" + i, 16), 16, new ConcurrentHashMap<>()); assertTrue(acquired(admission)); global.add(admission); }
        assertFalse(acquired(acquire("overflow", profile("overflow", "overflow", 16), 16, new ConcurrentHashMap<>())));
        global.forEach(MailImapAdmissionLayerTest::release); assertGatesEmpty();

        List<Object> tenant = new ArrayList<>();
        for (int i = 0; i < 16; i++) { Object admission = acquire("shared", profile("shared", "profile" + i, 16), 16, new ConcurrentHashMap<>()); assertTrue(acquired(admission)); tenant.add(admission); }
        assertFalse(acquired(acquire("shared", profile("shared", "overflow", 16), 16, new ConcurrentHashMap<>())));
        Object crossTenant = acquire("other", profile("other", "profile", 16), 16, new ConcurrentHashMap<>()); assertTrue(acquired(crossTenant));
        release(crossTenant); tenant.forEach(MailImapAdmissionLayerTest::release); assertGatesEmpty();
    }

    @Test void profileAndActionTighteningAggregateIndependentlyAndRecover() throws Exception {
        List<Object> profileAdmissions = new ArrayList<>();
        for (int i = 0; i < 2; i++) { Object admission = acquire("tenant", profile("tenant", "shared", 2), 16, new ConcurrentHashMap<>()); assertTrue(acquired(admission)); profileAdmissions.add(admission); }
        assertFalse(acquired(acquire("tenant", profile("tenant", "shared", 2), 16, new ConcurrentHashMap<>())));
        profileAdmissions.forEach(MailImapAdmissionLayerTest::release); assertGatesEmpty();

        ConcurrentHashMap<String, Object> sharedAction = new ConcurrentHashMap<>();
        Object first = acquire("tenant", profile("tenant", "one", 16), 1, sharedAction); assertTrue(acquired(first));
        assertFalse(acquired(acquire("tenant", profile("tenant", "two", 16), 1, sharedAction)));
        release(first); assertGatesEmpty(); assertTrue(sharedAction.isEmpty());
        Object recovered = acquire("tenant", profile("tenant", "two", 16), 1, sharedAction); assertTrue(acquired(recovered)); release(recovered); assertGatesEmpty();
    }

    private static ImapProfile profile(String tenant, String id, int limit) { return new ImapProfile(tenant, id, "localhost", 993, "IMAPS", "reader", "credential", Set.of("INBOX"), 1_000, 1_000, limit, 10, 10); }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static Object acquire(String tenant, ImapProfile profile, int actionLimit, ConcurrentHashMap<?, ?> actions) throws Exception {
        Class<?> type = Class.forName(MailImapQueryNodeBehavior.class.getName() + "$Admission");
        Method acquire = type.getDeclaredMethod("acquire", String.class, ImapProfile.class, int.class, ConcurrentHashMap.class); acquire.setAccessible(true);
        return acquire.invoke(null, tenant, profile, actionLimit, (ConcurrentHashMap) actions);
    }
    private static boolean acquired(Object admission) { try { Method method = admission.getClass().getDeclaredMethod("acquired"); method.setAccessible(true); return (boolean) method.invoke(admission); } catch (Exception failure) { throw new AssertionError(failure); } }
    private static void release(Object admission) { try { Method method = admission.getClass().getDeclaredMethod("release"); method.setAccessible(true); method.invoke(admission); } catch (Exception failure) { throw new AssertionError(failure); } }
    @SuppressWarnings("unchecked") private static void assertGatesEmpty() throws Exception { for (String name : List.of("TENANT_SLOTS", "PROFILE_SLOTS")) { Field field = MailImapQueryNodeBehavior.class.getDeclaredField(name); field.setAccessible(true); assertTrue(((ConcurrentHashMap<String, ?>) field.get(null)).isEmpty(), name); } }
}
