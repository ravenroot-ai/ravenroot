import { expect, it, vi } from 'vitest';
import { createUiLocalization, useUiLocalization } from '../src/ui-text.js';
import { createDrawingModelController } from '../src/drawing-model-controller.js';
import { packageFromFiles, installFrontendPackage, listFrontendPackages } from '../src/frontend-plugin-store.js';
import { renderUiMessage } from '../src/ui-message.js';

vi.mock('../src/frontend-plugin-store.js', () => ({
  packageFromFiles: vi.fn(), installFrontendPackage: vi.fn(), listFrontendPackages: vi.fn(),
  removeFrontendPackage: vi.fn(), setFrontendPackageEnabled: vi.fn(),
}));

it('captures drawing package identity in its existing notification callback and preserves string edit labels', async () => {
  document.body.innerHTML = `<select id="drawing-model-select"><option value="integrated">Integrated</option></select>
    <input id="drawing-model-package-input" type="file"><button id="drawing-model-install"></button>
    <button id="drawing-model-manage"></button><button id="drawing-model-mapping"></button>
    <button id="drawing-model-full-flow"></button><dialog id="drawing-model-dialog"><form></form><button data-drawing-model-cancel></button></dialog>
    <dialog id="drawing-model-package-dialog"><button data-drawing-model-package-close></button></dialog>
    <div id="drawing-model-package-list"></div><textarea id="drawing-model-mapping-json"></textarea><p id="drawing-model-error"></p>`;
  const context = createUiLocalization({ locale: 'fr', catalogs: Object.fromEntries(['fr', 'de'].map(locale => [locale, {
    'workspace.drawing.model.controller.installed.frontend.package': `${locale}[{name}|{version}]`,
    'workspace.drawing.model.controller.select.drawing.model': `${locale}_SELECT`,
  }])) });
  useUiLocalization(context);
  const package_ = { manifest: { name: '<authored Ω>', version: 'original' } };
  packageFromFiles.mockResolvedValue(package_); installFrontendPackage.mockResolvedValue(); listFrontendPackages.mockResolvedValue([]);
  const notify = vi.fn(); const edit = vi.fn();
  const controller = createDrawingModelController({ document, getContext: () => null, notify, editGraphProperties: edit });
  try {
    document.getElementById('drawing-model-package-input').dispatchEvent(new Event('change'));
    await vi.waitFor(() => expect(notify).toHaveBeenCalledTimes(1));
    const [render, state] = notify.mock.calls[0];
    expect(typeof render).toBe('function'); expect(state).toBe('completed');
    package_.manifest.name = 'later'; package_.manifest.version = 'later';
    document.getElementById('drawing-model-select').dispatchEvent(new Event('change'));
    expect(edit.mock.calls[0][2]).toBe('fr_SELECT');
    for (const locale of ['fr', 'de', 'fr']) {
      context.setLocale(locale);
      expect(render()).toBe(`${locale}[<authored Ω>|original]`);
      expect(renderUiMessage(edit.mock.calls[0][3], context.text)).toBe(`${locale}_SELECT`);
    }
    expect(installFrontendPackage).toHaveBeenCalledTimes(1);
  } finally { controller.destroy(); useUiLocalization(createUiLocalization()); document.body.replaceChildren(); }
});
