package ai.ravenroot.server.assistant.provider;

import java.net.URI;
import java.util.List;

/**
 * A provider that answers from a script instead of a network.
 *
 * <h2>Why this lives in main sources rather than under {@code src/test}</h2>
 * <p>Because it is not scaffolding for a test — it is what this deployment shape actually runs today.
 * The composition root selects it with {@code RAVENROOT_ASSISTANT_PROVIDER=scripted}, exactly as it
 * selects any other provider, so one artifact does three jobs: the local development path, the
 * impoverished adapter that proves the port's conformance contract holds for something other than
 * Anthropic, and the vehicle an evaluation harness drives. A copy under {@code src/test} would do only
 * the third, and would be a second implementation of the port for the other two to drift from.</p>
 *
 * <p><b>There is no special case for it anywhere in {@code AssistantService}.</b> It implements
 * {@link AssistantProvider} and nothing else; the service cannot tell it apart from a network adapter,
 * which is the property that makes it a conformance vehicle rather than a bypass. It is also the only
 * adapter the composition root will wire without a consent store, because it makes no outbound call —
 * see {@code AssistantService#fromEnvironment}.</p>
 *
 * <h2>What it must never do</h2>
 * <p>It must not echo the author's prompt. A panel that appears to work by reflecting the question
 * back violates the assistant contract, and putting it behind a provider
 * interface would make it harder to see rather than acceptable. Its answers are its <em>own</em>
 * words, labelled as a scripted development provider, so an author cannot mistake one for a model.</p>
 */
public final class ScriptedAssistantProvider implements AssistantProvider {

    /** The id an operator sets to select this adapter. */
    public static final String ID = "scripted";

    /**
     * Not a real destination.
     *
     * <p>Reported so {@link AssistantProvider#describe()} has something to say, and deliberately a
     * non-routable scheme so that anything which mistakes it for a URL and tries to open it fails
     * immediately rather than resolving somewhere.</p>
     */
    private static final URI NOT_A_DESTINATION = URI.create("scripted:///no-network");

    private final List<Turn> script;
    private int next;

    /** The development default: one turn, saying plainly what it is. */
    public ScriptedAssistantProvider() {
        this(List.of(new Turn.Answer(
                "This deployment is running Ravenroot's scripted assistant provider, so no model is "
                        + "connected. The panel, the transport, the authorization path and the tool "
                        + "loop are all real; the words are not a model's. Set "
                        + "RAVENROOT_ASSISTANT_PROVIDER to a real provider to connect one.",
                ID, false)));
    }

    public ScriptedAssistantProvider(List<Turn> script) {
        this.script = List.copyOf(script);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public URI endpoint() {
        return NOT_A_DESTINATION;
    }

    /**
     * This adapter opens nothing. It is the reason {@link AssistantProvider#egresses()} exists as a
     * declared property rather than a list of exempt provider names: the exemption belongs to adapters
     * that do not reach the network, and a second one inherits it by saying so.
     */
    @Override
    public boolean egresses() {
        return false;
    }

    @Override
    public Turn complete(Request request) throws AssistantProviderException {
        if (next >= script.size()) {
            // Running off the end is a named failure rather than a repeat of the last turn: silently
            // replaying would make an exhausted script look like a model that kept answering.
            throw new AssistantProviderException(
                    ai.ravenroot.server.assistant.AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                    "the scripted provider has no further turn", null);
        }
        return script.get(next++);
    }
}
