/**
 * Framework-free visual tooltips for the panel surface.
 *
 * A control opts in with `data-tooltip`. `data-tooltip-short` is either a
 * boolean marker (show the normal text only while its panel is short) or a
 * short-state string (show that string while short). `data-tooltip-disabled`
 * replaces the normal text while the control is disabled or aria-disabled.
 */

const POINTER_DELAY = 450;
const VIEWPORT_MARGIN = 8;
const TOOLTIP_GAP = 8;
const controllerByDocument = new WeakMap();

function isElement(value) {
  return value?.nodeType === 1;
}

function rect(value) {
  if (!value) return null;
  const left = Number(value.left) || 0;
  const top = Number(value.top) || 0;
  const width = Math.max(0, Number(value.width) || Number(value.right) - left || 0);
  const height = Math.max(0, Number(value.height) || Number(value.bottom) - top || 0);
  return { left, top, width, height, right: left + width, bottom: top + height };
}

function intersectionArea(a, b) {
  if (!a || !b) return 0;
  const width = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
  const height = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
  return width * height;
}

function clamp(value, lower, upper) {
  return Math.max(lower, Math.min(value, Math.max(lower, upper)));
}

function isDisabled(element) {
  return element.disabled === true || element.getAttribute('aria-disabled') === 'true';
}

function isHidden(element) {
  return !element.isConnected
    || element.closest('[hidden], [aria-hidden="true"], [inert]') !== null;
}

function listScopes(scope, document) {
  if (!scope) return [document.body].filter(Boolean);
  if (typeof scope === 'string') return [...document.querySelectorAll(scope)];
  if (isElement(scope) || scope?.nodeType === 9) return [scope];
  return [...scope].filter(isElement);
}

function isInScope(element, scopes) {
  return scopes.some(scope => scope === element || scope?.contains?.(element));
}

function textForShortState(element) {
  const value = element.getAttribute('data-tooltip-short');
  if (value === null || !element.closest('.panel--short')) return null;
  return value && value !== 'true' ? value : element.getAttribute('data-tooltip');
}

/**
 * Returns the tooltip text for an opted-in element, otherwise null.
 * Exported so inventory tests can use exactly the same applicability rule as
 * the runtime delegate.
 */
export function tooltipDescriptor(element, { scopes } = {}) {
  if (!isElement(element) || isHidden(element)) return null;
  if (scopes?.length && !isInScope(element, scopes)) return null;

  const shortText = textForShortState(element);
  const disabledText = isDisabled(element) ? element.getAttribute('data-tooltip-disabled') : null;
  const normalText = element.getAttribute('data-tooltip');
  const text = disabledText || shortText || (element.hasAttribute('data-tooltip-short') ? null : normalText);

  return typeof text === 'string' && text.trim()
    ? { element, text: text.trim() }
    : null;
}

/**
 * Selects one of the four cardinal placements after viewport clamping. A
 * caller may provide the owning panel rect; candidates that cover it are
 * deprioritised, while trigger overlap is always avoided whenever possible.
 */
export function placeVisualTooltip(anchorRect, tooltipRect, viewport, {
  gap = TOOLTIP_GAP,
  margin = VIEWPORT_MARGIN,
  ownerRect = null,
  ownerSide = null,
} = {}) {
  const anchor = rect(anchorRect);
  const tip = rect(tooltipRect);
  const view = rect(viewport) || { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 };
  if (!anchor || !tip) return null;

  const owner = rect(ownerRect);
  const outsideOwner = owner && {
    top: ['top', anchor.left + (anchor.width - tip.width) / 2, owner.top - tip.height - gap],
    bottom: ['bottom', anchor.left + (anchor.width - tip.width) / 2, owner.bottom + gap],
    right: ['right', owner.right + gap, anchor.top + (anchor.height - tip.height) / 2],
    left: ['left', owner.left - tip.width - gap, anchor.top + (anchor.height - tip.height) / 2],
  }[ownerSide];
  const candidates = [
    ...(outsideOwner ? [outsideOwner] : []),
    ['top', anchor.left + (anchor.width - tip.width) / 2, anchor.top - tip.height - gap],
    ['bottom', anchor.left + (anchor.width - tip.width) / 2, anchor.bottom + gap],
    ['right', anchor.right + gap, anchor.top + (anchor.height - tip.height) / 2],
    ['left', anchor.left - tip.width - gap, anchor.top + (anchor.height - tip.height) / 2],
  ].map(([placement, naturalLeft, naturalTop], order) => {
    const left = clamp(naturalLeft, view.left + margin, view.right - margin - tip.width);
    const top = clamp(naturalTop, view.top + margin, view.bottom - margin - tip.height);
    const candidate = { left, top, width: tip.width, height: tip.height, right: left + tip.width, bottom: top + tip.height };
    const overflow = Math.max(0, view.left + margin - naturalLeft)
      + Math.max(0, naturalLeft + tip.width - (view.right - margin))
      + Math.max(0, view.top + margin - naturalTop)
      + Math.max(0, naturalTop + tip.height - (view.bottom - margin));
    return {
      placement, left, top, order, overflow,
      triggerOverlap: intersectionArea(candidate, anchor),
      ownerOverlap: intersectionArea(candidate, owner),
    };
  });

  candidates.sort((a, b) => (
    (a.triggerOverlap > 0) - (b.triggerOverlap > 0)
    || a.ownerOverlap - b.ownerOverlap
    || a.overflow - b.overflow
    || a.order - b.order
  ));
  const chosen = candidates[0];
  return { placement: chosen.placement, left: chosen.left, top: chosen.top };
}

function addDescribedBy(element, id) {
  const values = (element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
  if (!values.includes(id)) values.push(id);
  element.setAttribute('aria-describedby', values.join(' '));
}

function removeDescribedBy(element, id) {
  const values = (element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
  const remaining = values.filter(value => value !== id);
  if (remaining.length) element.setAttribute('aria-describedby', remaining.join(' '));
  else element.removeAttribute('aria-describedby');
}

/**
 * Creates the single delegated tooltip controller for a document. Calling it
 * again for that document replaces the previous controller, which is useful
 * for hot reload and keeps listener ownership unambiguous.
 */
export function createVisualTooltip({
  root = document,
  tooltip = null,
  scope = null,
  window: suppliedWindow = root.defaultView || globalThis.window,
  pointerDelay = POINTER_DELAY,
} = {}) {
  const document = root.nodeType === 9 ? root : root.ownerDocument;
  if (!document) throw new TypeError('A document-backed root is required for visual tooltips.');
  controllerByDocument.get(document)?.destroy();

  const scopes = listScopes(scope, document);
  let createdTooltip = false;
  const tooltipElement = tooltip || document.getElementById('visual-tooltip') || (() => {
    createdTooltip = true;
    const element = document.createElement('div');
    element.id = 'visual-tooltip';
    document.body.append(element);
    return element;
  })();
  if (!tooltipElement.id) tooltipElement.id = 'visual-tooltip';
  tooltipElement.setAttribute('role', 'tooltip');
  tooltipElement.hidden = true;

  let current = null;
  let pointerAnchor = null;
  let focusAnchor = null;
  let tipHovered = false;
  let showTimer = null;
  let dismissTimer = null;
  let destroyed = false;
  const media = suppliedWindow?.matchMedia?.('(prefers-reduced-motion: reduce)');

  const setReducedMotion = () => {
    tooltipElement.dataset.reducedMotion = media?.matches ? 'true' : 'false';
  };
  setReducedMotion();

  const clearTimers = () => {
    if (showTimer !== null) suppliedWindow.clearTimeout(showTimer);
    if (dismissTimer !== null) suppliedWindow.clearTimeout(dismissTimer);
    showTimer = null;
    dismissTimer = null;
  };

  const dismiss = () => {
    const wasActive = current !== null || showTimer !== null || dismissTimer !== null || !tooltipElement.hidden;
    clearTimers();
    if (current) removeDescribedBy(current.element, tooltipElement.id);
    current = null;
    pointerAnchor = null;
    focusAnchor = null;
    tipHovered = false;
    tooltipElement.hidden = true;
    tooltipElement.classList.remove('is-visible');
    tooltipElement.removeAttribute('data-placement');
    return wasActive;
  };

  const position = element => {
    const viewport = {
      left: 0, top: 0,
      width: suppliedWindow?.innerWidth || document.documentElement.clientWidth,
      height: suppliedWindow?.innerHeight || document.documentElement.clientHeight,
    };
    viewport.right = viewport.left + viewport.width;
    viewport.bottom = viewport.top + viewport.height;
    const owner = element.closest('[data-tooltip-owner], .panel');
    const zone = owner?.dataset.panelZone;
    const ownerSide = zone === 'left' ? 'right' : zone === 'right' ? 'left' : zone === 'bottom' ? 'top' : null;
    const placement = placeVisualTooltip(
      element.getBoundingClientRect(), tooltipElement.getBoundingClientRect(), viewport,
      { ownerRect: owner?.getBoundingClientRect(), ownerSide },
    );
    if (!placement) return;
    tooltipElement.style.left = `${Math.round(placement.left)}px`;
    tooltipElement.style.top = `${Math.round(placement.top)}px`;
    tooltipElement.dataset.placement = placement.placement;
  };

  const show = descriptor => {
    if (destroyed || !descriptor || isHidden(descriptor.element)) return;
    clearTimers();
    if (current && current.element !== descriptor.element) {
      removeDescribedBy(current.element, tooltipElement.id);
      current = null;
    }
    current = descriptor;
    tooltipElement.textContent = descriptor.text;
    tooltipElement.hidden = false;
    tooltipElement.classList.add('is-visible');
    addDescribedBy(descriptor.element, tooltipElement.id);
    position(descriptor.element);
  };

  const candidateFor = target => {
    const element = isElement(target) ? target : target?.parentElement;
    if (!element) return null;
    const candidate = element.closest('[data-tooltip], [data-tooltip-short], [data-tooltip-disabled]');
    return tooltipDescriptor(candidate, { scopes });
  };

  const scheduleShow = descriptor => {
    clearTimers();
    showTimer = suppliedWindow.setTimeout(() => show(descriptor), pointerDelay);
  };

  const scheduleDismiss = () => {
    if (focusAnchor || pointerAnchor || tipHovered) return;
    if (dismissTimer !== null) suppliedWindow.clearTimeout(dismissTimer);
    dismissTimer = suppliedWindow.setTimeout(() => {
      dismissTimer = null;
      if (!focusAnchor && !pointerAnchor && !tipHovered) dismiss();
    }, 0);
  };

  const onPointerOver = event => {
    const descriptor = candidateFor(event.target);
    if (!descriptor) return;
    // Keyboard focus owns the explanatory surface until it moves or blurs. A stationary pointer
    // over a rail item must not overwrite the tooltip for a newly focused panel control.
    if (focusAnchor && focusAnchor !== descriptor.element) return;
    if (pointerAnchor === descriptor.element && descriptor.element.contains(event.relatedTarget)) return;
    if (current?.element !== descriptor.element) dismiss();
    pointerAnchor = descriptor.element;
    scheduleShow(descriptor);
  };
  const onPointerOut = event => {
    const descriptor = candidateFor(event.target);
    if (!descriptor || descriptor.element !== pointerAnchor) return;
    if (descriptor.element.contains(event.relatedTarget)) return;
    pointerAnchor = null;
    if (showTimer !== null) clearTimers();
    scheduleDismiss();
  };
  const onFocusIn = event => {
    const descriptor = candidateFor(event.target);
    if (!descriptor) return;
    focusAnchor = descriptor.element;
    show(descriptor);
  };
  const onFocusOut = event => {
    if (focusAnchor?.contains(event.relatedTarget)) return;
    focusAnchor = null;
    if (!pointerAnchor && !tipHovered) dismiss();
    else scheduleDismiss();
  };
  const onTipPointerOver = () => {
    tipHovered = true;
    if (dismissTimer !== null) suppliedWindow.clearTimeout(dismissTimer);
    dismissTimer = null;
  };
  const onTipPointerOut = event => {
    if (tooltipElement.contains(event.relatedTarget)) return;
    tipHovered = false;
    scheduleDismiss();
  };
  const onKeyDown = event => {
    if (event.key === 'Escape' && dismiss()) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  };

  root.addEventListener('pointerover', onPointerOver);
  root.addEventListener('pointerout', onPointerOut);
  root.addEventListener('focusin', onFocusIn);
  root.addEventListener('focusout', onFocusOut);
  root.addEventListener('keydown', onKeyDown);
  root.addEventListener('pointerdown', dismiss, true);
  tooltipElement.addEventListener('pointerover', onTipPointerOver);
  tooltipElement.addEventListener('pointerout', onTipPointerOut);
  suppliedWindow?.addEventListener?.('scroll', dismiss, true);
  suppliedWindow?.addEventListener?.('resize', dismiss);
  document.addEventListener('visibilitychange', dismiss);
  media?.addEventListener?.('change', setReducedMotion);

  const controller = {
    dismiss,
    refresh: () => {
      const descriptor = current && tooltipDescriptor(current.element, { scopes });
      return descriptor ? show(descriptor) : dismiss();
    },
    destroy: () => {
      if (destroyed) return;
      destroyed = true;
      dismiss();
      root.removeEventListener('pointerover', onPointerOver);
      root.removeEventListener('pointerout', onPointerOut);
      root.removeEventListener('focusin', onFocusIn);
      root.removeEventListener('focusout', onFocusOut);
      root.removeEventListener('keydown', onKeyDown);
      root.removeEventListener('pointerdown', dismiss, true);
      tooltipElement.removeEventListener('pointerover', onTipPointerOver);
      tooltipElement.removeEventListener('pointerout', onTipPointerOut);
      suppliedWindow?.removeEventListener?.('scroll', dismiss, true);
      suppliedWindow?.removeEventListener?.('resize', dismiss);
      document.removeEventListener('visibilitychange', dismiss);
      media?.removeEventListener?.('change', setReducedMotion);
      if (createdTooltip) tooltipElement.remove();
      if (controllerByDocument.get(document) === controller) controllerByDocument.delete(document);
    },
  };
  controllerByDocument.set(document, controller);
  return controller;
}

export const VISUAL_TOOLTIP_POINTER_DELAY = POINTER_DELAY;
