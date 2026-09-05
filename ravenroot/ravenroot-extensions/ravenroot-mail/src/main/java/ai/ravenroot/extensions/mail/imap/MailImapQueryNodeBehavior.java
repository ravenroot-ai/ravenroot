package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SentDateTerm;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
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
import java.util.function.LongSupplier;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/** A bounded, one-shot, read-only IMAP query. It deliberately has no polling or background lifecycle. */
public final class MailImapQueryNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "mail.imap.query";
    private static final int GLOBAL_CONCURRENCY = 32;
    private static final int TENANT_CONCURRENCY = 16;
    private static final int UID_WINDOW = 128;
    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final int MAX_MIME_DEPTH = 8;
    private static final int MAX_MIME_PARTS = 64;
    private static final int MAX_ATTACHMENTS = 20;
    private static final int MAX_ADDRESSES = 50;
    private static final int MAX_SCANNED_MESSAGES = 4_096;
    private static final int MAX_UID_WINDOWS = 4_096;
    private static final int MAX_QUERY_MS = 30_000;
    static final String PREVIEW_MODE = "preview";
    static final String FULL_MODE = "full";
    private static final int RESOLVER_GLOBAL_CONCURRENCY = 32;
    private static final int RESOLVER_TENANT_CONCURRENCY = 16;
    private static final Semaphore GLOBAL_SLOTS = new Semaphore(GLOBAL_CONCURRENCY, true);
    private static final ConcurrentHashMap<String, Gate> TENANT_SLOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Gate> PROFILE_SLOTS = new ConcurrentHashMap<>();
    private static final Semaphore RESOLVER_GLOBAL_SLOTS = new Semaphore(RESOLVER_GLOBAL_CONCURRENCY, true);
    private static final ConcurrentHashMap<String, Gate> RESOLVER_TENANT_SLOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Gate> RESOLVER_PROFILE_SLOTS = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE_WATCHDOGS = new AtomicInteger();
    private static final AtomicInteger ACTIVE_RESOLVER_TASKS = new AtomicInteger();
    private static final Executor DEFAULT_IMAP_EXECUTOR = task -> {
        Thread worker = Thread.ofVirtual().name("ravenroot-imap-", 0).unstarted(task);
        worker.setContextClassLoader(MailImapQueryNodeBehavior.class.getClassLoader());
        worker.start();
    };

    private final ImapProfileResolver profiles;
    private final CredentialResolver credentials;
    private final UnaryOperator<Properties> sessionProperties;
    private final Executor executor;
    private final LongSupplier nanoTime;
    private final ReservedNetworkPolicy destinationPolicy;

    public MailImapQueryNodeBehavior() { this(new EnvironmentImapProfileResolver(), new ai.ravenroot.extensions.mail.EnvironmentMailCredentialResolver()); }
    public MailImapQueryNodeBehavior(ImapProfileResolver profiles, CredentialResolver credentials) { this(profiles, credentials, UnaryOperator.identity()); }
    /* Package-visible test seam: each invocation receives a fresh, per-session property set. */
    MailImapQueryNodeBehavior(ImapProfileResolver profiles, CredentialResolver credentials, UnaryOperator<Properties> sessionProperties) { this(profiles, credentials, sessionProperties, DEFAULT_IMAP_EXECUTOR); }
    MailImapQueryNodeBehavior(ImapProfileResolver profiles, CredentialResolver credentials, UnaryOperator<Properties> sessionProperties, Executor executor) { this(profiles, credentials, sessionProperties, executor, System::nanoTime); }
    MailImapQueryNodeBehavior(ImapProfileResolver profiles, CredentialResolver credentials, UnaryOperator<Properties> sessionProperties, Executor executor, LongSupplier nanoTime) {
        this(profiles, credentials, sessionProperties, executor, nanoTime,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }
    MailImapQueryNodeBehavior(ImapProfileResolver profiles, CredentialResolver credentials,
                              UnaryOperator<Properties> sessionProperties, Executor executor,
                              LongSupplier nanoTime, ReservedNetworkPolicy destinationPolicy) {
        this.profiles = Objects.requireNonNull(profiles); this.credentials = Objects.requireNonNull(credentials); this.sessionProperties = Objects.requireNonNull(sessionProperties); this.executor = Objects.requireNonNull(executor); this.nanoTime = Objects.requireNonNull(nanoTime);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Query mailbox", "Mail", "One-shot read-only IMAP query.", "actor", false, List.of(
                NodePropertyDescriptor.required("profile", "Mail profile", NodePropertyType.STRING, "Opaque tenant-scoped operator profile."),
                NodePropertyDescriptor.optional("folder", "Folder", NodePropertyType.STRING, "Policy-approved default folder.", "INBOX"),
                NodePropertyDescriptor.optional("limit", "Limit", NodePropertyType.INTEGER, "Tightening-only page limit.", "50"),
                // This property is the *default* used when a payload omits `contentMode`, and
                // nothing else: the payload field is authoritative per invocation. It exists as a node
                // property, and not only as a payload field, because a payload field is neither
                // inspectable in the editor nor carried by GraphML, and both were required. Its two
                // values are deliberately `preview`/`full` and not `metadata`/`preview`: the sibling
                // `mail.imap.consume` uses the latter pair for a different question (whether a body is
                // read at all), and harmonising the two vocabularies belongs outside this node.
                new NodePropertyDescriptor("contentMode", "Content mode", NodePropertyType.STRING, false,
                        "Default body mode when the payload omits contentMode: preview truncates to the operator preview budget, full returns the whole decoded body.",
                        PREVIEW_MODE, List.of(PREVIEW_MODE, FULL_MODE)),
                NodePropertyDescriptor.optional("maxConcurrency", "Concurrency limit", NodePropertyType.INTEGER, "Optional 1–16 action limit; blank uses the profile ceiling.", ""),
                // PERS-04 (ADR 0022). This is the one mail node where 'repeatable' is honestly
                // reachable, and it is reachable unconditionally rather than in some states: the
                // folder is opened READ_ONLY and the session sets mail.imap(s).peek=true below, so a
                // fetch does not set \Seen and the query leaves no trace at the server. That also
                // satisfies the harder half of what REPEATABLE promises -- safety under overlap, not
                // only safety in sequence -- because two peeked read-only queries cannot observe each
                // other. A late attempt here is reclassified as a deadline by
                // the watchdog, so the outcome genuinely is ambiguous; this node is where that
                // ambiguity costs nothing. No default, so an author who says nothing still parks.
                ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(
                        "Whether repeating this query after a crash of unknown outcome is safe. The "
                                + "query is read-only and peeked, so a repeat changes nothing at the "
                                + "server and observes no other attempt.")),
                Set.of("network", "credential-reference"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        String profileId = configuration.requiredProperty("profile");
        String defaultFolder = configuration.property("folder", "INBOX");
        int configuredLimit = positive(configuration.property("limit", "50"), "Invalid limit");
        // Refused here, at build time, exactly as an unusable `limit` is: a graph that names a
        // body mode this node cannot honour is a defective graph, not a request to fall back to
        // preview. A blank or absent value is a graph that said nothing, which is `preview`.
        String defaultContentMode = bodyMode(configuration.property("contentMode", PREVIEW_MODE), "Invalid content mode");
        int actionLimit = optionalPositive(configuration.property("maxConcurrency", ""), 16, "Invalid concurrency limit");
        ConcurrentHashMap<String, Gate> actionSlots = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Gate> resolverActionSlots = new ConcurrentHashMap<>();
        return message -> {
            try {
                Request request = Request.from(message.payload(), defaultFolder, configuredLimit, defaultContentMode);
                ImapProfile profile = resolveProfile(message.tenantId(), profileId);
                if (!profile.folders().contains(request.folder()))
                    throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "IMAP folder unavailable");
                int effectiveActionLimit = Math.min(actionLimit, profile.maxConcurrency());
                Admission admission = Admission.acquire(message.tenantId(), profile, effectiveActionLimit, actionSlots);
                if (!admission.acquired()) return CompletableFuture.failedFuture(capacity());
                Request boundedRequest = request.withLimit(Math.min(request.limit(), profile.maxResults()));
                try {
                    return CompletableFuture.supplyAsync(() -> {
                        try { return query(message.tenantId(), profile, boundedRequest, effectiveActionLimit, resolverActionSlots); }
                        finally { admission.release(); }
                    }, executor);
                } catch (RuntimeException rejected) {
                    admission.release(); return CompletableFuture.failedFuture(capacity());
                } catch (Error rejected) {
                    admission.release(); return CompletableFuture.failedFuture(new ImapQueryException(ImapQueryException.Code.TRANSPORT_FAILURE, "IMAP query submission failed"));
                }
            } catch (ImapQueryException failure) { return CompletableFuture.failedFuture(failure); }
        };
    }

    private ImapProfile resolveProfile(String tenant, String profileId) {
        if (!safeId(tenant) || !safeId(profileId)) throw new ImapQueryException(ImapQueryException.Code.PROFILE_UNAVAILABLE, "IMAP profile unavailable");
        Optional<ImapProfile> resolved;
        try { resolved = profiles.resolve(tenant, profileId); }
        catch (RuntimeException hostile) { throw new ImapQueryException(ImapQueryException.Code.PROFILE_UNAVAILABLE, "IMAP profile unavailable"); }
        ImapProfile profile = resolved.orElseThrow(() -> new ImapQueryException(ImapQueryException.Code.PROFILE_UNAVAILABLE, "IMAP profile unavailable"));
        if (!tenant.equals(profile.tenant()) || !profileId.equals(profile.id()))
            throw new ImapQueryException(ImapQueryException.Code.PROFILE_UNAVAILABLE, "IMAP profile unavailable");
        try { destinationPolicy.requireAllowedLiteral(profile.host()); }
        catch (SecurityException refused) { throw new ImapQueryException(ImapQueryException.Code.PROFILE_UNAVAILABLE, "IMAP profile unavailable"); }
        return profile;
    }

    private NodeResult query(String tenantId, ImapProfile profile, Request request, int actionLimit, ConcurrentHashMap<String, Gate> resolverActions) {
        int timeoutMs = Math.min(MAX_QUERY_MS, profile.readTimeoutMs());
        long deadline = nanoTime.getAsLong() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        DeadlineWatchdog watchdog = new DeadlineWatchdog(timeoutMs);
        SecretValue secret = null;
        char[] password = null;
        Store store = null; Folder folder = null;
        watchdog.start();
        try {
            secret = resolveCredential(tenantId, profile, actionLimit, resolverActions, watchdog);
            try { password = secret.copy(); }
            catch (RuntimeException hostile) { throw new ImapQueryException(ImapQueryException.Code.CREDENTIAL_UNAVAILABLE, "IMAP credential unavailable"); }
            String protocol = profile.securityMode().equals("IMAPS") ? "imaps" : "imap";
            Properties configured = sessionProperties.apply(properties(profile, protocol));
            trackSockets(configured, protocol, watchdog);
            Session session = Session.getInstance(configured);
            store = session.getStore(protocol);
            watchdog.track(store);
            // JavaMail requires this short-lived String. It is neither retained nor exposed in failures.
            store.connect(profile.host(), profile.port(), profile.username(), new String(password));
            folder = store.getFolder(request.folder());
            watchdog.track(folder);
            folder.open(Folder.READ_ONLY);
            if (!(folder instanceof UIDFolder uidFolder) || !(folder instanceof IMAPFolder imapFolder))
                throw new ImapQueryException(ImapQueryException.Code.TRANSPORT_FAILURE, "IMAP query failed");
            checkDeadline(deadline);
            long uidValidity = imapFolder.getUIDValidity();
            long after = request.cursor(uidValidity);
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean more = false;
            long nextUid = after == Long.MAX_VALUE ? 1 : Math.max(Math.max(1, after + 1), request.uidMin());
            checkDeadline(deadline);
            long upper = after == Long.MAX_VALUE ? 0 : Math.min(request.uidMax(), Math.max(0, imapFolder.getUIDNext() - 1));
            int scanLimit = Math.min(MAX_SCANNED_MESSAGES, Math.max(UID_WINDOW, Math.multiplyExact(profile.maxResults(), UID_WINDOW)));
            int scanned = 0, windows = 0;
            while (nextUid <= upper && !more) {
                checkDeadline(deadline);
                if (++windows > Math.min(MAX_UID_WINDOWS, scanLimit)) throw resourceLimit();
                long windowEnd = Math.min(upper, nextUid > Long.MAX_VALUE - (UID_WINDOW - 1L) ? Long.MAX_VALUE : nextUid + UID_WINDOW - 1L);
                Message[] candidates = uidFolder.getMessagesByUID(nextUid, windowEnd);
                scanned += candidates.length;
                if (scanned > scanLimit) throw resourceLimit();
                Message[] found = folder.search(request.term(), candidates);
                Map<Message, Long> uids = new IdentityHashMap<>();
                for (Message message : found) uids.put(message, uidFolder.getUID(message));
                Arrays.sort(found, Comparator.comparingLong(uids::get));
                for (Message message : found) {
                    checkDeadline(deadline);
                    long uid = uids.get(message);
                    if (uid < nextUid || uid > windowEnd) continue;
                    if (rows.size() == request.limit()) { more = true; break; }
                    rows.add(row(message, uid, profile.maxPreviewChars(), request.full()));
                }
                if (windowEnd == Long.MAX_VALUE) break;
                nextUid = windowEnd + 1;
            }
            long last = rows.isEmpty() ? after : ((Number) rows.getLast().get("uid")).longValue();
            checkDeadline(deadline);
            return NodeResult.continueWith(Map.of("version", "mail.imap.query.v1", "folder", request.folder(),
                    "mailbox", Map.of("uidValidity", uidValidity), "messages", List.copyOf(rows),
                    "cursor", Map.of("uidValidity", uidValidity, "lastUid", last), "hasMore", more));
        } catch (ImapQueryException failure) { if (watchdog.timedOut()) throw timeout(); throw failure; }
        catch (Throwable failure) { if (watchdog.timedOut()) throw timeout(); throw new ImapQueryException(ImapQueryException.Code.TRANSPORT_FAILURE, "IMAP query failed"); }
        finally {
            if (password != null) Arrays.fill(password, '\0');
            if (secret != null) try { secret.close(); } catch (RuntimeException ignored) { }
            try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) { }
            try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) { }
            if (watchdog.finishAndJoin()) throw timeout();
        }
    }

    private SecretValue resolveCredential(String tenantId, ImapProfile profile, int actionLimit,
                                          ConcurrentHashMap<String, Gate> resolverActions, DeadlineWatchdog watchdog) {
        ResolverAdmission admission = ResolverAdmission.acquire(tenantId, profile, actionLimit, resolverActions);
        if (!admission.acquired()) throw resolverCapacity();
        CredentialHandoff handoff = new CredentialHandoff();
        Thread resolver;
        try {
            resolver = Thread.ofVirtual().name("ravenroot-imap-credential-", 0).unstarted(() -> {
                ACTIVE_RESOLVER_TASKS.incrementAndGet();
                try {
                    CredentialOutcome outcome;
                    try {
                        Optional<SecretValue> resolved = credentials.resolve(profile.credentialRef());
                        outcome = resolved == null || resolved.isEmpty()
                                ? CredentialOutcome.failure(credentialUnavailable())
                                : CredentialOutcome.success(resolved.get());
                    } catch (Throwable hostile) { outcome = CredentialOutcome.failure(credentialUnavailable()); }
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

    private static Properties properties(ImapProfile profile, String protocol) {
        String prefix = "mail." + protocol;
        int timeoutMs = Math.min(MAX_QUERY_MS, profile.readTimeoutMs());
        Properties properties = new Properties();
        properties.setProperty(prefix + ".ssl.checkserveridentity", "true");
        properties.setProperty(prefix + ".connectiontimeout", Integer.toString(Math.min(timeoutMs, profile.connectTimeoutMs())));
        properties.setProperty(prefix + ".timeout", Integer.toString(timeoutMs));
        properties.setProperty(prefix + ".writetimeout", Integer.toString(timeoutMs));
        if (protocol.equals("imap")) properties.setProperty("mail.imap.peek", "true");
        if (protocol.equals("imaps")) properties.setProperty("mail.imaps.peek", "true");
        if (protocol.equals("imap")) {
            properties.setProperty("mail.imap.starttls.enable", "true");
            properties.setProperty("mail.imap.starttls.required", "true");
        }
        return properties;
    }

    private static void trackSockets(Properties properties, String protocol, DeadlineWatchdog watchdog) {
        String prefix = "mail." + protocol;
        Object configuredSsl = properties.get(prefix + ".ssl.socketFactory");
        SSLSocketFactory ssl = configuredSsl instanceof SSLSocketFactory factory ? factory : (SSLSocketFactory) SSLSocketFactory.getDefault();
        properties.put(prefix + ".ssl.socketFactory", new TrackingSslSocketFactory(ssl, watchdog));
        if (protocol.equals("imap")) {
            Object configuredPlain = properties.get(prefix + ".socketFactory");
            SocketFactory plain = configuredPlain instanceof SocketFactory factory ? factory : SocketFactory.getDefault();
            properties.put(prefix + ".socketFactory", new TrackingSocketFactory(plain, watchdog));
        }
    }

    private static Map<String, Object> row(Message message, long uid, int previewBudget, boolean full) throws Exception {
        int messageSize = message.getSize();
        if (messageSize < 0 || messageSize > MAX_MESSAGE_BYTES) throw resourceLimit();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uid", uid); row.put("messageId", bounded(header(message, "Message-ID"), 512));
        row.put("subject", bounded(Objects.toString(message.getSubject(), ""), 512));
        row.put("sentAt", message.getSentDate() == null ? "" : message.getSentDate().toInstant().toString());
        row.put("from", addresses(message.getFrom())); row.put("to", addresses(message.getRecipients(Message.RecipientType.TO)));
        row.put("flags", Arrays.stream(message.getFlags().getSystemFlags()).map(Object::toString).toList());
        // `previewBudget` is the operator's `maxPreviewChars`, and the full branch never receives
        // it -- that is the whole point of splitting here rather than passing a "budget" of MAX_VALUE
        // down one shared path. Changing `maxPreviewChars` cannot move a byte of a full result because
        // no full code path can read it.
        row.put("size", messageSize); row.putAll(full ? fullContent(message) : content(message, previewBudget));
        return Map.copyOf(row);
    }

    /**
     * The complete decoded body, never abbreviated. A full result deliberately carries neither
     * {@code textPreview} nor {@code previewTruncated}: a successful full query that also shipped a
     * shortened copy of the same body would be exactly the silent downgrade this mode exists to
     * forbid, and a caller could not tell the two apart from the payload alone. {@code complete} is
     * the unambiguous marker that replaces them, and {@code attachmentBodiesIncluded} states the one
     * boundary full does *not* cross: attachment bytes are still not fetched, so "complete" means
     * complete body text, not complete message.
     */
    private static Map<String, Object> fullContent(Part part) throws Exception {
        List<Map<String, Object>> attachments = new ArrayList<>();
        MimeBudget limits = new MimeBudget();
        FullBody body = new FullBody();
        collect(part, attachments, limits, body, 0);
        return Map.of("content", Map.of("mode", FULL_MODE, "text", body.text(), "html", body.html(),
                        "complete", true, "attachmentBodiesIncluded", false),
                "attachments", List.copyOf(attachments));
    }

    /**
     * Walks every non-attachment part in document order. Unlike {@link #extract}, which stops at the
     * first non-empty {@code text/plain} it finds, this keeps going: dropping a second textual part
     * would be an abbreviation, and full forbids abbreviation. It also collects {@code text/html},
     * which preview deliberately still ignores so that the preview shape stays byte-identical.
     */
    private static void collect(Part part, List<Map<String, Object>> attachments, MimeBudget limits, FullBody body, int depth) throws Exception {
        limits.visit(depth);
        if (part.isMimeType("text/plain")) { body.addText(decode(part, limits)); return; }
        if (part.isMimeType("text/html")) { body.addHtml(decode(part, limits)); return; }
        if (!part.isMimeType("multipart/*")) return;
        Object value = part.getContent();
        if (!(value instanceof Multipart multipart)) throw resourceLimit();
        for (int i = 0; i < multipart.getCount(); i++) {
            Part child = multipart.getBodyPart(i);
            if (attachment(child)) {
                limits.visit(depth + 1);
                if (attachments.size() < MAX_ATTACHMENTS) attachments.add(attachmentMetadata(child));
            } else collect(child, attachments, limits, body, depth + 1);
        }
    }

    /**
     * Reads one textual part whole. The aggregate 1 MiB ceiling stays in force through the same
     * {@link CountingInputStream}/{@link MimeBudget#inspect} pair the preview branch uses; the extra
     * per-part character ceiling here refuses rather than truncates, because a truncated success is
     * the outcome this mode must never produce.
     */
    private static String decode(Part part, MimeBudget limits) throws Exception {
        try {
            String charset = new ContentType(part.getContentType()).getParameter("charset");
            java.nio.charset.Charset decoded = java.nio.charset.Charset.forName(MimeUtility.javaCharset(charset == null ? "UTF-8" : charset));
            CountingInputStream counted = new CountingInputStream(part.getInputStream());
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            try (Reader reader = new InputStreamReader(counted, decoded)) {
                while (true) {
                    int read = reader.read(buffer, 0, buffer.length);
                    if (read < 0) break;
                    if (text.length() + read > MAX_MESSAGE_BYTES) throw resourceLimit();
                    text.append(buffer, 0, read);
                }
            }
            limits.inspect(counted.count());
            return text.toString();
        } catch (ImapQueryException failure) { throw failure; }
        catch (ContentLimitException overLimit) { throw resourceLimit(); }
        catch (RuntimeException invalidCharset) { throw resourceLimit(); }
    }

    private static boolean attachment(Part child) throws Exception {
        return Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition()) || child.getFileName() != null;
    }
    private static Map<String, Object> attachmentMetadata(Part child) throws Exception {
        return Map.of("name", bounded(Objects.toString(child.getFileName(), ""), 256),
                "contentType", bounded(child.getContentType(), 256), "size", Math.max(0, child.getSize()));
    }

    private static Map<String, Object> content(Part part, int budget) throws Exception {
        List<Map<String, Object>> attachments = new ArrayList<>();
        MimeBudget limits = new MimeBudget();
        Preview preview = extract(part, budget, attachments, limits, 0);
        return Map.of("textPreview", preview.text(), "previewTruncated", preview.truncated(), "attachments", List.copyOf(attachments));
    }
    private static Preview extract(Part part, int budget, List<Map<String, Object>> attachments, MimeBudget limits, int depth) throws Exception {
        limits.visit(depth);
        if (budget == 0) return new Preview("", false);
        if (part.isMimeType("text/plain")) {
            try {
                String charset = new ContentType(part.getContentType()).getParameter("charset");
                java.nio.charset.Charset decoded = java.nio.charset.Charset.forName(MimeUtility.javaCharset(charset == null ? "UTF-8" : charset));
                CountingInputStream counted = new CountingInputStream(part.getInputStream());
                char[] chars = new char[budget + 1]; int offset = 0;
                try (Reader reader = new InputStreamReader(counted, decoded)) {
                    while (offset < chars.length) { int read = reader.read(chars, offset, chars.length - offset); if (read < 0) break; offset += read; }
                }
                limits.inspect(counted.count());
                return new Preview(new String(chars, 0, Math.min(offset, budget)), offset > budget);
            } catch (ImapQueryException failure) { throw failure; }
            catch (RuntimeException invalidCharset) { throw resourceLimit(); }
        }
        if (!part.isMimeType("multipart/*")) return new Preview("", false);
        Object value = part.getContent();
        if (!(value instanceof Multipart multipart)) throw resourceLimit();
        Preview text = new Preview("", false);
        for (int i = 0; i < multipart.getCount(); i++) {
            Part child = multipart.getBodyPart(i);
            if (attachment(child)) {
                limits.visit(depth + 1);
                if (attachments.size() < MAX_ATTACHMENTS) attachments.add(attachmentMetadata(child));
            } else if (text.text().isEmpty()) text = extract(child, budget, attachments, limits, depth + 1);
            else limits.visit(depth + 1);
        }
        return text;
    }
    private static String header(Message message, String name) throws Exception { String[] values = message.getHeader(name); return values == null || values.length == 0 ? "" : values[0]; }
    private static List<String> addresses(Address[] addresses) { if (addresses == null) return List.of(); if (addresses.length > MAX_ADDRESSES) throw resourceLimit(); return Arrays.stream(addresses).map(Address::toString).map(value -> bounded(value, 512)).toList(); }
    private static String bounded(String value, int budget) { return value.length() <= budget ? value : value.substring(0, budget); }
    private static String bodyMode(String value, String message) {
        if (PREVIEW_MODE.equals(value) || FULL_MODE.equals(value)) return value;
        throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message);
    }
    private static int positive(String value, String message) { try { int parsed = Integer.parseInt(value); if (parsed > 0 && parsed <= 500) return parsed; } catch (RuntimeException ignored) { } throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message); }
    private static int optionalPositive(String value, int defaultValue, String message) { return value == null || value.isBlank() ? defaultValue : positive(value, message); }
    private static boolean safeId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
    private static ImapQueryException capacity() { return new ImapQueryException(ImapQueryException.Code.SATURATED, "IMAP query capacity is unavailable"); }
    private static ImapQueryException resolverCapacity() { return new ImapQueryException(ImapQueryException.Code.SATURATED, "IMAP credential resolver capacity is unavailable"); }
    private static ImapQueryException credentialUnavailable() { return new ImapQueryException(ImapQueryException.Code.CREDENTIAL_UNAVAILABLE, "IMAP credential unavailable"); }
    private static ImapQueryException resourceLimit() { return new ImapQueryException(ImapQueryException.Code.RESOURCE_LIMIT, "IMAP query exceeds processing limits"); }
    private static ImapQueryException timeout() { return new ImapQueryException(ImapQueryException.Code.TIMEOUT, "IMAP query deadline exceeded"); }
    private void checkDeadline(long deadline) { if (nanoTime.getAsLong() - deadline >= 0) throw new ImapQueryException(ImapQueryException.Code.TIMEOUT, "IMAP query deadline exceeded"); }

    private record Request(String folder, int limit, long uidMin, long uidMax, String contentMode, Map<?, ?> raw) {
        Request withLimit(int value) { return new Request(folder, value, uidMin, uidMax, contentMode, raw); }
        boolean full() { return FULL_MODE.equals(contentMode); }
        static Request from(Object payload, String defaultFolder, int configuredLimit, String defaultContentMode) {
            if (!(payload instanceof Map<?, ?> map) || !"mail.imap.query.v1".equals(map.get("version")))
                throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid IMAP query payload");
            String folder = map.containsKey("folder") ? string(map.get("folder"), "Invalid folder") : defaultFolder;
            if (folder.isBlank() || folder.length() > 256 || folder.indexOf('\r') >= 0 || folder.indexOf('\n') >= 0)
                throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid folder");
            int requested = map.containsKey("limit") ? positiveNumber(map.get("limit"), "Invalid limit") : configuredLimit;
            long min = map.containsKey("uidMin") ? nonNegative(map.get("uidMin"), "Invalid UID bound") : 0;
            long max = map.containsKey("uidMax") ? nonNegative(map.get("uidMax"), "Invalid UID bound") : Long.MAX_VALUE;
            if (min > max) throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid UID bound");
            validateString(map, "from"); validateString(map, "to"); validateString(map, "subject"); validateInstant(map, "since"); validateInstant(map, "before");
            if (map.containsKey("unseen") && !(map.get("unseen") instanceof Boolean)) throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid unseen criterion");
            // The payload field is authoritative for this invocation: when it says `full`, the
            // outcome is either a full body or a typed error. There is no path from here to a preview
            // result, and the node property only supplies the value used when the field is absent.
            String mode = map.containsKey("contentMode")
                    ? bodyMode(string(map.get("contentMode"), "Invalid content mode"), "Invalid content mode")
                    : defaultContentMode;
            return new Request(folder, Math.min(configuredLimit, requested), min, max, mode, map);
        }
        SearchTerm term() {
            List<SearchTerm> terms = new ArrayList<>();
            addString(terms, "from", value -> new FromStringTerm(value)); addString(terms, "to", value -> new RecipientStringTerm(Message.RecipientType.TO, value)); addString(terms, "subject", SubjectTerm::new);
            if (Boolean.TRUE.equals(raw.get("unseen"))) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            addDate(terms, "since", ComparisonTerm.GE); addDate(terms, "before", ComparisonTerm.LT);
            return terms.isEmpty() ? new FlagTerm(new Flags(), true) : terms.stream().reduce(AndTerm::new).orElseThrow();
        }
        long cursor(long validity) {
            Object value = raw.get("cursor"); if (value == null) return 0;
            if (!(value instanceof Map<?, ?> cursor)) throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid or stale cursor");
            long cursorValidity = exactLong(cursor.get("uidValidity"), "Invalid or stale cursor");
            long cursorUid = exactLong(cursor.get("lastUid"), "Invalid or stale cursor");
            if (cursorValidity != validity || cursorUid < 0) throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid or stale cursor");
            return cursorUid;
        }
        private void addString(List<SearchTerm> terms, String key, java.util.function.Function<String, SearchTerm> factory) { if (raw.containsKey(key)) terms.add(factory.apply((String) raw.get(key))); }
        private void addDate(List<SearchTerm> terms, String key, int comparison) { if (raw.containsKey(key)) terms.add(new SentDateTerm(comparison, Date.from(Instant.parse((String) raw.get(key))))); }
        private static void validateString(Map<?, ?> map, String key) { if (map.containsKey(key)) string(map.get(key), "Invalid " + key + " criterion"); }
        private static void validateInstant(Map<?, ?> map, String key) { if (map.containsKey(key)) try { Instant.parse(string(map.get(key), "Invalid date criterion")); } catch (RuntimeException e) { throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, "Invalid date criterion"); } }
        private static String string(Object value, String message) { if (value instanceof String text && text.length() <= 512 && text.indexOf('\r') < 0 && text.indexOf('\n') < 0) return text; throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message); }
        private static int positiveNumber(Object value, String message) { long parsed = exactLong(value, message); if (parsed > 0 && parsed <= 500) return (int) parsed; throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message); }
        private static long nonNegative(Object value, String message) { long parsed = exactLong(value, message); if (parsed >= 0) return parsed; throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message); }
        private static long exactLong(Object value, String message) {
            if (!(value instanceof Number number)) throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message);
            try {
                if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long) return number.longValue();
                if (number instanceof BigInteger integer) return integer.longValueExact();
                if (number instanceof BigDecimal decimal) return decimal.longValueExact();
                if (number instanceof Float || number instanceof Double) { double decimal = number.doubleValue(); if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)) throw new ArithmeticException(); return BigDecimal.valueOf(decimal).longValueExact(); }
                return new BigDecimal(number.toString()).longValueExact();
            } catch (RuntimeException invalid) { throw new ImapQueryException(ImapQueryException.Code.INVALID_INPUT, message); }
        }
    }

    private record Preview(String text, boolean truncated) { }
    /**
     * Accumulates the textual parts of one message in document order, with a single {@code \n}
     * <em>between</em> parts and never around them: the single-part case, which is the norm, is
     * byte-exact with respect to the part it came from. The counters exist instead of an
     * {@code isEmpty()} test so that a first part that is legitimately empty still separates the
     * second one -- with {@code isEmpty()} the two would silently run together.
     */
    private static final class FullBody {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder html = new StringBuilder();
        private int textParts, htmlParts;
        void addText(String value) { if (textParts++ > 0) text.append('\n'); text.append(value); }
        void addHtml(String value) { if (htmlParts++ > 0) html.append('\n'); html.append(value); }
        String text() { return text.toString(); }
        String html() { return html.toString(); }
    }
    private static final class MimeBudget {
        private int parts;
        private int inspectedBytes;
        void visit(int depth) { if (depth > MAX_MIME_DEPTH || ++parts > MAX_MIME_PARTS) throw resourceLimit(); }
        void inspect(int bytes) { inspectedBytes += bytes; if (inspectedBytes > MAX_MESSAGE_BYTES) throw resourceLimit(); }
    }
    /**
     * A named subtype instead of a bare {@code IOException}. The preview branch never reaches
     * this ceiling -- it stops reading at {@code budget + 1} characters -- so nothing about its
     * behaviour changes, but the full branch reads a part whole and needs to tell "this message is
     * too big" apart from "the socket broke", which is the difference between {@code RESOURCE_LIMIT}
     * and {@code TRANSPORT_FAILURE}. It is still an {@code IOException}, so every existing catch site
     * classifies it exactly as before.
     */
    private static final class ContentLimitException extends java.io.IOException {
        private static final long serialVersionUID = 1L;
        ContentLimitException() { super("IMAP content limit"); }
    }
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private int count;
        CountingInputStream(InputStream input) { super(input); }
        int count() { return count; }
        @Override public int read() throws java.io.IOException { int value = super.read(); if (value >= 0 && ++count > MAX_MESSAGE_BYTES) throw new ContentLimitException(); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException { int read = super.read(bytes, offset, Math.min(length, MAX_MESSAGE_BYTES + 1 - count)); if (read > 0 && (count += read) > MAX_MESSAGE_BYTES) throw new ContentLimitException(); return read; }
    }

    private record CredentialOutcome(SecretValue secret, ImapQueryException failure) {
        static CredentialOutcome success(SecretValue secret) { return new CredentialOutcome(Objects.requireNonNull(secret), null); }
        static CredentialOutcome failure(ImapQueryException failure) { return new CredentialOutcome(null, Objects.requireNonNull(failure)); }
    }
    private static final class CredentialHandoff {
        private Thread resolver;
        private CredentialOutcome outcome;
        private boolean abandoned;

        synchronized void submitted(Thread value) { resolver = value; if (abandoned) value.interrupt(); }
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
                    int nanos = (int) (remaining - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
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
        private static void close(SecretValue secret) { if (secret != null) try { secret.close(); } catch (RuntimeException ignored) { } }
    }

    private static final class DeadlineWatchdog {
        private static final int ACTIVE = 0, FINISHED = 1, TIMED_OUT = 2;
        private final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final AtomicReference<Folder> folder = new AtomicReference<>();
        private final AtomicReference<Store> store = new AtomicReference<>();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Thread owner = Thread.currentThread();
        private final long deadline;
        private final Thread thread;

        DeadlineWatchdog(int timeoutMs) {
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            thread = Thread.ofVirtual().name("ravenroot-imap-watchdog-", 0).unstarted(this::watch);
        }
        void start() {
            ACTIVE_WATCHDOGS.incrementAndGet();
            try { thread.start(); }
            catch (Throwable failure) { ACTIVE_WATCHDOGS.decrementAndGet(); state.set(FINISHED); throw failure; }
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
            while (thread.isAlive()) try { thread.join(); } catch (InterruptedException ignored) { interrupted = true; }
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
            } finally { ACTIVE_WATCHDOGS.decrementAndGet(); }
        }
        private synchronized boolean expireIfDue() {
            if (state.get() == ACTIVE && System.nanoTime() - deadline >= 0) state.set(TIMED_OUT);
            return state.get() != ACTIVE;
        }
        private synchronized void finishState() {
            if (state.get() == ACTIVE) state.set(System.nanoTime() - deadline >= 0 ? TIMED_OUT : FINISHED);
        }
        private static void close(Socket socket) {
            if (socket == null) return;
            try { socket.shutdownInput(); } catch (Exception ignored) { }
            try { socket.shutdownOutput(); } catch (Exception ignored) { }
            try { socket.close(); } catch (Exception ignored) { }
        }
        private static void close(Folder folder) {
            if (folder == null) return;
            try { if (folder instanceof IMAPFolder imap) imap.forceClose(); else if (folder.isOpen()) folder.close(false); } catch (Exception ignored) { }
        }
        private static void close(Store store) { if (store != null) try { if (store.isConnected()) store.close(); } catch (Exception ignored) { } }
    }

    private static class TrackingSocketFactory extends SocketFactory {
        private final SocketFactory delegate; private final DeadlineWatchdog watchdog;
        TrackingSocketFactory(SocketFactory delegate, DeadlineWatchdog watchdog) { this.delegate = delegate; this.watchdog = watchdog; }
        @Override public Socket createSocket() throws java.io.IOException { return watchdog.track(delegate.createSocket()); }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port, local, localPort)); }
    }
    private static final class TrackingSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate; private final DeadlineWatchdog watchdog;
        TrackingSslSocketFactory(SSLSocketFactory delegate, DeadlineWatchdog watchdog) { this.delegate = delegate; this.watchdog = watchdog; }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws java.io.IOException { return watchdog.track(delegate.createSocket()); }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws java.io.IOException { return watchdog.track(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws java.io.IOException { return watchdog.track(delegate.createSocket(socket, host, port, close)); }
    }

    private static GateLease lease(ConcurrentHashMap<String, Gate> gates, String key, int permits) { Gate gate = gates.compute(key, (ignored, current) -> current == null ? new Gate(permits) : current.retain()); return new GateLease(gates, key, gate); }
    private static final class Admission {
        private final GateLease tenant, profile, action; private boolean globalPermit, tenantPermit, profilePermit, actionPermit;
        private Admission(GateLease tenant, GateLease profile, GateLease action) { this.tenant = tenant; this.profile = profile; this.action = action; }
        static Admission acquire(String tenantId, ImapProfile profile, int actionLimit, ConcurrentHashMap<String, Gate> actions) {
            Admission admission = new Admission(lease(TENANT_SLOTS, tenantId, TENANT_CONCURRENCY), lease(PROFILE_SLOTS, tenantId + "\0" + profile.id(), profile.maxConcurrency()), lease(actions, tenantId, actionLimit));
            if (!(admission.globalPermit = GLOBAL_SLOTS.tryAcquire()) || !(admission.tenantPermit = admission.tenant.gate.slots.tryAcquire()) || !(admission.profilePermit = admission.profile.gate.slots.tryAcquire()) || !(admission.actionPermit = admission.action.gate.slots.tryAcquire())) admission.release();
            return admission;
        }
        boolean acquired() { return globalPermit && tenantPermit && profilePermit && actionPermit; }
        void release() { if (actionPermit) { action.gate.slots.release(); actionPermit = false; } if (profilePermit) { profile.gate.slots.release(); profilePermit = false; } if (tenantPermit) { tenant.gate.slots.release(); tenantPermit = false; } if (globalPermit) { GLOBAL_SLOTS.release(); globalPermit = false; } action.close(); profile.close(); tenant.close(); }
    }
    private static final class ResolverAdmission {
        private final GateLease tenant, profile, action; private boolean globalPermit, tenantPermit, profilePermit, actionPermit;
        private ResolverAdmission(GateLease tenant, GateLease profile, GateLease action) { this.tenant = tenant; this.profile = profile; this.action = action; }
        static ResolverAdmission acquire(String tenantId, ImapProfile profile, int actionLimit, ConcurrentHashMap<String, Gate> actions) {
            ResolverAdmission admission = new ResolverAdmission(lease(RESOLVER_TENANT_SLOTS, tenantId, RESOLVER_TENANT_CONCURRENCY),
                    lease(RESOLVER_PROFILE_SLOTS, tenantId + "\0" + profile.id(), profile.maxConcurrency()), lease(actions, tenantId, actionLimit));
            if (!(admission.globalPermit = RESOLVER_GLOBAL_SLOTS.tryAcquire()) || !(admission.tenantPermit = admission.tenant.gate.slots.tryAcquire())
                    || !(admission.profilePermit = admission.profile.gate.slots.tryAcquire()) || !(admission.actionPermit = admission.action.gate.slots.tryAcquire())) admission.release();
            return admission;
        }
        boolean acquired() { return globalPermit && tenantPermit && profilePermit && actionPermit; }
        void release() { if (actionPermit) { action.gate.slots.release(); actionPermit = false; } if (profilePermit) { profile.gate.slots.release(); profilePermit = false; } if (tenantPermit) { tenant.gate.slots.release(); tenantPermit = false; } if (globalPermit) { RESOLVER_GLOBAL_SLOTS.release(); globalPermit = false; } action.close(); profile.close(); tenant.close(); }
    }
    private static final class GateLease { private final ConcurrentHashMap<String, Gate> gates; private final String key; private final Gate gate; private boolean closed; GateLease(ConcurrentHashMap<String, Gate> gates, String key, Gate gate) { this.gates = gates; this.key = key; this.gate = gate; } void close() { if (!closed) { closed = true; gates.computeIfPresent(key, (ignored, current) -> current == gate && current.references.decrementAndGet() == 0 ? null : current); } } }
    private static final class Gate { final Semaphore slots; final AtomicInteger references = new AtomicInteger(1); Gate(int permits) { slots = new Semaphore(permits, true); } Gate retain() { references.incrementAndGet(); return this; } }
}
