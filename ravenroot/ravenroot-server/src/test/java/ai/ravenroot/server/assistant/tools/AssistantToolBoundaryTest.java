package ai.ravenroot.server.assistant.tools;

import ai.ravenroot.server.assistant.provider.AssistantProvider;
import ai.ravenroot.server.spec.AssistantPosture;
import ai.ravenroot.server.spec.RouteDescriptor;
import ai.ravenroot.server.spec.RouteTable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant-tool boundary's two type-level properties, checked rather than asserted.
 *
 * <p>The ADR says internal context tools "receive the authorized application as their <b>only</b>
 * capability and hold <b>no HTTP client</b> — an internal tool cannot egress because it has nothing to
 * egress with". These tests enforce that boundary deliberately.</p>
 */
class AssistantToolBoundaryTest {

    /** Types that would give a tool a way out of the process, or a way to reach one. */
    private static final Set<Class<?>> EGRESS_CAPABLE = Set.of(
            HttpClient.class, URLConnection.class, Socket.class, URL.class, AssistantProvider.class);

    /**
     * <b>No type in the tools package holds, or can be handed, anything that can egress.</b>
     *
     * <p>Scans every compiled class in the package — not a hand-maintained list, so a tool added later
     * is covered without anyone remembering to add it here — and fails on a field or constructor
     * parameter assignable to an egress-capable type.</p>
     *
     * <p><b>Mutation proof.</b> Add {@code private final HttpClient client;} to
     * {@link AssistantInternalContext}, or a constructor parameter of that type, and this test reds
     * naming the field. Removing the check makes the mutated class pass, which is the other half.</p>
     */
    @Test
    void noToolHoldsAnythingThatCanEgress() throws Exception {
        List<Class<?>> toolClasses = compiledToolClasses();
        assertFalse(toolClasses.isEmpty(),
                "found no compiled classes in the tools package -- this test would pass vacuously");

        var violations = new ArrayList<String>();
        for (Class<?> type : toolClasses) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (isEgressCapable(field.getType())) {
                    violations.add(type.getSimpleName() + "." + field.getName() + " : "
                            + field.getType().getName());
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (isEgressCapable(parameter)) {
                        violations.add(type.getSimpleName() + "(<init>) takes " + parameter.getName());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "an assistant tool can egress, which the tool boundary forbids: "
                        + violations);
    }

    /**
     * <b>The one capability is the authorized application.</b>
     *
     * <p>Stated separately from the egress test because "holds nothing that can egress" and "holds
     * only the authorized application" are different claims, and the ADR makes both. A tool holding an
     * {@code ExecutorService} would pass the first and fail this one.</p>
     *
     * <p><b>Mutation proof.</b> Add a second instance field of any type to
     * {@link AssistantInternalContext} and this test reds.</p>
     */
    @Test
    void theAuthorizedApplicationIsTheOnlyCapability() {
        List<Field> instanceFields = java.util.Arrays.stream(
                        AssistantInternalContext.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
        assertEquals(1, instanceFields.size(),
                () -> "the tier-0 reader must hold exactly one capability, found: "
                        + instanceFields.stream().map(Field::getName).toList());
        assertEquals("ai.ravenroot.api.application.AuthorizedRavenrootApplication",
                instanceFields.get(0).getType().getName(),
                "the one capability must be the reference monitor, not the raw application");
    }

    /**
     * <b>A tool may only mirror a route the table declares READ.</b>
     *
     * <p>This is what stops the posture column from being a comment. {@code /v1/drain} is declared
     * {@code NEVER}, so a tool naming it must be refused at construction.</p>
     *
     * <p><b>Mutation proof.</b> Delete the posture branch in
     * {@code AssistantInternalContext#requireReadPosture} and the first assertion reds; flip
     * {@code /v1/node-types} to {@code NEVER} in {@code RouteTable} and the second reds, along with
     * every test that builds a server.</p>
     */
    @Test
    void aToolCannotMirrorARouteThatIsNotDeclaredRead() {
        assertThrows(IllegalStateException.class,
                () -> AssistantInternalContext.requireReadPosture("made_up", "/v1/drain"),
                "a NEVER route must not be reachable as a tool");
        assertThrows(IllegalStateException.class,
                () -> AssistantInternalContext.requireReadPosture("made_up", "/v1/not-a-route"),
                "a tool naming a route that is not in the table must be refused, not silently allowed");
        assertDoesNotThrow(
                () -> AssistantInternalContext.requireReadPosture("ravenroot_node_types", "/v1/node-types"));
    }

    /**
     * <b>Every route the shipped tools mirror is authenticated and GET-only.</b>
     *
     * <p>Redundant with {@code RouteDescriptor}'s own constructor check by design: that one guards the
     * table, this one guards the tools, and the two would have to be defeated together for an
     * unauthenticated read to become reachable through the assistant.</p>
     */
    @Test
    void everyToolRouteIsAnAuthenticatedGetRead() {
        for (AssistantInternalContext.Tool tool : AssistantInternalContext.Tool.values()) {
            RouteDescriptor route = RouteTable.ALL.stream()
                    .filter(candidate -> candidate.path().equals(tool.routePath()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(AssistantPosture.READ, route.assistantPosture(), tool.toolName());
            assertTrue(route.authenticated(),
                    () -> tool.toolName() + " mirrors an unauthenticated route, so its read could not "
                            + "pass the author's own authorization");
            assertEquals(Set.of("GET"), route.methods(), tool.toolName());
        }
    }

    /**
     * <b>No class anywhere in the assistant references a mutating application method.</b>
     *
     * <p>Wider than the tools package on purpose. the documented contract is that the model proposes and
     * the user presses: a proposal crosses the wire as inert JSON and the panel turns it into an
     * execution under a click. That guarantee is only worth the paper it is written on if nothing on
     * the server side can turn a proposal into a call — so this scans every compiled class under
     * {@code ai.ravenroot.server.assistant} for a reference to any mutator on
     * {@code AuthorizedRavenrootApplication}.</p>
     *
     * <p>The scan is over the class file's constant pool, which is where a method reference lands:
     * it therefore catches a direct call, a method reference and a reflective lookup that names the
     * method as a literal, and it does not depend on anyone remembering to add a new class to a list.</p>
     *
     * <p><b>Mutation proof.</b> Add {@code application.drain(context, Duration.ofSeconds(1));} to
     * {@code AssistantInternalContext}, or even a dead {@code Runnable r = () ->
     * application.cancelExecution(null, null);}, and this test reds naming the class and the method.
     * Deleting the reference makes it green again.</p>
     */
    @Test
    void noAssistantClassReferencesAMutatingApplicationMethod() throws IOException {
        List<String> forbidden = List.of("startGraphMl", "cancelExecution", "drain",
                "createProgramArtifact", "approveProgramArtifact", "activateProgramArtifact",
                "retireProgramArtifact", "validateProgramArtifact", "testProgramArtifact");
        Path assistantClasses = Path.of("target", "classes", "ai", "ravenroot", "server", "assistant");
        assertTrue(Files.isDirectory(assistantClasses),
                () -> "expected compiled assistant classes at " + assistantClasses.toAbsolutePath());

        var violations = new ArrayList<String>();
        int scanned = 0;
        try (var walk = Files.walk(assistantClasses)) {
            for (Path entry : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                scanned++;
                String constantPool = new String(Files.readAllBytes(entry),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                for (String method : forbidden) {
                    if (constantPool.contains(method)) {
                        violations.add(entry.getFileName() + " references " + method);
                    }
                }
            }
        }
        assertTrue(scanned > 0, "scanned no assistant classes -- this test would pass vacuously");
        assertTrue(violations.isEmpty(),
                () -> "the assistant must not be able to turn a proposal into an execution: " + violations);
    }

    private static boolean isEgressCapable(Class<?> type) {
        return EGRESS_CAPABLE.stream().anyMatch(forbidden -> forbidden.isAssignableFrom(type)
                || type.isAssignableFrom(forbidden));
    }

    /**
     * Every compiled class in the tools package.
     *
     * <p>Read from {@code target/classes} rather than from a hand-written list: a list is a control
     * that silently stops covering the thing it was written for the moment someone adds a class.</p>
     */
    private static List<Class<?>> compiledToolClasses() throws IOException, ClassNotFoundException {
        Path packageDirectory = Path.of("target", "classes", "ai", "ravenroot", "server", "assistant",
                "tools");
        assertTrue(Files.isDirectory(packageDirectory),
                () -> "expected compiled tool classes at " + packageDirectory.toAbsolutePath()
                        + " -- run this from ravenroot-server's own module directory");
        var classes = new ArrayList<Class<?>>();
        try (var entries = Files.list(packageDirectory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".class")) {
                    continue;
                }
                classes.add(Class.forName("ai.ravenroot.server.assistant.tools."
                        + name.substring(0, name.length() - ".class".length())));
            }
        }
        return classes;
    }
}
