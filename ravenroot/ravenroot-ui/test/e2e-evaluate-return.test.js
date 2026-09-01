import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

// The static guard for a fault that produces `page.evaluate: Target crashed` on a different test
// each run, making one serialization failure look like several unrelated flakes.
//
// The mechanism, measured rather than argued (`e2e/edge-authoring.spec.js:fitPinnedGraph` carries
// the numbers): every Cytoscape mutator returns the core, or the collection it was called on, so
// that calls can be chained. A CONCISE arrow body returns that object, and `page.evaluate` then
// asks the renderer to serialise a live Cytoscape instance -- every element, every style, the
// renderer, its canvases, all cyclic -- back across the wire. `() => window.cy` on its own, calling
// nothing, costs 6.9 seconds; `() => window.cy.fit(undefined, 80)` costs 9.4 seconds and then kills
// the renderer process. The same expression with a BLOCK body costs 1-5ms.
//
// This is a property of how the call is WRITTEN, not of any one test, which is exactly why fixing
// the known sites is not enough: the next `page.evaluate(() => someCollection.emit('tap'))` anyone
// writes reopens it, and it will again look like a flake in whichever test is unlucky. Hence a
// check, not a comment.
//
// TWO WAYS OUT, both fine, and the guard must not confuse them:
// - a BLOCK body, `() => { window.cy.fit(undefined, 80); }`, returning nothing;
// - `page.evaluateHandle(...)`, which returns a JSHandle and serialises NOTHING. Measured on the
// same page: `evaluateHandle(() => window.cy)` costs 4ms and the handle is fully usable
// (`handle.evaluate(instance => instance.nodes().length)` answers). `evaluateHandle` is
// therefore never flagged -- it is the correct way to hand a live Cytoscape object back, and a
// guard that reddened `npm test` over it would be giving wrong advice in CI.

const E2E_DIR = 'e2e';

// ── What is flagged ────────────────────────────────────────────────────────────────────────────
// Cytoscape methods that return the core or a collection at EVERY arity, so no argument shape can
// make them safe.
const ALWAYS_CHAINING = [
  'fit', 'stop', 'resize', 'center', 'emit', 'trigger', 'select', 'unselect',
  'addClass', 'removeClass', 'toggleClass', 'lock', 'unlock', 'restore', 'destroy',
  'batch', 'startBatch', 'endBatch', 'removeStyle', 'removeData', 'removeScratch',
  'panBy', 'add', 'remove', 'move', 'layout', 'run', 'animate', 'delay', 'stopAnimation',
  'on', 'off', 'one', 'bind', 'unbind', 'listen', 'unlisten', 'promiseOn',
];

// Accessors whose return value IS a collection. `() => window.cy.nodes()` is the same 5-second
// serialisation as `() => window.cy.fit(...)`; nothing has to be mutated for this to bite.
//
// The bar this list has to clear is EVERY NAME THIS REPOSITORY ACTUALLY WRITES, checkable with a
// grep -- not "every name in the Cytoscape API". Some entries below (`symmetricDifference`,
// `codirectedEdges`, `absoluteComplement`) have zero occurrences here; they are harmless and cost
// nothing, but they are not the reason the list is adequate, and padding it with unused names is
// the same defect as omitting used ones, pointing the other way.
const COLLECTION_PRODUCERS = [
  // `$` first, because it is the one this suite actually writes: `cy.$('#start')` appears in eight
  // concise `evaluate` bodies across five files, saved so far only by what follows it in the chain.
  // On its own it costs a measured 6917ms, and `$id('start')` -- Cytoscape's shorthand for
  // `getElementById` -- costs 4392ms. Covering the same function under one name while missing the
  // other would leave the guard blind to exactly the alias this suite uses.
  '$', '$id',
  'nodes', 'edges', 'elements', 'getElementById', 'collection', 'children', 'parent',
  'ancestors', 'descendants', 'neighborhood', 'closedNeighborhood', 'openNeighborhood',
  'connectedNodes', 'connectedEdges', 'source', 'target', 'incomers', 'outgoers',
  'union', 'difference', 'intersection', 'not', 'absoluteComplement', 'symmetricDifference',
  'first', 'last', 'eq', 'roots', 'leaves', 'merge', 'unmerge', 'parallelEdges', 'codirectedEdges',
];

// `data`, `style` and `scratch` are covered below because they are the mutators this suite reaches
// for most often, which makes them the likeliest way for a sixth site to be written -- more likely
// than another `emit('tap')`. Measured, same page, same instance, concise body: `data('name', 'x')`
// 7214ms, `style('background-color', 'red')` 4545ms, `scratch('k', 1)` 5274ms,
// `cy.remove(cy.getElementById('error'))` 5425ms. A list limited to `zoom`/`pan`/`position` that
// treats `style('x')` and `data('k')` only as getters lets all three mutating forms through. The
// arity distinction below covers those setters without flagging the getter forms.
//
// Setters told apart from same-named getters by their ARGUMENTS. One argument is enough for these:
// `zoom(0.85)` sets and returns the core, `zoom()` reads and returns a number.
const ONE_ARGUMENT_SETTERS = [
  'zoom', 'pan', 'viewport', 'minZoom', 'maxZoom', 'json', 'classes', 'shift',
];

// These need an object literal or a second argument, because their ONE-argument form is a getter
// returning a plain value: `style('curve-style')`, `data('name')`, `position('x')`, `scratch('k')`.
// The distinction is load-bearing in both directions -- the getter form appears sixteen times
// across nine files in this suite and must stay silent, while `data('name', 'x')` costs 7214ms.
const OBJECT_OR_SECOND_ARGUMENT_SETTERS = [
  'data', 'style', 'scratch', 'position', 'renderedPosition', 'relativePosition',
];

// ── What is NOT flagged, and why ───────────────────────────────────────────────────────────────
// Declared rather than silently omitted, because "the guard is green" has to mean something exact.
//
// 1. ARRAY-AMBIGUOUS TAIL CALLS -- `map`, `filter`, `sort`, `slice`, `find`, `some`, `every`,
// `reduce`, `concat`, `flat`. A Cytoscape collection's own `filter`/`sort` DO return
// collections, so this is a real gap. It is left open on purpose: `window.cy.nodes().map(node
// => node.id()).sort()` is an Array by the time `.sort()` runs and appears all over this suite,
// and a guard that reddened it would be switched off within a week. `forEach` is the one
// exception -- see below -- because it is the only one of these whose collection form actually
// reads like a mutation.
// 2. CALLBACKS NOT WRITTEN INLINE. The scan looks at the first argument of an `evaluate` call and
// only when it is an arrow written at the call site, which is how all of `e2e/` is written
// today. A callback passed by reference is not covered.
// 3. A CORE OR COLLECTION INSIDE AN OBJECT OR ARRAY LITERAL.
// `() => ({ nodes: window.cy.nodes(), zoom: window.cy.zoom() })` serialises just as badly and is
// NOT flagged: the guard looks at the expression's tail call, and the tail here is a literal, not
// a call. Returning a literal built from getters is an established idiom in at least five files
// in this suite, so the check has to look INSIDE the literal at each property to tell the two
// apart -- a real extension, not a one-line widening, and deliberately not attempted here.
// 4. EXPRESSIONS WITH NO VISIBLE CYTOSCAPE RECEIVER. A body is only considered if it mentions a
// Cytoscape handle (`window.cy`, or a chain ending in `.cy`). This is what keeps
// `document.getElementById('x').remove()` and `document.querySelector('input').select()` out --
// `remove` and `select` are DOM methods too, and on a DOM element they return `undefined`. The
// cost is that a Cytoscape handle reached under some other name would be missed.
//
// GREEN HERE IS NOT PROOF OF ABSENCE. It means no inline concise-bodied `evaluate` returns a
// Cytoscape object through one of the shapes above.
const ARRAY_AMBIGUOUS = ['map', 'filter', 'sort', 'slice', 'find', 'some', 'every', 'reduce', 'concat', 'flat'];

// A Cytoscape handle returned with no call at all -- the 6864ms case.
const bareHandleTail = /(?:^|[\s(])(?:window\.cy|[\w$)\]]+\.cy)\s*$/;

// `!x`, `!!x`, `typeof x` and `void x` evaluate to a boolean, a string or `undefined` whatever `x`
// is, so nothing they wrap can be serialised. Adding `!` to the receiver character class would turn
// `() => !window.cy` into a false positive on correct code.
const PRIMITIVE_VALUED_UNARY = /^(?:!+|typeof\s|void\s)/;
// Does this expression touch a Cytoscape handle at all?
const hasCytoscapeReceiver = /(?:^|[^\w$.])(?:window\.cy|[\w$)\]]+\.cy)(?![\w$])/;

// Comments are stripped before the scan, and it is not a nicety: the explanation of this fault has to
// WRITE OUT the forbidden form to explain it, and a guard that reads its own explanation as a
// violation is a guard that gets deleted. Strings and template literals are skipped so an `//`
// inside a URL never truncates a line, and every removed character is replaced by a space so line
// numbers still point at the real source.
export function withoutComments(source) {
  let output = '';
  let index = 0;
  while (index < source.length) {
    const character = source[index];
    const pair = source.slice(index, index + 2);
    if (pair === '//') {
      while (index < source.length && source[index] !== '\n') { output += ' '; index += 1; }
    } else if (pair === '/*') {
      const end = source.indexOf('*/', index + 2);
      const stop = end === -1 ? source.length : end + 2;
      output += source.slice(index, stop).replace(/[^\n]/g, ' ');
      index = stop;
    } else if (character === '"' || character === "'" || character === '`') {
      output += character;
      index += 1;
      while (index < source.length && source[index] !== character) {
        if (source[index] === '\\') { output += source.slice(index, index + 2); index += 2; continue; }
        output += source[index];
        index += 1;
      }
      output += source[index] ?? '';
      index += 1;
    } else {
      output += character;
      index += 1;
    }
  }
  return output;
}

function closingParenIndex(source, openIndex) {
  let depth = 0;
  for (let index = openIndex; index < source.length; index += 1) {
    if (source[index] === '(') depth += 1;
    else if (source[index] === ')') {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}

// The value of `a ? b : c`, of `a && b`, of `a || b` and of `(x)` is one of its parts, so each part
// is examined. Without this a single pair of brackets or one ternary walks straight past the guard.
// Operators whose LEFT side can never be the value of the expression. `a && b` evaluates to `b` or
// to `a`'s falsy value -- never to a live core -- and `cond ? b : c` evaluates to `b` or `c`, never
// to `cond`. Checking those positions is the same defect this file already documents for `?.` and
// `??`: it manufactures a bare `window.cy` fragment out of code that returns a boolean. `||` is NOT
// here -- `a || b` really can evaluate to `a` -- and neither are the ternary's two branches.
const VALUE_CANNOT_COME_FROM_LEFT = ['&&', '?'];

function returnedExpressions(body) {
  let expression = body.trim();
  while (expression.startsWith('(') && closingParenIndex(expression, 0) === expression.length - 1) {
    expression = expression.slice(1, -1).trim();
  }
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index];
    if ('([{'.includes(character)) depth += 1;
    else if (')]}'.includes(character)) depth -= 1;
    else if (depth === 0) {
      const two = expression.slice(index, index + 2);
      // `?.` is optional chaining and `??` is nullish coalescing -- neither is a ternary, and
      // splitting on them leaves a bare `window.cy` fragment that the handle check then reports.
      // That is how `window.cy?.elements().length ?? 0`, a plain number, got flagged.
      if (two === '?.' || two === '??') { index += 1; continue; }
      if (character === '?' || character === ':' || two === '&&' || two === '||') {
        // An arrow inside the body (`node => node.id()`) also uses `=>`; only split on the
        // operators above, and never on the `:` of an object literal, which is inside braces.
        const operator = two === '&&' || two === '||' ? two : character;
        if (!VALUE_CANNOT_COME_FROM_LEFT.includes(operator)) parts.push(expression.slice(start, index));
        index += two === '&&' || two === '||' ? 1 : 0;
        start = index + 1;
      }
    }
  }
  parts.push(expression.slice(start));
  return parts.map(part => part.trim()).filter(Boolean);
}

// Every `evaluate(<arrow>)` in `source` whose arrow has a concise body, as `{ line, body }`.
// `evaluateHandle` is deliberately excluded here rather than filtered later: it does not serialise,
// so there is nothing for this guard to say about it.
export function conciseEvaluateBodies(rawSource) {
  const source = withoutComments(rawSource);
  const found = [];
  const opener = /\bevaluate\s*\(\s*(?:async\s+)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>\s*/g;
  let match = opener.exec(source);
  while (match) {
    const bodyStart = match.index + match[0].length;
    if (source[bodyStart] !== '{') {
      const close = closingParenIndex(source, source.indexOf('(', match.index));
      if (close !== -1) {
        // Stop at the argument separator when `evaluate(fn, arg)` passes one, so the argument is
        // never mistaken for part of the body.
        let end = close;
        let depth = 0;
        for (let index = bodyStart; index < close; index += 1) {
          const character = source[index];
          if ('([{'.includes(character)) depth += 1;
          else if (')]}'.includes(character)) depth -= 1;
          else if (character === ',' && depth === 0) { end = index; break; }
        }
        found.push({
          line: source.slice(0, match.index).split('\n').length,
          body: source.slice(bodyStart, end).trim(),
        });
      }
    }
    match = opener.exec(source);
  }
  return found;
}

// The final call of `expression`, as `{ name, args }`, or null when it does not end in one. Parsed
// by walking back from the closing parenthesis rather than matched with a regular expression: the
// argument list nests, and `cy.remove(cy.getElementById('error'))` -- a real 5425ms offender -- is
// exactly the shape a `[^()]*` argument pattern reads straight past.
function tailCall(expression) {
  const text = expression.trim();
  if (!text.endsWith(')')) return null;
  let depth = 0;
  let open = -1;
  for (let index = text.length - 1; index >= 0; index -= 1) {
    if (text[index] === ')') depth += 1;
    else if (text[index] === '(') {
      depth -= 1;
      if (depth === 0) { open = index; break; }
    }
  }
  if (open <= 0) return null;
  const name = /\.([A-Za-z_$][\w$]*)\s*$/.exec(text.slice(0, open));
  return name ? { name: name[1], args: text.slice(open + 1, -1).trim() } : null;
}

function argumentCount(args) {
  if (!args) return 0;
  let depth = 0;
  let count = 1;
  for (const character of args) {
    if ('([{'.includes(character)) depth += 1;
    else if (')]}'.includes(character)) depth -= 1;
    else if (character === ',' && depth === 0) count += 1;
  }
  return count;
}

export function offendingBodies(source) {
  return conciseEvaluateBodies(source).filter(({ body }) => {
    const flat = body.replace(/\s+/g, ' ').trim();
    if (!hasCytoscapeReceiver.test(flat)) return false;
    return returnedExpressions(flat).some(expression => {
      if (PRIMITIVE_VALUED_UNARY.test(expression)) return false;
      if (bareHandleTail.test(expression)) return true;
      const call = tailCall(expression);
      if (!call) return false;
      if (ALWAYS_CHAINING.includes(call.name) || COLLECTION_PRODUCERS.includes(call.name)) return true;
      if (ONE_ARGUMENT_SETTERS.includes(call.name)) return argumentCount(call.args) >= 1;
      if (OBJECT_OR_SECOND_ARGUMENT_SETTERS.includes(call.name)) {
        return argumentCount(call.args) >= 2 || call.args.startsWith('{');
      }
      if (call.name === 'forEach') {
        return !ARRAY_AMBIGUOUS.some(name => expression.includes(`.${name}(`));
      }
      return false;
    });
  });
}

describe('e2e callbacks never return a live Cytoscape object', () => {
  it('finds no concise-bodied evaluate that returns the core or a collection', async () => {
    const entries = await readdir(E2E_DIR, { recursive: true, withFileTypes: true });
    const specs = entries
      .filter(entry => entry.isFile() && entry.name.endsWith('.js'))
      .map(entry => join(entry.parentPath ?? entry.path, entry.name))
      .sort();
    expect(specs.length, 'the sweep found no e2e sources to check').toBeGreaterThan(20);

    const offenders = [];
    for (const spec of specs) {
      const source = await readFile(spec, 'utf8');
      offendingBodies(source).forEach(({ line, body }) => offenders.push(`${spec}:${line}  ${body}`));
    }
    expect(offenders, 'use a block body, or evaluateHandle if you really need the object')
      .toEqual([]);
  });

  it('reads code, not the comments that have to quote the forbidden form to explain it', () => {
    const commented = [
      "// await page.evaluate(() => window.cy.fit(undefined, 80)); <- what NOT to write",
      "/* `() => window.cy.getElementById('x').emit('tap')` returns the collection. */",
      "const doc = await page.evaluate(() => document.location.href); // see https://x/y",
    ].join('\n');
    expect(offendingBodies(commented)).toEqual([]);
  });

  it('catches every unsafe callback shape and the nine sites exactly', () => {
    // CONTROL for the absence above. An absence assertion whose matcher is wrong passes for every
    // input, so the same expression is shown finding the exact lines that were removed — the four
    // sibling setup helpers' shared `fit`, and the `emit('tap')` inside a test body that is why the
    // signature could never be "the crash happens inside a setup helper".
    const planted = [
      "await page.evaluate(() => window.cy.fit(undefined, 80));",
      "await page.evaluate(() => window.cy.getElementById('edge-1').emit('tap'));",
      "await page.evaluate(() => window.cy.getElementById('start').select());",
      "await page.evaluate(() => window.cy.resize());",
      "await page.evaluate(() => window.cy);",
      "await page.evaluate(id => window.ravenroot.workspace.find(id).cy.zoom(0.31), first);",
    ].join('\n');
    expect(offendingBodies(planted).map(entry => entry.line)).toEqual([1, 2, 3, 4, 5, 6]);

    // And the count, pinned rather than asserted in prose. This guard reports exactly these NINE bodies across the 29
    // JavaScript sources under `e2e/`, and nothing else. Nine, not five: the four siblings' `fit`
    // is one shape but four call sites, and `visual-style-layout-separation.spec.js` carried two.
    const historical = [
      "await page.evaluate(() => window.cy.fit(undefined, 80));",             // pinNodesApart
      "await page.evaluate(() => window.cy.fit(undefined, 80));",             // useCytoAndPinNodesApart
      "await page.evaluate(() => window.cy.fit(undefined, 80));",             // useN8nAndPinNodesApart
      "await page.evaluate(() => window.cy.fit(undefined, 80));",             // useRendererAndPinNodes
      "await page.evaluate(() => window.cy.getElementById('edge-1').emit('tap'));",
      "await page.evaluate(() => window.cy.getElementById('start').select());",
      "await page.evaluate(() => window.cy.resize());",
      "await page.evaluate(() => window.cy.getElementById('start').emit('tap'));",
      "await page.evaluate(() => window.cy.getElementById('node-1').emit('tap'));",
    ].join('\n');
    expect(offendingBodies(historical)).toHaveLength(9);
  });

  it('catches two-argument setters and collection producers', () => {
    // Each of these is a measured multi-second serialisation that incomplete setter coverage would
    // report as clean: `data` 7214ms, `style` 4545ms, `scratch` 5274ms,
    // `cy.remove(...)` 5425ms, and `nodes()` needs no mutation at all to return a collection.
    const planted = [
      "await page.evaluate(() => window.cy.getElementById('start').data('name', 'x'));",
      "await page.evaluate(() => window.cy.getElementById('start').style('background-color', 'red'));",
      "await page.evaluate(() => window.cy.scratch('_rrProbe', 1));",
      "await page.evaluate(() => window.cy.remove(window.cy.getElementById('error')));",
      "await page.evaluate(() => window.cy.add({ group: 'nodes', data: { id: 'x' } }));",
      "await page.evaluate(() => window.cy.nodes());",
      "await page.evaluate(() => window.cy.layout({ name: 'preset' }).run());",
      "await page.evaluate(() => (window.cy.getElementById('start').addClass('x')));",
      "await page.evaluate(() => window.cy.nodes().length > 1 ? window.cy.nodes().first() : window.cy.edges());",
      "await page.evaluate(() => window.cy.nodes().forEach(node => node.lock()));",
      "await page.evaluate(() => window.cy.$('#start'));",
      "await page.evaluate(() => window.cy.$id('start'));",
    ].join('\n');
    expect(offendingBodies(planted).map(entry => entry.line))
      .toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  });

  it('checks only the operand a value can actually come from', () => {
    // `a && b` is `b` or a falsy `a`; `cond ? b : c` is `b` or `c`. Checking the other side
    // manufactures a bare `window.cy` out of an expression that returns a boolean — the same defect
    // this file documents for `?.` and `??`. `window.cy && !window.cy.scratch(...)` already exists
    // in this suite under `waitForFunction`; converting it to an `evaluate` poll is an ordinary
    // refactor and must not redden CI.
    const correct = [
      "await page.evaluate(() => window.cy && window.cy.nodes().length);",
      "await page.evaluate(() => !window.cy);",
      "await page.evaluate(() => !!window.cy);",
      "await page.evaluate(() => typeof window.cy);",
      "await page.evaluate(() => window.cy ? 1 : 2);",
    ].join('\n');
    expect(offendingBodies(correct)).toEqual([]);

    // …and the splitting still has to happen, or `||` and the ternary's own branches walk past.
    const offending = [
      "await page.evaluate(() => window.cy || null);",
      "await page.evaluate(() => window.cy.nodes().length && window.cy);",
      "await page.evaluate(() => ready ? window.cy.nodes() : null);",
    ].join('\n');
    expect(offendingBodies(offending).map(entry => entry.line)).toEqual([1, 2, 3]);
  });

  it('does not pretend to see a core hidden inside an object literal', () => {
    // Declared gap 3. This serialises just as badly and is NOT caught; the test exists so the gap
    // is a recorded fact with a failing example attached, rather than a sentence in a comment that
    // nobody can check.
    const uncaught = "await page.evaluate(() => ({ nodes: window.cy.nodes(), zoom: window.cy.zoom() }));";
    expect(offendingBodies(uncaught)).toEqual([]);
  });

  it('leaves the getters and the block bodies that make up the rest of the suite alone', () => {
    // The other half of the control: a guard that flagged these would be turned off within a week.
    const legitimate = [
      "await page.evaluate(() => { window.cy.fit(undefined, 80); });",
      "const curve = await page.evaluate(() => window.cy.getElementById('edge-1').style('curve-style'));",
      "const ids = await page.evaluate(() => window.cy.nodes().map(node => node.id()).sort());",
      "const zoom = await page.evaluate(() => window.cy.zoom());",
      "const box = await page.evaluate(() => window.cy.container().getBoundingClientRect());",
      "const running = await page.evaluate(() => Boolean(window.cy.scratch('_rrLayoutRunning')));",
      "await page.evaluate(({ positions }) => { Object.entries(positions).forEach(([id, p]) => window.cy.getElementById(id).position(p)); }, { positions });",
    ].join('\n');
    expect(offendingBodies(legitimate)).toEqual([]);
  });

  it('leaves evaluateHandle and the DOM alone — both are correct code', () => {
    // `evaluateHandle` hands back a JSHandle and serialises nothing: 4ms measured, handle usable. It
    // is the right answer when a test genuinely needs the live object, so reddening CI over it would
    // reject correct code. And `remove`/`select` are DOM methods too, where
    // they return `undefined` — the Cytoscape-receiver requirement is what tells them apart.
    const legitimate = [
      "const handle = await page.evaluateHandle(() => window.cy);",
      "const nodes = await page.evaluateHandle(() => window.cy.nodes());",
      "await page.evaluate(() => document.getElementById('edge-ghost').remove());",
      "await page.evaluate(() => document.querySelector('#node-editor input').select());",
      "await page.evaluate(() => document.activeElement.blur());",
    ].join('\n');
    expect(offendingBodies(legitimate)).toEqual([]);
  });
});
