function (request) {
  "use strict";
  // Bounded architecture evidence, not an installed behavior or production parser.
  const ceilings = {definitionBytes: 32768, depth: 8, states: 64, alphabet: 64,
    transitions: 1024, input: 128, steps: 10000, frontier: 64, output: 512,
    outputBytes: 8192, traceEntries: 129, traceBytes: 32768, idBytes: 64, tokenBytes: 128};
  const p = request.payload;
  function fail(code, path, detail = "") { throw new Error("FA_" + code + " path=" + path + (detail ? " " + detail : "")); }
  const limits = Object.assign({}, ceilings);
  for (const key of Object.keys(p.limits || {})) {
    const n = p.limits[key];
    if (!Object.hasOwn(ceilings, key) || !Number.isSafeInteger(n) || n < 1 || n > ceilings[key])
      fail("CONFIG", "/limits");
    limits[key] = n;
  }
  function bound(key, n, path) { if (n > limits[key]) fail("LIMIT", path, "limit=" + key); }
  function bytes(s, path) {
    let n = 0;
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff) {
        const d = s.charCodeAt(++i);
        if (!(d >= 0xdc00 && d <= 0xdfff)) fail("UNICODE", path);
        n += 4;
      } else if (c >= 0xdc00 && c <= 0xdfff) fail("UNICODE", path);
      else n += c < 128 ? 1 : c < 2048 ? 2 : 3;
    }
    return n;
  }
  function compare(a, b) {
    const x = Array.from(a, c => c.codePointAt(0)), y = Array.from(b, c => c.codePointAt(0));
    for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i];
    return x.length - y.length;
  }
  function canonical(value) {
    if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
    if (value !== null && typeof value === "object") return "{" + Object.keys(value).sort(compare)
      .map(k => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
    return JSON.stringify(value);
  }
  // This format contains only strings, null, arrays and objects. Reject other JSON types.
  function parse(text) {
    if (typeof text !== "string") fail("FORMAT", "/definition");
    bound("definitionBytes", text.length, "/definition");
    bound("definitionBytes", bytes(text, "/definition"), "/definition");
    let at = 0;
    function ws() { while (/[\x20\t\r\n]/.test(text[at] || "x")) at++; }
    function string(path) {
      const begin = at++;
      while (at < text.length) {
        const ch = text[at++];
        if (ch === "\\") { at++; continue; }
        if (ch === '"') {
          let value;
          try { value = JSON.parse(text.slice(begin, at)); } catch (_) { fail("FORMAT", path); }
          bytes(value, path);
          return value;
        }
      }
      fail("FORMAT", path);
    }
    function value(depth, path) {
      bound("depth", depth, path); ws();
      if (text[at] === '"') return string(path);
      if (text.slice(at, at + 4) === "null") { at += 4; return null; }
      const object = text[at] === "{", array = text[at] === "[";
      if (!object && !array) fail("FORMAT", path);
      at++; ws();
      const out = object ? Object.create(null) : [];
      const end = object ? "}" : "]";
      if (text[at] === end) { at++; return out; }
      let count = 0;
      const field = path.slice(1);
      const cap = ["states", "alphabet", "transitions"].includes(field) ? field : null;
      while (at < text.length) {
        count++;
        // Other containers are bounded by source bytes/depth, not transition cardinality.
        if (cap) bound(cap, count, path);
        ws();
        let key = String(count - 1);
        if (object) {
          if (text[at] !== '"') fail("FORMAT", path);
          key = string(path); ws();
          if (Object.hasOwn(out, key)) fail("DUPLICATE_KEY", path);
          if (text[at++] !== ":") fail("FORMAT", path);
        }
        const child = value(depth + 1, path + "/" + key.replace(/~/g, "~0").replace(/\//g, "~1"));
        if (object) out[key] = child; else out.push(child);
        ws();
        if (text[at] === end) { at++; return out; }
        if (text[at++] !== ",") fail("FORMAT", path);
      }
      fail("FORMAT", path);
    }
    const result = value(1, ""); ws();
    if (at !== text.length) fail("FORMAT", "/definition");
    return result;
  }
  function object(v, required, optional, path) {
    if (!v || typeof v !== "object" || Array.isArray(v)) fail("FORMAT", path);
    for (const k of required) if (!Object.hasOwn(v, k)) fail("MISSING_FIELD", path + "/" + k);
    for (const k of Object.keys(v)) if (!required.includes(k) && !optional.includes(k)) fail("UNKNOWN_FIELD", path);
  }
  function token(v, path, key = "tokenBytes") {
    if (typeof v !== "string" || !v.length) fail("FORMAT", path);
    bound(key, bytes(v, path), path);
    return v;
  }
  function set(v, path, key, ids = false) {
    if (!Array.isArray(v)) fail("FORMAT", path);
    bound(key, v.length, path);
    const seen = new Set();
    for (let i = 0; i < v.length; i++) {
      token(v[i], path + "/" + i, ids ? "idBytes" : "tokenBytes");
      if (seen.has(v[i])) fail("DUPLICATE", path + "/" + i);
      seen.add(v[i]);
    }
    return Array.from(seen).sort(compare);
  }
  function emission(v, path) {
    if (!Array.isArray(v)) fail("OUTPUT", path);
    bound("output", v.length, path);
    let n = 0;
    for (let i = 0; i < v.length; i++) {
      token(v[i], path + "/" + i); n += bytes(v[i], path);
      bound("outputBytes", n, path);
    }
    return v;
  }
  const d = parse(p.definition);
  object(d, ["schema", "kind", "states", "alphabet", "initialStates", "acceptingStates", "transitions"],
    ["transitionPolicy", "stateOutputs"], "");
  if (d.schema !== "ravenroot.finite-automaton/v1") fail("VERSION", "/schema");
  if (!["dfa", "nfa", "enfa", "mealy", "moore"].includes(d.kind)) fail("KIND", "/kind");
  const deterministic = ["dfa", "mealy", "moore"].includes(d.kind);
  if (deterministic ? !["total", "partial"].includes(d.transitionPolicy) : Object.hasOwn(d, "transitionPolicy"))
    fail("POLICY", "/transitionPolicy");
  d.states = set(d.states, "/states", "states", true);
  d.alphabet = set(d.alphabet, "/alphabet", "alphabet");
  d.initialStates = set(d.initialStates, "/initialStates", "states", true);
  d.acceptingStates = set(d.acceptingStates, "/acceptingStates", "states", true);
  if (!d.states.length || !d.initialStates.length || (deterministic && d.initialStates.length !== 1))
    fail("INITIAL", "/initialStates");
  const states = new Set(d.states), alphabet = new Set(d.alphabet);
  function state(v, path) { if (!states.has(v)) fail("UNKNOWN_STATE", path); }
  for (const name of ["initialStates", "acceptingStates"]) d[name].forEach((v, i) => state(v, "/" + name + "/" + i));
  if (d.kind === "moore") {
    object(d.stateOutputs, d.states, [], "/stateOutputs");
    d.states.forEach(v => emission(d.stateOutputs[v], "/stateOutputs"));
  } else if (Object.hasOwn(d, "stateOutputs")) fail("OUTPUT", "/stateOutputs");
  if (!Array.isArray(d.transitions)) fail("FORMAT", "/transitions");
  bound("transitions", d.transitions.length, "/transitions");
  const duplicates = new Set(), index = new Map(d.states.map(s => [s, new Map()]));
  d.transitions.forEach((t, i) => {
    const path = "/transitions/" + i;
    object(t, d.kind === "mealy" ? ["from", "on", "to", "emit"] : ["from", "on", "to"], [], path);
    state(t.from, path + "/from"); state(t.to, path + "/to");
    if (t.on === null ? d.kind !== "enfa" : !alphabet.has(t.on)) fail("SYMBOL", path + "/on");
    if (d.kind === "mealy") emission(t.emit, path + "/emit");
    const key = canonical(t);
    if (duplicates.has(key)) fail("DUPLICATE_TRANSITION", path);
    duplicates.add(key);
    const outgoing = index.get(t.from), matches = outgoing.get(t.on) || [];
    if (deterministic && matches.length) fail("NONDETERMINISM", path);
    matches.push(t); outgoing.set(t.on, matches);
  });
  if (deterministic && d.transitionPolicy === "total") for (const s of d.states)
    for (const a of d.alphabet) if (!index.get(s).has(a)) fail("NOT_TOTAL", "/transitions");
  d.transitions.sort((a, b) => compare(canonical(a), canonical(b)));
  for (const outgoing of index.values()) for (const matches of outgoing.values())
    matches.sort((a, b) => compare(canonical(a), canonical(b)));
  const serialized = canonical(d);
  bound("definitionBytes", bytes(serialized, "/definition"), "/definition");
  if (p.operation === "canonical") return serialized;
  if (p.operation !== undefined && p.operation !== "run") fail("CONFIG", "/operation");
  if (!Array.isArray(p.input)) fail("INPUT", "/input");
  bound("input", p.input.length, "/input");
  // Validate every token before execution, including tokens after a dead frontier.
  p.input.forEach((v, i) => { token(v, "/input/" + i); if (!alphabet.has(v)) fail("SYMBOL", "/input/" + i); });
  const mode = p.traceMode === undefined ? "none" : p.traceMode;
  if (!["none", "summary", "full"].includes(mode)) fail("CONFIG", "/traceMode");
  let steps = 0, consumed = 0, visits = 0, epsilonVisits = 0, maxFrontier = 0, outputBytes = 0, traceBytes = 2;
  const output = [], entries = [];
  function work(epsilon) {
    bound("steps", steps + 1, "/execution"); steps++;
    if (epsilon !== undefined) { visits++; if (epsilon) epsilonVisits++; }
  }
  function add(frontier, s) {
    if (!frontier.has(s)) { bound("frontier", frontier.size + 1, "/execution"); frontier.add(s); }
    maxFrontier = Math.max(maxFrontier, frontier.size);
  }
  function closure(seed) {
    const found = new Set();
    for (const s of seed) add(found, s);
    const queue = Array.from(found).sort(compare);
    for (let i = 0; i < queue.length; i++) for (const t of index.get(queue[i]).get(null) || []) {
      work(true);
      if (!found.has(t.to)) { add(found, t.to); queue.push(t.to); }
    }
    return found;
  }
  function emit(tokens) {
    for (const t of tokens) {
      bound("output", output.length + 1, "/execution");
      const n = bytes(t, "/execution"); bound("outputBytes", outputBytes + n, "/execution");
      output.push(t); outputBytes += n;
    }
  }
  function snapshot(frontier, on, emittedAt) {
    if (mode !== "full") return;
    bound("traceEntries", entries.length + 1, "/trace");
    const entry = {position: consumed, on, states: Array.from(frontier).sort(compare),
      emitted: output.slice(emittedAt), steps};
    const n = bytes(canonical(entry), "/trace") + (entries.length ? 1 : 0);
    bound("traceBytes", traceBytes + n, "/trace");
    entries.push(entry); traceBytes += n;
  }
  let frontier = closure(d.initialStates);
  if (d.kind === "moore") emit(d.stateOutputs[d.initialStates[0]]);
  snapshot(frontier, null, 0);
  // Each advance has private state; a full run is the same ordered sequence of advances.
  function advance(on) {
    work(); const next = new Set(), emittedAt = output.length;
    for (const s of Array.from(frontier).sort(compare)) for (const t of index.get(s).get(on) || []) {
      work(false); add(next, t.to);
      if (d.kind === "mealy") emit(t.emit);
      if (d.kind === "moore") emit(d.stateOutputs[t.to]);
    }
    frontier = closure(next); consumed++;
    snapshot(frontier, on, emittedAt);
  }
  p.input.forEach(advance);
  const finalStates = Array.from(frontier).sort(compare);
  return {accepted: finalStates.some(s => d.acceptingStates.includes(s)), finalStates, consumed, output, steps,
    trace: {mode, complete: true, entries, summary: mode === "none" ? null :
      {maxFrontier, transitionVisits: visits, epsilonVisits, outputTokens: output.length}}};
}
