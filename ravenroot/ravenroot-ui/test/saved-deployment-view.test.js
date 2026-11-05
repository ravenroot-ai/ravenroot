import { describe, it, expect, vi } from 'vitest';
import { readSavedDeploymentView } from '../src/saved-deployment-view.js';
const binding = { deploymentId: 'orders', graphVersion: 'v1', registrationIdentity: 'registration', incarnationId: 'old-runtime' };
function client(overrides = {}, source = {}) {
  return { deployment: vi.fn().mockResolvedValue({ ...binding, runtimeIncarnationId: 'new-runtime', ...overrides }),
    deploymentView: vi.fn().mockResolvedValue({ source: { ...binding, incarnationId: 'new-runtime', ...source } }),
    registerDeployment: vi.fn(), restoreDeployment: vi.fn(), startDeployment: vi.fn() };
}
describe('saved read-only deployment observation', () => {
  it('reconnects the same registration across runtime restart using reads only', async () => {
    const api = client();
    expect((await readSavedDeploymentView(api, binding)).source.incarnationId).toBe('new-runtime');
    expect(api.registerDeployment).not.toHaveBeenCalled();
    expect(api.restoreDeployment).not.toHaveBeenCalled();
    expect(api.startDeployment).not.toHaveBeenCalled();
  });
  it.each([{ registrationIdentity: 'replacement' }, { graphVersion: 'v2' }])('refuses replacement identity or version %j', async override => {
    const api = client(override);
    await expect(readSavedDeploymentView(api, binding)).rejects.toThrow();
    expect(api.deploymentView).not.toHaveBeenCalled();
  });
  it('refuses an incarnation race between the status and view reads', async () => {
    await expect(readSavedDeploymentView(client({}, { incarnationId: 'replaced-again' }), binding)).rejects.toThrow();
  });
  it('does not recreate an unavailable deployment', async () => {
    const api = client(); api.deployment.mockRejectedValue(new Error('404'));
    await expect(readSavedDeploymentView(api, binding)).rejects.toThrow('404');
    expect(api.restoreDeployment).not.toHaveBeenCalled();
  });
});
