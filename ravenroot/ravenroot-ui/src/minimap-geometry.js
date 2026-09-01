const finite = (value, fallback = 0) => Number.isFinite(value) ? value : fallback;

export function normalizeBounds(value = {}) {
  const x1 = finite(Math.min(value.x1, value.x2));
  const y1 = finite(Math.min(value.y1, value.y2));
  const x2 = Math.max(x1 + 1e-9, finite(Math.max(value.x1, value.x2), x1 + 1));
  const y2 = Math.max(y1 + 1e-9, finite(Math.max(value.y1, value.y2), y1 + 1));
  return {
    x1, y1, x2, y2,
    w: Math.max(1e-9, x2 - x1), h: Math.max(1e-9, y2 - y1),
  };
}

export function unionBounds(...values) {
  const bounds = values.filter(Boolean).map(normalizeBounds);
  if (!bounds.length) return normalizeBounds({ x1: 0, y1: 0, x2: 1, y2: 1 });
  return normalizeBounds({
    x1: Math.min(...bounds.map(item => item.x1)),
    y1: Math.min(...bounds.map(item => item.y1)),
    x2: Math.max(...bounds.map(item => item.x2)),
    y2: Math.max(...bounds.map(item => item.y2)),
  });
}

export function projectMinimap({ contentBounds, visibleBounds, width, height,
  padding = 6, header = 16, minimumViewport = 12 } = {}) {
  const content = normalizeBounds(contentBounds);
  const visible = normalizeBounds(visibleBounds);
  const world = unionBounds(content, visible);
  const map = {
    x: padding, y: header,
    width: Math.max(1, finite(width, 1) - padding * 2),
    height: Math.max(1, finite(height, 1) - header - padding),
  };
  const scale = Math.max(1e-12, Math.min(map.width / world.w, map.height / world.h));
  const offsetX = map.x + (map.width - world.w * scale) / 2 - world.x1 * scale;
  const offsetY = map.y + (map.height - world.h * scale) / 2 - world.y1 * scale;
  const raw = {
    x: visible.x1 * scale + offsetX,
    y: visible.y1 * scale + offsetY,
    width: visible.w * scale,
    height: visible.h * scale,
  };
  const viewport = {
    width: Math.min(map.width, Math.max(minimumViewport, raw.width)),
    height: Math.min(map.height, Math.max(minimumViewport, raw.height)),
  };
  viewport.x = Math.min(map.x + map.width - viewport.width,
    Math.max(map.x, raw.x - (viewport.width - raw.width) / 2));
  viewport.y = Math.min(map.y + map.height - viewport.height,
    Math.max(map.y, raw.y - (viewport.height - raw.height) / 2));
  return {
    contentBounds: content, visibleBounds: visible, worldBounds: world, map, viewport,
    scale, offsetX, offsetY,
  };
}

export function minimapToWorld(projection, point) {
  return {
    x: (finite(point?.x) - projection.offsetX) / projection.scale,
    y: (finite(point?.y) - projection.offsetY) / projection.scale,
  };
}

export function clampViewportCenter(contentBounds, visibleBounds, center) {
  const content = normalizeBounds(contentBounds);
  const visible = normalizeBounds(visibleBounds);
  const halfW = visible.w / 2;
  const halfH = visible.h / 2;
  const clampAxis = (value, min, max, half) => max - min <= half * 2
    ? (min + max) / 2 : Math.min(max - half, Math.max(min + half, finite(value)));
  return {
    x: clampAxis(center?.x, content.x1, content.x2, halfW),
    y: clampAxis(center?.y, content.y1, content.y2, halfH),
  };
}
