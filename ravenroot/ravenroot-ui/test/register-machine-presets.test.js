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
  it('round-trips every preset as ordinary nodes and edges with exact properties and no macro metadata', () => {
    const value = `9${'0'.repeat(4095)}`;
    installDom();
    const cases = [
      ['register-set', { register: 'counter', decimal: value }, [
        { operation: 'copy', left: `literal:${value}`, target: 'counter' },
      ]],
      ['register-increment', { register: 'counter' }, [
        { operation: 'add', left: 'field:counter', right: 'literal:1', target: 'counter' },
      ]],
      ['register-zero-test', { register: 'counter', boolean: 'isZero' }, [
        { operation: 'equal', left: 'field:counter', right: 'literal:0', target: 'isZero' },
      ]],
      ['register-decjz', {
        register: 'counter', boolean: 'isZero', zeroTarget: 'end', nonzeroTarget: 'start',
      }, [
        { operation: 'equal', left: 'field:counter', right: 'literal:0', target: 'isZero' },
        { expression: 'payload.isZero', trueOutcome: 'zero', falseOutcome: 'nonzero' },
        { operation: 'subtract', left: 'field:counter', right: 'literal:1', target: 'counter' },
      ]],
    ];

    for (const [presetId, configuration, expectedProperties] of cases) {
      const authored = graph();
      const inserted = insertRegisterMachinePreset(authored, presetId, configuration);
      const xml = serializeGraphML(authored);
      const reloaded = parseGraphML(xml);
      for (const [index, node] of inserted.nodes.entries()) {
        for (const [key, value] of Object.entries(expectedProperties[index])) {
          expect(reloaded.nodeMap[node.id].properties[key]).toBe(value);
          expect(reloaded.nodeMap[node.id].propertyTypes[key]).toBe('string');
        }
      }
      expect(reloaded.graphProperties).not.toHaveProperty('registerMachineMacro');
      expect(xml).not.toContain('registerMachineMacro');
      expect(xml).not.toContain('presetId');
      expect(xml).not.toContain('9e+');

      if (presetId === 'register-decjz') {
        const [test, decision, decrement] = inserted.nodes;
        expect(inserted.edges.map(edge => {
          const roundTripped = reloaded.edges.find(candidate => candidate.id === edge.id);
          return [roundTripped.source, roundTripped.target, roundTripped.outcome];
        })).toEqual([
          [test.id, decision.id, 'continue'],
          [decision.id, 'end', 'zero'],
          [decision.id, decrement.id, 'nonzero'],
          [decrement.id, 'start', 'continue'],
        ]);
      }
    }
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
