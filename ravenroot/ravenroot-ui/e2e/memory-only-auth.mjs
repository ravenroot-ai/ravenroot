/** Existing feature specs deliberately decline the new optional browser credential persistence. */
export async function keepAuthenticationInMemory(page) {
  await page.addLocatorHandler(page.locator('#remember-connection-dialog'), async dialog => {
    await dialog.getByRole('button', { name: 'Keep in memory', exact: true }).click();
  });
}
