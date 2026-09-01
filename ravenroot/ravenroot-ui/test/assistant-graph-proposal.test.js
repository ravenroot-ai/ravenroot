import { describe, expect, it } from 'vitest';

import {
  applyAssistantGraphProposal,
  catalogProposalDigest,
  planAssistantGraphProposal,
  rejectAssistantGraphProposal,
} from '../src/assistant-graph-proposal.js';
import { createCommandHistory, updateNodeCommand } from '../src/graph-commands.js';
import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';

const CATALOG = Object.freeze([{
  behavior: 'template', displayName: 'Template', visualType: 'flow',
  properties: [
    { name: 'template', type: 'STRING', required: true },
    { name: 'retries', type: 'INTEGER', defaultValue: '1' },
    { name: 'credentialRef', type: 'SECRET_REFERENCE' },
  ],
  outcomes: [{ name: 'continue' }],
}]);

function context(graph = createWorkflowDocument(), history = createCommandHistory(), overrides = {}) {
  return {
    graph, history, catalog: CATALOG, documentIncarnation: 'document-a',
    revision: history.revision(), catalogDigest: catalogProposalDigest(CATALOG), ...overrides,
  };
}

function proposal(operations, overrides = {}) {
  return {
    version: 1,
    id: 'assistant-proposal-1',
    document: {
      incarnation: 'document-a', revision: 0, catalogDigest: catalogProposalDigest(CATALOG),
    },
    summary: 'Build the requested branch',
    operations,
    ...overrides,
  };
}

describe('Assistant graph proposal planning', () => {
  it('binds catalogs with a real SHA-256 digest', () => {
    expect(catalogProposalDigest([])).toBe(
      'proposal-catalog-v1-sha256-4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945');
  });

  it('adds two nodes and their edge as one edit and undoes and redoes that one edit', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const value = proposal([
      { op: 'create-node', ref: 'first', id: 'assistant-first', behavior: 'template',
        properties: [{ name: 'template', value: 'first' }] },
      { op: 'create-node', ref: 'second', id: 'assistant-second', behavior: 'template',
        properties: [{ name: 'template', value: 'second' }] },
      { op: 'create-edge', ref: 'link', id: 'assistant-link',
        source: { created: 'first' }, destination: { created: 'second' }, outcome: 'continue' },
    ]);

    const result = applyAssistantGraphProposal(value, context(graph, history));

    expect(result.ok).toBe(true);
    expect(result.preview.changes).toHaveLength(3);
    expect(graph.nodeMap['assistant-first']).toMatchObject({ behavior: 'template' });
    expect(graph.nodeMap['assistant-second']).toMatchObject({ behavior: 'template' });
    expect(graph.edges.find(edge => edge.id === 'assistant-link'))
      .toMatchObject({ source: 'assistant-first', target: 'assistant-second' });
    expect(history.depth()).toBe(1);
    expect(history.state().lastMetadata).toEqual({
      origin: 'assistant', proposalId: 'assistant-proposal-1', schemaVersion: 1,
      opCount: 3, userConfirmed: true,
    });

    history.undo(graph);
    expect(graph.nodeMap['assistant-first']).toBeUndefined();
    expect(graph.nodeMap['assistant-second']).toBeUndefined();
    expect(graph.edges.some(edge => edge.id === 'assistant-link')).toBe(false);

    history.redo(graph);
    expect(graph.nodeMap['assistant-first']).toMatchObject({ behavior: 'template' });
    expect(graph.nodeMap['assistant-second']).toMatchObject({ behavior: 'template' });
    expect(graph.edges.find(edge => edge.id === 'assistant-link'))
      .toMatchObject({ source: 'assistant-first', target: 'assistant-second' });
    expect(history.depth()).toBe(1);
  });

  it('preserves an exact whitespace-significant edge id through create and existing selection', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const created = proposal([
      { op: 'create-node', ref: 'first', id: 'assistant-first', behavior: 'template',
        properties: [{ name: 'template', value: 'first' }] },
      { op: 'create-node', ref: 'second', id: 'assistant-second', behavior: 'template',
        properties: [{ name: 'template', value: 'second' }] },
      { op: 'create-edge', ref: 'link', id: ' assistant-link ',
        source: { created: 'first' }, destination: { created: 'second' }, outcome: 'continue' },
    ]);
    expect(applyAssistantGraphProposal(created, context(graph, history)).ok).toBe(true);
    expect(graph.edges.find(edge => edge.id === ' assistant-link ')?.id).toBe(' assistant-link ');
    expect(graph.edges.some(edge => edge.id === 'assistant-link')).toBe(false);

    const selected = proposal([
      { op: 'update-edge', target: { existing: ' assistant-link ' }, outcome: 'continue' },
    ], { document: {
      incarnation: 'document-a', revision: history.revision(), catalogDigest: catalogProposalDigest(CATALOG),
    } });
    expect(planAssistantGraphProposal(selected, context(graph, history)).ok).toBe(true);
  });

  it('previews catalog-validated update and delete operations including incident edges', () => {
    const graph = createWorkflowDocument();
    graph.nodeMap.dosomething.behavior = 'template';
    graph.nodeMap.dosomething.properties = { template: 'before', retries: '1' };
    graph.nodeMap.dosomething.propertyTypes = { template: 'string', retries: 'long' };
    const digest = catalogProposalDigest(CATALOG);
    const update = proposal([{ op: 'update-node', target: { existing: 'dosomething' },
      name: 'After', properties: [{ name: 'retries', value: '2' }] }]);
    const deletion = proposal([{ op: 'delete-node', target: { existing: 'dosomething' } }]);

    expect(planAssistantGraphProposal(update, context(graph)).ok).toBe(true);
    const deleted = planAssistantGraphProposal(deletion, context(graph, createCommandHistory(), {
      catalogDigest: digest,
    }));
    expect(deleted.ok).toBe(true);
    expect(deleted.preview.changes[0]).toMatch(/3 incident edge/);
  });

  it('applies node position and property updates plus edge update/delete through one history entry', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    graph.nodeMap.dosomething.behavior = 'template';
    graph.nodeMap.dosomething.properties = { template: 'before', retries: '1' };
    graph.nodeMap.dosomething.propertyTypes = { template: 'string', retries: 'long' };
    const before = serializeGraphML(graph);
    const value = proposal([
      { op: 'update-node', target: { existing: 'dosomething' }, name: 'After',
        position: { x: 640, y: 480 }, properties: [{ name: 'retries', value: '2' }] },
      { op: 'update-edge', target: { existing: 'edge-dosomething-error' }, outcome: 'continue' },
      { op: 'delete-edge', target: { existing: 'edge-dosomething-end' } },
    ]);

    const result = applyAssistantGraphProposal(value, context(graph, history));

    expect(result.ok).toBe(true);
    expect(graph.nodeMap.dosomething).toMatchObject({
      name: 'After', ox: 640, oy: 480, _positionIsCenter: true,
      properties: { template: 'before', retries: '2' },
    });
    expect(graph.edges.find(edge => edge.id === 'edge-dosomething-error').outcome).toBe('continue');
    expect(graph.edges.some(edge => edge.id === 'edge-dosomething-end')).toBe(false);
    expect(history.depth()).toBe(1);

    history.undo(graph);
    expect(serializeGraphML(graph)).toBe(before);
    history.redo(graph);
    expect(graph.nodeMap.dosomething).toMatchObject({ name: 'After', ox: 640, oy: 480 });
    expect(graph.edges.some(edge => edge.id === 'edge-dosomething-end')).toBe(false);
  });

  it('applies an existing node deletion and visibly includes its untouched incident edges', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = serializeGraphML(graph);

    const result = applyAssistantGraphProposal(proposal([
      { op: 'delete-node', target: { existing: 'dosomething' } },
    ]), context(graph, history));

    expect(result.ok).toBe(true);
    expect(result.preview.changes).toEqual([
      'Delete node dosomething and 3 incident edge(s).',
    ]);
    expect(graph.nodeMap.dosomething).toBeUndefined();
    expect(graph.edges).toHaveLength(0);
    expect(history.depth()).toBe(1);
    history.undo(graph);
    expect(serializeGraphML(graph)).toBe(before);
  });

  it('rejects without changing even one serialized byte', () => {
    const graph = createWorkflowDocument();
    const before = serializeGraphML(graph);
    expect(rejectAssistantGraphProposal(proposal([
      { op: 'delete-node', target: { existing: 'dosomething' } },
    ]))).toEqual({ id: 'assistant-proposal-1', rejected: true });
    expect(serializeGraphML(graph)).toBe(before);
  });

  it.each([
    ['revision', { revision: 1 }, /stale because the document changed/],
    ['document switch', { documentIncarnation: 'document-b' }, /different open document/],
    ['document replacement', { documentIncarnation: 'replacement-c' }, /different open document/],
    ['catalog', { catalogDigest: 'different' }, /node catalog changed/],
  ])('refuses stale %s bindings', (_label, overrides, expected) => {
    const result = planAssistantGraphProposal(proposal([
      { op: 'delete-edge', target: { existing: 'edge-start-dosomething' } },
    ]), context(createWorkflowDocument(), createCommandHistory(), overrides));
    expect(result.ok).toBe(false);
    expect(result.errors[0]).toMatch(expected);
  });

  it('keeps a proposal stale after a real edit, undo, and redo revision sequence', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const pending = proposal([
      { op: 'delete-edge', target: { existing: 'edge-start-dosomething' } },
    ]);
    const stale = () => planAssistantGraphProposal(pending, context(graph, history));

    history.execute(graph, updateNodeCommand('dosomething', { name: 'Manual edit' }));
    expect(stale().errors[0]).toMatch(/stale because the document changed/);
    history.undo(graph);
    expect(stale().errors[0]).toMatch(/stale because the document changed/);
    history.redo(graph);
    expect(stale().errors[0]).toMatch(/stale because the document changed/);
  });

  it.each([
    ['unknown position field', { x: 1, y: 2, z: 3 }],
    ['missing position coordinate', { x: 1 }],
    ['non-finite position coordinate', { x: Number.POSITIVE_INFINITY, y: 2 }],
  ])('refuses %s while a closed finite position remains accepted', (_label, position) => {
    const graph = createWorkflowDocument();
    graph.nodeMap.dosomething.behavior = 'template';
    graph.nodeMap.dosomething.properties = { template: 'before' };
    const before = serializeGraphML(graph);
    const invalid = planAssistantGraphProposal(proposal([
      { op: 'update-node', target: { existing: 'dosomething' }, position },
    ]), context(graph));
    const valid = planAssistantGraphProposal(proposal([
      { op: 'update-node', target: { existing: 'dosomething' }, position: { x: 1, y: 2 } },
    ]), context(graph));

    expect(invalid.ok).toBe(false);
    expect(valid.ok).toBe(true);
    expect(serializeGraphML(graph)).toBe(before);
  });

  it('rejects direct edge work hidden by a later node cascade but permits a reroute away', () => {
    const graph = createWorkflowDocument();
    const before = serializeGraphML(graph);
    const cases = [
      [
        { op: 'create-edge', ref: 'new-edge', id: 'new-edge',
          source: { existing: 'start' }, destination: { existing: 'end' } },
        { op: 'delete-node', target: { existing: 'start' } },
      ],
      [
        { op: 'update-edge', target: { existing: 'edge-start-dosomething' }, outcome: 'continue' },
        { op: 'delete-node', target: { existing: 'start' } },
      ],
      [
        { op: 'delete-node', target: { existing: 'start' } },
        { op: 'update-edge', target: { existing: 'edge-start-dosomething' }, outcome: 'continue' },
      ],
    ];
    for (const operations of cases) {
      const result = planAssistantGraphProposal(proposal(operations), context(graph));
      expect(result.ok).toBe(false);
      expect(serializeGraphML(graph)).toBe(before);
    }

    const rerouted = planAssistantGraphProposal(proposal([
      { op: 'update-edge', target: { existing: 'edge-start-dosomething' },
        destination: { existing: 'end' } },
      { op: 'delete-node', target: { existing: 'dosomething' } },
    ]), context(graph));
    expect(rerouted.ok).toBe(true);
    expect(rerouted.preview.changes.at(-1)).toMatch(/2 incident edge/);
  });

  it.each([
    ['unknown catalog behavior', [{ op: 'create-node', ref: 'n', id: 'n', behavior: 'missing' }]],
    ['unknown property', [{ op: 'create-node', ref: 'n', id: 'n', behavior: 'template',
      properties: [{ name: 'unknown', value: 'x' }] }]],
    ['invalid property value', [{ op: 'create-node', ref: 'n', id: 'n', behavior: 'template',
      properties: [{ name: 'template', value: 'x' }, { name: 'retries', value: 'not-an-integer' }] }]],
    ['invalid edge endpoint', [{ op: 'create-edge', ref: 'e', id: 'e',
      source: { existing: 'ghost' }, destination: { existing: 'end' } }]],
    ['duplicate property', [{ op: 'create-node', ref: 'n', id: 'n', behavior: 'template', properties: [
      { name: 'template', value: 'x' }, { name: 'template', value: 'y' },
    ] }]],
  ])('refuses %s', (_label, operations) => {
    const graph = createWorkflowDocument();
    const before = serializeGraphML(graph);
    const result = planAssistantGraphProposal(proposal(operations), context(graph));
    expect(result.ok).toBe(false);
    expect(serializeGraphML(graph)).toBe(before);
  });

  it('fails a partially invalid proposal atomically before live history', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = serializeGraphML(graph);
    const result = applyAssistantGraphProposal(proposal([
      { op: 'create-node', ref: 'valid', id: 'valid', behavior: 'template',
        properties: [{ name: 'template', value: 'ok' }] },
      { op: 'delete-node', target: { existing: 'ghost' } },
    ]), context(graph, history));
    expect(result.ok).toBe(false);
    expect(history.depth()).toBe(0);
    expect(serializeGraphML(graph)).toBe(before);
  });

  it('structurally refuses secret properties and unknown JSON fields', () => {
    const graph = createWorkflowDocument();
    const secret = planAssistantGraphProposal(proposal([{ op: 'create-node', ref: 'n', id: 'n',
      behavior: 'template', properties: [
        { name: 'template', value: 'x' }, { name: 'credentialRef', value: 'vault://secret' },
      ] }]), context(graph));
    const unknown = planAssistantGraphProposal({ ...proposal([
      { op: 'delete-edge', target: { existing: 'edge-start-dosomething' } },
    ]), confirmed: true }, context(graph));
    expect(secret.ok).toBe(false);
    expect(secret.errors[0]).toMatch(/secret-class/);
    expect(unknown.ok).toBe(false);
    expect(unknown.errors[0]).toMatch(/unknown or missing/);
  });
});
