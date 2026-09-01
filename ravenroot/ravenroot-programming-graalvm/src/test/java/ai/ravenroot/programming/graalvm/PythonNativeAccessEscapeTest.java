package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native extensions are admitted; the consequence is that the external supervisor becomes the only
 * defence. The eight evasion
 * probes that supported the original recommendation had been run with native access DISABLED, so
 * what they proved did not apply to the configuration that was then chosen. This class is those
 * eight probes re-run against the configuration actually shipped.
 *
 * <p><b>The measured answer: all eight still hold.</b> Each probe was run first as a standalone
 * harness in three configurations -- native access off, native access on, and native access on with
 * {@code python.PosixModuleBackend=native} requested -- and produced byte-identical verdicts in all
 * three. This class keeps the middle one (the shipped configuration) as a permanent regression
 * against the real worker rather than against a hand-built context that could drift from it.
 *
 * <p><b>How little "all eight are identical" actually says, stated here so nobody leans on it.</b>
 * <b>Only P4 is sensitive to {@code allowNativeAccess} at all.</b> The other seven are stopped by
 * entirely different switches -- process creation, IO, threads, host class lookup, environment
 * access, and an excluded socket module -- none of which this flag touches. Seven of the eight could
 * not have changed whatever the flag was set to, so their agreement across configurations is close
 * to content-free. The evidential weight of this suite rests on P4 alone, and P4's verdict is
 * platform-dependent in a way this suite cannot see: see {@code PythonNativeModuleAvailabilityTest},
 * which exists precisely because the reason behind P4's refusal was misread once already.
 *
 * <p><b>What was misread.</b> An earlier revision of this comment said no native module was present
 * to load. It is present: {@code python-resources} ships a native {@code _ctypes} for four
 * platforms and the host's copy is extracted to disk, where the guest can list it. On
 * darwin/aarch64 the import machinery looks for an {@code x86_64-linux} suffix -- a build constant,
 * not derived from the host -- so the correctly named file is never sought. <b>On linux/amd64, which
 * is where CI runs and what the image is built for, that mismatch does not exist, and the outcome
 * has not been measured.</b> That platform-specific uncertainty must remain explicit.
 *
 * <p><b>Read what this proves narrowly.</b> It says these eight attempts are refused by the language
 * boundary in the shipped configuration <b>on the platform the suite happens to run on</b>. It is
 * not a claim that the boundary is proof against an adversary, it says nothing about the external
 * supervisor, which is not in this repository and has never been measured end to end, and -- because
 * of P4 -- a green run here on darwin/aarch64 is not evidence about linux/amd64.
 */
class PythonNativeAccessEscapeTest {

    /**
     * The eight probes, each as an artifact the real worker executes. Named so a failure report says
     * which capability got through. Marker paths are asserted absent afterwards, so a probe that was
     * "refused" only after doing its work cannot pass.
     */
    private static final Map<String, String> PROBES = new LinkedHashMap<>();

    private static final Path MARKER_ONE = Path.of("/tmp/ravenroot-409-escape-1");
    private static final Path MARKER_TWO = Path.of("/tmp/ravenroot-409-escape-2");

    static {
        PROBES.put("P1 os.system spawns a process",
                "def handler(request):\n"
                + "    import os\n"
                + "    os.system('touch " + MARKER_ONE + "')\n"
                + "    return {'escaped': True}\n"
                + "handler");
        PROBES.put("P2 subprocess spawns a process",
                "def handler(request):\n"
                + "    import subprocess\n"
                + "    subprocess.run(['/usr/bin/touch', '" + MARKER_TWO + "'])\n"
                + "    return {'escaped': True}\n"
                + "handler");
        PROBES.put("P3 reading a file outside the sandbox",
                "def handler(request):\n"
                + "    return {'escaped': open('/etc/passwd').read(16)}\n"
                + "handler");
        // THE ONLY PROBE OF THE EIGHT THAT allowNativeAccess CAN AFFECT. Its refusal on
        // darwin/aarch64 is a name mismatch in the import machinery, not an absent module and not a
        // denied permission -- PythonNativeModuleAvailabilityTest is where that is pinned. Do not
        // treat a green here as evidence about linux/amd64.
        PROBES.put("P4 loading a native library through ctypes",
                "def handler(request):\n"
                + "    import ctypes\n"
                + "    return {'escaped': str(ctypes.CDLL(None))}\n"
                + "handler");
        PROBES.put("P5 opening a network socket",
                "def handler(request):\n"
                + "    import socket\n"
                + "    s = socket.socket()\n"
                + "    s.connect(('127.0.0.1', 9))\n"
                + "    return {'escaped': True}\n"
                + "handler");
        PROBES.put("P6 starting a thread",
                "def handler(request):\n"
                + "    import threading\n"
                + "    t = threading.Thread(target=lambda: None)\n"
                + "    t.start()\n"
                + "    t.join()\n"
                + "    return {'escaped': True}\n"
                + "handler");
        PROBES.put("P7 reaching a host class",
                "def handler(request):\n"
                + "    import java\n"
                + "    return {'escaped': java.type('java.lang.System').getProperty('user.home')}\n"
                + "handler");
        PROBES.put("P8 reading the process environment",
                "def handler(request):\n"
                + "    import os\n"
                + "    return {'escaped': os.environ['PATH']}\n"
                + "handler");
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void everyEvasionProbeIsStillRefusedWithNativeAccessEnabled() throws Exception {
        Files.deleteIfExists(MARKER_ONE);
        Files.deleteIfExists(MARKER_TWO);

        var escaped = new java.util.ArrayList<String>();
        var refusals = new LinkedHashMap<String, String>();
        for (var probe : PROBES.entrySet()) {
            GeneratedArtifact artifact = artifact(probe.getValue());
            byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact, request());
            try {
                Object result = ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes));
                escaped.add(probe.getKey() + " -> returned " + result);
            } catch (ProgramWireProtocol.ProgramWorkerException refusal) {
                refusals.put(probe.getKey(), refusal.getMessage());
            }
        }

        assertTrue(escaped.isEmpty(), "these probes were NOT refused by the shipped Python context, "
                + "which means the language boundary no longer holds where it did when this was "
                + "measured, and the external supervisor is now the only thing left: " + escaped);
        assertTrue(refusals.size() == PROBES.size(),
                "every probe must have produced a refusal to report: " + refusals);

        // A refusal that still performed the side effect would be the worst of both readings, and
        // the response alone cannot distinguish the two.
        assertTrue(!Files.exists(MARKER_ONE) && !Files.exists(MARKER_TWO),
                "a probe was reported as refused but its process still ran");
    }

    private static ProgramRequest request() {
        return new ProgramRequest(UUID.randomUUID(), "node-1", Map.of("name", "Ravenroot"), Map.of());
    }

    private static GeneratedArtifact artifact(String source) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("escape-probe", "python", hash, source,
                    ArtifactState.ACTIVE, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
