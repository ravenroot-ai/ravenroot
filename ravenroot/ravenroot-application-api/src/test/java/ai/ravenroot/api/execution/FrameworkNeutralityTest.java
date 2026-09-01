package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application API must not name an actor framework (CORE-04).
 *
 * <p>Previously that rule was only a convention. A convention is exactly the wrong
 * mechanism here: the leak that matters is not a whole class but a single parameter or return type
 * added in a hurry, and it compiles, passes every functional test and only surfaces when someone
 * tries to build an application without the adapter on the classpath.</p>
 *
 * <p>The check reads compiled classes rather than sources, so it sees what the module actually
 * publishes: a framework type used in a signature, a field, a local variable, an annotation or a cast
 * all leave its name in the constant pool. Documentation does not, which is why the adapters may
 * still be discussed in Javadoc.</p>
 */
class FrameworkNeutralityTest {
    private static final List<String> FORBIDDEN = List.of("pekko", "akka");

    @Test
    void publishesNoActorFrameworkTypeAnywhereInTheApplicationApi() throws Exception {
        Path root = compiledApiRoot();
        var offenders = new ArrayList<String>();

        try (Stream<Path> classes = Files.walk(root)) {
            for (Path classFile : classes.filter(path -> path.toString().endsWith(".class")).toList()) {
                String constants = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1)
                        .toLowerCase(Locale.ROOT);
                FORBIDDEN.stream()
                        .filter(constants::contains)
                        .forEach(name -> offenders.add(root.relativize(classFile) + " references " + name));
            }
        }

        assertEquals(List.of(), offenders,
                "ravenroot-application-api must stay engine-neutral; adapters translate, they do not leak");
    }

    @Test
    void checksSomethingRatherThanAnEmptyDirectory() throws Exception {
        Path root = compiledApiRoot();
        try (Stream<Path> classes = Files.walk(root)) {
            long compiled = classes.filter(path -> path.toString().endsWith(".class")).count();
            // A neutrality assertion over zero files is a green test that proves nothing, and this
            // one resolves its own root from the classpath, so a build layout change could silently
            // empty it.
            assertTrue(compiled > 20, "expected the application API classes on the test classpath, found " + compiled);
        }
    }

    private static Path compiledApiRoot() throws URISyntaxException, IOException {
        Path executionPackage = Path.of(FrameworkNeutralityTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path apiRoot = executionPackage.resolveSibling("classes").resolve("ai").resolve("ravenroot").resolve("api");
        if (!Files.isDirectory(apiRoot)) {
            throw new IOException("Cannot locate the compiled application API under " + apiRoot);
        }
        return apiRoot;
    }
}
