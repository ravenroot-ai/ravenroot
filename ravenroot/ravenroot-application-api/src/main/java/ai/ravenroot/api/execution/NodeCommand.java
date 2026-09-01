package ai.ravenroot.api.execution;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structural instruction delivered to one node, independent of payload, attributes and outcome.
 *
 * <p>Only {@link #PASSTHROUGH} has framework-reserved bypass semantics. Named commands are inert
 * application vocabulary until the target's trusted catalog descriptor admits them; their presence
 * grants no capability and never changes authorization.</p>
 * @param directive the reserved processing directive requested by the graph
 * @param name the optional application-defined command name; blank names are normalized to {@code null}
 */
public record NodeCommand(NodeDirective directive, String name) {
    public static final int MAX_NAME_LENGTH = 64;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public static final NodeCommand PROCESS = new NodeCommand(NodeDirective.PROCESS, "process");
    public static final NodeCommand PASSTHROUGH = new NodeCommand(NodeDirective.PASSTHROUGH, "passthrough");

/**
 * Rejects command shapes that would blur an operational directive with application vocabulary.
 */
    public NodeCommand {
        Objects.requireNonNull(directive, "directive");
        name = normalize(name);
        switch (directive) {
            case PROCESS -> {
                if (!"process".equals(name)) throw new IllegalArgumentException("PROCESS command must be 'process'");
            }
            case PASSTHROUGH -> {
                if (!"passthrough".equals(name)) {
                    throw new IllegalArgumentException("PASSTHROUGH command must be 'passthrough'");
                }
            }
            case APPLICATION -> {
                if ("process".equals(name) || "continue".equals(name) || "passthrough".equals(name)) {
                    throw new IllegalArgumentException("Application command uses a reserved name: " + name);
                }
            }
        }
    }

/**
 * Parses a GraphML/API value; {@code continue} is the compatibility spelling of PROCESS.
 * @param value GraphML or API text naming an operational directive or an application command
 * @return the normalized command represented by the supplied text
 */
    public static NodeCommand parse(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "process", "continue" -> PROCESS;
            case "passthrough" -> PASSTHROUGH;
            default -> application(normalized);
        };
    }

/**
 * Creates a non-reserved command that an admitted node type may interpret.
 *
 * @param name the application command label retained in the resulting command
 * @return an {@link NodeDirective#APPLICATION} command with that label
 */
    public static NodeCommand application(String name) {
        return new NodeCommand(NodeDirective.APPLICATION, name);
    }

/**
 * Distinguishes the engine-owned directives from application-defined commands.
 *
 * @return {@code true} when this command has a reserved non-application directive
 */
    public boolean operational() {
        return directive != NodeDirective.PASSTHROUGH;
    }

    private static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("Node command cannot be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Node command must match " + NAME + " and be at most "
                    + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    @Override public String toString() {
        return name;
    }
}
