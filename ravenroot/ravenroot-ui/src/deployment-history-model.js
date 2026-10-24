const VIEWS = new Set(['policy', 'histories', 'traversals', 'uncorrelated-traversals', 'process-traversals',
  'nodes', 'edges', 'visits', 'attempts', 'parents', 'joins', 'join-members', 'activity']);
const AVAILABILITY = new Set(['AVAILABLE', 'NOT_FOUND', 'EXPIRED', 'NOT_RECORDED', 'CAPTURE_DISABLED', 'NOT_RETAINED']);
const abortFailure = () => new DOMException('History selection retired', 'AbortError');
const sourceEqual = (a, b) => a?.kind === 'deployment' && b?.kind === 'deployment'
  && a.deploymentId === b.deploymentId && a.incarnationId === b.incarnationId && a.graphVersion === b.graphVersion;

/** One captured source, one finite response budget, and no stale asynchronous publication. */
export function createBoundHistoryReader({ source, read, isCurrent = () => true }) {
  const captured = Object.freeze({ kind: 'deployment', deploymentId: source.deploymentId,
    incarnationId: source.incarnationId, graphVersion: source.graphVersion });
  if (![captured.deploymentId, captured.incarnationId, captured.graphVersion].every(value => typeof value === 'string' && value))
    throw new TypeError('History requires an exact source');
  let generation = 0, destroyed = false, transportCount = 0;
  const pending = new Set();
  return Object.freeze({
    source: captured,
    async read(query, { signal } = {}) {
      if (destroyed || !isCurrent() || signal?.aborted) throw abortFailure();
      const fields = Object.freeze(Object.fromEntries(Object.entries(query).filter(([, value]) => value != null).map(([name, value]) => [name, String(value)])));
      const limit = Number(fields.limit || 50);
      if (!VIEWS.has(fields.view || 'histories') || !Number.isSafeInteger(limit) || limit < 1 || limit > 1000)
        throw new RangeError('Invalid history page request');
      if (transportCount >= 32) throw new RangeError('History transport concurrency budget exceeded');
      const token = generation, controller = new AbortController(); pending.add(controller); transportCount++;
      const abort = () => controller.abort(); signal?.addEventListener('abort', abort, { once: true });
      try {
        const page = await read(fields, { signal: controller.signal });
        if (destroyed || token !== generation || controller.signal.aborted || !isCurrent()) throw abortFailure();
        if (!sourceEqual(captured, page?.source) || page.view !== (fields.view || 'histories')
            || !Array.isArray(page.items) || page.items.length > limit || !AVAILABILITY.has(page.availability)
            || page.items.some(item => !item || typeof item !== 'object' || Array.isArray(item))
            || page.nextCursor != null && (typeof page.nextCursor !== 'string' || !page.nextCursor || page.nextCursor.length > 32768)
            || page.availability !== 'AVAILABLE' && (page.items.length || page.nextCursor))
          throw new TypeError('Invalid bounded history response');
        return Object.freeze({ source: captured, view: page.view, items: Object.freeze(page.items),
          nextCursor: page.nextCursor || null, availability: page.availability, gap: page.gap === true,
          contentPolicy: page.contentPolicy || null });
      } finally { signal?.removeEventListener('abort', abort); pending.delete(controller); transportCount--; }
    },
    invalidate() { generation++; for (const controller of pending) controller.abort(); pending.clear(); },
    destroy() { destroyed = true; generation++; for (const controller of pending) controller.abort(); pending.clear(); },
  });
}

/** Replaces each rendered page; a finite cursor trail supports previous/next without retaining rows. */
export function createHistoryPager({ reader, query, limit = 10, onChange = () => {}, maximumPreviousPages = 128 }) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 1000 || !Number.isSafeInteger(maximumPreviousPages) || maximumPreviousPages < 1 || maximumPreviousPages > 1024)
    throw new RangeError('Invalid pager budget');
  let selection = Object.freeze({ ...query }), cursors = [null], position = 0, page = null, controller = null;
  let generation = 0, flight = null, destroyed = false, failure = null;
  const snapshot = () => Object.freeze({ page, busy: Boolean(flight), failure,
    hasPrevious: position > 0, hasNext: Boolean(page?.nextCursor), rememberedPages: cursors.length });
  const publish = () => onChange(snapshot());
  const cancel = () => { generation++; controller?.abort(); controller = null; flight = null; };
  const load = () => {
    if (destroyed) return Promise.reject(abortFailure());
    if (flight) return flight;
    const token = generation, abort = new AbortController(); controller = abort; failure = null;
    const promise = (async () => {
      // Let flight become visible before a synchronous read rejection or an immediate response.
      await Promise.resolve();
      try {
        const next = await reader.read({ ...selection, limit, ...(cursors[position] ? { cursor: cursors[position] } : {}) }, { signal: abort.signal });
        if (destroyed || token !== generation || abort.signal.aborted) throw abortFailure();
        page = next; return next;
      } catch (error) {
        if (token === generation && error?.name !== 'AbortError') failure = error;
        throw error;
      } finally {
        if (token === generation && flight === promise) { flight = null; controller = null; publish(); }
      }
    })();
    flight = promise; publish(); return promise;
  };
  return Object.freeze({
    snapshot,
    load,
    async reset(nextQuery = selection) {
      cancel(); selection = Object.freeze({ ...nextQuery }); cursors = [null]; position = 0; page = null; failure = null; publish(); return load();
    },
    next() {
      if (flight) return flight;
      if (!page?.nextCursor) return Promise.resolve(page);
      cursors = cursors.slice(0, position + 1); cursors.push(page.nextCursor); position++;
      if (cursors.length > maximumPreviousPages) { cursors.shift(); position--; }
      page = null; return load();
    },
    previous() {
      if (flight) return flight;
      if (position === 0) return Promise.resolve(page);
      position--; page = null; return load();
    },
    refresh() { if (flight) return flight; return load(); },
    destroy() { destroyed = true; cancel(); page = null; cursors = []; },
  });
}
