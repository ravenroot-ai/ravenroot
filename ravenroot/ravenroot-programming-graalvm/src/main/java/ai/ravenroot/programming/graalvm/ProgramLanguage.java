package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ProgramLanguageDescriptor;

import java.util.Locale;

/**
 * The single place that knows which languages this adapter executes and how each one differs.
 *
 * <p><b>Why this exists rather than a second string comparison.</b> The language is checked at two
 * independent doors -- {@code GraalVmProgramRuntime#verifyArtifact} on the control plane and
 * {@code GraalVmWorkerMain#evaluate} inside the sandboxed child JVM -- and that duplication is
 * deliberate: the worker must refuse on its own, because a compromised or bypassed control plane is
 * exactly the situation the worker exists for. Deliberate duplication of the CHECK is not a licence
 * for duplication of the LIST. Before this type the two doors held two hand-written comparisons
 * against the same two strings; adding a language meant editing both, and editing one was a silent
 * divergence that no test would have caught until an artifact was accepted by one door and refused
 * by the other.
 *
 * <p><b>The per-language differences below are measured, not assumed.</b> Each field records a place
 * where treating Python as "JavaScript with different syntax" produces working-looking code that is
 * wrong; the reasoning is on each field.
 */
enum ProgramLanguage {

    /**
     * {@code sourceNeedsParentheses}: a bare {@code function (x) {...}} is a declaration, not an
     * expression, so the JavaScript source is wrapped to make {@code eval} yield the function.
     *
     * <p>{@code resultPrefersHashEntries} is false, and changing it would be an observable change to
     * JavaScript that the existing-result-shape requirement forbids. Measured: a JavaScript {@code Map} reports BOTH
     * {@code hasMembers()} (with an empty key set) and {@code hasHashEntries()}, so a marshaller that
     * consulted hash entries first would start returning {@code {a: 1}} where this adapter has always
     * returned {@code {}}. See {@code PythonProgramExecutionTest#javascriptKeepsItsExistingResultShapeForAMap}.
     *
     * <p>{@code exampleSource} is the literal default the editor's workbench offered before
     * language became selectable, moved here so a second language could get its own starter instead
     * of a JavaScript one relabelled.
     */
    JAVASCRIPT("js", true, false, false, "a JavaScript function", "JavaScript",
            "({ payload, attributes }) => ({ value: String(payload) })", "javascript", "js"),

    /**
     * {@code sourceNeedsParentheses} is false: wrapping is what makes a JavaScript declaration an
     * expression, and applying it to Python turns every artifact with more than one statement into a
     * syntax error. A Python artifact instead ends on the name of its handler, and {@code eval}
     * yields the value of that last expression.
     *
     * <p>{@code resultPrefersHashEntries} is true, and this one is not a preference. Measured: a
     * Python {@code dict} reports {@code hasMembers() == true} and its member keys are its METHODS
     * ({@code pop}, {@code keys}, {@code values}, ...), not its entries. A marshaller that checked
     * members first -- as this adapter always has, correctly, for JavaScript -- would walk a Python
     * result's method table and then reject it with "Program results cannot contain functions". The
     * failure is not subtle, but the reason for it is entirely invisible from the JavaScript side.
     *
     * <p>{@code allowNativeAccess} is true for Python so native extensions are admitted. The external
     * supervisor is therefore the only defence at that boundary.
     *
     * <p><b>What this flag does in practice is NOT settled, and an earlier revision of this comment
     * claimed it was.</b> That revision said "no native module is present to load -- the component
     * ships the pure-Python {@code ctypes} wrapper without its {@code _ctypes} backend". <b>That was
     * false.</b> {@code python-resources} ships a native {@code _ctypes} for four platforms, and the
     * one for the host is materialised on disk where the guest can list it. What actually happens on
     * darwin/aarch64 is a NAME MISMATCH: the import machinery advertises
     * {@code .graalpy250-312-native-x86_64-linux.so} -- a build constant, not derived from the
     * host -- so the correctly named darwin file is never looked for. On linux/amd64, which is what
     * CI and the image build use, that mismatch DOES NOT EXIST, and <b>the outcome there has not
     * been measured</b>.
     *
     * <p>See {@code PythonNativeModuleAvailabilityTest}, which pins the reason rather than the
     * refusal. Do not read a green test on a developer laptop as evidence
     * about what this flag permits.
     *
     * <p>{@code exampleSource} uses attribute access ({@code request.payload}), not subscript
     * access ({@code request['payload']}): {@code PythonProgramExecutionTest}'s own Javadoc records
     * that the worker hands the guest a {@code ProxyObject}, which GraalPy surfaces as a
     * {@code polyglot.ForeignObject} supporting attributes and raises {@code TypeError} on subscript.
     * A starter using the wrong one would fail on the very first Test click.
     */
    PYTHON("python", false, true, true, "a Python callable", "Python",
            "def handler(request):\n    return {'value': str(request.payload)}\nhandler", "python", "py");

    private final String graalId;
    private final boolean sourceNeedsParentheses;
    private final boolean resultPrefersHashEntries;
    private final boolean allowNativeAccess;
    private final String handlerDescription;
    private final String displayName;
    private final String exampleSource;
    private final String[] aliases;

    ProgramLanguage(String graalId, boolean sourceNeedsParentheses, boolean resultPrefersHashEntries,
                    boolean allowNativeAccess, String handlerDescription, String displayName,
                    String exampleSource, String... aliases) {
        this.graalId = graalId;
        this.sourceNeedsParentheses = sourceNeedsParentheses;
        this.resultPrefersHashEntries = resultPrefersHashEntries;
        this.allowNativeAccess = allowNativeAccess;
        this.handlerDescription = handlerDescription;
        this.displayName = displayName;
        this.exampleSource = exampleSource;
        this.aliases = aliases;
    }

    /** The GraalVM language id this maps to. */
    String graalId() {
        return graalId;
    }

    boolean sourceNeedsParentheses() {
        return sourceNeedsParentheses;
    }

    boolean resultPrefersHashEntries() {
        return resultPrefersHashEntries;
    }

    boolean allowNativeAccess() {
        return allowNativeAccess;
    }

    /** Used only to phrase the refusal when an artifact does not evaluate to something callable. */
    String handlerDescription() {
        return handlerDescription;
    }

    /**
     * The public catalog entry for this language: the exact token {@link #of} resolves (its
     * first, and therefore canonical, alias -- {@code aliases[1..]} exist only so {@link #of} accepts
     * a shorthand it does not itself advertise), a label for a selector, and a starter source. Exposed
     * through {@link GraalVmProgramRuntime#supportedLanguages()} rather than read directly by an
     * editor, so an editor never depends on this package-private type.
     */
    ProgramLanguageDescriptor descriptor() {
        return new ProgramLanguageDescriptor(aliases[0], displayName, exampleSource);
    }

    /**
     * Resolves a declared language, or throws with the declared value echoed back. Both doors call
     * this, so both produce the same message for the same input.
     */
    static ProgramLanguage of(String declared) {
        String normalised = declared == null ? "" : declared.strip().toLowerCase(Locale.ROOT);
        for (ProgramLanguage language : values()) {
            for (String alias : language.aliases) {
                if (alias.equals(normalised)) {
                    return language;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported program language: " + declared);
    }
}
