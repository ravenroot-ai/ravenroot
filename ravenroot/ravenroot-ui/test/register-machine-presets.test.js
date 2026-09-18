import { describe, expect, it } from 'vitest';
import { JSDOM } from 'jsdom';
import { parseGraphML } from '../src/graph-parsers.js';
import { createEdge, createNode, serializeGraphML } from '../src/graph-document.js';
import { applyCommand } from '../src/graph-commands.js';
import {
  availableRegisterMachinePresets,
  insertRegisterMachinePreset,
  REGISTER_MACHINE_PRESETS,
} from '../src/register-machine-presets.js';

function graph() {
  const start = createNode('start', 'Start', 'START');
  const end = createNode('end', 'End', 'END');
  return { format: 'graphml', nodes: [start, end], nodeMap: { start, end },
    edges: [createEdge('start-end', 'start', 'end')], graphProperties: {} };
}

describe('register machine authoring presets', () => {
  it('keeps a 4096-digit decimal exact through ordinary GraphML serialization and parsing', () => {
    const value = `9${'0'.repeat(4095)}`;
    const authored = graph();
    const inserted = insertRegisterMachinePreset(authored, 'register-set', { register: 'counter', decimal: value });
    const xml = serializeGraphML(authored);
    installDom();
    const reloaded = parseGraphML(xml);
    expect(reloaded.nodeMap[inserted.primary.id].properties.left).toBe(`literal:${value}`);
    expect(reloaded.nodeMap[inserted.primary.id].propertyTypes.left).toBe('string');
    expect(xml).not.toContain('9e+');
  });

  it('inserts DECJZ as one atomic command containing three editable nodes and four edges', () => {
    const authored = graph();
    const history = { calls: [], execute(target, command) {
      this.calls.push(command);
      applyCommand(target, command);
    } };
    const result = insertRegisterMachinePreset(authored, 'register-decjz', {
      register: 'counter', boolean: 'isZero', zeroTarget: 'end', nonzeroTarget: 'start',
    }, {}, history);
    expect(history.calls).toHaveLength(1);
    expect(result.nodes.map(node => node.behavior)).toEqual(['bigint-op', 'cel-decision', 'bigint-op']);
    expect(result.edges.map(edge => edge.outcome)).toEqual(['continue', 'zero', 'nonzero', 'continue']);
    expect(authored.graphProperties).not.toHaveProperty('registerMachineMacro');
  });

  it('publishes four transparent palette entries', () => {
    expect(REGISTER_MACHINE_PRESETS.map(item => item.presetId)).toEqual([
      'register-set', 'register-increment', 'register-zero-test', 'register-decjz',
    ]);
  });

  it('offers presets only when every elementary runtime behavior they expand to is advertised', () => {
    expect(availableRegisterMachinePresets([])).toEqual([]);
    expect(availableRegisterMachinePresets([{ behavior: 'bigint-op' }]).map(preset => preset.presetId))
      .toEqual(['register-set', 'register-increment', 'register-zero-test']);
    expect(availableRegisterMachinePresets([{ behavior: 'cel-decision' }, { behavior: 'bigint-op' }])
      .map(preset => preset.presetId)).toEqual([
      'register-set', 'register-increment', 'register-zero-test', 'register-decjz',
    ]);
  });
});

function installDom() {
  const dom = new JSDOM('<!doctype html>');
  globalThis.DOMParser = dom.window.DOMParser;
  globalThis.XMLSerializer = dom.window.XMLSerializer;
}
