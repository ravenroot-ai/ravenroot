import { describe, expect, it, vi } from 'vitest';
import {
  applyAcceptedGraphMl,
  GraphAuthoringClient,
  MAX_AUTHORED_RELEASE_VERSION,
  decodeGraphMl,
  parseAuthoredReleaseVersion,
  repositorySourceStatus,
  validateGraphAuthoringCapability,
} from '../src/graph-authoring-client.js';
import { parseGraphML } from '../src/graph-parsers.js';
import { createNode, createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';

describe('graph authoring client', () => {
  it('sends all three stale-state tokens and a bounded request identity on save', async () => {
    const fetchImpl = vi.fn(async (_url, options) => new Response(JSON.stringify({ documentId: 'a.graphml' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const client = new GraphAuthoringClient('https://ravenroot.example', {
      tokenProvider: () => 'session', fetchImpl,
    });
    await client.save('a.graphml', '<graphml/>', { draft: 'd', release: 'r', publication: 'p' },
      '12345678-1234-1234-1234-123456789012');
    const [url, options] = fetchImpl.mock.calls[0];
    expect(url).toBe('https://ravenroot.example/v1/graph-authoring/a.graphml');
    expect(options.headers.get('X-Ravenroot-Expected-Draft')).toBe('d');
    expect(options.headers.get('X-Ravenroot-Expected-Release')).toBe('r');
    expect(options.headers.get('X-Ravenroot-Expected-Publication')).toBe('p');
    expect(options.headers.get('Authorization')).toBe('Bearer session');
  });

  it('uses the shared mutable runtime token provider contract', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ items: [] }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const tokenProvider = { getAccessToken: vi.fn(() => 'shared-session') };
    await new GraphAuthoringClient('', { tokenProvider, fetchImpl }).list();
    expect(fetchImpl.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer shared-session');
  });

  it('validates capability posture and decodes exact returned GraphML bytes', () => {
    expect(validateGraphAuthoringCapability({ mode: 'git', provider: 'github', operations: ['SAVE'] }).mode)
      .toBe('git');
    expect(() => validateGraphAuthoringCapability({ mode: 'git', provider: 'caller-url', operations: [] }))
      .toThrow(/malformed/);
    const xml = '<graphml>é</graphml>';
    expect(decodeGraphMl({ graphMl: btoa(unescape(encodeURIComponent(xml))) })).toBe(xml);
  });

  it('accepts only canonical exact authored versions shared with JSON and GraphML', () => {
    expect(parseAuthoredReleaseVersion(1)).toBe(1);
    expect(parseAuthoredReleaseVersion(String(MAX_AUTHORED_RELEASE_VERSION)))
      .toBe(MAX_AUTHORED_RELEASE_VERSION);
    for (const value of [0, -1, MAX_AUTHORED_RELEASE_VERSION + 1, 1.5, 1e20,
      '0', '01', '+1', '1.0', '1e0', '9007199254740992']) {
      expect(() => parseAuthoredReleaseVersion(value)).toThrow(/canonical safe positive integer/);
    }
  });

  it('keeps adjacent high catalog versions distinct and rejects rounded response identities', async () => {
    const digest = 'a'.repeat(64);
    const artifactRef = `${'b'.repeat(64)}:${digest}`;
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [
        { graphId: 'orders', releaseVersion: 9007199254740990, sha256: digest, artifactRef },
        { graphId: 'orders', releaseVersion: 9007199254740991, sha256: digest, artifactRef },
      ] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('{"items":[{"graphId":"orders",'
        + '"releaseVersion":9007199254740993,"sha256":"' + digest
        + '","artifactRef":"' + artifactRef + '"}]}',
      { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const client = new GraphAuthoringClient('', { fetchImpl });
    const catalog = await client.artifacts();
    expect(catalog.items.map(item => item.releaseVersion))
      .toEqual([9007199254740990, 9007199254740991]);
    await expect(client.artifacts()).rejects.toThrow(/safe positive integer/);
  });

  it('uses accepted identity XML as the next save base without erasing in-flight edits', () => {
    const current = createWorkflowDocument();
    current.nodes.push(createNode('during-save', 'During save', 'PASSTHROUGH'));
    current.nodeMap['during-save'] = current.nodes.at(-1);
    const accepted = parseGraphML(serializeGraphML(createWorkflowDocument()));
    accepted.graphProperties['ravenroot.authoring.graphId'] = 'orders';
    accepted.graphProperties['ravenroot.authoring.releaseVersion'] = '3';
    const acceptedXml = serializeGraphML(accepted);
    const envelope = { graphId: 'orders', releaseVersion: 3,
      graphMl: btoa(unescape(encodeURIComponent(acceptedXml))) };

    applyAcceptedGraphMl(current, envelope, parseGraphML);

    expect(current.nodes.some(node => node.id === 'during-save')).toBe(true);
    expect(current.sourceXml).toBe(acceptedXml);
    const roundTrip = parseGraphML(serializeGraphML(current));
    expect(roundTrip.graphProperties['ravenroot.authoring.graphId']).toBe('orders');
    expect(roundTrip.graphProperties['ravenroot.authoring.releaseVersion']).toBe('3');
    expect(roundTrip.nodes.some(node => node.id === 'during-save')).toBe(true);
    const firstNoOpSerialization = serializeGraphML(roundTrip);
    expect(serializeGraphML(parseGraphML(firstNoOpSerialization))).toBe(firstNoOpSerialization);
    expect(() => applyAcceptedGraphMl(current, { ...envelope, releaseVersion: 4 }, parseGraphML))
      .toThrow(/inconsistent/);
  });

  it('keeps current draft and exact reviewed publication status visible separately', () => {
    expect(repositorySourceStatus({ releaseVersion: 2, releasedVersion: 1,
      released: true, published: true, draftDeleted: false })).toEqual({
      sourceState: 'Repository draft', version: 'v2', releaseState: 'Released v1',
      publicationState: 'Published',
    });
    expect(repositorySourceStatus({ releaseVersion: 1, releasedVersion: 1,
      released: true, published: false, draftDeleted: true })).toEqual({
      sourceState: 'Released source', version: 'v1', releaseState: 'Released v1',
      publicationState: 'Publication pending or failed',
    });
  });

  it('maps repository history, mutations, release and rollback to server routes', async () => {
    const fetchImpl = vi.fn(async (_url, options) => new Response(
      options?.method === 'DELETE' ? null : JSON.stringify({ items: [] }),
      { status: options?.method === 'DELETE' ? 204 : 200,
        headers: { 'Content-Type': 'application/json' } }));
    const client = new GraphAuthoringClient('https://ravenroot.example', { fetchImpl });
    const revision = { draft: 'draft', release: 'release', publication: 'publication' };
    await client.history('orders.graphml');
    await client.diff('orders.graphml', 'a'.repeat(40), 'b'.repeat(40));
    await client.restore('orders.graphml', 'c'.repeat(40), revision, 'restore-key-00000001');
    await client.discard('orders.graphml', revision, 'discard-key-00000001');
    await client.delete('orders.graphml', revision, 'delete-key-000000001');
    await client.release('orders.graphml', revision, 'release-key-00000001');
    await client.artifacts();
    const selectedDigest = 'd'.repeat(64);
    const selectedRef = `${'e'.repeat(64)}:${selectedDigest}`;
    await client.importArtifact('orders', 1, selectedDigest, selectedRef);
    await client.deploy('orders', 1, 'orders-rollback', selectedDigest, selectedRef);
    const urls = fetchImpl.mock.calls.map(([url]) => url);
    expect(urls[0]).toContain('/orders.graphml/history');
    expect(urls[1]).toContain('/orders.graphml/diff?from=');
    expect(urls.slice(2)).toEqual([
      'https://ravenroot.example/v1/graph-authoring/orders.graphml/restore?revision=' + 'c'.repeat(40),
      'https://ravenroot.example/v1/graph-authoring/orders.graphml/discard',
      'https://ravenroot.example/v1/graph-authoring/orders.graphml',
      'https://ravenroot.example/v1/graph-authoring/orders.graphml/release',
      'https://ravenroot.example/v1/graph-artifacts',
      `https://ravenroot.example/v1/graph-artifacts/orders/1/import?expectedSha256=${selectedDigest}&expectedArtifactRef=${encodeURIComponent(selectedRef)}`,
      `https://ravenroot.example/v1/graph-artifacts/orders/1/deploy?id=orders-rollback&expectedSha256=${selectedDigest}&expectedArtifactRef=${encodeURIComponent(selectedRef)}`,
    ]);
    expect(fetchImpl.mock.calls.slice(2, 6).every(([, options]) =>
      options.headers.get('X-Ravenroot-Expected-Publication') === 'publication')).toBe(true);
  });

  it('surfaces stale conflicts without sending caller-selected provider coordinates', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ error: 'CONFLICT' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } }));
    const client = new GraphAuthoringClient('', { fetchImpl });
    await expect(client.open('safe.graphml')).rejects.toThrow('CONFLICT');
    expect(fetchImpl.mock.calls[0][0]).toBe('/v1/graph-authoring/safe.graphml?source=draft');
  });
});
