import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source = readFileSync(new URL('./program.js', import.meta.url), 'utf8');
const handler = vm.runInNewContext('(' + source + ')');
const fixtures = JSON.parse(readFileSync(new URL('./fixtures.json', import.meta.url), 'utf8'));
const clone = x => JSON.parse(JSON.stringify(x));
function run(d, input = [], options = {}) {
  return clone(handler({payload: {definition: typeof d === 'string' ? d : JSON.stringify(d), input, ...options}}));
}
const parity = fixtures.find(x => x.name === 'binary parity').definition;
for (const f of fixtures) test(f.name, () => {
  const result = run(f.definition, f.input, {traceMode: 'full'});
  for (const [key, expected] of Object.entries(f.expected)) assert.deepEqual(result[key], expected, key);
  assert.equal(result.trace.entries.length, f.input.length + 1);
  assert.equal(result.trace.complete, true);
});
test('full run agrees with every step prefix including closure, output and work', () => {
  for (const f of fixtures) {
    const full = run(f.definition, f.input, {traceMode: 'full'});
    for (let i = 0; i <= f.input.length; i++) {
      const prefix = run(f.definition, f.input.slice(0, i), {traceMode: 'full'});
      assert.deepEqual(prefix.finalStates, full.trace.entries[i].states);
      assert.equal(prefix.steps, full.trace.entries[i].steps);
      assert.deepEqual(prefix.output, full.trace.entries.slice(0, i + 1).flatMap(s => s.emitted));
    }
  }
});
test('trace mode cannot change the semantic result', () => {
  for (const mode of ['none', 'summary', 'full']) {
    const result = run(parity, ['1', '0', '1'], {traceMode: mode});
    assert.equal(result.accepted, true); assert.equal(result.steps, 6);
    assert.equal(result.trace.entries.length, mode === 'full' ? 4 : 0);
    assert.deepEqual(result.trace.summary, mode === 'none' ? null :
      {maxFrontier: 1, transitionVisits: 3, epsilonVisits: 0, outputTokens: 0});
  }
});
test('canonical round trips and permutations are byte identical', () => {
  for (const f of fixtures) {
    const d = clone(f.definition);
    const first = run(d, [], {operation: 'canonical'});
    assert.equal(run(first, [], {operation: 'canonical'}), first);
    for (const k of ['states', 'alphabet', 'initialStates', 'acceptingStates', 'transitions']) d[k].reverse();
    assert.equal(run(d, [], {operation: 'canonical'}), first);
    assert.deepEqual(run(d, f.input, {traceMode: 'full'}), run(f.definition, f.input, {traceMode: 'full'}));
  }
});
test('canonical scalar-value ordering, exact UTF-8 and no Unicode normalization', () => {
  const d = {schema: parity.schema, kind: 'nfa', states: ['😀', '\ue000', 'é', 'e\u0301'],
    alphabet: ['é', 'e\u0301'], initialStates: ['😀'], acceptingStates: [], transitions: []};
  const c = run(d, [], {operation: 'canonical'});
  assert.deepEqual(JSON.parse(c).states, ['e\u0301', 'é', '\ue000', '😀']);
  assert.equal(Buffer.from(c).toString('utf8'), c);
  assert.equal(c.includes('\\ud83d'), false);
  assert.throws(() => run(d, [], {limits: {definitionBytes: c.length}}), /FA_LIMIT/);
});
const invalid = [
  ['malformed JSON', () => '{', 'FORMAT'],
  ['duplicate object member', () => JSON.stringify(parity).replace('"dfa"', '"dfa","kind":"dfa"'), 'DUPLICATE_KEY'],
  ['unknown version', d => (d.schema = 'v2', d), 'VERSION'],
  ['unknown property', d => (d.code = 'execute()', d), 'UNKNOWN_FIELD'],
  ['unknown initial state', d => (d.initialStates = ['absent'], d), 'UNKNOWN_STATE'],
  ['unknown accepting state', d => (d.acceptingStates = ['absent'], d), 'UNKNOWN_STATE'],
  ['unknown transition state', d => (d.transitions[0].to = 'absent', d), 'UNKNOWN_STATE'],
  ['nondeterminism', d => (d.transitions.push({...d.transitions[0], to: 'odd'}), d), 'NONDETERMINISM'],
  ['duplicate transition', d => (d.transitions.push({...d.transitions[0]}), d), 'DUPLICATE_TRANSITION'],
  ['duplicate state', d => (d.states.push('even'), d), 'DUPLICATE'],
  ['multiple deterministic initial states', d => (d.initialStates = ['even', 'odd'], d), 'INITIAL'],
  ['empty initial set', d => (d.initialStates = [], d), 'INITIAL'],
  ['missing total transition', d => (d.transitions.pop(), d), 'NOT_TOTAL'],
  ['missing transition policy', d => (delete d.transitionPolicy, d), 'POLICY'],
  ['epsilon outside enfa', d => (d.transitions[0].on = null, d), 'SYMBOL'],
  ['transition symbol outside alphabet', d => (d.transitions[0].on = 'x', d), 'SYMBOL'],
  ['empty alphabet token', d => (d.alphabet.push(''), d), 'FORMAT'],
  ['lone Unicode surrogate', d => (d.states.push('\ud800'), d), 'UNICODE'],
  ['nondeterministic output forbidden', d => (d.kind = 'nfa', delete d.transitionPolicy, d.transitions[0].emit = ['x'], d), 'UNKNOWN_FIELD'],
  ['Mealy requires output', d => (d.kind = 'mealy', d), 'MISSING_FIELD'],
  ['Moore requires every state output', d => (d.kind = 'moore', d.stateOutputs = {even: []}, d), 'MISSING_FIELD'],
  ['arbitrary JSON outputs refused', d => (d.kind = 'moore', d.stateOutputs = {even: ['x'], odd: [null]}, d), 'FORMAT']
];
for (const [name, change, code] of invalid) test(name, () => {
  assert.throws(() => run(change(clone(parity))), new RegExp('FA_' + code + ' path='));
});
test('out-of-alphabet and non-array inputs fail, including after a dead frontier', () => {
  const d = clone(parity); d.transitionPolicy = 'partial'; d.transitions = [];
  assert.throws(() => run(d, ['0', 'x']), /FA_SYMBOL path=\/input\/1/);
  assert.throws(() => run(parity, '101'), /FA_INPUT/);
});
test('every evidence resource ceiling can refuse an over-budget request', () => {
  const cases = [
    ['definitionBytes', JSON.stringify(parity).length - 1, parity, []],
    ['depth', 3, parity, []], ['states', 1, parity, []], ['alphabet', 1, parity, []],
    ['transitions', 3, parity, []], ['input', 1, parity, ['0', '0']],
    ['steps', 1, parity, ['0']], ['idBytes', 3, parity, []],
    ['tokenBytes', 1, fixtures.find(x => x.name === 'Unicode tokens').definition, []],
    ['frontier', 1, fixtures.find(x => x.name === 'epsilon cycle').definition, []],
    ['output', 1, fixtures.find(x => x.name === 'Moore initial output').definition, ['a']],
    ['outputBytes', 1, fixtures.find(x => x.name === 'Moore initial output').definition, ['a']],
    ['traceEntries', 1, parity, ['0']], ['traceBytes', 1, parity, []]
  ];
  for (const [key, value, definition, input] of cases)
    assert.throws(() => run(definition, input, {traceMode: 'full', limits: {[key]: value}}),
      new RegExp('FA_LIMIT .*limit=' + key), key);
  assert.equal(run(parity, ['0'], {limits: {steps: 2, input: 1}}).steps, 2);
  assert.equal(run(parity, [], {limits: {definitionBytes: JSON.stringify(parity).length}}).accepted, true);
});
test('caller can tighten but never raise or disable bounded evidence limits', () => {
  for (const limits of [{steps: 10001}, {steps: 0}, {steps: -1}, {steps: 1.5}, {other: 1}])
    assert.throws(() => run(parity, [], {limits}), /FA_CONFIG/);
});
test('generated parity corpus has independent oracle through length eight', () => {
  for (let n = 0; n <= 8; n++) for (let bits = 0; bits < 2 ** n; bits++) {
    const input = Array.from({length: n}, (_, i) => String((bits >> i) & 1));
    assert.equal(run(parity, input).accepted, input.filter(x => x === '1').length % 2 === 0);
  }
});
test('substring NFA agrees with independent string oracle through length eight', () => {
  const d = fixtures.find(x => x.name === 'substring NFA').definition;
  for (let n = 0; n <= 8; n++) for (let bits = 0; bits < 2 ** n; bits++) {
    const input = Array.from({length: n}, (_, i) => ((bits >> i) & 1) ? 'b' : 'a');
    assert.equal(run(d, input).accepted, input.join('').includes('ab'));
  }
});
test('serial adder agrees with integer addition for all four-bit operand pairs', () => {
  const d = fixtures.find(x => x.name === 'serial adder final carry').definition;
  for (let a = 0; a < 16; a++) for (let b = 0; b < 16; b++) {
    const input = Array.from({length: 4}, (_, i) => String((a >> i) & 1) + String((b >> i) & 1));
    input.push('end');
    const result = run(d, input);
    assert.equal(result.accepted, true);
    assert.equal(result.output.reduce((sum, bit, i) => sum + Number(bit) * 2 ** i, 0), a + b);
  }
});

test('one-transition machine remains valid with transitions ceiling one', () => {
  const d = {schema: parity.schema, kind: 'dfa', states: ['q'], alphabet: ['a'],
    initialStates: ['q'], acceptingStates: ['q'], transitionPolicy: 'total',
    transitions: [{from: 'q', on: 'a', to: 'q'}]};
  assert.equal(run(d, ['a'], {limits: {transitions: 1, states: 1, alphabet: 1, frontier: 1,
    input: 1, steps: 2, idBytes: 1, tokenBytes: 1}}).accepted, true);
});

test('explicit invalid trace modes cannot silently select none', () => {
  for (const traceMode of ['', null, false, 'truncated'])
    assert.throws(() => run(parity, [], {traceMode}), /FA_CONFIG path=\/traceMode/);
});
