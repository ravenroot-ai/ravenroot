package ai.ravenroot.server.credential;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped composition root composes the credential store and hands it over.
 *
 * <h2>Why the composition root is scanned</h2>
 * <p>Every behavioural test in this package would keep passing if {@code RavenrootServerMain} stopped
 * composing {@link SqliteUserCredentialStore}. Removing both wirings — the resolver chain and the
 * constructor argument — leaves the rest of the server module green at 587 tests while
 * {@code POST /v1/credentials} answers
 * 404, so nothing can be entered from the interface at all; {@code credentialAdmission} falls back to
 * {@link CredentialAdmission#permissive()}, whose {@code isOwnedBy} is {@code true} by construction,
 * so ownership admission is disabled in production; a stored credential also does not resolve.</p>
 *
 * <p>The panel, text, route and flow are all built and covered by tests; the analogous assistant
 * composition is pinned in
 * {@code AssistantCompositionTest#theShippedCompositionRootWiresTheAssistantThroughThisClass}, whose
 * Javadoc states the reason in the same words. This test uses that shape plus the fourth assertion
 * credential composition needs: <b>ordering</b>.</p>
 *
 * <h2>A source scan, deliberately</h2>
 * <p>Same reasoning {@code DeploymentProbeWiringTest} gives for the deployment manifests: the property
 * is "this line exists", and a behavioural test cannot state it about a composition root whose
 * {@code run} method blocks on a latch and reads {@code System.getenv()}. A scan is what can.</p>
 */
class CredentialCompositionWiringTest {

    private static final Path MAIN =
            Path.of("src/main/java/ai/ravenroot/server/RavenrootServerMain.java")
                    .toAbsolutePath().normalize();

    /**
     * <b>The store is opened, handed to the server, and closed.</b>
     *
     * <p>Three assertions rather than one because <em>two</em>
     * independent wirings and either one alone is a shipped defect: without the constructor argument
     * the route is a 404, and without the chain a stored credential never resolves.</p>
     *
     * <p><b>Mutation proof, executed.</b> Replace the chain at the composition site with
     * {@code new ProviderCredentialResolver(new EnvironmentCredentialResolver())} and this reds on the
     * fourth assertion; replace the constructor argument with {@code null} and it reds on the second.
     * Applying both mutations reds both assertions.</p>
     */
    @Test
    void theShippedCompositionRootOpensTheStoreHandsItOverAndClosesIt() throws Exception {
        String source = Files.readString(MAIN);

        assertTrue(source.contains(
                        "ai.ravenroot.server.credential.SqliteUserCredentialStore.fromEnvironment(System.getenv())"),
                () -> MAIN + " must open the author-credential store from the operator's environment");
        assertTrue(source.contains("embedConfiguration, userCredentials)"),
                () -> MAIN + " must hand the store to the server. Without it the constructor receives "
                        + "null, /v1/credentials answers 404, and credentialAdmission falls back to "
                        + "permissive() -- so nothing can be entered and nothing is owned by anyone.");
        assertTrue(source.contains("userCredentials.close()"),
                () -> MAIN + " must close the store it opened");
    }

    /**
     * <b>The store is the FIRST link of the resolver chain, and the environment resolver is behind
     * it.</b>
     *
     * <p>The assertion the assistant's equivalent did not need. {@code CredentialResolverChain}'s own
     * Javadoc says the order "decides nothing about precedence" — <em>because</em> the two namespaces
     * are disjoint by shape, which is a property of {@link CredentialReference#PREFIX} and not of this
     * call site. So the ordering is not load-bearing today and the chain's <b>membership</b> is: a
     * chain that lost the store link resolves no author credential at all, silently, and every
     * behavioural test in this package would still pass because they call the store directly.</p>
     *
     * <p>Position is asserted anyway rather than mere presence, and the reason is written in that same
     * Javadoc: "If that prefix were ever dropped, this class would silently become a precedence rule."
     * Pinning the order costs nothing now and is the difference between a silent behaviour change and
     * a red test on the day someone relaxes the prefix.</p>
     */
    @Test
    void theAuthorStoreIsTheFirstLinkAndTheOperatorEnvironmentIsBehindIt() throws Exception {
        String source = Files.readString(MAIN);
        int chain = source.indexOf("new ai.ravenroot.server.credential.CredentialResolverChain(");
        assertTrue(chain > 0,
                () -> MAIN + " must compose the resolver chain: without it a credential stored through "
                        + "POST /v1/credentials never resolves, violating the bounded credential-operation contract");

        String arguments = source.substring(chain, source.indexOf("),", chain));
        int store = arguments.indexOf("userCredentials::resolve");
        int environment = arguments.indexOf("EnvironmentCredentialResolver");

        assertTrue(store > 0, () -> "the author-credential store must be a link of the chain: " + arguments);
        assertTrue(environment > 0,
                () -> "the operator's environment bindings must stay in the chain -- withdrawing that "
                        + "path is the condition that would promote "
                        + "API-05 from a stated dependency to a hard prerequisite: " + arguments);
        assertTrue(store < environment,
                () -> "the author store must come first in the chain: " + arguments);
    }
}
