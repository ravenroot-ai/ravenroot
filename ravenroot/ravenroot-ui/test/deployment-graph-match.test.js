import { expect, it } from 'vitest';
import { deploymentGraphSignature } from '../src/deployment-graph-match.js';
const graph = (mode, x, behavior = 'log') => `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="view" for="graph" attr.name="ravenroot.renderMode"/><key id="x" for="node" attr.name="layoutX"/><key id="b" for="node" attr.name="behavior"/><graph id="g"><data key="view">${mode}</data><node id="n"><data key="x">${x}</data><data key="b">${behavior}</data></node></graph></graphml>`;
it('allows presentation changes but rejects changed executable properties and topology', () => {
  expect(deploymentGraphSignature(graph('design', 1))).toBe(deploymentGraphSignature(graph('monitoring', 10)));
  expect(deploymentGraphSignature(graph('design', 1))).not.toBe(deploymentGraphSignature(graph('monitoring', 10, 'http')));
  expect(deploymentGraphSignature(graph('design', 1))).not.toBe(deploymentGraphSignature(graph('design', 1).replace('id="n"', 'id="other"')));
});
