package ai.ravenroot.devharness;

import ai.ravenroot.adapter.openaicompatible.CredentialRequirement;
import ai.ravenroot.adapter.openaicompatible.OpenAiCompatibleModelProvider;
import ai.ravenroot.api.execution.ExecutionEngines;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.AllowlistToolPolicy;
import ai.ravenroot.core.security.EnvironmentCredentialResolver;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.ProviderCredentialResolver;
import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.AuthenticationConfiguration;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A local, source-only bench that starts Ravenroot with an {@code llm-prompt} node it supplies itself
 * and a model provider already registered.
 *
 * <h1>THIS IS NOT A RELEASE ARTIFACT, AND IT IS NOT AN AIRTIGHT SERVER</h1>
 * <p>Read {@code README.md} in this directory before running it, and
 * {@code docs/architecture/model-provider-adapters.md} for why it exists in this shape.
 *
 * <h2>The bench supplies the node as well as the provider</h2>
 * <p>The core supplies neither {@code llm-prompt} nor a registered {@code ModelProvider}.
 * {@link LlmPromptNodeBehaviorFactory} therefore lives in this module and is registered below on top
 * of the standard catalogue. The program has one configuration surface — its environment variables
 * — and arms the registry directly from it.
 *
 * <h2>Why a second entry point exists at all</h2>
 * <p>Because there is no first one that can do this. {@code RavenrootServerMain} builds an
 * <em>empty</em> {@link ModelProviderRegistry} and never calls {@code register}; there is no
 * {@code ServiceLoader} for {@code ModelProvider}; and {@code NodePackage} — the operator-named
 * extension point that does load classes from outside the artifact — contributes
 * {@code NodeBehavior}s and is handed no {@link BehaviorEnvironment}, so it cannot reach the registry
 * either.
 *
 * <p>That is not a defect to be fixed in the reactor. <b>P3</b> of
 * {@code ReleaseArtifactBoundaryChecks} makes a call to {@code ModelProviderRegistry#register}
 * reachable from {@code RavenrootServerMain#main} a release violation: the released artefact must
 * not compose a model adapter into itself,
 * and the embedding seam is supplied from outside the artefact or not at all. Ravenroot's
 * qualification as an upstream component under EU AI Act recital 89 rests on the shipped artifact
 * not performing that composition, as captured by ADR 0017. So the composition below is deliberately in a
 * different program, in a different module, with different coordinates, that is never published.
 *
 * <h2>What makes "never published" a fact rather than a promise</h2>
 * <ol>
 *   <li>{@code maven.deploy.skip} and {@code maven.install.skip} are set in this module's pom, so
 *   {@code mvn deploy} and {@code mvn install} publish nothing — not to a repository, not to the
 *   local one.</li>
 *   <li>{@code NotAReleaseArtifactTest} fails the build if this module's coordinates appear in any
 *   release, publish or deploy surface: a workflow job outside the build/test allowlist, either
 *   {@code Dockerfile}, {@code compose.yaml}, anything under {@code deploy/}, or
 *   {@code ravenroot/pom.xml}'s {@code <modules>}.</li>
 *   <li>The name says it. {@code ravenroot-dev-harness}, not {@code ravenroot-server-full} — a
 *   convenient name is how the thing gets operated by someone who never read this file.</li>
 * </ol>
 *
 * <p><b>The condition none of the three can enforce:</b> this bench must never be used for production
 * work. No repository control can observe what it is used for; this is an operational constraint.
 *
 * <h2>The listener is loopback, and that is not configurable here</h2>
 * <p>{@code RAVENROOT_BIND_ADDRESS} is read by {@code RavenrootServerMain} and is deliberately
 * <em>ignored</em> below: this program forces {@link InetAddress#getLoopbackAddress()}. Serving a
 * reachable network would be putting into service under EU AI Act art. 3(11), and "it was only for
 * testing" is not an exemption there. The environment variable is not honoured rather than warned
 * about, because a warning is a control someone can decide to accept.
 *
 * <p>The authenticator is still the product's own, decided by
 * {@link AuthenticationConfiguration#fromEnvironment} against an environment whose bind address has
 * been forced to loopback — so the decision stays consistent with the socket actually opened, and on
 * loopback with no {@code RAVENROOT_AUTH_MODE} it is the same {@code disabled} mode, and the same
 * printed warning, that the shipped server produces locally. Nothing about authentication is
 * weakened here; only the address is fixed.
 */
public final class DevHarnessMain {

    /** Environment variable naming the provider ids to register, comma separated. */
    public static final String PROVIDERS_VARIABLE = "RAVENROOT_DEV_MODEL_PROVIDERS";

    /** Default when {@link #PROVIDERS_VARIABLE} is unset: a local Ollama target. */
    public static final String DEFAULT_PROVIDER_ID = "ollama-local";

    private static final String ENDPOINT_SUFFIX = "_ENDPOINT";
    private static final String MODEL_SUFFIX = "_MODEL";
    private static final String CREDENTIAL_REF_SUFFIX = "_CREDENTIAL_REF";
    private static final String PREFIX = "RAVENROOT_DEV_MODEL_";

    /** Ollama's OpenAI-compatible endpoint, which is what this bench exists to reach. */
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen3";

    private DevHarnessMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> environment = System.getenv();
        int port = Integer.parseInt(environment.getOrDefault("RAVENROOT_PORT", "8080"));

        // JVM-wide and installed before anything resolves a name, exactly as RavenrootServerMain
        // does. Copied rather than skipped: this bench must not be a place where an egress control
        // quietly does not apply.
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                environment.get("RAVENROOT_EGRESS_RESERVED_EXCEPTIONS")));

        CredentialResolver credentials =
                new ProviderCredentialResolver(new EnvironmentCredentialResolver());

        // This bench has one configuration surface, so it arms the registry directly from the
        // environment rather than introducing a second profile store.
        var providers = new ModelProviderRegistry();
        var toolPolicy = AllowlistToolPolicy.fromCommaSeparated(
                environment.getOrDefault("RAVENROOT_ALLOWED_TOOLS", "model.generate"));
        armDeclaredProviders(environment, providers, credentials);

        var artifacts = new InMemoryArtifactRegistry();
        var programRuntime = new DisabledProgramRuntime();
        var behaviorEnvironment = new BehaviorEnvironment(providers, new AgentRuntimeRegistry(),
                artifacts, programRuntime, credentials, toolPolicy,
                OutboundHttpPolicy.fromCommaSeparated(
                        environment.get("RAVENROOT_HTTP_ALLOWED_HOSTS"),
                        environment.get("RAVENROOT_HTTP_ALLOWED_PORTS"), 0, 0));

        // The core catalogue, plus the one node this bench brings. llm-prompt is not in
        // StandardBehaviorFactories, so composing the environment alone would arm a registry with no
        // reader -- see this class's own header.
        var behaviors = BehaviorRegistry.standard(behaviorEnvironment)
                .registerFactory(new LlmPromptNodeBehaviorFactory(providers, toolPolicy));

        var engine = ExecutionEngines.create(
                environment.getOrDefault("RAVENROOT_ENGINE", "pekko"), "ravenroot-dev-harness");
        var monitor = new ExecutionMonitor();
        var application = new DefaultRavenrootApplication(engine, monitor, behaviors, artifacts,
                programRuntime);

        // Forced, not read. See this class's Javadoc.
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        var authentication = AuthenticationConfiguration.fromEnvironment(loopbackOnly(environment), port);
        String uiPath = environment.getOrDefault("RAVENROOT_UI_DIR", "").trim();

        var server = new RavenrootServer(application, address, uiPath.isEmpty() ? null : Path.of(uiPath),
                authentication.authenticator());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
                // The process is exiting either way.
            } finally {
                engine.close();
            }
        }));

        server.start();
        System.out.println("""
                ------------------------------------------------------------------
                ravenroot-dev-harness: LOCAL DEVELOPMENT BENCH, NOT A RELEASE BUILD
                Do not operate this on a reachable network, and do not use it for
                real work. See README.md in this directory.
                ------------------------------------------------------------------""");
        System.out.println("Listening on http://" + address.getAddress().getHostAddress() + ":" + port
                + " (engine: " + engine.id() + ", authentication: " + authentication.mode()
                + ", model providers: " + String.join(", ", declaredProviderIds(environment)) + ")");
        if (uiPath.isEmpty()) {
            System.out.println("RAVENROOT_UI_DIR is unset, so no editor is served. Build the UI "
                    + "(npm --prefix ravenroot/ravenroot-ui run build) and point it at "
                    + "ravenroot/ravenroot-ui/dist.");
        }
        new CountDownLatch(1).await();
    }

    /**
     * One model target this bench declares from its own environment.
     *
     * <p>A local record rather than a server profile type: this program owns one configuration
     * surface and needs only the adapter identifier it installs.</p>
     *
     * <p><b>There is no component here that could hold a credential value, and that is structural
     * rather than a habit.</b> {@code credentialRef} names a reference the operator declared; its
     * value is resolved by {@code EnvironmentCredentialResolver} and is never read by this program.
     * A bench that put the secret itself into this record would have reintroduced, one module away,
     * precisely what the server refuses.</p>
     *
     * @param credentialRef the operator's declaration that this endpoint authenticates. Its
     *                      <em>presence</em> chooses {@link CredentialRequirement}; its value is not
     *                      read here and never leaves the resolver.
     */
    record DeclaredProvider(String id, java.net.URI endpoint, String model, String credentialRef) {
        DeclaredProvider {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(endpoint, "endpoint");
        }
    }

    /**
     * The providers this bench declares from its own environment.
     *
     * <p>Configuration is per-id environment variables, and an id may contain only {@code [a-z0-9-]}
     * so that the derivation to a variable-name segment is injective: {@code -} maps to {@code _}, and
     * {@code _} is not an admissible id character, so no two distinct ids can produce the same
     * variable. This avoids the same identifier-collision defect as
     * {@code EnvironmentCredentialResolver} by narrowing the id namespace -- which is
     * legitimate because an id is operator-chosen configuration, not graph content.</p>
     */
    static java.util.List<DeclaredProvider> declaredProviders(Map<String, String> environment) {
        var declared = new java.util.ArrayList<DeclaredProvider>();
        for (String id : declaredProviderIds(environment)) {
            String segment = variableSegment(id);
            String endpoint = environment.getOrDefault(PREFIX + segment + ENDPOINT_SUFFIX, DEFAULT_ENDPOINT);
            String model = environment.getOrDefault(PREFIX + segment + MODEL_SUFFIX, DEFAULT_MODEL);
            String credentialRef =
                    environment.getOrDefault(PREFIX + segment + CREDENTIAL_REF_SUFFIX, "").trim();
            declared.add(new DeclaredProvider(id, java.net.URI.create(endpoint), model, credentialRef));
        }
        return java.util.List.copyOf(declared);
    }

    /**
     * Registers every declared provider into {@code providers}, and returns the ids it armed.
     *
     * <h2>This is the one call in the tree that arms a node, and it is deliberately here</h2>
     * <p>Arming means {@code ModelProviderRegistry#register}, and <b>P3</b> of
     * {@code ReleaseArtifactBoundaryChecks} makes that call being reachable from
     * {@code RavenrootServerMain#main} or {@code RavenrootCliMain#main} a release violation. This
     * method performs it; it lives in a module that is never published, and the shipped composition
     * roots reach no registry at all. So the call graph P3 walks from either shipped {@code main}
     * stays empty, while this program's own {@code main} arms the registry the node below resolves
     * against.</p>
     *
     * <p>Idempotent on the id, as {@code ModelProviderRegistry#register} is: declaring the same id
     * twice with two different endpoints leaves the second one live.</p>
     */
    static java.util.List<String> armDeclaredProviders(Map<String, String> environment,
                                                       ModelProviderRegistry providers,
                                                       CredentialResolver credentials) {
        var armed = new java.util.ArrayList<String>();
        for (DeclaredProvider declared : declaredProviders(environment)) {
            // The reference is the OPERATOR's declaration that this endpoint authenticates. Its
            // presence chooses the mode; its value is not read here and never leaves the resolver.
            var requirement = declared.credentialRef().isEmpty()
                    ? CredentialRequirement.NONE
                    : CredentialRequirement.REQUIRED;
            providers.register(new OpenAiCompatibleModelProvider(declared.id(), credentials,
                    declared.endpoint().toString(), declared.model(), requirement, Duration.ofMinutes(10)));
            armed.add(declared.id());
        }
        return java.util.List.copyOf(armed);
    }

    private static java.util.List<String> declaredProviderIds(Map<String, String> environment) {
        String declared = environment.getOrDefault(PROVIDERS_VARIABLE, DEFAULT_PROVIDER_ID);
        var ids = new java.util.ArrayList<String>();
        for (String token : declared.split(",")) {
            String id = token.trim();
            if (!id.isEmpty()) {
                ids.add(requireAdmissibleId(id));
            }
        }
        return java.util.List.copyOf(ids);
    }

    /**
     * {@code [a-z0-9-]}, not starting with a hyphen. See {@link #declaredProviders}.
     *
     * <p>Written as an explicit per-character rejection rather than as a flag accumulated across a
     * loop. The first revision computed a {@code boolean admissible} and reassigned it each
     * iteration, relying on {@code admissible &&} in the loop condition to stop early. That is
     * correct as written and <em>silently wrong</em> after one plausible edit: widening the loop
     * condition to {@code index < id.length()} — the sort of change made while adding a check —
     * leaves only the <b>last</b> character deciding, and every id whose last character is legal is
     * admitted. The injectivity of {@link #variableSegment} rests entirely on this method, so a
     * structure that can decay into a no-op is the wrong structure to rest it on.
     */
    static String requireAdmissibleId(String id) {
        if (id == null || id.isEmpty() || id.charAt(0) == '-') {
            throw new IllegalArgumentException(PROVIDERS_VARIABLE + " ids may contain only lowercase "
                    + "letters, digits and hyphens, and may not start with a hyphen: '" + id + "'");
        }
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            boolean legal = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-';
            if (!legal) {
                throw new IllegalArgumentException(PROVIDERS_VARIABLE + " ids may contain only "
                        + "lowercase letters, digits and hyphens, and may not start with a hyphen: '"
                        + id + "'");
            }
        }
        return id;
    }

    /** {@code ollama-local} becomes {@code OLLAMA_LOCAL}. Injective, because ids exclude {@code _}. */
    static String variableSegment(String id) {
        return id.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    /**
     * The environment with its bind address forced to loopback.
     *
     * <p>A copy rather than a mutation, and the forcing is here rather than at the call site so the
     * authenticator decision and the socket cannot be made from two different addresses — which is
     * the way this control would decay: someone changes one and not the other, and the server
     * authenticates as if it were local while listening on a network.
     */
    static Map<String, String> loopbackOnly(Map<String, String> environment) {
        var forced = new LinkedHashMap<>(environment);
        forced.put("RAVENROOT_BIND_ADDRESS", InetAddress.getLoopbackAddress().getHostAddress());
        return Map.copyOf(forced);
    }
}
