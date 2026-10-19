import { expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

it('enforces eager-retention negatives and deferred/authored positives through the production CLI', () => {
  const root = path.resolve(import.meta.dirname, '..');
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'ravenroot-retention-cli-'));
  const fixture = path.join(temporary, 'ravenroot-ui');
  try {
    fs.mkdirSync(fixture);
    for (const name of ['src', 'scripts', 'public', 'index.html', 'package.json']) {
      fs.cpSync(path.join(root, name), path.join(fixture, name), { recursive: true });
    }
    fs.symlinkSync(path.join(root, 'node_modules'), path.join(fixture, 'node_modules'), 'dir');
    const embed = 'ravenroot-server/src/main/java/ai/ravenroot/server/embed/EmbedBrowserHttpHandler.java';
    fs.mkdirSync(path.dirname(path.join(temporary, embed)), { recursive: true });
    fs.copyFileSync(path.join(root, '..', embed), path.join(temporary, embed));
    const cases = [
      [`uiMessage('workspace.app.incident', { incidentId: uiText('workspace.app.existing') });`, false],
      [`const state = uiText('workspace.app.new'); const params = { incidentId: state }; uiMessage('workspace.app.incident', params);`, false],
      [`addActivityMessage(uiMessage('workspace.notice.editor.23'), uiErrorReason(error));`, false],
      [`const authored = request.url; addActivityMessage(uiMessage('workspace.notice.editor.23'), uiMessage('workspace.app.incident', { incidentId: authored })); const captured = request.id; addActivityMessage(uiMessage('workspace.notice.editor.23'), uiComputed(() => uiText('workspace.app.incident', { incidentId: captured })));`, true],
    ];
    for (const [source, pass] of cases) {
      fs.writeFileSync(path.join(fixture, 'src', 'retention-probe.js'), source);
      const result = spawnSync(process.execPath, ['scripts/check-ui-i18n.mjs'], { cwd: fixture, encoding: 'utf8', timeout: 20000 });
      expect(result.error).toBeUndefined();
      expect(result.status, result.stderr).toBe(pass ? 0 : 1);
      if (!pass) expect(result.stderr).toContain('eager translation loses retained activity message semantics');
      else expect(result.stdout).toContain('0 inline sink violations');
    }
  } finally { fs.rmSync(temporary, { recursive: true, force: true }); }
}, 90000);
