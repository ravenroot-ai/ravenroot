function requireMethod(target, name) {
  if (typeof target?.[name] !== 'function') {
    throw new TypeError(`Viewer renderer requires ${name}().`);
  }
  return target[name].bind(target);
}

/**
 * The renderer-neutral surface owned by the read-only viewer.
 *
 * Keeping this contract smaller than Cytoscape prevents the embed entry from
 * acquiring editor operations such as node mutation, layout persistence, or
 * runtime controls through a renderer handle.
 */
export function createReadOnlyRendererAdapter(renderer) {
  const render = requireMethod(renderer, 'render');
  const fit = requireMethod(renderer, 'fit');
  const zoomBy = requireMethod(renderer, 'zoomBy');
  const panBy = requireMethod(renderer, 'panBy');
  const destroy = requireMethod(renderer, 'destroy');

  return Object.freeze({
    render: snapshot => render(snapshot),
    fit: padding => fit(padding),
    zoomBy: factor => zoomBy(factor),
    panBy: delta => panBy(delta),
    destroy: () => destroy(),
  });
}

/** Adapt a Cytoscape instance without exposing it to viewer-core consumers. */
export function createCytoscapeReadOnlyRendererAdapter(instance) {
  if (instance == null) throw new TypeError('A Cytoscape instance is required.');

  const requestFrame = typeof globalThis.requestAnimationFrame === 'function'
    ? globalThis.requestAnimationFrame.bind(globalThis)
    : callback => setTimeout(callback, 0);
  const cancelFrame = typeof globalThis.cancelAnimationFrame === 'function'
    ? globalThis.cancelAnimationFrame.bind(globalThis)
    : handle => clearTimeout(handle);
  const operations = new Set();
  let destroyed = false;
  let generation = 0;

  const abortError = message => new DOMException(message, 'AbortError');
  const cancelOperation = (operation, failure) => {
    if (operation.settled) return;
    operation.settled = true;
    for (const handle of operation.frames) cancelFrame(handle);
    operation.frames.clear();
    operations.delete(operation);
    operation.reject(failure);
  };
  const cancelOperations = failure => {
    for (const operation of [...operations]) cancelOperation(operation, failure);
  };
  const scheduleFrame = (operation, callback) => {
    let handle;
    handle = requestFrame(() => {
      operation.frames.delete(handle);
      if (destroyed || operation.settled || operation.generation !== generation) return;
      callback();
    });
    operation.frames.add(handle);
  };

  return createReadOnlyRendererAdapter({
    render(snapshot) {
      if (destroyed) return Promise.reject(new Error('Viewer renderer is destroyed.'));
      generation += 1;
      cancelOperations(abortError('Viewer render superseded.'));
      requireMethod(instance, 'elements')().remove();
      // Cytoscape owns and mutates model-position objects during layout. Keep the core snapshot
      // deeply immutable by handing the adapter a renderer-local copy.
      const elements = snapshot.elements.map(element => ({
        data: { ...element.data },
        ...(element.position ? { position: { ...element.position } } : {}),
      }));
      requireMethod(instance, 'add')(elements);
      const positioned = snapshot.nodes.length > 0 && snapshot.nodes.every(node => node.layout !== null);
      const layout = requireMethod(instance, 'layout')({
        name: positioned ? 'preset' : 'grid',
        fit: false,
        padding: 60,
        avoidOverlap: true,
      });
      layout?.run?.();
      return new Promise((resolve, reject) => {
        const operation = {
          generation,
          frames: new Set(),
          reject,
          settled: false,
        };
        operations.add(operation);
        scheduleFrame(operation, () => scheduleFrame(operation, () => {
          if (operation.settled) return;
          operation.settled = true;
          operations.delete(operation);
          resolve();
        }));
      });
    },
    fit(padding = 60) {
      requireMethod(instance, 'fit')(padding);
    },
    zoomBy(factor) {
      const current = requireMethod(instance, 'zoom')();
      requireMethod(instance, 'zoom')({
        level: current * factor,
        renderedPosition: {
          x: requireMethod(instance, 'width')() / 2,
          y: requireMethod(instance, 'height')() / 2,
        },
      });
    },
    panBy(delta) {
      requireMethod(instance, 'panBy')({ x: Number(delta?.x) || 0, y: Number(delta?.y) || 0 });
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      generation += 1;
      cancelOperations(abortError('Viewer renderer destroyed.'));
      requireMethod(instance, 'destroy')();
    },
  });
}
