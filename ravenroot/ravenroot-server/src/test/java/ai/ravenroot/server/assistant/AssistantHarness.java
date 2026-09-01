package ai.ravenroot.server.assistant;

import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import ai.ravenroot.server.assistant.provider.AssistantProviderException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assistant doubles with no network, environment or clock.
 *
 * <p>Lives in the production package so it can build an {@link AssistantService} around a scripted
 * provider without widening any production constructor for the sake of a test.</p>
 */
public final class AssistantHarness {

    private AssistantHarness() {
    }

    /**
     * The canary. Distinctive on purpose: a grep for it cannot collide with anything a server
     * legitimately writes, so a hit is a leak rather than a coincidence.
     */
    public static final String PLANTED_CREDENTIAL = "sk-ant-planted-CANARY-9f3b2a7c-do-not-log";

    /** A fully ready configuration whose credential is the canary the leak test greps for. */
    public static AssistantConfiguration readyConfiguration() {
        return new AssistantConfiguration(true, AssistantConfiguration.ANTHROPIC_PROVIDER,
                AssistantConfiguration.ANTHROPIC_ENDPOINT, "claude-opus-5",
                AssistantCredential.ofNullable(PLANTED_CREDENTIAL),
                OutboundHttpPolicy.fromCommaSeparatedHosts("api.anthropic.com"),
                Duration.ofSeconds(5), 4096, 4);
    }

    /** The service an operator has switched off. Answers 404, which the panel reads as inert. */
    public static AssistantService disabledService() {
        return new AssistantService(AssistantConfiguration.fromEnvironment(
                Map.of(AssistantConfiguration.ENABLED_VARIABLE, "false")), null);
    }

    /** The service a given operator environment produces, with no provider adapter wired. */
    public static AssistantService serviceFrom(Map<String, String> environment) {
        return new AssistantService(AssistantConfiguration.fromEnvironment(environment), null);
    }

    /**
     * A provider that answers from a scripted queue and records what it was asked.
     *
     * <p>Records the requests rather than only a count, because two properties under test are about
     * what did <em>not</em> happen on a second call: that a denied read stops the turn, and that no
     * request was composed at all while the panel was inert.</p>
     */
    public static final class ScriptedProviderView {
        private final List<Object> script = new ArrayList<>();
        private final List<AssistantProvider.Request> received = new ArrayList<>();
        private int calls;

        public ScriptedProviderView answering(String text) {
            script.add(new AssistantProvider.Turn.Answer(text, "claude-opus-5", false));
            return this;
        }

        public ScriptedProviderView truncating(String text) {
            script.add(new AssistantProvider.Turn.Answer(text, "claude-opus-5", true));
            return this;
        }

        public ScriptedProviderView refusing() {
            script.add(new AssistantProvider.Turn.Refused("cyber"));
            return this;
        }

        public ScriptedProviderView failing() {
            script.add(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE);
            return this;
        }

        public ScriptedProviderView callingTool(String toolName) {
            return callingTool(toolName, "{}");
        }

        public ScriptedProviderView callingTool(String toolName, String inputJson) {
            var use = new AssistantProvider.Content.ToolUse("toolu_" + toolName, toolName, inputJson);
            script.add(new AssistantProvider.Turn.ToolCalls(List.of(use), "claude-opus-5", List.of(use)));
            return this;
        }

        public ScriptedProviderView callingTools(AssistantProvider.Content.ToolUse... uses) {
            var calls = List.of(uses);
            script.add(new AssistantProvider.Turn.ToolCalls(calls, "claude-opus-5",
                    new ArrayList<>(calls)));
            return this;
        }

        public int callCount() {
            return calls;
        }

        public List<AssistantProvider.Request> received() {
            return List.copyOf(received);
        }

        /** The service under test: the real one, around this scripted provider. */
        public AssistantService service() {
            return new AssistantService(readyConfiguration(), provider());
        }

        /**
         * The same service, around a consent register.
         *
         * <p>Separate from {@link #service()} rather than replacing it: the no-consent overload is what
         * non-egressing tests exercise, and it stays legal here for the reason
         * {@code AssistantService}'s canonical constructor states — this provider declares it does not
         * egress, so no consent question arises about bytes that never leave the JVM.</p>
         */
        public AssistantService service(AssistantConsentStore consent) {
            return new AssistantService(readyConfiguration(), provider(), consent);
        }

        public AssistantProvider provider() {
            return new AssistantProvider() {
                @Override
                public String id() {
                    return "anthropic";
                }

                @Override
                public URI endpoint() {
                    return AssistantConfiguration.ANTHROPIC_ENDPOINT;
                }

                /**
                 * Answers from a script; opens nothing. Declaring it truthfully is what lets the
                 * service be built around it without a consent store — the same exemption the
                 * scripted adapter gets, for the same declared reason.
                 */
                @Override
                public boolean egresses() {
                    return false;
                }

                @Override
                public Turn complete(Request request) throws AssistantProviderException {
                    received.add(request);
                    int index = calls++;
                    if (index >= script.size()) {
                        throw new AssistantProviderException(
                                AssistantOutcome.Reason.PROVIDER_UNAVAILABLE, "script exhausted", null);
                    }
                    Object scripted = script.get(index);
                    if (scripted instanceof AssistantOutcome.Reason reason) {
                        throw new AssistantProviderException(reason);
                    }
                    return (Turn) scripted;
                }
            };
        }
    }
}
