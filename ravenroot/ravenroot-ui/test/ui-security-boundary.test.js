import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

describe('UI browser security boundary', () => {
  it('exposes accessible authentication and revocation controls with explicit storage disclosure', async () => {
    const html = await readFile('index.html', 'utf8');

    expect(html).toContain('id="access-token" type="password"');
    expect(html).toContain('autocomplete="off"');
    expect(html).toContain('id="btn-revoke"');
    expect(html).toContain('aria-describedby="remember-connection-disclosure"');
    expect(html).toContain('readable by page JavaScript');
    expect(html).toContain('id="runtime-connection"');
    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
    expect(html).not.toMatch(/\bon(?:click|change|input)=/);
  });

  it('does not persist an origin trust bypass, anonymous EventSource or raw D3 HTML', async () => {
    const [app, runtime] = await Promise.all([
      readFile('src/app.js', 'utf8'),
      readFile('src/runtime-client.js', 'utf8'),
    ]);

    // Browser tests assert decline leaves no credential anywhere in storage and opt-in permits
    // only the closed connection record. Arbitrary writes still fail the canary-backed assertion.
    expect(app).not.toContain("params.get('service')");
    expect(app).not.toMatch(/d3tip\.html\(/);
    expect(runtime).not.toContain('EventSource');
    expect(runtime).toContain("credentials: 'omit'");
    expect(runtime).toContain("'Last-Event-ID'");
  });
});
