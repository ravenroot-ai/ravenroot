import { describe, expect, it } from 'vitest';

import {
  DEFAULT_ROUTED_OUTCOME,
  MAX_PUBLIC_REASON_LENGTH,
  conformingPublicReason,
  publicExecutionDescription,
} from '../src/execution-event-description.js';

// The panel showed "Node completed successfully." for a node whose event carried
// `detail="outcome=failed"` -- a false statement at the exact spot a reader goes to find out what
// went wrong. These tests exist to make that specific row unproducible, from every input path.
describe('a completed node never claims success for a non-default outcome', () => {
  it('names the routed outcome instead of asserting success', () => {
    expect(publicExecutionDescription(undefined, 'NODE_COMPLETED', 'failed'))
      .toBe('Node completed and routed its "failed" outcome.');
  });

  it('asserts success only for the default outcome', () => {
    expect(publicExecutionDescription(undefined, 'NODE_COMPLETED', DEFAULT_ROUTED_OUTCOME))
      .toBe('Node completed successfully.');
  });

  // The regression itself: with no classifier the sentence must stay weak rather than guess. A peer
  // that predates `publicReason` reaches exactly this path, and it is the path that used to lie.
  it('does not claim success when it does not know the outcome', () => {
    expect(publicExecutionDescription(undefined, 'NODE_COMPLETED')).toBe('Node completed.');
    expect(publicExecutionDescription(undefined, 'NODE_COMPLETED', null)).toBe('Node completed.');
  });

  it('never produces the phrase for any classifier other than the default outcome', () => {
    for (const outcome of ['failed', 'error', 'retry', 'escalate', 'timeout', 'rejected']) {
      expect(publicExecutionDescription(undefined, 'NODE_COMPLETED', outcome))
        .not.toContain('successfully');
    }
  });
});

describe('a failure carries a reason where it previously carried none', () => {
  it('names the exception class for a failed node', () => {
    expect(publicExecutionDescription(undefined, 'NODE_FAILED', 'UnknownProgramArtifactException'))
      .toBe('Node failed with UnknownProgramArtifactException. '
        + 'Protected diagnostics may contain more detail.');
  });

  it('names the join reason for a failed join', () => {
    expect(publicExecutionDescription(undefined, 'JOIN_FAILED', 'QUORUM_UNREACHABLE'))
      .toBe('Join conditions could not be satisfied: QUORUM_UNREACHABLE.');
  });
});

// The classifier lands inside a sentence rendered into the DOM, and it arrives over the wire. The
// character rule is the entire reason that is safe, so it is checked here and not assumed from the
// server's own check -- a fixture, a proxy or an older peer can put anything under this key.
describe('the classifier rejects anything that is not a classifier', () => {
  it('accepts outcome names, Java simple names and join reasons', () => {
    for (const value of ['continue', 'failed', 'UnknownProgramArtifactException',
      'QUORUM_UNREACHABLE', 'a.b-c:d_e', '0']) {
      expect(conformingPublicReason(value)).toBe(value);
    }
  });

  it('rejects prose, exception messages and markup rather than trimming them', () => {
    for (const value of [
      'Tool is not allowlisted: program.execute',
      'password=hunter2',
      '<img src=x onerror=alert(1)>',
      'outcome=failed',
      'failed\nNODE_COMPLETED',
      '',
    ]) {
      expect(conformingPublicReason(value)).toBeNull();
    }
  });

  it('rejects an over-long token instead of truncating it into a plausible one', () => {
    expect(conformingPublicReason('a'.repeat(MAX_PUBLIC_REASON_LENGTH))).not.toBeNull();
    expect(conformingPublicReason('a'.repeat(MAX_PUBLIC_REASON_LENGTH + 1))).toBeNull();
  });

  it('rejects non-string transport values', () => {
    for (const value of [null, undefined, 42, {}, ['failed']]) {
      expect(conformingPublicReason(value)).toBeNull();
    }
  });

  // A rejected classifier must degrade to the weak sentence, never to the confident one.
  it('falls back to the reason-less sentence when the classifier does not conform', () => {
    expect(publicExecutionDescription(undefined, 'NODE_COMPLETED', 'outcome=failed'))
      .toBe('Node completed.');
  });
});

// Two different facts now share NODE_BYPASSED and the panel rendered one sentence for both:
// "the whole run is a rehearsal" and "one node is switched off in the saved graph while the rest of
// the run is real". A reader deciding whether a result is trustworthy has to tell those apart, and
// `detail` cannot help -- `RavenrootServer` never serializes it. Only `publicReason` crosses.
describe('a bypassed node says WHICH kind of bypass it was', () => {
  it('names an author switching one node off', () => {
    expect(publicExecutionDescription(undefined, 'NODE_BYPASSED', 'authored'))
      .toBe('Node was bypassed: the graph author switched this node off.');
  });

  it('names a traversal that was not executing behaviours at all', () => {
    expect(publicExecutionDescription(undefined, 'NODE_BYPASSED', 'command.passthrough'))
      .toBe('Node was bypassed: the traversal was not executing node behaviours.');
  });

  it('keeps the two sentences distinguishable, which is the entire point', () => {
    expect(publicExecutionDescription(undefined, 'NODE_BYPASSED', 'authored'))
      .not.toBe(publicExecutionDescription(undefined, 'NODE_BYPASSED', 'command.passthrough'));
  });

  // The classifier SELECTS among sentences here rather than being interpolated into one, exactly as
  // `PublicExecutionDescription.forType` does on the server -- so an unrecognised token falls back to
  // the bare sentence instead of producing prose built around a string neither side can vouch for.
  it('falls back to the bare sentence for a classifier it does not recognise', () => {
    for (const reason of ['test-mode', 'AUTHORED', 'command', 'authored.x']) {
      expect(publicExecutionDescription(undefined, 'NODE_BYPASSED', reason))
        .toBe('Node was bypassed.');
    }
  });

  it('never lets an unrecognised classifier reach the rendered sentence', () => {
    expect(publicExecutionDescription(undefined, 'NODE_BYPASSED', 'switched-off-by-mallory'))
      .not.toContain('mallory');
  });

  // Durable replay never captured a classifier, and a peer predating sends none.
  it('keeps the legacy sentence when no classifier arrives at all', () => {
    expect(publicExecutionDescription(undefined, 'NODE_BYPASSED')).toBe('Node was bypassed.');
    expect(publicExecutionDescription(undefined, 'NODE_BYPASSED', null)).toBe('Node was bypassed.');
  });

  it('still ignores the diagnostic channel, which is where the old constant lived', () => {
    // `ExecutionMonitor#nodeBypassed` used to publish a fixed "incoming command=passthrough" detail.
    // It does not cross the HTTP boundary and must not be read even when a fixture supplies one.
    const legacy = { type: 'NODE_BYPASSED', detail: 'incoming command=passthrough' };
    expect(publicExecutionDescription(legacy.description, legacy.type, legacy.publicReason))
      .toBe('Node was bypassed.');
  });
});

describe('the server sentence wins when it is present', () => {
  it('prefers the composed description over recomposing one locally', () => {
    expect(publicExecutionDescription('Node completed and routed its "escalate" outcome.',
      'NODE_COMPLETED', 'escalate'))
      .toBe('Node completed and routed its "escalate" outcome.');
  });

  it('still ignores the legacy diagnostic channel entirely', () => {
    const legacy = { type: 'NODE_COMPLETED', detail: 'outcome=failed password=hunter2' };
    expect(publicExecutionDescription(legacy.description, legacy.type, legacy.publicReason))
      .toBe('Node completed.');
  });
});
