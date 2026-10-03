const point = (positions, id, fallback) => positions[id] || fallback;

function curve(source, target, bend) {
  const middleX = (source.x + target.x) / 2;
  const middleY = (source.y + target.y) / 2 + bend;
  return `M ${source.x} ${source.y} Q ${middleX} ${middleY} ${target.x} ${target.y}`;
}

function transitionElements(transition, positions, index) {
  const source = positions[transition.source];
  const target = positions[transition.target];
  const activeClass = transition.active ? 'automata-transition active' : 'automata-transition';
  if (transition.source === transition.target) {
    const d = `M ${source.x - 23} ${source.y - 39} C ${source.x - 58} ${source.y - 105}, ${source.x + 58} ${source.y - 105}, ${source.x + 23} ${source.y - 39}`;
    return [
      { type: 'path', id: transition.id, role: 'transition', label: `${transition.source} to ${transition.target} on ${transition.label}`, d, className: activeClass },
      { type: 'text', text: transition.label, x: source.x, y: source.y - 92, className: 'automata-label' },
    ];
  }
  const bend = index % 2 ? 62 : -62;
  const d = curve(source, target, bend);
  return [
    { type: 'path', id: transition.id, role: 'transition', label: `${transition.source} to ${transition.target} on ${transition.label}`, d, className: activeClass },
    { type: 'text', text: transition.label, x: (source.x + target.x) / 2, y: (source.y + target.y) / 2 + bend * .62, className: 'automata-label' },
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
    snapshot.transitions.forEach((transition, index) => elements.push(...transitionElements(transition, positions, index)));
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
