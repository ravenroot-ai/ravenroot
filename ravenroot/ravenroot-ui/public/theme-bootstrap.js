(() => {
  const root = document.documentElement;
  let persisted = null;
  try {
    const value = localStorage.getItem('ravenroot.ui.theme');
    if (value === 'dark' || value === 'light') persisted = value;
  } catch { /* Browser storage is optional. */ }
  const system = typeof matchMedia === 'function'
    ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    : null;
  const theme = persisted || system || 'dark';
  root.dataset.theme = theme;
  root.style.colorScheme = theme;
})();
