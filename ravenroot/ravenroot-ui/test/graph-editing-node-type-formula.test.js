import { describe, expect, it, vi } from 'vitest';

// `resolveDescriptorNodeType` is THE single formula for a descriptor's visual node type. The canvas
// drag-and-drop insertion path (`createNodeForInsertion`) must delegate to that shared module rather
// than carry an independent copy that can silently diverge from catalog placement.
//
// A test asserting "the two formulas give the same result" proves nothing about whether
// graph-editing.js delegates to the shared module or just computes the same thing independently.
// This test instead swaps out the shared formula's *behavior* and checks that the drag-and-drop path
// reflects the swap. An inline copy never consults the mocked shared export, so `node.nodeType`
// comes out as 'actor', not the mocked value, and the mock's spy records zero calls.
vi.mock('../src/catalog-node-icon.js', () => ({
  resolveDescriptorNodeType: vi.fn(() => 'mock-formula-result'),
}));

const { createWorkflowDocument } = await import('../src/graph-document.js');
const { addNodeAt, addConnectedNodeAt } = await import('../src/graph-editing.js');
const { resolveDescriptorNodeType } = await import('../src/catalog-node-icon.js');

describe('graph-editing drag-and-drop insertion delegates node-type resolution', () => {
  it('addNodeAt uses the shared formula instead of a private copy', () => {
    const graph = createWorkflowDocument();
    const descriptor = { behavior: 'send-mail', displayName: 'Send mail', visualType: 'actor', agentic: false };

    const node = addNodeAt(graph, { x: 10, y: 10 }, null, { descriptor });

    expect(resolveDescriptorNodeType).toHaveBeenCalledWith(descriptor);
    expect(node.nodeType).toBe('mock-formula-result');
  });

  it('addConnectedNodeAt (the toolbox-drop-on-a-source gesture) also uses the shared formula', () => {
    const graph = createWorkflowDocument();
    const descriptor = { behavior: 'send-mail', displayName: 'Send mail', visualType: 'actor', agentic: false };

    const { node } = addConnectedNodeAt(graph, { x: 10, y: 10 }, 'start', null, { descriptor });

    expect(node.nodeType).toBe('mock-formula-result');
  });
});
