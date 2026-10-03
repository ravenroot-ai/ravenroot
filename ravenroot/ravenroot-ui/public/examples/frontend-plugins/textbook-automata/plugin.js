const point = (positions, id, fallback) => positions[id] || fallback;

function curve(source, target, lane) {
  const dx = target.x - source.x;
  const dy = target.y - source.y;
  const distance = Math.hypot(dx, dy);
  const ux = dx / distance;
  const uy = dy / distance;
  const start = { x: source.x + ux * 50, y: source.y + uy * 50 };
  const end = { x: target.x - ux * 55, y: target.y - uy * 55 };
  const control = {
    x: (start.x + end.x) / 2 - uy * (120 + lane * 45),
    y: (start.y + end.y) / 2 + ux * (120 + lane * 45),
  };
  return {
    d: `M ${start.x} ${start.y} Q ${control.x} ${control.y} ${end.x} ${end.y}`,
    label: {
      x: (start.x + 2 * control.x + end.x) / 4,
      y: (start.y + 2 * control.y + end.y) / 4,
    },
  };
}

function transitionElements(transition, positions, lane) {
  const source = positions[transition.source];
  const target = positions[transition.target];
  const activeClass = transition.active ? 'automata-transition active' : 'automata-transition';
  if (transition.source === transition.target) {
    const spread = 58 + lane * 18;
    const rise = 105 + lane * 34;
    const d = `M ${source.x - 23} ${source.y - 39} C ${source.x - spread} ${source.y - rise}, ${source.x + spread} ${source.y - rise}, ${source.x + 23} ${source.y - 39}`;
    return [
      { type: 'path', id: transition.id, role: 'transition', label: `${transition.source} to ${transition.target} on ${transition.label}`, d, className: activeClass },
      { type: 'text', text: transition.label, x: source.x, y: source.y - 92 - lane * 34, className: 'automata-label' },
    ];
  }
  const curved = curve(source, target, lane);
  return [
    { type: 'path', id: transition.id, role: 'transition', label: `${transition.source} to ${transition.target} on ${transition.label}`, d: curved.d, className: activeClass },
    { type: 'text', text: transition.label, x: curved.label.x, y: curved.label.y, className: 'automata-label' },
  ];
}

export default {
  layout(snapshot) {
    const positions = {};
    snapshot.states.forEach((state, index) => {
      positions[state.id] = point(snapshot.positions, state.id, { x: 220 + index * 330, y: 250 });
    });
    return { schema: 'ravenroot.layout-result/v1', positions };
  },
  render(input) {
    const { snapshot, layout } = input;
    const positions = layout.positions;
    const elements = [];
    const lanes = new Map();
    snapshot.transitions.forEach(transition => {
      const key = `${transition.source}\u0000${transition.target}`;
      const lane = lanes.get(key) || 0;
      lanes.set(key, lane + 1);
      elements.push(...transitionElements(transition, positions, lane));
    });
    snapshot.states.forEach(state => {
      const p = positions[state.id];
      if (state.initial) {
        elements.push({ type: 'line', id: `initial-${state.id}`, role: 'state', label: `Initial state ${state.label}`,
          x1: p.x - 105, y1: p.y, x2: p.x - 48, y2: p.y, className: 'automata-initial' });
      }
      elements.push({ type: 'circle', id: state.id, role: 'state', label: `${state.label}${state.initial ? ', initial' : ''}${state.accepting ? ', accepting' : ''}`,
        x: p.x, y: p.y, r: 46, className: state.active ? 'automata-state active' : 'automata-state' });
      if (state.accepting) elements.push({ type: 'circle', x: p.x, y: p.y, r: 38, className: 'automata-accepting' });
      elements.push({ type: 'text', text: state.label, x: p.x, y: p.y + 7, className: 'automata-state-label' });
    });
    return { schema: 'ravenroot.scene/v1', width: 800, height: 500, elements };
  },
};
