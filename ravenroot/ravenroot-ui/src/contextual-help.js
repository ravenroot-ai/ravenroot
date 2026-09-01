const controllerByDocument = new WeakMap();
const VIEWPORT_MARGIN = 8;
const POPOVER_GAP = 8;

const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max));

function intersectionArea(left, right) {
  if (!left || !right) return 0;
  return Math.max(0, Math.min(left.right, right.right) - Math.max(left.left, right.left))
    * Math.max(0, Math.min(left.bottom, right.bottom) - Math.max(left.top, right.top));
}

/**
 * Places long-form help beside its trigger while keeping the whole surface in the viewport.
 * Inspector help prefers the canvas-facing side of the owning panel so it does not cover the
 * field the author is reading. The remaining candidates make the same helper usable elsewhere.
 */
export function placeContextualHelp(anchor, popover, viewport, {
  ownerRect = null,
  ownerSide = null,
  margin = VIEWPORT_MARGIN,
  gap = POPOVER_GAP,
} = {}) {
  if (!anchor || !popover || !viewport || popover.width <= 0 || popover.height <= 0) return null;
  const view = {
    left: viewport.left ?? 0,
    top: viewport.top ?? 0,
    right: viewport.right ?? ((viewport.left ?? 0) + viewport.width),
    bottom: viewport.bottom ?? ((viewport.top ?? 0) + viewport.height),
  };
  const natural = {
    left: [anchor.left - popover.width - gap, anchor.top + (anchor.height - popover.height) / 2],
    right: [anchor.right + gap, anchor.top + (anchor.height - popover.height) / 2],
    bottom: [anchor.left + (anchor.width - popover.width) / 2, anchor.bottom + gap],
    top: [anchor.left + (anchor.width - popover.width) / 2, anchor.top - popover.height - gap],
  };
  if (ownerRect && ownerSide === 'left') {
    natural.left = [ownerRect.left - popover.width - gap,
      anchor.top + (anchor.height - popover.height) / 2];
  } else if (ownerRect && ownerSide === 'right') {
    natural.right = [ownerRect.right + gap,
      anchor.top + (anchor.height - popover.height) / 2];
  }
  const preferred = ownerSide && natural[ownerSide]
    ? [ownerSide, 'bottom', 'top', ownerSide === 'left' ? 'right' : 'left']
    : ['bottom', 'top', 'left', 'right'];
  const candidates = preferred.map((placement, order) => {
    const [naturalLeft, naturalTop] = natural[placement];
    const left = clamp(naturalLeft, view.left + margin, view.right - margin - popover.width);
    const top = clamp(naturalTop, view.top + margin, view.bottom - margin - popover.height);
    const box = {
      left, top, width: popover.width, height: popover.height,
      right: left + popover.width, bottom: top + popover.height,
    };
    const overflow = Math.max(0, view.left + margin - naturalLeft)
      + Math.max(0, naturalLeft + popover.width - (view.right - margin))
      + Math.max(0, view.top + margin - naturalTop)
      + Math.max(0, naturalTop + popover.height - (view.bottom - margin));
    return {
      placement, left, top, order, overflow,
      triggerOverlap: intersectionArea(box, anchor),
      ownerOverlap: intersectionArea(box, ownerRect),
    };
  });
  candidates.sort((left, right) => (
    (left.triggerOverlap > 0) - (right.triggerOverlap > 0)
    || left.ownerOverlap - right.ownerOverlap
    || left.overflow - right.overflow
    || left.order - right.order
  ));
  const chosen = candidates[0];
  return { placement: chosen.placement, left: chosen.left, top: chosen.top };
}

export function contextualHelpDescriptor(element) {
  const HTMLElement = element?.ownerDocument?.defaultView?.HTMLElement;
  if (!HTMLElement || !(element instanceof HTMLElement)) return null;
  const title = String(element.dataset.contextualHelpTitle || '').trim();
  const content = String(element.dataset.contextualHelp || '').trim();
  if (!title || !content || element.hidden || element.closest('[hidden]')) return null;
  return { element, title, content };
}

/**
 * One delegated controller owns one non-modal help surface per document. Pointer activation keeps
 * the current editor control focused; keyboard activation moves focus into the help and Escape
 * returns it to the opener. Outside pointerdown dismisses without cancelling the intended action.
 */
export function createContextualHelp({
  root = document,
  popover = null,
  window: suppliedWindow = root.defaultView || globalThis.window,
} = {}) {
  const document = root.nodeType === 9 ? root : root.ownerDocument;
  if (!document) throw new TypeError('A document-backed root is required for contextual help.');
  controllerByDocument.get(document)?.destroy();

  const surface = popover || document.getElementById('contextual-help-popover');
  if (!surface) throw new TypeError('The contextual help popover is required.');
  const heading = surface.querySelector('[data-contextual-help-heading]');
  const body = surface.querySelector('[data-contextual-help-body]');
  const close = surface.querySelector('[data-contextual-help-close]');
  if (!heading || !body || !close) throw new TypeError('The contextual help popover is incomplete.');

  let opener = null;
  let keyboardOwnedFocus = false;
  let destroyed = false;

  const position = () => {
    if (!opener?.isConnected || surface.hidden) return false;
    const viewport = {
      left: 0,
      top: 0,
      width: suppliedWindow?.innerWidth || document.documentElement.clientWidth,
      height: suppliedWindow?.innerHeight || document.documentElement.clientHeight,
    };
    viewport.right = viewport.width;
    viewport.bottom = viewport.height;
    const owner = opener.closest('[data-tooltip-owner], .panel');
    const zone = owner?.dataset.panelZone;
    const ownerSide = zone === 'right' ? 'left' : zone === 'left' ? 'right' : null;
    const placement = placeContextualHelp(
      opener.getBoundingClientRect(), surface.getBoundingClientRect(), viewport,
      { ownerRect: owner?.getBoundingClientRect(), ownerSide },
    );
    if (!placement) return false;
    surface.style.left = `${Math.round(placement.left)}px`;
    surface.style.top = `${Math.round(placement.top)}px`;
    surface.dataset.placement = placement.placement;
    return true;
  };

  const dismiss = ({ restoreFocus = false } = {}) => {
    if (!opener && surface.hidden) return false;
    const previous = opener;
    previous?.setAttribute('aria-expanded', 'false');
    opener = null;
    surface.hidden = true;
    surface.removeAttribute('data-placement');
    keyboardOwnedFocus = false;
    if (restoreFocus && previous?.isConnected) previous.focus({ preventScroll: true });
    return true;
  };

  const open = (trigger, { moveFocus = false } = {}) => {
    const descriptor = contextualHelpDescriptor(trigger);
    if (!descriptor || destroyed) return false;
    if (opener && opener !== trigger) opener.setAttribute('aria-expanded', 'false');
    opener = trigger;
    keyboardOwnedFocus = moveFocus;
    heading.textContent = descriptor.title;
    body.textContent = descriptor.content;
    trigger.setAttribute('aria-expanded', 'true');
    surface.hidden = false;
    position();
    if (moveFocus) surface.focus({ preventScroll: true });
    return true;
  };

  const triggerFor = target => target?.closest?.('[data-contextual-help]');

  const onPointerDown = event => {
    const trigger = triggerFor(event.target);
    if (trigger) {
      // Keep a text control focused while its adjacent help is read. The ensuing click still opens
      // the real button; only the pointer's browser-default focus transfer is suppressed.
      event.preventDefault();
      return;
    }
    if (!surface.hidden && !surface.contains(event.target)) dismiss({ restoreFocus: false });
  };

  const onClick = event => {
    const trigger = triggerFor(event.target);
    if (!trigger) return;
    event.preventDefault();
    if (trigger === opener && !surface.hidden) dismiss({ restoreFocus: event.detail === 0 });
    else open(trigger, { moveFocus: event.detail === 0 });
  };

  const onKeyDown = event => {
    if (event.key !== 'Escape' || surface.hidden) return;
    const restoreFocus = keyboardOwnedFocus || surface.contains(document.activeElement);
    if (dismiss({ restoreFocus })) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  };

  const onClose = () => dismiss({ restoreFocus: true });
  const onViewportChange = () => {
    if (!opener?.isConnected) dismiss();
    else position();
  };

  root.addEventListener('pointerdown', onPointerDown, true);
  root.addEventListener('click', onClick);
  root.addEventListener('keydown', onKeyDown, true);
  close.addEventListener('click', onClose);
  suppliedWindow?.addEventListener?.('resize', onViewportChange);
  suppliedWindow?.addEventListener?.('scroll', onViewportChange, true);

  const controller = {
    open,
    dismiss,
    activeTrigger: () => opener,
    refresh: () => opener?.isConnected ? position() : dismiss(),
    destroy: () => {
      if (destroyed) return;
      destroyed = true;
      dismiss();
      root.removeEventListener('pointerdown', onPointerDown, true);
      root.removeEventListener('click', onClick);
      root.removeEventListener('keydown', onKeyDown, true);
      close.removeEventListener('click', onClose);
      suppliedWindow?.removeEventListener?.('resize', onViewportChange);
      suppliedWindow?.removeEventListener?.('scroll', onViewportChange, true);
      if (controllerByDocument.get(document) === controller) controllerByDocument.delete(document);
    },
  };
  controllerByDocument.set(document, controller);
  return controller;
}
