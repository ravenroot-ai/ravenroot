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
  const toolbar = document.createElement('div');
  toolbar.className = 'repository-picker-toolbar';
  toolbar.append(search, filter);
  const footer = document.createElement('footer');
  footer.append(more, cancel);
  const table = document.createElement('table');
  table.setAttribute('aria-label', uiText('repository.picker.title'));
  const head = document.createElement('thead');
  const headers = document.createElement('tr');
  for (const column of ['name', 'source', 'version', 'released', 'publication']) {
    const th = document.createElement('th');
    th.scope = 'col'; th.textContent = uiText(`repository.picker.column.${column}`);
    headers.append(th);
  }
  head.append(headers);
  const body = document.createElement('tbody');
  table.append(head, body); list.append(table);
  list.tabIndex = 0;
  list.setAttribute('aria-label', uiText('repository.picker.results'));
  dialog.append(heading, toolbar, status, list, footer);
  document.body.append(dialog);
  let items = [], cursor = '', complete = false, loading = false, failed = false;
  let closed = false, visibleRows = 0;
  const visited = new Set();
  let resolve;
  const result = new Promise(done => { resolve = done; });
  const finish = choice => { closed = true; observer?.disconnect(); dialog.close(); dialog.remove(); origin?.focus(); resolve(choice); };
  dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); });
  cancel.addEventListener('click', () => finish(null));
  function render() {
    body.replaceChildren();
    visibleRows = 0;
    const query = search.value.trim().toLocaleLowerCase();
    for (const item of items) {
      if (!`${item.documentId} ${item.displayPath}`.toLocaleLowerCase().includes(query)) continue;
      for (const source of ['draft', 'release']) {
        if (filter.value !== 'all' && filter.value !== source) continue;
        if (source === 'draft' && item.draftDeleted || source === 'release' && !item.released) continue;
        const button = document.createElement('button'); button.type = 'button';
        const path = item.displayPath || item.documentId;
        button.textContent = path.split('/').pop();
        button.title = path;
        button.setAttribute('aria-label', uiText(source === 'draft' ? 'repository.picker.draftItem' : 'repository.picker.releaseItem', {
          path, version: source === 'draft' ? item.releaseVersion : item.releasedVersion,
          released: item.releasedVersion || '—',
          publication: uiText(item.published ? 'repository.picker.published' : 'repository.picker.pending'),
        }));
        button.addEventListener('click', () => finish({ documentId: item.documentId, source }));
        const row = document.createElement('tr');
        const name = document.createElement('td'); name.append(button);
        if (path.includes('/')) {
          const directory = document.createElement('small');
          directory.textContent = path.slice(0, path.lastIndexOf('/')); name.append(directory);
        }
        row.append(name);
        const values = [uiText(`repository.picker.source.${source}`),
          uiText('repository.picker.version', { version: source === 'draft' ? item.releaseVersion : item.releasedVersion }),
          item.releasedVersion ? uiText('repository.picker.version', { version: item.releasedVersion }) : '—',
          uiText(item.published ? 'repository.picker.published' : item.released ? 'repository.picker.pending' : 'repository.picker.unreleased')];
        values.forEach((value, index) => {
          const cell = document.createElement('td');
          const label = document.createElement('span'); label.textContent = value;
          if (index === 0 || index === 3) label.className = `repository-badge ${index === 0 ? source : item.published ? 'published' : 'pending'}`;
          cell.append(label); row.append(cell);
        });
        body.append(row); visibleRows++;
      }
    }
    status.textContent = uiText(loading ? 'repository.picker.loading' : failed ? 'repository.picker.failed'
      : !complete ? 'repository.picker.partial' : !items.length ? 'repository.picker.empty'
        : !visibleRows ? 'repository.picker.noMatch' : 'repository.picker.complete') + ' ' + uiText('repository.picker.count', { documents: items.length, rows: visibleRows });
    list.setAttribute('aria-busy', String(loading));
    more.hidden = complete;
    more.disabled = loading;
    more.textContent = uiText(failed ? 'repository.picker.retry' : 'repository.picker.more');
  }
  async function load() {
    if (loading || closed || complete) return;
    loading = true; failed = false; render();
    try {
      const page = await client.list(cursor);
      if (closed) return;
      if (!Array.isArray(page.items) || typeof page.nextCursor !== 'string'
          || page.nextCursor && (page.nextCursor === cursor || visited.has(page.nextCursor))) throw new Error('Invalid inventory page');
      visited.add(cursor);
      const byId = new Map(items.map(item => [item.documentId, item]));
      page.items.forEach(item => byId.set(item.documentId, item));
      items = [...byId.values()]; cursor = page.nextCursor; complete = !cursor;
    } catch { failed = true; }
    finally { loading = false; if (!closed) { render(); requestAnimationFrame(loadVisible); } }
  }
  function loadVisible() {
    if (closed || loading || complete || failed) return;
    // Search covers unseen pages too; scrolling and resizing fill the visible viewport.
    if (search.value.trim() || filter.value !== 'all'
        || list.clientHeight > 0 && list.scrollHeight - list.scrollTop - list.clientHeight < 120) void load();
  }
  const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(loadVisible) : null;
  observer?.observe(list);
  list.addEventListener('scroll', loadVisible, { passive: true });
  search.addEventListener('input', () => { render(); loadVisible(); });
  filter.addEventListener('change', () => { render(); loadVisible(); });
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
