package ai.ravenroot.api.programming;

/**
 * What a program runtime declares about one language it accepts.
 *
 * <p>{@code id} is the exact token an editor must send back as the {@code language} parameter of
 * {@code createProgramArtifact} — the same alias {@code ProgramRuntime} implementations already
 * resolve internally, republished rather than duplicated, so a runtime cannot declare a language its
 * own artifact-creation path would then refuse. {@code exampleSource} is a starter an author can run
 * unmodified: this repository's runtime measured that "JavaScript with different syntax" is not a
 * safe assumption for a new language (see {@code ProgramLanguage}'s own Javadoc for the Python
 * evidence), so a generic placeholder would be more likely to mislead an author than a real one is to
 * go stale.
 *
 * <p>Declared in this module, not in an adapter, because the SPI method that returns it
 * ({@link ProgramRuntime#supportedLanguages()}) lives here: an editor discovers languages through the
 * runtime boundary, never by importing an adapter's own types or by enumerating them by hand. This
 * keeps a newly supported language selectable without an editor change.
 * @param id exact language token accepted by the runtime adapter.
 * @param displayName human-readable label to show in an editor.
 * @param exampleSource runnable starter source; empty when no starter is supplied.
 */
public record ProgramLanguageDescriptor(String id, String displayName, String exampleSource) {
/**
 * Rejects blank runtime tokens and labels while normalizing a missing starter to empty text.
 */
    public ProgramLanguageDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Program language id cannot be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Program language display name cannot be blank");
        }
        exampleSource = exampleSource == null ? "" : exampleSource;
    }
}
