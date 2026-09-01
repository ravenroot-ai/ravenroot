package ai.ravenroot.distribution;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads classes and {@code META-INF/services} registrations from a class-file source - the module's
 * resolved Maven classpath, or an arbitrary jar/directory such as a built artifact - without loading
 * or executing a single one of them: bytecode header only (access flags, superclass, declared
 * interfaces, service-file text) for every class, plus a call graph for the narrow {@code
 * ai.ravenroot.*} subset (see {@link #recordClass}).
 *
 * <p>Two independent boundary checks build on this index:
 * <ul>
 *   <li>{@link DefaultDistributionModelAdapterBoundaryTest} - a fast pre-filter over {@code
 *   java.class.path}, runs at the ordinary Surefire {@code test} phase;
 *   <li>{@link ReleaseArtifactModelAdapterBoundaryIT} - the legally authoritative check, over the
 *   actual built {@code ravenroot.jar} and {@code ravenroot-bin.zip}, runs at {@code verify} via
 *   Failsafe. What is "placed on the market" under arts. 3(9)/(10) of the AI Act is the artifact, not
 *   the source tree or the resolved classpath that produces it - see that class's Javadoc.
 * </ul>
 */
final class ClassGraphIndex {

    record ClassInfo(int access, String superInternalName, List<String> interfaceInternalNames, String origin) {
        boolean isConcrete() {
            return (access & Opcodes.ACC_INTERFACE) == 0 && (access & Opcodes.ACC_ABSTRACT) == 0;
        }
    }

    record ServiceRegistration(String spiFqcn, String origin, List<String> registeredImplementations) {
        @Override
        public String toString() {
            return origin + " -> " + registeredImplementations;
        }
    }

    /** A method, identified the way a bytecode call instruction identifies its target: owner, name,
     * descriptor - all in internal form. Not necessarily the declaring class if inherited. */
    record MethodRef(String owner, String name, String descriptor) {
        String display() {
            return owner.replace('/', '.') + "#" + name;
        }
    }

    private final Map<String, ClassInfo> classesByInternalName = new HashMap<>();
    private final List<ServiceRegistration> serviceRegistrations = new ArrayList<>();
    /** Call edges between methods of {@code ai/ravenroot/*} classes only - see {@link #recordClass}. */
    private final Map<MethodRef, Set<MethodRef>> ravenrootCallGraph = new HashMap<>();

    ClassGraphIndex() {
    }

    static ClassGraphIndex scanJavaClassPath(Set<String> watchedServiceFqcns) throws IOException {
        var index = new ClassGraphIndex();
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            throw new IllegalStateException(
                    "java.class.path is empty; this check must run under Surefire (mvn test/verify/"
                            + "install), which populates it with this module's resolved compile+runtime "
                            + "dependency graph. Running the test class directly from an IDE without that "
                            + "classpath would make every assertion in it vacuous.");
        }
        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry);
            if (Files.isDirectory(path)) {
                index.addDirectory(path, watchedServiceFqcns);
            } else if (Files.isRegularFile(path) && entry.endsWith(".jar")) {
                index.addJar(path, watchedServiceFqcns);
            }
        }
        return index;
    }

    void addJar(Path jarPath, Set<String> watchedServiceFqcns) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isClassEntry(name)) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        recordClass(in.readAllBytes(), jarPath.toString());
                    }
                } else {
                    Optional<String> matchedFqcn = matchingServiceFqcn(name, watchedServiceFqcns);
                    if (matchedFqcn.isPresent()) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
                            recordService(matchedFqcn.get(), jarPath.toString(), lines);
                        }
                    }
                }
            }
        }
    }

    void addDirectory(Path root, Set<String> watchedServiceFqcns) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (isClassEntry(relative)) {
                    recordClass(Files.readAllBytes(file), root.toString());
                } else {
                    matchingServiceFqcn(relative, watchedServiceFqcns)
                            .ifPresent(fqcn -> recordService(fqcn, root.toString(), readLines(file)));
                }
            }
        }
    }

    boolean containsClass(String internalName) {
        return classesByInternalName.containsKey(internalName);
    }

    /** Concrete (non-interface, non-abstract) classes whose ancestry reaches {@code targetInternalName}. */
    List<String> concreteImplementationsOf(String targetInternalName) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, ClassInfo> entry : classesByInternalName.entrySet()) {
            String className = entry.getKey();
            ClassInfo info = entry.getValue();
            if (!className.equals(targetInternalName) && info.isConcrete()
                    && isInAncestry(className, targetInternalName, new HashSet<>())) {
                violations.add(className.replace('/', '.') + " (" + info.origin() + ")");
            }
        }
        return violations;
    }

    List<ServiceRegistration> serviceRegistrationsFor(String spiFqcn) {
        return serviceRegistrations.stream()
                .filter(registration -> registration.spiFqcn().equals(spiFqcn))
                .toList();
    }

    boolean hasCallGraphDataFor(String ownerInternalName) {
        return ravenrootCallGraph.keySet().stream().anyMatch(m -> m.owner().equals(ownerInternalName));
    }

    /**
     * Breadth-first search over the {@code ai.ravenroot.*} call graph from {@code entryPoint} looking
     * for a call instruction whose (owner, method name) matches one of {@code targetOwnerAndNames}
     * (owner internal name -&gt; method name; descriptor is not matched, since both registries expose
     * exactly one overload of {@code register} each and matching by name alone is simpler and no less
     * precise here). Returns the call chain from the entry point to the hit, as
     * {@code "owner#method"} strings, or empty if no such call is reachable through methods this
     * index has call-graph data for.
     *
     * <p>Scope and its limit: only follows edges between methods of classes recorded with call-graph
     * data (see {@link #recordClass} - the {@code ai/ravenroot/} subtree). Both ordinary call
     * instructions and {@code invokedynamic} (lambdas, method references - see {@link
     * CallGraphVisitor}'s {@code visitInvokeDynamicInsn}) are followed, so the ordinary way modern
     * Java reaches a method is covered, not only direct calls. What genuinely remains outside this
     * BFS's reach: a registration issued through reflection ({@code Method.invoke}), a {@code
     * java.lang.invoke.MethodHandle} obtained and invoked dynamically at runtime rather than compiled
     * as a static {@code invokedynamic} site, or a callback into third-party library code that later
     * calls back into Ravenroot through a path this scan cannot resolve statically. That is a real,
     * standing limitation of static bytecode analysis in general, not specific to this check; P1/P2
     * (no concrete adapter class at all) remain the structural backstop for cases this cannot see -
     * and are a complete one here, because both {@code ModelProvider} and {@code AgentRuntime} declare
     * abstract methods a lambda cannot implement, so a lambda can call {@code register(...)} but can
     * never itself be the registered adapter.
     */
    Optional<List<String>> registerCallPathFrom(MethodRef entryPoint, Map<String, String> targetOwnerAndNames) {
        record Hop(MethodRef method, Hop from) {
        }
        Deque<Hop> queue = new ArrayDeque<>();
        Set<MethodRef> visited = new HashSet<>();
        queue.add(new Hop(entryPoint, null));
        visited.add(entryPoint);
        while (!queue.isEmpty()) {
            Hop current = queue.poll();
            for (MethodRef callee : ravenrootCallGraph.getOrDefault(current.method(), Set.of())) {
                String expectedMethodName = targetOwnerAndNames.get(callee.owner());
                if (expectedMethodName != null && expectedMethodName.equals(callee.name())) {
                    List<String> path = new ArrayList<>();
                    for (Hop hop = current; hop != null; hop = hop.from()) {
                        path.add(0, hop.method().display());
                    }
                    path.add(callee.display());
                    return Optional.of(path);
                }
                if (visited.add(callee)) {
                    queue.add(new Hop(callee, current));
                }
            }
        }
        return Optional.empty();
    }

    private boolean isInAncestry(String className, String targetInternalName, Set<String> visited) {
        if (!visited.add(className)) {
            return false;
        }
        ClassInfo info = classesByInternalName.get(className);
        if (info == null) {
            return false;
        }
        if (targetInternalName.equals(info.superInternalName())) {
            return true;
        }
        if (info.interfaceInternalNames().contains(targetInternalName)) {
            return true;
        }
        if (info.superInternalName() != null && isInAncestry(info.superInternalName(), targetInternalName, visited)) {
            return true;
        }
        for (String iface : info.interfaceInternalNames()) {
            if (isInAncestry(iface, targetInternalName, visited)) {
                return true;
            }
        }
        return false;
    }

    private void recordClass(byte[] bytes, String origin) {
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (RuntimeException malformed) {
            // Not every resource ending in ".class" on a real-world classpath is a well-formed class
            // file (some libraries carry unrelated payloads under matching names). Skip it rather than
            // fail this boundary check on unrelated classpath resource layout.
            return;
        }
        String[] interfaceNames = reader.getInterfaces();
        List<String> interfaces = interfaceNames == null ? List.of() : List.of(interfaceNames);
        classesByInternalName.putIfAbsent(reader.getClassName(),
                new ClassInfo(reader.getAccess(), reader.getSuperName(), interfaces, origin));

        // Full method-body visiting (needed for call edges) is restricted to this product's own
        // classes. Doing it for the whole classpath - which includes GraalVM Truffle/JS, Pekko and
        // Gremlin, tens of thousands of classes - would be expensive for no benefit: the composition
        // root and the two registries are all ai.ravenroot.* code (see ModelProviderRegistry,
        // AgentRuntimeRegistry, RavenrootServerMain, RavenrootCliMain), so any reachable register()
        // call site is reachable through a chain of ai.ravenroot.* call edges, PROVIDED both the
        // ordinary invokevirtual/invokestatic/invokeinterface/invokespecial edges (CallGraphVisitor's
        // visitMethodInsn) and the invokedynamic edges a lambda or method reference compiles to
        // (visitInvokeDynamicInsn, below) are both recorded. A javac-generated lambda body is a
        // synthetic method on the enclosing class (e.g. RavenrootServerMain.lambda$main$0) - itself
        // ai.ravenroot.* code and already covered once reached - but the call SITE that creates the
        // lambda reaches it via invokedynamic, whose implementation method lives in a
        // java.lang.invoke.MethodHandle inside the bootstrap arguments, not in a normal call
        // instruction. Missing that edge was a real defect (found by the fault-injection regression into
        // the shutdown-hook lambda already present in RavenrootServerMain#main): the callback class
        // being ai.ravenroot.* is necessary but was not sufficient, because the edge reaching that
        // class was invisible to a visitor that only overrides visitMethodInsn.
        if (reader.getClassName().startsWith("ai/ravenroot/")) {
            reader.accept(new CallGraphVisitor(reader.getClassName()), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    private final class CallGraphVisitor extends ClassVisitor {
        private final String owner;

        CallGraphVisitor(String owner) {
            super(Opcodes.ASM9);
            this.owner = owner;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                          String[] exceptions) {
            MethodRef self = new MethodRef(owner, name, descriptor);
            Set<MethodRef> callees = ravenrootCallGraph.computeIfAbsent(self, k -> new LinkedHashSet<>());
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    callees.add(new MethodRef(owner, name, descriptor));
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                                    Object... bootstrapMethodArguments) {
                    // A lambda or method reference compiles to an invokedynamic whose actual
                    // implementation method (the lambda body, or the referenced method) is carried as
                    // a Handle among the bootstrap arguments - LambdaMetafactory.metafactory/
                    // altMetafactory put it there, not in a normal call instruction, so
                    // visitMethodInsn alone never sees this edge. Recording every Handle found here,
                    // regardless of position, covers both metafactory forms without needing to know
                    // which bootstrap method produced them. This does not false-positive on
                    // StringConcatFactory (Java 9+ string concatenation also compiles to
                    // invokedynamic): its bootstrap arguments are a recipe String and constant
                    // operands, no Handle among them.
                    for (Object argument : bootstrapMethodArguments) {
                        if (argument instanceof Handle handle) {
                            callees.add(new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc()));
                        }
                    }
                }
            };
        }
    }

    private void recordService(String spiFqcn, String origin, List<String> rawLines) {
        List<String> implementations = rawLines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        serviceRegistrations.add(new ServiceRegistration(spiFqcn, origin, implementations));
    }

    private static boolean isClassEntry(String name) {
        return name.endsWith(".class") && !name.equals("module-info.class")
                && !name.startsWith("META-INF/versions/");
    }

    private static Optional<String> matchingServiceFqcn(String resourceName, Set<String> watchedServiceFqcns) {
        return watchedServiceFqcns.stream()
                .filter(fqcn -> resourceName.equals("META-INF/services/" + fqcn))
                .findFirst();
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
