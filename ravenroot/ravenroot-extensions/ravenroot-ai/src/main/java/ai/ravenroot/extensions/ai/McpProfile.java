package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One operator-owned MCP server, named by a graph and never described by one.
 *
 * <p>The same split {@link LlmProfile} makes, for the same reason and with one additional security
 * property: <b>the list of callable tools is part of the operator's
 * declaration, not part of what the server announces.</b></p>
 *
 * <h2>Why the allow-list is here and not derived from {@code tools/list}</h2>
 * <p>An MCP server answers {@code tools/list} with names, JSON schemas and <em>descriptions</em>, and
 * those descriptions are placed in front of a model as the text that tells it what a tool does. That
 * is remote text becoming instruction. If the set of callable tools were whatever the server last
 * announced, a server — or anything that has taken it over — could widen its own reach between two
 * executions of an unchanged graph, and nothing in this repository would record that it had.</p>
 *
 * <p>With the allow-list on the profile, a tool that appears after the operator wrote the profile is
 * announced, read, and then not exposed to the model at all. The operator's file is the authority and
 * the server is the input. Descriptions still arrive from the server, and still reach the model —
 * there is no way to run a tool the model cannot be told about — but they arrive as members of the
 * tool list and never as part of the operator-authored {@code system} turn.</p>
 *
 * <h2>The name the model sees is not the name the server uses</h2>
 * <p>Two servers may both expose {@code search}. Handing a model two tools of the same name makes the
 * resolution of a call ambiguous, and the natural way to break the tie — declaration order — is the
 * way a call reaches the wrong server with nobody noticing. So every tool is exposed as
 * {@code <profile>__<tool>} and resolution is a map lookup built at discovery, never a scan. See
 * {@link #exposedName(String)}.</p>
 *
 * @param name the profile name a graph writes into the node's {@code mcpServers} property
 * @param endpoint absolute MCP Streamable HTTP endpoint
 * @param credentialBinding binding the runtime uses to place the key, or empty for an
 *     unauthenticated server such as one on loopback
 * @param timeoutMs deadline for one JSON-RPC exchange with this server, in milliseconds
 * @param maxResponseBytes ceiling this bundle reads one of its responses against
 * @param maxConcurrency runs that may hold this server at once, per tenant
 * @param allowedTools the tools an operator permits, which is a subset of what the server announces
 *     and never the other way round
 */
public record McpProfile(String name, URI endpoint,
                         Optional<OutboundCredentialBinding> credentialBinding,
                         int timeoutMs, int maxResponseBytes, int maxConcurrency,
                         Set<String> allowedTools) {

    /**
     * What separates a profile name from a tool name in the name the model is given.
     *
     * <p>Two characters and not one, so that a single underscore — common inside a tool name — does
     * not read as the boundary. Nothing ever parses an exposed name back into its two halves, which
     * is what makes an ambiguous split harmless: {@link McpToolset} keeps a map from exposed name to
     * the session and the remote name, so resolution never has to guess where the boundary was.</p>
     */
    public static final String SEPARATOR = "__";

    /** Longest response this bundle will ever read from an MCP server, whatever a profile asks for. */
    public static final int HARD_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** Longest deadline this bundle will ever ask the managed channel for. */
    public static final int HARD_MAX_TIMEOUT_MS = 600_000;
    /** Most tools one profile may permit. */
    public static final int MAX_ALLOWED_TOOLS = 64;

    /**
     * What an exposed name must look like.
     *
     * <p>The intersection of what OpenAI-compatible endpoints accept for a function name and what is
     * unambiguous here. It excludes {@code .}, which {@link EnvironmentLlmProfileResolver}'s name
     * mask does allow — so a profile named {@code corp.search} is refused <b>at profile read</b>,
     * in front of the operator who can still rename it, rather than at the first call in front of a
     * graph author who cannot.</p>
     */
    private static final String EXPOSED_NAME_PATTERN = "[A-Za-z0-9_-]{1,64}";

    public McpProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(credentialBinding, "credentialBinding");
        Objects.requireNonNull(allowedTools, "allowedTools");
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("endpoint");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("endpoint");
        }
        // Identical to LlmProfile's rule and stated again rather than referenced: the managed channel
        // already refuses to place a credential on a plaintext origin, and repeating it here turns
        // "your call failed" into "your profile is wrong", while the operator can still see which
        // profile they wrote.
        if (credentialBinding.isPresent() && !scheme.equals("https")) {
            throw new IllegalArgumentException("credentialBinding");
        }
        if (timeoutMs < 1 || timeoutMs > HARD_MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeoutMs");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > HARD_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("maxResponseBytes");
        }
        if (maxConcurrency < 1 || maxConcurrency > 256) {
            throw new IllegalArgumentException("maxConcurrency");
        }
        // An empty allow-list is refused rather than read as "everything". The whole point of the
        // list is that omitting it must never be the permissive reading.
        if (allowedTools.isEmpty() || allowedTools.size() > MAX_ALLOWED_TOOLS) {
            throw new IllegalArgumentException("allowedTools");
        }
        var copy = new LinkedHashSet<String>(allowedTools.size());
        for (String tool : allowedTools) {
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException("allowedTools");
            }
            String exposed = name + SEPARATOR + tool;
            if (!exposed.matches(EXPOSED_NAME_PATTERN)) {
                throw new IllegalArgumentException("allowedTools");
            }
            copy.add(tool);
        }
        allowedTools = Set.copyOf(copy);
    }

    /**
     * The name the model is given for {@code tool} on this server.
     *
     * <p>Cannot collide with a built-in tool by construction: every exposed name contains
     * {@link #SEPARATOR} and {@link LoadSkillTool#NAME} does not. The collision that <em>is</em>
     * possible — two profiles whose names and tools concatenate to the same string — is detected at
     * discovery by {@link McpToolset}, which refuses rather than letting the second silently win.</p>
     *
     * @param tool the name as the server announced it
     * @return the unambiguous name placed in the model's tool list
     */
    public String exposedName(String tool) {
        return name + SEPARATOR + tool;
    }

    /** Whether the operator permitted {@code tool}, which is the only question that decides it. */
    public boolean permits(String tool) {
        return allowedTools.contains(tool);
    }
}
