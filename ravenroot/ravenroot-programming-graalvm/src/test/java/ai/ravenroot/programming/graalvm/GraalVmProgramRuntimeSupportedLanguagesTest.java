package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraalVmProgramRuntime#supportedLanguages()} is the SPI method an editor is supposed
 * to read instead of hard-coding "javascript" -- this pins what it actually returns, so a change to
 * {@link ProgramLanguage} that silently drops or renames a language fails here rather than only
 * being noticed by an editor that stopped offering it.
 */
class GraalVmProgramRuntimeSupportedLanguagesTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    @Test
    void declaresExactlyTheTwoLanguagesProgramLanguageDeclares() {
        List<ProgramLanguageDescriptor> declared = runtime().supportedLanguages();

        assertEquals(2, declared.size(), declared.toString());
        var byId = declared.stream()
                .collect(java.util.stream.Collectors.toMap(ProgramLanguageDescriptor::id, d -> d));
        assertTrue(byId.containsKey("javascript"), declared.toString());
        assertTrue(byId.containsKey("python"), declared.toString());
    }

    /**
     * The id each descriptor carries is exactly the token {@code ProgramLanguage#of} resolves --
     * proven by round-tripping it through {@code verifyArtifact}'s own resolution path rather than
     * asserted as a string a future refactor could drift from independently.
     */
    @Test
    void eachDeclaredIdRoundTripsThroughLanguageResolution() {
        for (ProgramLanguageDescriptor descriptor : runtime().supportedLanguages()) {
            assertEquals(descriptor.id(), ProgramLanguage.of(descriptor.id()).descriptor().id());
        }
    }

    @Test
    void pythonsStarterIsAPythonCallableUsingAttributeAccessNotJavascriptRelabelled() {
        var declared = runtime().supportedLanguages().stream()
                .collect(java.util.stream.Collectors.toMap(ProgramLanguageDescriptor::id, d -> d));

        String python = declared.get("python").exampleSource();
        String javascript = declared.get("javascript").exampleSource();

        assertTrue(python.contains("def "), "a Python starter must contain a def, was: " + python);
        assertTrue(python.contains("request.payload"),
                "measured behavior: the worker hands Python a ProxyObject reachable only by "
                        + "attribute access -- request['payload'] raises TypeError there. Was: " + python);
        assertNotEquals(javascript, python,
                "a second language must not ship the first language's example under a new label");
    }

    @Test
    void aThirdLanguageWouldAppearHereWithNoEditorChange() {
        // Not exercised directly -- ProgramLanguage is a fixed two-value enum today -- but the
        // property this pins is that supportedLanguages() is computed FROM ProgramLanguage.values()
        // (see GraalVmProgramRuntime#supportedLanguages), not a hand-written literal beside it.
        // Adding a third enum constant is the only change required on this side of the
        // boundary; nothing here or in RavenrootServer enumerates languages a second time.
        assertEquals(ProgramLanguage.values().length, runtime().supportedLanguages().size());
    }

    private static GraalVmProgramRuntime runtime() {
        return new GraalVmProgramRuntime(new FakeSupervisor(), policy(Duration.ofSeconds(5)));
    }

    private static SandboxPolicy policy(Duration timeout) {
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id", JAVA,
                "jre-test-id");
    }
}
