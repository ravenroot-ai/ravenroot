import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

describe('UI browser security boundary', () => {
  it('exposes accessible authentication and revocation controls without persistent-token hints', async () => {
    const html = await readFile('index.html', 'utf8');

    expect(html).toContain('id="access-token" type="password"');
    expect(html).toContain('autocomplete="off"');
    expect(html).toContain('id="btn-revoke"');
    expect(html).toContain('id="runtime-connection"');
    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
    expect(html).not.toMatch(/\bon(?:click|change|input)=/);
  });

  it('does not reintroduce service trust persistence, anonymous EventSource or raw D3 HTML', async () => {
    const [app, runtime] = await Promise.all([
      readFile('src/app.js', 'utf8'),
      readFile('src/runtime-client.js', 'utf8'),
    ]);

    // A whole-file `localStorage` text search is an invalid proxy for the property defended here:
    // NO TOKEN AND NO SERVICE TRUST DECISION IS PERSISTED. The UI legitimately persists panel
    // layout, and a text search cannot distinguish that safe state from token or trust persistence.
    // Narrowing the search to selected spellings would produce a weaker guard wearing the same name.
    //
    // The browser test therefore guards what actually reaches storage, whichever file
    // put it there: `e2e/security-boundary.spec.js`, "persists the panel layout and never the
    // token". That test ships with a red control that injects a token write and shows the
    // assertion still fires. MOVING THE STORE TO ANOTHER MODULE WOULD NOT EVADE IT, which is the
    // property the text search never had.
    expect(app).not.toContain("params.get('service')");
    expect(app).not.toMatch(/d3tip\.html\(/);
    expect(runtime).not.toContain('EventSource');
    expect(runtime).toContain("credentials: 'omit'");
    expect(runtime).toContain("'Last-Event-ID'");
  });
});
