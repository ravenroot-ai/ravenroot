import { describe, expect, it, vi } from 'vitest';

import { createLayoutSessions } from '../src/layout-session.js';

describe('layout sessions', () => {
  it('captures a synchronous per-document owner snapshot and lets only the latest request win', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    const first = sessions.request({ documentId: 'one', cy, mode: 'cose' }).token;
    const second = sessions.request({ documentId: 'one', cy, mode: 'dagre' }).token;

    expect(first).toMatchObject({ documentId: 'one', cy, mode: 'cose' });
    expect(second.generation).toBeGreaterThan(first.generation);
    expect(Object.isFrozen(first)).toBe(true);
    expect(sessions.isCurrent(first)).toBe(false);
    expect(sessions.isLive(first)).toBe(false);
    expect(sessions.isCurrent(second)).toBe(true);
  });

  it('keeps document and Cytoscape identities independent', () => {
    const sessions = createLayoutSessions();
    const one = sessions.request({ documentId: 'one', cy: {}, mode: 'cose' }).token;
    const two = sessions.request({ documentId: 'two', cy: {}, mode: 'cose' }).token;

    expect(sessions.isCurrent(one)).toBe(true);
    expect(sessions.isCurrent(two)).toBe(true);
  });

  it('retires every callback on a close and never revives a reused document id', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    const old = sessions.request({ documentId: 'one', cy, mode: 'elk', nativeCancel: { stop: true } }).token;
    const closed = sessions.invalidate('one');
    const reopened = sessions.request({ documentId: 'one', cy, mode: 'elk' }).token;

    expect(closed.cancelled).toEqual([expect.objectContaining({ generation: old.generation, reason: 'invalidated' })]);
    expect(sessions.isCurrent(old)).toBe(false);
    expect(sessions.isCurrent(reopened)).toBe(true);
    expect(reopened.generation).toBeGreaterThan(old.generation);
  });

  it('replaces a binding when its Cytoscape identity changes', () => {
    const sessions = createLayoutSessions();
    const firstCy = {};
    const secondCy = {};
    const first = sessions.request({ documentId: 'one', cy: firstCy, mode: 'elk', nativeCancel: 'stop-first' }).token;
    const next = sessions.request({ documentId: 'one', cy: secondCy, mode: 'cose' });

    expect(next.cancelled).toEqual([expect.objectContaining({ generation: first.generation, reason: 'replaced' })]);
    expect(sessions.isCurrent(first)).toBe(false);
    expect(sessions.isCurrent(next.token)).toBe(true);
  });

  it('returns native cancellation metadata without executing it', () => {
    const sessions = createLayoutSessions();
    const stop = vi.fn();
    const cy = {};
    sessions.request({ documentId: 'one', cy, mode: 'cose', nativeCancel: stop });
    const next = sessions.request({ documentId: 'one', cy: {}, mode: 'dagre' });

    // The second request replaces the binding; state management is pure and
    // leaves the app in charge of invoking the supplied native cancellation.
    expect(next.cancelled).toEqual([expect.objectContaining({ nativeCancel: stop, reason: 'replaced' })]);
    expect(stop).not.toHaveBeenCalled();
  });

  it('serializes ELK per document and starts only the newest queued request after completion', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    const first = sessions.request({ documentId: 'one', cy, mode: 'elk' });
    const second = sessions.request({ documentId: 'one', cy, mode: 'elk' });
    const third = sessions.request({ documentId: 'one', cy, mode: 'elk' });

    expect(first.start).toBe(true);
    expect(second.start).toBe(false);
    expect(third.start).toBe(false);
    expect(third.cancelled).toEqual([
      expect.objectContaining({ generation: second.token.generation, reason: 'superseded' }),
    ]);
    expect(sessions.isCurrent(first.token)).toBe(false);
    expect(sessions.complete(first.token).start).toBe(third.token);
    expect(sessions.isCurrent(third.token)).toBe(true);
    expect(sessions.complete(third.token)).toEqual({ start: null });
  });

  it('queues a native layout behind ELK and releases it when the ELK settles', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    const elk = sessions.request({ documentId: 'one', cy, mode: 'elk' });
    const cose = sessions.request({ documentId: 'one', cy, mode: 'cose' });

    expect(cose.start).toBe(false);
    expect(sessions.complete(elk.token)).toEqual({ start: cose.token });
    expect(sessions.isCurrent(cose.token)).toBe(true);
  });

  it('keeps only the newest request while ELK is running, even when its kind changes', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    const elk = sessions.request({ documentId: 'one', cy, mode: 'elk' });
    const cose = sessions.request({ documentId: 'one', cy, mode: 'cose' });
    const nextElk = sessions.request({ documentId: 'one', cy, mode: 'elk' });

    expect(cose.start).toBe(false);
    expect(nextElk.start).toBe(false);
    expect(sessions.complete(elk.token)).toEqual({ start: nextElk.token });
    expect(sessions.complete(nextElk.token)).toEqual({ start: null });
  });

  // The registry claims the serialization slot at REQUEST time, for any ELK-kind mode, and
  // only `complete` gives it back. That is a caller obligation, and the caller used to honour it in
  // one place only: the `layoutstop` handler of a native layout. A request that finishes WITHOUT
  // ever constructing a native layout — a preserved-position load assigns coordinates and fits —
  // therefore held the slot forever, and every later request for that document queued behind a
  // layout that had already finished. The symptom was silent: the toolbar showed the new mode as
  // active while the renderer never changed.
  // `kind` is passed explicitly here because the registry's own default only recognises the literal
  // mode `'elk'`. Which modes are ELK-backed is the caller's knowledge — `app.js` holds that in
  // `ELK_LAYOUT_MODES` (`elk`, `n8n`, `n8n2`, `n8n3`, `n8n4`, `cyto`) — so these tests mirror the
  // call the application actually makes rather than the registry's convenience fallback.
  it('strands every later request when an ELK-kind claim is never completed', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    // A preserved-position load: ELK-kind mode, so the slot is claimed even though the caller will
    // position the nodes itself and no `layoutstop` will ever arrive.
    const preserved = sessions.request({ documentId: 'one', cy, mode: 'n8n', kind: 'elk' });
    expect(preserved.start).toBe(true);

    const stranded = sessions.request({ documentId: 'one', cy, mode: 'elastic', kind: 'native' });
    expect(stranded.start).toBe(false);
    // Nothing else can rescue it: the slot is only released by completing the token that holds it.
    expect(sessions.request({ documentId: 'one', cy, mode: 'dagre', kind: 'native' }).start).toBe(false);
  });

  it('releases a queued request as soon as the holder settles, whatever ended it', () => {
    const sessions = createLayoutSessions();
    const cy = {};
    const preserved = sessions.request({ documentId: 'one', cy, mode: 'n8n', kind: 'elk' });
    const elastic = sessions.request({ documentId: 'one', cy, mode: 'elastic', kind: 'native' });

    expect(elastic.start).toBe(false);
    // The caller settles the preserved-position request directly, with no native layout involved.
    expect(sessions.complete(preserved.token)).toEqual({ start: elastic.token });
    expect(sessions.isCurrent(elastic.token)).toBe(true);
    // Releasing a native request does not re-claim the slot, so the document is free again.
    expect(sessions.request({ documentId: 'one', cy, mode: 'dagre', kind: 'native' }).start).toBe(true);
  });
});
