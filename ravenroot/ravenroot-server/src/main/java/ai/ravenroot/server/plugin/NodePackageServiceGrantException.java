package ai.ravenroot.server.plugin;

/**
 * One operator-authored node package service grant could not be read.
 *
 * <p>This is deliberately a distinct type rather than a bare {@link IllegalArgumentException}, so
 * {@link PluginActivationDiagnostics} can tell "the operator wrote a grant and got it wrong" apart
 * from "a package refused registration", and can name the variable that has to be fixed. It carries
 * the <em>name</em> of the offending environment variable and never its value: the value is operator
 * input of unbounded shape, and on the credential-binding path it sits next to references that must
 * not reach a console or an audit record.</p>
 */
public final class NodePackageServiceGrantException extends RuntimeException {

    private final String variableName;

    NodePackageServiceGrantException(String variableName, String message) {
        super(message);
        this.variableName = variableName;
    }

    NodePackageServiceGrantException(String variableName, String message, Throwable cause) {
        super(message, cause);
        this.variableName = variableName;
    }

    /** The environment variable whose value could not be read. Never the value itself. */
    public String variableName() {
        return variableName;
    }
}
