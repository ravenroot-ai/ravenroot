import { describe, expect, it, vi } from 'vitest';
import { uiMessage, uiComputed, uiErrorMessage, uiMessageOf, renderUiMessage } from '../src/ui-message.js';
import { createUiLocalization, useUiLocalization } from '../src/ui-text.js';
import { readVisualGroupMetadataElement, readVisualGroups, VISUAL_GROUPS_PROPERTY } from '../src/visual-groups.js';
import { insertNodesCommand, applyCommand } from '../src/graph-commands.js';
import { productError } from '../src/product-error.js';
import { RuntimeRequestError, ProgramSourceRejectedError, RavenrootRuntimeClient } from '../src/runtime-client.js';
import { validateEdgeConnection, edgeGestureCandidate } from '../src/edge-gestures.js';
import { repositoryReviewMessageKey } from '../src/graph-authoring-client.js';

function localization() {
  return createUiLocalization({ locale: 'fr', catalogs: Object.fromEntries(['fr', 'de'].map(locale => [locale, {
    'workspace.notice.graph.review': `${locale}[{detail1}|{reviewType}|{url}]`,
    'workspace.app.existing': `${locale}_EXISTING`, 'workspace.app.new': `${locale}_NEW`,
    'workspace.app.pull.request': `${locale}_PULL`, 'workspace.app.merge.request': `${locale}_MERGE`,
    'workspace.runtime.request.failure': `${locale}[{reason}|{where}|{hint}]`,
    'workspace.runtime.request.networkHint': `${locale}_NETWORK`,
    'workspace.runtime.program.rejected': `${locale}[{diagnostic}]`,
    'workspace.runtime.program.noReason': `${locale}_NO_REASON`,
    'workspace.edge.validation.startIncoming': `${locale}[{node}]`,
    'workspace.app.incident': `${locale}[{incidentId}]`,
    'workspace.app.nodes.selected': { one: `${locale}_ONE`, other: `${locale}[{count}]` },
  }])) });
}

describe('retained UI messages', () => {
  it.each(['github', 'gitlab'].flatMap(provider => [true, false].map(reused => ({ provider, reused }))))(
    'repaints the same $provider/$reused message A to B to A without changing event data', ({ provider, reused }) => {
      const context = localization();
      const input = { detail1: uiMessage(reused ? 'workspace.app.existing' : 'workspace.app.new'),
        reviewType: uiMessage(repositoryReviewMessageKey(provider)),
        url: 'https://example.test/?<img onerror="bad()">&opaque="é"' };
      const retained = uiMessage('workspace.notice.graph.review', input);
      input.url = 'later provider';
      for (const locale of ['fr', 'de', 'fr']) {
        context.setLocale(locale);
        expect(renderUiMessage(retained, context.text)).toBe(`${locale}[${locale}_${reused ? 'EXISTING' : 'NEW'}|${locale}_${provider === 'github' ? 'PULL' : 'MERGE'}|https://example.test/?<img onerror="bad()">&opaque="é"]`);
      }
    });

  it('preserves typed numeric parameters through nested plural and number formatting', () => {
    const context = localization();
    const retained = uiMessage('workspace.app.incident', {
      incidentId: uiMessage('workspace.app.nodes.selected', { count: 1234 }),
    });
    expect(renderUiMessage(retained, context.text)).toContain(new Intl.NumberFormat('fr').format(1234));
    context.setLocale('de');
    expect(renderUiMessage(retained, context.text)).toBe(`de[de[${new Intl.NumberFormat('de').format(1234)}]]`);
  });

  it('never treats a forged recipe or authored markup as executable presentation', () => {
    let calls = 0;
    const forged = { key: 'workspace.app.existing', render: () => { calls++; return 'unsafe'; } };
    expect(renderUiMessage(forged, localization().text)).toBe('[object Object]');
    expect(renderUiMessage('<svg onload="bad()">', localization().text)).toBe('<svg onload="bad()">');
    expect(calls).toBe(0);
  });

  it('samples product-error parameter factories once and preserves Error identity and wire fields', () => {
    let id = 'original'; let calls = 0;
    const error = new TypeError('opaque diagnostic');
    error.code = 'MACHINE_CODE';
    expect(productError('workspace.app.incident', error, () => { calls++; return { incidentId: id }; })).toBe(error);
    const serialized = JSON.stringify(error);
    const message = uiErrorMessage(error);
    id = 'later';
    const context = localization();
    for (const locale of ['fr', 'de', 'fr']) {
      context.setLocale(locale);
      expect(renderUiMessage(message, context.text)).toBe(`${locale}[original]`);
    }
    expect(calls).toBe(1);
    expect(error).toBeInstanceOf(TypeError);
    expect(error.message).toBe('opaque diagnostic');
    expect(JSON.stringify(error)).toBe(serialized);
    expect(Object.keys(error)).toEqual(['code']);
    expect(uiErrorMessage(new Error('untranslated <opaque>'))).toBe('untranslated <opaque>');
  });

  it('retains nested runtime error labels and snapshots the original diagnostic', () => {
    const context = localization();
    const error = new RuntimeRequestError('opaque <diagnostic>', { method: 'GET', path: '/v1/nodes' });
    const message = uiErrorMessage(error);
    const program = uiErrorMessage(new ProgramSourceRejectedError());
    error.message = 'later mutation'; error.status = 404;
    for (const locale of ['fr', 'de', 'fr']) {
      context.setLocale(locale);
      expect(renderUiMessage(message, context.text)).toBe(`${locale}[opaque <diagnostic>|GET /v1/nodes|${locale}_NETWORK]`);
      expect(renderUiMessage(program, context.text)).toBe(`${locale}[${locale}_NO_REASON]`);
    }
  });

  it('retains an edge refusal without validating a later graph or changing its string API', () => {
    const context = localization(); useUiLocalization(context);
    const source = { id: 'source', kind: 'BEHAVIOR', name: 'Authored' };
    const target = { id: 'target', kind: 'START', name: '<opaque>' };
    const graph = { nodes: [source, target] };
    const result = edgeGestureCandidate({ mode: 'connect', sourceId: source.id }, graph, target.id);
    expect(result).toEqual({ ok: false, reason: 'fr[<opaque> (target)]', source: 'source', target: 'target' });
    const wire = JSON.stringify(result);
    const message = uiMessageOf(result);
    target.kind = 'END'; target.name = 'Later'; graph.nodes.length = 0;
    context.setLocale('de');
    expect(renderUiMessage(message, context.text)).toBe('de[<opaque> (target)]');
    expect(JSON.stringify(result)).toBe(wire);
    useUiLocalization(createUiLocalization());
  });
  it('preserves semantic labels through the real command inverse and keeps authored labels literal', () => {
    const context = localization(); useUiLocalization(context);
    const graph = { nodes: [], edges: [], nodeMap: {} };
    const message = uiMessage('workspace.app.incident', { incidentId: 'original-id' });
    const command = insertNodesCommand([{ node: { id: 'n' }, index: 0 }], message);
    const inverse = applyCommand(graph, command);
    const redo = applyCommand(graph, inverse);
    context.setLocale('de');
    expect(command.label).toBe('de[original-id]');
    expect(inverse.label).toBe('de[original-id]');
    expect(redo.label).toBe('de[original-id]');
    expect(uiMessageOf(redo)).toBe(message);
    const authored = insertNodesCommand([], '<authored>');
    expect(applyCommand(graph, authored).label).toBe('<authored>');
    useUiLocalization(createUiLocalization());
  });

  it.each([
    ['ambiguous', { a: { id: 'a', name: VISUAL_GROUPS_PROPERTY, scope: 'graph', type: 'string', defaultValue: '{}' }, b: { id: 'b', name: VISUAL_GROUPS_PROPERTY, scope: 'graph', type: 'string', defaultValue: '{}' } }],
    ['graphString', { a: { id: 'a', name: VISUAL_GROUPS_PROPERTY, scope: 'all', type: 'string', defaultValue: '{}' } }],
    ['scalar', { a: { id: 'a', name: VISUAL_GROUPS_PROPERTY, scope: 'graph', type: 'string', defaultValue: '{}', defaultComplex: true } }],
  ])('retains the known %s parser diagnostic without changing its metadata shape', (kind, definitions) => {
    const context = createUiLocalization({ locale: 'fr', catalogs: Object.fromEntries(['fr', 'de'].map(locale => [locale, {
      'workspace.visualGroups.warning': `${locale}[{message}]`,
      [`workspace.visualGroups.${kind}`]: `${locale}_${kind}`,
    }])) });
    const metadata = readVisualGroupMetadataElement({ children: [] }, definitions, 'namespace');
    const wire = JSON.stringify(metadata);
    const graph = { graphProperties: { [VISUAL_GROUPS_PROPERTY]: metadata.raw }, _visualGroupsMetadata: metadata };
    const retained = uiMessageOf(readVisualGroups(graph));
    graph.graphProperties[VISUAL_GROUPS_PROPERTY] = 'later';
    for (const locale of ['fr', 'de', 'fr']) {
      context.setLocale(locale);
      expect(renderUiMessage(retained, context.text)).toBe(`${locale}[${locale}_${kind}]`);
    }
    expect(Object.keys(metadata)).toEqual(['raw', 'invalid']);
    expect(JSON.stringify(metadata)).toBe(wire);
  });

  it('keeps the deployment callback API while snapshotting an error before later repaint', async () => {
    const context = localization(); useUiLocalization(context);
    const error = productError('workspace.app.incident', new Error('original diagnostic'), { incidentId: 'original' });
    const fetchImpl = vi.fn().mockRejectedValue(error);
    const client = new RavenrootRuntimeClient('', { fetchImpl, maxRetries: 0 });
    const view = { viewerSourceVersion: '1', source: { kind: 'deployment', deploymentId: 'd', graphVersion: 'g', incarnationId: 'i' },
      lifecycle: 'READY', canonicalDigest: 'g', projection: { viewerContractVersion: '1.0', graphId: 'd', graphVersionId: 'g', canonicalDigest: 'g', nodes: [], edges: [] } };
    const changes = [];
    const disconnect = client.connectDeploymentView(view, () => {}, (...change) => changes.push(change));
    await vi.waitFor(() => expect(changes).toHaveLength(1));
    const [status, legacy, render] = changes[0];
    expect(status).toBe('error'); expect(legacy).toBe('Deployment observation stopped: original diagnostic');
    expect(typeof render).toBe('function');
    error.message = 'later';
    for (const locale of ['fr', 'de', 'fr']) {
      context.setLocale(locale); expect(render()).toContain(`${locale}[original]`);
    }
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    disconnect(); useUiLocalization(createUiLocalization());
  });

  it('snapshots ordinary stream error and retry count without reconnecting on repaint', async () => {
    const context = localization(); useUiLocalization(context);
    const error = productError('workspace.app.incident', new Error('original'), { incidentId: 'event' });
    const fetchImpl = vi.fn().mockRejectedValue(error);
    const client = new RavenrootRuntimeClient('', { fetchImpl, maxRetries: 0 });
    const changes = [];
    const disconnect = client.connect(() => {}, (...change) => changes.push(change));
    try {
      await vi.waitFor(() => expect(changes).toHaveLength(1));
      const [state, legacy, render] = changes[0];
      expect(state).toBe('error'); expect(legacy).toBe('Live events stopped after 0 retries: original');
      client.maxRetries = 99; error.message = 'later';
      for (const locale of ['fr', 'de', 'fr']) {
        context.setLocale(locale); expect(render()).toContain(`${locale}[event]`);
        expect(render()).toContain('0 retries'); expect(render()).not.toContain('99');
      }
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    } finally { disconnect(); useUiLocalization(createUiLocalization()); }
  });

});
