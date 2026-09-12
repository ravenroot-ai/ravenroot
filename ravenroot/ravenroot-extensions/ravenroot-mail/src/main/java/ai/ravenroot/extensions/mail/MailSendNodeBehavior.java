package ai.ravenroot.extensions.mail;

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
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** SMTP/SMTPS sender. Graph content describes one message; deployment configuration names its secret. */
public final class MailSendNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "mail.send";
    private static final int MAX_RECIPIENTS = 100;
    private static final int MAX_ATTACHMENTS = 10;
    private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_ATTACHMENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ENCODED_ATTACHMENT_BYTES = 14 * 1024 * 1024;
    private static final int TENANT_CONCURRENCY = 16;
    private static final Semaphore MAIL_SLOTS = new Semaphore(32, true);
    private static final java.util.concurrent.ConcurrentHashMap<String, Gate> TENANT_SLOTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Gate> PROFILE_SLOTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.Executor MAIL_EXECUTOR = task -> Thread.ofVirtual().name("ravenroot-mail-", 0).start(task);
    private final CredentialResolver credentials;
    private final MailProfileResolver profiles;
    private final SecretCopy secretCopy;
    private final PasswordString passwordString;
    private final ReservedNetworkPolicy destinationPolicy;

    public MailSendNodeBehavior() { this(new EnvironmentMailCredentialResolver(), new EnvironmentMailProfileResolver()); }
    public MailSendNodeBehavior(CredentialResolver credentials) { this(credentials, new EnvironmentMailProfileResolver()); }
    public MailSendNodeBehavior(CredentialResolver credentials, MailProfileResolver profiles) {
        this(credentials, profiles, SecretValue::copy, String::new);
    }
    MailSendNodeBehavior(CredentialResolver credentials, MailProfileResolver profiles,
                         SecretCopy secretCopy, PasswordString passwordString) {
        this(credentials, profiles, secretCopy, passwordString,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }
    MailSendNodeBehavior(CredentialResolver credentials, MailProfileResolver profiles,
                         SecretCopy secretCopy, PasswordString passwordString,
                         ReservedNetworkPolicy destinationPolicy) {
        this.credentials = credentials;
        this.profiles = profiles;
        this.secretCopy = secretCopy;
        this.passwordString = passwordString;
        this.destinationPolicy = java.util.Objects.requireNonNull(destinationPolicy);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Send email", "Mail", "Sends a mail.send.v1 message through SMTP.",
                "actor", false, List.of(
                required("mailProfile", "Mail profile", "Opaque operator-managed SMTP policy name."),
                optional("host", "SMTP host", "Legacy exact-match only."), optional("port", "Port", NodePropertyType.INTEGER, "Legacy exact-match only.", ""),
                optional("securityMode", "Security mode", "Legacy exact-match only."), optional("tlsVerify", "Verify TLS", NodePropertyType.BOOLEAN, "Must remain true.", "true"),
                optional("authUsername", "Authentication username", "Legacy exact-match only."),
                optional("credentialRef", "Credential reference", NodePropertyType.SECRET_REFERENCE, "Legacy exact-match only.", ""),
                optional("connectTimeoutMs", "Connect timeout", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("readTimeoutMs", "Read timeout", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("writeTimeoutMs", "Write timeout", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("retries", "Retries", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxRecipients", "Recipient limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxHeaders", "Header limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxHeaderChars", "Envelope metadata limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxBodyChars", "Body limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxAttachments", "Attachment limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxAttachmentBytes", "Attachment byte limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxTotalAttachmentBytes", "Attachment aggregate limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxEncodedAttachmentBytes", "Base64 aggregate limit", NodePropertyType.INTEGER, "May only tighten profile.", ""), optional("maxConcurrency", "Concurrency limit", NodePropertyType.INTEGER, "Optional 1–16 limit; blank uses the mail profile ceiling and a value may only tighten it.", ""), optional("defaultFrom", "Default sender", "Legacy exact-match only.")
                // PERS-04 (ADR 0022). mail.send deliberately does NOT declare
                // RecoveryRepeatabilityProperty, and the absence is the declaration, not an omission
                // to be filled in later.
                //
                // The reason is that repeatability here is not a constant of the node: it depends on
                // where the attempt died. Before connect() succeeds nothing has been offered and a
                // repeat is free; once sendMessage() reaches DATA the server may have accepted the
                // message, and a repeat is a SECOND message rather than a retry of the first. The
                // engine's only discriminator is the attempt status, and SCHEDULED-versus-RUNNING
                // does not cut at that boundary: every phase of this method lives inside RUNNING.
                //
                // Nor can the effect be made safe downstream the way an AMQP or Kafka receiver can.
                // Recovery re-dispatches under the attempt id and SMTP has nowhere
                // to put it: the Message-ID is generated per MimeMessage, and no relay deduplicates
                // on anything the runtime controls. So no author assertion could make 'repeatable'
                // true for this node, and declaring the property would hand authors a lever whose
                // only reachable honest value is the one that changes nothing.
                //
                // Declaring nothing yields UNDECLARED, which parks -- the correct disposition. What
                // it gets wrong is the reason shown to the operator: the park cause reads
                // "is undeclared", i.e. "the author forgot", where the truth is "the type refuses".
                // AttemptRepeatability has three members and all three describe an instance value;
                // there is no way for a behavior TYPE to say NOT_REPEATABLE. This is a boundary
                // limitation rather than something to work around here.
                //
                // Nothing about when this node retries changed with that finding: connect() retries
                // only connection establishment, and sendMessage() is invoked exactly once.
        ), Set.of("network", "credential-reference", "side-effect"));
    }

    private static NodePropertyDescriptor required(String n, String display, String description) {
        return NodePropertyDescriptor.required(n, display, NodePropertyType.STRING, description);
    }
    private static NodePropertyDescriptor optional(String n, String display, String description) {
        return NodePropertyDescriptor.optional(n, display, NodePropertyType.STRING, description, "");
    }
    private static NodePropertyDescriptor optional(String n, String display, String description, String value) {
        return NodePropertyDescriptor.optional(n, display, NodePropertyType.STRING, description, value);
    }
    private static NodePropertyDescriptor optional(String n, String display, NodePropertyType type, String description, String value) {
        return NodePropertyDescriptor.optional(n, display, type, description, value);
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        var actionSlots = new java.util.concurrent.ConcurrentHashMap<String, Gate>();
        return message -> {
            final Settings settings;
            try {
                settings = Settings.from(configuration, profiles, destinationPolicy, message.tenantId());
                if (!MAIL_SLOTS.tryAcquire()) return CompletableFuture.failedFuture(new MailSendException(MailSendException.Code.CAPACITY_UNAVAILABLE, "Mail delivery capacity is unavailable"));
                Admission admission = Admission.acquire(message.tenantId(), settings, actionSlots);
                if (!admission.acquired()) { MAIL_SLOTS.release(); return CompletableFuture.failedFuture(new MailSendException(MailSendException.Code.CAPACITY_UNAVAILABLE, "Mail delivery capacity is unavailable")); }
                try { return CompletableFuture.supplyAsync(() -> { try { return send(settings, message.payload()); } finally { admission.release(); MAIL_SLOTS.release(); } }, MAIL_EXECUTOR); }
                catch (RuntimeException rejected) { admission.release(); MAIL_SLOTS.release(); return CompletableFuture.failedFuture(new MailSendException(MailSendException.Code.CAPACITY_UNAVAILABLE, "Mail delivery capacity is unavailable")); }
                catch (Error rejected) { admission.release(); MAIL_SLOTS.release(); throw rejected; }
            } catch (MailSendException failure) { return CompletableFuture.failedFuture(failure); }
        };
    }
    private static GateLease lease(java.util.concurrent.ConcurrentHashMap<String, Gate> gates, String key, int permits) {
        Gate gate = gates.compute(key, (ignored, existing) -> existing == null ? new Gate(permits) : existing.retain());
        return new GateLease(gates, key, gate);
    }
    private static final class Admission {
        private final GateLease tenant;
        private final GateLease profile;
        private final GateLease action;
        private boolean tenantHeld;
        private boolean profileHeld;
        private boolean actionHeld;
        private boolean released;

        private Admission(GateLease tenant, GateLease profile, GateLease action) { this.tenant = tenant; this.profile = profile; this.action = action; }
        static Admission acquire(String tenantId, Settings settings, java.util.concurrent.ConcurrentHashMap<String, Gate> actionSlots) {
            GateLease tenant = lease(TENANT_SLOTS, tenantId, TENANT_CONCURRENCY);
            GateLease profile = lease(PROFILE_SLOTS, tenantId + "\u0000" + settings.profile.name(), settings.profile.maxConcurrency());
            GateLease action = lease(actionSlots, tenantId, settings.maxConcurrency());
            Admission admission = new Admission(tenant, profile, action);
            if (!(admission.tenantHeld = tenant.gate.slots.tryAcquire())
                    || !(admission.profileHeld = profile.gate.slots.tryAcquire())
                    || !(admission.actionHeld = action.gate.slots.tryAcquire())) admission.release();
            return admission;
        }
        boolean acquired() { return tenantHeld && profileHeld && actionHeld; }
        // Mirrors KafkaRuntimeControls.Admission.release() / AmqpRuntimeControls.Admission.release():
        // a single `released` guard under `synchronized` makes double release a no-op by construction,
        // not by an accident of how the caller happens to be sequenced.
        synchronized void release() {
            if (released) return;
            released = true;
            if (actionHeld) action.gate.slots.release();
            if (profileHeld) profile.gate.slots.release();
            if (tenantHeld) tenant.gate.slots.release();
            action.close(); profile.close(); tenant.close();
        }
    }
    private static final class GateLease {
        private final java.util.concurrent.ConcurrentHashMap<String, Gate> gates;
        private final String key;
        private final Gate gate;
        private boolean closed;
        GateLease(java.util.concurrent.ConcurrentHashMap<String, Gate> gates, String key, Gate gate) { this.gates = gates; this.key = key; this.gate = gate; }
        synchronized void close() { if (!closed) { closed = true; gates.computeIfPresent(key, (ignored, current) -> current == gate && current.refs.decrementAndGet() == 0 ? null : current); } }
    }
    private static final class Gate { final Semaphore slots; final java.util.concurrent.atomic.AtomicInteger refs = new java.util.concurrent.atomic.AtomicInteger(1); Gate(int permits) { slots = new Semaphore(permits, true); } Gate retain() { refs.incrementAndGet(); return this; } }

    private NodeResult send(Settings settings, Object rawPayload) {
        Payload payload = Payload.from(rawPayload, settings);
        Properties props = settings.properties();
        Session session = Session.getInstance(props);
        MimeMessage message = buildMessage(session, payload, settings);
        try (Transport transport = session.getTransport(settings.protocol())) {
            connect(transport, settings);
            // Once sendMessage starts, the SMTP server may accept DATA. Never retry this operation.
            transport.sendMessage(message, message.getAllRecipients());
        } catch (SendFailedException failure) {
            return recipientResult(message, payload, failure);
        } catch (MailSendException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MailSendException(MailSendException.Code.TRANSPORT_FAILURE, "SMTP delivery failed");
        }
        List<String> accepted = safeRecipients(message);
        return new NodeResult("continue", Map.of("version", "mail.send.v1", "status", "SENT",
                "messageId", safeMessageId(message), "correlationId", payload.correlationId,
                "acceptedRecipients", accepted, "rejectedRecipients", List.of()), Map.of());
    }

    private void connect(Transport transport, Settings settings) throws Exception {
        String password = settings.authenticated() ? resolvePassword(settings) : null;
        Exception last = null;
        for (int attempt = 0; attempt <= settings.retries; attempt++) {
            try {
                transport.connect(settings.host, settings.port, settings.authenticated() ? settings.username : null,
                        password);
                return;
            } catch (jakarta.mail.AuthenticationFailedException failure) {
                throw new MailSendException(MailSendException.Code.AUTHENTICATION_FAILURE, "SMTP authentication failed");
            } catch (Exception failure) {
                if (authenticationFailure(failure))
                    throw new MailSendException(MailSendException.Code.AUTHENTICATION_FAILURE, "SMTP authentication failed");
                last = failure;
                if (!transientFailure(transport, failure) || attempt == settings.retries)
                    throw MailSendException.transportFailure(classification(transport, last));
                backoff(attempt);
            }
        }
        throw MailSendException.transportFailure(classification(transport, last));
    }

    private String resolvePassword(Settings settings) {
        SecretValue secret = credentials.resolve(settings.credentialRef).orElseThrow(() ->
                new MailSendException(MailSendException.Code.CREDENTIAL_UNAVAILABLE, "Mail credential is unavailable"));
        char[] copy = null;
        try {
            copy = secretCopy.copy(secret);
            return passwordString.create(copy);
        } finally {
            if (copy != null) Arrays.fill(copy, '\0');
            secret.close();
        }
    }

    private static boolean authenticationFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof jakarta.mail.AuthenticationFailedException) return true;
            if (current instanceof jakarta.mail.MessagingException messaging
                    && messaging.getNextException() instanceof jakarta.mail.AuthenticationFailedException) return true;
        }
        return false;
    }
    private static boolean transientFailure(Transport transport, Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof javax.net.ssl.SSLHandshakeException
                    || current instanceof java.security.cert.CertificateException) return false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.net.ConnectException || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.SocketException || current instanceof java.io.EOFException
                    || current instanceof org.eclipse.angus.mail.util.MailConnectException) return true;
        }
        return failure.getClass() == jakarta.mail.MessagingException.class
                && transport instanceof org.eclipse.angus.mail.smtp.SMTPTransport smtp
                && "[EOF]".equals(smtp.getLastServerResponse());
    }
    private static String classification(Transport transport, Exception failure) {
        return transientFailure(transport, failure)
                ? "transient SMTP connection failure" : "terminal SMTP connection failure";
    }
    private static void backoff(int attempt) {
        long millis = Math.min(500L, 50L << Math.min(attempt, 3));
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw MailSendException.transportFailure("interrupted SMTP connection retry");
        }
    }
    /** Package-private materialization seams make cleanup and exactly-once behavior observable. */
    @FunctionalInterface interface SecretCopy { char[] copy(SecretValue secret); }
    @FunctionalInterface interface PasswordString { String create(char[] copy); }

    private static MimeMessage buildMessage(Session session, Payload p, Settings settings) {
        try {
            MimeMessage message = new MimeMessage(session);
            String from = p.from.isBlank() ? settings.defaultFrom : p.from;
            if (from.isBlank()) throw new MailSendException(MailSendException.Code.INVALID_INPUT, "A sender is required");
            if (!settings.profile.allowsAddress(settings.profile.allowedFrom(), from)) throw new MailSendException(MailSendException.Code.INVALID_INPUT, "Sender is not allowed by mail profile");
            message.setFrom(new InternetAddress(from));
            setRecipients(message, Message.RecipientType.TO, p.to);
            setRecipients(message, Message.RecipientType.CC, p.cc);
            setRecipients(message, Message.RecipientType.BCC, p.bcc);
            if (!p.replyTo.isBlank()) {
                if (!settings.profile.allowsAddress(settings.profile.allowedReplyTo(), p.replyTo)) throw new MailSendException(MailSendException.Code.INVALID_INPUT, "Reply-To is not allowed by mail profile");
                message.setReplyTo(InternetAddress.parse(p.replyTo, true));
            }
            message.setSubject(p.subject, StandardCharsets.UTF_8.name());
            for (var header : p.headers.entrySet()) {
                if (!settings.profile.allowsHeader(header.getKey()) || Set.of("from", "to", "cc", "bcc", "reply-to", "subject", "content-type", "message-id", "date").contains(header.getKey().toLowerCase(Locale.ROOT)))
                    throw new MailSendException(MailSendException.Code.INVALID_INPUT, "Header is not allowed by mail profile");
                message.setHeader(header.getKey(), header.getValue());
            }
            MimeMultipart multipart = new MimeMultipart("mixed");
            if (!p.text.isBlank()) { MimeBodyPart text = new MimeBodyPart(); text.setText(p.text, StandardCharsets.UTF_8.name()); multipart.addBodyPart(text); }
            if (!p.html.isBlank()) { MimeBodyPart html = new MimeBodyPart(); html.setContent(p.html, "text/html; charset=UTF-8"); multipart.addBodyPart(html); }
            for (Attachment attachment : p.attachments) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(attachment.bytes, attachment.contentType)));
                part.setFileName(attachment.name); multipart.addBodyPart(part);
            }
            message.setContent(multipart); message.saveChanges(); return message;
        } catch (MailSendException failure) { throw failure; }
        catch (Exception failure) { throw new MailSendException(MailSendException.Code.INVALID_INPUT, "Mail payload is invalid"); }
    }
    private static void setRecipients(MimeMessage m, Message.RecipientType type, List<String> values) throws Exception {
        if (!values.isEmpty()) m.setRecipients(type, InternetAddress.parse(String.join(",", values), true));
    }
    private static List<String> addresses(Address[] values) {
        return values == null ? List.of() : Arrays.stream(values).map(Address::toString).toList();
    }
    private static List<String> safeRecipients(MimeMessage message) {
        try { return addresses(message.getAllRecipients()); } catch (Exception ignored) { return List.of(); }
    }
    private static String safeMessageId(MimeMessage m) { try { return m.getMessageID(); } catch (Exception ignored) { return ""; } }
    private static NodeResult recipientResult(MimeMessage message, Payload payload, SendFailedException failure) {
        List<String> accepted = addresses(failure.getValidSentAddresses());
        if (failure.getInvalidAddresses() == null || failure.getInvalidAddresses().length == 0
                || (failure.getValidUnsentAddresses() != null && failure.getValidUnsentAddresses().length != 0)) {
            return new NodeResult("continue", Map.of("version", "mail.send.v1", "status", "AMBIGUOUS",
                    "messageId", safeMessageId(message), "correlationId", payload.correlationId,
                    "acceptedRecipients", List.of(), "rejectedRecipients", List.of(), "errors", List.of("DELIVERY_STATE_UNKNOWN")), Map.of());
        }
        List<Map<String, String>> rejected = new ArrayList<>();
        for (Address address : failure.getInvalidAddresses()) {
            rejected.add(Map.of("recipient", address.toString(), "code", "SMTP_REJECTED",
                    "message", "SMTP server rejected recipient"));
        }
        String status = accepted.isEmpty() ? "REJECTED" : "PARTIAL";
        return new NodeResult("continue", Map.of("version", "mail.send.v1", "status", status,
                "messageId", safeMessageId(message), "correlationId", payload.correlationId,
                "acceptedRecipients", accepted, "rejectedRecipients", rejected, "errors", List.of()), Map.of());
    }
    private static List<Address> concat(Address[] first, Address[] second) {
        List<Address> result = new ArrayList<>();
        if (first != null) result.addAll(Arrays.asList(first));
        if (second != null) result.addAll(Arrays.asList(second));
        return result;
    }

    private record Settings(MailProfile profile, String host, int port, String securityMode, String username, String credentialRef,
                            int connectTimeout, int readTimeout, int writeTimeout, int retries, String defaultFrom,
                            int maxRecipients, int maxHeaders, int maxAttachments, int maxAttachmentBytes, int maxTotalAttachmentBytes,
                            int maxEncodedAttachmentBytes, int maxBodyChars, int maxHeaderChars, int maxConcurrency) {
        static Settings from(NodeConfiguration c, MailProfileResolver profiles, String tenant) {
            return from(c, profiles, ReservedNetworkPolicy.fromEnvironment(System.getenv()), tenant);
        }
        static Settings from(NodeConfiguration c, MailProfileResolver profiles,
                             ReservedNetworkPolicy destinationPolicy, String tenant) {
            if (!safeId(tenant)) throw new MailSendException(MailSendException.Code.CONFIGURATION, "Mail tenant is invalid");
            String profileName = c.property("mailProfile").orElseThrow(() -> new MailSendException(MailSendException.Code.CONFIGURATION, "A mail profile is required"));
            if (!safeId(profileName)) throw new MailSendException(MailSendException.Code.CONFIGURATION, "Mail profile is invalid");
            MailProfile profile = profiles.resolve(tenant, profileName).orElseThrow(() -> new MailSendException(MailSendException.Code.CONFIGURATION, "Mail profile is unavailable"));
            if (!profile.tenant().equals(tenant) || !profile.name().equals(profileName)) throw new MailSendException(MailSendException.Code.CONFIGURATION, "Mail profile binding is invalid");
            try { destinationPolicy.requireAllowedLiteral(profile.host()); }
            catch (SecurityException refused) { throw new MailSendException(MailSendException.Code.CONFIGURATION, "Mail profile is unavailable"); }
            if (profile.securityMode().equals("SMTP") && (!profile.allowPlaintext() || !profile.authUsername().isBlank()
                    || !Set.of("localhost", "127.0.0.1", "::1").contains(profile.host())))
                throw new MailSendException(MailSendException.Code.CONFIGURATION, "Plain SMTP is restricted to an unauthenticated local profile");
            // Legacy fields are advisory compatibility fields: each must match the profile or be a tighter numeric limit.
            exact(c, "host", profile.host()); exact(c, "port", Integer.toString(profile.port())); exact(c, "securityMode", profile.securityMode());
            exact(c, "authUsername", profile.authUsername()); exact(c, "credentialRef", profile.credentialRef()); exact(c, "defaultFrom", profile.defaultFrom());
            if (c.property("tlsVerify").isPresent() && !Boolean.parseBoolean(c.property("tlsVerify", "true"))) throw new MailSendException(MailSendException.Code.CONFIGURATION, "TLS verification cannot be disabled");
            return new Settings(profile, profile.host(), profile.port(), profile.securityMode(), profile.authUsername(), profile.credentialRef(),
                    tighten(c, "connectTimeoutMs", profile.connectTimeoutMs(), 1), tighten(c, "readTimeoutMs", profile.readTimeoutMs(), 1), tighten(c, "writeTimeoutMs", profile.writeTimeoutMs(), 1),
                    tighten(c, "retries", profile.retries(), 0), profile.defaultFrom(), tighten(c, "maxRecipients", profile.maxRecipients(), 1), tighten(c, "maxHeaders", profile.maxHeaders(), 0),
                    tighten(c, "maxAttachments", profile.maxAttachments(), 0), tighten(c, "maxAttachmentBytes", profile.maxAttachmentBytes(), 1),
                    tighten(c, "maxTotalAttachmentBytes", profile.maxTotalAttachmentBytes(), 1), tighten(c, "maxEncodedAttachmentBytes", profile.maxEncodedAttachmentBytes(), 1),
                    tighten(c, "maxBodyChars", profile.maxBodyChars(), 1), tighten(c, "maxHeaderChars", profile.maxHeaderChars(), 1), tighten(c, "maxConcurrency", profile.maxConcurrency(), 1));
        }
        static boolean safeId(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"); }
        static void exact(NodeConfiguration c, String name, String expected) { if (c.property(name).isPresent() && !c.property(name, "").equals(expected)) throw new MailSendException(MailSendException.Code.CONFIGURATION, "Legacy " + name + " does not match mail profile"); }
        static int tighten(NodeConfiguration c, String name, int ceiling, int minimum) {
            try { int value = Integer.parseInt(c.property(name, Integer.toString(ceiling))); if (value < minimum || value > ceiling) throw new NumberFormatException(); return value; }
            catch (NumberFormatException invalid) { throw new MailSendException(MailSendException.Code.CONFIGURATION, "Invalid " + name); }
        }
        String protocol() { return securityMode.equals("SMTPS") ? "smtps" : "smtp"; }
        boolean authenticated() { return !username.isBlank(); }
        Properties properties() { Properties p = new Properties(); String prefix = "mail." + protocol();
            p.setProperty("mail.transport.protocol", protocol()); p.setProperty(prefix + ".host", host); p.setProperty(prefix + ".port", Integer.toString(port));
            p.setProperty(prefix + ".connectiontimeout", Integer.toString(connectTimeout)); p.setProperty(prefix + ".timeout", Integer.toString(readTimeout)); p.setProperty(prefix + ".writetimeout", Integer.toString(writeTimeout));
            p.setProperty(prefix + ".auth", Boolean.toString(authenticated()));
            // Angus otherwise aborts at the first rejected RCPT and never exposes the valid-sent
            // addresses needed for the v1 PARTIAL result. This affects only recipient negotiation;
            // sendMessage itself is still invoked exactly once and is never retried.
            p.setProperty(prefix + ".sendpartial", "true");
            if (securityMode.equals("STARTTLS")) {
                p.setProperty("mail.smtp.starttls.enable", "true"); p.setProperty("mail.smtp.starttls.required", "true");
                p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            }
            if (securityMode.equals("SMTPS")) {
                p.setProperty("mail.smtps.ssl.enable", "true");
                p.setProperty("mail.smtps.ssl.checkserveridentity", "true");
            }
            return p; }
    }

    private record Payload(List<String> to, List<String> cc, List<String> bcc, String from, String replyTo, String subject,
                           String text, String html, Map<String,String> headers, List<Attachment> attachments, String correlationId) {
        static Payload from(Object raw, Settings s) {
            if (!(raw instanceof Map<?,?> map) || !"mail.send.v1".equals(string(map.get("version")))) throw invalid("Payload version must be mail.send.v1");
            var seen = new LinkedHashSet<String>();
            List<String> to = mailboxes(map.get("to"), seen); if (to.isEmpty()) throw invalid("At least one recipient is required");
            List<String> cc = mailboxes(map.get("cc"), seen), bcc = mailboxes(map.get("bcc"), seen);
            if (seen.size() > s.maxRecipients) throw invalid("Recipient limit exceeded");
            if (concat(to, cc, bcc).stream().anyMatch(address -> !s.profile.allowsAddress(s.profile.allowedRecipients(), address))) throw invalid("Recipient is not allowed by mail profile");
            String text=string(map.get("text")), html=string(map.get("html")); if (text.isBlank() && html.isBlank() || text.length()+html.length()>s.maxBodyChars) throw invalid("Body is missing or exceeds its limit");
            Map<String,String> headers = headers(map.get("headers")); if (headers.size()>s.maxHeaders || headers.entrySet().stream().mapToInt(e->e.getKey().length()+e.getValue().length()).sum()>s.maxHeaderChars) throw invalid("Header limit exceeded");
            int envelope = textBytes(string(map.get("subject"))) + textBytes(string(map.get("correlationId"))) + textBytes(string(map.get("from"))) + textBytes(string(map.get("replyTo")));
            for (String recipient : concat(to, cc, bcc)) envelope = add(envelope, textBytes(recipient));
            for (var header : headers.entrySet()) envelope = add(envelope, add(textBytes(header.getKey()), textBytes(header.getValue())));
            if (envelope > s.maxHeaderChars) throw invalid("Envelope metadata limit exceeded");
            List<Attachment> attachments = attachments(map.get("attachments"), s); return new Payload(to,cc,bcc,string(map.get("from")),string(map.get("replyTo")),string(map.get("subject")),text,html,headers,attachments,string(map.get("correlationId")));
        }
        static String string(Object o) {
            if (o == null) return "";
            if (o instanceof String value) return value;
            throw invalid("Expected string");
        }
        static List<String> strings(Object o) { if (o == null) return List.of(); if (!(o instanceof List<?> list)) throw invalid("Expected array"); List<String> r=new ArrayList<>(); for(Object v:list){String s=string(v);if(s.isBlank())throw invalid("Blank value");r.add(s);} return List.copyOf(r); }
        static List<String> mailboxes(Object raw, Set<String> seen) {
            if (raw == null) return List.of(); if (!(raw instanceof List<?> values) || values.size() > MAX_RECIPIENTS) throw invalid("Recipient limit exceeded");
            List<String> result = new ArrayList<>();
            int rawChars = 0; for (Object value : values) try {
                String source = string(value); if (source.isBlank() || source.length() > 320) throw invalid("Recipient is too long");
                rawChars = add(rawChars, source.length()); if (rawChars > 8192) throw invalid("Recipient limit exceeded");
                for (InternetAddress address : InternetAddress.parse(source, true)) {
                    if (address.isGroup() || address.getAddress() == null || address.getAddress().isBlank()) throw invalid("Groups and empty recipients are unsupported");
                    String addrSpec = address.getAddress();
                    int at = addrSpec.indexOf('@');
                    if (!addrSpec.chars().allMatch(c -> c <= 0x7f) || at <= 0 || at != addrSpec.lastIndexOf('@')
                            || at == addrSpec.length() - 1) throw invalid("Recipient must be an ASCII addr-spec");
                    String key = addrSpec.toLowerCase(Locale.ROOT);
                    if (seen.add(key)) result.add(address.toUnicodeString());
                }
            } catch (MailSendException failure) { throw failure; }
            catch (Exception failure) { throw invalid("Malformed recipient"); }
            return List.copyOf(result);
        }
        static Map<String,String> headers(Object o) { if(o==null)return Map.of();if(!(o instanceof Map<?,?> m))throw invalid("Expected headers object");Map<String,String> r=new LinkedHashMap<>();for(var e:m.entrySet()){String k=string(e.getKey()),v=string(e.getValue());if(!k.matches("(?i)X-[A-Za-z0-9-]{1,62}")||v.indexOf('\r')>=0||v.indexOf('\n')>=0)throw invalid("Unsafe header");r.put(k,v);}return Map.copyOf(r); }
        static int textBytes(String value) { if (value.length() > 8192) throw invalid("Metadata field exceeds its limit"); return value.getBytes(StandardCharsets.UTF_8).length; }
        static int add(int a, int b) { try { return Math.addExact(a,b); } catch(ArithmeticException overflow){ throw invalid("Envelope metadata limit exceeded"); } }
        static List<Attachment> attachments(Object o, Settings s) { if(o==null)return List.of();if(!(o instanceof List<?> l)||l.size()>s.maxAttachments)throw invalid("Attachment limit exceeded");List<Attachment> r=new ArrayList<>();int decoded=0, encoded=0;for(Object v:l){if(!(v instanceof Map<?,?> m))throw invalid("Invalid attachment");String name=string(m.get("name")),type=string(m.get("contentType"));Object content=m.get("content");byte[] b;int encodedSize;try { if(content instanceof byte[] raw){b=raw;encodedSize=4*((b.length+2)/3);}else if(content instanceof String base64){if(base64.length()>s.maxEncodedAttachmentBytes)throw invalid("Attachment limit exceeded");b=java.util.Base64.getDecoder().decode(base64);encodedSize=base64.length();}else throw invalid("Invalid attachment"); }catch(IllegalArgumentException invalid){throw invalid("Invalid attachment");}if(!name.matches("[A-Za-z0-9._ -]{1,128}")||!type.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")||b.length>s.maxAttachmentBytes)throw invalid("Invalid attachment");decoded=add(decoded,b.length);encoded=add(encoded,encodedSize);if(decoded>s.maxTotalAttachmentBytes||encoded>s.maxEncodedAttachmentBytes)throw invalid("Attachment limit exceeded");r.add(new Attachment(name,type,b.clone()));}return List.copyOf(r); }
        static List<String> concat(List<String>... values) { List<String> result=new ArrayList<>(); for(List<String> value:values)result.addAll(value);return result; }
        static MailSendException invalid(String message) { return new MailSendException(MailSendException.Code.INVALID_INPUT, message); }
    }
    private record Attachment(String name, String contentType, byte[] bytes) { }
}
