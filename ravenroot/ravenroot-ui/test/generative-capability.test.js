import { readFile, readdir } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

// Relative to `ravenroot/ravenroot-ui`, the directory vitest runs from — the same base the Java
// constant is read from below.
const BUNDLE_DIR = '../../ravenroot-plugins';

import {
  GENERATIVE_CAPABILITIES,
  PROVIDER_CONFIG_POINTER,
  invokesModelProvider,
} from '../src/generative-capability.js';

// ── MODEL-INVOKING NODE CLASSIFICATION AND GUIDANCE ─────────────────────────────────────────
//
// These cases test that the editor decides WHICH NODES invoke a model the same way the runtime does.
// That classification is independent of any particular configuration surface.
//
// Provider guidance must name the plugin bundle that supplies the node. The editor has no Model
// providers section, so pointing there would be false. The sentence names a real place without
// promising what happens once the author gets there.

describe('where an unconfigured model-invoking node is told to go', () => {
  it('mirrors the runtime’s own generative-capability set rather than keeping a name list', async () => {
    // Asserted against the Java constant, so a capability added on one side and not the other fails
    // here instead of quietly changing which nodes get the sentence. This matters because
    // `llm-prompt` and `agent` have left the core catalog, so a behavior-name list in this
    // editor would be a list of names it never sees, and a capability set is what a bundle declares
    // for itself.
    const java = await readFile(
      '../ravenroot-application-api/src/main/java/ai/ravenroot/api/provenance/SyntheticProvenance.java', 'utf8');
    const declared = java.match(/GENERATIVE_CAPABILITIES = Set\.of\(([^)]*)\)/)[1]
      .split(',').map(entry => entry.trim().replace(/"/g, ''));
    expect([...GENERATIVE_CAPABILITIES].sort()).toEqual(declared.sort());
  });

  it('recognises a model-invoking node from its declared capabilities', () => {
    expect(invokesModelProvider({ capabilities: ['ai', 'external-provider'] })).toBe(true);
    expect(invokesModelProvider({ capabilities: ['agentic'] })).toBe(true);
  });

  it('says nothing to an adapter-bound node that has nothing to do with models', () => {
    // An AMQP or Telegram package declares an adapter binding too. Telling its author to go and
    // configure a model provider would be a confident instruction to the wrong place.
    expect(invokesModelProvider({ capabilities: ['messaging'] })).toBe(false);
    expect(invokesModelProvider({ capabilities: [] })).toBe(false);
    expect(invokesModelProvider({})).toBe(false);
    expect(invokesModelProvider(null)).toBe(false);
  });

  it('tells the author what the field names and promises nothing about what happens next', () => {
    // An assertion such as `toMatch(/bundle/i)` would make
    // the false sentence look verified: it pinned the very word that was the defect. What is
    // actually required of this constant is that it says something, and that what it says is not a
    // promise about execution — a declared profile does not arm anything.
    expect(PROVIDER_CONFIG_POINTER.trim().length).toBeGreaterThan(40);
    expect(PROVIDER_CONFIG_POINTER).not.toMatch(/\b(then it will work|active|ready|connected|enabled)\b/i);
  });

  it('names no surface this editor does not have', () => {
    // If a surface loses content, it either disappears or says why. The panel disappeared, so the
    // sentence must not point at it — a reader sent to look for a section that is not there is worse off
    // than one given no destination at all.
    expect(PROVIDER_CONFIG_POINTER).not.toMatch(/Model providers/i);
    expect(PROVIDER_CONFIG_POINTER).not.toMatch(/\bInspector\b/i);
    expect(PROVIDER_CONFIG_POINTER).not.toMatch(/\bpanels?\b/i);
  });

  // ── CONTROL FOR A BUNDLE-RELATIVE DESCRIPTION ──────────────────────────────────────────────
  //
  // "The Inspector's Model providers section" and "the plugin bundle that supplies this node
  // type" share ONE defect — they name a place the
  // reader cannot open — and the assertions above catch only the first spelling of it, because they
  // are a list of forbidden words. A list of words cannot catch the next wording nobody thought of.
  //
  // So this reads the REPOSITORY instead of a word list. A bundle is an honest destination exactly
  // when a bundle exists; until one does, naming it is the same confident, wrong instruction the
  // panel wording was. When someone really does compile and include one, this case stops forbidding
  // it on its own, with no edit here — which is the point of measuring the condition rather than
  // asserting today's answer.
  it('does not send the author to a bundle while the repository ships none', async () => {
    const entries = await readdir(BUNDLE_DIR);
    const bundles = entries.filter(entry => entry !== '.gitkeep' && entry !== 'README.md');

    if (bundles.length === 0) {
      expect(PROVIDER_CONFIG_POINTER).not.toMatch(/\bbundles?\b/i);
      expect(PROVIDER_CONFIG_POINTER).not.toMatch(/\bplug-?ins?\b/i);
    }
  });

  it('names the one holder that still exists: the deployment’s own composition', async () => {
    // The positive half, without which the case above is satisfied by a sentence that names no
    // destination at all — and an author staring at a blank `provider` field with no idea what the
    // value even IS is the state this sentence was written to remove.
    //
    // The server exposes no `/v1/model-providers` route because the provider-configuration boundary
    // is absent. This check therefore requires the route's ABSENCE: the sentence may not point at an
    // API a reader cannot call, exactly as it may not point at a panel that is not there.
    //
    // Measured against the server rather than against a word list, for the reason the case below
    // states: a list of forbidden spellings cannot catch the next wording nobody thought of.
    const routes = await readFile(
      '../ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServer.java', 'utf8');
    expect(routes).not.toMatch(/apiContext\("\/v1\/model-providers"/);
    expect(PROVIDER_CONFIG_POINTER).not.toMatch(/\/v1\//);

    // What remains true, and is what the sentence names: a model-invoking node reaching this editor
    // came from outside the artifact, and whoever put it there composed its provider.
    expect(PROVIDER_CONFIG_POINTER).toMatch(/deployment|service/i);
  });
});
