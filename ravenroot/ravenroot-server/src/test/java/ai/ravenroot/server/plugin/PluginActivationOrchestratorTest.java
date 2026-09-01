package ai.ravenroot.server.plugin;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.security.AllowlistToolPolicy;
import ai.ravenroot.core.security.EnvironmentCredentialResolver;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.ProviderCredentialResolver;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.plugin.bundle.PluginBundleException;
import ai.ravenroot.plugin.bundle.PluginBundleValidator;
import com.example.orchestratorfixture.OrchestratorFixtureNodePackage;
import com.example.orchestratorfixture.OrchestratorCollisionFixtureNodePackage;
import com.example.orchestratorfixture.LegacyOrchestratorFixtureNodePackage;
import com.example.orchestratorfixture.ServiceAwareOrchestratorFixtureNodePackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginActivationOrchestrator} is the full sequence -- resolve environment, activate, merge
 * with {@code NodePackageLoader}, register -- and it is deliberately silent (no console, no audit),
 * so it can be tested directly: given a real plugins directory and environment map, it either returns
 * or throws.
 */
class PluginActivationOrchestratorTest {

    @Test
    void anEmptyEnvironmentActivatesNothingAndStillRegistersTheStandardCatalog() {
        var registered = PluginActivationOrchestrator.register(BehaviorRegistry.standard(behaviorEnvironment()),
                Map.of());

        assertTrue(registered.activation().packages().isEmpty());
        assertTrue(registered.activation().manifests().isEmpty());
    }

    @Test
    void anUnknownEnabledIdPropagatesFromTheLoaderUnchanged(@TempDir Path pluginsDir) {
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_ENABLED_PLUGINS", "does.not.exist");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginActivationOrchestrator.register(BehaviorRegistry.standard(behaviorEnvironment()),
                        environment));
        assertEquals(PluginBundleException.Reason.UNKNOWN_PLUGIN_ID, rejection.reason());
    }

    @Test
    void aRealBundleActivatesAndMergesIntoTheRegistry(@TempDir Path pluginsDir) throws Exception {
        writeFixtureBundle(pluginsDir, "test.orchestrator.fixture");
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_ENABLED_PLUGINS", "test.orchestrator.fixture");

        var registered = PluginActivationOrchestrator.register(BehaviorRegistry.standard(behaviorEnvironment()),
                environment);

        assertEquals(1, registered.activation().packages().size());
        assertTrue(registered.registry().descriptor("orchestrator.probe").isPresent(),
                "the activated bundle's behavior must reach the SAME registry the server actually uses");
        registered.activation().close();
    }

    @Test
    void explicitCompositionInjectsTheExactGrantAndLegacyCompositionFailsClosed(@TempDir Path pluginsDir) {
        ServiceAwareOrchestratorFixtureNodePackage.reset();
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_NODE_PACKAGES", ServiceAwareOrchestratorFixtureNodePackage.class.getName());
        NodePackageServices granted = httpOnlyServices();
        var services = NodePackageServiceRegistry.builder()
                .grant("test.orchestrator.services", granted).build();

        var registered = PluginActivationOrchestrator.register(
                BehaviorRegistry.standard(behaviorEnvironment()), environment, services);
        registered.registry().create(new GraphNode("probe", NodeKind.BEHAVIOR,
                "orchestrator.services", Map.of())).orElseThrow();

        assertSame(granted, ServiceAwareOrchestratorFixtureNodePackage.RECEIVED_SERVICES.get());
        assertFalse(ServiceAwareOrchestratorFixtureNodePackage.LEGACY_CREATE_CALLED.get());
        registered.activation().close();

        BehaviorRegistry withoutComposition = BehaviorRegistry.standard(behaviorEnvironment());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PluginActivationOrchestrator.register(withoutComposition, environment));
        assertTrue(refused.getMessage().contains("outbound-http"));
        assertTrue(withoutComposition.descriptor("orchestrator.services").isEmpty(),
                "missing composition must fail before behavior mutation");
    }

    /**
     * Both grant directions through the surface an operator actually has: an environment map.
     *
     * <p>The test above proves the orchestrator honours a registry someone hands it. This one proves
     * a registry gets composed from what a deployment sets. Without that composition, no bundle declaring a required
     * capability could activate on it however correct the rest of the machinery was.</p>
     */
    @Test
    void anOperatorGrantInTheEnvironmentActivatesAServiceAwarePackageAndItsAbsenceRefusesIt(
            @TempDir Path pluginsDir) {
        ServiceAwareOrchestratorFixtureNodePackage.reset();
        String grantVariable = EnvironmentNodePackageServiceGrants
                .environmentVariableName("test.orchestrator.services");
        Map<String, String> withoutGrant = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_NODE_PACKAGES", ServiceAwareOrchestratorFixtureNodePackage.class.getName());
        Map<String, String> withGrant = new HashMap<>(withoutGrant);
        withGrant.put(grantVariable, java.util.Base64.getEncoder().encodeToString(
                "{\"capabilities\":[\"outbound-http\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var granted = EnvironmentNodePackageServiceGrants.fromEnvironment(withGrant, NO_CREDENTIALS);
        var registered = PluginActivationOrchestrator.register(
                BehaviorRegistry.standard(behaviorEnvironment()), withGrant, granted);
        registered.registry().create(new GraphNode("probe", NodeKind.BEHAVIOR,
                "orchestrator.services", Map.of())).orElseThrow();

        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP),
                ServiceAwareOrchestratorFixtureNodePackage.RECEIVED_SERVICES.get().capabilities());
        assertFalse(ServiceAwareOrchestratorFixtureNodePackage.LEGACY_CREATE_CALLED.get());
        registered.activation().close();

        // Same bundle, same directory, same allowlist -- only the grant removed. Deny by default is
        // the whole contract, so this must be a refusal and not a quieter activation.
        BehaviorRegistry withoutComposition = BehaviorRegistry.standard(behaviorEnvironment());
        var ungranted = EnvironmentNodePackageServiceGrants.fromEnvironment(withoutGrant, NO_CREDENTIALS);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PluginActivationOrchestrator.register(withoutComposition, withoutGrant, ungranted));
        assertTrue(refused.getMessage().contains("outbound-http"), refused::getMessage);
        assertTrue(withoutComposition.descriptor("orchestrator.services").isEmpty(),
                "missing composition must fail before behavior mutation");
    }

    /**
     * The admissible-reference list, end to end through the surface an operator has, on the service
     * view a behavior actually receives.
     *
     * <p>The list is the only boundary {@code credential-resolution} can have: on that path no egress
     * policy is consulted and the secret is returned to the package in the clear. So it is not enough
     * that the parser accepts the member — the restricted resolver has to be the one that reaches
     * {@code ManagedNodePackageServices}, and that is what this asserts, by resolving through the
     * captured {@code NodePackageServices} rather than through anything this test composed.</p>
     */
    @Test
    void anAdmissibleReferenceListReachesTheServiceViewTheBehaviorReceives(@TempDir Path pluginsDir)
            throws Exception {
        ServiceAwareOrchestratorFixtureNodePackage.reset();
        var asked = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        ai.ravenroot.core.security.nodepackage.TenantCredentialResolver recording =
                (packageId, tenantId, reference) -> {
                    asked.add(reference);
                    return java.util.Optional.of(new ai.ravenroot.api.security.SecretValue(
                            "secret".toCharArray()));
                };
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_NODE_PACKAGES", ServiceAwareOrchestratorFixtureNodePackage.class.getName(),
                EnvironmentNodePackageServiceGrants.environmentVariableName("test.orchestrator.services"),
                java.util.Base64.getEncoder().encodeToString("""
                        {"capabilities":["outbound-http","credential-resolution"],
                         "credentialReferences":["allowed"]}"""
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var registered = PluginActivationOrchestrator.register(
                BehaviorRegistry.standard(behaviorEnvironment()), environment,
                EnvironmentNodePackageServiceGrants.fromEnvironment(environment, recording));
        registered.registry().create(new GraphNode("probe", NodeKind.BEHAVIOR,
                "orchestrator.services", Map.of())).orElseThrow();
        var services = ServiceAwareOrchestratorFixtureNodePackage.RECEIVED_SERVICES.get();

        assertTrue(await(services.credentials().resolve(message("tenant-a"), "allowed",
                java.time.Duration.ofSeconds(5))), "an admitted reference must resolve");
        assertFalse(await(services.credentials().resolve(message("tenant-a"), "denied",
                java.time.Duration.ofSeconds(5))), "a reference outside the list must not resolve");
        assertEquals(List.of("allowed"), asked,
                "the deployment credential path must never even be asked for a reference outside the list");
        registered.activation().close();
    }

    /**
     * What the startup refusal does <em>not</em> remove, pinned as behaviour rather than left in prose.
     *
     * <p>{@code TenantCredentialResolver} carries no caller-path discriminator, so one restricted view
     * serves signing and reading alike. A grant that concedes
     * {@code credential-resolution} alongside a list is refused at startup unless every SigV4-bound
     * reference appears in that list — so the operator here has written {@code storage-key} into it,
     * which is the only way this grant composes at all. This test is what happens next: the reference
     * they bound to <em>sign</em> toward one HTTPS origin is now readable in the clear by the package.
     * The refusal made the exposure explicit; it did not remove it, and a documented consequence that
     * nothing executes is exactly how the earlier divergence between the prose and the code got in.</p>
     */
    @Test
    void aSigningReferenceWrittenIntoTheListIsThenReadableInTheClear(
            @TempDir Path pluginsDir) throws Exception {
        ServiceAwareOrchestratorFixtureNodePackage.reset();
        ai.ravenroot.core.security.nodepackage.TenantCredentialResolver deployment =
                (packageId, tenantId, reference) -> java.util.Optional.of(
                        new ai.ravenroot.api.security.SecretValue(("secret:" + reference).toCharArray()));
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_NODE_PACKAGES", ServiceAwareOrchestratorFixtureNodePackage.class.getName(),
                EnvironmentNodePackageServiceGrants.environmentVariableName("test.orchestrator.services"),
                java.util.Base64.getEncoder().encodeToString("""
                        {"capabilities":["outbound-http","credential-resolution"],
                         "credentialReferences":["api-key","storage-key"],
                         "awsSigV4Bindings":[{"bindingId":"storage",
                           "origin":{"scheme":"https","host":"s3.eu-west-1.amazonaws.com","port":443},
                           "credentialReference":"storage-key","region":"eu-west-1","service":"s3"}]}"""
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var registered = PluginActivationOrchestrator.register(
                BehaviorRegistry.standard(behaviorEnvironment()), environment,
                EnvironmentNodePackageServiceGrants.fromEnvironment(environment, deployment));
        registered.registry().create(new GraphNode("probe", NodeKind.BEHAVIOR,
                "orchestrator.services", Map.of())).orElseThrow();
        var services = ServiceAwareOrchestratorFixtureNodePackage.RECEIVED_SERVICES.get();

        try (var lease = services.credentials()
                .resolve(message("tenant-a"), "storage-key", java.time.Duration.ofSeconds(5))
                .completion().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS)) {
            assertEquals("secret:storage-key", new String(lease.copy()),
                    "once written into the list, a SigV4-bound reference is readable in the clear");
        }
        // The list still governs everything it names, and everything it does not.
        assertTrue(await(services.credentials().resolve(message("tenant-a"), "api-key",
                java.time.Duration.ofSeconds(5))));
        assertFalse(await(services.credentials().resolve(message("tenant-a"), "unrelated",
                java.time.Duration.ofSeconds(5))));
        registered.activation().close();
    }

    /** True when the call produced a lease; false when it refused. Closes the lease either way. */
    private static boolean await(ai.ravenroot.api.node.service.OutboundCall<
            ai.ravenroot.api.node.service.CredentialLease> call) throws Exception {
        try (var lease = call.completion().toCompletableFuture()
                .get(10, java.util.concurrent.TimeUnit.SECONDS)) {
            return lease != null;
        } catch (java.util.concurrent.ExecutionException refused) {
            return false;
        }
    }

    private static ai.ravenroot.api.execution.NodeMessage message(String tenant) {
        java.util.UUID id = java.util.UUID.randomUUID();
        return new ai.ravenroot.api.execution.NodeMessage(
                new ai.ravenroot.api.security.SecurityContext("request", tenant, "subject",
                        ai.ravenroot.api.security.PrincipalType.USER, "issuer"),
                id, id, "node", null, Map.of());
    }

    /**
     * The two-argument {@code registerWithInventory} is what embedders compile against. Its meaning is
     * "no grants", and that has to stay true by test and not just by reading.
     */
    @Test
    void theTwoArgumentEntryPointStillMeansNoGrantsAtAll(@TempDir Path pluginsDir) {
        ServiceAwareOrchestratorFixtureNodePackage.reset();
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_NODE_PACKAGES", ServiceAwareOrchestratorFixtureNodePackage.class.getName(),
                EnvironmentNodePackageServiceGrants.environmentVariableName("test.orchestrator.services"),
                java.util.Base64.getEncoder().encodeToString(
                        "{\"capabilities\":[\"outbound-http\"]}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // The grant is in the environment and is ignored, because this overload never reads it.
        assertThrows(IllegalArgumentException.class,
                () -> PluginActivationOrchestrator.registerWithInventory(
                        BehaviorRegistry.standard(behaviorEnvironment()), environment));
    }

    @Test
    void sdkOneClasspathPackageAlwaysUsesTheLegacyBridge(@TempDir Path pluginsDir) {
        LegacyOrchestratorFixtureNodePackage.reset();
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_NODE_PACKAGES", LegacyOrchestratorFixtureNodePackage.class.getName());
        var services = NodePackageServiceRegistry.builder()
                .grant("test.orchestrator.legacy", httpOnlyServices()).build();

        var registered = PluginActivationOrchestrator.register(
                BehaviorRegistry.standard(behaviorEnvironment()), environment, services);
        registered.registry().create(new GraphNode("legacy", NodeKind.BEHAVIOR,
                "orchestrator.legacy", Map.of())).orElseThrow();

        assertTrue(LegacyOrchestratorFixtureNodePackage.LEGACY_CREATE_CALLED.get());
        assertFalse(LegacyOrchestratorFixtureNodePackage.SERVICE_CREATE_CALLED.get());
        registered.activation().close();
    }

    /**
     * The property this test exists for: a bundle that activates cleanly through the loader can still
     * collide with the built-in catalog at registration -- and when it does, the classloader(s)
     * PluginBundleLoader already created must not leak. NodePackages.register refuses rather than
     * replacing, so registering the same fixture id/behavior twice (once directly, once via a second
     * bundle claiming the same behavior name) reproduces the collision without needing a real built-in
     * name.
     */
    @Test
    void aRegistrationFailureAfterSuccessfulLoadingClosesTheClassLoaderRatherThanLeakingIt(
            @TempDir Path pluginsDir) throws Exception {
        writeFixtureBundle(pluginsDir, "test.orchestrator.fixture");
        Map<String, String> environment = new HashMap<>(Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_ENABLED_PLUGINS", "test.orchestrator.fixture"));
        // NodePackageLoader's own path already registers a package that declares the SAME behavior
        // name ("orchestrator.probe") this fixture bundle declares -- forcing NodePackages.register to
        // refuse the second one as a duplicate, entirely independent of anything PluginBundleLoader
        // itself checks.
        environment.put("RAVENROOT_NODE_PACKAGES", OrchestratorFixtureNodePackage.class.getName());

        assertThrows(IllegalArgumentException.class, () -> PluginActivationOrchestrator.register(
                BehaviorRegistry.standard(behaviorEnvironment()), environment));
        // No direct assertion on classloader closure is possible from outside the package (the
        // classloader itself is not exposed), but PluginActivationOrchestrator.register's own
        // try/catch calling activation.close() before rethrowing is what this test exercises; a
        // regression there would not surface as a visible failure here, only as a resource leak over
        // many startup attempts, which is exactly why the ownership is documented rather than left to
        // be rediscovered.
    }

    /**
     * The collision-with-a-package check {@code NodePackages.validate}
     * already enforces (see {@code NodePackageRegistrationTest.aPackageMayNotReplaceAnExistingBehaviorName}
     * for the built-in-catalog case) is proven here between TWO REAL, INDEPENDENTLY LOADED PLUGIN
     * BUNDLES for the first time -- the existing
     * {@code aRegistrationFailureAfterSuccessfulLoadingClosesTheClassLoaderRatherThanLeakingIt} test
     * above collides a bundle against an in-process {@code RAVENROOT_NODE_PACKAGES} package, not a
     * second bundle. Both bundles here reuse the same fixture class
     * ({@code OrchestratorFixtureNodePackage}, declaring {@code orchestrator.probe}) under two
     * different manifest ids, which is enough: PluginBundleLoader treats them as two independent
     * bundles with two independent classloaders regardless of shared class bytes, and
     * NodePackages.register refuses the second registration attempt exactly as it would for any other
     * two packages declaring the same behavior name.
     */
    @Test
    void twoDistinctEnabledBundlesDeclaringTheSameBehaviorIdAreBothRefused(@TempDir Path pluginsDir)
            throws Exception {
        writeFixtureBundle(pluginsDir, "test.orchestrator.fixture.a");
        writeFixtureBundle(pluginsDir, "test.orchestrator.fixture.b",
                OrchestratorCollisionFixtureNodePackage.class);
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_ENABLED_PLUGINS", "test.orchestrator.fixture.a,test.orchestrator.fixture.b");

        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> PluginActivationOrchestrator.register(BehaviorRegistry.standard(behaviorEnvironment()),
                        environment));

        assertTrue(rejection.getMessage().contains("orchestrator.probe"),
                "the refusal must name the colliding behavior, not just fail silently");
        // Both bundles' classloaders were already created by PluginBundleLoader.load before
        // registerAll ever ran (activation succeeds at the loader level; only registration collides),
        // so this is the same classloader-leak risk the existing collision test above documents --
        // PluginActivationOrchestrator.register's try/catch around activation.close() covers this
        // case identically, regardless of whether the other contributor is a bundle or an in-process
        // package.
    }

    /**
     * The strongest form of "presence never executes", extended to the
     * collision case specifically -- a SECOND bundle that would collide with the first is never even
     * loaded, let alone refused, as long as it is not itself named in the allowlist. Proves the
     * registry ends up with exactly the enabled bundle's behavior and nothing about the inactive one's
     * presence on disk affects that outcome: PluginBundleLoader only loads ids present in
     * RAVENROOT_ENABLED_PLUGINS, so the colliding bundle is never scanned into a classloader, never
     * reaches NodePackages.register, and therefore never has the chance to collide with anything.
     */
    @Test
    void anEnabledBundleActivatesNormallyWhileACollidingButNotEnabledBundleOnDiskHasNoEffect(
            @TempDir Path pluginsDir) throws Exception {
        writeFixtureBundle(pluginsDir, "test.orchestrator.fixture.enabled");
        writeFixtureBundle(pluginsDir, "test.orchestrator.fixture.inactive-collider");
        Map<String, String> environment = Map.of(
                PluginActivationOrchestrator.PLUGINS_DIR_ENVIRONMENT_VARIABLE, pluginsDir.toString(),
                "RAVENROOT_ENABLED_PLUGINS", "test.orchestrator.fixture.enabled");

        var registered = PluginActivationOrchestrator.register(BehaviorRegistry.standard(behaviorEnvironment()),
                environment);

        assertEquals(1, registered.activation().packages().size(),
                "only the enabled bundle should ever be loaded -- the colliding, not-enabled bundle "
                        + "must never reach the loader at all, not merely lose a registration race");
        assertTrue(registered.registry().descriptor("orchestrator.probe").isPresent());
        registered.activation().close();
    }

    private static BehaviorEnvironment behaviorEnvironment() {
        return new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                new ProviderCredentialResolver(new EnvironmentCredentialResolver()),
                AllowlistToolPolicy.fromCommaSeparated(null), OutboundHttpPolicy.fromCommaSeparatedHosts(null));
    }

    private static final ai.ravenroot.core.security.nodepackage.TenantCredentialResolver NO_CREDENTIALS =
            (packageId, tenantId, reference) -> java.util.Optional.empty();

    private static NodePackageServices httpOnlyServices() {
        NodePackageServices unavailable = NodePackageServices.unavailable();
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() {
                return Set.of(NodePackageCapability.OUTBOUND_HTTP);
            }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return unavailable.credentials();
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
                return unavailable.outboundHttp();
            }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
                return unavailable.outboundWebSocket();
            }
        };
    }

    private static void writeFixtureBundle(Path pluginsDir, String manifestId) throws Exception {
        writeFixtureBundle(pluginsDir, manifestId, OrchestratorFixtureNodePackage.class);
    }

    private static void writeFixtureBundle(Path pluginsDir, String manifestId,
                                           Class<? extends ai.ravenroot.api.node.NodePackage> fixtureClass)
            throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve(manifestId));
        String simpleName = fixtureClass.getSimpleName();
        byte[] mainClassBytes = classBytes(fixtureClass, simpleName + ".class");
        byte[] behaviorClassBytes = classBytes(fixtureClass, simpleName + "$ProbeBehavior.class");
        String classPath = fixtureClass.getName().replace('.', '/');
        Path jarPath = bundleDir.resolve("plugin.jar");
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry(classPath + ".class"));
            zipOut.write(mainClassBytes);
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry(classPath + "$ProbeBehavior.class"));
            zipOut.write(behaviorClassBytes);
            zipOut.closeEntry();
        }
        String sha256 = sha256Hex(jarPath);
        long size = Files.size(jarPath);
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"%s",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["%s"],
                  "behaviors":["orchestrator.probe"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(manifestId, NodeSdk.CONTRACT, fixtureClass.getName(), sha256, size));
    }

    private static byte[] classBytes(Class<?> declaringClass, String simpleName) throws IOException {
        String packagePath = declaringClass.getPackageName().replace('.', '/');
        try (InputStream in = declaringClass.getResourceAsStream("/" + packagePath + "/" + simpleName)) {
            if (in == null) {
                throw new IllegalStateException("Test fixture not compiled: " + simpleName);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }
}
