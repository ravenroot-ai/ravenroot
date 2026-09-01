package ai.ravenroot.programming.graalvm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>This test exists because a previous revision read a
 * {@code ModuleNotFoundError} as an absence, and wrote that false reading into five places.</b>
 *
 * <p><b>The false claim.</b> "No native module is present to load -- the component ships the
 * pure-Python {@code ctypes} wrapper without its {@code _ctypes} backend." That is wrong.
 * {@code python-resources} ships a native {@code _ctypes} for <b>four</b> platforms
 * (linux/amd64, linux/aarch64, darwin/aarch64, windows/amd64), and on a developer machine the one
 * for the host is materialised on disk -- 230,352 bytes for darwin/aarch64 -- inside the directory
 * that is {@code sys.path[1]} in the shipped context. The guest can list it and see it.
 *
 * <p><b>What actually happens on darwin/aarch64.</b> The import machinery advertises
 * {@code EXTENSION_SUFFIXES == ['.graalpy250-312-native-x86_64-linux.so', '.so', '.pyd']} -- it
 * looks for an <b>x86_64-linux</b> suffix while running on <b>aarch64-darwin</b>. That string is a
 * build constant inside {@code python-language}, not something derived from the host. The correctly
 * named file for this platform is on disk and is not the name the import looks for. So
 * {@code ModuleNotFoundError} is <b>a name that does not match</b>: not an absence, and not a denial
 * of permission.
 *
 * <p><b>Why this distinction is not academic.</b> CI runs on {@code ubuntu-latest} and the image is
 * built for {@code linux/amd64}. There, the advertised suffix and the shipped filename are the SAME
 * string, so the mismatch that produces the refusal on a developer laptop <b>does not exist</b>. A
 * measurement taken only on darwin cannot tell "the permission grants nothing" apart from "the
 * permission grants something that a wrong name hides on one architecture".
 *
 * <p><b>What this test asserts, and why it survives a change of platform.</b> It pins the REASON
 * rather than the refusal. It always asserts the module is shipped and visible, so the "absent"
 * reading cannot be written down again, and it always observes BOTH configurations -- granted and
 * denied -- before deciding anything.
 *
 * <p>There are <b>three</b> outcomes, not two, and the third is the one that keeps this test
 * honest:
 * <ol>
 *   <li><b>No advertised suffix matches any shipped filename.</b> The module is not resolvable
 *       here, so the run says NOTHING about what the permission allows, and says so out loud.</li>
 *   <li><b>Names match and the module LOADS with the permission granted.</b> Then denying the
 *       permission must refuse it. This is the only branch that establishes the security property,
 *       and the only one that provides the missing platform-specific measurement.</li>
 *   <li><b>Names match and it still does not load, in either configuration.</b> INCONCLUSIVE. The
 *       refusal is pinned to its reason and the measured message is put on record, and the run
 *       explicitly must not be read as the missing measurement.</li>
 * </ol>
 *
 * <p><b>The suffix is necessary but not sufficient</b> -- measured on darwin/aarch64 by renaming the
 * shipped file to the name the import looks for, after which it still did not load. So this test
 * does NOT branch on "the only thing that decides the outcome", and it does not branch on the
 * platform: it branches on the name, and then on what was actually observed. An earlier revision of
 * this Javadoc claimed both of those things, and was wrong about both.
 *
 * <p><b>The two failure modes this test exists for, each with its provenance.</b> The provenance is
 * written here on purpose: a Javadoc rewritten while the body comments stay put is exactly how a
 * measurement's origin gets dropped, and it gets dropped in the reassuring direction every time.
 * An earlier revision of this very paragraph lost the word "simulated" and thereby asserted, in the
 * most-read part of this file, a result on the one platform where the measurement is missing.
 *
 * <p><b>First mode.</b> A test that merely asserted "the import fails" would have gone green
 * <b>here, on darwin/aarch64</b>, for a reason that has nothing to do with the permission -- the
 * name mismatch. Whether it would also go green on linux/amd64 is <b>not known</b>.
 * This deliberately does not say "on every platform", which the previous revision did say and could
 * not support.
 *
 * <p><b>Second mode.</b> A test that branched on the name and then looked only at the denied
 * configuration went green while the module loaded in <b>neither</b> configuration. That was
 * observed on a <b>SIMULATED</b> linux/amd64 -- simulated on darwin/aarch64 by parking the shipped
 * file and leaving only one named the way the import looks for it. It is <b>not</b> a measurement
 * on linux/amd64 and must not be read as one.
 */
class PythonNativeModuleAvailabilityTest {

    private static final String MODULE = "_ctypes";

    /**
     * Mirrors {@code GraalVmWorkerMain}'s context exactly, except that native access is a parameter.
     * The duplication is deliberate and is the only way to compare the permission on against off:
     * the worker only ever builds the "on" configuration. {@code PythonNativeAccessEscapeTest} keeps
     * the un-duplicated check by driving the real worker; this one needs the knob.
     */
    private static Context context(boolean nativeAccess) {
        return Context.newBuilder("python")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(ignored -> false)
                .allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(nativeAccess)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void theNativeModuleIsShippedAndItsLoadabilityIsDecidedBySuffixNotByAbsence() {
        List<String> suffixes;
        List<String> present;
        try (Context context = context(true)) {
            suffixes = strings(context, "repr(__import__('importlib.machinery', fromlist=['x'])"
                    + ".EXTENSION_SUFFIXES)");
            // Listed BY THE GUEST, inside the shipped context, not by the test from the host: what
            // the host can see is beside the point, and reading it from outside would have been a
            // weaker claim than the one being corrected.
            present = strings(context, "repr([f for f in __import__('os')"
                    + ".listdir(__import__('sys').path[1]) if f.startswith('" + MODULE + ".')])");
        }

        assertFalse(suffixes.isEmpty(), "the import machinery must advertise some extension suffix");
        assertFalse(present.isEmpty(), "the native " + MODULE + " module is shipped by "
                + "python-resources for four platforms and is materialised into the resource cache. "
                + "An empty listing means one of two things, and this assertion does NOT decide "
                + "which: either the component changed what it ships, or the extraction directory "
                + "moved and sys.path[1] is no longer where it lands. Check the second before "
                + "concluding the first -- the whole reason this test exists is that a missing "
                + "lookup was once read as a missing file");

        // anyMatch over the WHOLE listing, not present.get(0): the order listdir returns is not a
        // fact about anything. With both a darwin-named and a linux-named file on disk, taking the
        // first entry concluded "the names do not match" while a matching file was sitting right
        // there.
        boolean nameMatches = present.stream()
                .anyMatch(file -> suffixes.stream().anyMatch(suffix -> file.equals(MODULE + suffix)));

        // BOTH configurations are observed, always. Branching on the name alone and then looking
        // only at the denied case is how this test passed green on a simulated linux/amd64 while
        // the module loaded in NEITHER configuration -- reporting "verified" for a run that
        // measured nothing. The permission case is the one the relevant contract is waiting for; it must be looked at.
        String withPermission = importFailure(true);
        String withoutPermission = importFailure(false);
        boolean loads = withPermission == null;

        if (!nameMatches) {
            assertFalse(loads, "no advertised suffix matches any shipped filename, so the import "
                    + "cannot possibly resolve -- if it loaded anyway, this test's model of how "
                    + "resolution works is wrong, was: " + present + " against " + suffixes);
            assertTrue(withoutPermission != null && withoutPermission.contains("No module named"),
                    "the refusal must be about the NAME, was: " + withoutPermission);
            record("names do not match on this platform; " + MODULE + " is not resolvable here, so "
                    + "this run says NOTHING about what allowNativeAccess permits");
            return;
        }

        if (loads) {
            // The module genuinely loads with the permission granted. This is the only branch that
            // can establish the security property, and here it is a requirement, not a prediction.
            assertTrue(withoutPermission != null,
                    MODULE + " loads with allowNativeAccess(true) but is NOT refused with "
                            + "allowNativeAccess(false). The permission does not gate native "
                            + "modules, and the isolation claim in "
                            + "docs/architecture/python-programmable-nodes.md is wrong");
            record("names match AND " + MODULE + " loads with the permission granted, and is "
                    + "refused without it. This IS the measurement the relevant contract is waiting for: the "
                    + "permission gates native modules on this platform. Refusal without the "
                    + "permission was: " + withoutPermission);
            return;
        }

        // Names match and it still does not load, in either configuration. INCONCLUSIVE, and it must
        // say so rather than bank the green: the suffix is necessary but demonstrably not
        // sufficient (reproduced by hand on darwin by renaming the shipped file). Pin the reason and
        // put the measured messages on record, because those messages ARE what the relevant contract needs to read.
        assertTrue(withoutPermission != null,
                "inconsistent: " + MODULE + " does not load with the permission granted but does "
                        + "load without it, which no model of this explains");
        assertEquals(withPermission, withoutPermission,
                "with the name matching, if the refusal differed between the two configurations "
                        + "then the permission WOULD be deciding something and this case would not "
                        + "be inconclusive after all. granted=" + withPermission
                        + " denied=" + withoutPermission);
        record("names match but " + MODULE + " does not load in EITHER configuration, with the same "
                + "message both times, so the refusal is not about the permission and something "
                + "beyond the suffix is required. This run is INCONCLUSIVE for the relevant contract and must not be "
                + "read as the missing measurement. Measured message: " + withPermission);
    }

    /**
     * Puts a verdict in the surefire output on a GREEN run. An assertion message is only shown when
     * a test fails, so without this the distinction between "measured" and "inconclusive" would be
     * invisible in exactly the runs that matter -- which is how a green on CI could be mistaken for
     * the measurement the relevant contract is waiting for. Same shape as the {@code [ravenroot-sandbox-conformance]}
     * lines the conformance testkit already prints.
     */
    private static void record(String verdict) {
        System.out.println("[ravenroot-python-native] " + verdict);
    }

    /** Returns the failure message of {@code import _ctypes}, or {@code null} if it loaded. */
    private static String importFailure(boolean nativeAccess) {
        try (Context context = context(nativeAccess)) {
            context.eval("python", "import " + MODULE);
            return null;
        } catch (PolyglotException error) {
            return String.valueOf(error.getMessage());
        }
    }

    private static List<String> strings(Context context, String pythonExpression) {
        Value value = context.eval("python", pythonExpression);
        String text = value.asString();
        // repr() of a list of str; parsed rather than eval'd back, so the test does not depend on a
        // second evaluation inside the very context it is characterising.
        var out = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("'([^']*)'").matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return List.copyOf(out);
    }
}
