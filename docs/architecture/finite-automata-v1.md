# Finite automata v1 contract

This is the accepted design for an optional `finite-automaton` behavior, **not an installed
bundle or an available catalog entry**. The executable [evidence](../examples/finite-automata/index.md)
uses the existing `program` mechanism; it does not implement the production node contract.
[ADR 0044](https://github.com/ravenroot-ai/ravenroot/blob/dev/adr/0044-encapsulated-finite-automata.md) records the decisions and deferred work.

## Definition and canonical identity

The definition is declarative data with schema `ravenroot.finite-automaton/v1`. It cannot contain
expressions, code, regular expressions, executable references, imports or network locations.
Unknown properties and unsupported versions fail closed. The complete root fields are:

| Field | Contract |
|---|---|
| `schema` | Exactly `ravenroot.finite-automaton/v1`; required |
| `kind` | Exactly `dfa`, `nfa`, `enfa`, `mealy` or `moore`; required |
| `states` | Nonempty set of opaque, nonempty string identifiers; required |
| `alphabet` | Set of nonempty string input tokens; may be empty; required |
| `initialStates` | Nonempty subset of states; exactly one for DFA, Mealy and Moore; required |
| `acceptingStates` | Subset of states; may be empty; required for every kind |
| `transitions` | Array of transition records; may be empty; required |
| `transitionPolicy` | Required `total` or `partial` for DFA, Mealy and Moore; forbidden otherwise |
| `stateOutputs` | Required object mapping every state ID to an output array for Moore; forbidden otherwise |

A transition contains exactly `from`, `on`, and `to`; Mealy also requires `emit`. Both endpoints
must name states. `on` is one whole alphabet token. JSON `null` is reserved for epsilon and is valid
only for `enfa`. It is distinct from the string `"null"`. Mealy `emit` and Moore state outputs are
ordered arrays of nonempty string tokens, including the empty array for no emission. These output
tokens need not belong to the input alphabet. Numeric, boolean, object and null outputs are not v1.
DFA, NFA and epsilon-NFA cannot carry output fields: ambiguous nondeterministic output is rejected
statically, not resolved by branch order or arbitrary choice.

IDs carry no execution meaning and are not graph node IDs, property paths or class names. Tokens
are exact Unicode scalar sequences, compared case-sensitively without trimming, splitting, locale
collation or Unicode normalization. `"ab"`, `"👩‍💻"`, `"é"` and `"é"` are four distinct tokens; the
last two remain distinct even when rendered alike. Unpaired UTF-16 surrogates and malformed UTF-8
are invalid. ID and token limits measure UTF-8 bytes, separately from Ravenroot payload text limits,
which measure UTF-16 code units.

Runtime configuration accepts strict UTF-8 JSON only: no BOM, comments, trailing commas, duplicate
object keys or non-JSON numbers. Duplicate members of set fields and duplicate transitions are
errors, not silently deduplicated authoring mistakes. Duplicate transitions mean identical complete
records. Two deterministic transitions with the same `(from,on)` are invalid even when destinations
match but outputs differ. NFA/enfa may have different destinations for that pair.

YAML is an **authoring import format**, not a second runtime wire format. The authoring importer must
accept one YAML 1.2 document restricted to JSON-compatible mappings, sequences, quoted strings and
null; reject aliases, anchors, merge keys, explicit tags, duplicate keys and implicit scalar typing;
and apply byte, nesting and collection budgets while parsing. It then runs the same validator and
emits canonical JSON. There is no YAML importer in this milestone. V1 runtime nodes must reject YAML
rather than let a permissive loader choose semantics.

Canonical serialization has no optional defaults to materialize. After validation:

1. Sort `states`, `alphabet`, `initialStates` and `acceptingStates` by Unicode scalar value. For valid
   Unicode this is also lexicographic UTF-8 byte order. Never use UTF-16 code-unit or locale order.
2. Recursively sort object keys by that order. Preserve order within emission arrays.
3. Sort transitions by the same order of their complete canonical JSON strings.
4. Encode compact UTF-8 JSON without BOM, whitespace or final newline. Escape quotation mark and
   backslash; use `\b`, `\t`, `\n`, `\f`, `\r` for their controls and lowercase `\u00xx` for other
   U+0000–U+001F controls. Leave slash and other scalar values unescaped.

No numbers occur in a valid definition, avoiding cross-runtime numeric normalization. SHA-256 of
these exact bytes is the definition digest, lowercase hexadecimal. The schema participates in the
digest; node policy and input do not. Policy identity is pinned separately. Reordering transitions,
sets or object members changes neither canonical identity nor execution. Reordering emitted tokens
does change identity and output. Diagnostics use source JSON Pointers before normalization; digest
and execution use only the normalized model. Canonical parse/serialize is idempotent.

## Execution semantics

Let `F` be a set of active states and `i` the number of consumed tokens. Initialize `i=0`, output to
`[]`, and `F=closure(initialStates)`. Closure is identity except for epsilon-NFA. Moore emits the
initial state's output once at initialization, including for empty input. No machine implicitly
consumes an end marker or flushes output.

For each input token, compute the union of matching transition destinations from `F`, emit the
matching Mealy transition's tokens or the reached Moore state's tokens, take epsilon-closure of the
union, and increment `i`. Iterate source states and transitions in canonical order. Each target is
inserted only once. Mealy and Moore are deterministic, so at most one emitting transition applies.

Epsilon-closure starts with a sorted seed set and a FIFO work queue. Mark each state visited on
insertion, visit its canonical ordered epsilon edges once, and enqueue each previously unseen
destination. Epsilon cycles and self-loops terminate because states are visited, not because a
budget happens to expire. Compute closure before the first token and after **every** token.

| Kind | Required behavior |
|---|---|
| Total DFA | Exactly one transition per state/alphabet token; incomplete definitions cannot activate |
| Partial DFA | At most one transition per pair; a missing transition produces an empty frontier |
| NFA | Union all matching destinations, deduplicate the frontier after every token |
| Epsilon-NFA | NFA behavior plus finite visited-set epsilon-closure at initialization and after each token |
| Mealy | Deterministic total/partial policy; append transition `emit` in order |
| Moore | Deterministic total/partial policy; append initial output then each reached state's output |

Every kind accepts exactly when, at end of input, `F` intersects `acceptingStates`. Transducers
therefore distinguish a successfully accepted word from the output of an incomplete protocol.
Output already emitted is retained on ordinary rejection. There are no nondeterministic
transducers in v1. Empty input accepts precisely when the initial closure intersects the accepting
set; Mealy emits nothing and Moore emits its initial output. An empty alphabet admits only empty
input. A total machine over an empty alphabet is vacuously total.

Input is an array of tokens, never a string to tokenize automatically. Validate the complete bounded
input before execution. A token outside the alphabet is an error even if an earlier transition would
empty the frontier. With a missing partial transition, continue consuming valid remaining input
against the empty frontier. Thus `consumed` is always input length on successful return, including
rejection; no result pretends that an invalid suffix was processed. Exhaustion or cancellation
produces a failure, not a partial success result.

The serial binary adder example is an ordinary Mealy definition. `00`, `01`, `10`, `11` represent
least-significant-bit-first operand pairs; `c0` and `c1` represent carry. An explicit `end` token emits
the carry and enters accepting state `done`. No input follows `end` in an accepted word. `11,11,end`
computes 3+3 with output `0,1,1`. Without `end`, the output prefix remains available but the machine
rejects. V1 has no special binary-adder behavior.

## Work accounting and stepping

`steps` is deterministic work, not elapsed time or Ravenroot node invocations. Charge one unit
before each token advance and one before inspecting each matching transition, including each
epsilon edge encountered by closure. An already visited epsilon destination still costs one edge
inspection, but is never enqueued twice in the same closure. No work unit is charged for absent
edges or acceptance testing. Empty DFA input costs zero; an empty epsilon-NFA run can cost more than
zero; Moore's initial emission costs output budget, not a work unit. Deterministic successful
transitions cost two units per input token. Missing partial transitions cost one.

A stepper initializes exactly once, then applies one token advance and its complete closure at a
time, retaining `(frontier, cursor, output, counters)` privately. Finishing checks acceptance only
after all tokens; intermediate frontiers are not a final accepted/rejected outcome. Initial output
must not be emitted again on resume. A full run is that exact sequence of operations. Prefix
snapshots must match full-run snapshots for state, cumulative output and work. Sessions also retain
the exact definition digest, policy digest and input identity; a mismatched resume fails. This is
an interpreter/authoring stepping contract, **not a new graph checkpoint or payload-resume API**.
The production node executes one complete run per invocation.

## Encapsulated node contract

The future optional package registers one `finite-automaton` behavior, declares `NodeSdk.CONTRACT`,
uses an immutable descriptor and publishes literal `accepted` and `rejected` outcomes. Package
installation remains a deployment decision. No definition can load a package. The initial package
covers all five machine kinds and requires no external I/O services.

| Property | V1 contract |
|---|---|
| `definitionFormat` | Required exact `ravenroot.finite-automaton/v1`, matching definition `schema` |
| `definition` | Required TEXT containing bounded strict JSON, configured with the graph |
| `inputPath` | Required JSON Pointer selecting an input array |
| `resultPath` | Required JSON Pointer selecting the destination; empty pointer replaces the payload |
| `traceMode` | Optional `none` (default), `summary`, or `full` |
| `maxSteps` | Optional positive integer that may only tighten the deployment work ceiling |

Paths use JSON Pointer escaping (`~0`, `~1`), with no URI fragment, wildcard or JSONPath evaluation.
For this v1 property contract, nonempty paths traverse **object members only**; arrays are values,
not path containers. Missing input members, malformed escapes, missing destination parents or a
non-object destination parent fail. An empty input pointer selects the whole incoming payload.
The result leaf is created or replaced in a fresh copy; intermediate objects must already exist.
Read the complete input before insertion, so input/result overlap is deterministic. Preserve other
payload members and message attributes. Applying the result must pass the deployment's complete
payload budgets; the definition is not automatically copied into output. There is no implicit
merge, append or mutation of the caller's object.

Inline configuration is governed by graph validation and activation. **V1 does not support
`definitionRef` or a payload definition path.** Such fields fail configuration validation. A later
immutable data-artifact mode must provide tenant ownership, exact digest/version resolution,
revocation/admission semantics, dependency manifest pinning and recovery rules before activation;
an artifact ID alone cannot be authority. Current `ArtifactRegistry` governs executable programs,
not generic automata data. Until that separate contract exists, authoring may import a document and
embed its validated canonical bytes inline.

A payload-supplied definition mode is also deferred and disabled. Any later laboratory mode needs
explicit deployment opt-in, separate per-request validation/cost budgets and authorization; a graph
property or input flag alone cannot enable it. The evidence program deliberately accepts a definition
as test input under fixed ceilings; this is not permission for the production node to do so.

## Result and traces

Every ordinary result contains exactly these semantic fields (transport envelopes remain separate):

| Field | Meaning |
|---|---|
| `accepted` | Boolean; native outcome is `accepted` iff true, otherwise `rejected` |
| `finalStates` | Canonically sorted distinct states after final closure; may be empty |
| `consumed` | Number of whole input tokens consumed; input length on all returned results |
| `output` | Ordered string tokens; empty for acceptors, retained on transducer rejection |
| `steps` | Cumulative work units defined above |
| `trace` | Trace object below, present even for `none` |

`trace` contains `mode`, `complete`, `entries`, and `summary`. `complete` is always true for a
returned v1 trace. `none` returns `entries: []` and `summary: null`. `summary` returns no entries and
`{maxFrontier, transitionVisits, epsilonVisits, outputTokens}`. `maxFrontier` includes intermediate
closure/frontier insertion sizes; `transitionVisits` includes epsilon visits; `outputTokens` includes
initial Moore output. `full` includes that same summary plus one initialization entry and one entry
per token, including entries for empty frontiers. Initialization has `position: 0` and `on: null`;
later entries have `position: consumed` and `on` equal to the consumed token. Each entry contains
`states` (sorted post-closure frontier), `emitted` (only this step's output), and cumulative `steps`.
Epsilon closure is atomic in the trace; individual epsilon edges are counted but not separate entries.
This bounds trace size and makes initial `null` unambiguous even in an epsilon-NFA.

Trace modes cannot change acceptance, final states, work, consumption or output. Full traces are
bounded. **There is no v1 truncation or externalization mode**: exceeding a trace limit fails visibly
with `FA_LIMIT` and its limit name. A later truncated/external trace requires an explicit versioned
contract carrying completeness, omitted counts or an immutable authorized reference; it cannot
silently reuse `complete: true`. `none` means intentionally unrecorded entries, not lost data.

## Validation, failure and governance

Static validation checks syntax/version/shape, Unicode, sets, endpoints, output typing,
determinism, policy completeness and all configured definition budgets. Structural errors refuse
graph construction/activation. They cannot be repaired by wiring `failure.route`. Unreachable
states and states unable to reach an accepting state may produce bounded authoring warnings;
these are not structural errors. Reachability and productivity follow both consuming and epsilon
edges. The evidence interpreter does not generate these optional warnings.

Operational input, output, work, trace or payload failures produce no `accepted`/`rejected` outcome
and no fabricated automaton result. The future adapter uses typed exceptions: definition/configuration
failures, input failures and limit failures are distinct categories (`FiniteAutomatonDefinitionException`,
`FiniteAutomatonInputException` and `FiniteAutomatonLimitException`, respectively; not yet implemented). Cancellation remains the engine's
cancellation signal rather than ordinary rejection. Diagnostics use a stable `FA_*` code and JSON
Pointer, optionally token position or limit name. They never echo the definition, token value,
source code or stack trace. Required code families are:

| Code | Category and diagnostic location |
|---|---|
| `FA_FORMAT`, `FA_UNICODE`, `FA_VERSION`, `FA_KIND` | Invalid source/type/encoding/schema/kind; source pointer |
| `FA_MISSING_FIELD`, `FA_UNKNOWN_FIELD`, `FA_DUPLICATE_KEY`, `FA_DUPLICATE` | Object/set structural error; containing pointer or missing field |
| `FA_INITIAL`, `FA_UNKNOWN_STATE` | Initial cardinality or unresolved state reference; field/index pointer |
| `FA_POLICY`, `FA_NOT_TOTAL`, `FA_NONDETERMINISM`, `FA_DUPLICATE_TRANSITION` | Invalid transition contract; policy or transition pointer |
| `FA_OUTPUT` | Invalid output declaration; emission/state-output pointer |
| `FA_CONFIG` | Invalid mode, pointer or budget override; property pointer |
| `FA_INPUT`, `FA_SYMBOL` | Invalid runtime input or out-of-alphabet token; `/input/<index>` when applicable; a definition transition symbol is static |
| `FA_LIMIT` | Resource ceiling exceeded; location and `limit=<name>`; static for definition budgets, operational for execution budgets |

If several defects exist, implementations may report the first encountered in source order; codes
and source pointers must be precise, but diagnostic ordering is not canonical identity. This is a
future extension diagnostic contract. The prototype throws ordinary JavaScript errors containing
these codes; it does not claim Java exception types or new runtime error-envelope members.

Current `GraphRunner` handles a failed attempt through retry policy and then the selected
`failure.route`; absence of a route retains normal failed-traversal behavior. `NodeFailurePayload`
contains exactly `nodeId`, `errorClass`, `message`, and `input`, unwrapping the deepest cause. The
future adapter must preserve the stable diagnostic in that deepest exception and make deterministic
input/configuration/limit exceptions implement the existing `RetryClassified` contract with
`Retryability.DETERMINISTIC_REJECT`. Current `RetryClassifier` honors that explicit classification
before an author-supplied retry allowlist. It must not
invent a `failure` outcome. Current messages are bounded, not scrubbed; the adapter is responsible
for safe diagnostic text. Existing routing may carry the original input, so trace/data confidentiality
continues to depend on the deployment's payload and access policies.

All ceilings are deployment-owned finite positive integers, with supported hard maxima. A node may
only lower `maxSteps`. The effective policy must be recorded in `operationalPolicyDigest()` so a
resumed execution cannot silently change limits. No definition can raise budgets. Cancellation and
host deadlines additionally bound wall time; deterministic work accounting is not a scheduler.

| Budget | Enforcement point | Evidence profile ceiling |
|---|---|---:|
| Definition UTF-8 bytes | Before parsing and on canonical output | 32,768 |
| Definition depth (root = 1) | Before descending/allocating parser containers | 8 |
| States / alphabet / transitions | Before collection/index insertion | 64 / 64 / 1,024 |
| ID / token UTF-8 bytes | Before storing identifiers/tokens | 64 / 128 |
| Input tokens | Before execution | 128 |
| Work units | Before token advance/transition inspection | 10,000 |
| Active frontier | Before inserting a new distinct state, including closure | 64 |
| Output tokens / token UTF-8 bytes in aggregate | Before append, including Moore initialization | 512 / 8,192 |
| Full-trace entries / encoded array bytes | Before append, including initialization, brackets and commas | 129 / 32,768 |

The table's numeric values are the **reproducible evidence profile**, not shipped runtime defaults.
Production may choose other bounded profiles; it must publish them and intersect them with graph,
payload, sandbox and execution limits. The output-byte counter measures emitted string bytes;
encoded JSON overhead is separately covered by payload/transport ceilings. Similarly trace bytes
measure the canonical `entries` array, while the complete result remains under payload limits.
Ravenroot currently defaults to 256 KiB encoded payload, depth 32, 1,000 entries per collection,
10,000 total values, 32 KiB UTF-16 text length and 256 UTF-16 key length. The combined result can
exceed those even when individual automata ceilings pass, and then it must fail as a payload error.
An output cap does not override those existing limits.

Production parsers must reject byte/depth/collection breaches while reading, before building an
unbounded object tree. Bound indexes, visited sets, strings and trace entries before allocating or
appending them. The evidence parser checks definition bytes before parsing, depth before descent,
collection growth before insertion, and executor growth before append. Its sandbox request has
already been materialized by the host, and an individual trace entry is built from bounded state
before its encoded byte check. It is bounded evidence, not proof of a production streaming parser
or process isolation. Node's standalone harness is not a sandbox.

## Integration and algorithm boundaries

These assumptions were rechecked against source at `846def1f9df0e78c106427017006a7331777fac1`:

- `NodePackage` supplies `id`, `version`, `sdkContract`, behaviors and optional operational policy
  digest. Trust comes from registration/operator configuration, never graph-selected classes.
- `NodeBehavior.descriptor()` is stable and graph validation uses its property schema. `create()`
  parses configuration and may refuse the graph; returned actions are shared and re-entrant.
  Declare every property above, validate the full definition in `create`, retain immutable indexes,
  and allocate frontier/output/counters per invocation. Use WORKER runtime nature for bounded CPU
  execution and cooperate with cancellation; do not block traversal control threads.
- `NodeOutcomeDescriptor` expresses one literal or one property-derived outcome. It has no general
  collection-of-alphabet-outcomes schema. Two fixed outcomes fit today; `automaton-state` is deferred.
- `ProgramNodeBehaviorFactory` declares and always returns `continue` on success, replacing the
  payload with the program result. It resolves tenant-owned ACTIVE content by language/source digest,
  checks compatibility and uses revocable execution admission. `artifactId` is a read-only audit
  reference, not content or lifecycle authority. Program validation checks callable source; it does
  not statically validate a machine later supplied in a request.
- A following `cel-decision` with `expression=payload.accepted`, `trueOutcome=accepted`, and
  `falseOutcome=rejected` preserves the result and routes it. Sandbox failures remain failed attempts.
- `ArtifactRegistry` and its admission/lifecycle methods govern generated code. Reusing its name for
  arbitrary JSON would falsely imply data lifecycle guarantees it does not establish.
- `PayloadLimits`, `PayloadValue` and `NodeFailurePayload` remain the payload/failure boundary; the
  proposed extension does not add core transport types.

Separate four responsibilities: bounded parser produces a source tree with locations; validator
checks the v1 invariants; canonical model owns normalized immutable definition/indexes and digest;
executor owns per-invocation state and deterministic stepping. An authoring application may use all
four, but runtime does not determinize, minimize, compile code, draw graphs or resolve arbitrary URLs.

With indexed transitions, DFA/Mealy/Moore execution is O(L + output size), plus bounded trace
serialization. NFA/enfa execution is O(L + sum of matching and epsilon edge visits + emitted/trace
size), with worst case O((L+1)(S+T)) for S states and T transitions. State sorting for reproducible
snapshots adds O((L+1) S log S) in the evidence implementation; production can use canonical integer
indices for ordered emission. Index construction costs O(S+T) storage and canonical sorting costs
O(T log T + S log S), with bounded string comparisons. Closure/frontier memory is O(S); retained
output/trace memory is capped separately. Subset construction's exponential cost is not hidden in
these runtime bounds.

Ordinary graph fan-out is not NFA execution: independent traversals neither deduplicate each
position's states nor provide one existential acceptance/completion decision or epsilon closure.
A rejected branch must not terminate another accepting branch, and epsilon cycles must not spawn
infinite graph work. Encapsulating the frontier and closure inside one action supplies those
semantics without changing the traversal engine, graph joins or completion protocol.

## Authoring and later stages

The first visual representation is one `finite-automaton` node. Authoring owns the editable machine
document and simulation view, with a nested inspection view attached to that node. Export embeds
canonical JSON in its `definition` GraphML property with XML escaping, declares the exact format,
paths and trace mode, and connects fixed `accepted`/`rejected` edges plus ordinary failure routing.
`START` and destination/terminal nodes are graph structure; machine states remain internal. Import
must recover identical canonical definition bytes and settings. This export is specified here but
not implemented or advertised as runnable before the bundle exists.

Optional DFA/Mealy/Moore expansion is a later authoring compiler mode, not another v1 runtime mode.
It needs generated graph IDs distinct from opaque state IDs, an explicit symbol-to-outcome mapping
(no raw-token collisions with terminal outcomes), separate control state for token cursor/output,
and one initial Moore emission. Preserve source digest, compiler version, source-state mapping,
symbol mapping, acceptance/output policy and policy ceilings as versioned provenance. Store that
provenance in a supported authoring representation; do not claim arbitrary metadata is already
preserved by today's GraphML import/export.

NFA/enfa expansion requires bounded subset construction in authoring: epsilon-close the initial
subset and each post-token subset, canonicalize subset identities, preserve acceptance by existential
intersection, and stop **before insertion** when generated states, transitions, work, bytes or time
would exceed an operator/authoring limit. Worst-case state count is 2^S. Report refusal and keep the
original machine executable in encapsulated form; never export a partial graph as equivalent. Each
generated state records the original subset and source digest. No subset construction is implemented
here. Minimization is also authoring-only, after validated deterministic construction; transducer
minimization must preserve ordered outputs and the initial-output convention, not just accepted
language. It is deferred and must not be substituted with acceptor minimization.

`automaton-state` remains deferred until a general Node SDK/catalog contract can describe bounded
alphabet-derived outcomes consistently for runtime validation and the editor. Empty outcome metadata
or generated special-purpose behaviors are not a substitute for that contract. This milestone does
not implement an editor, exporter, dynamic outcomes, determinization, minimization or production bundle.

Implementation sequence and gates:

1. Complete the architecture evidence (this milestone). Freeze v1 format/semantics and integration
   assumptions with accepted ADR and reproducible tests.
2. Build the optional universal package with production bounded parser/validator, immutable model,
   executor, static activation diagnostics, typed failures, cancellation, payload insertion and Node
   SDK conformance tests. Ship all five kinds together with inline definitions and fixed outcomes.
3. Add authoring import, normalization, simulator and single-node export. Separately define the
   governed data-artifact and optional payload-definition contracts if needed; neither is assumed.
4. Add bounded determinization and applicable minimization in authoring with provenance. Then
   establish the general SDK dynamic-outcome contract before shipping deterministic expanded mode.

The later authoring test gate must compare each NFA/enfa with its generated DFA over all words up to
a bounded length and a seeded generated corpus (including empty words, epsilon cycles, dead subsets,
multiple initial/accepting states and Unicode tokens), checking acceptance and limit refusals against
an independent interpreter. Record seed, corpus bounds, source/destination digests and compiler
version. Separately round-trip expanded GraphML for DFA/Mealy/Moore: recover source/provenance,
compare canonical identities and accepted language/output on the corpus, verify cursor/terminal
routing, initial Moore output, final carry, encoded outcome collisions and failure routes. Reject
missing/changed provenance rather than infer a machine from arbitrary graph topology. These are
**future test requirements**, not results of the current proof of concept.
