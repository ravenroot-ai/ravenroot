// Pure, testable helpers for the artifact workbench's language selector.
//
// The defect this file exists to close: the workbench used to hard-code `language: 'javascript'`
// at the one place an artifact is created, so a runtime that already executes a second language
// (Python) had no way for an author to reach it. Adding a Python option is not sufficient --
// that would only move the hard-coding, and a third language would need this file edited again. It
// is that the workbench reads the language catalog from `GET /v1/program-languages`, through
// `RavenrootRuntimeClient#programLanguages`, and renders whatever comes back. Nothing here, and
// nothing in app.js's program-workspace wiring, names a language.
//
// `escapeHtml`/`escapeAttribute` are duplicated from app.js's own (byte-for-byte identical rules)
// rather than imported: app.js is the caller of this module, so importing the other way would be
// circular, and this module is small enough that the duplication is cheaper than a third shared
// module just for two one-line functions.

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char]);
}

function escapeAttribute(value) {
  return escapeHtml(value).replace(/`/g, '&#96;');
}

/**
 * The workbench's disclosure that a created artifact does not survive a server restart (AI-04):
 * `InMemoryArtifactRegistry` holds every artifact only in process memory. Shown where the artifact
 * is created, not discovered later after a restart empties the registry. A single exported
 * constant so the wording lives in exactly one place and a test can pin it.
 */
export const ARTIFACT_DURABILITY_NOTICE =
  'This artifact lives only in the running server’s memory. A server restart discards it, '
  + 'along with every other program artifact -- there is no durable store yet.';

/**
 * Renders `<option>` elements for the language `<select>`, in the order the runtime declared them.
 * Empty input renders no options; the caller is expected to show its own "no languages available"
 * state rather than treat an empty select as an error inside this function.
 */
export function programLanguageOptionsHtml(languages, selectedId) {
  return (languages || [])
    .filter(language => language && language.id)
    .map(language => {
      const selected = language.id === selectedId ? ' selected' : '';
      const label = language.displayName || language.id;
      return `<option value="${escapeAttribute(language.id)}"${selected}>${escapeHtml(label)}</option>`;
    })
    .join('');
}

/** The declared starter source for one language id, or '' if the id is not (or not yet) known. */
export function exampleSourceForLanguage(languages, id) {
  const match = (languages || []).find(language => language && language.id === id);
  return match ? (match.exampleSource || '') : '';
}

/**
 * Which language id the selector should default to once the catalog has loaded: `preferredId` when
 * the runtime still declares it (keeps a reopened workspace on the language it was already using),
 * otherwise the first language the runtime declared, otherwise '' when the runtime declares none at
 * all -- a legitimate answer (see `ProgramRuntime#supportedLanguages`'s own default), not a bug to
 * paper over with an invented fallback.
 */
export function defaultLanguageId(languages, preferredId) {
  const list = (languages || []).filter(language => language && language.id);
  if (preferredId && list.some(language => language.id === preferredId)) return preferredId;
  return list.length ? list[0].id : '';
}
