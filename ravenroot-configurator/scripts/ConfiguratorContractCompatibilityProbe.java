import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;


/**
 * Executes the exact values emitted by the standalone configurator against every first-party
 * extension resolver. The fixture is generated from the TypeScript registry immediately before
 * this probe, so this is a cross-language compatibility boundary rather than a
 * second handwritten example catalog.
 */
public final class ConfiguratorContractCompatibilityProbe {
    private Properties fixtures;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("fixture path is required");
        var probe = new ConfiguratorContractCompatibilityProbe();
        probe.fixtures = fixtures(Path.of(arguments[0]));
        for (String id : probe.required("ids").split(",")) probe.verify(id);
        System.out.println("Configurator Java resolver compatibility passed: " + probe.required("ids").split(",").length + " contracts");
    }

    private void verify(String id) throws Exception {
        String className = required(id + ".class");
        String mode = required(id + ".mode");
        String environmentKey = required(id + ".environmentKey");
        String valid = required(id + ".valid");
        String[] arguments = required(id + ".args").isEmpty()
                ? new String[0] : required(id + ".args").split("\u001f", -1);
        Class<?> type = Class.forName(className);
        if ("configuration".equals(mode)) {
            Method factory = type.getDeclaredMethod(required(id + ".method"), Map.class);
            factory.setAccessible(true);
            require(factory.invoke(null, Map.of(environmentKey, valid)) != null, id + " rejected configurator output");
            for (int index = 0; index < Integer.parseInt(required(id + ".positiveCount")); index++) {
                Object accepted = factory.invoke(null, Map.of(environmentKey, required(id + ".positive." + index + ".value")));
                require(accepted != null, id + " rejected valid vector " + required(id + ".positive." + index + ".label"));
                verifyAssertion(required(id + ".positive." + index + ".assertion"), accepted, id);
            }
            for (int index = 0; index < Integer.parseInt(required(id + ".negativeCount")); index++) {
                String invalid = required(id + ".negative." + index + ".value");
                try { factory.invoke(null, Map.of(environmentKey, invalid)); throw new AssertionError(id + " accepted negative vector " + index + " (" + required(id + ".negative." + index + ".label") + ")"); }
                catch (InvocationTargetException expected) { require(expected.getCause() != null, id + " rejection lost its cause"); }
            }
            return;
        }
        Constructor<?> constructor = type.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        Method resolve = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(required(id + ".method"))
                        && candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        resolve.setAccessible(true);
        Object accepted = resolve.invoke(constructor.newInstance(Map.of(environmentKey, valid)), (Object[]) arguments);
        require(accepted instanceof Optional<?> && ((Optional<?>) accepted).isPresent(), id + " rejected configurator output");
        String alternate = required(id + ".alternateValid");
        if (!alternate.isEmpty()) {
            Object optionalAccepted = resolve.invoke(constructor.newInstance(Map.of(environmentKey, alternate)), (Object[]) arguments);
            require(optionalAccepted instanceof Optional<?> && ((Optional<?>) optionalAccepted).isPresent(), id + " rejected valid omitted optional fields");
        }
        for (int index = 0; index < Integer.parseInt(required(id + ".positiveCount")); index++) {
            String rawArguments = required(id + ".positive." + index + ".args");
            String[] positiveArguments = rawArguments.isEmpty() ? new String[0] : rawArguments.split("\u001f", -1);
            Object result = resolve.invoke(constructor.newInstance(Map.of(environmentKey, required(id + ".positive." + index + ".value"))),
                    (Object[]) positiveArguments);
            require(result instanceof Optional<?> && ((Optional<?>) result).isPresent(),
                    id + " rejected valid vector " + required(id + ".positive." + index + ".label"));
            verifyAssertion(required(id + ".positive." + index + ".assertion"), ((Optional<?>) result).orElseThrow(), id);
        }
        for (int index = 0; index < Integer.parseInt(required(id + ".negativeCount")); index++) {
            String invalid = required(id + ".negative." + index + ".value");
            String rawArguments = required(id + ".negative." + index + ".args");
            String[] invalidArguments = rawArguments.isEmpty() ? new String[0] : rawArguments.split("\u001f", -1);
            Object refused = resolve.invoke(constructor.newInstance(Map.of(environmentKey, invalid)), (Object[]) invalidArguments);
            require(refused instanceof Optional<?> && ((Optional<?>) refused).isEmpty(), id + " accepted negative vector " + index + " (" + required(id + ".negative." + index + ".label") + ")");
        }
    }

    private static void verifyAssertion(String assertion, Object accepted, String id) throws Exception {
        if (assertion.isEmpty()) return;
        if (!"openapi-normalized".equals(assertion)) throw new AssertionError(id + " has unknown assertion " + assertion);
        Method origin = accepted.getClass().getMethod("origin");
        Method fixedHeaders = accepted.getClass().getMethod("fixedHeaders");
        Method inputHeaders = accepted.getClass().getMethod("allowedInputHeaders");
        Method responseHeaders = accepted.getClass().getMethod("projectedResponseHeaders");
        require("https://api.example.test".equals(origin.invoke(accepted).toString()), id + " did not normalize HTTPS origin");
        require(((Map<?, ?>) fixedHeaders.invoke(accepted)).containsKey("x-mixed-case"), id + " did not normalize fixed header name");
        require(((java.util.Set<?>) inputHeaders.invoke(accepted)).contains("x-request-id"), id + " did not normalize input header name");
        require(((java.util.Set<?>) responseHeaders.invoke(accepted)).contains("etag"), id + " did not normalize response header name");
    }

    private static Properties fixtures(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            Properties result = new Properties();
            result.load(input);
            return result;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private String required(String key) {
        String value = fixtures.getProperty(key);
        if (value == null) throw new IllegalStateException("missing fixture property " + key);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
