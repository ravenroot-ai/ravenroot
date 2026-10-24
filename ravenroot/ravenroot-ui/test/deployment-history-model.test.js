import { describe, expect, it, vi } from 'vitest';
import { createBoundHistoryReader, createHistoryPager } from '../src/deployment-history-model.js';
const source = { kind: 'deployment', deploymentId: 'orders', incarnationId: 'runtime', graphVersion: 'pin' };
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const response = (query, overrides = {}) => ({ source, view: query.view, availability: 'AVAILABLE', items: [], nextCursor: null, ...overrides });
describe('source-bound deployment history', () => {
  it('passes only the captured source selection and refuses another response source', async () => {
    const read = vi.fn(async query => response(query, { source: { ...source, incarnationId: 'replacement' } }));
    const reader = createBoundHistoryReader({ source, read });
    await expect(reader.read({ view: 'nodes', processInstanceId: 'p', traversalId: 't', limit: 1 })).rejects.toThrow('Invalid bounded');
    expect(read.mock.calls[0][0]).toEqual({ view: 'nodes', processInstanceId: 'p', traversalId: 't', limit: '1' });
  });
  it.each(['destroy', 'invalidate', 'authority'])('retires delayed results on %s even if transport ignores cancellation', async kind => {
    const gate = deferred(); let current = true;
    const reader = createBoundHistoryReader({ source, read: () => gate.promise, isCurrent: () => current });
    const request = reader.read({ view: 'visits', limit: 10 });
    if (kind === 'authority') current = false; else reader[kind]();
    gate.resolve(response({ view: 'visits' })); await expect(request).rejects.toMatchObject({ name: 'AbortError' });
  });
  it.each([
    { items: Array.from({ length: 11 }, () => ({})) }, { availability: 'EXPIRED', items: [{}] },
    { availability: 'invented' }, { nextCursor: 'x'.repeat(32769) }, { view: 'attempts' },
  ])('refuses malformed or unbounded pages before rendering %j', async changed => {
    const reader = createBoundHistoryReader({ source, read: async query => response(query, changed) });
    await expect(reader.read({ view: 'visits', limit: 10 })).rejects.toThrow('Invalid bounded');
  });
  it('retains truthful old edge projection availability without pretending zero counts', async () => {
    const reader = createBoundHistoryReader({ source, read: async query => response(query, { availability: 'NOT_RECORDED' }) });
    expect(await reader.read({ view: 'edges', limit: 1 })).toMatchObject({ availability: 'NOT_RECORDED', items: [] });
  });
});
describe('bounded history paging', () => {
  it('replaces thousands of rows page by page and bounds remembered cursors', async () => {
    const read = vi.fn(async query => {
      const offset = Number(query.cursor || 0), limit = Number(query.limit);
      return response(query, { items: Array.from({ length: limit }, (_, i) => ({ applicationCorrelationId: `ingestion-${offset + i}` })), nextCursor: offset + limit < 3000 ? String(offset + limit) : null });
    });
    const reader = createBoundHistoryReader({ source, read });
    const pager = createHistoryPager({ reader, query: { view: 'histories' }, limit: 10, maximumPreviousPages: 3 });
    await pager.load(); for (let i = 0; i < 299; i++) await pager.next();
    expect(read).toHaveBeenCalledTimes(300); expect(pager.snapshot().page.items).toHaveLength(10);
    expect(pager.snapshot()).toMatchObject({ hasNext: false, hasPrevious: true, rememberedPages: 3 });
    expect(pager.snapshot().page.items[0].applicationCorrelationId).toBe('ingestion-2990');
    await pager.previous(); expect(pager.snapshot().page.items[0].applicationCorrelationId).toBe('ingestion-2980');
  });
  it('coalesces double-next and keeps one in-flight request', async () => {
    const gate = deferred(); const reader = createBoundHistoryReader({ source, read: () => gate.promise });
    const pager = createHistoryPager({ reader, query: { view: 'traversals' } });
    const first = pager.load(); expect(pager.next()).toBe(first);
    gate.resolve(response({ view: 'traversals' })); await first; expect(pager.snapshot().busy).toBe(false);
  });
  it('never publishes an earlier selection after reset', async () => {
    const gate = deferred(), pages = [];
    const reader = createBoundHistoryReader({ source, read: query => query.applicationCorrelationId === 'old' ? gate.promise : Promise.resolve(response(query, { items: [{ applicationCorrelationId: 'new' }] })) });
    const pager = createHistoryPager({ reader, query: { view: 'traversals', applicationCorrelationId: 'old' }, onChange: state => { if (state.page) pages.push(state.page); } });
    const stale = pager.load(); await Promise.resolve();
    await pager.reset({ view: 'traversals', applicationCorrelationId: 'new' });
    gate.resolve(response({ view: 'traversals' }, { items: [{ applicationCorrelationId: 'old' }] }));
    await expect(stale).rejects.toMatchObject({ name: 'AbortError' });
    expect(pages.every(page => page.items[0].applicationCorrelationId === 'new')).toBe(true);
  });
});


it('bounds a hostile transport that ignores abort across repeated retired requests', async () => {
  const pending = [];
  const source = { kind: 'deployment', deploymentId: 'd', graphVersion: 'v', incarnationId: 'i' };
  const reader = createBoundHistoryReader({ source, read: () => new Promise(resolve => pending.push(resolve)) });
  const reads = [];
  for (let i = 0; i < 32; i++) { reads.push(reader.read({ view: 'histories', limit: 1 }).catch(error => error.name)); reader.invalidate(); }
  await expect(reader.read({ view: 'histories', limit: 1 })).rejects.toThrow('concurrency budget');
  expect(pending).toHaveLength(32);
  for (const resolve of pending) resolve({ source, view: 'histories', items: [], availability: 'AVAILABLE', nextCursor: null });
  expect(await Promise.all(reads)).toEqual(Array(32).fill('AbortError'));
  reader.destroy();
});
