/** Run application startup exactly once, including when an awaited module dependency settles late. */
export function scheduleApplicationBoot({ document, window, boot }) {
  if (!document || !window || typeof boot !== 'function') {
    throw new TypeError('Application boot requires document, window, and a boot function.');
  }
  let started = false;
  const start = () => {
    if (started) return false;
    started = true;
    boot();
    return true;
  };
  if (document.readyState === 'complete') start();
  else window.addEventListener('load', start, { once: true });
  return start;
}
