package ai.ravenroot.api.programming;

/**
 * A {@link ProgramRuntime} could not compile the artifact's source, and says why.
 *
 * <h2>Why this type exists rather than a message on an existing exception</h2>
 * <p>Previously a Python artifact with an indentation error reached the author as
 * {@code the request was rejected as invalid} and an HTTP 400. The compiler had produced
 * {@code IndentationError: expected an indented block after function definition on line 1}, the
 * adapter had carried it as far as the HTTP layer, and the handler there read the exception only to
 * choose between two status codes — {@code cause instanceof IllegalStateException ? CONFLICT :
 * INVALID_REQUEST} — and dropped the text. The most common failure of the whole workbench was also
 * the one the author learned nothing from.</p>
 *
 * <p>The obvious repair — put {@code getMessage()} into the error response — is the one thing the
 * product has structurally forbidden: {@code ErrorEnvelope} has no public entry point that accepts
 * caller-composed message text and must not gain one. So the diagnostic does not travel in an error
 * response at all. A source that does not compile is a <b>result</b> of a well-formed request, and
 * {@code POST /v1/program-artifacts/{id}/validate} reports it as one, in a 200 outcome body — the
 * same shape {@code POST /v1/executions/{id}/cancel} and {@code POST /v1/model-providers/{id}/verify}
 * already use. {@code ErrorEnvelope}'s own Javadoc draws the boundary exactly there: it bounds what
 * an <em>error response</em> discloses and "says nothing about what a successful response … contains".</p>
 *
 * <p>This type is what lets the HTTP layer tell the two apart <b>structurally</b> rather than by
 * inspecting a message. A runtime that could not compile the source throws this; every other failure
 * keeps the meaning it had, so a missing sandbox launcher or an exhausted deadline is still reported
 * as what it is and never as "your source does not compile" — a false cause is worse than a vague
 * one.</p>
 *
 * <h2>What the constructor guarantees about the text</h2>
 * <p>The diagnostic is compiler output about code the author themselves wrote, so it is theirs to
 * see — but it is still text this product did not author, and it is delimited before it is stored,
 * not at each place it is later rendered. {@link #delimit(String)} is total and is the only route to
 * the field:</p>
 * <ol>
 *   <li><b>First line only.</b> Everything from the first {@code \n} or {@code \r} onward is dropped.
 *       This is not merely cosmetic: measured on the real worker, GraalPy's message is a single line
 *       carrying cause and location, while Graal.js appends a caret display — the offending source
 *       line, a newline, and a caret. That trailing display is the <em>only</em> part that echoes raw
 *       source, so dropping it removes the whole class of hostile characters at the source rather
 *       than filtering them afterwards, and loses nothing that names the cause.</li>
 *   <li><b>Printable, single-spaced.</b> Any remaining character below {@code 0x20} or equal to
 *       {@code 0x7f} becomes a space, and runs of spaces collapse. A newline is itself the injection
 *       when the sink is line-oriented; quoting it would not be enough.</li>
 *   <li><b>Unable to open an HTML tag.</b> A {@code <} that is immediately followed by an ASCII
 *       letter, {@code /}, {@code !} or {@code ?} gets a space inserted after it. <b>Nothing is ever
 *       removed</b>, so every character the compiler named survives into the field.
 *
 *       <p>An earlier version of this replaced {@code <}, {@code >} and {@code &} with {@code ?},
 *       and justified it here by claiming those characters were unreachable in practice because a
 *       compiler's prose does not contain them. <b>Real-engine tests proved that false.</b> Graal.js
 *       names the offending token inside the first line, so the
 *       substitution landed precisely on the character the diagnostic exists to identify. Measured on
 *       Graal.js 25.2.4:</p>
 *       <pre>
 *       Expected { but found =&gt;            became   ... but found =?
 *       Expected an operand but found &lt;    became   ... but found ?
 *       Expected an operand but found &amp;    became   ... but found ?
 *       </pre>
 *       <p>That is the same defect as a column that is off by one: {@code found ?} looks like an
 *       answer and is not one, inside the diagnostic path intended to preserve the compiler's reason.
 *       So the rule was narrowed to the smallest one that still holds, rather than the
 *       excuse rewritten.</p>
 *
 *       <p>Note what this does <em>not</em> claim. It guarantees that this field cannot introduce a
 *       tag into a document. It does not make the field safe to interpolate into an attribute value
 *       or inside an existing script element — no character rule can, and the previous one could not
 *       either. The transport is protected separately and structurally: the JSON body escapes every
 *       field it writes, exactly as every other body this product assembles does.</p></li>
 *   <li><b>Bounded at {@value #MAX_DIAGNOSTIC_LENGTH} characters.</b> The same bound established
 *       for the raw body of a non-JSON response, reused rather than a second number invented for the
 *       same job.</li>
 * </ol>
 *
 * <p>What it deliberately does not do is escape for a transport, and in particular it does not
 * HTML-escape. Turning {@code <} into {@code &lt;} inside a JSON field would be wrong for every sink
 * that is not HTML — including the only one this product ships, which renders the field through
 * {@code textContent} and needs no escaping at all — and would put an encoding into stored data on the
 * chance that some consumer forgets to apply it.</p>
 */
public final class ProgramSourceRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The bound on {@link #diagnostic()}, in characters.
     *
     * <p>200, because that is the clamp used for the raw body of a response that turned out not
     * to be JSON, and this is the same problem: text the product did not author, shown to a person so
     * they can act on it. Measured against the real worker the longest of the cases exercised is 102
     * characters, so the bound is a ceiling rather than a routine truncation.</p>
     */
    public static final int MAX_DIAGNOSTIC_LENGTH = 200;

    /**
     * What the field says when the runtime refused the source but produced nothing readable.
     *
     * <p>Product-authored, and deliberately not "the request was rejected as invalid": it states what
     * is actually known — the source was refused and no reason came back — instead of naming a cause
     * nobody established.</p>
     */
    public static final String UNSTATED_DIAGNOSTIC =
            "the program runtime refused the artifact source without stating a reason";

/**
 * Sanitized 1-based compiler line, or zero when the runtime did not provide one.
 */
    private final int line;
/**
 * Sanitized 1-based compiler column, or zero when the runtime did not provide one.
 */
    private final int column;

    /**
     * Creates a source-rejection failure with its diagnostic coordinates.
     * @param diagnostic the runtime's own text, delimited by {@link #delimit(String)} on the way in
     * @param line       1-based line the runtime attributed the refusal to, or {@code 0} when it
     *                   provided none. Zero and a real line are different facts and are never merged.
     * @param column     1-based column, or {@code 0} when the runtime provided none. Measured on the
     *                   real worker, <b>both shipped languages supply both coordinates</b> for a
     *                   syntax error; zero is what a refusal carrying no source location at all
     *                   produces, such as a source that parses cleanly and is then found not to be
     *                   callable. The coordinates are expressed in the author's own source: the
     *                   adapter corrects for the parenthesis it wraps JavaScript in.
     */
    public ProgramSourceRejectedException(String diagnostic, int line, int column) {
        super(delimit(diagnostic));
        this.line = Math.max(0, line);
        this.column = Math.max(0, column);
    }

/**
 * The delimited compiler text. Never null, never blank, never longer than the bound.
 * @return bounded, single-line compiler diagnostic safe for the validation outcome.
 */
    public String diagnostic() {
        return getMessage();
    }

/**
 * 1-based line, or {@code 0} when the runtime did not provide one.
 * @return 1-based diagnostic line, or zero when unavailable.
 */
    public int line() {
        return line;
    }

/**
 * 1-based column, or {@code 0} when the runtime did not provide one.
 * @return 1-based diagnostic column, or zero when unavailable.
 */
    public int column() {
        return column;
    }

    /**
     * The whole delimiting rule, in one total function. See this class's Javadoc for why each step is
     * there; this method is public so the property can be asserted directly rather than only through
     * an exception instance.
 * @param raw compiler-supplied diagnostic that may contain source echo or control characters.
 * @return bounded first-line diagnostic with control characters normalized and tag starts broken.
     */
    public static String delimit(String raw) {
        if (raw == null) {
            return UNSTATED_DIAGNOSTIC;
        }
        int end = raw.length();
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '\n' || character == '\r') {
                end = index;
                break;
            }
        }
        var delimited = new StringBuilder(Math.min(end, MAX_DIAGNOSTIC_LENGTH));
        for (int index = 0; index < end && delimited.length() < MAX_DIAGNOSTIC_LENGTH; index++) {
            char character = raw.charAt(index);
            char safe = character < 0x20 || character == 0x7f ? ' ' : character;
            if (safe == ' ' && (delimited.isEmpty() || delimited.charAt(delimited.length() - 1) == ' ')) {
                continue;
            }
            delimited.append(safe);
            // The only structural intervention, and it ADDS rather than removes: a '<' that could
            // open a tag is separated from what follows it. See opensTag.
            if (safe == '<' && index + 1 < end && opensTag(raw.charAt(index + 1))
                    && delimited.length() < MAX_DIAGNOSTIC_LENGTH) {
                delimited.append(' ');
            }
        }
        while (!delimited.isEmpty() && delimited.charAt(delimited.length() - 1) == ' ') {
            delimited.setLength(delimited.length() - 1);
        }
        return delimited.isEmpty() ? UNSTATED_DIAGNOSTIC : delimited.toString();
    }

    /**
     * Whether {@code next}, immediately after a {@code <}, would put an HTML tokeniser into a tag.
     *
     * <p>These four cases are the whole of the HTML tag-open state: {@code !} begins a markup
     * declaration, {@code /} an end tag, {@code ?} a bogus comment, and an ASCII letter a tag name.
     * For every other character — a space, a digit, {@code =}, another {@code <}, or the end of the
     * text — a tokeniser emits the {@code <} as ordinary text. So separating exactly these four is
     * sufficient to make the field unable to open a tag, and separating anything more would cost
     * legibility for no protection.</p>
     *
     * <p>{@code >} and {@code &} are deliberately not touched at all. A {@code >} cannot open
     * anything, and an {@code &} can at most produce a character reference, which is decoded after
     * tokenisation and can never itself become a tag.</p>
     */
    private static boolean opensTag(char next) {
        return next == '!' || next == '/' || next == '?'
                || (next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z');
    }
}
