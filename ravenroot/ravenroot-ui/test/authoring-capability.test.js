import { describe, expect, it, vi } from 'vitest';
import {
  AUTHORING_CAPABILITY_STATE,
  authoringCapabilityFromConfiguration,
  loadAuthoringCapability,
  pendingAuthoringCapability,
  primaryPersistenceTarget,
  runPrimaryPersistence,
} from '../src/authoring-capability.js';

describe('authoring capability state', () => {
  it('blocks primary persistence while capability proof is pending', () => {
    expect(primaryPersistenceTarget(pendingAuthoringCapability())).toBe('blocked');
  });

  it('preserves explicit legacy local mode only after a successful configuration response', () => {
    expect(authoringCapabilityFromConfiguration({ schemaVersion: 1 }).state)
      .toBe(AUTHORING_CAPABILITY_STATE.LOCAL);
    expect(authoringCapabilityFromConfiguration({ graphAuthoring: { mode: 'local', provider: 'none' } }).state)
      .toBe(AUTHORING_CAPABILITY_STATE.LOCAL);
  });

  it.each([
    ['401', Object.assign(new Error('Authentication required'), { status: 401 })],
    ['403', Object.assign(new Error('Forbidden'), { status: 403 })],
    ['network', new TypeError('Failed to fetch')],
    ['malformed', null],
  ])('keeps primary persistence blocked after %s configuration failure', async (_case, failure) => {
    const client = failure
      ? { configuration: vi.fn().mockRejectedValue(failure) }
      : { configuration: vi.fn().mockResolvedValue({ graphAuthoring: { mode: 'git', provider: 'caller-url' } }) };
    const result = await loadAuthoringCapability(client);
    expect(result.capability.state).toBe(AUTHORING_CAPABILITY_STATE.UNAVAILABLE);
    expect(primaryPersistenceTarget(result.capability)).toBe('blocked');
    const localDownload = vi.fn();
    const blocked = vi.fn();
    runPrimaryPersistence(result.capability, { local: localDownload, git: vi.fn(), blocked });
    expect(localDownload).not.toHaveBeenCalled();
    expect(blocked).toHaveBeenCalledOnce();
  });

  it('changes from unavailable to Git only after an explicit successful retry', async () => {
    const client = { configuration: vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce({ graphAuthoring: { mode: 'git', provider: 'github' } }) };
    const failed = await loadAuthoringCapability(client);
    const retried = await loadAuthoringCapability(client);
    expect(primaryPersistenceTarget(failed.capability)).toBe('blocked');
    expect(primaryPersistenceTarget(retried.capability)).toBe('git');
  });
});
