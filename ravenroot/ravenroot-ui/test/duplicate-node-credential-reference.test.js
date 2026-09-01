import { describe, expect, it } from 'vitest';

import { createCommandHistory } from '../src/graph-commands.js';
import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { duplicateNode } from '../src/graph-editing.js';

// ── DUPLICATING A NODE CARRIES THE SAME CREDENTIAL REFERENCE ─────────────────────────────────
//
// This property is a NAME the service resolves; a copy of a node must still point at the same
// credential. It does so for a
// reason that is easy to delete by accident: `duplicateNode` copies `properties` explicitly, field
// by field, and a `credentialRef` rides along inside that copy.
//
// THE REAL `duplicateNode` IS EXERCISED HERE, not a stand-in, because a stand-in would be a copy of
// the very line under test — and the mutation below only means something against the
// shipped one.
//
// WHAT THIS FILE DELIBERATELY DOES NOT ASSERT: that a duplicate gets a NEW reference, or that the
// two nodes' credentials are somehow isolated from each other. Both would be wrong. A reference is
// not a secret and not an instance; it is the name of a credential the service holds, and two nodes
// naming the same credential is the ordinary, intended case — the same way two nodes can name the
// same model provider profile. This is preservation, not isolation.

const CREDENTIAL_REF = 'rrc_0123456789abcdef0123456789abcdef';

/** A behavior node declaring a credential reference and a plain sibling property beside it. */
function documentWithCredentialNode() {
  const graph = createWorkflowDocument();
  const source = graph.nodeMap.dosomething;
  source.kind = 'BEHAVIOR';
  source.behavior = 'http-request';
  source.properties = { credentialRef: CREDENTIAL_REF, method: 'POST' };
  source.propertyTypes = { credentialRef: 'string', method: 'string' };
  return { graph, source };
}

describe('a duplicated node keeps the credential reference it was pointing at', () => {
  it('the copy declares the SAME reference, byte for byte', () => {
    const { graph, source } = documentWithCredentialNode();

    const copy = duplicateNode(graph, source.id);

    expect(copy).not.toBeNull();
    expect(copy.id).not.toBe(source.id);
    expect(copy.properties.credentialRef).toBe(CREDENTIAL_REF);
    expect(copy.properties.credentialRef).toBe(source.properties.credentialRef);
    expect(copy.propertyTypes.credentialRef).toBe('string');
  });

  it('the two nodes hold SEPARATE property maps, so editing one does not retarget the other', () => {
    // The same act has to satisfy both halves: the VALUE is shared, the CONTAINER is not. A shared
    // object would mean choosing a different credential on the copy silently changed the original.
    const { graph, source } = documentWithCredentialNode();

    const copy = duplicateNode(graph, source.id);
    copy.properties.credentialRef = 'rrc_ffffffffffffffffffffffffffffffff';

    expect(source.properties.credentialRef).toBe(CREDENTIAL_REF);
    expect(copy.properties).not.toBe(source.properties);
  });

  it('the reference survives into the saved GraphML, which is what a later session reads back', () => {
    const { graph, source } = documentWithCredentialNode();

    duplicateNode(graph, source.id, createCommandHistory());
    const xml = serializeGraphML(graph);

    // Twice: once for the original, once for the copy. Serialization is the boundary the criterion
    // actually has to hold across — a copy correct only in memory is a copy that is lost on save.
    expect([...xml.matchAll(new RegExp(CREDENTIAL_REF, 'g'))]).toHaveLength(2);
  });

  it('an undo of the duplicate removes the copy and leaves the original\'s reference untouched', () => {
    const { graph, source } = documentWithCredentialNode();
    const history = createCommandHistory();

    const copy = duplicateNode(graph, source.id, history);
    expect(graph.nodeMap[copy.id]).toBeDefined();

    history.undo(graph);

    expect(graph.nodeMap[copy.id]).toBeUndefined();
    expect(graph.nodeMap[source.id].properties.credentialRef).toBe(CREDENTIAL_REF);
  });

  // ── MUTATION CONTROL: DROPPING THE PROPERTY COPY ────────────────────────────────────────────────
  //
  // Removing `properties: { ...(source.properties || {}) }` from `duplicateNode`
  // (src/graph-editing.js) makes two of the five tests go RED: "the copy declares the SAME
  // reference" sees `undefined`, and "the reference survives into the saved GraphML" sees one
  // occurrence instead of two.
  //
  // THE OTHER THREE STAY GREEN UNDER THAT MUTATION, AND THAT IS WORTH KNOWING RATHER THAN HIDING.
  // "separate property maps" passes because `createNode` gives the copy its own empty `properties`,
  // so the objects are still distinct — it pins non-aliasing, which the deleted line is not the only
  // thing providing. "an undo removes the copy" passes because undo is about the command, not about
  // what the node carried. The last one is the vacuity guard below, which is about the fixture and
  // not about `duplicateNode` at all. None of the three is redundant; they simply guard a different
  // thing. Only the two that go red demonstrate that the reference itself is preserved.
  it('is not vacuous: the node under test really does declare a reference before it is copied', () => {
    const { source } = documentWithCredentialNode();
    expect(source.properties.credentialRef).toBe(CREDENTIAL_REF);
  });
});
