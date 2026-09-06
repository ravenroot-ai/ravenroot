const ease = value => value < .5 ? 2 * value * value : 1 - ((-2 * value + 2) ** 2) / 2;

/** One replaceable animation owner; frame values never contain a viewport or canonical graph. */
export function createVisualGroupTransition({
  paint, isCurrent = () => true, duration = 240,
  requestFrame = callback => requestAnimationFrame(callback),
  cancelFrame = handle => cancelAnimationFrame(handle),
  now = () => performance.now(),
  reducedMotion = () => globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true,
} = {}) {
  let current = new Map();
  let target = new Map();
  let frame = null;
  let generation = 0;
  let active = false;
  let destroyed = false;
  const stopFrame = () => { if (frame !== null) cancelFrame(frame); frame = null; };
  const publish = final => { if (!destroyed && isCurrent()) paint(new Map(current), { final }); };
  function finish() {
    stopFrame();
    generation += 1;
    if (destroyed) return;
    current = new Map(target);
    active = false;
    publish(true);
  }
  function request(values, { animate = true } = {}) {
    stopFrame();
    const token = ++generation;
    target = new Map(values);
    if (destroyed || !isCurrent()) { active = false; return; }
    const from = new Map([...target].map(([id, value]) => [id, current.get(id) ?? value]));
    if (!animate || reducedMotion() || [...target].every(([id, value]) => from.get(id) === value)) {
      finish(); return;
    }
    const started = now();
    active = true;
    const tick = timestamp => {
      if (destroyed || token !== generation) return;
      if (!isCurrent()) { active = false; frame = null; return; }
      const elapsed = Math.max(0, timestamp - started);
      const amount = ease(Math.min(1, elapsed / Math.max(1, duration)));
      current = new Map([...target].map(([id, value]) => [id, from.get(id) + (value - from.get(id)) * amount]));
      publish(amount === 1);
      if (amount === 1) { active = false; frame = null; }
      else frame = requestFrame(tick);
    };
    current = from;
    publish(false);
    frame = requestFrame(tick);
  }
  return {
    request, finish,
    seed(values) { stopFrame(); generation += 1; active = false; current = new Map(values); target = new Map(values); },
    cancel() { stopFrame(); generation += 1; active = false; },
    destroy() { stopFrame(); generation += 1; active = false; destroyed = true; },
    get active() { return active; },
    get values() { return new Map(current); },
  };
}
