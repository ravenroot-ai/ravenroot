// The catalog is server-driven, but its interaction contract is entirely local: every descriptor
// remains one native button in response order. Keeping this small view separate from app.js makes
// that contract testable without booting the graph renderer or duplicating production markup.

function catalogIdentity(type) {
  const displayName = String(type.displayName || type.behavior || 'Unnamed node');
  const category = String(type.category || '');
  return { displayName, category };
}

export function renderNodeCatalogItems(container, types, { iconFor, onActivate, onDragStart, selectedBehavior = '' }) {
  const document = container.ownerDocument;
  const fragment = document.createDocumentFragment();
  container.classList.add('node-catalog--items');

  const origins = [
    { id: 'CORE', label: 'Core nodes' },
    { id: 'BUNDLE', label: 'Installed bundles' },
  ];
  for (const origin of origins) {
    const matching = types.filter(type => String(type.origin || 'BUNDLE').toUpperCase() === origin.id);
    if (!matching.length) continue;
    const toolbox = document.createElement('section');
    toolbox.className = 'catalog-toolbox';
    toolbox.dataset.catalogOrigin = origin.id;
    const heading = document.createElement('h3');
    heading.className = 'catalog-toolbox-title';
    heading.textContent = origin.label;
    toolbox.append(heading);

    const categories = new Map();
    for (const type of matching) {
      const category = String(type.category || 'Other');
      if (!categories.has(category)) categories.set(category, []);
      categories.get(category).push(type);
    }
    for (const [category, categoryTypes] of categories) {
      const group = document.createElement('details');
      group.className = 'catalog-category';
      group.open = true;
      const summary = document.createElement('summary');
      summary.textContent = category;
      group.append(summary);
      for (const type of categoryTypes) group.append(catalogButton(
        document, type, iconFor, onActivate, onDragStart, selectedBehavior, types.indexOf(type)));
      toolbox.append(group);
    }
    fragment.append(toolbox);
  }

  container.replaceChildren(fragment);
  container.onkeydown = event => {
    if (event.key !== 'Tab' || !container.closest('.panel--short')) return;
    const current = event.target.closest?.('.catalog-item');
    if (!current) return;
    const ordered = [...container.querySelectorAll('.catalog-item')]
      .sort((left, right) => Number(left.dataset.catalogOrder) - Number(right.dataset.catalogOrder));
    const next = ordered[ordered.indexOf(current) + (event.shiftKey ? -1 : 1)];
    if (!next) return;
    event.preventDefault();
    next.focus();
  };
}

function catalogButton(document, type, iconFor, onActivate, onDragStart, selectedBehavior, order) {
    const { displayName, category } = catalogIdentity(type);
    const button = document.createElement('button');
    button.className = 'catalog-item';
    button.type = 'button';
    button.dataset.catalogAdd = String(type.behavior || '');
    button.dataset.catalogOrder = String(order);
    button.draggable = true;
    button.setAttribute('aria-pressed', String(button.dataset.catalogAdd === selectedBehavior));
    button.classList.toggle('selected', button.dataset.catalogAdd === selectedBehavior);
    button.setAttribute('aria-label', `Add ${displayName} node${category ? `, category ${category}` : ''}`);

    button.dataset.tooltip = category ? `Add ${displayName} · ${category}` : `Add ${displayName}`;

    const icon = document.createElement('span');
    icon.className = 'catalog-item-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = iconFor(type);

    const copy = document.createElement('span');
    copy.className = 'catalog-item-copy';
    const name = document.createElement('b');
    name.textContent = displayName;
    const categoryLabel = document.createElement('small');
    categoryLabel.textContent = category;
    copy.append(name, categoryLabel);

    button.append(icon, copy);
    button.addEventListener('click', () => onActivate(button.dataset.catalogAdd));
    button.addEventListener('dragstart', event => onDragStart?.(event, button.dataset.catalogAdd));
    return button;
}
