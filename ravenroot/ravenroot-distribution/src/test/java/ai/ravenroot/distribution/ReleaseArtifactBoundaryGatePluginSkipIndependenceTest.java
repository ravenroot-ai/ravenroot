package ai.ravenroot.distribution;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PLAT-11 pin for the redundant control: this module's {@code pom.xml} runs the release-artifact
 * boundary check ({@link ReleaseArtifactBoundaryGate}) through <b>two
 * different plugin executions</b> at the {@code package} phase - one via {@code exec-maven-plugin}'s
 * {@code exec} goal, one via {@code maven-antrun-plugin}'s {@code run} goal - specifically so that no
 * single {@code -D} flag can silence the release condition. That property does not hold merely
 * because there are two {@code <execution>} blocks; it holds only because those two executions each
 * answer to a <b>different</b> Maven property for their {@code skip} parameter ({@code exec.skip} vs
 * {@code maven.antrun.skip}). Nothing before this test noticed if that stopped being true - a
 * maintainer consolidating both executions onto one plugin, or aliasing one skip property to the
 * other, would leave every other test in this reactor green.
 *
 * <p><b>What this test actually resolves, and why not the pom text.</b> Grepping {@code pom.xml} for
 * the strings {@code exec.skip} and {@code maven.antrun.skip} would pass even after that regression:
 * both strings already appear in this file today, but only inside the {@code <!-- ... -->} comments
 * documenting the rationale (see the plugin declarations below) - a check built that way asserts the
 * presence of two words in a text file, not the existence of two independent silencing paths. Neither
 * execution below declares an explicit {@code <skip>} in its own {@code <configuration>} today; the
 * property that actually controls each one is a default binding baked into the <i>plugin's own
 * descriptor</i> ({@code META-INF/maven/plugin.xml} inside the resolved plugin jar - not anything in
 * this project's pom), specifically the {@code default-value} expression of the {@code skip}
 * parameter on the {@code exec} mojo (for {@code exec-maven-plugin}) and the {@code run} mojo (for
 * {@code maven-antrun-plugin}). This test therefore does two things, neither of which is a text
 * search over {@code pom.xml}'s prose:
 * <ol>
 *   <li>Parses this module's {@code pom.xml} with a real XML parser, walking only {@code Element}
 *       nodes (DOM comments are a distinct node type and are structurally never visited), to find
 *       every {@code <execution>} whose {@code <configuration>} actually invokes {@link
 *       ReleaseArtifactBoundaryGate} - via an {@code exec-maven-plugin} {@code <argument>} or a
 *       {@code maven-antrun-plugin} Ant {@code <java classname="...">} task - and records, for each,
 *       whether that execution's own {@code <configuration>} explicitly overrides {@code <skip>}.</li>
 *   <li>For whichever executions do <i>not</i> carry an explicit override, opens the resolved plugin
 *       jar for that execution's exact {@code groupId:artifactId:version} straight from the local
 *       Maven repository, reads its embedded {@code plugin.xml} plugin descriptor, and extracts the
 *       {@code skip} parameter's property expression for the goal actually used - i.e. what Maven
 *       itself would bind, not a value this test assumes or hardcodes.</li>
 * </ol>
 *
 * <p><b>THE GAP THAT MATTERS MOST: this pin can be green while the property it exists to protect is
 * gone.</b> This test compares literal {@code skip} property <i>expressions</i> ({@code "${exec.skip}"}
 * vs {@code "${maven.antrun.skip}"}), not their runtime-resolved values. If someone adds, to this
 * pom's (or a parent pom's) {@code <properties>} section, an alias such as
 * <pre>{@code <maven.antrun.skip>${exec.skip}</maven.antrun.skip>}</pre>
 * the two executions' {@code skip} parameters still carry two textually distinct expressions - this
 * test still reads {@code "${exec.skip}"} and {@code "${maven.antrun.skip}"}, still finds them
 * different, and still passes - while Maven's own property interpolation now collapses
 * {@code maven.antrun.skip} onto whatever {@code exec.skip} evaluates to. Run {@code mvn
 * -Dexec.skip=true package} afterward and both executions are silenced by that one flag: the exact
 * outcome this redundancy exists to prevent, with this test green throughout. Detecting that alias would
 * require evaluating the property through Maven's own interpolator against a live process - e.g. {@code
 * mvn help:describe} or an {@code effective-pom} round trip - not comparing expression strings, which
 * is all this test does. It was left out of this test deliberately, not by oversight: a nested Maven
 * invocation from inside this test would fork its own process against the same local Maven repository
 * ({@code ~/.m2/repository}) that other workers' builds may be reading and writing concurrently, which
 * is exactly the contention this task was told to avoid. Reading the plugin descriptor directly out of
 * the already-resolved jar, as this test does, is read-only, deterministic, and needs no network or
 * reactor lock - at the cost of this specific blind spot. A reviewer relying on this test's name or its
 * green result alone, without reading this paragraph, will believe the two skip properties are
 * independent when a {@code <properties>} alias like the one above would say otherwise. That is the
 * same defect class {@link ReleaseArtifactBoundaryGate}'s own javadoc names - "a comment asserting more
 * than the code guarantees" - reproduced one level up in a test's name and green checkmark instead of a
 * comment; this paragraph exists so this file does not repeat it.
 *
 * <p><b>Everything else this pin does NOT protect</b> - stated plainly so the next reader does not
 * assume more coverage than exists:
 * <ul>
 *   <li>Deleting both executions (or the plugins) outright: zero matches fails the "exactly two"
 *       assertion below with a message telling a human to look, but that is a structural-count guard,
 *       not a semantic one - it cannot tell a deliberate, reviewed removal from an accidental one.</li>
 *   <li>A third silencing path introduced elsewhere - e.g. a new {@code <profile>} that disables the
 *       whole {@code package} phase, a reactor-level {@code -pl}/{@code -rf} invocation that skips this
 *       module, or an unrelated property that happens to gate both executions' enclosing profile
 *       activation. This test only compares the two {@code skip} parameters of the two known executions
 *       to each other.</li>
 *   <li>A change to {@link ReleaseArtifactBoundaryGate}'s own check logic, {@link
 *       ReleaseArtifactBoundaryChecks}, or {@link BuiltArtifactIndices} - this test never runs the
 *       gate, it only resolves what would silence it.</li>
 *   <li>Note on plugin version bumps, stated precisely rather than left to guesswork: this test always
 *       resolves the descriptor for whatever {@code <version>} is currently pinned in this pom, not a
 *       hardcoded assumption, so a version bump that renamed one plugin's {@code skip} property into a
 *       collision with the other's would actually be caught - the two resolved expressions would become
 *       equal and the core assertion would go red. What a version bump CAN do instead is remove or
 *       rename the {@code skip} parameter, or the goal itself, from a plugin's descriptor entirely; this
 *       test fails loudly in that case (an explicit {@code fail()} naming the plugin, goal and what was
 *       expected) rather than passing silently, but a maintainer then has to update this test to match
 *       the new descriptor shape before the build is green again - a maintenance cost this test imposes
 *       on unrelated, legitimate plugin upgrades, not a silent gap in what it protects.</li>
 * </ul>
 */
class ReleaseArtifactBoundaryGatePluginSkipIndependenceTest {

    private static final String GATE_FQCN = "ai.ravenroot.distribution.ReleaseArtifactBoundaryGate";
    private static final Path MODULE_POM = Path.of("pom.xml");

    @Test
    void theTwoPackagePhaseInvocationsOfTheGateAnswerToDifferentSkipProperties() throws IOException {
        Path pomPath = MODULE_POM.toAbsolutePath();
        assertFalse(Files.notExists(MODULE_POM), "Expected to find this module's pom.xml at "
                + pomPath + " (Surefire's working directory is normally the module basedir). If this "
                + "test is being run from a different working directory, point it at "
                + "ravenroot/ravenroot-distribution/pom.xml.");

        Document pom = parseXml(MODULE_POM);
        List<GateExecution> executions = findGateExecutions(pom);

        assertEquals(2, executions.size(), "Expected exactly two <execution> blocks in " + pomPath
                + " whose <configuration> invokes " + GATE_FQCN + " (the redundant "
                + "release-artifact boundary control), found " + executions.size() + ": " + executions
                + ". This test only pins the distinct-skip-property relationship between exactly two "
                + "such executions - a different count is a structural change (an execution added or "
                + "removed) that this test is not designed to interpret; it needs a human, not a "
                + "silent pass or a misleading pass/fail from the assertions below.");

        GateExecution first = executions.get(0);
        GateExecution second = executions.get(1);

        assertNotEquals(first.pluginKey(), second.pluginKey(), "The two executions of " + GATE_FQCN
                + " must be bound to two DIFFERENT plugins, so that no single plugin's own skip switch "
                + "can silence both. Both resolved to the same plugin (" + first.pluginKey() + "): "
                + first + " and " + second + ". If both executions were moved onto one plugin, a "
                + "single -D flag now silences the release-artifact boundary check "
                + "entirely.");

        String firstSkip = effectiveSkipExpression(first);
        String secondSkip = effectiveSkipExpression(second);

        assertFalse(firstSkip.isBlank(), "Could not resolve a skip-parameter property expression for "
                + first + " - neither an explicit <skip> override in this execution's own "
                + "<configuration>, nor a default-value expression on the corresponding mojo's `skip` "
                + "parameter in " + first.pluginKey() + ":" + first.pluginVersion()
                + "'s own plugin descriptor.");
        assertFalse(secondSkip.isBlank(), "Could not resolve a skip-parameter property expression for "
                + second + " - neither an explicit <skip> override in this execution's own "
                + "<configuration>, nor a default-value expression on the corresponding mojo's `skip` "
                + "parameter in " + second.pluginKey() + ":" + second.pluginVersion()
                + "'s own plugin descriptor.");

        assertNotEquals(firstSkip, secondSkip, "The redundant control requires the two package-phase "
                + "invocations of " + GATE_FQCN + " to be silenced by two DIFFERENT Maven properties, "
                + "so that no single -D flag disables both. Both now resolve - via " + first.pluginKey()
                + " and " + second.pluginKey() + "'s own plugin descriptors, or an explicit <skip> "
                + "override added to this module's pom.xml - to the same property expression '"
                + firstSkip + "'. A single flag can now silence the release-artifact boundary check "
                + "entirely: " + first + " / " + second + ". (This assertion compares literal property "
                + "expressions, e.g. \"${exec.skip}\" vs \"${maven.antrun.skip}\"; it does not evaluate "
                + "whether a <properties> alias makes two different expressions resolve to the same "
                + "value at Maven's own interpolation time - see this class's Javadoc.)");
    }

    /**
     * One {@code <execution>} block, in this module's pom, whose configuration invokes {@link
     * ReleaseArtifactBoundaryGate}.
     */
    private record GateExecution(String executionId, String pluginGroupId, String pluginArtifactId,
                                  String pluginVersion, String goal, String explicitSkipOverride) {
        String pluginKey() {
            return pluginGroupId + ":" + pluginArtifactId;
        }

        @Override
        public String toString() {
            return "execution '" + executionId + "' (" + pluginKey() + ":" + pluginVersion + ", goal '"
                    + goal + "'" + (explicitSkipOverride != null
                            ? ", explicit <skip>" + explicitSkipOverride + "</skip>" : "") + ")";
        }
    }

    /** The property expression that actually governs whether {@code execution} runs. */
    private static String effectiveSkipExpression(GateExecution execution) throws IOException {
        if (execution.explicitSkipOverride() != null) {
            return execution.explicitSkipOverride().trim();
        }
        return resolveSkipExpressionFromPluginDescriptor(execution.pluginGroupId(),
                execution.pluginArtifactId(), execution.pluginVersion(), execution.goal());
    }

    /**
     * Walks {@code /project/build/plugins/plugin/executions/execution} and returns one {@link
     * GateExecution} per execution whose {@code <configuration>} subtree contains a reference to
     * {@link #GATE_FQCN} - as an {@code exec-maven-plugin} {@code <argument>} element's text, or as a
     * {@code classname} attribute on an Ant {@code <java>} task. Only real {@code Element} nodes and
     * their attributes are inspected; XML comments are a different DOM node type and are never
     * descended into by this traversal, so a mention of the gate class (or of either skip property)
     * inside a {@code <!-- ... -->} comment cannot make an execution match or influence its recorded
     * configuration.
     */
    private static List<GateExecution> findGateExecutions(Document pom) {
        List<GateExecution> found = new ArrayList<>();
        Element project = pom.getDocumentElement();
        Element build = childElements(project, "build").stream().findFirst().orElse(null);
        Element plugins = build == null ? null : childElements(build, "plugins").stream()
                .findFirst().orElse(null);
        for (Element plugin : childElements(plugins, "plugin")) {
            String groupId = childText(plugin, "groupId");
            String artifactId = childText(plugin, "artifactId");
            String version = childText(plugin, "version");
            if (artifactId == null) {
                continue;
            }
            for (Element executionsEl : childElements(plugin, "executions")) {
                for (Element executionEl : childElements(executionsEl, "execution")) {
                    Element configuration = childElements(executionEl, "configuration").stream()
                            .findFirst().orElse(null);
                    if (configuration == null || !subtreeReferencesFqcn(configuration, GATE_FQCN)) {
                        continue;
                    }
                    String executionId = childText(executionEl, "id");
                    String goal = childElements(executionEl, "goals").stream()
                            .flatMap(g -> childElements(g, "goal").stream())
                            .map(ReleaseArtifactBoundaryGatePluginSkipIndependenceTest::text)
                            .findFirst()
                            .orElse(null);
                    String explicitSkip = childText(configuration, "skip");
                    found.add(new GateExecution(executionId, groupId, artifactId, version, goal,
                            explicitSkip));
                }
            }
        }
        return found;
    }

    /** True if any Element descendant's text content or attribute value equals {@code fqcn}. */
    private static boolean subtreeReferencesFqcn(Element root, String fqcn) {
        String ownText = directText(root);
        if (fqcn.equals(ownText)) {
            return true;
        }
        for (int i = 0; i < root.getAttributes().getLength(); i++) {
            Attr attr = (Attr) root.getAttributes().item(i);
            if (fqcn.equals(attr.getValue())) {
                return true;
            }
        }
        for (Element child : allChildElements(root)) {
            if (subtreeReferencesFqcn(child, fqcn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the {@code default-value} expression Maven binds for the {@code skip} parameter of the
     * given goal, by reading {@code META-INF/maven/plugin.xml} out of the plugin jar as already
     * resolved into the local Maven repository - i.e. exactly what a real {@code mvn package} run
     * against this pom would use, not a value this test assumes.
     */
    private static String resolveSkipExpressionFromPluginDescriptor(String groupId, String artifactId,
                                                                      String version, String goal)
            throws IOException {
        Path jar = localRepositoryRoot()
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
        if (Files.notExists(jar)) {
            fail("Plugin descriptor jar not found at " + jar.toAbsolutePath() + " - this test reads "
                    + groupId + ":" + artifactId + ":" + version + "'s own plugin.xml straight out of "
                    + "the local Maven repository rather than hardcoding its skip-parameter binding, "
                    + "so it needs that jar already resolved there (a prior `mvn validate`/`test` "
                    + "against this reactor resolves it). Not found is a genuine environment gap, not "
                    + "something this test can fall back around without risking exactly the kind of "
                    + "hardcoded assumption it exists to avoid.");
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entry = jarFile.getEntry("META-INF/maven/plugin.xml");
            if (entry == null) {
                fail("No META-INF/maven/plugin.xml inside " + jar.toAbsolutePath() + " - not a plugin "
                        + "jar, or an unexpected plugin packaging this test does not know how to read.");
            }
            Document descriptor;
            try (InputStream in = jarFile.getInputStream(entry)) {
                descriptor = parseXml(in);
            }
            for (Element mojo : allDescendantElements(descriptor.getDocumentElement(), "mojo")) {
                String mojoGoal = childText(mojo, "goal");
                if (!goal.equals(mojoGoal)) {
                    continue;
                }
                Element configuration = childElements(mojo, "configuration").stream()
                        .findFirst().orElse(null);
                String skipExpression = configuration == null ? null : childText(configuration, "skip");
                if (skipExpression == null || skipExpression.isBlank()) {
                    fail("Goal '" + goal + "' in " + groupId + ":" + artifactId + ":" + version
                            + "'s plugin.xml has no <skip> entry under its <configuration> - this "
                            + "plugin version's mojo may no longer expose a skip parameter the way "
                            + "prior versions did.");
                }
                return skipExpression.trim();
            }
            fail("No <mojo> for goal '" + goal + "' found in " + groupId + ":" + artifactId + ":"
                    + version + "'s plugin.xml.");
            throw new AssertionError("unreachable");
        }
    }

    // ---- local repository location -----------------------------------------------------------

    private static Path localRepositoryRoot() throws IOException {
        String override = System.getProperty("ravenroot.test.m2Repo");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        Path settingsXml = userHome.resolve(".m2").resolve("settings.xml");
        if (Files.exists(settingsXml)) {
            Document settings = parseXml(settingsXml);
            String configured = documentElement(settings, "localRepository").map(Element::getTextContent)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .orElse(null);
            if (configured != null) {
                return Path.of(configured);
            }
        }
        return userHome.resolve(".m2").resolve("repository");
    }

    // ---- small DOM helpers ---------------------------------------------------------------------

    private static Document parseXml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return parseXml(in);
        }
    }

    private static Document parseXml(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // No external entity/DTD resolution needed for these trusted, local, well-formed files;
            // disabled explicitly rather than left to library defaults.
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (Exception e) {
            throw new UncheckedIOException("Failed to parse XML", new IOException(e));
        }
    }

    private static java.util.Optional<Element> documentElement(Document doc, String childName) {
        for (Element e : allChildElements(doc.getDocumentElement())) {
            if (e.getTagName().equals(childName)) {
                return java.util.Optional.of(e);
            }
        }
        return java.util.Optional.empty();
    }

    private static List<Element> childElements(Node parent, String tagName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (Element e : allChildElements(parent)) {
            if (e.getTagName().equals(tagName)) {
                result.add(e);
            }
        }
        return result;
    }

    private static List<Element> allChildElements(Node parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static List<Element> allDescendantElements(Element root, String tagName) {
        List<Element> result = new ArrayList<>();
        for (Element child : allChildElements(root)) {
            if (child.getTagName().equals(tagName)) {
                result.add(child);
            }
            result.addAll(allDescendantElements(child, tagName));
        }
        return result;
    }

    private static String childText(Element parent, String tagName) {
        return childElements(parent, tagName).stream().findFirst()
                .map(ReleaseArtifactBoundaryGatePluginSkipIndependenceTest::text)
                .orElse(null);
    }

    private static String text(Element e) {
        return e.getTextContent() == null ? "" : e.getTextContent().trim();
    }

    /** Only this element's own direct text, ignoring any nested element's text. */
    private static String directText(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getTextContent());
            }
        }
        return sb.toString().trim();
    }
}
