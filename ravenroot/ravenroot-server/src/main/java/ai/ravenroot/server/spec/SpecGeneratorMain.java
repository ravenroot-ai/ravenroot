package ai.ravenroot.server.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regenerates a checked-in OpenAPI document from {@link RouteTable#ALL} (API-05).
 *
 * <p>Run after adding, removing or changing a {@link RouteDescriptor} in {@link RouteTable}. If the
 * checked-in file and this generator's current output disagree, {@code RouteTableSpecServerAgreementTest}
 * fails — that is the whole mechanism, not a suggestion to remember to run this.</p>
 *
 * <pre>
 * mvn -q -pl ravenroot-server -am compile &amp;&amp; \
 * mvn -q -f ravenroot-server/pom.xml exec:java \
 *   -Dexec.mainClass=ai.ravenroot.server.spec.SpecGeneratorMain \
 *   -Dexec.args="src/test/resources/documentation/openapi.json"
 * </pre>
 */
public final class SpecGeneratorMain {
    private SpecGeneratorMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: SpecGeneratorMain <output-file-path>");
            System.exit(2);
            return;
        }
        Path target = Path.of(args[0]);
        Files.createDirectories(target.getParent());
        Files.writeString(target, OpenApiSpecGenerator.generate(RouteTable.ALL), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target.toAbsolutePath());
    }
}
