package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.persistence.AgentBudgetVector;

import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** Finite operator policy and pinned model rate card for one packaged runtime instance. */
public record AgentAuthorityBudgetPolicy(String runtimeInstanceId, long bootEpoch,
                                         String policyVersion, String rateCardVersion,
                                         String currency, Duration rootLifetime,
                                         AgentBudgetVector rootMaxima,
                                         long maximumInputTokensPerTurn,
                                         long maximumOutputTokensPerTurn,
                                         long inputTokenRateMicros,
                                         long outputTokenRateMicros,
                                         Set<String> dataScopes,
                                         Set<String> authorityScopes) {
    private static final Pattern IDENTITY_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private static final int MAX_IDENTITY_LENGTH = 128;
    private static final int MAX_SCOPE_LENGTH = 256;
    private static final int MAX_SCOPES = 256;
    static final String INTERNAL_ROOT_SCOPE = "runtime:root";

    public AgentAuthorityBudgetPolicy {
        identity("runtimeInstanceId", runtimeInstanceId);
        identity("policyVersion", policyVersion);
        identity("rateCardVersion", rateCardVersion);
        if (bootEpoch < 0 || currency == null || !currency.matches("[A-Z]{3}")
                || rootLifetime == null || rootLifetime.isZero() || rootLifetime.isNegative()
                || rootMaxima == null || maximumInputTokensPerTurn <= 0
                || maximumOutputTokensPerTurn <= 0 || inputTokenRateMicros < 0
                || outputTokenRateMicros < 0) {
            throw new IllegalArgumentException("agent authority budget policy is invalid");
        }
        dataScopes = scopes("dataScopes", dataScopes);
        authorityScopes = authorityScopes(authorityScopes);
    }

    /** Exact finite deadline; diagnostic deliberately excludes values and raw temporal causes. */
    public Instant rootDeadlineAt(Instant now) {
        try {
            return now.plus(rootLifetime);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("rootLifetime cannot form a finite deadline (RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS)");
        }
    }

    /** Versioned policy equality proof, using configured scopes before the internal root union. */
    public String policyFingerprint() {
        return fingerprint("ravenroot.agent-authority-policy", out -> {
            text(out, policyVersion);
            out.writeLong(rootLifetime.getSeconds()); out.writeInt(rootLifetime.getNano());
            out.writeLong(rootMaxima.turns()); out.writeLong(rootMaxima.inputTokens());
            out.writeLong(rootMaxima.outputTokens()); out.writeLong(rootMaxima.elapsedMillis());
            out.writeLong(rootMaxima.costMicros()); out.writeLong(rootMaxima.toolCalls());
            out.writeLong(rootMaxima.delegationDepth()); out.writeLong(rootMaxima.teamCumulative());
            out.writeLong(rootMaxima.teamActive());
            out.writeLong(maximumInputTokensPerTurn); out.writeLong(maximumOutputTokensPerTurn);
            scopes(out, dataScopes); scopes(out, authorityScopes);
        });
    }

    /** Versioned economic equality proof; runtime identity and boot diagnostics are excluded. */
    public String rateCardFingerprint() {
        return fingerprint("ravenroot.agent-authority-rate-card", out -> {
            text(out, rateCardVersion); text(out, currency);
            out.writeLong(inputTokenRateMicros); out.writeLong(outputTokenRateMicros);
        });
    }

    private static String fingerprint(String domain, Encoding encoding) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            text(out, domain); out.writeInt(1); encoding.write(out); out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("agent fingerprint encoding is unavailable");
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static void scopes(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values.stream().sorted().toList()) text(out, value);
    }

    @FunctionalInterface private interface Encoding { void write(DataOutputStream out) throws IOException; }

    private static void identity(String name, String value) {
        if (value == null || value.length() > MAX_IDENTITY_LENGTH || !IDENTITY_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an identity token of 1..128 characters");
        }
    }

    private static Set<String> scopes(String name, Set<String> values) {
        if (values == null) return Set.of();
        if (values.size() > MAX_SCOPES) {
            throw new IllegalArgumentException(name + " must contain at most 256 unique scope tokens");
        }
        for (String value : values) {
            if (value == null || value.length() > MAX_SCOPE_LENGTH || !SCOPE_TOKEN.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " contains an invalid scope token");
            }
        }
        return Set.copyOf(values);
    }

    private static Set<String> authorityScopes(Set<String> values) {
        Set<String> validated = scopes("authorityScopes", values);
        if (validated.size() == MAX_SCOPES && !validated.contains(INTERNAL_ROOT_SCOPE)) {
            throw new IllegalArgumentException(
                    "authorityScopes must contain at most 256 effective root scope tokens");
        }
        return validated;
    }
}
