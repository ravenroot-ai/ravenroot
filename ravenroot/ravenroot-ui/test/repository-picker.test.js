import { describe, expect, it } from 'vitest';
import { managedRepositoryLaunch } from '../src/repository-picker.js';

describe('managed repository startup contract', () => {
  it('keeps content imports separate and defaults managed sources to draft', () => {
    expect(managedRepositoryLaunch('?file=orders.graphml')).toBeNull();
    expect(managedRepositoryLaunch('?repositoryDocument=orders.graphml')).toEqual({ documentId: 'orders.graphml', source: 'draft' });
    expect(managedRepositoryLaunch('?repositoryDocument=orders.graphml&source=release&tenant=ignored')).toEqual({ documentId: 'orders.graphml', source: 'release' });
  });
  for (const query of ['?source=draft', '?repositoryDocument=', '?repositoryDocument=a&source=',
    '?repositoryDocument=a&source=invalid', '?repositoryDocument=a&file=a',
    '?repositoryDocument=a&repositoryDocument=b', '?repositoryDocument=a&source=draft&source=release']) {
    it(`rejects ambiguous or invalid launch ${query}`, () => expect(() => managedRepositoryLaunch(query)).toThrow());
  }
});

import { afterEach, beforeEach, vi } from 'vitest';
import { chooseRepositoryDocument } from '../src/repository-picker.js';

describe('repository table and progressive inventory', () => {
  const item = (id, extra = {}) => ({ documentId: id, releaseVersion: 2, releasedVersion: 1, released: true, published: true, ...extra });
  const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal = vi.fn();
    HTMLDialogElement.prototype.close = vi.fn();
    vi.stubGlobal('requestAnimationFrame', fn => { queueMicrotask(fn); return 1; });
  });
  afterEach(() => { document.querySelector('dialog footer button:last-child')?.click(); document.body.replaceChildren(); vi.unstubAllGlobals(); });
  it('uses distinct columns and returns the exact chosen source', async () => {
    const result = chooseRepositoryDocument({ list: vi.fn().mockResolvedValue({ items: [item('flows/mail.graphml')], nextCursor: '' }) });
    await settle();
    expect([...document.querySelectorAll('th')].map(el => el.textContent)).toEqual(['Name', 'Source', 'Version', 'Latest release', 'Publication']);
    expect(document.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(document.querySelector('[role=status]').textContent).toContain('1 documents · 2 matching sources');
    document.querySelectorAll('tbody button')[1].click();
    expect(await result).toEqual({ documentId: 'flows/mail.graphml', source: 'release' });
  });
  it('loads the next page on scroll, without overlapping requests or duplicate rows', async () => {
    const list = vi.fn().mockResolvedValueOnce({ items: [item('first')], nextCursor: 'p2' }).mockResolvedValueOnce({ items: [item('first'), item('second')], nextCursor: '' });
    chooseRepositoryDocument({ list }); await settle();
    const viewport = document.querySelector('.repository-picker-list');
    Object.defineProperty(viewport, 'clientHeight', { value: 200 });
    Object.defineProperty(viewport, 'scrollHeight', { value: 500 });
    viewport.scrollTop = 290;
    viewport.dispatchEvent(new Event('scroll')); viewport.dispatchEvent(new Event('scroll'));
    await settle();
    expect(list.mock.calls).toEqual([[''], ['p2']]);
    expect(document.querySelectorAll('tbody tr')).toHaveLength(4);
    expect(document.querySelector('[role=status]').textContent).toContain('All repository documents loaded');
  });
  it('searches unseen pages and stops on failure until explicit retry', async () => {
    const list = vi.fn().mockResolvedValueOnce({ items: [item('first')], nextCursor: 'p2' }).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ items: [item('target')], nextCursor: '' });
    chooseRepositoryDocument({ list }); await settle();
    const search = document.querySelector('input'); search.value = 'TARGET'; search.dispatchEvent(new Event('input')); await settle();
    expect(document.querySelector('[role=status]').textContent).toContain('inventory is incomplete');
    expect(list).toHaveBeenCalledTimes(2);
    document.querySelector('footer button').click(); await settle();
    expect(document.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(document.querySelector('tbody button').textContent).toBe('target');
  });
  it('does not request another page after closing a pending load', async () => {
    let done; const list = vi.fn(() => new Promise(resolve => { done = resolve; }));
    const result = chooseRepositoryDocument({ list });
    document.querySelector('footer button:last-child').click();
    done({ items: [], nextCursor: 'p2' }); await settle();
    expect(await result).toBeNull(); expect(list).toHaveBeenCalledTimes(1);
  });
});
