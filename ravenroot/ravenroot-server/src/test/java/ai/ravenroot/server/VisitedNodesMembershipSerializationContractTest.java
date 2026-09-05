package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins set-membership serialization without making any particular presentation order contractual. */
class VisitedNodesMembershipSerializationContractTest {

    @Test
    void serializesTheSameMembershipIndependentlyOfSourceIterationOrder() throws Exception {
        Set<String> forward = iteratingAs("third", "first", "second");
        Set<String> reverse = iteratingAs("second", "first", "third");

        String forwardJson = serializeSet(forward);
        String reverseJson = serializeSet(reverse);

        assertEquals(forwardJson, reverseJson,
                "wire presentation must depend on membership, not a Set's iteration or traversal order");
        for (String member : forward) {
            assertTrue(forwardJson.contains("\"" + member + "\""),
                    () -> "serialized membership omitted " + member + ": " + forwardJson);
        }
    }

    private static String serializeSet(Set<String> values) throws Exception {
        Method serializer = RavenrootServer.class.getDeclaredMethod("stringArrayJson", Set.class);
        serializer.setAccessible(true);
        return (String) serializer.invoke(null, values);
    }

    private static Set<String> iteratingAs(String... values) {
        List<String> order = List.of(values);
        return new AbstractSet<>() {
            @Override
            public Iterator<String> iterator() {
                return order.iterator();
            }

            @Override
            public int size() {
                return order.size();
            }
        };
    }
}
