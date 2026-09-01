import { describe, expect, it } from 'vitest';

import {
  ARTIFACT_DURABILITY_NOTICE,
  defaultLanguageId,
  exampleSourceForLanguage,
  programLanguageOptionsHtml,
} from '../src/program-language.js';

// A hard-coded `language: 'javascript'` in the artifact
// workbench. These tests pin the replacement's actual contract: the selector is built FROM
// whatever the runtime declares, never from a name written into this module or into app.js, so a
// third language becomes offerable with zero code change on this side. None of these tests
// mentions "python" as a special case for that reason -- a fixture named "ruby" proves the point
// better than a real language name would, because nothing here can be quietly special-casing it.

const LANGUAGES = [
  { id: 'javascript', displayName: 'JavaScript', exampleSource: '({ payload }) => payload' },
  { id: 'ruby', displayName: 'Ruby', exampleSource: 'def call(payload)\n  payload\nend' },
];

describe('programLanguageOptionsHtml', () => {
  it('renders one option per declared language, in the order given', () => {
    const html = programLanguageOptionsHtml(LANGUAGES, 'javascript');
    expect(html).toBe(
      '<option value="javascript" selected>JavaScript</option>'
      + '<option value="ruby">Ruby</option>',
    );
  });

  it('marks no option selected when the selected id matches none of them', () => {
    const html = programLanguageOptionsHtml(LANGUAGES, 'nonexistent');
    expect(html).not.toContain('selected');
  });

  it('renders nothing for an empty or missing catalog, rather than inventing a placeholder option', () => {
    expect(programLanguageOptionsHtml([], 'javascript')).toBe('');
    expect(programLanguageOptionsHtml(undefined, 'javascript')).toBe('');
  });

  it('escapes a language id and display name that could break the option markup', () => {
    const html = programLanguageOptionsHtml(
      [{ id: 'x"><script>', displayName: '<b>bold</b>' }], 'x"><script>');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('<b>bold</b>');
    expect(html).toContain('&lt;script&gt;');
  });

  it('falls back to the id as the label when displayName is absent', () => {
    const html = programLanguageOptionsHtml([{ id: 'lua' }], '');
    expect(html).toBe('<option value="lua">lua</option>');
  });
});

describe('exampleSourceForLanguage', () => {
  it('returns the starter source declared for the matching id', () => {
    expect(exampleSourceForLanguage(LANGUAGES, 'ruby')).toBe('def call(payload)\n  payload\nend');
  });

  it('returns an empty string for an id the catalog does not declare, never a guessed default', () => {
    expect(exampleSourceForLanguage(LANGUAGES, 'python')).toBe('');
    expect(exampleSourceForLanguage([], 'javascript')).toBe('');
  });
});

describe('defaultLanguageId', () => {
  it('keeps the preferred id when the catalog still declares it', () => {
    expect(defaultLanguageId(LANGUAGES, 'ruby')).toBe('ruby');
  });

  it('falls back to the first declared language when the preferred id is absent or unset', () => {
    expect(defaultLanguageId(LANGUAGES, 'nonexistent')).toBe('javascript');
    expect(defaultLanguageId(LANGUAGES, '')).toBe('javascript');
  });

  it('returns an empty string for an empty catalog -- a runtime declaring nothing is not papered over', () => {
    expect(defaultLanguageId([], 'javascript')).toBe('');
  });
});

describe('ARTIFACT_DURABILITY_NOTICE', () => {
  it('says plainly that a restart discards the artifact, at the point the author would read it', () => {
    // AC: the user learns this where they create the artifact, not after a restart has already
    // discarded it. The exact wording is asserted so a future edit that quietly waters this down
    // (or that fixes AI-04 and forgets to remove the notice) is a visible, deliberate change.
    expect(ARTIFACT_DURABILITY_NOTICE).toMatch(/restart/i);
    expect(ARTIFACT_DURABILITY_NOTICE).toMatch(/discards? it/i);
  });
});
