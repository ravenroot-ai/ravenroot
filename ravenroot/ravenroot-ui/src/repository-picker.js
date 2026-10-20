import { uiText } from './ui-text.js';

/** A paginated inventory remains explicitly incomplete until its final page is loaded. */
export function chooseRepositoryDocument(client) {
  const origin = document.activeElement;
  const dialog = document.createElement('dialog');
  dialog.className = 'repository-picker';
  dialog.setAttribute('aria-labelledby', 'repository-picker-title');
  const heading = document.createElement('h2');
  heading.id = 'repository-picker-title';
  heading.textContent = uiText('repository.picker.title');
  const search = document.createElement('input');
  search.type = 'search';
  search.setAttribute('aria-label', uiText('repository.picker.search'));
  search.placeholder = uiText('repository.picker.search');
  const filter = document.createElement('select');
  filter.setAttribute('aria-label', uiText('repository.picker.filter'));
  for (const value of ['all', 'draft', 'release']) {
    const option = document.createElement('option');
    option.value = value; option.textContent = uiText(`repository.picker.${value}`);
    filter.append(option);
  }
  const status = document.createElement('p');
  status.setAttribute('role', 'status');
  const list = document.createElement('div');
  list.className = 'repository-picker-list';
  const more = document.createElement('button');
  more.type = 'button';
  const cancel = document.createElement('button');
  cancel.type = 'button'; cancel.textContent = uiText('repository.picker.cancel');
  dialog.append(heading, search, filter, status, list, more, cancel);
  document.body.append(dialog);
  let items = [], cursor = '', complete = false, loading = false, failed = false;
  let resolve;
  const result = new Promise(done => { resolve = done; });
  const finish = choice => { dialog.close(); dialog.remove(); origin?.focus(); resolve(choice); };
  dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); });
  cancel.addEventListener('click', () => finish(null));
  function render() {
    list.replaceChildren();
    const query = search.value.toLocaleLowerCase();
    for (const item of items) {
      if (!`${item.documentId} ${item.displayPath}`.toLocaleLowerCase().includes(query)) continue;
      for (const source of ['draft', 'release']) {
        if (filter.value !== 'all' && filter.value !== source) continue;
        if (source === 'draft' && item.draftDeleted || source === 'release' && !item.released) continue;
        const button = document.createElement('button'); button.type = 'button';
        button.textContent = uiText(source === 'draft' ? 'repository.picker.draftItem' : 'repository.picker.releaseItem', {
          path: item.displayPath || item.documentId,
          version: source === 'draft' ? item.releaseVersion : item.releasedVersion,
          released: item.releasedVersion || '—',
          publication: uiText(item.published ? 'repository.picker.published' : 'repository.picker.pending'),
        });
        button.addEventListener('click', () => finish({ documentId: item.documentId, source }));
        list.append(button);
      }
    }
    status.textContent = uiText(loading ? 'repository.picker.loading' : failed ? 'repository.picker.failed'
      : !complete ? 'repository.picker.partial' : !items.length ? 'repository.picker.empty'
        : !list.childElementCount ? 'repository.picker.noMatch' : 'repository.picker.complete');
    more.hidden = complete;
    more.disabled = loading;
    more.textContent = uiText(failed ? 'repository.picker.retry' : 'repository.picker.more');
  }
  async function load() {
    if (loading) return;
    loading = true; failed = false; render();
    try {
      const page = await client.list(cursor);
      if (!Array.isArray(page.items) || typeof page.nextCursor !== 'string'
          || page.nextCursor && page.nextCursor === cursor) throw new Error('Invalid inventory page');
      const byId = new Map(items.map(item => [item.documentId, item]));
      page.items.forEach(item => byId.set(item.documentId, item));
      items = [...byId.values()]; cursor = page.nextCursor; complete = !cursor;
    } catch { failed = true; }
    finally { loading = false; render(); }
  }
  search.addEventListener('input', render); filter.addEventListener('change', render);
  more.addEventListener('click', load);
  dialog.showModal(); search.focus(); void load();
  return result;
}

export function managedRepositoryLaunch(search) {
  const params = new URLSearchParams(search);
  if (!params.has('repositoryDocument') && !params.has('source')) return null;
  const documentId = params.get('repositoryDocument');
  const source = params.get('source') || 'draft';
  if (!documentId || params.has('file') || params.getAll('repositoryDocument').length !== 1
      || params.getAll('source').length > 1 || !['draft', 'release'].includes(source)
      || params.has('source') && !params.get('source')) throw new Error(uiText('repository.launch.invalid'));
  return Object.freeze({ documentId, source });
}
