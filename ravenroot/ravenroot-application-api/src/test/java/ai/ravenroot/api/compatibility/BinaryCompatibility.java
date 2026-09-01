package ai.ravenroot.api.compatibility;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Proves that a constructor shape a previously-compiled caller depends on is still <em>binary</em>
 * compatible, rather than merely still compiling (PLAT-01).
 *
 * <h2>Why this exists</h2>
 * <p>{@code ExecutionEvent} is a record whose canonical constructor has grown twice — PLAT-01 added
 * {@code joinWaitDuration}, then {@code processingDuration}. Adding a record component changes
 * the canonical constructor's descriptor, which is a <em>binary</em> break for any caller already
 * compiled against the older class file, not only a source break for one being recompiled. Both
 * changes handled it correctly by adding a compatibility constructor at the previous arity.
 *
 * <p><b>Neither proved it.</b> The claim was made in prose (`ExecutionEvent`'s own Javadoc) and
 * backed by a source-level test asserting the old argument list still compiles — which is a
 * statement about {@code javac}, not about linkage. A source-compatibility test passes just as
 * happily when the compatibility constructor is present as when the canonical one merely happens to
 * accept the same arguments. This class supplies the missing half.
 *
 * <h2>Two mechanisms, deliberately</h2>
 * <ol>
 *   <li>{@link #declaredConstructorDescriptors} reads the <em>class file on disk</em> with
 *       {@code javap}, not the loaded runtime class through reflection. They agree here, but the
 *       class file is the artifact that ships, so it is the honest thing to measure.</li>
 *   <li>{@link #linksAgainstCurrent} is the real proof: it compiles a caller against a synthesized
 *       stub declaring only the historical shape, then executes that caller with the stub off the
 *       classpath and the current class on it. The caller's constant pool holds the historical
 *       descriptor, so if the current class no longer offers it the JVM raises
 *       {@link NoSuchMethodError} exactly as a downstream artifact would.</li>
 * </ol>
 *
 * <h2>Validate the instrument before trusting it</h2>
 * <p>An instrument that always reports success is worse than none, and this run has counted
 * thirty-four that measured nothing. Both mechanisms are therefore exercised against a shape that
 * has <em>never</em> existed, and are required to report a break — see the negative controls in
 * {@code ExecutionEventBinaryCompatibilityTest}. Without that, "every historical shape still links"
 * would be indistinguishable from "this harness cannot detect anything".
 */
public final class BinaryCompatibility {
    private BinaryCompatibility() {
    }

    /**
     * Every constructor descriptor {@code javap} reports for the compiled class file.
     *
     * <p>Parsed from {@code javap -p -s}, whose output pairs a declaration line with a
     * {@code descriptor:} line. Constructors are the declarations whose name is the class's own
     * binary name — which is how {@code javap} renders {@code <init>} in its declaration form.
     */
    public static List<String> declaredConstructorDescriptors(Class<?> type) {
        var javap = java.util.spi.ToolProvider.findFirst("javap")
                .orElseThrow(() -> new IllegalStateException("javap is not available in this JDK; the "
                        + "binary-compatibility harness cannot read the class file it is meant to measure"));
        var out = new StringWriter();
        var err = new StringWriter();
        int status = javap.run(new PrintWriter(out), new PrintWriter(err),
                "-p", "-s", "-cp", classpathOf(type), type.getName());
        if (status != 0) {
            throw new IllegalStateException("javap failed for " + type.getName() + ": " + err);
        }
        List<String> lines = out.toString().lines().map(String::strip).toList();
        var descriptors = new ArrayList<String>();
        for (int i = 0; i < lines.size() - 1; i++) {
            // A constructor's declaration line names the class itself, so the token before '(' is the
            // binary name. Methods name a member instead, and static initialisers have no descriptor
            // line of this shape.
            String declaration = lines.get(i);
            int parenthesis = declaration.indexOf('(');
            if (parenthesis < 0 || !declaration.endsWith(");")) {
                continue;
            }
            String beforeParenthesis = declaration.substring(0, parenthesis);
            if (!beforeParenthesis.endsWith(type.getName())) {
                continue;
            }
            String next = lines.get(i + 1);
            if (next.startsWith("descriptor: ")) {
                descriptors.add(next.substring("descriptor: ".length()));
            }
        }
        if (descriptors.isEmpty()) {
            throw new IllegalStateException("javap reported no constructors for " + type.getName()
                    + "; the parse is wrong and every assertion built on it would be vacuous");
        }
        return List.copyOf(descriptors);
    }

    /** The JVM descriptor a constructor taking exactly these parameter types would carry. */
    public static String descriptorOf(Class<?>... parameterTypes) {
        return "(" + java.util.Arrays.stream(parameterTypes)
                .map(BinaryCompatibility::typeDescriptor)
                .collect(Collectors.joining()) + ")V";
    }

    /**
     * Whether a caller compiled against {@code shape} still links against the current {@code type}.
     *
     * <p>The caller is built against a stub that declares <em>only</em> that shape, so its constant
     * pool cannot accidentally record some other overload the current class happens to offer. It is
     * then loaded by a classloader that cannot see the stub, so every symbol resolves against the
     * real class. Anything other than {@link NoSuchMethodError} counts as linked: the constructor was
     * found and entered, and whether its arguments then satisfy a validating compact constructor is a
     * different question from whether the method exists.
     */
    public static boolean linksAgainstCurrent(Class<?> type, ConstructorShape shape) throws Exception {
        Path work = Files.createTempDirectory("binary-compat");
        try {
            Path stubClasses = Files.createDirectory(work.resolve("stub"));
            Path callerClasses = Files.createDirectory(work.resolve("caller"));
            // The stub needs the real classpath to resolve the parameter types it names (the event
            // type enum, and anything else in the shape). javac prefers the source it is asked to
            // compile over a class file of the same name on the classpath, so the stub still wins as
            // the declaration of the historical class.
            String real = classpathOf(type);
            // Named for the class it declares: a public type must live in a file of its own name.
            compile(List.of(write(work, type.getSimpleName() + ".java", shape.stubSource(type))),
                    stubClasses, real);
            // Stub FIRST: javac searches the classpath in order, so the caller binds to the historical
            // declaration rather than the current one. If this order is ever reversed the measurement
            // becomes circular and every case passes for the wrong reason.
            compile(List.of(write(work, "Caller.java", shape.callerSource(type))), callerClasses,
                    stubClasses + java.io.File.pathSeparator + real);

            var loader = new URLClassLoader(new URL[] {callerClasses.toUri().toURL()}, type.getClassLoader());
            try (loader) {
                Class<?> caller = loader.loadClass("ai.ravenroot.api.compatibility.generated.Caller");
                try {
                    caller.getMethod("call").invoke(null);
                    return true;
                } catch (java.lang.reflect.InvocationTargetException invoked) {
                    return !(invoked.getCause() instanceof NoSuchMethodError);
                }
            }
        } finally {
            delete(work);
        }
    }

    /**
     * A constructor shape expressed as its parameter types, able to generate both the stub that
     * declares it and a caller that invokes it.
     *
     * @param description what this shape is, for assertion messages
     * @param parameterTypes the exact parameter list, in order
     * @param arguments Java source for one argument per parameter, valid for the real class
     */
    public record ConstructorShape(String description, List<Class<?>> parameterTypes, List<String> arguments) {
        public ConstructorShape {
            if (parameterTypes.size() != arguments.size()) {
                throw new IllegalArgumentException(description + ": " + parameterTypes.size()
                        + " parameter types but " + arguments.size() + " arguments");
            }
            parameterTypes = List.copyOf(parameterTypes);
            arguments = List.copyOf(arguments);
        }

        public String descriptor() {
            return descriptorOf(parameterTypes.toArray(Class<?>[]::new));
        }

        /**
         * A stand-in for the historical class declaring only this constructor. It is not a record and
         * carries no behaviour: the caller compiled against it only needs the descriptor to resolve.
         */
        String stubSource(Class<?> type) {
            var parameters = new StringBuilder();
            for (int i = 0; i < parameterTypes.size(); i++) {
                parameters.append(i == 0 ? "" : ", ")
                        .append(parameterTypes.get(i).getCanonicalName()).append(" p").append(i);
            }
            return "package " + type.getPackageName() + ";\n"
                    + "public class " + type.getSimpleName() + " {\n"
                    + "    public " + type.getSimpleName() + "(" + parameters + ") { }\n"
                    + "}\n";
        }

        String callerSource(Class<?> type) {
            return "package ai.ravenroot.api.compatibility.generated;\n"
                    + "public final class Caller {\n"
                    + "    public static void call() {\n"
                    + "        new " + type.getCanonicalName() + "(" + String.join(", ", arguments) + ");\n"
                    + "    }\n"
                    + "}\n";
        }
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == long.class) return "J";
        if (type == int.class) return "I";
        if (type == boolean.class) return "Z";
        if (type == double.class) return "D";
        if (type == float.class) return "F";
        if (type == short.class) return "S";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == void.class) return "V";
        if (type.isArray()) return "[" + typeDescriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static Path write(Path directory, String name, String source) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, source);
        return file;
    }

    private static void compile(List<Path> sources, Path output, String classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler; run the build on a JDK, not a JRE");
        }
        var arguments = new ArrayList<String>(List.of("-d", output.toString(), "-classpath", classpath));
        sources.forEach(source -> arguments.add(source.toString()));
        // Diagnostics are captured and re-thrown. The first version of this method discarded them into
        // a null stream and reported only "compilation failed", which made the harness itself the kind
        // of instrument it exists to prevent: it could tell you something was wrong and nothing about
        // what.
        var diagnostics = new java.io.ByteArrayOutputStream();
        int status = compiler.run(null, null, diagnostics, arguments.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("compilation failed for " + sources + " with classpath "
                    + classpath + ":\n" + diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String classpathOf(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("cannot locate the class file for " + type.getName());
        }
        return Path.of(java.net.URI.create(source.getLocation().toString())).toString();
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp directory is not worth failing a passing test over.
                }
            });
        }
    }
}
