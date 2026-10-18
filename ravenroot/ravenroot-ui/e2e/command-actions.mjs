// Use the same visible File/Edit entry points in every shell density and viewport.
export async function commandMenuItem(page, commandId) {
  const menuName = commandId.split('.')[0];
  if (!['file', 'edit'].includes(menuName)) throw new Error(`Unsupported command menu: ${commandId}`);
  const item = page.locator(`#application-menu [data-command-id="${commandId}"]`);
  if (!await item.isVisible()) await page.locator(`#menu-${menuName}`).click();
  return item;
}

export async function clickCommand(page, commandId, options) {
  await (await commandMenuItem(page, commandId)).click(options);
}
