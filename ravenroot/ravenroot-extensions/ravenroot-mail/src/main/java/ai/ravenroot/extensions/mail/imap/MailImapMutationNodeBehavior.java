package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/** Bounded one-shot IMAP move and delete actions addressed only by folder UID identity. */
public final class MailImapMutationNodeBehavior implements NodeBehavior {
    public enum Kind {
        MOVE("mail.imap.move", "Move message"),
        DELETE("mail.imap.delete", "Delete message");

        private final String behavior;
        private final String displayName;

        Kind(String behavior, String displayName) {
            this.behavior = behavior;
            this.displayName = displayName;
        }

        String behavior() { return behavior; }
        String displayName() { return displayName; }
    }

    public static final String MOVE_BEHAVIOR = "mail.imap.move";
    public static final String DELETE_BEHAVIOR = "mail.imap.delete";
    static final String HARD_DELETE_ACKNOWLEDGEMENT = "I_UNDERSTAND_EXPUNGE_IS_PERMANENT";

    private static final int GLOBAL_CONCURRENCY = 32;
    private static final int TENANT_CONCURRENCY = 16;
    private static final int RESOLVER_GLOBAL_CONCURRENCY = 32;
    private static final int RESOLVER_TENANT_CONCURRENCY = 16;
    private static final int MAX_MUTATION_MS = 30_000;
    private static final Semaphore GLOBAL_SLOTS = new Semaphore(GLOBAL_CONCURRENCY, true);
    private static final Semaphore RESOLVER_GLOBAL_SLOTS =
            new Semaphore(RESOLVER_GLOBAL_CONCURRENCY, true);
    private static final ConcurrentHashMap<String, Gate> TENANT_SLOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Gate> PROFILE_SLOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Gate> RESOLVER_TENANT_SLOTS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Gate> RESOLVER_PROFILE_SLOTS =
            new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE_WATCHDOGS = new AtomicInteger();
    private static final AtomicInteger ACTIVE_RESOLVER_TASKS = new AtomicInteger();
    private static final Executor DEFAULT_EXECUTOR = task -> {
        Thread worker = Thread.ofVirtual().name("ravenroot-imap-mutation-", 0).unstarted(task);
        worker.setContextClassLoader(MailImapMutationNodeBehavior.class.getClassLoader());
        worker.start();
    };

    private final Kind kind;
    private final ImapProfileResolver profiles;
    private final ImapMutationPolicyResolver policies;
    private final CredentialResolver credentials;
    private final UnaryOperator<Properties> sessionProperties;
    private final Executor executor;
    private final ReservedNetworkPolicy destinationPolicy;

    public MailImapMutationNodeBehavior(Kind kind) {
        this(kind, new EnvironmentImapProfileResolver(), new EnvironmentImapMutationPolicyResolver(),
                new ai.ravenroot.extensions.mail.EnvironmentMailCredentialResolver());
    }

    public MailImapMutationNodeBehavior(Kind kind, ImapProfileResolver profiles,
                                        ImapMutationPolicyResolver policies,
                                        CredentialResolver credentials) {
        this(kind, profiles, policies, credentials, UnaryOperator.identity(), DEFAULT_EXECUTOR);
    }

    MailImapMutationNodeBehavior(Kind kind, ImapProfileResolver profiles,
                                 ImapMutationPolicyResolver policies,
                                 CredentialResolver credentials,
                                 UnaryOperator<Properties> sessionProperties) {
        this(kind, profiles, policies, credentials, sessionProperties, DEFAULT_EXECUTOR);
    }

    MailImapMutationNodeBehavior(Kind kind, ImapProfileResolver profiles,
                                 ImapMutationPolicyResolver policies,
                                 CredentialResolver credentials,
                                 UnaryOperator<Properties> sessionProperties,
                                 Executor executor) {
        this(kind, profiles, policies, credentials, sessionProperties, executor,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }
    MailImapMutationNodeBehavior(Kind kind, ImapProfileResolver profiles,
                                 ImapMutationPolicyResolver policies,
                                 CredentialResolver credentials,
                                 UnaryOperator<Properties> sessionProperties,
                                 Executor executor, ReservedNetworkPolicy destinationPolicy) {
        this.kind = Objects.requireNonNull(kind);
        this.profiles = Objects.requireNonNull(profiles);
        this.policies = Objects.requireNonNull(policies);
        this.credentials = Objects.requireNonNull(credentials);
        this.sessionProperties = Objects.requireNonNull(sessionProperties);
        this.executor = Objects.requireNonNull(executor);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override public NodeTypeDescriptor descriptor() {
        List<NodePropertyDescriptor> properties = kind == Kind.MOVE
                ? moveProperties() : deleteProperties();
        return new NodeTypeDescriptor(kind.behavior(), kind.displayName(), "Mail",
                kind == Kind.MOVE
                        ? "Moves one IMAP message by source folder, UIDVALIDITY and UID."
                        : "Moves one IMAP message to trash or explicitly expunges exactly one UID.",
                "actor", false, properties,
                Set.of("network", "credential-reference", "side-effect"));
    }

    private static List<NodePropertyDescriptor> moveProperties() {
        return List.of(
                NodePropertyDescriptor.required("profile", "Mail profile", NodePropertyType.STRING,
                        "Opaque tenant-scoped operator profile; connection and policy stay outside GraphML."),
                NodePropertyDescriptor.optional("sourceFolder", "Source folder", NodePropertyType.STRING,
                        "Fallback source folder when the versioned input does not carry one.", "INBOX"),
                NodePropertyDescriptor.required("destinationFolder", "Destination folder",
                        NodePropertyType.STRING, "Requested destination; the operator policy must allow it."),
                NodePropertyDescriptor.optional("maxConcurrency", "Concurrency limit",
                        NodePropertyType.INTEGER,
                        "Optional 1–16 action limit; blank uses the profile ceiling.", ""),
                RecoveryRepeatabilityProperty.declaration(
                        "Move is effect-idempotent by immutable source UID: a repeat cannot move a second "
                                + "message. Declare repeatable only when a missing-on-repeat outcome is acceptable."));
    }

    private static List<NodePropertyDescriptor> deleteProperties() {
        PropertyCondition hardDelete = PropertyCondition.equalTo("deleteMode", "HARD_DELETE");
        return List.of(
                NodePropertyDescriptor.required("profile", "Mail profile", NodePropertyType.STRING,
                        "Opaque tenant-scoped operator profile; connection and policy stay outside GraphML."),
                NodePropertyDescriptor.optional("sourceFolder", "Source folder", NodePropertyType.STRING,
                        "Fallback source folder when the versioned input does not carry one.", "INBOX"),
                new NodePropertyDescriptor("deleteMode", "Delete mode", NodePropertyType.STRING,
                        false, "TRASH moves to the operator trash folder; HARD_DELETE expunges exactly one UID.",
                        "TRASH", List.of("TRASH", "HARD_DELETE"), false, null, null),
                new NodePropertyDescriptor("hardDeleteAcknowledgement", "Permanent-delete acknowledgement",
                        NodePropertyType.STRING, false,
                        "Required for HARD_DELETE; payload authorization is required separately at runtime.",
                        "", List.of(HARD_DELETE_ACKNOWLEDGEMENT), false, hardDelete, hardDelete),
                NodePropertyDescriptor.optional("maxConcurrency", "Concurrency limit",
                        NodePropertyType.INTEGER,
                        "Optional 1–16 action limit; blank uses the profile ceiling.", ""),
                RecoveryRepeatabilityProperty.declaration(
                        "TRASH is effect-idempotent by immutable source UID. HARD_DELETE must be declared "
                                + "not-repeatable because a disconnect after expunge is ambiguous."));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        Settings settings = Settings.from(kind, configuration);
        ConcurrentHashMap<String, Gate> actionSlots = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Gate> resolverActionSlots = new ConcurrentHashMap<>();
        return message -> {
            try {
                Request request = Request.from(kind, message.payload(), settings);
                ImapProfile profile = resolveProfile(message.tenantId(), settings.profileId());
                Optional<ImapMutationPolicy> resolvedPolicy = resolvePolicy(
                        message.tenantId(), settings.profileId());
                PolicyDecision policy = authorize(kind, profile, resolvedPolicy.orElse(null), request);
                if (!policy.allowed()) return CompletableFuture.completedFuture(
                        result(kind, "refused", "REFUSED", request, null, policy.reason()));
                Request authorizedRequest = policy.request();

                int effectiveLimit = Math.min(settings.actionLimit(), profile.maxConcurrency());
                Admission admission = Admission.acquire(message.tenantId(), profile,
                        effectiveLimit, actionSlots);
                if (!admission.acquired()) return CompletableFuture.failedFuture(capacity());
                try {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            return mutate(message.tenantId(), profile, authorizedRequest, effectiveLimit,
                                    resolverActionSlots);
                        } finally {
                            admission.release();
                        }
                    }, executor);
                } catch (RuntimeException rejected) {
                    admission.release();
                    return CompletableFuture.failedFuture(capacity());
                } catch (Error rejected) {
                    admission.release();
                    return CompletableFuture.failedFuture(new ImapMutationException(
                            ImapMutationException.Code.TRANSPORT_FAILURE,
                            "IMAP mutation submission failed"));
                }
            } catch (ImapMutationException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    private ImapProfile resolveProfile(String tenant, String profileId) {
        if (!safeId(tenant) || !safeId(profileId)) throw profileUnavailable();
        Optional<ImapProfile> resolved;
        try { resolved = profiles.resolve(tenant, profileId); }
        catch (RuntimeException hostile) { throw profileUnavailable(); }
        ImapProfile profile = resolved.orElseThrow(MailImapMutationNodeBehavior::profileUnavailable);
        if (!tenant.equals(profile.tenant()) || !profileId.equals(profile.id()))
            throw profileUnavailable();
        try { destinationPolicy.requireAllowedLiteral(profile.host()); }
        catch (SecurityException refused) { throw profileUnavailable(); }
        return profile;
    }

    private Optional<ImapMutationPolicy> resolvePolicy(String tenant, String profileId) {
        try {
            Optional<ImapMutationPolicy> resolved = policies.resolve(tenant, profileId);
            return resolved == null ? Optional.empty() : resolved.filter(policy ->
                    tenant.equals(policy.tenant()) && profileId.equals(policy.profileId()));
        } catch (RuntimeException hostile) {
            return Optional.empty();
        }
    }

    private static PolicyDecision authorize(Kind kind, ImapProfile profile,
                                             ImapMutationPolicy policy, Request request) {
        if (!profile.folders().contains(request.sourceFolder()))
            return PolicyDecision.refused("SOURCE_FOLDER");
        if (policy == null) return PolicyDecision.refused("ACTION");
        if (!policy.allows(request.operation())) return PolicyDecision.refused("ACTION");
        if (kind == Kind.MOVE && !policy.allowsDestination(request.destinationFolder()))
            return PolicyDecision.refused("DESTINATION_FOLDER");
        if (request.operation() == ImapMutationOperation.TRASH
                && !policy.allowsDestination(policy.trashFolder()))
            return PolicyDecision.refused("DESTINATION_FOLDER");
        if (request.operation() == ImapMutationOperation.HARD_DELETE
                && !request.hardDeleteAuthorized())
            return PolicyDecision.refused("HARD_DELETE_PAYLOAD_AUTHORIZATION");
        return PolicyDecision.allowed(request.operation() == ImapMutationOperation.TRASH
                ? request.withDestination(policy.trashFolder()) : request);
    }

    private NodeResult mutate(String tenant, ImapProfile profile, Request request,
                              int actionLimit, ConcurrentHashMap<String, Gate> resolverActions) {
        int timeoutMs = Math.min(MAX_MUTATION_MS, profile.readTimeoutMs());
        DeadlineWatchdog watchdog = new DeadlineWatchdog(timeoutMs);
        SecretValue secret = null;
        char[] password = null;
        Store store = null;
        IMAPFolder source = null;
        Folder destination = null;
        boolean attempted = false;
        NodeResult completed = null;
        Throwable failure = null;
        boolean timedOutAtFailure = false;
        watchdog.start();
        try {
            secret = resolveCredential(tenant, profile, actionLimit, resolverActions, watchdog);
            try { password = secret.copy(); }
            catch (RuntimeException hostile) { throw credentialUnavailable(); }

            String protocol = profile.securityMode().equals("IMAPS") ? "imaps" : "imap";
            Properties configured = sessionProperties.apply(properties(profile, protocol));
            trackSockets(configured, protocol, watchdog);
            Session session = Session.getInstance(configured);
            store = session.getStore(protocol);
            watchdog.track(store);
            store.connect(profile.host(), profile.port(), profile.username(), new String(password));
            if (request.operation() != ImapMutationOperation.HARD_DELETE) {
                destination = store.getFolder(request.destinationFolder());
                if (!destination.exists()) throw transportFailure();
            }
            Folder rawSource = store.getFolder(request.sourceFolder());
            if (!(rawSource instanceof IMAPFolder imapSource))
                throw transportFailure();
            source = imapSource;
            watchdog.track(source);
            source.open(Folder.READ_WRITE);
            checkDeadline(watchdog);
            long actualValidity = source.getUIDValidity();
            if (actualValidity != request.uidValidity()) {
                completed = result(kind, "stale", "STALE_UIDVALIDITY", request, null, "UIDVALIDITY");
            } else {
                Message message = source.getMessageByUID(request.uid());
                if (message == null) {
                    completed = result(kind, "missing", "MISSING", request, null, "MESSAGE_ABSENT");
                } else if (request.operation() == ImapMutationOperation.HARD_DELETE) {
                    attempted = true;
                    message.setFlag(Flags.Flag.DELETED, true);
                    Message[] expunged = source.expunge(new Message[]{message});
                    checkDeadline(watchdog);
                    if (expunged.length != 1 || source.getMessageByUID(request.uid()) != null)
                        completed = result(kind, "ambiguous", "AMBIGUOUS", request, null,
                                "EXPUNGE_UNVERIFIED");
                    else completed = result(kind, "success", "HARD_DELETED", request, null, "VERIFIED");
                } else {
                    attempted = true;
                    AppendUID[] mapped = source.moveUIDMessages(new Message[]{message}, destination);
                    checkDeadline(watchdog);
                    DestinationIdentity identity = destinationIdentity(mapped);
                    source.close(false);
                    source.open(Folder.READ_WRITE);
                    checkDeadline(watchdog);
                    if (source.getUIDValidity() != request.uidValidity())
                        completed = result(kind, "ambiguous", "AMBIGUOUS", request, null,
                                "UIDVALIDITY_CHANGED_AFTER_COMMAND");
                    else if (source.getMessageByUID(request.uid()) != null)
                        completed = result(kind, "ambiguous", "AMBIGUOUS", request, null,
                                "MOVE_UNVERIFIED");
                    else completed = result(kind, "success",
                            request.operation() == ImapMutationOperation.TRASH ? "TRASHED" : "MOVED",
                            request, identity, "VERIFIED");
                }
            }
        } catch (Throwable caught) {
            failure = caught;
            timedOutAtFailure = watchdog.timedOut() || watchdog.remainingNanos() <= 0
                    || caught instanceof ImapMutationException mutation
                    && mutation.code() == ImapMutationException.Code.TIMEOUT;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (secret != null) try { secret.close(); } catch (RuntimeException ignored) { }
            try { if (source != null && source.isOpen()) source.close(false); }
            catch (Exception ignored) { }
            try { if (store != null && store.isConnected()) store.close(); }
            catch (Exception ignored) { }
        }
        boolean expired = watchdog.finishAndJoin();
        if (failure != null || expired) {
            if (attempted) return result(kind, "ambiguous", "AMBIGUOUS", request, null,
                    timedOutAtFailure || failure == null && expired
                            ? "TIMEOUT_AFTER_COMMAND" : "DISCONNECT_AFTER_COMMAND");
            if (expired || failure instanceof ImapMutationException mutation
                    && mutation.code() == ImapMutationException.Code.TIMEOUT) throw timeout();
            if (failure instanceof ImapMutationException mutation) throw mutation;
            throw transportFailure();
        }
        return completed;
    }

    private SecretValue resolveCredential(String tenant, ImapProfile profile, int actionLimit,
                                          ConcurrentHashMap<String, Gate> resolverActions,
                                          DeadlineWatchdog watchdog) {
        ResolverAdmission admission = ResolverAdmission.acquire(tenant, profile, actionLimit,
                resolverActions);
        if (!admission.acquired()) throw resolverCapacity();
        CredentialHandoff handoff = new CredentialHandoff();
        Thread resolver;
        try {
            resolver = Thread.ofVirtual().name("ravenroot-imap-mutation-credential-", 0)
                    .unstarted(() -> {
                        ACTIVE_RESOLVER_TASKS.incrementAndGet();
                        try {
                            CredentialOutcome outcome;
                            try {
                                Optional<SecretValue> value = credentials.resolve(profile.credentialRef());
                                outcome = value == null || value.isEmpty()
                                        ? CredentialOutcome.failure(credentialUnavailable())
                                        : CredentialOutcome.success(value.get());
                            } catch (Throwable hostile) {
                                outcome = CredentialOutcome.failure(credentialUnavailable());
                            }
                            handoff.publish(outcome);
                        } finally {
                            admission.release();
                            ACTIVE_RESOLVER_TASKS.decrementAndGet();
                        }
                    });
            handoff.submitted(resolver);
            resolver.start();
        } catch (Throwable submissionFailure) {
            admission.release();
            throw credentialUnavailable();
        }
        CredentialOutcome outcome = handoff.await(watchdog);
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.secret();
    }

    private static DestinationIdentity destinationIdentity(AppendUID[] mappings) {
        if (mappings == null || mappings.length != 1 || mappings[0] == null
                || mappings[0].uidvalidity <= 0 || mappings[0].uid <= 0) return null;
        return new DestinationIdentity(mappings[0].uidvalidity, mappings[0].uid);
    }

    private static NodeResult result(Kind kind, String outcome, String status, Request request,
                                     DestinationIdentity destination, String reason) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", kind.behavior() + ".v1");
        payload.put("status", status);
        payload.put("operation", request.operation().name());
        payload.put("source", Map.of("folder", request.sourceFolder(),
                "uidValidity", request.uidValidity(), "uid", request.uid()));
        if (destination != null) payload.put("destination", Map.of(
                "folder", request.destinationFolder(), "uidValidity", destination.uidValidity(),
                "uid", destination.uid()));
        else if (outcome.equals("success") && request.destinationFolder() != null
                && !request.destinationFolder().isEmpty())
            payload.put("destination", Map.of("folder", request.destinationFolder()));
        payload.put("reason", reason);
        payload.put("retry", retry(outcome, request.operation()));
        return new NodeResult(outcome, Map.copyOf(payload), Map.of());
    }

    private static String retry(String outcome, ImapMutationOperation operation) {
        if (outcome.equals("ambiguous") || operation == ImapMutationOperation.HARD_DELETE)
            return "DO_NOT_RETRY_AUTOMATICALLY";
        return switch (outcome) {
            case "refused" -> "RETRY_ONLY_AFTER_POLICY_OR_AUTHORIZATION_CHANGE";
            case "stale" -> "RETRY_ONLY_WITH_FRESH_CHECKPOINT";
            case "missing" -> "NO_EFFECT_ALREADY_ABSENT";
            default -> "EFFECT_IDEMPOTENT";
        };
    }

    private static Properties properties(ImapProfile profile, String protocol) {
        String prefix = "mail." + protocol;
        int timeoutMs = Math.min(MAX_MUTATION_MS, profile.readTimeoutMs());
        Properties properties = new Properties();
        properties.setProperty(prefix + ".ssl.checkserveridentity", "true");
        properties.setProperty(prefix + ".connectiontimeout",
                Integer.toString(Math.min(timeoutMs, profile.connectTimeoutMs())));
        properties.setProperty(prefix + ".timeout", Integer.toString(timeoutMs));
        properties.setProperty(prefix + ".writetimeout", Integer.toString(timeoutMs));
        if (protocol.equals("imap")) {
            properties.setProperty("mail.imap.starttls.enable", "true");
            properties.setProperty("mail.imap.starttls.required", "true");
        }
        return properties;
    }

    private static void trackSockets(Properties properties, String protocol,
                                     DeadlineWatchdog watchdog) {
        String prefix = "mail." + protocol;
        Object sslValue = properties.get(prefix + ".ssl.socketFactory");
        SSLSocketFactory ssl = sslValue instanceof SSLSocketFactory factory
                ? factory : (SSLSocketFactory) SSLSocketFactory.getDefault();
        properties.put(prefix + ".ssl.socketFactory", new TrackingSslSocketFactory(ssl, watchdog));
        if (protocol.equals("imap")) {
            Object plainValue = properties.get(prefix + ".socketFactory");
            SocketFactory plain = plainValue instanceof SocketFactory factory
                    ? factory : SocketFactory.getDefault();
            properties.put(prefix + ".socketFactory", new TrackingSocketFactory(plain, watchdog));
        }
    }

    private static void checkDeadline(DeadlineWatchdog watchdog) {
        if (watchdog.timedOut() || watchdog.remainingNanos() <= 0) throw timeout();
    }

    private static boolean safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static ImapMutationException profileUnavailable() {
        return new ImapMutationException(ImapMutationException.Code.PROFILE_UNAVAILABLE,
                "IMAP profile unavailable");
    }

    private static ImapMutationException credentialUnavailable() {
        return new ImapMutationException(ImapMutationException.Code.CREDENTIAL_UNAVAILABLE,
                "IMAP credential unavailable");
    }

    private static ImapMutationException capacity() {
        return new ImapMutationException(ImapMutationException.Code.SATURATED,
                "IMAP mutation capacity is unavailable");
    }

    private static ImapMutationException resolverCapacity() {
        return new ImapMutationException(ImapMutationException.Code.SATURATED,
                "IMAP credential capacity is unavailable");
    }

    private static ImapMutationException timeout() {
        return new ImapMutationException(ImapMutationException.Code.TIMEOUT,
                "IMAP mutation timed out");
    }

    private static ImapMutationException transportFailure() {
        return new ImapMutationException(ImapMutationException.Code.TRANSPORT_FAILURE,
                "IMAP mutation failed");
    }

    private record Settings(String profileId, String sourceFolder, String destinationFolder,
                            ImapMutationOperation operation, int actionLimit) {
        static Settings from(Kind kind, NodeConfiguration configuration) {
            String profile = safeToken(configuration.requiredProperty("profile"),
                    "Invalid IMAP profile");
            String source = folder(configuration.property("sourceFolder", "INBOX"),
                    "Invalid source folder");
            int limit = optionalPositive(configuration.property("maxConcurrency", ""));
            if (kind == Kind.MOVE) {
                String destination = folder(configuration.requiredProperty("destinationFolder"),
                        "Invalid destination folder");
                return new Settings(profile, source, destination, ImapMutationOperation.MOVE, limit);
            }
            String rawMode = configuration.property("deleteMode", "TRASH");
            ImapMutationOperation operation;
            try { operation = ImapMutationOperation.valueOf(rawMode); }
            catch (RuntimeException invalid) {
                throw invalid("Invalid delete mode");
            }
            if (operation != ImapMutationOperation.TRASH
                    && operation != ImapMutationOperation.HARD_DELETE)
                throw invalid("Invalid delete mode");
            String acknowledgement = configuration.property("hardDeleteAcknowledgement", "");
            if (operation == ImapMutationOperation.HARD_DELETE
                    && !HARD_DELETE_ACKNOWLEDGEMENT.equals(acknowledgement))
                throw invalid("Hard delete acknowledgement is required");
            if (operation == ImapMutationOperation.HARD_DELETE
                    && RecoveryRepeatabilityProperty.REPEATABLE.equals(
                            configuration.property(RecoveryRepeatabilityProperty.NAME, "")))
                throw invalid("Hard delete cannot be declared repeatable");
            return new Settings(profile, source, "", operation, limit);
        }
    }

    private record Request(String sourceFolder, long uidValidity, long uid,
                           String destinationFolder, ImapMutationOperation operation,
                           boolean hardDeleteAuthorized) {
        static Request from(Kind kind, Object payload, Settings settings) {
            if (!(payload instanceof Map<?, ?> map) || map.size() > 64)
                throw invalid("Invalid IMAP mutation payload");
            BoundedEnvelope.validate(map);
            Object version = map.get("version");
            Identity identity;
            String destination = settings.destinationFolder();
            boolean hardDeleteAuthorized = false;
            if ((kind.behavior() + ".v1").equals(version)) {
                Set<String> allowed = kind == Kind.MOVE
                        ? Set.of("version", "sourceFolder", "uidValidity", "uid", "destinationFolder")
                        : Set.of("version", "sourceFolder", "uidValidity", "uid",
                                "authorizeHardDelete");
                if (!allowed.containsAll(map.keySet()))
                    throw invalid("Invalid IMAP mutation payload");
                identity = flatIdentity(map, settings.sourceFolder(), false);
                if (kind == Kind.MOVE && map.containsKey("destinationFolder"))
                    destination = folder(map.get("destinationFolder"), "Invalid destination folder");
                if (kind == Kind.DELETE && map.containsKey("authorizeHardDelete")) {
                    if (!(map.get("authorizeHardDelete") instanceof Boolean allowedDelete))
                        throw invalid("Invalid hard delete authorization");
                    hardDeleteAuthorized = allowedDelete;
                }
            } else if ("mail.imap.message.v1".equals(version)) {
                identity = flatIdentity(map, settings.sourceFolder(), true);
            } else if ("mail.imap.query.v1".equals(version)) {
                identity = queryIdentity(map);
            } else {
                throw invalid("Invalid IMAP mutation payload");
            }
            return new Request(identity.sourceFolder(), identity.uidValidity(), identity.uid(),
                    destination, settings.operation(), hardDeleteAuthorized);
        }

        Request withDestination(String value) {
            return new Request(sourceFolder, uidValidity, uid, value, operation,
                    hardDeleteAuthorized);
        }

        private static Identity flatIdentity(Map<?, ?> map, String defaultSource,
                                             boolean sourceRequired) {
            if (sourceRequired && !map.containsKey("sourceFolder"))
                throw invalid("Invalid source folder");
            String source = map.containsKey("sourceFolder")
                    ? folder(map.get("sourceFolder"), "Invalid source folder") : defaultSource;
            long validity = positiveLong(map.get("uidValidity"), "Invalid UIDVALIDITY");
            long uid = positiveLong(map.get("uid"), "Invalid UID");
            return new Identity(source, validity, uid);
        }

        private static Identity queryIdentity(Map<?, ?> map) {
            String source = folder(map.get("folder"), "Invalid source folder");
            if (!(map.get("mailbox") instanceof Map<?, ?> mailbox))
                throw invalid("Invalid UIDVALIDITY");
            long validity = positiveLong(mailbox.get("uidValidity"), "Invalid UIDVALIDITY");
            if (!(map.get("messages") instanceof List<?> messages) || messages.size() != 1
                    || !(messages.getFirst() instanceof Map<?, ?> message))
                throw invalid("IMAP query result must contain exactly one message");
            long uid = positiveLong(message.get("uid"), "Invalid UID");
            return new Identity(source, validity, uid);
        }
    }

    private static String safeToken(Object value, String message) {
        if (value instanceof String text && safeId(text)) return text;
        throw invalid(message);
    }

    private static String folder(Object value, String message) {
        if (value instanceof String text && !text.isBlank() && text.length() <= 256
                && text.indexOf('\r') < 0 && text.indexOf('\n') < 0 && text.indexOf('\0') < 0)
            return text;
        throw invalid(message);
    }

    private static int optionalPositive(String value) {
        if (value == null || value.isBlank()) return 16;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 1 && parsed <= 16) return parsed;
        } catch (RuntimeException ignored) { }
        throw invalid("Invalid concurrency limit");
    }

    private static long positiveLong(Object value, String message) {
        if (!(value instanceof Number number)) throw invalid(message);
        try {
            long parsed;
            if (number instanceof Byte || number instanceof Short || number instanceof Integer
                    || number instanceof Long) parsed = number.longValue();
            else if (number instanceof BigInteger integer) parsed = integer.longValueExact();
            else if (number instanceof BigDecimal decimal) parsed = decimal.longValueExact();
            else if (number instanceof Float || number instanceof Double) {
                double decimal = number.doubleValue();
                if (!Double.isFinite(decimal) || decimal != Math.rint(decimal))
                    throw new ArithmeticException();
                parsed = BigDecimal.valueOf(decimal).longValueExact();
            } else parsed = new BigDecimal(number.toString()).longValueExact();
            if (parsed <= 0) throw new ArithmeticException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid(message);
        }
    }

    private static ImapMutationException invalid(String message) {
        return new ImapMutationException(ImapMutationException.Code.INVALID_INPUT, message);
    }

    private record Identity(String sourceFolder, long uidValidity, long uid) { }
    private record DestinationIdentity(long uidValidity, long uid) { }
    private record PolicyDecision(boolean allowed, String reason, Request request) {
        static PolicyDecision allowed(Request request) { return new PolicyDecision(true, "", request); }
        static PolicyDecision refused(String reason) { return new PolicyDecision(false, reason, null); }
    }

    private record CredentialOutcome(SecretValue secret, ImapMutationException failure) {
        static CredentialOutcome success(SecretValue value) {
            return new CredentialOutcome(Objects.requireNonNull(value), null);
        }
        static CredentialOutcome failure(ImapMutationException failure) {
            return new CredentialOutcome(null, Objects.requireNonNull(failure));
        }
    }

    /** Rejects oversized, deeply nested, cyclic, or exotic inbound envelopes before parsing. */
    private static final class BoundedEnvelope {
        private static final int MAX_DEPTH = 6;
        private static final int MAX_COLLECTION = 128;
        private static final int MAX_VALUES = 512;
        private static final int MAX_UTF8_BYTES = 65_536;
        private final Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        private int values;
        private int utf8Bytes;

        static void validate(Object value) {
            new BoundedEnvelope().visit(value, 0);
        }

        private void visit(Object value, int depth) {
            if (++values > MAX_VALUES || depth > MAX_DEPTH)
                throw invalid("IMAP mutation payload exceeds bounds");
            if (value == null || value instanceof Number || value instanceof Boolean) return;
            if (value instanceof String text) {
                addUtf8(text);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                if (map.size() > MAX_COLLECTION || !visiting.add(value))
                    throw invalid("IMAP mutation payload exceeds bounds");
                try {
                    map.forEach((key, nested) -> {
                        if (!(key instanceof String text))
                            throw invalid("Invalid IMAP mutation payload");
                        addUtf8(text);
                        visit(nested, depth + 1);
                    });
                } finally { visiting.remove(value); }
                return;
            }
            if (value instanceof List<?> list) {
                if (list.size() > MAX_COLLECTION || !visiting.add(value))
                    throw invalid("IMAP mutation payload exceeds bounds");
                try { list.forEach(nested -> visit(nested, depth + 1)); }
                finally { visiting.remove(value); }
                return;
            }
            throw invalid("Invalid IMAP mutation payload");
        }

        private void addUtf8(String text) {
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                utf8Bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
                        : codePoint <= 0xffff ? 3 : 4;
                if (utf8Bytes > MAX_UTF8_BYTES)
                    throw invalid("IMAP mutation payload exceeds bounds");
            }
        }
    }

    private static final class CredentialHandoff {
        private Thread resolver;
        private CredentialOutcome outcome;
        private boolean abandoned;

        synchronized void submitted(Thread value) {
            resolver = value;
            if (abandoned) value.interrupt();
        }

        void publish(CredentialOutcome value) {
            SecretValue late = null;
            synchronized (this) {
                if (abandoned) late = value.secret();
                else { outcome = value; notifyAll(); }
            }
            close(late);
        }

        CredentialOutcome await(DeadlineWatchdog watchdog) {
            Thread interrupt = null;
            SecretValue late = null;
            boolean interrupted = false;
            CredentialOutcome available = null;
            synchronized (this) {
                while (true) {
                    long remaining = watchdog.remainingNanos();
                    if (remaining <= 0 || watchdog.timedOut()) {
                        abandoned = true;
                        interrupt = resolver;
                        if (outcome != null) late = outcome.secret();
                        outcome = null;
                        break;
                    }
                    if (outcome != null) {
                        available = outcome;
                        outcome = null;
                        break;
                    }
                    long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining);
                    int nanos = (int) (remaining
                            - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
                    try { wait(millis, nanos); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupt != null) interrupt.interrupt();
            close(late);
            if (interrupted) Thread.currentThread().interrupt();
            if (available == null) throw timeout();
            return available;
        }

        private static void close(SecretValue value) {
            if (value != null) try { value.close(); } catch (RuntimeException ignored) { }
        }
    }

    private static final class DeadlineWatchdog {
        private static final int ACTIVE = 0;
        private static final int FINISHED = 1;
        private static final int TIMED_OUT = 2;
        private final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final AtomicReference<Folder> folder = new AtomicReference<>();
        private final AtomicReference<Store> store = new AtomicReference<>();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Thread owner = Thread.currentThread();
        private final long deadline;
        private final Thread thread;

        DeadlineWatchdog(int timeoutMs) {
            deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            thread = Thread.ofVirtual().name("ravenroot-imap-mutation-watchdog-", 0)
                    .unstarted(this::watch);
        }

        void start() {
            ACTIVE_WATCHDOGS.incrementAndGet();
            try { thread.start(); }
            catch (Throwable failure) {
                ACTIVE_WATCHDOGS.decrementAndGet();
                state.set(FINISHED);
                throw failure;
            }
        }

        void track(Store value) { store.set(value); if (timedOut()) close(value); }
        void track(Folder value) { folder.set(value); if (timedOut()) close(value); }
        Socket track(Socket value) { sockets.add(value); if (timedOut()) close(value); return value; }
        boolean timedOut() { return state.get() == TIMED_OUT; }
        long remainingNanos() { return deadline - System.nanoTime(); }

        boolean finishAndJoin() {
            finishState();
            thread.interrupt();
            boolean interrupted = false;
            while (thread.isAlive()) {
                try { thread.join(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return timedOut();
        }

        private void watch() {
            try {
                while (state.get() == ACTIVE) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) LockSupport.parkNanos(this, remaining);
                    Thread.interrupted();
                    if (expireIfDue()) break;
                }
                if (timedOut()) {
                    sockets.forEach(DeadlineWatchdog::close);
                    owner.interrupt();
                    close(folder.get());
                    close(store.get());
                }
            } finally {
                ACTIVE_WATCHDOGS.decrementAndGet();
            }
        }

        private synchronized boolean expireIfDue() {
            if (state.get() == ACTIVE && System.nanoTime() - deadline >= 0) state.set(TIMED_OUT);
            return state.get() != ACTIVE;
        }

        private synchronized void finishState() {
            if (state.get() == ACTIVE)
                state.set(System.nanoTime() - deadline >= 0 ? TIMED_OUT : FINISHED);
        }

        private static void close(Socket socket) {
            if (socket == null) return;
            try { socket.shutdownInput(); } catch (Exception ignored) { }
            try { socket.shutdownOutput(); } catch (Exception ignored) { }
            try { socket.close(); } catch (Exception ignored) { }
        }

        private static void close(Folder folder) {
            if (folder == null) return;
            try {
                if (folder instanceof IMAPFolder imap) imap.forceClose();
                else if (folder.isOpen()) folder.close(false);
            } catch (Exception ignored) { }
        }

        private static void close(Store store) {
            if (store != null) try { if (store.isConnected()) store.close(); }
            catch (Exception ignored) { }
        }
    }

    private static final class TrackingSocketFactory extends SocketFactory {
        private final SocketFactory delegate;
        private final DeadlineWatchdog watchdog;
        TrackingSocketFactory(SocketFactory delegate, DeadlineWatchdog watchdog) {
            this.delegate = delegate; this.watchdog = watchdog;
        }
        @Override public Socket createSocket() throws java.io.IOException {
            return watchdog.track(delegate.createSocket());
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local,
                                             int localPort) throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port, local, localPort));
        }
    }

    private static final class TrackingSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final DeadlineWatchdog watchdog;
        TrackingSslSocketFactory(SSLSocketFactory delegate, DeadlineWatchdog watchdog) {
            this.delegate = delegate; this.watchdog = watchdog;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws java.io.IOException {
            return watchdog.track(delegate.createSocket());
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local,
                                             int localPort) throws java.io.IOException {
            return watchdog.track(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close)
                throws java.io.IOException {
            return watchdog.track(delegate.createSocket(socket, host, port, close));
        }
    }

    private static GateLease lease(ConcurrentHashMap<String, Gate> gates, String key, int permits) {
        Gate gate = gates.compute(key,
                (ignored, current) -> current == null ? new Gate(permits) : current.retain());
        return new GateLease(gates, key, gate);
    }

    private static final class Admission {
        private final GateLease tenant;
        private final GateLease profile;
        private final GateLease action;
        private boolean globalPermit;
        private boolean tenantPermit;
        private boolean profilePermit;
        private boolean actionPermit;
        private boolean released;

        private Admission(GateLease tenant, GateLease profile, GateLease action) {
            this.tenant = tenant; this.profile = profile; this.action = action;
        }

        static Admission acquire(String tenantId, ImapProfile profile, int actionLimit,
                                 ConcurrentHashMap<String, Gate> actions) {
            Admission admission = new Admission(
                    lease(TENANT_SLOTS, tenantId, TENANT_CONCURRENCY),
                    lease(PROFILE_SLOTS, tenantId + "\0" + profile.id(), profile.maxConcurrency()),
                    lease(actions, tenantId, actionLimit));
            if (!(admission.globalPermit = GLOBAL_SLOTS.tryAcquire())
                    || !(admission.tenantPermit = admission.tenant.gate.slots.tryAcquire())
                    || !(admission.profilePermit = admission.profile.gate.slots.tryAcquire())
                    || !(admission.actionPermit = admission.action.gate.slots.tryAcquire()))
                admission.release();
            return admission;
        }

        boolean acquired() {
            return globalPermit && tenantPermit && profilePermit && actionPermit;
        }

        synchronized void release() {
            if (released) return;
            released = true;
            if (actionPermit) action.gate.slots.release();
            if (profilePermit) profile.gate.slots.release();
            if (tenantPermit) tenant.gate.slots.release();
            if (globalPermit) GLOBAL_SLOTS.release();
            action.close(); profile.close(); tenant.close();
        }
    }

    private static final class ResolverAdmission {
        private final GateLease tenant;
        private final GateLease profile;
        private final GateLease action;
        private boolean globalPermit;
        private boolean tenantPermit;
        private boolean profilePermit;
        private boolean actionPermit;
        private boolean released;

        private ResolverAdmission(GateLease tenant, GateLease profile, GateLease action) {
            this.tenant = tenant; this.profile = profile; this.action = action;
        }

        static ResolverAdmission acquire(String tenantId, ImapProfile profile, int actionLimit,
                                         ConcurrentHashMap<String, Gate> actions) {
            ResolverAdmission admission = new ResolverAdmission(
                    lease(RESOLVER_TENANT_SLOTS, tenantId, RESOLVER_TENANT_CONCURRENCY),
                    lease(RESOLVER_PROFILE_SLOTS, tenantId + "\0" + profile.id(),
                            profile.maxConcurrency()),
                    lease(actions, tenantId, actionLimit));
            if (!(admission.globalPermit = RESOLVER_GLOBAL_SLOTS.tryAcquire())
                    || !(admission.tenantPermit = admission.tenant.gate.slots.tryAcquire())
                    || !(admission.profilePermit = admission.profile.gate.slots.tryAcquire())
                    || !(admission.actionPermit = admission.action.gate.slots.tryAcquire()))
                admission.release();
            return admission;
        }

        boolean acquired() {
            return globalPermit && tenantPermit && profilePermit && actionPermit;
        }

        synchronized void release() {
            if (released) return;
            released = true;
            if (actionPermit) action.gate.slots.release();
            if (profilePermit) profile.gate.slots.release();
            if (tenantPermit) tenant.gate.slots.release();
            if (globalPermit) RESOLVER_GLOBAL_SLOTS.release();
            action.close(); profile.close(); tenant.close();
        }
    }

    private static final class GateLease {
        private final ConcurrentHashMap<String, Gate> gates;
        private final String key;
        private final Gate gate;
        private boolean closed;
        GateLease(ConcurrentHashMap<String, Gate> gates, String key, Gate gate) {
            this.gates = gates; this.key = key; this.gate = gate;
        }
        synchronized void close() {
            if (closed) return;
            closed = true;
            gates.computeIfPresent(key, (ignored, current) -> current == gate
                    && current.references.decrementAndGet() == 0 ? null : current);
        }
    }

    private static final class Gate {
        private final Semaphore slots;
        private final AtomicInteger references = new AtomicInteger(1);
        Gate(int permits) { slots = new Semaphore(permits, true); }
        Gate retain() { references.incrementAndGet(); return this; }
    }
}
