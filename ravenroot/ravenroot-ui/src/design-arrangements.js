/** Stable semantic Design arrangements shared by Workbench and read-only embeds. */
export const DESIGN_ARRANGEMENTS = Object.freeze({
  hierarchical: Object.freeze({ layout: 'hierarchical' }),
  flow: Object.freeze({ layout: 'dagre' }),
  organic: Object.freeze({ layout: 'cose' }),
  keep: Object.freeze({ preservePositions: true }),
  'hierarchical-new': Object.freeze({ layout: 'hierarchical-new' }),
  'layered-down': Object.freeze({ layout: 'layered-down' }),
});
