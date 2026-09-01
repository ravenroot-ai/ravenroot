package ai.ravenroot.programming.graalvm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker entry point. It is only invoked in a child JVM by {@link GraalVmProgramRuntime}. */
public final class GraalVmWorkerMain {
    private static final int MAX_RESULT_DEPTH = 32;
    private static final long MAX_ARRAY_SIZE = 10_000;

    /**
     * GraalPy materialises its standard library into a Truffle "internal resource" cache on
     * disk the first time a context evaluates Python. Decompiled and confirmed against
     * {@code truffle-api} 25.2.4 ({@code com.oracle.truffle.polyglot.InternalResourceRoots
     * #findCacheRootDefault}): the root directory is resolved, in order, from the
     * {@value #RESOURCE_CACHE_PROPERTY} system property, then {@code $XDG_CACHE_HOME}, then
     * {@code $HOME/.cache}. {@code HOME} in the shipped image is {@code /opt/ravenroot}, and the
     * root filesystem is read-only by design in every deployment descriptor this repository ships
     * (Dockerfile's non-root user, {@code compose.yaml}'s {@code read_only: true}, and both
     * {@code deploy/helm} and {@code deploy/kubernetes}' {@code readOnlyRootFilesystem: true}), so
     * the extraction silently fails, {@code sys.path} ends up without the standard library, and
     * every import -- including {@code import json}, the first line of nearly every example --
     * dies with {@code ModuleNotFoundError}, naming the module rather than the real cause.
     *
     * <p>The fallback lives HERE, in the worker's own {@code main()}, not as an environment
     * variable read by some launcher -- {@link SandboxSupervisorProcessLauncher} clears the
     * environment before it spawns the (trusted, potentially integrator-supplied) sandbox
     * supervisor, so nothing this server's own process environment carries is guaranteed to
     * survive into this JVM at all, regardless of what {@code JAVA_TOOL_OPTIONS} a deployment
     * descriptor sets on the parent process. Calling {@link System#setProperty} at the very top of
     * {@code main()}, before the first {@link Context} is ever built, needs no cooperation from
     * whatever launched this process: the property is read lazily by the polyglot engine on first
     * use, not at JVM startup, and by the time any {@code Context} is built here it has already
     * been set.
     *
     * <p><b>This is a fallback, and for the ONE supervisor this
     * repository actually ships it never engages.</b> An earlier version of this Javadoc claimed
     * the correction "lives in the worker, not in a flag on some launcher's command line" as if
     * that were unconditionally true. It is true for a hypothetical integrator supervisor that
     * invokes this class with nothing extra on the command line -- but {@code
     * deploy/dev/sandbox-supervisor.sh}, the only supervisor this repository ships, already passes
     * {@code -Dpolyglot.engine.userResourceCache=/opt/ravenroot/data/cache} explicitly on the
     * worker's own command line, and {@link #applyResourceCacheDefault}
     * below returns immediately whenever the property is already set -- so for that script, the
     * pre-existing {@code -D} flag is what makes {@code import json} work, not this method. This
     * class's own default only takes over for a supervisor that does NOT set the property itself:
     * a future edit to the dev script that drops the flag, or an integrator's SEC-11 supervisor
     * that does not know about GraalPy's cache quirk at all. Verified both ways by running a real
     * container: the shipped, unmodified dev script (flag present, this method's own branch never
     * taken) and a copy with the flag removed (this method's branch taken, and the same artifact
     * still validates) both produce {@code "outcome":"validated"} for a Python artifact importing
     * sixteen standard-library modules under a read-only root filesystem.
     *
     * <p>The default names {@code /opt/ravenroot/data/cache}, not {@code $HOME}: every shipped
     * deployment descriptor already provisions {@code /opt/ravenroot/data} as the one persistent,
     * writable directory the image and every deployment mode agree on -- the same volume {@code
     * RAVENROOT_AUDIT_DIR} and {@code RAVENROOT_EXECUTION_STORE_DIR} already write to -- and the
     * Dockerfile pre-creates {@code data/cache} at build time, owned by the application user, for
     * the same reason it pre-creates {@code data/audit}: so a deployment that mounts an
     * empty volume over {@code /opt/ravenroot/data} still finds a writable, correctly-owned
     * directory there via the platform's existing-content copy-up, and the artifact this class
     * ships in never depends on an operator's own configuration to make {@code import json} work.
     *
     * <p>{@value #RESOURCE_CACHE_ENV} overrides the default when it reaches this process's own
     * environment -- which depends entirely on whatever launched it choosing to forward it. The
     * shipped dev supervisor ({@code deploy/dev/sandbox-supervisor.sh}) and {@link
     * SandboxSupervisorProcessLauncher} do not forward it, deliberately, so this is an escape
     * hatch for an integrator's own SEC-11 supervisor that chooses to pass environment through to
     * the worker, not something the shipped stack relies on to reach the default above.
     *
     * <p><b>Applied only when {@code /opt/ravenroot} exists</b> (checked, not deduced -- see
     * {@link #applyResourceCacheDefault}), i.e. only when this JVM is actually running inside the
     * shipped image's filesystem layout, where the directory is the fixed {@code WORKDIR}/{@code
     * HOME} every build produces. Measured regression, caught by this module's own test suite: an
     * earlier version of this fix applied the {@code /opt/ravenroot/data/cache} default
     * unconditionally, and on a development machine or a CI runner running this module's tests
     * directly -- neither of which has {@code /opt/ravenroot} at all -- that path cannot be
     * created either, so a Python worker that used to fall through correctly to GraalVM's own
     * {@code $HOME/.cache} default started failing every import with the exact
     * {@code ModuleNotFoundError}, just for a different unwritable
     * directory. The existence check restores the untouched, working default everywhere this
     * server does not ship its own filesystem layout, and only substitutes this one where it does.
     */
    private static final String RESOURCE_CACHE_PROPERTY = "polyglot.engine.userResourceCache";
    static final String RESOURCE_CACHE_ENV = "RAVENROOT_GRAAL_RESOURCE_CACHE_DIR";
    static final String DEFAULT_RESOURCE_CACHE_DIR = "/opt/ravenroot/data/cache";
    private static final java.nio.file.Path IMAGE_ROOT = java.nio.file.Path.of("/opt/ravenroot");

    private GraalVmWorkerMain() {
    }

    /**
     * Applies the resource-cache default described on {@link #DEFAULT_RESOURCE_CACHE_DIR},
     * but only inside the shipped image's own filesystem layout -- see that field's Javadoc for
     * the regression this guard exists to prevent. A no-op if the system property is already set,
     * by a {@code -D} flag whatever launched this JVM chose to add on the command line -- which,
     * for the one supervisor this repository ships, is exactly what happens (see the same field's
     * Javadoc on the correction) -- which stays authoritative over anything decided
     * here.
     */
    private static void applyResourceCacheDefault() {
        applyResourceCacheDefault(IMAGE_ROOT, System.getenv(RESOURCE_CACHE_ENV));
    }

    /**
     * The real body of {@link #applyResourceCacheDefault()}, split over its two
     * external inputs -- the same seam {@code GraalVmProgramRuntime#fromEnvironment(Map)} already
     * uses for the identical reason: {@code /opt/ravenroot}'s existence and this process's own
     * environment cannot be set from inside a test JVM. Package-private: a test seam for this
     * module, not a second public entry point. Before this seam existed, the only red/green control
     * on the image-root guard was mutation testing, whose failure message named an unrelated
     * symptom ({@code subprocess}) rather than this method -- {@code
     * GraalVmWorkerMainResourceCacheDefaultTest} now exercises it directly, by name.
     */
    static void applyResourceCacheDefault(java.nio.file.Path imageRoot, String override) {
        if (System.getProperty(RESOURCE_CACHE_PROPERTY) != null) {
            return;
        }
        if (override != null && !override.isBlank()) {
            System.setProperty(RESOURCE_CACHE_PROPERTY, override);
            return;
        }
        if (java.nio.file.Files.isDirectory(imageRoot)) {
            System.setProperty(RESOURCE_CACHE_PROPERTY, DEFAULT_RESOURCE_CACHE_DIR);
        }
    }

    /**
     * Verifies, before any Python evaluation, that the directory this process will
     * actually use for GraalPy's resource cache is genuinely writable -- catching two measured
     * intermediate misconfigurations, both of which satisfy {@link
     * #applyResourceCacheDefault}'s own {@code /opt/ravenroot} guard while the chosen directory is
     * still not usable: {@code --read-only} with no volume at all mounted over
     * {@code /opt/ravenroot/data} (the directory does not exist and the parent is read-only), and a
     * volume whose {@code data/cache} subdirectory exists but is owned by a different user (for
     * example a stale root-owned directory from a manual {@code docker cp}). Without this check,
     * GraalVM's own extraction fails silently, and
     * the failure resurfaces downstream as a {@code ModuleNotFoundError} pointing at whichever
     * import happens to be first in the author's source -- a source that did nothing wrong.
     *
     * <p>Called only for {@link ProgramLanguage#PYTHON}: JavaScript artifacts never touch this
     * directory, and running this check for them could fail a JavaScript artifact over a Python
     * concern.
     *
     * <p>A no-op when {@link #RESOURCE_CACHE_PROPERTY} is unset -- meaning neither a {@code -D}
     * flag from whatever launched this JVM nor {@link #applyResourceCacheDefault} set anything, so
     * this worker is running outside the shipped image and has no directory of its own to verify;
     * second-guessing GraalVM's native {@code $XDG_CACHE_HOME}/{@code $HOME/.cache} resolution in
     * an environment this class does not own would be a new failure mode, not a fix for one.
     */
    private static void verifyResourceCacheWritable() throws java.io.IOException {
        String configured = System.getProperty(RESOURCE_CACHE_PROPERTY);
        if (configured == null) {
            return;
        }
        java.nio.file.Path directory = java.nio.file.Path.of(configured);
        java.nio.file.Files.createDirectories(directory);
        if (!java.nio.file.Files.isWritable(directory)) {
            throw new java.io.IOException(
                    "Python resource cache directory is not writable: " + directory);
        }
    }

    /**
     * Exactly one envelope reaches stdout, and which one it is gets decided before a single
     * byte leaves.
     *
     * <p>{@code writeSuccess} now serialises into a bounded buffer and copies out only when the
     * envelope is whole, so a serialisation failure leaves the stream untouched and the {@code
     * catch} below writes its failure envelope onto a clean stream. Before, a half-written success
     * envelope stayed on stdout and the failure envelope was appended to it; the reader consumed the
     * first, then read the second's magic bytes as a length field, and reported "Invalid string
     * length: 1381126193" while the real refusal sat unread a few bytes further on.
     *
     * <p>The {@link ProgramWireProtocol.GuardedOutput#dirty()} check is the second line, not the
     * first. Its justification is ONE case, not two, and the case it is not covers a property of
     * this stream worth stating: <b>a broken stdout does not reach this catch block at all</b>.
     * {@code System.out} is a {@link java.io.PrintStream}, whose {@code write} and {@code flush}
     * do not declare checked exceptions -- they swallow the {@code IOException} and raise an
     * internal error flag readable only through {@code checkError()}. Measured on JDK 21 against a
     * sink that throws {@code Broken pipe}: {@code write(byte[],int,int)} (the call
     * {@code ByteArrayOutputStream.writeTo} makes), {@code write(int)}, {@code write(byte[])} and
     * {@code flush()} each returned normally with {@code checkError() == true}. So a stdout closed
     * under the worker mid-copy does not throw, this {@code catch} is never entered for it, and the
     * worker exits <b>0 with a truncated envelope, not 70</b>. Nothing here can change that, and no
     * claim in this class should suggest otherwise.
     *
     * <p>What the check does cover is the case the buffer cannot: a future writer added to the
     * success path that emits bytes before it fails. That failure arrives as an ordinary Java
     * exception rather than as a {@code PrintStream} error flag, so it does reach this block, and
     * there the worker stops instead of appending. The partial bytes stay on stdout -- nothing can
     * recall them -- but exit 70 makes the supervisor resolve the run as a failed child
     * ({@code SETUP_FAILURE}, on any non-zero exit) and no response is delivered from them. That is
     * worse than a refusal and better than the alternative, which is a garbled envelope:
     * a reader parsing structure as data and reporting a length that was never a length.
     */
    public static void main(String[] args) {
        applyResourceCacheDefault();
        var out = new ProgramWireProtocol.GuardedOutput(System.out);
        try {
            var request = ProgramWireProtocol.readRequest(System.in);
            verifyHash(request);
            Object result = evaluate(request);
            ProgramWireProtocol.writeSuccess(out, result);
        } catch (Throwable error) {
            if (out.dirty()) System.exit(70);
            try {
                // Whether the failure belongs to the AUTHOR'S SOURCE is decided here, where it
                // is known, and never inferred downstream from the text of a message. SourceRejected
                // is raised by evaluate(..) around exactly the region that loads the source and
                // checks its handler shape; everything else -- a launcher that is not there, an
                // exhausted deadline, a response past the protocol ceiling -- keeps the meaning it
                // had, because telling an author their source does not compile when it does is a
                // false cause, and a false cause is worse than a generic rejection.
                if (error instanceof SourceRejected rejected && rejected.getCause() != null) {
                    int[] at = locationOf(rejected.getCause(), rejected.wrapped());
                    ProgramWireProtocol.writeFailure(out, rejected.getCause(), true, at[0], at[1]);
                } else {
                    ProgramWireProtocol.writeFailure(out, error, false, 0, 0);
                }
            } catch (Throwable ignored) {
                System.exit(70);
            }
        }
    }

    /**
     * The 1-based line and column the language attributed the refusal to, <b>expressed in the
     * author's own source</b>, or {@code 0} for either the language did not supply.
     *
     * <p>Read from the {@code PolyglotException}'s own source location rather than parsed out of its
     * message: the message's shape is the language's business and differs between the two shipped
     * ones, while this is the API both implement. Measured on the real worker — an unindented Python
     * function body reports {@code line 2, column 1}, and a JavaScript syntax error reports the line
     * and column of the offending token. <b>Both languages supply both coordinates</b>; the
     * zero-means-absent case is a refusal with no source location at all, such as a source that
     * parses cleanly and then turns out not to be callable.</p>
     *
     * <h2>The wrapping parenthesis, and the off-by-one it caused</h2>
     * <p>{@code evaluate} wraps JavaScript in {@code (…)} because a bare {@code function (x) {…}} is a
     * declaration rather than an expression. The source the language parses is therefore not the
     * source the author wrote, and every column it reports on line 1 is one greater than the author's.
     * Measured before this correction: for {@code function (request) { return { ; }} the adapter
     * delivered column 32, while the {@code ;} sits at column 31 of the author's text. A structured
     * column exists so an editor can put a cursor on the offending character; one that is reliably
     * one character off is worse than none, because it looks right.</p>
     *
     * <p>Only line 1 is affected — the wrapper adds a character, not a line — so the correction is
     * applied there and nowhere else. Python is never wrapped and is never adjusted.</p>
     *
     * <p>Zero is a fact, not a placeholder. A caller must be able to tell "the runtime placed this at
     * line 2" from "the runtime did not say where", and a defaulted 1 would merge them.</p>
     */
    private static int[] locationOf(Throwable error, boolean sourceWasWrapped) {
        if (!(error instanceof PolyglotException polyglot)) {
            return new int[]{0, 0};
        }
        SourceSection section = polyglot.getSourceLocation();
        if (section == null || !section.isAvailable()) {
            return new int[]{0, 0};
        }
        int line = section.hasLines() ? section.getStartLine() : 0;
        int column = section.hasColumns() ? section.getStartColumn() : 0;
        if (sourceWasWrapped && line == 1 && column > 1) {
            column -= 1;
        }
        return new int[]{line, column};
    }

    /**
     * Marks a throwable as having escaped the region that loads the artifact's source, so the
     * failure path can classify it without knowing anything about which language ran.
     *
     * <p>A marker rather than a predicate over exception types, because the set of throwables the two
     * regions can produce overlaps: {@code IllegalArgumentException} is raised both by the handler
     * shape check below (the author's source is not callable -- their problem) and by
     * {@code toGuest}/{@code fromGuest} on a value neither the caller nor the author chose. Only
     * WHERE it was thrown separates them.</p>
     */
    private static final class SourceRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Whether the text handed to the language was the author's source wrapped in parentheses.
         * Carried here rather than re-derived in the failure path, so the one place that knows it is
         * the one place that decides it — see {@link #locationOf} for the off-by-one it corrects.
         */
        private final boolean wrapped;

        SourceRejected(Throwable cause, boolean wrapped) {
            super(cause);
            this.wrapped = wrapped;
        }

        boolean wrapped() {
            return wrapped;
        }
    }

    private static Object evaluate(ProgramWireProtocol.WorkerRequest request) throws Exception {
        // The second of the two independent doors -- see ProgramLanguage's Javadoc for why the
        // check is duplicated but the list is not. This one is the door that matters: it is the only
        // one still standing if the control plane's check is bypassed.
        ProgramLanguage language = ProgramLanguage.of(request.language());
        // Before any Context exists, so a resource-cache directory GraalVM cannot
        // actually write to is reported as itself -- naming the directory -- rather than as
        // whatever Python import happens to run first against a half-extracted standard library.
        if (language == ProgramLanguage.PYTHON) {
            verifyResourceCacheWritable();
        }
        try (Context context = Context.newBuilder(language.graalId())
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(ignored -> false)
                .allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                // The ONLY relaxation, and only for the language that declares it. Every other capability above stays denied
                // for every language, so this is a single named exception rather than a looser
                // context.
                //
                // WHAT THIS ACTUALLY PERMITS IS NOT SETTLED. The native _ctypes module IS shipped
                // and IS extracted to disk; on darwin/aarch64 it is not loadable because the import
                // machinery looks for an x86_64-linux suffix, which is a build constant rather than
                // something derived from the host. On linux/amd64 -- CI, and the built image --
                // that mismatch does not exist and the outcome has NOT been measured. See
                // PythonNativeModuleAvailabilityTest.
                .allowNativeAccess(language.allowNativeAccess())
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            // The bracket around everything that judges the AUTHOR'S SOURCE, and nothing else.
            // Its two members -- the language refusing to parse it, and it parsing to something that
            // cannot be called -- are the two ways an author's own text is wrong, and they are the
            // only failures this worker may report as such. Widening this region is how a sandbox or
            // marshalling failure would start being blamed on the author.
            Value function;
            boolean wrapped = language.sourceNeedsParentheses();
            try {
                String text = wrapped ? "(" + request.source() + ")" : request.source();
                Source source = Source.newBuilder(language.graalId(), text, request.artifactId())
                        .cached(false).buildLiteral();
                function = context.eval(source);
                if (!function.canExecute()) {
                    throw new IllegalArgumentException("Artifact must evaluate to " + language.handlerDescription());
                }
            } catch (RuntimeException rejection) {
                throw new SourceRejected(rejection, wrapped);
            }
            if (request.mode() == ProgramWireProtocol.Mode.VALIDATE) return null;
            if (request.request() == null) throw new IllegalArgumentException("Program input is missing");
            var input = new LinkedHashMap<String, Object>();
            input.put("executionId", request.request().executionId().toString());
            input.put("nodeId", request.request().nodeId());
            input.put("payload", toGuest(request.request().payload(), 0));
            input.put("attributes", toGuest(request.request().attributes(), 0));
            return fromGuest(function.execute(ProxyObject.fromMap(input)), 0, language);
        }
    }

    private static Object toGuest(Object value, int depth) {
        checkDepth(depth);
        if (value == null || value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), toGuest(item, depth + 1)));
            return ProxyObject.fromMap(result);
        }
        if (value instanceof Iterable<?> iterable) {
            var result = new ArrayList<Object>();
            iterable.forEach(item -> result.add(toGuest(item, depth + 1)));
            return ProxyArray.fromList(result);
        }
        throw new IllegalArgumentException("Unsupported input value: " + value.getClass().getName());
    }

    /**
     * {@code language} decides one branch and one only: whether hash entries are consulted
     * before members. See {@link ProgramLanguage} for the measurement behind that -- in short, a
     * Python {@code dict}'s member keys are its methods, and a JavaScript {@code Map}'s entries have
     * never been read by this adapter and must keep not being read.
     */
    private static Object fromGuest(Value value, int depth, ProgramLanguage language) {
        checkDepth(depth);
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) return value.asString();
        if (value.isNumber()) {
            if (value.fitsInLong()) return value.asLong();
            return value.asDouble();
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            if (size > MAX_ARRAY_SIZE) throw new IllegalArgumentException("Result array exceeds limit");
            var result = new ArrayList<Object>((int) size);
            for (long index = 0; index < size; index++) {
                result.add(fromGuest(value.getArrayElement(index), depth + 1, language));
            }
            return Collections.unmodifiableList(result);
        }
        if (language.resultPrefersHashEntries() && value.hasHashEntries()) {
            long size = value.getHashSize();
            if (size > MAX_ARRAY_SIZE) throw new IllegalArgumentException("Result object exceeds limit");
            var result = new LinkedHashMap<String, Object>();
            var entries = value.getHashEntriesIterator();
            while (entries.hasIteratorNextElement()) {
                Value entry = entries.getIteratorNextElement();
                Value member = entry.getArrayElement(1);
                if (member != null && member.canExecute()) {
                    throw new IllegalArgumentException("Program results cannot contain functions");
                }
                result.put(asKey(entry.getArrayElement(0)), fromGuest(member, depth + 1, language));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value.hasMembers()) {
            if (value.getMemberKeys().size() > MAX_ARRAY_SIZE) {
                throw new IllegalArgumentException("Result object exceeds limit");
            }
            var result = new LinkedHashMap<String, Object>();
            for (String key : value.getMemberKeys()) {
                Value member = value.getMember(key);
                if (member != null && member.canExecute()) {
                    throw new IllegalArgumentException("Program results cannot contain functions");
                }
                result.put(key, fromGuest(member, depth + 1, language));
            }
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Program result is not serializable");
    }

    /**
     * A result map's keys must survive the wire as strings. A hash key that is itself a container
     * is refused rather than stringified: {@code String.valueOf} on a guest object produces a
     * language-specific rendering that no caller could rely on, which is a worse outcome than a
     * clear refusal.
     */
    private static String asKey(Value key) {
        if (key == null || key.isNull()) throw new IllegalArgumentException("Result key cannot be null");
        if (key.isString()) return key.asString();
        if (key.isNumber() || key.isBoolean()) return key.toString();
        throw new IllegalArgumentException("Result keys must be strings, numbers or booleans");
    }

    private static void verifyHash(ProgramWireProtocol.WorkerRequest request) throws Exception {
        byte[] canonical = java.util.HexFormat.of().parseHex(
                ProgramArtifactDigest.canonical(request.language(), request.source()));
        byte[] legacy = MessageDigest.getInstance("SHA-256")
                .digest(request.source().getBytes(StandardCharsets.UTF_8));
        byte[] expected = java.util.HexFormat.of().parseHex(request.sha256());
        if (!MessageDigest.isEqual(canonical, expected) && !MessageDigest.isEqual(legacy, expected)) {
            throw new SecurityException("Artifact hash mismatch");
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_RESULT_DEPTH) throw new IllegalArgumentException("Program value nesting exceeds limit");
    }
}
