package ai.ravenroot.server.plugin;

import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import ai.ravenroot.core.security.nodepackage.TenantCredentialResolver;
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.approval.ToolApprovalSettings;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The operator surface for node package service grants: one environment variable per package,
 * holding strict canonical Base64 of a JSON grant.
 *
 * <h2>Why an environment variable and not a configuration file</h2>
 * <p>This runtime has no configuration-file convention and a strong, repeated environment one:
 * credentials ({@code RAVENROOT_CREDENTIAL_<HEX>}), storage profiles, Kafka and Telegram profiles,
 * the plugin allowlist, the audit directory. A grant is operator authority of exactly that kind, so
 * it is expressed the same way rather than introducing a second place an operator has to look. The
 * variable name is derived through {@link EnvironmentKeyCodec}, the one injective derivation every
 * other resolver already uses, so two distinct package ids can never share a variable.</p>
 *
 * <h2>Presence is the grant; absence is no grant</h2>
 * <p>A capability is conceded because an operator wrote a variable, exactly as a plugin is activated
 * because an operator named it in {@code RAVENROOT_ENABLED_PLUGINS}. With no variable set,
 * {@link #fromEnvironment} returns {@link NodePackageServiceRegistry#empty()} and the server behaves
 * byte for byte as it did before this class existed. Nothing here infers a grant from a package
 * being installed, from a behavior declaring a requirement, or from a manifest: a declaration is a
 * request, never an authorization.</p>
 *
 * <p>Set-to-empty reads as unset, and nothing else does. Compose declares optional settings as
 * {@code NAME: ${NAME:-}}, which delivers them present and empty; an empty value states nothing, so
 * "no grant" is its only reading, and deny-by-default is what it produces either way.</p>
 *
 * <h2>Malformed input fails startup — the deliberate departure from the profile resolvers</h2>
 * <p>{@code EnvironmentStorageProfileResolver} answers {@link java.util.Optional#empty()} on
 * malformed input, and that is right <em>there</em>: a profile is read per execution, a missing one
 * refuses that one execution, and the operator sees a refusal naming the profile. A grant is read
 * once, at startup, and downgrading a malformed grant to "no grant" would convert an operator's typo
 * into a package refused for a missing capability — a diagnostic pointing at the wrong cause, on a
 * different line, about a different thing. So every rejection here throws
 * {@link NodePackageServiceGrantException} and startup stops with the variable named.</p>
 *
 * <h2>What a grant may say</h2>
 * <pre>{@code
 * {
 *   "capabilities": ["outbound-http"],
 *   "origins": [{"scheme": "https", "host": "s3.eu-west-1.amazonaws.com", "port": 443}],
 *   "httpMethods": ["GET", "PUT"],
 *   "requestHeaders": ["content-type"],
 *   "responseHeaders": ["etag"],
 *   "webSocketSubprotocols": [],
 *   "credentialBindings": [
 *     {"bindingId": "api", "origin": {"scheme": "https", "host": "api.example.com", "port": 443},
 *      "headerName": "X-Api-Key", "prefix": ""}
 *   ],
 *   "awsSigV4Bindings": [
 *     {"bindingId": "s3", "origin": {"scheme": "https", "host": "s3.eu-west-1.amazonaws.com", "port": 443},
 *      "credentialReference": "storage-key", "region": "eu-west-1", "service": "s3"}
 *   ],
 *   "credentialReferences": ["storage-key"],
 *   "limits": {"maxResponseBytes": 4194304, "maxDeadlineMs": 15000}
 * }
 * }</pre>
 *
 * <p>{@code capabilities} is required and non-empty; every other member is optional. An omitted
 * ceiling is the policy's own default, read off {@link NodePackageEgressPolicy} itself rather than
 * copied into constants here, so this class cannot drift from the defaults it claims to honour.
 * Unknown members are refused at every level, which is what makes a mistyped key a startup failure
 * instead of a silently narrower grant.</p>
 *
 * <h2>Two rules that are this reader's own, not the policy's</h2>
 * <p>Almost everything above is validated by {@link NodePackageEgressPolicy.Builder}. Two things are
 * decided here because they are only reachable through this variable:</p>
 * <ul>
 *   <li>A {@code credentialBindings} entry must target an {@code https} or {@code wss} origin. The
 *       policy accepts {@code http}/{@code ws} for a plain destination, which is fine, and applies no
 *       scheme rule to a credential placement, which is not — see {@code requireEncryptedOrigin} for
 *       why the check lives here rather than beside the identical SigV4 rule in core.</li>
 *   <li>{@code credentialReferences}, when written, is the set of references this package may
 *       resolve at all, unioned with the references its own SigV4 bindings name. It is the <em>only
 *       boundary that exists</em> for {@code credential-resolution}: on that path no egress policy is
 *       consulted, and the secret is returned to the package in the clear. Omitting the list leaves
 *       today's behaviour — every reference the deployment holds is resolvable by a package granted
 *       that capability. A reference in the list <em>is</em> readable in the clear by a package that
 *       holds {@code credential-resolution} — the resolver cannot tell reading from signing — so when
 *       both a list and that capability are present, a SigV4-bound reference the list omits is a
 *       startup refusal rather than a silent addition. See {@code credentialScope}.</li>
 * </ul>
 *
 * <p>Value-level validation is not reimplemented: origins, header names, methods, subprotocols,
 * bindings and every ceiling are handed to {@link NodePackageEgressPolicy.Builder}, which is the
 * component that owns those rules and already refuses a credential header that could alter transport
 * authority, a non-{@code s3} SigV4 profile, or a non-positive ceiling. This class only decodes.</p>
 */
public final class EnvironmentNodePackageServiceGrants {

    /** Prefix of the one variable family this class reads. The suffix is {@code hex(packageId)}. */
    public static final String VARIABLE_PREFIX = "RAVENROOT_NODE_PACKAGE_SERVICES_";

    private static final int MAX_GRANT_BYTES = 16 * 1024;
    private static final PayloadLimits LIMITS =
            new PayloadLimits(MAX_GRANT_BYTES, 8, 256, 4096, 4096, 64);

    /**
     * The policy's own defaults, obtained by building an empty policy rather than by transcribing
     * numbers. Three of the ten ceilings are public constants and seven are private, so any
     * transcription would have been half copy and half guess, and would age silently.
     */
    private static final NodePackageEgressPolicy DEFAULTS = NodePackageEgressPolicy.builder().build();

    private static final Set<String> GRANT_KEYS = Set.of("capabilities", "origins", "httpMethods",
            "requestHeaders", "responseHeaders", "webSocketSubprotocols", "credentialBindings",
            "awsSigV4Bindings", "credentialReferences", "limits");
    /** Schemes a credential binding may target. See {@link #requireEncryptedOrigin}. */
    private static final Set<String> ENCRYPTED_SCHEMES = Set.of("https", "wss");
    private static final Set<String> LIMIT_KEYS = Set.of("maxRequestBytes", "maxResponseBytes",
            "maxWebSocketMessageBytes", "maxWebSocketFragments", "maxQueuedWebSocketSends",
            "maxConcurrentOperations", "maxConcurrentPerTenant", "maxDeadlineMs",
            "maxWebSocketLifetimeMs", "maxWebSocketIdleMs");
    private static final Set<String> ORIGIN_KEYS = Set.of("scheme", "host", "port");
    private static final Set<String> CREDENTIAL_BINDING_KEYS =
            Set.of("bindingId", "origin", "headerName", "prefix");
    private static final Set<String> SIGV4_BINDING_KEYS =
            Set.of("bindingId", "origin", "credentialReference", "region", "service");

    private static final Map<String, NodePackageCapability> CAPABILITIES_BY_NAME = capabilitiesByName();

    private EnvironmentNodePackageServiceGrants() {
    }

    /** The exact variable an operator must set to grant services to {@code packageId}. */
    public static String environmentVariableName(String packageId) {
        return VARIABLE_PREFIX + EnvironmentKeyCodec.hex(packageId);
    }

    /**
     * Reads every grant present in {@code environment}, in deterministic variable-name order.
     *
     * @param environment the process environment, or a test's stand-in for it
     * @param credentials the deployment's one credential path, adapted for the managed services
     * @return a registry holding exactly the grants that were written, and nothing else
     * @throws NodePackageServiceGrantException if any variable in the family cannot be read; the
     *     exception names the variable and never carries its value
     */
    public static NodePackageServiceRegistry fromEnvironment(Map<String, String> environment,
                                                             TenantCredentialResolver credentials) {
        return fromEnvironment(environment, credentials, ToolPolicy.denyAll(), ToolCallAuditSink.discarding());
    }

    /**
     * Reads grants and composes each view with the deployment's one tool-policy and audit path.
     */
    public static NodePackageServiceRegistry fromEnvironment(Map<String, String> environment,
                                                             TenantCredentialResolver credentials,
                                                             ToolPolicy toolPolicy,
                                                             ToolCallAuditSink toolAuditSink) {
        return fromEnvironment(environment, credentials, toolPolicy, toolAuditSink, null, null);
    }

    /** Reads grants and optionally installs the trusted durable approval coordinator. */
    public static NodePackageServiceRegistry fromEnvironment(Map<String, String> environment,
                                                             TenantCredentialResolver credentials,
                                                             ToolPolicy toolPolicy,
                                                             ToolCallAuditSink toolAuditSink,
                                                             ToolApprovalService approvals,
                                                             ToolApprovalSettings approvalSettings) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(toolPolicy, "toolPolicy");
        Objects.requireNonNull(toolAuditSink, "toolAuditSink");

        // Sorted so that a deployment with two malformed grants fails on the same one every run.
        // An operator fixing startup failures one at a time needs the order to be a property of the
        // configuration, not of whatever order the platform happened to hand back the environment in.
        Map<String, String> family = new TreeMap<>();
        environment.forEach((key, value) -> {
            // Set-to-empty means the same thing as unset here, and only here: `getOrDefault` defends
            // against an ABSENT key, while Compose declares every optional setting as
            // `NAME: ${NAME:-}` and so delivers it present and empty -- the same trap byteCeiling()
            // in RavenrootServerMain documents. Reading an empty value as a malformed grant would
            // make a deployment that merely mentions the variable fail to start, and an empty value
            // cannot be a mistyped grant the way non-canonical Base64 can: it says nothing at all,
            // so "no grant" is the only reading it has. Deny-by-default is unaffected -- empty is
            // still no capability conceded.
            if (key != null && key.startsWith(VARIABLE_PREFIX) && value != null && !value.isBlank()) {
                family.put(key, value);
            }
        });
        if (family.isEmpty()) {
            return NodePackageServiceRegistry.empty();
        }

        NodePackageServiceRegistry.Builder registry = NodePackageServiceRegistry.builder();
        family.forEach((variable, encoded) -> {
            String packageId = packageIdOf(variable);
            Map<String, Object> grant = decode(variable, encoded);
            try {
                registry.grant(packageId, services(variable, packageId, grant, credentials,
                        toolPolicy, toolAuditSink, approvals, approvalSettings));
            } catch (IllegalArgumentException refused) {
                // Everything the operator wrote about origins, headers, methods, subprotocols,
                // bindings and ceilings is validated by NodePackageEgressPolicy.Builder and by the
                // registry itself -- those rules are theirs, not this class's. Their refusal reaches
                // the operator with the variable attached instead of as a bare IllegalArgumentException
                // that PluginActivationDiagnostics would have to guess the origin of.
                throw new NodePackageServiceGrantException(variable,
                        "node package service grant was refused: " + refused.getMessage(), refused);
            }
        });
        return registry.build();
    }

    /**
     * Recovers the package id a variable name encodes. The derivation is injective and reversible —
     * fixed-width hex of UTF-8 bytes — and the recovered id is re-derived and compared, so a
     * lower-case, odd-length or otherwise non-canonical suffix is refused rather than accepted into a
     * grant keyed by something the operator did not write.
     */
    private static String packageIdOf(String variable) {
        String suffix = variable.substring(VARIABLE_PREFIX.length());
        if (suffix.isEmpty() || suffix.length() % 2 != 0 || !suffix.matches("[0-9A-F]+")) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant variable does not end in canonical upper-case hex");
        }
        String packageId;
        try {
            byte[] bytes = HexFormat.of().parseHex(suffix);
            packageId = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException | IllegalArgumentException malformed) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant variable does not decode to a package id", malformed);
        }
        if (!EnvironmentKeyCodec.hex(packageId).equals(suffix)) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant variable is not the canonical encoding of its package id");
        }
        return packageId;
    }

    private static Map<String, Object> decode(String variable, String encoded) {
        // Blank values never reach here; they were read as "unset" while collecting the family.
        if (encoded.length() > MAX_GRANT_BYTES * 2) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant exceeds the accepted size");
        }
        byte[] json;
        try {
            json = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant is not Base64", notBase64);
        }
        if (!Base64.getEncoder().encodeToString(json).equals(encoded)) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant is not canonical Base64");
        }
        Object root;
        try {
            root = PayloadJson.read(json, LIMITS).toJava();
        } catch (RuntimeException unreadable) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant is not readable JSON within the accepted budgets",
                    unreadable);
        }
        return object(variable, root, "grant");
    }

    private static ManagedNodePackageServices services(String variable, String packageId,
                                                       Map<String, Object> grant,
                                                       TenantCredentialResolver credentials,
                                                       ToolPolicy toolPolicy,
                                                       ToolCallAuditSink toolAuditSink,
                                                       ToolApprovalService approvals,
                                                       ToolApprovalSettings approvalSettings) {
        exactlyKnownKeys(variable, grant, GRANT_KEYS, "grant");
        Set<NodePackageCapability> capabilities = capabilities(variable, grant.get("capabilities"));

        NodePackageEgressPolicy.Builder policy = NodePackageEgressPolicy.builder();
        for (Object origin : optionalList(variable, grant, "origins")) {
            NodePackageEgressPolicy.Origin parsed = origin(variable, origin, "origins");
            policy.allowOrigin(parsed.scheme(), parsed.host(), parsed.port());
        }
        for (Object method : optionalList(variable, grant, "httpMethods")) {
            policy.allowHttpMethod(text(variable, method, "httpMethods"));
        }
        for (Object header : optionalList(variable, grant, "requestHeaders")) {
            policy.allowRequestHeader(text(variable, header, "requestHeaders"));
        }
        for (Object header : optionalList(variable, grant, "responseHeaders")) {
            policy.allowResponseHeader(text(variable, header, "responseHeaders"));
        }
        for (Object subprotocol : optionalList(variable, grant, "webSocketSubprotocols")) {
            policy.allowWebSocketSubprotocol(text(variable, subprotocol, "webSocketSubprotocols"));
        }
        for (Object binding : optionalList(variable, grant, "credentialBindings")) {
            Map<String, Object> entry = object(variable, binding, "credentialBindings");
            exactlyKnownKeys(variable, entry, CREDENTIAL_BINDING_KEYS, "credentialBindings");
            policy.bindCredential(text(variable, entry.get("bindingId"), "credentialBindings.bindingId"),
                    requireEncryptedOrigin(variable,
                            origin(variable, entry.get("origin"), "credentialBindings.origin"),
                            "credentialBindings.origin"),
                    text(variable, entry.get("headerName"), "credentialBindings.headerName"),
                    entry.get("prefix") == null
                            ? "" : text(variable, entry.get("prefix"), "credentialBindings.prefix"));
        }
        // Binding id -> stripped credential reference, collected while parsing. Keyed by binding id
        // because that is what a refusal may name; see credentialScope.
        var boundReferences = new LinkedHashMap<String, String>();
        for (Object binding : optionalList(variable, grant, "awsSigV4Bindings")) {
            Map<String, Object> entry = object(variable, binding, "awsSigV4Bindings");
            exactlyKnownKeys(variable, entry, SIGV4_BINDING_KEYS, "awsSigV4Bindings");
            String reference = text(variable, entry.get("credentialReference"),
                    "awsSigV4Bindings.credentialReference");
            String bindingId = text(variable, entry.get("bindingId"), "awsSigV4Bindings.bindingId");
            // Both stripped on the way in: core's safeToken strips what it stores, so an unstripped
            // copy here would fail to admit the very reference the signer will ask for.
            boundReferences.put(bindingId.strip(), reference.strip());
            policy.bindAwsSigV4(bindingId,
                    // SigV4 already refuses anything but HTTPS itself; passed through unchanged so
                    // the refusal keeps coming from the component that owns the rule.
                    origin(variable, entry.get("origin"), "awsSigV4Bindings.origin"),
                    reference,
                    text(variable, entry.get("region"), "awsSigV4Bindings.region"),
                    text(variable, entry.get("service"), "awsSigV4Bindings.service"));
        }
        applyLimits(variable, grant.get("limits"), policy);

        TenantCredentialResolver scoped = credentialScope(variable, grant.get("credentialReferences"),
                boundReferences, capabilities, credentials);
        var services = ManagedNodePackageServices.builder(packageId, policy.build(), scoped)
                .toolAuthorization(toolPolicy, toolAuditSink);
        if (approvals != null && approvalSettings != null) {
            services.durableToolApprovals(approvals, approvalSettings);
        }
        capabilities.forEach(services::grant);
        return services.build();
    }

    /**
     * The optional admissible-reference list, and what it does about the references the operator
     * bound for signing.
     *
     * <h2>The problem the list creates for itself</h2>
     * <p>{@link ai.ravenroot.core.security.nodepackage.TenantCredentialResolver} receives a package
     * id, a tenant id and a reference, and <strong>nothing that says which path is asking</strong>.
     * One restricted view therefore serves all four routes into the credential path — the
     * {@code credential-resolution} capability, the HTTP and WebSocket credential placements, and
     * SigV4 signing — so "admit this reference for signing but refuse it for reading" is not
     * expressible here. Whatever the set contains is readable in the clear by a package that holds
     * {@code credential-resolution}.</p>
     *
     * <p>That leaves two things this method must not do, and they pull in opposite directions. It
     * must not drop a reference the operator bound for signing, or adding a list would silently break
     * that operator's own signing binding at runtime with a {@code CREDENTIAL_UNAVAILABLE} naming
     * nothing. And it must not quietly add one, because an operator who writes
     * {@code credentialReferences} is writing a constraint, and stepping over it in silence is the
     * same "no grant is ever conceded implicitly" this whole surface exists to enforce, one level
     * further down: not at the capability, but at the boundary the operator drew inside it.</p>
     *
     * <h2>What it does instead: refuse, so the operator writes it</h2>
     * <p>When {@code credential-resolution} is granted <em>and</em> a list is present, a SigV4-bound
     * reference missing from that list is a <strong>startup refusal</strong>. The operator resolves it
     * by adding the reference to the list — at which point the exposure is still there, and is now
     * something they wrote and can see, instead of something they inherited without ever meeting it.
     * The refusal removes the surprise, not the exposure.</p>
     *
     * <p>When {@code credential-resolution} is <em>not</em> granted there is no clear-text path at
     * all, nothing to be surprised by, and the union stands exactly as before: the bound references
     * are added silently and signing keeps working. That is the common case — an egress-only grant,
     * {@code ravenroot-object-storage} among them — and it is unchanged by this rule, which is what
     * makes the rule safe to introduce now.</p>
     *
     * <h2>Why the refusal names the binding id and not the missing reference</h2>
     * <p>Naming the reference would be defensible on its own terms — the operator wrote it, so it
     * reveals nothing new to them. It is not done, because {@link PluginActivationDiagnostics}
     * enforces one uniform rule for every value that reaches a console line or an audit record, and
     * credential references are named in that rule as a category that does not. An exception argued
     * from "this particular one is harmless" is the first hole in a rule whose whole value is having
     * none, and the diagnostic would be no more actionable for it: the binding id locates the exact
     * {@code awsSigV4Bindings} entry inside the document the operator is already editing, and its
     * {@code credentialReference} is on the next line.</p>
     *
     * <h2>Whitespace</h2>
     * <p>Declared and bound references are {@code strip()}ped, because {@code
     * ManagedNodePackageServices.safeReference} strips before asking the resolver and
     * {@code NodePackageEgressPolicy}'s own {@code safeToken} strips what it stores. A list entry
     * written as {@code " api-key "} would otherwise never match anything, producing exactly the mute
     * {@code CREDENTIAL_UNAVAILABLE} this method exists to keep out.</p>
     *
     * <p>Package-private rather than private so a test can assert these properties directly: none of
     * them is observable from the composed {@code NodePackageServices}, which does not expose its
     * resolver.</p>
     *
     * @param boundReferences binding id to stripped credential reference, for every SigV4 binding
     * @param capabilities the capabilities this grant concedes; only {@code CREDENTIAL_RESOLUTION}
     *     changes what this method does
     * @return {@code credentials} unchanged when no list was written, or a restricted view of it
     */
    static TenantCredentialResolver credentialScope(String variable, Object declared,
                                                    Map<String, String> boundReferences,
                                                    Set<NodePackageCapability> capabilities,
                                                    TenantCredentialResolver credentials) {
        if (declared == null) {
            return credentials;
        }
        var allowed = new java.util.LinkedHashSet<String>();
        for (Object reference : list(variable, declared, "credentialReferences")) {
            allowed.add(text(variable, reference, "credentialReferences").strip());
        }
        allowed.remove("");
        if (allowed.isEmpty()) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant declares an empty credentialReferences list; a list "
                            + "that admits nothing is a mistake rather than a way to write no grant");
        }
        if (capabilities.contains(NodePackageCapability.CREDENTIAL_RESOLUTION)) {
            for (Map.Entry<String, String> bound : boundReferences.entrySet()) {
                if (!allowed.contains(bound.getValue())) {
                    // Deliberately short, and the binding id is deliberately early. The console
                    // message is neutralized and capped at 200 characters by
                    // PluginActivationDiagnostics; a longer sentence loses its own remedy to the cap,
                    // which is the failure mode this refusal exists to prevent in the first place.
                    // Pinned by PluginActivationDiagnosticsTest against the real cap.
                    throw new NodePackageServiceGrantException(variable,
                            "awsSigV4Bindings entry '" + bound.getKey() + "' names a credential "
                                    + "reference the credentialReferences list omits, while "
                                    + "credential-resolution is granted. Add it to the list, or drop "
                                    + "that capability");
                }
            }
        } else {
            allowed.addAll(boundReferences.values());
        }
        return DeploymentGlobalTenantCredentials.restrictedTo(credentials, allowed);
    }

    /**
     * Refuses a credential binding aimed at a cleartext origin.
     *
     * <h2>Why the check is here and not beside the SigV4 one in core</h2>
     * <p>{@code NodePackageEgressPolicy.AwsSigV4SigningGrant} rejects a non-HTTPS origin in its own
     * compact constructor, and {@code CredentialPlacement} — the same kind of binding, carrying the
     * same kind of secret — does not. The obvious tidy-up is to move this rule next to that one, and
     * it is the wrong move: {@code CredentialPlacement} is a public core type an embedder composes
     * directly, so tightening it changes a published contract. The gap is <em>reachable</em> through
     * the environment-grant surface, so it is closed at that surface, at the price of the asymmetry
     * this paragraph explains. Moving it for symmetry without
     * replacing it reopens a hole that ships a deployment secret in the clear.</p>
     *
     * <h2>Why loopback is not excepted</h2>
     * <p>A literal-loopback exception for a same-host sidecar was considered and declined. No shipped
     * bundle needs one, and the asymmetry of the two mistakes decides it: the exception can be added
     * later against a real deployment, whereas withdrawing it later would break a grant an operator
     * had already written. A cleartext credential binding is precisely the thing this rule exists to
     * prevent, so it is not conceded speculatively.</p>
     */
    private static NodePackageEgressPolicy.Origin requireEncryptedOrigin(
            String variable, NodePackageEgressPolicy.Origin origin, String field) {
        if (!ENCRYPTED_SCHEMES.contains(origin.scheme())) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant binds a credential to a cleartext origin in " + field
                            + "; a credential binding requires https or wss");
        }
        return origin;
    }

    private static Set<NodePackageCapability> capabilities(String variable, Object declared) {
        if (declared == null) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant declares no capabilities member");
        }
        var granted = java.util.EnumSet.noneOf(NodePackageCapability.class);
        for (Object name : list(variable, declared, "capabilities")) {
            String capabilityName = text(variable, name, "capabilities");
            NodePackageCapability capability = CAPABILITIES_BY_NAME.get(capabilityName);
            if (capability == null) {
                throw new NodePackageServiceGrantException(variable,
                        "node package service grant names an unknown capability: " + capabilityName);
            }
            granted.add(capability);
        }
        if (granted.isEmpty()) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant declares an empty capabilities list; a grant that "
                            + "concedes nothing is a mistake rather than a way to write no grant");
        }
        return granted;
    }

    private static void applyLimits(String variable, Object declared,
                                    NodePackageEgressPolicy.Builder policy) {
        Map<String, Object> limits = declared == null
                ? Map.of() : object(variable, declared, "limits");
        exactlyKnownKeys(variable, limits, LIMIT_KEYS, "limits");
        policy.byteLimits(
                bytes(variable, limits, "maxRequestBytes", DEFAULTS.maximumRequestBytes()),
                bytes(variable, limits, "maxResponseBytes", DEFAULTS.maximumResponseBytes()),
                bytes(variable, limits, "maxWebSocketMessageBytes",
                        DEFAULTS.maximumWebSocketMessageBytes()));
        policy.concurrencyLimits(
                count(variable, limits, "maxConcurrentOperations", DEFAULTS.maximumConcurrentOperations()),
                count(variable, limits, "maxConcurrentPerTenant", DEFAULTS.maximumConcurrentPerTenant()));
        policy.webSocketLimits(
                count(variable, limits, "maxWebSocketFragments", DEFAULTS.maximumWebSocketFragments()),
                count(variable, limits, "maxQueuedWebSocketSends", DEFAULTS.maximumQueuedWebSocketSends()),
                millis(variable, limits, "maxWebSocketLifetimeMs", DEFAULTS.maximumWebSocketLifetime()),
                millis(variable, limits, "maxWebSocketIdleMs", DEFAULTS.maximumWebSocketIdle()));
        policy.maximumDeadline(millis(variable, limits, "maxDeadlineMs", DEFAULTS.maximumDeadline()));
    }

    private static NodePackageEgressPolicy.Origin origin(String variable, Object declared, String field) {
        Map<String, Object> entry = object(variable, declared, field);
        exactlyKnownKeys(variable, entry, ORIGIN_KEYS, field);
        long port = number(variable, entry.get("port"), field + ".port");
        if (port < 1 || port > 65535) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant declares a port outside 1-65535 in " + field);
        }
        return new NodePackageEgressPolicy.Origin(text(variable, entry.get("scheme"), field + ".scheme"),
                text(variable, entry.get("host"), field + ".host"), (int) port);
    }

    private static long bytes(String variable, Map<String, Object> limits, String field, long fallback) {
        Object declared = limits.get(field);
        return declared == null ? fallback : number(variable, declared, field);
    }

    private static int count(String variable, Map<String, Object> limits, String field, int fallback) {
        Object declared = limits.get(field);
        if (declared == null) {
            return fallback;
        }
        long value = number(variable, declared, field);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant declares an out-of-range limit: " + field);
        }
        return (int) value;
    }

    private static Duration millis(String variable, Map<String, Object> limits, String field,
                                   Duration fallback) {
        Object declared = limits.get(field);
        return declared == null ? fallback : Duration.ofMillis(number(variable, declared, field));
    }

    private static long number(String variable, Object declared, String field) {
        if (!(declared instanceof Long value)) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant expects a whole number at: " + field);
        }
        return value;
    }

    private static String text(String variable, Object declared, String field) {
        if (!(declared instanceof String value)) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant expects text at: " + field);
        }
        return value;
    }

    private static List<Object> optionalList(String variable, Map<String, Object> grant, String field) {
        Object declared = grant.get(field);
        return declared == null ? List.of() : list(variable, declared, field);
    }

    /**
     * Neither this nor {@link #object} uses {@code List.copyOf}/{@code Map.copyOf}: a JSON
     * {@code null} projects to a Java {@code null}, and those factories answer it with a
     * {@link NullPointerException} -- an unrelated failure type that would escape this class's own
     * refusal vocabulary and reach the operator as a generic registration error. Copied defensively
     * into an unmodifiable view instead, so a {@code null} arrives at the field check that can name
     * the member it was written under.
     */
    private static List<Object> list(String variable, Object declared, String field) {
        if (!(declared instanceof List<?> value)) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant expects a list at: " + field);
        }
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(value));
    }

    private static Map<String, Object> object(String variable, Object declared, String field) {
        if (!(declared instanceof Map<?, ?> raw)
                || raw.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new NodePackageServiceGrantException(variable,
                    "node package service grant expects an object at: " + field);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, entry) -> value.put((String) key, entry));
        return java.util.Collections.unmodifiableMap(value);
    }

    /**
     * Unknown members are refused, absent ones are not. {@code StorageValues.exactKeys} demands an
     * exact set because every field of a storage profile is required; here most members are optional
     * with a documented default, so demanding the whole set would make the defaults unreachable.
     * The half that matters — a mistyped member never being silently ignored — is the same.
     */
    private static void exactlyKnownKeys(String variable, Map<String, Object> value, Set<String> known,
                                         String field) {
        for (String key : new TreeMap<>(value).keySet()) {
            if (!known.contains(key)) {
                throw new NodePackageServiceGrantException(variable,
                        "node package service grant declares an unknown member '" + key + "' in " + field);
            }
        }
    }

    private static Map<String, NodePackageCapability> capabilitiesByName() {
        Map<String, NodePackageCapability> byName = new LinkedHashMap<>();
        for (NodePackageCapability capability : NodePackageCapability.values()) {
            // Keyed by the exact capabilityName() and matched exactly: the names an operator writes
            // are the stable inventory tokens, and accepting case variants of them would make the
            // grant text and the audit dimension two different vocabularies.
            byName.put(capability.capabilityName(), capability);
        }
        return Map.copyOf(byName);
    }
}
