package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller compiled before a widening of the result record must still <em>link</em> — not merely
 * recompile — against the constructor shape it was built with.
 *
 * <p>All published shapes are pinned, because each widening puts a different one at risk. Adding
 * {@code bypassedNodes} put the five-component shape at risk; adding {@code handledFailureNodes} put
 * the <strong>six</strong>-component canonical constructor at risk; adding {@code untakenEdges} puts the
 * <strong>seven</strong>-component canonical
 * constructor at risk. Pinning only the older ones leaves this test green while the shape the newest
 * change actually threatens goes unexercised, so each widening adds its predecessor here rather than
 * replacing it.</p>
 */
class GraphExecutionResultBinaryCompatibilityTest {
    private static final List<Class<?>> HISTORICAL = List.of(UUID.class, UUID.class, Object.class,
            java.util.Set.class, java.util.Set.class);
    private static final String ARGUMENTS = "java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, "
            + "java.util.Set.<String>of(), java.util.Set.<String>of()";

    /** The canonical shape with the five above plus {@code bypassedNodes}. */
    private static final List<Class<?>> HISTORICAL_WITH_BYPASSED = List.of(UUID.class, UUID.class, Object.class,
            java.util.Set.class, java.util.Set.class, java.util.Set.class);
    private static final String ARGUMENTS_WITH_BYPASSED = ARGUMENTS + ", java.util.Set.<String>of()";

    /** The canonical shape with the six above plus {@code handledFailureNodes}. */
    private static final List<Class<?>> HISTORICAL_WITH_HANDLED_FAILURES = List.of(UUID.class, UUID.class,
            Object.class, java.util.Set.class, java.util.Set.class, java.util.Set.class, java.util.Set.class);
    private static final String ARGUMENTS_WITH_HANDLED_FAILURES = ARGUMENTS_WITH_BYPASSED
            + ", java.util.Set.<String>of()";

    @Test
    void historicalResultConstructorLinksAndIsPresentInTheCompiledClassFile() throws Exception {
        assertTrue(links(HISTORICAL, ARGUMENTS));
        assertTrue(descriptors().contains(descriptor(HISTORICAL)));
    }

    /**
     * Compatibility obligation for a caller compiled against the six-component canonical constructor —
     * {@code ravenroot-sample} and every out-of-reactor embedder among them — invokes a six-component
     * descriptor that no longer is the canonical one. Source compatibility does not cover this: the
     * caller is not recompiled, and a missing descriptor surfaces as {@code NoSuchMethodError} at the
     * call site, which is exactly what {@link #links} provokes.
     */
    @Test
    void theSixComponentConstructorTheSeventhComponentWidenedStillLinks() throws Exception {
        assertTrue(links(HISTORICAL_WITH_BYPASSED, ARGUMENTS_WITH_BYPASSED),
                "a caller compiled against the six-component constructor no longer links");
        assertTrue(descriptors().contains(descriptor(HISTORICAL_WITH_BYPASSED)),
                "the six-component descriptor is absent from the compiled class file: "
                        + descriptors());
    }

    /**
     * The next compatibility obligation, the same shape as {@link
     * #theSixComponentConstructorTheSeventhComponentWidenedStillLinks()} one widening later: a caller
     * compiled against the seven-component canonical constructor invokes a descriptor that is no
     * longer canonical once {@code untakenEdges} is added as the eighth.
     */
    @Test
    void theSevenComponentConstructorTheEighthComponentWidenedStillLinks() throws Exception {
        assertTrue(links(HISTORICAL_WITH_HANDLED_FAILURES, ARGUMENTS_WITH_HANDLED_FAILURES),
                "a caller compiled against the seven-component constructor no longer links");
        assertTrue(descriptors().contains(descriptor(HISTORICAL_WITH_HANDLED_FAILURES)),
                "the seven-component descriptor is absent from the compiled class file: "
                        + descriptors());
    }

    @Test
    void aNeverPublishedResultConstructorDoesNotLink() throws Exception {
        List<Class<?>> absent = List.of(UUID.class, Object.class, java.util.Set.class, java.util.Set.class,
                String.class);
        assertFalse(links(absent, "java.util.UUID.randomUUID(), null, java.util.Set.<String>of(), "
                + "java.util.Set.<String>of(), \"absent\""));
        assertFalse(descriptors().contains(descriptor(absent)));
    }

    private static boolean links(List<Class<?>> parameters, String arguments) throws Exception {
        Path root = Files.createTempDirectory("graph-result-binary");
        try {
            Path stub = Files.createDirectory(root.resolve("stub"));
            Path caller = Files.createDirectory(root.resolve("caller"));
            String type = GraphExecutionResult.class.getCanonicalName();
            String signature = java.util.stream.IntStream.range(0, parameters.size())
                    .mapToObj(i -> parameters.get(i).getCanonicalName() + " p" + i)
                    .collect(java.util.stream.Collectors.joining(", "));
            compile(root.resolve("GraphExecutionResult.java"), stub, "package "
                    + GraphExecutionResult.class.getPackageName() + "; public class GraphExecutionResult { public "
                    + "GraphExecutionResult(" + signature + ") {} }");
            compile(root.resolve("Caller.java"), caller, "package generated; public class Caller { public static void "
                    + "call() { new " + type + "(" + arguments + "); } }", stub.toString() + java.io.File.pathSeparator
                    + classpath());
            try (var loader = new URLClassLoader(new URL[] { caller.toUri().toURL() },
                    GraphExecutionResult.class.getClassLoader())) {
                try {
                    loader.loadClass("generated.Caller").getMethod("call").invoke(null);
                    return true;
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    return !(failure.getCause() instanceof NoSuchMethodError);
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    private static void compile(Path source, Path output, String code, String... extraClasspath) throws Exception {
        Files.writeString(source, code);
        String classpath = String.join(java.io.File.pathSeparator, extraClasspath.length == 0
                ? List.of(classpath()) : java.util.stream.Stream.concat(java.util.Arrays.stream(extraClasspath),
                java.util.stream.Stream.of(classpath())).toList());
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), "-cp",
                classpath, source.toString());
        if (status != 0) throw new IllegalStateException("historical caller did not compile");
    }

    private static List<String> descriptors() {
        var output = new java.io.StringWriter();
        java.util.spi.ToolProvider.findFirst("javap").orElseThrow().run(new java.io.PrintWriter(output),
                new java.io.PrintWriter(new java.io.StringWriter()), "-p", "-s", "-cp", classpath(),
                GraphExecutionResult.class.getName());
        return output.toString().lines().map(String::strip).filter(line -> line.startsWith("descriptor: "))
                .map(line -> line.substring("descriptor: ".length())).toList();
    }

    private static String descriptor(List<Class<?>> parameters) {
        return "(" + parameters.stream().map(GraphExecutionResultBinaryCompatibilityTest::type)
                .collect(java.util.stream.Collectors.joining()) + ")V";
    }
    private static String type(Class<?> value) { return "L" + value.getName().replace('.', '/') + ";"; }
    private static String classpath() { return Path.of(GraphExecutionResult.class.getProtectionDomain().getCodeSource()
            .getLocation().getPath()).toString(); }
}
