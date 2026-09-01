import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// The server now sends two different numbers per node event -- `activeInstances`, the live
// instances of the node's actor, and `inFlightArrivals`, the depth of the queue in front of them --
// and the view must show the first under a name that says which
// one it is. A caption reading "N active" satisfied neither half: it named no quantity, and the number
// behind it was the second one.
//
// This exercises the REAL `runtimeCountLabel` extracted from src/app.js, following the same pattern as
// activity-identifiers.test.js, because a reimplementation here could agree with itself while the
// shipped view says something else.

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');

function extractFunctionSource(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must exist in app.js for this control to mean anything`).toBeGreaterThan(-1);
  let index = source.indexOf('{', start);
  let depth = 0;
  for (; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) { index += 1; break; }
    }
  }
  return source.slice(start, index);
}

function loadRuntimeCountLabel() {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  // eslint-disable-next-line no-new-func
  return new Function(`
    ${extractFunctionSource(source, 'runtimeCountLabel')}
    return runtimeCountLabel;
  `)();
}

// `runtimeNodeLabel` is the canvas-node counterpart of `runtimeCountLabel` --
// same text, but with the first ` · ` turned into a line break instead of printed inline (`.replace`
// on a string pattern replaces only the FIRST occurrence, never every one). That is a real
// distinction to pin: a caption with both an instance count and an in-flight count has TWO ` · `
// separators, and only the boundary between the name and the stats is meant to become a line break --
// the second separator, between "N instances" and "M in flight", must stay inline as text. Nothing
// exercised this before; a well-meaning switch from `.replace` to a global replacement (a one-word
// change that reads as a harmless generalization) would break every two-line node label on the canvas
// without failing a single test in this file, because `runtimeCountLabel` itself never sees a newline.
//
// `bypassedNodeName` adds the switched-off marker to that composition using the SAME ` · `
// separator, so it lands in the middle of the string `.replace` was reaching into. It is extracted
// here rather than stubbed for the reason this file already gives about `runtimeCountLabel` — a
// stand-in could agree with this test while the shipped canvas said something else.
function loadRuntimeNodeLabel() {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  // eslint-disable-next-line no-new-func
  return new Function(`
    ${extractFunctionSource(source, 'bypassedNodeName')}
    ${extractFunctionSource(source, 'runtimeCountLabel')}
    ${extractFunctionSource(source, 'runtimeNodeLabel')}
    return runtimeNodeLabel;
  `)();
}

function fakeNode(data) {
  return { data: key => data[key] };
}

describe('the elastic caption names the quantity it shows', () => {
  const label = loadRuntimeCountLabel();

  it('says nothing extra for a node that is carrying no work', () => {
    expect(label('ocr', 0, 0)).toBe('ocr');
  });

  it('names the number as instances rather than the old unqualified "active"', () => {
    expect(label('ocr', 3, 3)).toBe('ocr · 3 instances');
    // The regression that matters: "active" said active *what*, and the answer used to be the wrong
    // quantity. If the word ever comes back it must come back attached to something countable.
    expect(label('ocr', 3, 3)).not.toContain('active');
  });

  it('agrees with English on one', () => {
    expect(label('ocr', 1, 1)).toBe('ocr · 1 instance');
  });

  it('shows the queue depth only when it exceeds the instances, which is when the two disagree', () => {
    // A resident node has one shared actor with ten arrivals waiting on it. The caption must show 1
    // as the workload and 10 as the queue, not 10 as both.
    expect(label('source', 1, 10)).toBe('source · 1 instance · 10 in flight');
  });

  it('does not print the queue when it matches the instances, so a mismatch stays noticeable', () => {
    // An ordinary worker node: ten invocations, ten instances. Printing "10 instances · 10 in flight"
    // on every worker node would make the second number background noise exactly where its appearing
    // is supposed to be a signal.
    expect(label('ocr', 10, 10)).toBe('ocr · 10 instances');
  });

  it('never lets a smaller arrival count contradict the instances it is shown beside', () => {
    // Terminal events can report an arrival count below the instance count while other invocations of
    // the same node are still alive. Appending it there would read as a queue shorter than the work
    // being done, which is not a state the reader should be asked to interpret.
    expect(label('ocr', 4, 1)).toBe('ocr · 4 instances');
  });
});

describe('the canvas node label breaks after the name, not after every separator', () => {
  const nodeLabel = loadRuntimeNodeLabel();

  it('is unchanged from the plain name when the node carries no work', () => {
    expect(nodeLabel(fakeNode({ name: 'ocr', instances: 0, arrivals: 0 }))).toBe('ocr');
  });

  it('breaks once, between the name and the instance count, when there is one separator', () => {
    expect(nodeLabel(fakeNode({ name: 'ocr', instances: 3, arrivals: 3 }))).toBe('ocr\n3 instances');
  });

  it('breaks only the first separator and keeps the second inline, when both numbers are shown', () => {
    // `runtimeCountLabel` produces two ` · ` separators here
    // ("source · 1 instance · 10 in flight"), and `runtimeNodeLabel` must turn only the first into a
    // line break -- the one between the node name and its stats -- leaving "1 instance · 10 in flight"
    // together on the second line. A global replacement would instead put each number on its own line,
    // which is not what this renderer's one-line-break contract asks for.
    expect(nodeLabel(fakeNode({ name: 'source', instances: 1, arrivals: 10 })))
      .toBe('source\n1 instance · 10 in flight');
  });
});

describe('the switched-off marker rides on the name, not on the run state', () => {
  const nodeLabel = loadRuntimeNodeLabel();

  it('marks an idle bypassed node on the canvas without opening the Inspector', () => {
    // At the level this label controls, the drawing itself has to say it.
    expect(nodeLabel(fakeNode({ name: 'ocr', instances: 0, arrivals: 0, bypassed: true })))
      .toBe('ocr · bypassed');
  });

  it('leaves an ordinary node label untouched', () => {
    expect(nodeLabel(fakeNode({ name: 'ocr', instances: 0, arrivals: 0, bypassed: false })))
      .toBe('ocr');
  });

  it('keeps the line break after the whole name when a run paints a bypassed node', () => {
    // `runtimeNodeLabel` turns the FIRST ` · ` into a line break,
    // and the bypass marker introduces one INSIDE the name -- so composing the count against the
    // display name would break the line as "ocr\nbypassed · 3 instances", tearing the marker off the
    // node it belongs to and reading as a second statistic. The break belongs after the whole name.
    expect(nodeLabel(fakeNode({ name: 'ocr', instances: 3, arrivals: 3, bypassed: true })))
      .toBe('ocr · bypassed\n3 instances');
  });

  it('still keeps the second separator inline when both numbers and the marker are present', () => {
    expect(nodeLabel(fakeNode({ name: 'source', instances: 1, arrivals: 10, bypassed: true })))
      .toBe('source · bypassed\n1 instance · 10 in flight');
  });
});
