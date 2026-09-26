/**
 * Keeps the embedded viewer's renderer mounted while its containing surface changes size.
 * Native fullscreen is preferred, but a same-document fixed viewport is a deterministic fallback
 * for sandboxed iframes and browsers that deny or omit the Fullscreen API.
 */
export function createEmbedMaximizeController(root, button, {
  document = root?.ownerDocument,
  window = document?.defaultView,
  onResize = () => {},
} = {}) {
  if (!root || !button || !document || !window) throw new TypeError('Embed maximize controls are required.');
  let maximized = false;
  let nativeFullscreen = false;
  let returnFocus = null;
  let destroyed = false;

  const resize = () => window.requestAnimationFrame?.(() => onResize()) ?? onResize();
  const publish = value => {
    maximized = value;
    root.classList.toggle('embed-viewer--maximized', value);
    document.documentElement.classList.toggle('embed-viewer-document--maximized', value);
    button.setAttribute('aria-pressed', String(value));
    button.setAttribute('aria-label', value ? 'Restore embedded graph' : 'Maximize embedded graph');
    button.title = value ? 'Restore embedded graph' : 'Maximize embedded graph';
    button.dataset.viewerMaximized = String(value);
    resize();
  };
  const restore = ({ focus = true } = {}) => {
    if (!maximized) return;
    nativeFullscreen = false;
    publish(false);
    if (document.fullscreenElement === root && typeof document.exitFullscreen === 'function') {
      Promise.resolve(document.exitFullscreen()).catch(() => {});
    }
    if (focus) (returnFocus?.isConnected ? returnFocus : button).focus?.({ preventScroll: true });
    returnFocus = null;
  };
  const maximize = () => {
    if (maximized) return;
    returnFocus = document.activeElement;
    publish(true);
    button.focus?.({ preventScroll: true });
    if (typeof root.requestFullscreen !== 'function') return;
    Promise.resolve(root.requestFullscreen()).then(() => {
      if (!destroyed && maximized && document.fullscreenElement === root) nativeFullscreen = true;
    }).catch(() => {
      nativeFullscreen = false;
      // The fixed-viewport fallback published above remains active.
    });
  };
  const toggle = () => maximized ? restore() : maximize();
  const onFullscreenChange = () => {
    if (nativeFullscreen && document.fullscreenElement !== root) restore();
  };
  const onKeydown = event => {
    if (maximized && event.key === 'Escape' && !nativeFullscreen) {
      event.preventDefault();
      restore();
    }
  };
  button.addEventListener('click', toggle);
  document.addEventListener('fullscreenchange', onFullscreenChange);
  document.addEventListener('keydown', onKeydown);
  publish(false);

  return Object.freeze({
    get maximized() { return maximized; },
    maximize,
    restore,
    toggle,
    destroy() {
      if (destroyed) return;
      destroyed = true;
      restore({ focus: false });
      button.removeEventListener('click', toggle);
      document.removeEventListener('fullscreenchange', onFullscreenChange);
      document.removeEventListener('keydown', onKeydown);
    },
  });
}
