package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code *Test} class this repository's prose names actually exists.
 *
 * <h2>Why this is a test and not a habit</h2>
 * <p>This codebase argues in its comments, and the strongest form of that argument is "the property
 * is real, and <em>this named test</em> is what fails when it stops being real". A citation that
 * points at nothing inverts the value of the whole convention: the reader follows the pointer, finds
 * no file, and cannot tell whether the test was deleted, renamed, or never written — so they cannot
 * tell whether the property is defended or merely asserted.</p>
 *
 * <p>A topic-based prose sweep, such as one limited to ADR 0018, cannot reliably find dead citations
 * because <b>a phantom citation is not a topic — it is a shape</b>. A shape is what a machine checks,
 * so this test checks it across the repository.</p>
 *
 * <p><b>Dead test names are deliberately absent from this Javadoc.</b> A dead pointer spelled inside
 * an explanation of dead pointers is still a dead pointer to anyone grepping, so examples must use
 * names that resolve.</p>
 *
 * <h2>Deliberately repo-wide, and deliberately cheap</h2>
 * <p>It scans every Java file in the reactor, because a citation added in an otherwise unrelated
 * module is exactly the case a narrowly scoped check misses. It
 * costs one directory walk and a regex; there is nothing to tune and nothing to keep in sync.</p>
 */
class TestCitationsResolveTest {

    private static final Path REACTOR = Path.of("..").toAbsolutePath().normalize();

    /** An identifier ending in {@code Test}, not preceded by a character that makes it part of one. */
    private static final Pattern CITATION = Pattern.compile("(?<![A-Za-z0-9_*])([A-Z][A-Za-z0-9_]*Test)\\b");

    /**
     * Types named {@code *Test} that are somebody else's and have no file here.
     *
     * <p>Every entry is a third-party or framework type this build compiles against, so "the file does
     * not exist in this repository" is the correct and expected state rather than a broken pointer.
     * The list is short and closed on purpose: a name added here to make the test pass is a name a
     * maintainer must resolve deliberately, which is the intended friction.</p>
     */
    private static final Set<String> NOT_OURS = Set.of(
            "DynamicTest", "ParameterizedTest", "RepeatedTest",   // org.junit.jupiter.api
            "ServerSetupTest");                                    // com.icegreen.greenmail

    /**
     * <b>Every cited {@code *Test} name resolves to a file.</b>
     *
     * <p><b>Mutation proof, executed.</b> Add a nonexistent test citation — the WHERE-clause comment in
     * {@code SqliteUserCredentialStore} is a short location — and this reds, naming the file and line
     * that carry the dead pointer.</p>
     */
    @Test
    void everyCitedTestClassExists() throws IOException {
        Set<String> declared = new TreeSet<>();
        Map<String, List<String>> cited = new TreeMap<>();

        try (Stream<Path> sources = Files.walk(REACTOR)) {
            List<Path> files = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/")).toList();
            assertTrue(files.size() > 500,
                    () -> "the scan found only " + files.size() + " sources, so it is not scanning the "
                            + "reactor and its green means nothing");
            for (Path path : files) {
                declared.add(path.getFileName().toString().replace(".java", ""));
            }
            for (Path path : files) {
                List<String> lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    Matcher matcher = CITATION.matcher(lines.get(index));
                    while (matcher.find()) {
                        cited.computeIfAbsent(matcher.group(1), ignored -> new java.util.ArrayList<>())
                                .add(REACTOR.relativize(path) + ":" + (index + 1));
                    }
                }
            }
        }

        var unresolved = new TreeMap<String, List<String>>();
        cited.forEach((name, sites) -> {
            if (!declared.contains(name) && !NOT_OURS.contains(name)) {
                unresolved.put(name, sites);
            }
        });

        assertEquals(Map.of(), unresolved,
                () -> "these names are cited as the proof of a property and no such file exists, so a "
                        + "reader following the pointer cannot tell whether the test was deleted, "
                        + "renamed or never written. Either fix the citation or write the test:\n"
                        + render(unresolved));
    }

    /**
     * The control: the scan finds real citations too, so its green is not the green of a scan that
     * matched nothing.
     *
     * <p>Without this, deleting the regex's body would make the assertion above pass forever. It is
     * the same discipline {@code CredentialRouteTest} applies when it asserts the canary really was
     * in the request it sent.</p>
     */
    @Test
    void theScanActuallyFindsCitations() throws IOException {
        String source = Files.readString(REACTOR.resolve(
                "ravenroot-server/src/main/java/ai/ravenroot/server/credential/SqliteUserCredentialStore.java"));
        Matcher matcher = CITATION.matcher(source);

        var found = new TreeSet<String>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }

        assertTrue(found.contains("UserCredentialStoreTest"),
                () -> "the scan must see a citation this file certainly carries: " + found);
    }

    private static String render(Map<String, List<String>> unresolved) {
        var report = new StringBuilder();
        unresolved.forEach((name, sites) -> {
            report.append("  ").append(name).append('\n');
            sites.forEach(site -> report.append("      ").append(site).append('\n'));
        });
        return report.toString();
    }
}
