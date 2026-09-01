import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

import {
  AI_ORIGIN_ROLES,
  DISCLOSURE_ROLE,
  DISCLOSURE_SHORT_TEXT,
  DISCLOSURE_TEXT,
  DISCLOSURE_TURN_ID,
  admitTurn,
  describedById,
  disclosurePlan,
  isDisclosed,
} from '../src/assistant-disclosure.js';
import { ASSISTANT, AUTHOR, NOTICE, TRANSCRIPT_LIMIT, appendTurn } from '../src/assistant-session.js';

const push = (transcript, turn) => [...transcript, turn];

// ── THE OBLIGATION ITSELF ────────────────────────────────────────────────────────────────────────
//
// Art. 50(5): the information is provided "at the latest at the time of the first interaction or
// exposure". The falsifiable reading of that, for a transcript, is an ORDERING: no AI-origin turn
// may occupy an index lower than the disclosure. Every test in this block puts a reply somewhere
// and asserts the disclosure got in front of it.
describe('no model-generated turn reaches a reader before the disclosure', () => {
  it('puts the disclosure in front of the very first reply', () => {
    const transcript = admitTurn([], { role: ASSISTANT, text: 'Six nodes.' }, push);

    expect(transcript.map(entry => entry.role)).toEqual([DISCLOSURE_ROLE, ASSISTANT]);
    expect(transcript[0].text).toBe(DISCLOSURE_TEXT);
  });

  // The reply is the FIRST thing admitted — no author turn, no notice, nothing before it. This is
  // the case a disclosure rendered by the composer or by a panel-open handler would miss entirely,
  // because neither of those ran.
  it('discloses even when a reply is the first thing that ever happens', () => {
    const [first] = admitTurn([], { role: ASSISTANT, text: 'Unprompted.' }, push);

    expect(first.role).toBe(DISCLOSURE_ROLE);
  });

  it('discloses ahead of the reply when the conversation opened with a question', () => {
    let transcript = admitTurn([], { role: AUTHOR, text: 'How many nodes?' }, push);
    transcript = admitTurn(transcript, { role: ASSISTANT, text: 'Six.' }, push);

    expect(transcript.map(entry => entry.role)).toEqual([AUTHOR, DISCLOSURE_ROLE, ASSISTANT]);
    // The ordering property, stated as the thing that must never be false.
    const disclosed = transcript.findIndex(entry => entry.role === DISCLOSURE_ROLE);
    const firstReply = transcript.findIndex(entry => AI_ORIGIN_ROLES.includes(entry.role));
    expect(disclosed).toBeLessThan(firstReply);
  });

  // Turns that are not model output owe nothing. A disclosure emitted beside the user's own
  // question, or beside a Ravenroot error notice, would be telling them their own words were
  // AI-generated.
  it('does not disclose for the author, and does not disclose for a product notice', () => {
    expect(disclosurePlan([], { role: AUTHOR, text: 'Hi' }).owed).toBe(false);
    expect(disclosurePlan([], { role: NOTICE, text: 'Service gone' }).owed).toBe(false);
    expect(admitTurn([], { role: NOTICE, text: 'Service gone' }, push)).toHaveLength(1);
  });

  it('discloses once, not before every reply', () => {
    let transcript = admitTurn([], { role: ASSISTANT, text: 'One.' }, push);
    transcript = admitTurn(transcript, { role: ASSISTANT, text: 'Two.' }, push);
    transcript = admitTurn(transcript, { role: ASSISTANT, text: 'Three.' }, push);

    expect(transcript.filter(entry => entry.role === DISCLOSURE_ROLE)).toHaveLength(1);
    expect(transcript).toHaveLength(4);
  });

  // A cleared conversation is a new first interaction. The obligation attaches again, and it does
  // so without anyone resetting a flag — `isDisclosed` reads the transcript, so an empty one is
  // undisclosed by construction.
  it('discloses again after the conversation is cleared', () => {
    const first = admitTurn([], { role: ASSISTANT, text: 'One.' }, push);
    expect(isDisclosed(first)).toBe(true);

    const cleared = admitTurn([], { role: ASSISTANT, text: 'Two.' }, push);
    expect(cleared[0].role).toBe(DISCLOSURE_ROLE);
  });
});

// ── A CAPABILITY, NOT A FEATURE OF THIS PANEL ───────────────────────────────────────────────────
//
// An integrator building its own conversational endpoint must be able to
// COMPOSE this, not reimplement it. Asserting "the module is reusable" would prove nothing, so this
// block builds a second endpoint and runs it.
//
// The endpoint below deliberately shares almost nothing with the Ravenroot UI: it STORES turns in
// its own shape (`{ who, body }`), uses its own role vocabulary (`bot`, not `assistant`), its own
// append (mutating push, not the immutable one), and touches no DOM, no `app.js`, no `index.html`
// and no `assistant-session.js`. If the disclosure were hardwired into the panel, none of this
// could work.
//
// One thing it does share: the turn handed TO `admitTurn` still carries a `role`, because
// `disclosurePlan` reads `turn.role` and there is no
// seam for that. The integrator's storage is its own; the call's argument shape is not.
describe("an integrator's own endpoint composes the capability without reimplementing it", () => {
  // A minimal conversational endpoint, of the kind ADR 0017 says the INTEGRATOR builds and this
  // project does not ship. Its only dependency on Ravenroot is `admitTurn`.
  const createIntegratorEndpoint = () => {
    const history = [];
    const options = {
      // Its roles are its own; the capability is told about them rather than assuming them.
      roles: ['bot'],
      // Its storage is its own, so it says which of ITS entries is the disclosure. This line is
      // the whole of what the integrator writes about disclosure state — see the `options.disclosed`
      // comment in the module for the defect that proved this seam has to exist.
      disclosed: log => log.some(entry => entry.who === 'system'),
    };
    // Its storage is its own; the capability is handed the append rather than owning it.
    const append = (log, turn) => { log.push(turn); return log; };
    return {
      history,
      reply(body) {
        // The vocabulary adapter is the integrator's, and it is the ONLY thing they write. The
        // ordering guarantee is not among the things they write.
        admitTurn(history, { role: 'bot', who: 'bot', body }, (log, turn) =>
          append(log, turn.role === DISCLOSURE_ROLE ? { who: 'system', body: turn.text } : turn), options);
        return this;
      },
    };
  };

  it('gets the disclosure ahead of its first reply having written no disclosure logic', () => {
    const endpoint = createIntegratorEndpoint().reply('Hello from a different product.');

    expect(endpoint.history[0]).toEqual({ who: 'system', body: DISCLOSURE_TEXT });
    expect(endpoint.history[1].body).toBe('Hello from a different product.');
  });

  it('gets the once-only property too, without tracking it', () => {
    const endpoint = createIntegratorEndpoint().reply('One.').reply('Two.');

    expect(endpoint.history.filter(entry => entry.who === 'system')).toHaveLength(1);
    expect(endpoint.history).toHaveLength(3);
  });

  // REGRESSION. The demonstration above caught this: with a foreign transcript shape the default
  // `isDisclosed` reads a `role` field that the integrator's storage does not have, reports "not
  // disclosed" forever, and re-discloses before every single reply. Over-disclosing is not a safe
  // failure — a panel that repeats the notice before each answer trains the reader to skip it, and
  // it proves the capability only works for callers who adopted this panel's field names. Pinned
  // separately from the demonstration so removing the seam reds a test that names the reason.
  it('does not re-disclose when the integrator stores turns in its own shape', () => {
    const endpoint = createIntegratorEndpoint().reply('One.').reply('Two.').reply('Three.');

    expect(endpoint.history.map(entry => entry.who))
      .toEqual(['system', 'bot', 'bot', 'bot']);
  });

  // The text is OBTAINED, never restated. An integrator that copied the sentence into its own
  // source would have a disclosure that silently diverges when the capability's sentence changes.
  // That violates the single-source invariant and would otherwise pass every test above.
  it('reads the disclosed text from the capability rather than carrying its own copy', async () => {
    const source = await readFile('test/assistant-disclosure.test.js', 'utf8');
    const endpointSource = source.slice(source.indexOf('const createIntegratorEndpoint'),
      source.indexOf('it(\'gets the disclosure ahead'));

    expect(endpointSource).not.toContain('You are interacting with an AI system');
    expect(endpointSource).toContain('turn.text');
  });
});

// ── ACCESSIBILITY ───────────────────────────────────────────────────────────────────────────────
describe('the disclosure is reachable from the content it discloses', () => {
  // Adjacency is not association. A screen-reader user who lands on a reply by navigating the log
  // must get the disclosure WITH it, not only if they happened to pass it on the way in.
  it('associates AI-origin turns with the disclosure, and nothing else', () => {
    expect(describedById(ASSISTANT)).toBe(DISCLOSURE_TURN_ID);
    expect(describedById(AUTHOR)).toBeNull();
    expect(describedById(NOTICE)).toBeNull();
    expect(describedById(DISCLOSURE_ROLE)).toBeNull();
  });

  it('is present before the first interaction, not only at the first reply', async () => {
    const html = await readFile('index.html', 'utf8');
    const help = new DOMParser().parseFromString(html, 'text/html')
      .getElementById('assistant-composer-help');

    // Pinned to the constant: the shipped sentence and the module cannot drift apart silently.
    expect(help.textContent).toContain(DISCLOSURE_SHORT_TEXT);
  });

  it('says the output is AI-generated, which is the fact Article 50(1) requires', () => {
    expect(DISCLOSURE_TEXT).toMatch(/AI system/);
    expect(DISCLOSURE_TEXT).toMatch(/generated by an AI model/);
    // It must not reassure. "Reviewed", "safe" or "accurate" as a claim would undercut it.
    expect(DISCLOSURE_TEXT).toMatch(/not reviewed by a person/);
  });
});

// ── THE PANEL'S OWN WIRING ───────────────────────────────────────────────────────────────────────
describe('the panel cannot append a reply around the gate', () => {
  // `assistant-client.test.js` pins its single construction site by reading the source, for the
  // reason its header gives: a guarantee resting on one unpinned call site is resting on a habit.
  // This is the same shape of guarantee and gets the same treatment — the disclosure holds only if
  // `pushAssistantTurn` is the one admission path and it goes through `admitTurn`.
  it('routes every transcript append through admitTurn', async () => {
    const source = await readFile('src/app.js', 'utf8');
    const body = source.slice(source.indexOf('function pushAssistantTurn'),
      source.indexOf('function renderAssistantTurn'));

    expect(body).toContain('admitTurn(');
    // Exactly one call site for the raw append, and it is inside the gate's own callback.
    expect(source.match(/appendTurn\(/g)).toHaveLength(1);
  });

  it('survives the transcript cap, in place and without reordering', () => {
    let transcript = admitTurn([], { role: ASSISTANT, text: 'first' }, appendTurn);
    for (let index = 0; index < TRANSCRIPT_LIMIT + 20; index += 1) {
      transcript = admitTurn(transcript, { role: AUTHOR, text: `q${index}` }, appendTurn);
    }

    // Still exactly one, still disclosed, and still ahead of the reply it disclosed.
    expect(transcript.filter(entry => entry.role === DISCLOSURE_ROLE)).toHaveLength(1);
    expect(transcript).toHaveLength(TRANSCRIPT_LIMIT);
    expect(isDisclosed(transcript)).toBe(true);
    expect(transcript[0].role).toBe(DISCLOSURE_ROLE);
  });

  // THE CAP EXCEPTION IS TWO CLAIMS, AND THE TEST ABOVE ONLY FALSIFIES ONE.
  //
  // `appendTurn` claims the disclosure survives the trim AND that it is dropped BY POSITION rather
  // than by rebuilding the array as `[...disclosure, ...rest]`. The test above cannot see the
  // second claim: it opens with a reply, so the disclosure is already at index 0 and both trims
  // agree. The two only diverge when TWO OR MORE non-disclosure turns precede the disclosure —
  // ask, service error, reply — which is an ordinary history, not a contrived one:
  //
  // positional ["notice", "disclosure", "assistant"] disclosure at 1
  // partition ["disclosure", "notice", "assistant"] disclosure at 0
  //
  // The partition result reorders the array while the DOM only ever appends, so the two
  // desynchronise and violate this region's append-only live-region invariant. It also has to be
  // measured at the append where the cap FIRST fires: keep trimming and the leading turns are all
  // gone, the difference washes out, and a saturating probe reports agreement that is not there.
  it('keeps the disclosure behind the turns it followed when the cap first fires', () => {
    let transcript = admitTurn([], { role: AUTHOR, text: 'why is this slow?' }, appendTurn);
    transcript = admitTurn(transcript, { role: NOTICE, text: 'the assistant request failed.' }, appendTurn);
    transcript = admitTurn(transcript, { role: ASSISTANT, text: 'Node 3 is the bottleneck.' }, appendTurn);
    // The disclosure sits where it was emitted: after the notice, ahead of the reply it discloses.
    expect(transcript.map(entry => entry.role))
      .toEqual([AUTHOR, NOTICE, DISCLOSURE_ROLE, ASSISTANT]);

    // Exactly to the limit, so the NEXT append is the first one that trims anything.
    while (transcript.length < TRANSCRIPT_LIMIT) {
      transcript = admitTurn(transcript, { role: AUTHOR, text: 'filler' }, appendTurn);
    }
    transcript = admitTurn(transcript, { role: AUTHOR, text: 'one more' }, appendTurn);

    // The oldest NON-disclosure entry — the opening author turn — is gone, and nothing else moved.
    expect(transcript.slice(0, 3).map(entry => entry.role))
      .toEqual([NOTICE, DISCLOSURE_ROLE, ASSISTANT]);
    expect(transcript.findIndex(entry => entry.role === DISCLOSURE_ROLE)).toBe(1);
    expect(transcript).toHaveLength(TRANSCRIPT_LIMIT);
  });

  it('refuses an append that is not a function rather than dropping the turn', () => {
    expect(() => admitTurn([], { role: ASSISTANT, text: 'x' }, null)).toThrow(TypeError);
  });
});
