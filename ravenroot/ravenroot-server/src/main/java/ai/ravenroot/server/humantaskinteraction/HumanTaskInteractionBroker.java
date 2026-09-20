package ai.ravenroot.server.humantaskinteraction;

import ai.ravenroot.api.persistence.HumanTaskAttentionItem;
import ai.ravenroot.api.persistence.HumanTaskAttentionLocator;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskPresentationKind;
import ai.ravenroot.api.persistence.HumanTaskSettlement;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Issues and consumes bounded, expiring, single-task interaction capabilities. */
public final class HumanTaskInteractionBroker {
    private static final int CAPABILITY_VERSION = 1;
    private static final int MAX_TOKEN_BYTES = 32_768;
    private final HumanTaskInteractionConfiguration configuration;
    private final HumanTaskService tasks;
    private final Clock clock;

    public record Launch(String capability, UUID capabilityId, Instant expiresAt,
                         HumanTaskInteractionConfiguration.Profile profile,
                         HumanTaskAttentionItem task,
                         ai.ravenroot.api.persistence.HumanTaskResponseSchema responseSchema,
                         String returnOrigin) { }

    public HumanTaskInteractionBroker(HumanTaskInteractionConfiguration configuration,
                                      HumanTaskService tasks, Clock clock) {
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public Launch issue(RequestContext context, UUID taskId, long generation, String returnOrigin) {
        java.util.Objects.requireNonNull(context, "context");
        requireOrigin(returnOrigin);
        HumanTaskService.InteractionTask interaction = tasks.interactionTask(context,
                new HumanTaskAttentionLocator(taskId, generation))
                .orElseThrow(() -> new CapabilityFailure(Code.UNAVAILABLE));
        HumanTaskAttentionItem task = interaction.attention();
        var presentation = task.interactionPresentation();
        if (presentation.kind() != HumanTaskPresentationKind.CUSTOM
                && presentation.kind() != HumanTaskPresentationKind.EXTERNAL) {
            throw new CapabilityFailure(Code.UNAVAILABLE);
        }
        var profile = configuration.requireProfile(presentation.profileId(),
                presentation.profileVersion(), presentation.kind());
        Instant now = clock.instant();
        Instant expiresAt = now.plus(configuration.capabilityTtl());
        if (task.expiresAt().isBefore(expiresAt)) expiresAt = task.expiresAt();
        if (!now.isBefore(expiresAt)) throw new CapabilityFailure(Code.EXPIRED);
        UUID capabilityId = UUID.randomUUID();
        Claims claims = new Claims(capabilityId, expiresAt, context.tenantId(), task.taskId(),
                task.generation(), profile.key().id(), profile.key().version(), profile.kind(),
                responseSchemaDigest(interaction.responseSchema()), Set.copyOf(task.availableActions()), returnOrigin,
                context.subject(), context.principalType(), context.issuer(), context.roles(), context.scopes());
        return new Launch(sign(claims), capabilityId, expiresAt, profile, task,
                interaction.responseSchema(), returnOrigin);
    }

    public HumanTaskResult complete(String capability, String origin, byte[] exactBody,
                                    String providerSignature, HumanTaskSettlement settlement) {
        Claims claims = verify(capability);
        if (tasks.interactionCapabilityRevoked(claims.tenantId(), claims.capabilityId(), clock.instant())) {
            throw new CapabilityFailure(Code.REVOKED);
        }
        if (!clock.instant().isBefore(claims.expiresAt())) throw new CapabilityFailure(Code.EXPIRED);
        var profile = configuration.requireProfile(claims.profileId(), claims.profileVersion(),
                claims.kind() == HumanTaskInteractionConfiguration.Kind.CUSTOM
                        ? HumanTaskPresentationKind.CUSTOM : HumanTaskPresentationKind.EXTERNAL);
        String expectedOrigin = claims.kind() == HumanTaskInteractionConfiguration.Kind.CUSTOM
                ? claims.returnOrigin() : profile.origin().toString();
        if (!expectedOrigin.equals(origin)) throw new CapabilityFailure(Code.ORIGIN_REFUSED);
        if (claims.kind() == HumanTaskInteractionConfiguration.Kind.EXTERNAL) {
            if (providerSignature == null || !constantTime(signature(profile.completionSecret(), exactBody),
                    providerSignature)) {
                throw new CapabilityFailure(Code.SIGNATURE_REFUSED);
            }
        } else if (providerSignature != null) {
            throw new CapabilityFailure(Code.SIGNATURE_REFUSED);
        }
        if (!claims.actions().contains(settlement.action())) {
            throw new CapabilityFailure(Code.ACTION_REFUSED);
        }
        RequestContext context = new RequestContext("human-task-capability:" + claims.capabilityId(),
                claims.subject(), claims.principalType(), claims.issuer(), claims.tenantId(),
                claims.roles(), claims.scopes());
        return tasks.settle(context, claims.taskId(), claims.generation(), settlement);
    }

    public void revoke(RequestContext context, String capability, UUID taskId, long generation) {
        Claims claims = verify(capability);
        if (!claims.tenantId().equals(context.tenantId()) || !claims.taskId().equals(taskId)
                || claims.generation() != generation) throw new CapabilityFailure(Code.UNAVAILABLE);
        boolean owner = claims.subject().equals(context.subject()) && claims.issuer().equals(context.issuer());
        boolean admin = context.roles().contains(Role.TENANT_ADMIN) || context.roles().contains(Role.PLATFORM_ADMIN);
        if (!owner && !admin) throw new CapabilityFailure(Code.UNAVAILABLE);
        Instant now = clock.instant();
        if (!now.isBefore(claims.expiresAt())) throw new CapabilityFailure(Code.EXPIRED);
        tasks.revokeInteractionCapability(context.tenantId(),
                new ai.ravenroot.api.persistence.HumanTaskInteractionRevocation(
                        claims.capabilityId(), claims.taskId(), claims.generation(), now, claims.expiresAt()));
    }

    public int maxCompletionBytes() { return configuration.maxCompletionBytes(); }

    public enum Code { MALFORMED, EXPIRED, REVOKED, ORIGIN_REFUSED, SIGNATURE_REFUSED,
        ACTION_REFUSED, UNAVAILABLE }

    public static final class CapabilityFailure extends RuntimeException {
        private final Code code;
        public CapabilityFailure(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }

    private record Claims(UUID capabilityId, Instant expiresAt, String tenantId, UUID taskId,
                          long generation, String profileId, int profileVersion,
                          HumanTaskInteractionConfiguration.Kind kind, String schemaDigest,
                          Set<HumanTaskConfirmationAction> actions, String returnOrigin,
                          String subject, PrincipalType principalType, String issuer,
                          Set<Role> roles, Set<String> scopes) { }

    private String sign(Claims claims) {
        byte[] body = encode(claims);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(configuration.capabilitySecret(), body));
    }

    private Claims verify(String token) {
        try {
            if (token == null || token.length() > MAX_TOKEN_BYTES) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] body = Base64.getUrlDecoder().decode(parts[0]);
            byte[] supplied = Base64.getUrlDecoder().decode(parts[1]);
            if (body.length > MAX_TOKEN_BYTES || !MessageDigest.isEqual(
                    hmac(configuration.capabilitySecret(), body), supplied)) {
                throw new IllegalArgumentException();
            }
            Claims claims = decode(body);
            configuration.requireProfile(claims.profileId(), claims.profileVersion(),
                    claims.kind() == HumanTaskInteractionConfiguration.Kind.CUSTOM
                            ? HumanTaskPresentationKind.CUSTOM : HumanTaskPresentationKind.EXTERNAL);
            return claims;
        } catch (CapabilityFailure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new CapabilityFailure(Code.MALFORMED);
        }
    }

    private static byte[] encode(Claims claims) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(CAPABILITY_VERSION);
            write(output, claims.capabilityId().toString());
            output.writeLong(claims.expiresAt().toEpochMilli());
            write(output, claims.tenantId());
            write(output, claims.taskId().toString());
            output.writeLong(claims.generation());
            write(output, claims.profileId());
            output.writeInt(claims.profileVersion());
            write(output, claims.kind().name());
            write(output, claims.schemaDigest());
            writeEnums(output, claims.actions());
            write(output, claims.returnOrigin());
            write(output, claims.subject());
            write(output, claims.principalType().name());
            write(output, claims.issuer());
            writeEnums(output, claims.roles());
            writeStrings(output, claims.scopes());
            output.flush();
            if (bytes.size() > MAX_TOKEN_BYTES) throw new IllegalArgumentException("capability is too large");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot encode in-memory capability", impossible);
        }
    }

    private static Claims decode(byte[] body) {
        try {
            var input = new DataInputStream(new ByteArrayInputStream(body));
            if (input.readInt() != CAPABILITY_VERSION) throw new IllegalArgumentException();
            UUID capabilityId = UUID.fromString(read(input));
            Instant expiresAt = Instant.ofEpochMilli(input.readLong());
            String tenant = read(input);
            UUID taskId = UUID.fromString(read(input));
            long generation = input.readLong();
            if (generation < 1) throw new IllegalArgumentException();
            String profileId = read(input);
            int profileVersion = input.readInt();
            var kind = HumanTaskInteractionConfiguration.Kind.valueOf(read(input));
            String schemaDigest = read(input);
            Set<HumanTaskConfirmationAction> actions = readEnums(input, HumanTaskConfirmationAction.class);
            String returnOrigin = read(input);
            requireOrigin(returnOrigin);
            String subject = read(input);
            PrincipalType principalType = PrincipalType.valueOf(read(input));
            String issuer = read(input);
            Set<Role> roles = readEnums(input, Role.class);
            Set<String> scopes = readStrings(input);
            if (input.read() != -1 || actions.isEmpty()) throw new IllegalArgumentException();
            return new Claims(capabilityId, expiresAt, tenant, taskId, generation, profileId,
                    profileVersion, kind, schemaDigest, actions, returnOrigin, subject,
                    principalType, issuer, roles, scopes);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("invalid capability", invalid);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8_192) throw new IllegalArgumentException("capability field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 8_192) throw new IllegalArgumentException();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IllegalArgumentException();
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static <E extends Enum<E>> void writeEnums(DataOutputStream output, Set<E> values)
            throws IOException {
        List<String> names = values.stream().map(Enum::name).sorted().toList();
        writeStrings(output, new HashSet<>(names));
    }

    private static void writeStrings(DataOutputStream output, Set<String> values) throws IOException {
        if (values.size() > 64) throw new IllegalArgumentException("too many capability values");
        List<String> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        output.writeInt(sorted.size());
        for (String value : sorted) write(output, value);
    }

    private static <E extends Enum<E>> Set<E> readEnums(DataInputStream input, Class<E> type)
            throws IOException {
        Set<String> names = readStrings(input);
        Set<E> result = EnumSet.noneOf(type);
        for (String name : names) result.add(Enum.valueOf(type, name));
        return Set.copyOf(result);
    }

    private static Set<String> readStrings(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 64) throw new IllegalArgumentException();
        Set<String> result = new HashSet<>();
        for (int index = 0; index < count; index++) {
            if (!result.add(read(input))) throw new IllegalArgumentException();
        }
        return Set.copyOf(result);
    }

    private static byte[] hmac(byte[] secret, byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 unavailable", unavailable);
        }
    }

    private static String signature(byte[] secret, byte[] body) {
        return "sha256=" + java.util.HexFormat.of().formatHex(hmac(secret, body));
    }

    private static boolean constantTime(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static String responseSchemaDigest(
            ai.ravenroot.api.persistence.HumanTaskResponseSchema schema) {
        String canonical = schema.contentType() + "\n" + schema.schema() + "\n"
                + schema.schemaVersion() + "\n" + schema.kind() + "\n" + schema.maxBytes();
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireOrigin(String value) {
        try {
            var origin = java.net.URI.create(value);
            if (origin.getScheme() == null || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || origin.getRawPath() != null && !origin.getRawPath().isEmpty()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new CapabilityFailure(Code.ORIGIN_REFUSED);
        }
    }
}
