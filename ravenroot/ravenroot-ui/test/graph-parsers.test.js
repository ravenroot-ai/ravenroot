import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  assertGraphMLWithinLimits,
  assertInputWithinByteLimit,
  detectAndParse,
  GRAPH_INPUT_LIMITS,
  GRAPH_INPUT_REJECTION_REASONS,
  GraphInputRejection,
  loadLocalGraphInput,
  loadUrlGraphInput,
  parseGraphifyJSON,
  parseGraphML,
} from '../src/graph-parsers.js';
import { serializeGraphML } from '../src/graph-document.js';
import { STABLE_EDGE_ID_MAX_UTF8_BYTES, stableEdgeIdUtf8Bytes } from '../src/stable-edge-id.js';

// This exercises yEd-style legacy compatibility parsing specifically, not the shipped
// example -- see the fixture's own header for why the two were split apart.
const GRAPHML = readFileSync(resolve('test/fixtures/legacy-yed-compatibility.graphml'), 'utf8');
const GRAPHIFY = JSON.parse(
  readFileSync(resolve('public/examples/graphify-minimal.json'), 'utf8'),
);
const COMPACT_ELEMENT_BOMB = `<graphml>${'<x/>'.repeat(300_000)}</graphml>`;
const TEST_DOCUMENT_MAX_BYTES = 64 * 1024;

function limitDocuments() {
  return [
    {
      label: 'element',
      atLimit: `<graphml>${'<x/>'.repeat(GRAPH_INPUT_LIMITS.maxElements - 1)}</graphml>`,
      aboveLimit: `<graphml>${'<x/>'.repeat(GRAPH_INPUT_LIMITS.maxElements)}</graphml>`,
    },
    {
      label: 'key',
      atLimit: `<graphml>${'<key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys)}</graphml>`,
      aboveLimit: `<graphml>${'<key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)}</graphml>`,
    },
    {
      label: 'attribute',
      atLimit: `<graphml${" a=''".repeat(GRAPH_INPUT_LIMITS.maxAttributes)}></graphml>`,
      aboveLimit: `<graphml${" a=''".repeat(GRAPH_INPUT_LIMITS.maxAttributes + 1)}></graphml>`,
    },
    {
      label: 'namespace declaration',
      atLimit: `<graphml${Array.from(
        { length: GRAPH_INPUT_LIMITS.maxNamespaceDeclarations },
        (_, index) => ` xmlns:p${index}='urn:${index}'`,
      ).join('')}></graphml>`,
      aboveLimit: `<graphml${Array.from(
        { length: GRAPH_INPUT_LIMITS.maxNamespaceDeclarations + 1 },
        (_, index) => ` xmlns:p${index}='urn:${index}'`,
      ).join('')}></graphml>`,
    },
  ];
}

describe('GraphML compatibility', () => {
  it('accepts the exact stable edge-id UTF-8 bound and rejects one byte over without truncation', () => {
    const boundedId = `${'€'.repeat(2_730)}aa`;
    expect(stableEdgeIdUtf8Bytes(boundedId)).toBe(STABLE_EDGE_ID_MAX_UTF8_BYTES);
    const graphml = id => `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">`
      + `<graph id="g" edgedefault="directed"><node id="a"/><node id="b"/>`
      + `<edge id="${id}" source="a" target="b"/></graph></graphml>`;
    expect(parseGraphML(graphml(boundedId)).edges[0].id).toBe(boundedId);
    expect(() => parseGraphML(graphml(`${boundedId}x`)))
      .toThrow(`maximum is ${STABLE_EDGE_ID_MAX_UTF8_BYTES}`);
  });

  it('round-trips and reloads distinct raw edge identities that differ only by surrounding whitespace', () => {
    const source = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
      + '<graph id="g" edgedefault="directed"><node id="a"/><node id="b"/>'
      + '<edge id="edge" source="a" target="b"/>'
      + '<edge id=" edge " source="a" target="b"/></graph></graphml>';
    const parsed = parseGraphML(source);
    expect(parsed.edges.map(edge => edge.id)).toEqual(['edge', ' edge ']);

    const serialized = serializeGraphML(parsed);
    expect(new DOMParser().parseFromString(serialized, 'application/xml')
      .querySelectorAll('edge')[1].getAttribute('id')).toBe(' edge ');
    expect(parseGraphML(serialized).edges.map(edge => edge.id)).toEqual(['edge', ' edge ']);
  });
  it('keeps topology, Ravenroot attributes and GraphML auto-detection', () => {
    const parsed = parseGraphML(GRAPHML);
    const detected = detectAndParse(GRAPHML, 'workflow.graphml', TEST_DOCUMENT_MAX_BYTES);

    expect(parsed.nodes).toHaveLength(2);
    expect(parsed.edges).toHaveLength(1);
    expect(parsed.nodeMap.n0).toMatchObject({ name: 'StartActor', nodeType: 'start', instances: 3 });
    expect(parsed.nodeMap.n1.nodeType).toBe('end');
    expect(detected.edges[0]).toMatchObject({ source: 'n0', target: 'n1', status: 1 });
  });

  it.each(['port', 'hyperedge', 'endpoint'])(
    'accepts an inert key declaration for the standard %s scope',
    scope => {
      const doc = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">`
        + `<key id="d1" for="${scope}" attr.name="custom" attr.type="string"/>`
        + '<graph id="g" edgedefault="directed"><node id="n0"/></graph></graphml>';

      const parsed = parseGraphML(doc);

      expect(parsed.keyDefinitions.d1).toMatchObject({ name: 'custom', scope });
      expect(parsed.nodeMap.n0.properties).not.toHaveProperty('custom');
    },
  );

  it('accepts and round-trips an inert yFiles resources key scoped to graphml', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns" '
      + 'xmlns:y="http://www.yworks.com/xml/graphml">'
      + '<key id="d12" for="graphml" yfiles.type="resources"/>'
      + '<graph id="g" edgedefault="directed"><node id="n0"/></graph>'
      + '<data key="d12"><y:Resources/></data></graphml>';

    const parsed = parseGraphML(doc);
    const serialized = serializeGraphML(parsed);

    expect(parsed.keyDefinitions.d12).toMatchObject({ scope: 'graphml' });
    expect(serialized).toContain('for="graphml"');
    expect(serialized).toContain('<y:Resources/>');
  });

  it('materialises canonical kind and outcome when loading a legacy executable graph', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns" '
      + 'xmlns:y="http://www.yworks.com/xml/graphml">'
      + '<key id="start" for="node" attr.name="start" attr.type="boolean"/>'
      + '<key id="end" for="node" attr.name="end" attr.type="boolean"/>'
      + '<key id="d22" for="edge" yfiles.type="edgegraphics"/>'
      + '<graph id="g" edgedefault="directed">'
      + '<node id="start"><data key="start">true</data></node>'
      + '<node id="end"><data key="end">true</data></node>'
      + '<edge id="e" source="start" target="end"><data key="d22">'
      + '<y:PolyLineEdge><y:EdgeLabel>COMPLETED</y:EdgeLabel></y:PolyLineEdge>'
      + '</data></edge>'
      + '</graph></graphml>';

    const serialized = serializeGraphML(parseGraphML(doc));
    const reparsed = parseGraphML(serialized);

    expect(reparsed.nodeMap.start.kind).toBe('START');
    expect(reparsed.nodeMap.end.kind).toBe('END');
    expect(reparsed.edges[0].outcome).toBe('completed');
    expect(reparsed.edges[0].label).toBe('COMPLETED');
    expect(serialized).toContain('attr.name="kind"');
    expect(serialized).toContain('attr.name="outcome"');
  });

  it('classifies canonical suffix and successful status-code edges as outcome edges', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns" '
      + 'xmlns:y="http://www.yworks.com/xml/graphml">'
      + '<key id="d13" for="edge" attr.name="status" attr.type="int"/>'
      + '<key id="d22" for="edge" yfiles.type="edgegraphics"/>'
      + '<graph id="g" edgedefault="directed">'
      + '<node id="start"/><node id="middle"/><node id="end"/>'
      + '<edge id="suffix" source="start" target="middle"><data key="d22">'
      + '<y:PolyLineEdge><y:EdgeLabel>APPROVED_OUTCOME</y:EdgeLabel></y:PolyLineEdge>'
      + '</data></edge>'
      + '<edge id="status" source="middle" target="end"><data key="d13">204</data>'
      + '<data key="d22"><y:PolyLineEdge><y:EdgeLabel>accepted</y:EdgeLabel>'
      + '</y:PolyLineEdge></data></edge>'
      + '</graph></graphml>';

    const parsed = parseGraphML(doc);
    const reparsed = parseGraphML(serializeGraphML(parsed));

    expect(parsed.edges.map(edge => [edge.id, edge.edgeType])).toEqual([
      ['suffix', 'outcome'],
      ['status', 'outcome'],
    ]);
    expect(reparsed.edges.map(edge => [edge.id, edge.edgeType])).toEqual([
      ['suffix', 'outcome'],
      ['status', 'outcome'],
    ]);
  });

  it('materialises legacy convergences as independent each-arrival merges', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
      + '<key id="start" for="node" attr.name="start" attr.type="boolean"/>'
      + '<key id="end" for="node" attr.name="end" attr.type="boolean"/>'
      + '<graph id="g" edgedefault="directed">'
      + '<node id="start"><data key="start">true</data></node>'
      + '<node id="left"/><node id="right"/><node id="merge"/>'
      + '<node id="end"><data key="end">true</data></node>'
      + '<edge source="start" target="left"/><edge source="start" target="right"/>'
      + '<edge source="left" target="merge"/><edge source="right" target="merge"/>'
      + '<edge source="merge" target="end"/>'
      + '</graph></graphml>';

    const serialized = serializeGraphML(parseGraphML(doc));
    const reparsed = parseGraphML(serialized);

    expect(reparsed.nodeMap.merge.properties.joinPolicy).toBe('each');
    expect(reparsed.nodeMap.start.properties.joinPolicy).toBeUndefined();
  });

  it('still rejects an actual port element because port topology is not supported', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
      + '<graph id="g" edgedefault="directed"><node id="n0"><port name="p"/></node>'
      + '</graph></graphml>';

    expect(() => parseGraphML(doc)).toThrow("GraphML element 'port' is not supported");
  });

  it('rejects DTD and entity declarations before DOMParser', () => {
    expect(() => parseGraphML('<!DOCTYPE graphml><graphml/>'))
      .toThrow('GraphML input rejected: DTD and entity declarations are not allowed');
    expect(() => parseGraphML('<!ENTITY x "value"><graphml/>'))
      .toThrow('GraphML input rejected: DTD and entity declarations are not allowed');
  });

  it.each([
    ['node', 'node', GRAPH_INPUT_LIMITS.maxNodes],
    ['edge', 'edge', GRAPH_INPUT_LIMITS.maxEdges],
    ['property', 'data', GRAPH_INPUT_LIMITS.maxProperties],
  ])('accepts exactly the configured %s limit and rejects N+1', (label, element, limit) => {
    const atLimit = `<graphml><graph>${`<${element}/>` .repeat(limit)}</graph></graphml>`;
    const aboveLimit = `<graphml><graph>${`<${element}/>` .repeat(limit + 1)}</graph></graphml>`;

    expect(() => assertGraphMLWithinLimits(atLimit)).not.toThrow();
    expect(() => assertGraphMLWithinLimits(aboveLimit))
      .toThrow(`GraphML input rejected: ${label} count exceeds the configured limit`);
  });

  it.each(limitDocuments())(
    'accepts exactly the configured $label limit and rejects N+1 before DOMParser',
    ({ label, atLimit, aboveLimit }) => {
      expect(() => assertGraphMLWithinLimits(atLimit)).not.toThrow();

      const domParser = vi.fn();
      vi.stubGlobal('DOMParser', domParser);
      try {
        expect(() => parseGraphML(aboveLimit))
          .toThrow(`GraphML input rejected: ${label} count exceeds the configured limit`);
        try {
          parseGraphML(aboveLimit);
        } catch (error) {
          expect(error).toBeInstanceOf(GraphInputRejection);
          expect(error.reason).toBe(GRAPH_INPUT_REJECTION_REASONS.RESOURCE_LIMIT);
        }
        expect(domParser).not.toHaveBeenCalled();
      } finally {
        vi.unstubAllGlobals();
      }
    },
  );

  it('counts canonical prefixed keys while ignoring inert extension keys', () => {
    const canonical = `<graphml xmlns:g="http://graphml.graphdrawing.org/xmlns">${
      '<g:key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;
    const inert = `<graphml xmlns:x="urn:extension">${
      '<x:key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;
    const inertDefault = `<graphml xmlns="urn:extension">${
      '<key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;

    expect(() => assertGraphMLWithinLimits(canonical))
      .toThrow('GraphML input rejected: key count exceeds the configured limit');
    expect(() => assertGraphMLWithinLimits(inert)).not.toThrow();
    expect(() => assertGraphMLWithinLimits(inertDefault)).not.toThrow();
  });

  it('does not count markup-like text in comments, CDATA, PI data, or quoted values', () => {
    const fakeElements = '<x a="b"/><key/><node/><data/>';
    const xml = `<graphml a="> ${fakeElements}"><!--${fakeElements}--><![CDATA[${
      fakeElements
    }]]><?safe > ${fakeElements}?><x/></graphml>`;

    expect(() => assertGraphMLWithinLimits(xml)).not.toThrow();
  });

  it('rejects a compact 300k-element document before constructing DOMParser', () => {
    expect(new TextEncoder().encode(COMPACT_ELEMENT_BOMB).length).toBeGreaterThan(1_200_000);
    expect(new TextEncoder().encode(COMPACT_ELEMENT_BOMB).length).toBeLessThan(
      2 * 1024 * 1024,
    );
    const domParser = vi.fn();
    vi.stubGlobal('DOMParser', domParser);
    try {
      expect(() => parseGraphML(COMPACT_ELEMENT_BOMB))
        .toThrow('GraphML input rejected: element count exceeds the configured limit');
      expect(domParser).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('enforces depth and string boundaries deterministically', () => {
    const nested = count => '<graphml>' + '<x>'.repeat(count) + '</x>'.repeat(count) + '</graphml>';
    const stringAtLimit = `<graphml><graph><data>${'x'.repeat(GRAPH_INPUT_LIMITS.maxStringLength)}</data></graph></graphml>`;
    const stringOverLimit = `<graphml><graph><data>${'x'.repeat(GRAPH_INPUT_LIMITS.maxStringLength + 1)}</data></graph></graphml>`;

    expect(() => assertGraphMLWithinLimits(nested(GRAPH_INPUT_LIMITS.maxDepth - 1))).not.toThrow();
    expect(() => assertGraphMLWithinLimits(nested(GRAPH_INPUT_LIMITS.maxDepth)))
      .toThrow('GraphML input rejected: XML depth exceeds the configured limit');
    expect(() => assertGraphMLWithinLimits(stringAtLimit)).not.toThrow();
    expect(() => assertGraphMLWithinLimits(stringOverLimit))
      .toThrow('GraphML input rejected: text value exceeds the string length limit');
  });

  it('accepts the byte limit and rejects N+1 bytes before parsing', () => {
    expect(() => assertInputWithinByteLimit('x'.repeat(64), 64)).not.toThrow();
    expect(() => assertInputWithinByteLimit('x'.repeat(65), 64))
      .toThrow('GraphML input rejected: document exceeds the configured byte limit');
  });

  it('counts UTF-8 bytes rather than JavaScript code units', () => {
    expect(() => assertInputWithinByteLimit('€', 3)).not.toThrow();
    expect(() => assertInputWithinByteLimit('€', 2))
      .toThrow('GraphML input rejected: document exceeds the configured byte limit');
  });

  it('accepts a local file exactly at the configured boundary', () => {
    const file = new File(['x'.repeat(64)], 'exact.graphml');
    const parseAndRender = vi.fn();
    const reader = {
      readAsText: vi.fn(function readAsText() {
        this.onload({ target: { result: 'x'.repeat(64) } });
      }),
    };
    const handlers = {
      createReader: vi.fn(() => reader), onStart: vi.fn(), parseAndRender,
      onRejected: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
    };

    expect(loadLocalGraphInput(file, 64, handlers)).toBe(true);
    expect(reader.readAsText).toHaveBeenCalledWith(file);
    expect(parseAndRender).toHaveBeenCalledWith('x'.repeat(64));
    expect(handlers.onRejected).not.toHaveBeenCalled();
    expect(handlers.onComplete).toHaveBeenCalledOnce();
  });

  it('rejects an oversized local File before its reader, parser, or renderer runs', () => {
    const file = new File(['x'], 'oversized.graphml', { type: 'application/graphml+xml' });
    Object.defineProperty(file, 'size', { value: 65 });
    const handlers = {
      createReader: vi.fn(), onStart: vi.fn(), parseAndRender: vi.fn(),
      onRejected: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
    };

    expect(loadLocalGraphInput(file, 64, handlers)).toBe(false);
    expect(handlers.createReader).not.toHaveBeenCalled();
    expect(handlers.onStart).not.toHaveBeenCalled();
    expect(handlers.parseAndRender).not.toHaveBeenCalled();
    expect(handlers.onComplete).not.toHaveBeenCalled();
    expect(handlers.onRejected).toHaveBeenCalledWith(expect.objectContaining({
      message: 'GraphML input rejected: document exceeds the configured byte limit',
    }));
  });

  it('rejects an oversized URL Content-Length before parse or render runs', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('', {
      headers: { 'content-length': '65' },
    }));
    const handlers = {
      fetchImpl, onStart: vi.fn(), parseAndRender: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
    };

    await expect(loadUrlGraphInput('https://example.test/oversized.graphml', 64, handlers)).resolves.toBe(false);
    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(handlers.parseAndRender).not.toHaveBeenCalled();
    expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({
      message: 'GraphML input rejected: document exceeds the configured byte limit',
    }));
    expect(handlers.onComplete).toHaveBeenCalledOnce();
  });

  it('cancels an N+1 chunked URL body before parse or render runs', async () => {
    const reader = {
      read: vi.fn()
        .mockResolvedValueOnce({ done: false, value: new Uint8Array(64) })
        .mockResolvedValueOnce({ done: false, value: new Uint8Array([0]) }),
      cancel: vi.fn().mockResolvedValue(undefined),
      releaseLock: vi.fn(),
    };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, headers: { get: () => null }, body: { getReader: () => reader },
    });
    const handlers = {
      fetchImpl, onStart: vi.fn(), parseAndRender: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
    };

    await expect(loadUrlGraphInput('https://example.test/chunked.graphml', 64, handlers)).resolves.toBe(false);
    expect(reader.cancel).toHaveBeenCalledOnce();
    expect(reader.releaseLock).toHaveBeenCalledOnce();
    expect(handlers.parseAndRender).not.toHaveBeenCalled();
    expect(handlers.onComplete).toHaveBeenCalledOnce();
  });

  it('accepts a streamed URL body exactly at the configured boundary', async () => {
    const parseAndRender = vi.fn();
    const handlers = {
      fetchImpl: vi.fn().mockResolvedValue(new Response('x'.repeat(64), {
        headers: { 'content-length': '64' },
      })),
      onStart: vi.fn(), parseAndRender, onError: vi.fn(), onComplete: vi.fn(),
    };

    await expect(loadUrlGraphInput('https://example.test/exact.graphml', 64, handlers))
      .resolves.toBe(true);
    expect(parseAndRender).toHaveBeenCalledWith('x'.repeat(64));
    expect(handlers.onError).not.toHaveBeenCalled();
  });

  it.each([null, 'not-a-number', '1'])(
    'enforces streamed bytes when Content-Length is absent, malformed, or misleading: %s',
    async contentLength => {
      const reader = {
        read: vi.fn()
          .mockResolvedValueOnce({ done: false, value: new Uint8Array(65) })
          .mockResolvedValueOnce({ done: true }),
        cancel: vi.fn().mockResolvedValue(undefined),
        releaseLock: vi.fn(),
      };
      const handlers = {
        fetchImpl: vi.fn().mockResolvedValue({
          ok: true,
          headers: { get: () => contentLength },
          body: { getReader: () => reader },
        }),
        onStart: vi.fn(), parseAndRender: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
      };

      await expect(loadUrlGraphInput('https://example.test/untrusted-length.graphml', 64, handlers))
        .resolves.toBe(false);
      expect(reader.cancel).toHaveBeenCalledOnce();
      expect(handlers.parseAndRender).not.toHaveBeenCalled();
      expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({
        reason: GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
      }));
    },
  );

  it('keeps the byte-limit verdict when cancelling an oversized stream fails', async () => {
    const reader = {
      read: vi.fn().mockResolvedValueOnce({ done: false, value: new Uint8Array(65) }),
      cancel: vi.fn().mockRejectedValue(new Error('transport cancellation failed')),
      releaseLock: vi.fn(),
    };
    const handlers = {
      fetchImpl: vi.fn().mockResolvedValue({
        ok: true, headers: { get: () => null }, body: { getReader: () => reader },
      }),
      onStart: vi.fn(), parseAndRender: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
    };

    await expect(loadUrlGraphInput('https://example.test/cancel-fails.graphml', 64, handlers))
      .resolves.toBe(false);
    expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({
      message: 'GraphML input rejected: document exceeds the configured byte limit',
      reason: GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
    }));
    expect(reader.releaseLock).toHaveBeenCalledOnce();
  });

  it('rejects a complexity-bounded local file before DOM parse or render', () => {
    const domParser = vi.fn();
    const render = vi.fn();
    const handlers = {
      createReader: () => ({
        readAsText() {
          this.onload({ target: { result: COMPACT_ELEMENT_BOMB } });
        },
      }),
      onStart: vi.fn(),
      parseAndRender: text => {
        const graph = parseGraphML(text);
        render(graph);
      },
      onRejected: vi.fn(),
      onError: vi.fn(),
      onComplete: vi.fn(),
    };
    const file = new File([COMPACT_ELEMENT_BOMB], 'compact.graphml');
    vi.stubGlobal('DOMParser', domParser);
    try {
      expect(loadLocalGraphInput(file, 2 * 1024 * 1024, handlers)).toBe(true);
      expect(domParser).not.toHaveBeenCalled();
      expect(render).not.toHaveBeenCalled();
      expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({
        name: 'GraphInputRejection',
        reason: GRAPH_INPUT_REJECTION_REASONS.RESOURCE_LIMIT,
      }));
      expect(handlers.onComplete).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('rejects a streamed complexity-bounded URL before DOM parse or render', async () => {
    const domParser = vi.fn();
    const render = vi.fn();
    const handlers = {
      fetchImpl: vi.fn().mockResolvedValue(new Response(COMPACT_ELEMENT_BOMB)),
      onStart: vi.fn(),
      parseAndRender: text => {
        const graph = parseGraphML(text);
        render(graph);
      },
      onError: vi.fn(),
      onComplete: vi.fn(),
    };
    vi.stubGlobal('DOMParser', domParser);
    try {
      await expect(loadUrlGraphInput('https://example.test/compact.graphml', 2 * 1024 * 1024, handlers))
        .resolves.toBe(false);
      expect(domParser).not.toHaveBeenCalled();
      expect(render).not.toHaveBeenCalled();
      expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({
        name: 'GraphInputRejection',
        reason: GRAPH_INPUT_REJECTION_REASONS.RESOURCE_LIMIT,
      }));
      expect(handlers.onComplete).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  // serializeGraphML/createWorkflowDocument WRITE graph-level `<data>` (currently only
  // `join.semantics`) via `graph.graphProperties`. The parser must read it back; otherwise a document
  // that genuinely carries the marker looks exactly like one that never had it. That would defeat
  // the "no-op on an already-declared document" half of the Migrate Join Semantics action and the
  // Inspector's "which kind is in effect" reading alike, for any document reopened rather than freshly
  // created in this session.
  it('reads a graph-level <data> (the join.semantics marker) back into graphProperties', () => {
    const withMarker = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<key id="kjoin" for="graph" attr.name="join.semantics" attr.type="string"/>',
      '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
      '<graph id="g" edgedefault="directed">',
      '<data key="kjoin">declared</data>',
      '<node id="start"><data key="kkind">START</data></node>',
      '<node id="end"><data key="kkind">END</data></node>',
      '<edge id="e" source="start" target="end"/>',
      '</graph></graphml>',
    ].join('');
    const withoutMarker = withMarker.replace('<data key="kjoin">declared</data>', '');

    expect(parseGraphML(withMarker).graphProperties).toEqual({ 'join.semantics': 'declared' });
    expect(parseGraphML(withoutMarker).graphProperties).toEqual({});
    // And the round trip through this editor's own writer preserves it byte for byte, same as any
    // other property this file reads and serializeGraphML writes back.
    expect(serializeGraphML(parseGraphML(withMarker))).toContain('>declared<');
  });
});

describe('Graphify JSON compatibility', () => {
  it('supports edges and automatic JSON detection', () => {
    const parsed = parseGraphifyJSON(GRAPHIFY);
    const detected = detectAndParse(JSON.stringify(GRAPHIFY), 'graph.json', TEST_DOCUMENT_MAX_BYTES);

    expect(parsed.format).toBe('graphify');
    expect(parsed.nodes).toHaveLength(2);
    expect(parsed.edges[0]).toMatchObject({
      source: 'file', target: 'method', gfRelation: 'contains', edgeType: 'outcome',
    });
    expect(detected.nodeMap.method.name).toBe('run()');
  });

  it('supports NetworkX-style links', () => {
    const parsed = parseGraphifyJSON({ nodes: GRAPHIFY.nodes, links: GRAPHIFY.edges });

    expect(parsed.edges).toHaveLength(1);
  });

  it('uses the shared configured byte boundary before JSON parsing', () => {
    const text = JSON.stringify(GRAPHIFY);
    const bytes = new TextEncoder().encode(text).byteLength;

    expect(detectAndParse(text, 'graph.json', bytes).format).toBe('graphify');
    try {
      detectAndParse(text, 'graph.json', bytes - 1);
      throw new Error('expected Graphify byte rejection');
    } catch (error) {
      expect(error).toMatchObject({ reason: GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE });
    }
  });

  it('loads Graphify through the same pre-read local-file boundary as GraphML', () => {
    const text = JSON.stringify(GRAPHIFY);
    const bytes = new TextEncoder().encode(text).byteLength;
    const file = new File([text], 'graph.json', { type: 'application/json' });
    const rendered = vi.fn();
    const handlers = {
      createReader: () => ({
        readAsText() { this.onload({ target: { result: text } }); },
      }),
      onStart: vi.fn(),
      parseAndRender: raw => rendered(detectAndParse(raw, file.name, bytes)),
      onRejected: vi.fn(), onError: vi.fn(), onComplete: vi.fn(),
    };

    expect(loadLocalGraphInput(file, bytes, handlers)).toBe(true);
    expect(rendered).toHaveBeenCalledWith(expect.objectContaining({ format: 'graphify' }));

    const rejected = { ...handlers, createReader: vi.fn(), onRejected: vi.fn() };
    expect(loadLocalGraphInput(file, bytes - 1, rejected)).toBe(false);
    expect(rejected.createReader).not.toHaveBeenCalled();
    expect(rejected.onRejected).toHaveBeenCalledWith(expect.objectContaining({
      reason: GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
    }));
  });
});

describe('Regression: inherited Object.prototype property names are never treated as declared ids', () => {
  // QA-07 fuzzing discovered this case. It is a fixed, minimal,
  // always-run pin that does not depend on `npm run test:fuzz` to catch a regression here.
  // test/graph-parsers.fuzz.test.js keeps its own generative version (all eight
  // Object.prototype property names, both parsers) for ongoing exploration.
  //
  // The gate-bypass half matters most: parseGraphifyJSON's edge filter
  // (`nodeMap[e.source] && nodeMap[e.target]`) is the actual check deciding which edges reach the
  // renderer, and with nodeMap as a plain `{}` it accepted 'toString' as an existing node id with
  // zero nodes ever declared -- an existence check satisfiable by naming a method the object never
  // had, failing open rather than merely not flagging anything.
  it('parseGraphifyJSON drops an edge naming "toString" as an endpoint when no such node was declared', () => {
    const parsed = parseGraphifyJSON({
      nodes: [{ id: 'real-node', label: 'Real' }],
      edges: [{ source: 'real-node', target: 'toString' }],
    });

    expect(parsed.edges).toHaveLength(0);
  });

  it('parseGraphML accepts a key id of "toString" as a first declaration, not a duplicate', () => {
    const doc = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">`
      + `<key id="toString" for="node" attr.name="p" attr.type="string"/>`
      + '<graph id="g" edgedefault="directed"><node id="n0"/></graph></graphml>';

    expect(() => parseGraphML(doc)).not.toThrow();
  });
});
