package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExecutionEngineProviderPolicyTest {
    private static final String UNSUPPORTED_POLICY_MESSAGE =
            "Execution engine provider does not support the requested policy";
    private static final ExecutionEngine ENGINE = testEngine();

    @Test
    void historicalProviderDelegatesOnlyTheExactFrozenLegacyPolicy() {
        var provider = new HistoricalProvider();

        assertSame(ENGINE, provider.create("legacy-system", ExecutionEnginePolicy.FROZEN_LEGACY));
        assertEquals("legacy-system", provider.systemName);
        assertEquals(1, provider.creations);

        var reconstructedLegacy = new ExecutionEnginePolicy(10_000, Duration.ofSeconds(10), 1_024);
        assertNotSame(ExecutionEnginePolicy.FROZEN_LEGACY, reconstructedLegacy);
        assertSame(ENGINE, provider.create("reconstructed-legacy-system", reconstructedLegacy));
        assertEquals("reconstructed-legacy-system", provider.systemName);
        assertEquals(2, provider.creations);

        for (ExecutionEnginePolicy changed : List.of(
                new ExecutionEnginePolicy(9_999, Duration.ofSeconds(10), 1_024),
                new ExecutionEnginePolicy(10_000, Duration.ofSeconds(9), 1_024),
                new ExecutionEnginePolicy(10_000, Duration.ofSeconds(10), 1_023))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> provider.create("refused-system", changed));
            assertEquals(UNSUPPORTED_POLICY_MESSAGE, failure.getMessage());
            assertNull(failure.getCause());
        }
        assertEquals(2, provider.creations);

        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> provider.create("refused-system", null));
        assertEquals("policy", missing.getMessage());
        assertNull(missing.getCause());
        assertEquals(2, provider.creations);
    }

    @Test
    void historicalEngineCompatibilityFingerprintIsEmpty() {
        assertEquals("", ENGINE.compatibilityFingerprint());
    }

    @Test
    void serviceLoaderRegistryForwardsTheExactPolicyIdentity(
            @TempDir Path temporary) throws Exception {
        Path descriptor = temporary.resolve("META-INF/services/")
                .resolve(ExecutionEngineProvider.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, CapturingProvider.class.getName() + System.lineSeparator());

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ExecutionEngine created = null;
        CapturingProvider.reset();
        try (var loader = new URLClassLoader(
                new java.net.URL[] {temporary.toUri().toURL()}, previous)) {
            try {
                thread.setContextClassLoader(loader);
                var policy = new ExecutionEnginePolicy(31, Duration.ofNanos(7), 19);
                created = ExecutionEngines.create("CAPTURE-POLICY", "captured-system", policy);

                assertSame(ENGINE, created);
                assertSame(policy, CapturingProvider.policy);
                assertEquals("captured-system", CapturingProvider.systemName);
                assertEquals(1, CapturingProvider.policyCreations);
                assertEquals(0, CapturingProvider.historicalCreations);
            } finally {
                thread.setContextClassLoader(previous);
                if (created != null) created.close();
            }
        }
    }

    private static ExecutionEngine testEngine() {
        return (ExecutionEngine) Proxy.newProxyInstance(
                ExecutionEngine.class.getClassLoader(),
                new Class<?>[] {ExecutionEngine.class},
                (proxy, method, arguments) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(
                                proxy, method, arguments == null ? new Object[0] : arguments);
                    }
                    if (method.getName().equals("close") && method.getParameterCount() == 0) return null;
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "test execution engine";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class HistoricalProvider implements ExecutionEngineProvider {
        private String systemName;
        private int creations;

        @Override
        public String id() {
            return "historical";
        }

        @Override
        public ExecutionEngine create(String systemName) {
            this.systemName = systemName;
            creations++;
            return ENGINE;
        }
    }

    public static final class CapturingProvider implements ExecutionEngineProvider {
        private static ExecutionEnginePolicy policy;
        private static String systemName;
        private static int policyCreations;
        private static int historicalCreations;

        public CapturingProvider() {
        }

        @Override
        public String id() {
            return "capture-policy";
        }

        @Override
        public ExecutionEngine create(String systemName) {
            historicalCreations++;
            return ENGINE;
        }

        @Override
        public ExecutionEngine create(String systemName, ExecutionEnginePolicy policy) {
            CapturingProvider.systemName = systemName;
            CapturingProvider.policy = policy;
            policyCreations++;
            return ENGINE;
        }

        private static void reset() {
            policy = null;
            systemName = null;
            policyCreations = 0;
            historicalCreations = 0;
        }
    }
}
