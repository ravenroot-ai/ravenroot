import { describe, expect, it, vi } from 'vitest';
import { readUiCatalogFile } from '../src/language-dialog.js';
import { MAX_UI_CATALOG_BYTES } from '../src/ui-localization.js';

describe('language dialog catalog import', () => {
  it('rejects an oversized catalog before reading the file body', async () => {
    const text = vi.fn();

    await expect(readUiCatalogFile({ size: MAX_UI_CATALOG_BYTES + 1, text }))
      .rejects.toThrow(/too large/i);
    expect(text).not.toHaveBeenCalled();
  });

  it('reads and parses a catalog within the import bound', async () => {
    const catalog = { schema: 'ravenroot.ui-catalog/v1', locale: 'en-XA', messages: {} };
    const text = vi.fn(async () => JSON.stringify(catalog));

    await expect(readUiCatalogFile({ size: 128, text })).resolves.toEqual(catalog);
    expect(text).toHaveBeenCalledOnce();
  });
});
