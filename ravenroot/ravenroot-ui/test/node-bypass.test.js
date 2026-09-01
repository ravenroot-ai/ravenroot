import { describe, expect, it } from 'vitest';

import { serializeGraphML } from '../src/graph-document.js';
import { parseGraphML } from '../src/graph-parsers.js';
import {
  BYPASS_ALLOWED_VALUES,
  DEFAULT_BYPASS_PROPERTY,
  bypassPropertyName,
  bypassRoutingConsequence,
  declaredBypass,
  isNodeBypassed,
  nodeAcceptsBypass,
  parseBypassValue,
  untakenBypassOutcomes,
} from '../src/node-bypass.js';

// The editor half of `execution.bypass`. Every assertion here mirrors a rule that already
// exists in Java -- `NodeBypassProperty` for the parsing and the property name, `NodeBypassValidator`
// for where the key is refused -- so what this file actually pins is that the editor does not drift
// from the runtime it writes documents for. The two places where drift would be silent AND harmful
// are the ones with the most cases below: a value the runtime refuses that the editor shows as
// "off", and a control offered on a node kind the runtime refuses the key on entirely.

const CATALOG = [
  { behavior: 'http-request', natureProperty: 'runtime.nature', bypassProperty: 'execution.bypass' },
  { behavior: 'cel-decision', natureProperty: 'runtime.nature', bypassProperty: 'execution.bypass' },
];

describe('the property name comes from the catalog, never from this file', () => {
  it('prefers the name the node own descriptor publishes', () => {
    const descriptor = { behavior: 'http-request', bypassProperty: 'execution.bypass' };
    expect(bypassPropertyName(descriptor, CATALOG)).toBe('execution.bypass');
  });

  it('follows a server that renamed the property, instead of the mirrored constant', () => {
    // The whole reason `/v1/catalog` publishes `bypassProperty` at all: if the two could drift, the
    // editor would write a key the runtime no longer reads and the node would execute anyway.
    const renamed = [{ behavior: 'http-request', bypassProperty: 'execution.skip' }];
    expect(bypassPropertyName(renamed[0], renamed)).toBe('execution.skip');
    expect(bypassPropertyName(null, renamed)).toBe('execution.skip');
  });

  it('answers for an UNCATALOGUED behavior from any other entry', () => {
    // This is the case exists for -- a node the deployment cannot provision, so the catalog has
    // no descriptor for it -- and it is exactly where `runtime.nature` would refuse to answer. The
    // name is platform-fixed and identical on every descriptor, so any entry is authoritative.
    expect(bypassPropertyName(undefined, CATALOG)).toBe('execution.bypass');
  });

  it('falls back to the mirrored constant only when nothing published a name', () => {
    expect(bypassPropertyName(null, [])).toBe(DEFAULT_BYPASS_PROPERTY);
    expect(bypassPropertyName(null, null)).toBe(DEFAULT_BYPASS_PROPERTY);
    expect(bypassPropertyName({ behavior: 'legacy' }, [{ behavior: 'other' }]))
      .toBe(DEFAULT_BYPASS_PROPERTY);
  });

  it('publishes exactly the two values the platform fixes, and no per-type narrowing', () => {
    expect([...BYPASS_ALLOWED_VALUES]).toEqual(['true', 'false']);
    expect(CATALOG.every(entry => !('allowedBypassValues' in entry))).toBe(true);
  });
});

describe('a value the runtime refuses is never shown as "not switched off"', () => {
  it('accepts both spellings GraphML can produce', () => {
    // A document that declares attr.type="boolean" arrives as a typed boolean; one that declares it a
    // string arrives as "true". Both spell the same author intent -- `NodeBypassProperty.parse`.
    expect(parseBypassValue(true)).toBe(true);
    expect(parseBypassValue('true')).toBe(true);
    expect(parseBypassValue(' TRUE ')).toBe(true);
    expect(parseBypassValue(false)).toBe(false);
    expect(parseBypassValue('False')).toBe(false);
  });

  it('returns null rather than a repaired false for anything else', () => {
    // `NodeBypassValidator` refuses the WHOLE GRAPH for these. Reading them as false here would show
    // an unticked box for a document that will not load, which is the one failure mode the flag
    // exists to prevent, told to the author in reverse.
    for (const raw of ['yes', '1', '', 'TRUE!', null, undefined, 0, {}]) {
      expect(parseBypassValue(raw), `parseBypassValue(${JSON.stringify(raw)})`).toBe(null);
    }
  });

  it('separates absent, off, on and unreadable as four different things', () => {
    expect(declaredBypass({}, 'execution.bypass'))
      .toEqual({ state: 'off', declared: false, raw: '' });
    expect(declaredBypass({ 'execution.bypass': 'false' }, 'execution.bypass'))
      .toEqual({ state: 'off', declared: true, raw: 'false' });
    expect(declaredBypass({ 'execution.bypass': 'true' }, 'execution.bypass'))
      .toEqual({ state: 'on', declared: true, raw: 'true' });
    expect(declaredBypass({ 'execution.bypass': 'yes' }, 'execution.bypass'))
      .toEqual({ state: 'unreadable', declared: true, raw: 'yes' });
  });

  it('draws an unreadable value as executing, matching what the runtime would do', () => {
    // `NodeBypassProperty.isBypassed` answers false for a value nobody could read, because silently
    // skipping a node on the strength of one is worse. The canvas has to say the same thing.
    expect(isNodeBypassed({ 'execution.bypass': 'yes' }, 'execution.bypass')).toBe(false);
    expect(isNodeBypassed({ 'execution.bypass': true }, 'execution.bypass')).toBe(true);
    expect(isNodeBypassed(null, 'execution.bypass')).toBe(false);
  });

  it('reads only the derived key, not a lookalike the document happens to carry', () => {
    expect(isNodeBypassed({ bypass: 'true' }, 'execution.bypass')).toBe(false);
    expect(isNodeBypassed({ 'execution.bypass ': 'true' }, 'execution.bypass')).toBe(false);
  });
});

describe('the control exists only where the runtime accepts the key', () => {
  it('accepts BEHAVIOR', () => {
    expect(nodeAcceptsBypass('BEHAVIOR')).toBe(true);
  });

  it('refuses every terminal and structural kind, so the control cannot be rendered there', () => {
    // `NodeBypassValidator` refuses the KEY on these, for `false` as well as `true` -- there is no
    // behaviour to skip. So a disabled-but-present checkbox is not good enough: switching it back
    // off would itself be the refusal. It must not exist.
    for (const kind of ['START', 'END', 'ERROR', 'PASSTHROUGH', '', null, undefined]) {
      expect(nodeAcceptsBypass(kind), `kind ${String(kind)}`).toBe(false);
    }
  });
});

describe('the Inspector names the branches a switched-off node stops taking', () => {
  const EDGES = [
    { source: 'decide', target: 'a', outcome: 'true' },
    { source: 'decide', target: 'b', outcome: 'false' },
    { source: 'decide', target: 'c', outcome: 'continue' },
    { source: 'decide', target: 'd', outcome: 'true' },
    { source: 'other', target: 'e', outcome: 'failed' },
  ];

  it('lists the non-default outcomes of this node only, deduplicated', () => {
    expect(untakenBypassOutcomes(EDGES, 'decide')).toEqual(['true', 'false']);
  });

  it('excludes the default branch, which is precisely the one that IS taken', () => {
    expect(untakenBypassOutcomes(EDGES, 'decide')).not.toContain('continue');
  });

  it('treats a blank outcome as the default, the way the canvas already does', () => {
    expect(untakenBypassOutcomes([{ source: 'n', target: 'x', outcome: '' }], 'n')).toEqual([]);
    expect(untakenBypassOutcomes([{ source: 'n', target: 'x' }], 'n')).toEqual([]);
  });

  it('says nothing for a node that loses nothing', () => {
    // A node whose only outgoing edges are `continue` routes identically switched off. Warning
    // anyway would teach an author to skim the box on the node where it does matter.
    expect(bypassRoutingConsequence([])).toBe('');
  });

  it('names the branches by name, and the join consequence, when there is something to lose', () => {
    const sentence = bypassRoutingConsequence(['true', 'false']);
    expect(sentence).toContain('“true”');
    expect(sentence).toContain('“false”');
    expect(sentence).toContain('continue');
    // The measured consequence recorded in docs/architecture/per-node-execution-bypass.md: the
    // branches that stop firing can leave a downstream join unsatisfiable, and the failure then names
    // the join rather than the flag.
    expect(sentence).toContain('join');
  });

  it('agrees with English on one branch', () => {
    expect(bypassRoutingConsequence(['failed'])).toContain('“failed” branch is');
    expect(bypassRoutingConsequence(['a', 'b'])).toContain('branches are');
  });
});

// The flag has to survive export/import. This is the
// editor's own serializer, not the Java one — a graph saved from here and reopened here must still
// carry the key, and it must survive a save that never touched it. The typed-boolean spelling is
// exercised alongside the string one because both reach the runtime (`NodeBypassProperty.parse`
// accepts each) and only one of them is what the platform's own corpus fixture uses.
describe('the flag survives GraphML export and import', () => {
  const documentWith = (typeAttribute, value) => [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
    '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
    '<key id="kbeh" for="node" attr.name="behavior" attr.type="string"/>',
    `<key id="kbyp" for="node" attr.name="execution.bypass" attr.type="${typeAttribute}"/>`,
    '<graph id="g" edgedefault="directed">',
    '<node id="start"><data key="kkind">START</data></node>',
    '<node id="n0"><data key="kkind">BEHAVIOR</data><data key="kbeh">http-request</data>'
      + `<data key="kbyp">${value}</data></node>`,
    '</graph></graphml>',
  ].join('');

  it('round-trips a string "true" through serialize and re-parse', () => {
    const graph = parseGraphML(documentWith('string', 'true'));
    expect(isNodeBypassed(graph.nodeMap.n0.properties, 'execution.bypass')).toBe(true);

    const reparsed = parseGraphML(serializeGraphML(graph));
    expect(isNodeBypassed(reparsed.nodeMap.n0.properties, 'execution.bypass')).toBe(true);
  });

  it('round-trips the typed-boolean spelling the platform corpus fixture uses', () => {
    // `ravenroot-core/src/test/resources/graphml-corpus/accepted/authored-bypass.graphml` declares
    // `attr.type="boolean"`. A reader that only understood the string would draw that graph's
    // switched-off node as executing.
    const graph = parseGraphML(documentWith('boolean', 'true'));
    expect(isNodeBypassed(graph.nodeMap.n0.properties, 'execution.bypass')).toBe(true);

    const reparsed = parseGraphML(serializeGraphML(graph));
    expect(isNodeBypassed(reparsed.nodeMap.n0.properties, 'execution.bypass')).toBe(true);
  });

  it('does not resurrect the key on a node that never carried it', () => {
    const graph = parseGraphML(documentWith('string', 'false'));
    delete graph.nodeMap.n0.properties['execution.bypass'];

    const reparsed = parseGraphML(serializeGraphML(graph));
    expect(Object.hasOwn(reparsed.nodeMap.n0.properties, 'execution.bypass')).toBe(false);
    expect(isNodeBypassed(reparsed.nodeMap.n0.properties, 'execution.bypass')).toBe(false);
  });
});
