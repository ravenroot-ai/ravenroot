package ai.ravenroot.adapter.openaicompatible;

import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The secret never leaves — enforced by the shape of the types, and checked here on every rendering
 * surface this adapter owns.
 *
 * <h2>Four sentinels, because three of the four vectors are not the obvious one</h2>
 * <ol>
 *   <li>the resolved operator key (the obvious one);</li>
 *   <li><b>the {@code credentialRef} itself</b> — the editor renders it as a generic
 *   {@code <input type="text">}, so it is the field an author pastes a real secret into by mistake
 *   instead of a name for one;</li>
 *   <li>the node's {@code parameter.*} entries — an untyped map, never schema-validated, the natural
 *   place for an author who could not get {@code credentialRef} to work to try {@code parameter.apiKey};</li>
 *   <li>the rendered prompt, which carries the traversal payload already substituted.</li>
 * </ol>
 *
 * <p>And the surface on which a leak would <em>survive</em>, which is not a failure message:
 * {@code payload}, {@code providerId} and {@code model} become node output and persisted attributes.
 * An error message is read once; a node attribute stays.
 */
class OpenAiCompatibleModelProviderCredentialSafetyTest {

    private static final String KEY_REFERENCE = "SENTINEL-REFERENCE-a1b2c3";
    private static final String KEY = "SENTINEL-KEY-d4e5f6";
    private static final String PARAMETER_SECRET = "SENTINEL-PARAMETER-g7h8i9";
    private static final String PROMPT_SECRET = "SENTINEL-PROMPT-j0k1l2";
    private static final List<String> SENTINELS =
            List.of(KEY_REFERENCE, KEY, PARAMETER_SECRET, PROMPT_SECRET);

    private ChatCompletionsDouble endpoint;

    @BeforeEach
    void start() throws Exception {
        endpoint = ChatCompletionsDouble.start();
    }

    @AfterEach
    void stop() {
        endpoint.close();
    }

    @Test
    @DisplayName("no sentinel reaches the message, the parameters, the causes or the printed stack trace")
    void noSentinelReachesAnyRenderingSurfaceOfAFailure() {
        // The worst realistic case: the far end reflects the request back inside its own error body.
        endpoint.responds(400, ChatCompletionsDouble.error("invalid_request",
                "rejected key " + KEY + " for prompt " + PROMPT_SECRET));

        ModelInvocationException failure = failure();

        // ANTI-FALSE-GREEN 1: the key really did travel through the code under test and really did
        // arrive. Without this the sweep below would pass just as well on a request that was never
        // authenticated at all.
        assertEquals("Bearer " + KEY, endpoint.observedAuthorization());
        // ANTI-FALSE-GREEN 2: the discarded error body really does contain the key. Without this the
        // "body is dropped unexamined" claim would be proved by a body that had nothing in it.
        assertTrue(ChatCompletionsDouble.error("invalid_request",
                "rejected key " + KEY + " for prompt " + PROMPT_SECRET).contains(KEY));

        String rendered = failure.getMessage() + "|" + failure.transportType() + "|" + failure.httpStatus();
        for (String sentinel : SENTINELS) {
            assertFalse(rendered.contains(sentinel), "rendered failure leaked " + sentinel);
            assertFalse(printed(failure).contains(sentinel), "stack trace leaked " + sentinel);
        }
        // Nothing to walk: no cause chain and no suppressed exceptions, by construction.
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    @DisplayName("no sentinel reaches the payload, the provider id, the model or the metadata")
    void noSentinelReachesTheSurfacesThatSurvive() throws Exception {
        endpoint.responds(200, ChatCompletionsDouble.completion("an ordinary answer"));

        var response = provider(CredentialRequirement.REQUIRED)
                .generate(hostileRequest()).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals("Bearer " + KEY, endpoint.observedAuthorization());
        String surviving = response.payload() + "|" + response.providerId() + "|" + response.model()
                + "|" + response.metadata();
        // The prompt sentinel is deliberately included: nothing from the request is echoed into
        // metadata -- not the reference, not the parameters, not the prompt.
        for (String sentinel : SENTINELS) {
            assertFalse(surviving.contains(sentinel), "a persisted surface leaked " + sentinel);
        }
    }

    @Test
    @DisplayName("the credential holder renders a constant, never the material")
    void theCredentialHolderRendersAConstant() {
        try (SecretValue secret = new SecretValue(KEY.toCharArray());
             BearerCredential credential = BearerCredential.from(secret)) {
            assertEquals(BearerCredential.REDACTED, credential.toString());
            assertEquals(BearerCredential.REDACTED, String.valueOf(credential));
            assertFalse(("" + credential).contains(KEY));
        }
    }

    @Test
    @DisplayName("the credential holder exposes no accessor that returns the material")
    void theCredentialHolderExposesNoAccessorThatReturnsTheMaterial() {
        for (Method method : BearerCredential.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            boolean yieldsMaterial = returned == char[].class
                    || (returned == String.class && !"toString".equals(method.getName()));
            assertFalse(yieldsMaterial,
                    "BearerCredential#" + method.getName() + " returns the material");
        }
    }

    @Test
    @DisplayName("the failure type accepts no free text on any creation path")
    void theFailureTypeAcceptsNoFreeTextOnAnyCreationPath() {
        // The sibling adapter shipped a revision whose Javadoc claimed exactly this while its sole
        // constructor took a String. This asserts the signature rather than the sentence.
        for (Executable creation : creationPaths()) {
            for (Class<?> parameter : creation.getParameterTypes()) {
                assertFalse(CharSequence.class.isAssignableFrom(parameter),
                        creation + " accepts a CharSequence");
                assertFalse(Throwable.class.isAssignableFrom(parameter),
                        creation + " accepts a Throwable");
            }
        }
    }

    @Test
    @DisplayName("a blank resolved credential is refused before a request is built")
    void aBlankResolvedCredentialIsRefusedBeforeARequestIsBuilt() {
        // The premise this guard rests on, checked rather than remembered: the JDK does NOT refuse a
        // blank field value, so without the guard the blank credential goes out on the wire -- and
        // against a local endpoint that ignores authentication that is a silent unauthenticated
        // success rather than a 401.
        HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:1/v1/chat/completions"))
                .header("authorization", "Bearer ");

        var blank = new OpenAiCompatibleModelProvider("ollama-local",
                reference -> Optional.of(new SecretValue("   ".toCharArray())),
                endpoint.endpoint(), "qwen3", CredentialRequirement.REQUIRED, Duration.ofSeconds(10));

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE, failureOf(blank).reason());
        assertEquals(0, endpoint.calls());
    }

    @Test
    @DisplayName("a credential carrying a control character is refused without being rendered")
    void aCredentialCarryingAControlCharacterIsRefusedWithoutBeingRendered() {
        // The JDK's own IllegalArgumentException quotes the whole rejected value. Catching and
        // remapping it is what keeps the key out of anything that later prints the stack trace.
        String pasted = "sk-" + KEY + "\nX-Injected: yes";
        var unusable = new OpenAiCompatibleModelProvider("ollama-local",
                reference -> Optional.of(new SecretValue(pasted.toCharArray())),
                endpoint.endpoint(), "qwen3", CredentialRequirement.REQUIRED, Duration.ofSeconds(10));

        ModelInvocationException failure = failureOf(unusable);

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNUSABLE, failure.reason());
        assertFalse(failure.getMessage().contains(KEY));
        assertFalse(printed(failure).contains(KEY));
        assertEquals(0, endpoint.calls());
    }

    @Test
    @DisplayName("an unresolvable reference is refused without naming the reference")
    void anUnresolvableReferenceIsRefusedWithoutNamingIt() {
        var unresolvable = new OpenAiCompatibleModelProvider("ollama-local",
                reference -> Optional.empty(), endpoint.endpoint(), "qwen3",
                CredentialRequirement.REQUIRED, Duration.ofSeconds(10));

        ModelInvocationException failure = failureOf(unresolvable);

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_UNRESOLVED, failure.reason());
        // The reference is the field most likely to contain a pasted secret, so it is not echoed.
        assertFalse(failure.getMessage().contains(KEY_REFERENCE));
        assertEquals(0, endpoint.calls());
    }

    // ---- the two credential modes ----------------------------------------------------------------

    @Test
    @DisplayName("REQUIRED: a node with no credentialRef is refused before any request")
    void requiredRefusesANodeWithNoReference() {
        var required = provider(CredentialRequirement.REQUIRED);
        ModelRequest anonymous = new ModelRequest(UUID.randomUUID(), "node-1", "hello", "payload", "",
                "", Map.of());

        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> required.generate(anonymous).toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_REFERENCE_ABSENT,
                assertInstanceOf(ModelInvocationException.class, raised.getCause()).reason());
        assertEquals(0, endpoint.calls());
    }

    @Test
    @DisplayName("NONE: the request carries no authorization header at all")
    void noneSendsNoAuthorizationHeader() throws Exception {
        var local = provider(CredentialRequirement.NONE);
        ModelRequest anonymous = new ModelRequest(UUID.randomUUID(), "node-1", "hello", "payload", "",
                "", Map.of());

        local.generate(anonymous).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, endpoint.calls());
        assertEquals("", endpoint.observedAuthorization());
    }

    @Test
    @DisplayName("NONE: a node that declares a credentialRef is refused, not silently ignored")
    void noneRefusesANodeThatDeclaresAReference() {
        // Ignoring it would run unauthenticated against an endpoint the author believed was
        // authenticated; honouring it would put a secret on a wire the operator declared carries
        // none. Refusing is the only branch that is true. And crucially: nothing is sent.
        var local = provider(CredentialRequirement.NONE);

        ModelInvocationException failure = failureOf(local);

        assertEquals(ModelInvocationException.Reason.CREDENTIAL_NOT_ACCEPTED, failure.reason());
        assertEquals(0, endpoint.calls());
        assertFalse(failure.getMessage().contains(KEY_REFERENCE));
    }

    @Test
    @DisplayName("two different references send two different keys, with no cache in between")
    void twoDifferentReferencesSendTwoDifferentKeys() throws Exception {
        var routing = new OpenAiCompatibleModelProvider("ollama-local",
                reference -> Optional.of(new SecretValue(("key-for-" + reference).toCharArray())),
                endpoint.endpoint(), "qwen3", CredentialRequirement.REQUIRED, Duration.ofSeconds(10));

        routing.generate(requestWith("first")).toCompletableFuture().get(10, TimeUnit.SECONDS);
        routing.generate(requestWith("second")).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(List.of("Bearer key-for-first", "Bearer key-for-second"),
                endpoint.observedAuthorizations());
    }

    @Test
    @DisplayName("the adapter holds no cache at all, and exactly one client")
    void theAdapterHoldsNoCacheAtAllAndExactlyOneClient() {
        long caches = java.util.Arrays.stream(OpenAiCompatibleModelProvider.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Map.class.isAssignableFrom(field.getType())
                        || java.util.Collection.class.isAssignableFrom(field.getType()))
                .count();
        assertEquals(0, caches, "a cache here would hold key material between calls");

        long clients = java.util.Arrays.stream(OpenAiCompatibleModelProvider.class.getDeclaredFields())
                .filter(field -> field.getType() == java.net.http.HttpClient.class)
                .count();
        assertEquals(1, clients);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static List<Executable> creationPaths() {
        var paths = new java.util.ArrayList<Executable>();
        for (Constructor<?> constructor : ModelInvocationException.class.getDeclaredConstructors()) {
            paths.add(constructor);
        }
        for (Method method : ModelInvocationException.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && ModelInvocationException.class.isAssignableFrom(method.getReturnType())) {
                paths.add(method);
            }
        }
        return List.copyOf(paths);
    }

    private OpenAiCompatibleModelProvider provider(CredentialRequirement requirement) {
        return new OpenAiCompatibleModelProvider("ollama-local",
                resolver(), endpoint.endpoint(), "qwen3", requirement, Duration.ofSeconds(10));
    }

    private static CredentialResolver resolver() {
        return reference -> KEY_REFERENCE.equals(reference)
                ? Optional.of(new SecretValue(KEY.toCharArray()))
                : Optional.empty();
    }

    private ModelInvocationException failure() {
        return failureOf(provider(CredentialRequirement.REQUIRED));
    }

    private static ModelInvocationException failureOf(OpenAiCompatibleModelProvider provider) {
        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> provider.generate(hostileRequest()).toCompletableFuture().get(10, TimeUnit.SECONDS));
        return assertInstanceOf(ModelInvocationException.class, raised.getCause());
    }

    /** Carries all four sentinels: the reference, a parameter, and the rendered prompt. */
    private static ModelRequest hostileRequest() {
        return new ModelRequest(UUID.randomUUID(), "node-1",
                "Summarise this, and here is a pasted secret: " + PROMPT_SECRET, "payload", "",
                KEY_REFERENCE, Map.of("apiKey", PARAMETER_SECRET, "maxTokens", "64"));
    }

    private static ModelRequest requestWith(String reference) {
        return new ModelRequest(UUID.randomUUID(), "node-1", "hello", "payload", "", reference,
                Map.of());
    }

    private static String printed(Throwable failure) {
        var buffer = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            failure.printStackTrace(stream);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
