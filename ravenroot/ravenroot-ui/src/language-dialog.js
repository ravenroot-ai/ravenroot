import { productError } from './product-error.js';
import { MAX_UI_CATALOG_BYTES } from './ui-localization.js';

function element(document_, tag, attributes = {}) {
  const node = document_.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (name === 'className') node.className = value;
    else if (name === 'textContent') node.textContent = value;
    else node.setAttribute(name, value);
  }
  return node;
}

function localeName(locale, displayLocale) {
  try {
    return new Intl.DisplayNames([displayLocale], { type: 'language' }).of(locale) || locale;
  } catch { return locale; }
}

export async function readUiCatalogFile(file) {
  if (file.size > MAX_UI_CATALOG_BYTES) throw productError("workspace.error.language.dialog.ui.catalog.file.is.too.large", new RangeError('UI catalog file is too large.'));
  return JSON.parse(await file.text());
}

export function createLanguageDialog({ document: document_, controller }) {
  if (!document_ || !controller?.localization) throw productError("workspace.error.language.dialog.language.dialog.requires.localization", new TypeError('Language dialog requires localization.'));
  const { localization } = controller;
  const dialog = element(document_, 'dialog', { className: 'language-dialog',
    'aria-labelledby': 'language-dialog-title' });
  const form = element(document_, 'form', { method: 'dialog', className: 'language-dialog-form' });
  const title = element(document_, 'h2', { id: 'language-dialog-title' });
  const description = element(document_, 'p');
  const label = element(document_, 'label', { for: 'language-selection' });
  const select = element(document_, 'select', { id: 'language-selection' });
  const source = element(document_, 'p', { className: 'language-current-source' });
  const status = element(document_, 'p', { className: 'language-status', role: 'status',
    'aria-live': 'polite' });
  const file = element(document_, 'input', { type: 'file', accept: 'application/json,.json',
    className: 'visually-hidden' });
  const actions = element(document_, 'div', { className: 'language-actions' });
  const importButton = element(document_, 'button', { type: 'button', className: 'btn' });
  const removeButton = element(document_, 'button', { type: 'button', className: 'btn danger' });
  const closeButton = element(document_, 'button', { type: 'submit', className: 'btn primary' });
  actions.append(importButton, removeButton, closeButton);
  form.append(title, description, label, select, source, status, file, actions);
  dialog.append(form);
  document_.body.append(dialog);

  const render = () => {
    const t = localization.text;
    title.textContent = t('language.title');
    description.textContent = t('language.description');
    label.textContent = t('language.selection.label');
    importButton.textContent = t('language.import');
    removeButton.textContent = t('language.remove');
    closeButton.textContent = t('language.close');
    file.setAttribute('aria-label', t('language.catalogFile.label'));
    const resolved = localization.locale;
    const preference = controller.preferenceLocale;
    select.replaceChildren();
    const automatic = element(document_, 'option', { value: '', textContent: t('language.automatic') });
    select.append(automatic);
    const selectableLocales = [...localization.availableLocales()];
    if (preference && !selectableLocales.includes(preference)) selectableLocales.push(preference);
    for (const locale of selectableLocales) {
      const option = element(document_, 'option', { value: locale,
        textContent: `${localeName(locale, localization.locale)} — ${locale}` });
      select.append(option);
    }
    select.value = preference ?? '';
    const sourceName = controller.sourceFor(resolved);
    source.textContent = t('language.currentResolution', {
      locale: resolved,
      source: t('language.currentSource', {
        source: t(`language.source.${sourceName}`),
      }),
    });
    removeButton.disabled = sourceName !== 'imported';
  };

  select.addEventListener('change', () => {
    if (select.value) controller.chooseLocale(select.value);
    else controller.useAutomaticLocale();
    render();
  });
  importButton.addEventListener('click', () => file.click());
  file.addEventListener('change', () => {
    const selectedFile = file.files?.[0];
    file.value = '';
    if (!selectedFile) return;
    void readUiCatalogFile(selectedFile).then(value => controller.importCatalog(value)).then(locale => {
      controller.chooseLocale(locale);
      status.textContent = localization.text('language.import.success', { locale });
      render();
    }).catch(failure => {
      status.textContent = localization.text('language.import.failure');
      controller.reportDiagnostic?.('CATALOG_IMPORT_FAILED', failure);
    });
  });
  removeButton.addEventListener('click', () => {
    const locale = localization.locale;
    void controller.removeImportedCatalog(locale).then(() => {
      status.textContent = '';
      render();
    }).catch(failure => {
      status.textContent = localization.text('language.remove.failure');
      controller.reportDiagnostic?.('CATALOG_REMOVE_FAILED', failure);
    });
  });
  const unsubscribe = localization.subscribe(render);
  render();

  return Object.freeze({
    open() { status.textContent = ''; render(); dialog.showModal(); select.focus(); },
    destroy() { unsubscribe(); dialog.remove(); },
  });
}
