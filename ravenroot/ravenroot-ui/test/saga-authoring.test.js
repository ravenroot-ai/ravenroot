import { describe, expect, it } from 'vitest';
import { migrateLegacySagaDefaults, readSagaCatalogProperties, sagaParticipant, sagaScopes } from '../src/saga-authoring.js';
import { createEmptyWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { parseGraphML } from '../src/graph-parsers.js';
import { createNodeForInsertion } from '../src/graph-editing.js';

const legacy = { 'saga.role': 'forward', 'saga.irreversible': 'false', 'saga.businessCompletionRequired': false };
const descriptor = (behavior, capabilities = []) => ({ behavior, displayName: behavior, capabilities,
  properties: [{ name: 'message', type: 'STRING', defaultValue: '{{payload}}' },
    ...Object.entries(legacy).map(([name, defaultValue]) => ({ name, type: 'STRING', defaultValue }))] });

describe('explicit optional saga authoring', () => {
  it('rejects generic effectful contracts and trusts exact behavior plus adapter capability', () => {
    for (const behavior of ['log', 'slack.send', 'kafka.produce', 'jdbc.insert']) {
      expect(sagaParticipant(descriptor(behavior, ['side-effect']))).toBeNull();
    }
    expect(sagaParticipant(descriptor('trusted', ['saga-pure']))).toBe('pure');
    expect(sagaParticipant(descriptor('log', ['saga-pure', 'side-effect']))).toBeNull();
    expect(sagaParticipant(descriptor('http-request', ['saga-adapter:ravenroot.http-idempotency.v1']))).toBe('http-idempotency-v1');
    expect(sagaParticipant(descriptor('fake', ['saga-adapter:ravenroot.http-idempotency.v1']))).toBeNull();
  });

  it('catalog/palette insertion ignores stale saga defaults through export and reopen', () => {
    const graph = createEmptyWorkflowDocument();
    for (const behavior of ['log', 'http-request', 'kafka.produce', 'trusted']) {
      const node = createNodeForInsertion(graph, { x: 100, y: 100 }, { descriptor: descriptor(behavior) });
      expect(node.properties).toEqual({ message: '{{payload}}' });
      graph.nodes.push(node); graph.nodeMap[node.id] = node;
    }
    const reopened = parseGraphML(serializeGraphML(graph));
    expect(reopened.nodes.every(node => !Object.keys(node.properties).some(name => name.startsWith('saga.')))).toBe(true);
  });

  it('repairs exactly the complete historical Log signature and persists its absence', () => {
    const graph = createEmptyWorkflowDocument();
    const log = createNodeForInsertion(graph, { x: 100, y: 100 }, { descriptor: descriptor('log') });
    Object.assign(log.properties, legacy);
    graph.nodes.push(log); graph.nodeMap[log.id] = log;
    const reopened = parseGraphML(serializeGraphML(graph));
    expect(reopened.nodeMap[log.id].properties.message).toBe('{{payload}}');
    expect(Object.keys(reopened.nodeMap[log.id].properties).filter(key => key.startsWith('saga.'))).toEqual([]);
    for (const properties of [ { 'saga.role': 'forward' }, { ...legacy, 'saga.scope': 'order' },
      { ...legacy, 'saga.irreversible': true }, { ...legacy, 'saga.future': 'authored' }]) {
      const original = { behavior: 'log', properties };
      expect(migrateLegacySagaDefaults(original)).toBe(original);
    }
    const custom = { behavior: 'custom', properties: legacy };
    expect(migrateLegacySagaDefaults(custom)).toBe(custom);
  });

  it('off writes no catalog saga properties; existing declared participants remain explicit', () => {
    const form = document.createElement('form');
    form.innerHTML = '<input type="checkbox" data-saga-enabled>';
    const read = () => ({ properties: { message: '{{payload}}', ...legacy }, propertyTypes: {} });
    expect(readSagaCatalogProperties(form, read).properties).toEqual({ message: '{{payload}}' });
    form.querySelector('input').checked = true;
    expect(readSagaCatalogProperties(form, read).properties).toEqual(read().properties);
  });

  it('scope listing never enrolls ordinary nodes or creates an empty persisted scope', () => {
    const graph = { nodes: [{ id: 'log', behavior: 'log', properties: {} },
      { id: 'transform', properties: {} }, { id: 'participant', properties: { 'saga.scope': 'order' } }] };
    expect([...sagaScopes(graph).keys()]).toEqual(['order']);
    expect(sagaScopes(graph).get('order').map(node => node.id)).toEqual(['participant']);
    expect([...sagaScopes({ nodes: [] })]).toEqual([]);
  });
});
