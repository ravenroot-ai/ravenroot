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
