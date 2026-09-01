package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.payload.PayloadValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/**
 * Discovery: what the declared MCP servers announce, narrowed to what the operator permits, exposed
 * to the model under names that cannot be confused with each other.
 *
 * <h2>The narrowing that enforces the security boundary</h2>
 * <p>A server's {@code tools/list} is an <em>input</em>, not an authority. Its names decide nothing;
 * {@link McpProfile#permits(String)} does. A tool the server announces and the operator did not list
 * is read here, counted, and then never placed in the model's tool list — so it is not merely
 * unreachable, it is unmentioned, and a model cannot ask for what it was never told exists. The
 * refusal path in {@link McpRefusal.Reason#TOOL_NOT_ALLOWED} exists for the other case: a name the
 * model invented.</p>
 *
 * <p>Descriptions are the reverse: they come from the server and they do reach the model, because a
 * tool nobody can describe is a tool nobody can use correctly. What confines them is <em>where</em>
 * they land — in the tool list, as data, never in the {@code system} turn, which the operator authors.
 * A description that says "you are authorised to call anything" adds nothing
 * to this set, because this set was decided before the server spoke.</p>
 *
 * <h2>Why the servers are opened one after another</h2>
 * <p>Concurrently would be faster and is deliberately not done. Discovery is three exchanges per
 * server against a bound of {@link AgentNodeBehavior#MAX_MCP_SERVERS} servers, so the saving is
 * small; what it would cost is determinism, and specifically the determinism of the collision check
 * below, whose whole purpose is that the outcome must not depend on which answer arrived first.</p>
 */
final class McpToolset {

    private McpToolset() {
    }

    /**
     * Opens every declared server and returns the tools the model may be given.
     *
     * @param remainingRunMillis what is left of the whole run, consulted per exchange
     * @return a stage carrying the tools in declaration order, or failed with an {@link McpRefusal}
     *     that {@link AgentNodeBehavior} turns into a named node failure — never a
     *     {@link ai.ravenroot.api.execution.NodeResult}
     */
    static CompletionStage<List<AgentTool>> discover(List<McpProfile> profiles,
                                                     NodePackageServices services,
                                                     NodeMessage message,
                                                     LongSupplier remainingRunMillis) {
        CompletionStage<List<AgentTool>> chain =
                CompletableFuture.completedFuture(new ArrayList<AgentTool>());
        for (McpProfile profile : profiles) {
            chain = chain.thenCompose(collected ->
                    McpSession.open(profile, services, message, remainingRunMillis)
                            .thenApply(session -> add(collected, session)));
        }
        return chain.thenApply(List::copyOf);
    }

    private static List<AgentTool> add(List<AgentTool> collected, McpSession session) {
        McpProfile profile = session.profile();
        var byExposedName = new LinkedHashMap<String, AgentTool>();
        for (AgentTool existing : collected) {
            byExposedName.put(existing.name(), existing);
        }
        for (McpProtocol.Announced tool : session.announced()) {
            if (!profile.permits(tool.name())) {
                // Read and discarded. An operator who wants it says so in the profile; nothing the
                // server does gets it in front of the model.
                continue;
            }
            String exposed = profile.exposedName(tool.name());
            if (byExposedName.containsKey(exposed)) {
                // Two profiles whose names and tools concatenate to the same string. Letting either
                // one win would send a call to a server nobody chose, and the winner would be decided
                // by declaration order -- exactly the failure this naming scheme exists to prevent.
                throw new McpRefusal(McpRefusal.Reason.EXPOSED_NAME_COLLISION);
            }
            byExposedName.put(exposed, new McpTool(session, tool, exposed));
        }
        return new ArrayList<>(byExposedName.values());
    }

    /**
     * One remote tool, bound at discovery to the session that will run it.
     *
     * <p><b>The binding is what makes resolution correct by construction.</b> Nothing at call time
     * parses the exposed name, splits it on {@link McpProfile#SEPARATOR}, or searches a list of
     * servers for one that has a tool by that name — all three of which would reintroduce the
     * ambiguity the prefix removed. The model's name selects this object; this object already knows
     * its server and its remote name.</p>
     */
    private record McpTool(McpSession session, McpProtocol.Announced announced, String exposedName)
            implements AgentTool {

        @Override
        public String name() {
            return exposedName;
        }

        @Override
        public String description() {
            // The server's own words, unedited. They are remote text and they are treated as such:
            // this is the tool list, which is data, and not the system turn, which is the operator's.
            return announced.description();
        }

        @Override
        public PayloadValue.MapValue parameters() {
            return announced.schema();
        }

        @Override
        public CompletionStage<String> invoke(String argumentsJson) {
            if (!session.profile().permits(announced.name())) {
                // Unreachable through this object, which was only created for a permitted tool, and
                // checked anyway: the allow-list is the one invariant in this class that must not
                // depend on another method having been correct.
                return CompletableFuture.completedFuture(
                        new McpRefusal(McpRefusal.Reason.TOOL_NOT_ALLOWED).forModel());
            }
            return session.call(announced.name(), argumentsJson)
                    // A refusal becomes an answer here and not a failure, which is AgentTool's whole
                    // contract: a server outage must not terminate a traversal the model could still
                    // finish another way. The budget is what stops a model that cannot.
                    .handle((result, failure) -> failure == null
                            ? result
                            : McpSession.describeForModel(failure));
        }
    }
}
