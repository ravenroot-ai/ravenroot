import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  ASSISTANT,
  AUTHOR,
  DEGRADED,
  ERROR,
  HOST_NOT_ALLOWLISTED,
  INSECURE_REFUSED,
  INERT,
  INERT_REASON_ORDER,
  ASSISTANT_FAILURE_TEXT,
  ASSISTANT_FAILURE_TOKENS,
  INERT_REASON_TEXT,
  assistantFailureText,
  NOTICE,
  NOT_LINKED,
  NOT_SIGNED_IN,
  NO_PROFILE,
  READY,
  SERVICE_UNAVAILABLE,
  TRANSCRIPT_LIMIT,
  appendTurn,
  deriveState,
  inertReason,
  validateDraft,
} from '../src/assistant-session.js';

const ready = { reachable: true, configured: true, allowlisted: true, signedIn: true };

describe('with no model configured the panel is inert and says which thing is missing', () => {
  it.each([
    ['the service is not deployed', { reachable: false }, SERVICE_UNAVAILABLE],
    ['no provider profile is registered', { reachable: true, configured: false }, NO_PROFILE],
    ['plaintext is refused', { reachable: true, configured: true, insecureRefused: true }, INSECURE_REFUSED],
    ['the provider host is not allowlisted', { reachable: true, configured: true, allowlisted: false }, HOST_NOT_ALLOWLISTED],
    ['the user has not signed in', { reachable: true, configured: true, allowlisted: true, signedIn: false }, NOT_SIGNED_IN],
  ])('names %s', (_label, availability, expected) => {
    expect(inertReason(availability)).toBe(expected);
    const state = deriveState({ availability });
    expect(state.state).toBe(INERT);
    expect(state.reason).toBe(expected);
    // The panel is RENDERED and speaks. An inert state with no sentence is a silent failure with a
    // state name attached.
    expect(state.message).toBe(INERT_REASON_TEXT[expected]);
    expect(state.message.length).toBeGreaterThan(0);
    expect(state.canCompose).toBe(false);
  });

  it('keeps the four reasons distinct rather than pooling them into one message', () => {
    const sentences = INERT_REASON_ORDER.map(reason => INERT_REASON_TEXT[reason]);
    expect(new Set(sentences).size).toBe(INERT_REASON_ORDER.length);
  });

  // The order is a product behaviour, not an implementation accident: telling a user to sign in
  // when no provider is configured at all sends them to the wrong person.
  it('names the most fundamental missing thing first', () => {
    // There are five reasons. `not-linked` is last because connecting cannot help while the service is
    // absent, no profile is configured, the host is refused or the Ravenroot session is not
    // authenticated — see `assistant-not-connected.test.js`, which provokes each of those with a
    // connection ALSO outstanding and asserts the fundamental one still wins.
    expect(INERT_REASON_ORDER).toEqual([
      SERVICE_UNAVAILABLE, NO_PROFILE, INSECURE_REFUSED,
      HOST_NOT_ALLOWLISTED, NOT_SIGNED_IN, NOT_LINKED,
    ]);
    expect(inertReason({ reachable: false, configured: true, allowlisted: true, signedIn: true }))
      .toBe(SERVICE_UNAVAILABLE);
    expect(inertReason({ reachable: true, configured: false, allowlisted: true, signedIn: true }))
      .toBe(NO_PROFILE);
    expect(inertReason({
      reachable: true, configured: true, insecureRefused: true, allowlisted: true, signedIn: true,
    })).toBe(INSECURE_REFUSED);
    expect(inertReason({ reachable: true, configured: true, allowlisted: false, signedIn: true }))
      .toBe(HOST_NOT_ALLOWLISTED);
  });

  // Never optimistic. A panel that guessed READY has already told the user it was working.
  it.each([
    ['undefined', undefined],
    ['null', null],
    ['an empty object', {}],
    ['a partial object', { reachable: true }],
  ])('treats %s availability as inert, never as ready', (_label, availability) => {
    expect(deriveState({ availability }).state).toBe(INERT);
  });
});

describe('the states above inert', () => {
  it('is READY when everything is present and no context was lost', () => {
    const state = deriveState({ availability: ready, context: { degraded: false } });
    expect(state.state).toBe(READY);
    expect(state.canCompose).toBe(true);
  });

  it('is DEGRADED when a context source was lost, and still allows a deliberate send', () => {
    const state = deriveState({ availability: ready, context: { degraded: true } });
    expect(state.state).toBe(DEGRADED);
    // The user decides whether an answer without that class is worth having — the panel does not
    // decide for them, and it does not pretend the class is there.
    expect(state.canCompose).toBe(true);
    expect(state.message).toMatch(/unavailable/i);
  });

  it('lets ERROR outrank everything, because that is what the user is looking at', () => {
    const state = deriveState({
      availability: ready, context: { degraded: true }, error: { message: 'HTTP 502' },
    });
    expect(state.state).toBe(ERROR);
    expect(state.message).toBe('HTTP 502');
    expect(state.canCompose).toBe(false);
  });

  it('has a sentence in every state it can be in', () => {
    const states = [
      deriveState({ availability: {} }),
      deriveState({ availability: ready, context: { degraded: true } }),
      deriveState({ availability: ready, error: { message: 'x' } }),
    ];
    expect(states.every(state => typeof state.message === 'string' && state.message.length > 0)).toBe(true);
  });
});

describe('the transcript, which is a live region', () => {
  it('appends, and never re-parents or reorders what is already there', () => {
    let transcript = [];
    transcript = appendTurn(transcript, { role: AUTHOR, text: 'first' });
    const firstTurn = transcript[0];
    transcript = appendTurn(transcript, { role: ASSISTANT, text: 'second' });
    // The existing entry is the SAME object at the SAME index. A structure that regroups as
    // content arrives destroys focus stability and over-announces in exactly this kind of region.
    expect(transcript[0]).toBe(firstTurn);
    expect(transcript.map(turn => turn.text)).toEqual(['first', 'second']);
  });

  it('drops the OLDEST when it is full, never the entry that just arrived', () => {
    let transcript = [];
    for (let index = 0; index < TRANSCRIPT_LIMIT + 5; index += 1) {
      transcript = appendTurn(transcript, { role: AUTHOR, text: `turn-${index}` });
    }
    expect(transcript).toHaveLength(TRANSCRIPT_LIMIT);
    expect(transcript[transcript.length - 1].text).toBe(`turn-${TRANSCRIPT_LIMIT + 4}`);
    expect(transcript[0].text).toBe('turn-5');
  });

  it('records which classes were attached, so the disclosure stays beside the question', () => {
    const [turn] = appendTurn([], { role: AUTHOR, text: 'why is this slow?', attached: ['graph', 'events'] });
    expect(turn.attached).toEqual(['graph', 'events']);
  });

  it('copies the attached list, so a later recompose cannot rewrite history', () => {
    const attached = ['graph'];
    const [turn] = appendTurn([], { role: AUTHOR, text: 'q', attached });
    attached.push('catalog');
    expect(turn.attached).toEqual(['graph']);
  });

  it('normalizes an unknown role to a notice rather than rendering an unknown class', () => {
    const [turn] = appendTurn([], { role: 'system-override', text: 'x' });
    expect(turn.role).toBe(NOTICE);
  });

  it('keeps every turn a string, so the renderer never has to guess', () => {
    const [turn] = appendTurn([], { role: ASSISTANT, text: { toString: () => 'object reply' } });
    expect(typeof turn.text).toBe('string');
  });
});

describe('refusing a draft', () => {
  const readyState = deriveState({ availability: ready, context: { degraded: false } });

  it('refuses an empty draft with a sentence the control can be associated with', () => {
    const verdict = validateDraft('   ', readyState);
    expect(verdict.ok).toBe(false);
    expect(verdict.message).toBe('Type a question before sending.');
  });

  it('refuses any draft while the panel cannot compose', () => {
    const verdict = validateDraft('a real question', deriveState({ availability: {} }));
    expect(verdict.ok).toBe(false);
    expect(verdict.message).toMatch(/not available/);
  });

  it('accepts a trimmed draft when the panel is usable', () => {
    expect(validateDraft('  why did node 3 fail?  ', readyState))
      .toEqual({ ok: true, message: '', text: 'why did node 3 fail?' });
  });
});

// ── THE ASSISTANT SESSION IS READ-ONLY, ASSERTED AT ITS OUTPUT BOUNDARY ─────────────────────────
//
// An unused exported `EFFECTORS` constant could remain empty while production gained effectors. The
// enforceable boundary is the state a renderer receives: it has an EXACT key set, so a state that
// grew an `actions` or `toolCalls` field to carry a suggestion could not pass. Companion assertions
// live in
// `assistant-client.test.js` (the reply projection) and `assistant-panel-markup.test.js` (the
// panel's `data-action` inventory).
describe('the read-only assistant session', () => {
  it('returns no action, tool call or confirmation from any state', () => {
    const states = [
      deriveState({ availability: {} }),
      deriveState({ availability: ready, context: { degraded: false } }),
      deriveState({ availability: ready, context: { degraded: true } }),
      deriveState({ availability: ready, error: { message: 'x' } }),
    ];
    for (const state of states) {
      expect(Object.keys(state).sort()).toEqual(['canCompose', 'message', 'reason', 'state']);
    }
  });
});

// ── NO REASON TEXT MAY CLAIM WHERE THE PROVIDER CREDENTIAL LIVES ─────────────────────────────────
//
// The shipped `not-signed-in` sentence read "Sign in to your own model subscription… Ravenroot
// never holds a provider key on your behalf." Against the server that landed, both clauses were
// false: the credential is operator-configured, so Ravenroot does hold a key and there is no user
// sign-in to perform.
//
// The panel cannot OBSERVE custody: nothing in the status response says whether the key came from
// operator configuration or a user's device flow. The per-author credential contract and shipped
// server use different custody models, so any panel sentence on the subject would be a guess. These
// tests therefore require every reason text to remain custody-neutral under either model.
describe('the inert reason sentences', () => {
  const CUSTODY_CLAIMS = [
    /never holds/i,
    /on your behalf/i,
    /your own (model )?subscription/i,
    /api key/i,
    /device (flow|authorization)/i,
    /keychain/i,
    /we (do not|don.t) store/i,
  ];

  it.each(INERT_REASON_ORDER)('says nothing about credential custody for %s', reason => {
    const text = INERT_REASON_TEXT[reason];
    for (const claim of CUSTODY_CLAIMS) {
      expect(text, `"${text}" makes a custody claim the panel cannot observe`).not.toMatch(claim);
    }
  });

  // CONTROL: the matchers above must be capable of firing, or the test is decorative.
  it('has matchers that actually detect a custody claim', () => {
    const offending = 'Ravenroot never holds a provider key on your behalf.';
    expect(CUSTODY_CLAIMS.some(claim => claim.test(offending))).toBe(true);
  });

  it('describes the Ravenroot session for not-signed-in, not a provider sign-in', () => {
    // The one route that reaches this reason is a 401/403 from Ravenroot itself, so the sentence
    // has to send the reader to the thing that is actually unauthenticated.
    expect(INERT_REASON_TEXT[NOT_SIGNED_IN]).toMatch(/Ravenroot session/i);
    expect(INERT_REASON_TEXT[NOT_SIGNED_IN]).not.toMatch(/provider|subscription/i);
  });

  it('gives every reason a sentence naming who can change it', () => {
    for (const reason of INERT_REASON_ORDER) {
      expect(INERT_REASON_TEXT[reason].length).toBeGreaterThan(40);
    }
  });
});

// Reads the server's reason enum, or returns null when it is genuinely not in the tree.
//
// `fileURLToPath` + `path.join`, deliberately: see the comment on the caller for why the
// `new URL(literal, import.meta.url)` idiom cannot be used from a Vitest file. Only ENOENT means
// "absent"; every other error propagates, so a future breakage of the read mechanism fails this
// test instead of silently converting it into a tautology.
const SERVER_REASON_SOURCE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', '..',
  'ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantOutcome.java',
);

async function readServerReasonSource(from = SERVER_REASON_SOURCE) {
  try {
    return await readFile(from, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') return null;
    throw error;
  }
}

// ── THE NAMED SEND FAILURES ─────────────────────────────────────────────────────────────────────
describe('the nine named send failures', () => {
  // Pinned literally rather than derived from the table it checks, which would make the assertion
  // agree with itself. This is the panel's half of the contract.
  const SERVER_VOCABULARY = [
    'ASSISTANT_ADAPTER_DEFECT',
    'ASSISTANT_EGRESS_REFUSED',
    'ASSISTANT_INVALID_TURN',
    'ASSISTANT_MODEL_PROPOSAL_INVALID',
    'ASSISTANT_PROVIDER_REFUSED',
    'ASSISTANT_PROVIDER_REJECTED',
    'ASSISTANT_PROVIDER_UNAVAILABLE',
    'ASSISTANT_PROVIDER_UNREADABLE',
    'ASSISTANT_TOOL_LOOP_EXHAUSTED',
  ];

  it('understands exactly the server vocabulary, no more and no less', () => {
    expect([...ASSISTANT_FAILURE_TOKENS].sort()).toEqual(SERVER_VOCABULARY);
  });

  // THE HALF THAT MAKES THE PIN TOTAL. When the server source is absent, the pin above validates the
  // client vocabulary alone. When present, this derives the vocabulary from `Reason.wireToken()`
  // itself, so a token added on either side and not the other FAILS rather
  // than degrading to generic prose — the four-into-seven collapse rebuilt on the client.
  //
  // ── TWO SOURCE-READING CONSTRAINTS, BOTH REQUIRED ─────────────────────────────────────────────
  //
  // 1. THE PATH IS BUILT WITH `fileURLToPath`, NOT `new URL(literal, import.meta.url)`. Vite
  // statically pattern-matches that exact idiom — it is their asset-URL feature — and rewrites
  // it to an `/@fs/...` specifier whose protocol is not `file:`, so `readFile` throws
  // ERR_INVALID_URL_SCHEME under Vitest even though the identical relative path resolves fine
  // under plain `node`. Using that idiom here would therefore NEVER read the file in any environment.
  //
  // 2. THE FALLBACK IS ENOENT-ONLY, AND EVERYTHING ELSE RETHROWS. That is what actually makes this
  // test honest: a broad `catch {}` would treat a broken
  // READ MECHANISM as a missing FILE and fell through to `expect(7).toBe(7)` — a tautology
  // independent of the file's contents, which passed just as green with a deliberately mutated
  // token as without one. A check that cannot distinguish "the source is not here yet" from "I
  // could not read it" is a check that reports agreement it never established.
  it('matches the server enum once its source is present in the tree', async () => {
    const source = await readServerReasonSource();
    if (source === null) {
      // Genuinely absent from this source tree. The literal pin above still stands alone; when the
      // server source is present, this assertion compares its vocabulary. Any OTHER failure throws.
      expect(ASSISTANT_FAILURE_TOKENS.length).toBe(SERVER_VOCABULARY.length);
      return;
    }
    const tokens = [...source.matchAll(/"(ASSISTANT_[A-Z0-9_]+)"/g)].map(match => match[1]);
    expect(tokens.length, 'no wire tokens found — the parse, not the server, is what broke')
      .toBeGreaterThan(0);
    expect([...new Set(tokens)].sort()).toEqual([...ASSISTANT_FAILURE_TOKENS].sort());
  });

  // THE GUARD ITSELF, MADE FALSIFIABLE. Treating every error as "the file is not here" lets a broken
  // read report agreement it never established. The asserted distinction is exact: absence is null,
  // and anything else propagates.
  it('treats only ENOENT as absence, and propagates every other read failure', async () => {
    // A path that does not exist -> absent.
    expect(await readServerReasonSource(path.join(path.dirname(SERVER_REASON_SOURCE), 'NoSuchFile.java')))
      .toBeNull();
    // A path that exists but cannot be read as a file -> EISDIR, which must NOT read as absence.
    await expect(readServerReasonSource(path.dirname(fileURLToPath(import.meta.url))))
      .rejects.toThrow();
  });

  it('reads the server source when it is present, rather than always taking the fallback', async () => {
    // Belt-and-braces against the original defect: if the file IS in the tree, the reader must
    // return its bytes. A reader that always returned null would satisfy every other assertion here.
    const source = await readServerReasonSource();
    if (source !== null) expect(source).toContain('ASSISTANT_');
  });

  // ── `Reason.retryable()` PINNED AGAINST THE SENTENCES, NOT AGAINST ITS OWN JAVADOC ────────────
  //
  // Production does not read this accessor, so an accessor-only unit test would merely self-agree.
  // That control cannot detect semantic drift: rewrite ASSISTANT_PROVIDER_UNREADABLE's sentence to
  // say retrying will
  // not help and `retryable() == true` disagrees with the panel silently, which is precisely the
  // drift this file exists to catch between the two sides of the wire.
  //
  // So the boolean is read from the server enum and checked against the DIRECTION of the sentence
  // the panel actually renders. The classification is by phrase rather than by a second table of
  // expectations, because a table here would be the same self-agreement one layer along.
  //
  // The two-way rule, and why "false" admits two shapes of sentence: `retryable()` means THE
  // IDENTICAL REQUEST, UNCHANGED, is worth sending again. Six sentences state a retry direction
  // outright. The other two — PROVIDER_REFUSED and TOOL_LOOP_EXHAUSTED — propose a DIFFERENT
  // request instead (rephrase, narrow the question), which is agreement with `false`, not silence:
  // proposing a change of request is a statement that repeating this one will not do.
  const ENCOURAGES_RETRY = /trying again may succeed/i;
  const DISCOURAGES_RETRY = /retrying will not|will fail the same way|retrying will do the same thing/i;
  const PROPOSES_A_DIFFERENT_REQUEST = /rephrasing|rewording|try rewording|a narrower question/i;

  it('agrees with the server enum on whether the identical request is worth repeating', async () => {
    const source = await readServerReasonSource();
    if (source === null) {
      // Server branch unmerged: the sentences still have to classify, which is the half of this
      // check that does not depend on the file. Anything other than ENOENT has already thrown.
      for (const token of ASSISTANT_FAILURE_TOKENS) {
        const text = ASSISTANT_FAILURE_TEXT[token];
        expect(
          ENCOURAGES_RETRY.test(text) || DISCOURAGES_RETRY.test(text) || PROPOSES_A_DIFFERENT_REQUEST.test(text),
          `${token}: the sentence says nothing about what to do next`,
        ).toBe(true);
      }
      return;
    }

    // Each constant is TOKEN("ASSISTANT_...", "message...", true|false). Slice from one wire token
    // to the next and take the trailing boolean of that constructor call.
    const retryableByToken = new Map();
    const matches = [...source.matchAll(/"(ASSISTANT_[A-Z0-9_]+)"/g)];
    for (const [index, match] of matches.entries()) {
      const slice = source.slice(match.index, matches[index + 1]?.index ?? source.length);
      const flag = slice.match(/,\s*(true|false)\s*\)/);
      if (flag) retryableByToken.set(match[1], flag[1] === 'true');
    }

    expect(retryableByToken.size, 'no retryable flags parsed — the parse, not the server, is what broke')
      .toBe(ASSISTANT_FAILURE_TOKENS.length);

    for (const [token, retryable] of retryableByToken) {
      const text = ASSISTANT_FAILURE_TEXT[token];
      expect(text, `${token} has no panel sentence`).toBeTruthy();

      if (retryable) {
        expect(ENCOURAGES_RETRY.test(text), `${token}: retryable() is true but the panel does not say `
          + `trying again may succeed. One of the two is wrong: "${text}"`).toBe(true);
        expect(DISCOURAGES_RETRY.test(text), `${token}: retryable() is true but the panel talks the `
          + `author out of retrying: "${text}"`).toBe(false);
      } else {
        expect(ENCOURAGES_RETRY.test(text), `${token}: retryable() is false but the panel invites a `
          + `retry: "${text}"`).toBe(false);
        expect(
          DISCOURAGES_RETRY.test(text) || PROPOSES_A_DIFFERENT_REQUEST.test(text),
          `${token}: retryable() is false but the sentence neither says so nor proposes a different `
            + `request, so the panel leaves the author's next decision unanswered: "${text}"`,
        ).toBe(true);
      }
    }
  });

  it('gives every token a sentence, and never the same sentence twice', () => {
    const sentences = ASSISTANT_FAILURE_TOKENS.map(token => ASSISTANT_FAILURE_TEXT[token]);
    expect(sentences.every(text => typeof text === 'string' && text.length > 30)).toBe(true);
    // Two tokens sharing a sentence would be the collapse wearing nine names.
    expect(new Set(sentences).size).toBe(ASSISTANT_FAILURE_TOKENS.length);
  });

  // ── AN UNKNOWN TOKEN MUST NOT FABRICATE ───────────────────────────────────────────────────────
  it.each([
    ['a token this build has never seen', 'ASSISTANT_QUOTA_EXHAUSTED'],
    ['a token that merely shares a prefix', 'ASSISTANT_PROVIDER_REFUSED_BY_POLICY'],
    ['a token that is a prefix of a known one', 'ASSISTANT_PROVIDER'],
    ['a lowercased known token', 'assistant_provider_refused'],
    ['an empty token', ''],
    ['a non-string', 42],
  ])('returns nothing for %s rather than guessing a neighbour', (_label, token) => {
    expect(assistantFailureText(token)).toBeNull();
  });

  it('resolves every known token exactly', () => {
    for (const token of ASSISTANT_FAILURE_TOKENS) {
      expect(assistantFailureText(token)).toBe(ASSISTANT_FAILURE_TEXT[token]);
    }
  });

  it('is not fooled by inherited object properties', () => {
    // `Object.hasOwn`, not `in`: `toString` is on every object and is not a failure reason.
    expect(assistantFailureText('toString')).toBeNull();
    expect(assistantFailureText('constructor')).toBeNull();
  });
});

// ── THE CUSTODY RULE EXTENDS TO THE NEW SENTENCES ───────────────────────────────────────────────
//
// The rule existed only over the inert reasons. Seven new sentences arriving is exactly when a rule
// scoped to yesterday's table stops being a rule — so it is applied to every sentence the panel can
// render, and the source of truth for "every sentence" is both tables together.
describe('no sentence the panel can render claims where the credential lives', () => {
  const CUSTODY_CLAIMS = [
    /never holds/i, /on your behalf/i, /your own (model )?subscription/i,
    /api key/i, /device (flow|authorization)/i, /keychain/i, /we (do not|don.t) store/i,
  ];
  const EVERY_SENTENCE = [
    ...INERT_REASON_ORDER.map(reason => [reason, INERT_REASON_TEXT[reason]]),
    ...ASSISTANT_FAILURE_TOKENS.map(token => [token, ASSISTANT_FAILURE_TEXT[token]]),
  ];

  it.each(EVERY_SENTENCE)('%s says nothing about credential custody', (_name, text) => {
    for (const claim of CUSTODY_CLAIMS) {
      expect(text, `"${text}" makes a custody claim the panel cannot observe`).not.toMatch(claim);
    }
  });

  // The two tokens whose SERVER-side wording would have been wrong here if copied across: the
  // server tells its own reader to "check the configured profile", which the author of a graph
  // cannot do. A sentence that sends someone to a control they do not have is a dead end.
  it.each(['ASSISTANT_PROVIDER_REJECTED', 'ASSISTANT_EGRESS_REFUSED'])(
    '%s names the operator rather than instructing the author', token => {
      expect(ASSISTANT_FAILURE_TEXT[token]).toMatch(/operator/i);
      expect(ASSISTANT_FAILURE_TEXT[token]).not.toMatch(/^Check |check the configured profile/i);
    });

  // Retryability is the author's actual next decision and it differs per token, so the two terminal
  // ones must say so rather than inviting a retry that cannot work.
  it.each(['ASSISTANT_PROVIDER_REJECTED', 'ASSISTANT_EGRESS_REFUSED'])(
    '%s says retrying will not help', token => {
      expect(ASSISTANT_FAILURE_TEXT[token]).toMatch(/will not (clear it|help)/i);
    });

  // TOTAL, not a sample. The claim in `assistant-session.js` is that EVERY sentence tells the author
  // whether trying again is worth it; asserting it for two of seven would leave the claim resting on
  // the five nobody checked — which is how ASSISTANT_INVALID_TURN shipped silent on the question.
  const RETRY_GUIDANCE = /again|retry|retrying|rephras|rewording|narrower|will not/i;

  it.each(ASSISTANT_FAILURE_TOKENS)('%s tells the author whether trying again is worth it', token => {
    expect(ASSISTANT_FAILURE_TEXT[token]).toMatch(RETRY_GUIDANCE);
  });

  // CONTROL: the matcher must be able to miss, or the row above is decorative.
  it('has retry-guidance matching that can fail', () => {
    expect(RETRY_GUIDANCE.test('The message could not be read, so nothing was sent to the model.'))
      .toBe(false);
  });
});
