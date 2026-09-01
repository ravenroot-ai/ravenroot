const SHARED = Object.freeze({
  nodeType: Object.freeze({
    start: '#238636', end: '#cf222e', error: '#9a6700', terminal: '#57606a',
    consumer: '#bc4c00', handler: '#8250df', agent: '#0969da', flow: '#0969da',
    actor: '#9a6700', system: '#57606a',
  }),
  edgeType: Object.freeze({
    failed: '#cf222e', completed: '#238636', continue: '#0969da', continueP: '#0969da',
    validate: '#9a6700', ping: '#007d75', outcome: '#8250df', undefined: '#6f42c1',
    callback: '#bc4c00', default: '#656d76',
    // Deliberately NOT the `failed` red. `failed` is an outcome a node returned; `failure` is
    // the route an exception takes, and confusing the two is dangerous: an `outcome=failed` edge
    // into an Error node never fires. Sharing the red family would leave that confusion intact at
    // a glance, which is
    // the one thing this colour has to prevent. `continue`/`continueP` share a hue and separate on
    // width; these two must separate on hue, because width alone is not what an author scans for
    // when asking "what happens when this breaks".
    failure: '#bf3989',
  }),
});

export const RENDERER_PALETTES = Object.freeze({
  dark: Object.freeze({
    canvas: '#0d1117', nodeSurface: '#21262d', nodeText: '#e6edf3', nodeBorder: '#8b949e',
    edgeLabel: '#a9b4c0', edgeLabelSurface: '#0d1117', trace: '#79b8ff', traceStart: '#2a3a50',
    selection: '#58a6ff', focus: '#d2a8ff', runtimeIdle: '#0d1117', minimapViewport: '#79c0ff',
    minimapViewportFill: 'rgba(88, 166, 255, .14)',
    nodeType: Object.freeze({
      start: '#3fb950', end: '#ff7b72', error: '#e3b341', terminal: '#a5adb7',
      consumer: '#f0883e', handler: '#d2a8ff', agent: '#58a6ff', flow: '#58a6ff',
      actor: '#e3b341', system: '#8b949e',
    }),
    edgeType: Object.freeze({
      failed: '#ff7b72', completed: '#3fb950', continue: '#58a6ff', continueP: '#58a6ff',
      validate: '#e3b341', ping: '#4ecdc4', outcome: '#d2a8ff', undefined: '#b083e3',
      callback: '#f0883e', default: '#6e7681',
      // Dark counterpart of the light `failure` above -- same reasoning, same separation
      // from `failed`.
      failure: '#f778ba',
    }),
    nodeSurfaceByType: Object.freeze({
      start: '#0d2518', end: '#2d0e0e', error: '#2d1a06', terminal: '#1a1a2e',
      consumer: '#2e1500', handler: '#1e1030', agent: '#0e1e30', flow: '#0e1e30',
      actor: '#241800', system: '#1a1f26',
    }),
  }),
  light: Object.freeze({
    canvas: '#f6f8fa', nodeSurface: '#ffffff', nodeText: '#1f2328', nodeBorder: '#57606a',
    edgeLabel: '#3d444d', edgeLabelSurface: '#f6f8fa', trace: '#0550ae', traceStart: '#dbeafe',
    selection: '#0969da', focus: '#8250df', runtimeIdle: '#ffffff', minimapViewport: '#0550ae',
    minimapViewportFill: 'rgba(9, 105, 218, .12)',
    nodeType: SHARED.nodeType,
    edgeType: SHARED.edgeType,
    nodeSurfaceByType: Object.freeze({
      start: '#dafbe1', end: '#ffebe9', error: '#fff8c5', terminal: '#f6f8fa',
      consumer: '#fff1e5', handler: '#fbefff', agent: '#ddf4ff', flow: '#ddf4ff',
      actor: '#fff8c5', system: '#f6f8fa',
    }),
  }),
});

export function getRendererPalette(theme) {
  return RENDERER_PALETTES[theme] || RENDERER_PALETTES.dark;
}
