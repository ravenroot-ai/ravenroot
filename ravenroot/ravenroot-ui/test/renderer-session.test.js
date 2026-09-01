import { describe, expect, it } from 'vitest';

import { createRendererSessions } from '../src/renderer-session.js';

describe('renderer sessions', () => {
  it('captures an immutable per-document renderer generation', () => {
    const sessions = createRendererSessions();
    const renderer = {};
    const { token } = sessions.register({ documentId: 'one', renderer, kind: 'cytoscape' });

    expect(token).toMatchObject({ documentId: 'one', renderer, kind: 'cytoscape' });
    expect(Object.isFrozen(token)).toBe(true);
    expect(sessions.isCurrent(token)).toBe(true);
    expect(sessions.isLive(token)).toBe(true);
    expect(sessions.isSuspended(token)).toBe(false);
  });

  it('keeps renderer ownership independent for simultaneous documents', () => {
    const sessions = createRendererSessions();
    const first = sessions.register({ documentId: 'one', renderer: {} }).token;
    const second = sessions.register({ documentId: 'two', renderer: {} }).token;

    expect(sessions.isLive(first)).toBe(true);
    expect(sessions.isLive(second)).toBe(true);
  });

  it('idempotently retains a current matching renderer binding', () => {
    const sessions = createRendererSessions();
    const renderer = {};
    const first = sessions.register({ documentId: 'one', renderer });
    const repeated = sessions.register({ documentId: 'one', renderer });

    expect(repeated).toEqual({ token: first.token, registered: false, retired: null });
    expect(repeated.token.generation).toBe(first.token.generation);
  });

  it('retires a replaced renderer and never lets its generation become current', () => {
    const sessions = createRendererSessions();
    const first = sessions.register({ documentId: 'one', renderer: {}, kind: 'cytoscape' }).token;
    const next = sessions.register({ documentId: 'one', renderer: {}, kind: 'd3' });

    expect(next.retired).toEqual(expect.objectContaining({
      generation: first.generation, state: 'destroyed', reason: 'replaced', kind: 'cytoscape',
    }));
    expect(sessions.isCurrent(first)).toBe(false);
    expect(sessions.isLive(next.token)).toBe(true);
  });

  it('suspends and resumes a current generation once each', () => {
    const sessions = createRendererSessions();
    const token = sessions.register({ documentId: 'one', renderer: {} }).token;

    expect(sessions.suspend(token)).toEqual({
      changed: true,
      action: expect.objectContaining({ state: 'suspended', generation: token.generation }),
    });
    expect(sessions.isCurrent(token)).toBe(true);
    expect(sessions.isLive(token)).toBe(false);
    expect(sessions.isSuspended(token)).toBe(true);
    expect(sessions.suspend(token)).toEqual({ changed: false, action: null });

    expect(sessions.resume(token)).toEqual({
      changed: true,
      action: expect.objectContaining({ state: 'live', generation: token.generation }),
    });
    expect(sessions.resume(token)).toEqual({ changed: false, action: null });
    expect(sessions.isLive(token)).toBe(true);
  });

  it('destroys and invalidates idempotently, including a reused document id', () => {
    const sessions = createRendererSessions();
    const old = sessions.register({ documentId: 'one', renderer: {} }).token;

    expect(sessions.invalidate('one')).toEqual({
      changed: true,
      action: expect.objectContaining({ generation: old.generation, state: 'destroyed', reason: 'invalidated' }),
    });
    expect(sessions.invalidate('one')).toEqual({ changed: false, action: null });
    expect(sessions.destroy(old)).toEqual({ changed: false, action: null });

    const reopened = sessions.register({ documentId: 'one', renderer: {} }).token;
    expect(reopened.generation).toBeGreaterThan(old.generation);
    expect(sessions.isCurrent(old)).toBe(false);
    expect(sessions.isLive(reopened)).toBe(true);
  });

  it('refuses incomplete bindings before they can create ambiguous ownership', () => {
    const sessions = createRendererSessions();

    expect(() => sessions.register({ renderer: {} })).toThrow(TypeError);
    expect(() => sessions.register({ documentId: 'one' })).toThrow(TypeError);
  });
});
