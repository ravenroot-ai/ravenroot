import { DESIGN_ARRANGEMENTS } from './design-arrangements.js';

/** Maps the persisted Design arrangement vocabulary to the editor's layout engines. */
export function viewerDesignLayoutOptions(arrangement) {
  if (arrangement != null && !Object.hasOwn(DESIGN_ARRANGEMENTS, arrangement)) {
    throw new TypeError('Unsupported Design arrangement.');
  }
  if (arrangement === 'keep') return { name: 'preset', fit: false };
  if (arrangement === 'flow') return { name: 'dagre', rankDir: 'LR', fit: false, animate: false };
  if (arrangement === 'organic') return { name: 'cose', fit: false, animate: false };
  if (arrangement === 'hierarchical-new' || arrangement === 'layered-down') {
    return { name: 'rr-layered', mode: arrangement, fit: false, animate: false };
  }
  return {
    name: 'elk', fit: false, animate: false,
    elk: { algorithm: 'layered', 'elk.direction': 'RIGHT' },
  };
}
