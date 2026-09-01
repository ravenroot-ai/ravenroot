import { expect, test } from '@playwright/test';
import { createAppCommands } from '../src/app-commands.js';

const catalogCommands = Object.fromEntries(createAppCommands(
  new Proxy({}, { get: () => () => {} })).map(command => [command.id, command]));

async function openMenu(page, name) {
  await page.locator(`#menu-${name}`).click();
  await expect(page.locator('#application-menu')).toBeVisible();
}

test.describe('application command menus', () => {
  test('renders catalog labels as accessible names with ARIA navigation and focus restore', async ({ page }) => {
    await page.goto('/');
    const triggers = page.locator('#application-menubar [role="menuitem"]');
    await expect(triggers).toHaveText(['File', 'Edit', 'View', 'Layout', 'Run']);
    expect(await triggers.evaluateAll(items => items.map(item => item.tabIndex))).toEqual([0, -1, -1, -1, -1]);

    await page.locator('#menu-file').focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.locator('#menu-edit')).toBeFocused();
    await page.keyboard.press('End');
    await expect(page.locator('#menu-run')).toBeFocused();
    await page.keyboard.press('Home');
    await expect(page.locator('#menu-file')).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: catalogCommands['file.new'].label })).toBeFocused();

    await page.keyboard.press('End');
    await expect(page.getByRole('menuitem', { name: catalogCommands['file.close'].label })).toBeFocused();
    await page.keyboard.press('Home');
    await expect(page.getByRole('menuitem', { name: catalogCommands['file.new'].label })).toBeFocused();
    await page.keyboard.type('rep');
    await expect(page.getByRole('menuitem', { name: 'Replace Active…' })).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await expect(page.locator('#menu-edit')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('menuitemcheckbox', { name: 'Modify' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#application-menu')).toBeHidden();
    await expect(page.locator('#menu-edit')).toBeFocused();

    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Shift+Tab');
    await expect(page.locator('#application-menu')).toBeHidden();
    await expect(page.locator('#menu-edit')).not.toBeFocused();
  });

  test('keeps menu, toolbar and shortcut state in sync and generates platform help', async ({ page }) => {
    await page.goto('/');
    await openMenu(page, 'edit');
    await expect(page.getByRole('menuitemcheckbox', { name: 'Modify' })).toHaveAttribute('aria-checked', 'false');
    await page.getByRole('menuitemcheckbox', { name: 'Modify' }).click();
    await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');

    await openMenu(page, 'edit');
    await expect(page.getByRole('menuitemcheckbox', { name: 'Modify' })).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByRole('menuitem', { name: 'Undo' })).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('Escape');

    await openMenu(page, 'layout');
    await page.getByRole('menuitemradio', { name: 'Monitoring' }).click();
    await expect(page.locator('#btn-monitoring')).toHaveClass(/active/);
    await openMenu(page, 'layout');
    await expect(page.getByRole('menuitemradio', { name: 'Monitoring' })).toHaveAttribute('aria-checked', 'true');
    await page.keyboard.press('Escape');

    await page.locator('#service-url').focus();
    await page.keyboard.press('Control+Enter');
    await expect(page.locator('#service-url')).toBeFocused();
    await openMenu(page, 'view');
    const helpItem = page.getByRole('menuitem', { name: 'Keyboard Shortcuts' });
    await expect(helpItem).toHaveAttribute('aria-keyshortcuts', 'Shift+Slash');
    await expect(helpItem).toContainText(/⇧\/|Shift\+\//);
    await helpItem.click();
    await expect(page.locator('#shortcut-help-list')).toContainText(catalogCommands['file.save'].help);
    await expect(page.locator('#shortcut-help-list')).toContainText(/Ctrl\+S|⌘S/);
    await expect(page.locator('#shortcut-help-list')).not.toContainText('Ctrl+N');
    await page.locator('.help-close').click();
    // The close button overlays the graph. Its click must never be reinterpreted as a canvas press
    // just because its page coordinate happens to be above a node underneath.
    await expect(page.locator('#cy-wrap')).not.toHaveAttribute('data-edge-gesture-state');

    await page.locator('#btn-play').focus();
    await page.keyboard.press('Shift+/');
    await expect(page.locator('#help-overlay')).toHaveClass(/on/);
    await expect(page.locator('.help-close')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#help-overlay')).not.toHaveClass(/on/);
    await expect(page.locator('#btn-play')).toBeFocused();

    await page.locator('#service-url').focus();
    await page.keyboard.press('Shift+/');
    await expect(page.locator('#help-overlay')).not.toHaveClass(/on/);
    await expect(page.locator('#service-url')).toBeFocused();
  });

  test('duplicates exactly one eligible selected node through the shared command state', async ({ page }) => {
    await page.goto('/');
    const [first, second] = await page.evaluate(() => {
      const firstId = window.ravenroot.activeDocument().id;
      return [firstId, window.ravenroot.openDocument()];
    });
    expect(second).not.toBe(first);
    await openMenu(page, 'edit');
    const duplicate = page.getByRole('menuitem', { name: 'Duplicate Node' });
    await expect(duplicate).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('Escape');
    await page.locator('#btn-modify').click();
    await page.evaluate(() => {
      window.cy.getElementById('dosomething').select();
    });
    await openMenu(page, 'edit');
    await expect(duplicate).toHaveAttribute('aria-disabled', 'false');
    const before = await page.evaluate(() => window.ravenroot.activeDocument().history.depth());
    await duplicate.click();
    await expect.poll(() => page.evaluate(inactiveId => ({
      copies: window.cy.nodes().filter(node => node.id().startsWith('dosomething-copy-')).length,
      selected: window.cy.nodes(':selected').map(node => node.id()),
      depth: window.ravenroot.activeDocument().history.depth(),
      edgeCount: window.cy.edges().length,
      inactiveCopies: window.ravenroot.workspace.find(inactiveId).cy.nodes()
        .filter(node => node.id().startsWith('dosomething-copy-')).length,
    }), first)).toEqual({
      copies: 1, selected: ['dosomething-copy-1'], depth: before + 1, edgeCount: 3,
      inactiveCopies: 0,
    });
  });

  test('coexists with the document switcher and native unsaved dialog', async ({ page }) => {
    await page.goto('/');
    await openMenu(page, 'file');
    await page.locator('#document-switcher').click();
    await expect(page.locator('#application-menu')).toBeHidden();
    await expect(page.locator('#document-switcher-dialog')).toBeVisible();
    await page.keyboard.press('Escape');

    await page.locator('#btn-modify').click();
    await page.locator('#btn-add-node').click();
    await page.locator('#node-editor button[type="submit"]').click();
    await openMenu(page, 'file');
    await page.getByRole('menuitem', { name: 'Close Document' }).click();
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    await page.locator('#menu-file').click({ force: true });
    await expect(page.locator('#application-menu')).toBeHidden();
    await page.locator('#unsaved-document-dialog').press('Escape');
    await expect(page.locator('#menu-file')).toBeFocused();
  });

  for (const width of [1280, 1440, 1920]) {
    test(`keeps priority routes visible without bar overflow at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/');
      for (const selector of ['#application-menubar', '#document-switcher', '#btn-play',
        '#service-url', '#access-token', '#btn-authenticate', '#btn-revoke', '#execution-payload']) {
        await expect(page.locator(selector)).toBeVisible();
        await expect(page.locator(selector)).toBeInViewport();
      }
      const geometry = await page.evaluate(() => ['topbar', 'workflowbar'].map(id => {
        const element = document.getElementById(id);
        return { id, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth };
      }));
      for (const bar of geometry) expect(bar.scrollWidth, bar.id).toBeLessThanOrEqual(bar.clientWidth);
      if (width === 1280) {
        await expect(page.locator('.layout-mirrors')).toBeVisible();
        await page.locator('#btn-monitoring').click();
        await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
        await expect(page.locator('#elastic-ctrl')).toBeHidden();
        await expect.poll(() => page.evaluate(() => {
          const topbar = document.getElementById('topbar');
          return topbar.scrollWidth <= topbar.clientWidth;
        })).toBe(true);
      }
      await openMenu(page, 'layout');
      await expect(page.getByRole('menuitemradio', { name: 'Design' })).toBeVisible();
      await expect(page.getByRole('menuitemradio', { name: 'Monitoring' })).toBeVisible();
    });
  }
});
