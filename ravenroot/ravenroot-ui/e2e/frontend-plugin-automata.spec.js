import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

const packageDirectory = fileURLToPath(new URL('../public/examples/frontend-plugins/textbook-automata/', import.meta.url));
const fixture = readFileSync(new URL('../public/examples/frontend-plugins/textbook-automata/dfa-even-ones-sanitized.graphml', import.meta.url), 'utf8');
const mapping = readFileSync(new URL('../public/examples/frontend-plugins/textbook-automata/dfa-even-ones-presentation.json', import.meta.url), 'utf8');

test('installs and authors the textbook automata drawing model while keeping real evidence accessible', async ({ page }) => {
  await page.goto('/');
  await page.locator('#drawing-model-package-input').setInputFiles(packageDirectory);
  await expect(page.locator('#drawing-model-select option')).toContainText(['Integrated Design', 'Textbook DFA/NFA']);

  await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'dfa-even-ones.graphml'), fixture);
  await page.locator('#drawing-model-mapping').click();
  await page.locator('#drawing-model-mapping-json').fill(mapping);
  await page.locator('#drawing-model-dialog button[type="submit"]').click();
  await page.locator('#drawing-model-select').selectOption('model|ai.ravenroot.examples.textbook-automata|textbook-automata');

  const scene = page.locator('.drawing-model-scene');
  await expect(scene).toBeVisible();
  await expect(scene.locator('circle[role="button"]')).toHaveCount(2);
  await expect(scene.getByText('qEven')).toBeVisible();
  await expect(scene.getByText('qOdd')).toBeVisible();
  await expect(page.locator('.automata-accepting')).toHaveCount(1);
  await expect(page.locator('[data-evidence-role="transition"]')).toHaveCount(4);

  await page.locator('[data-evidence-id="qEven-1"]').focus();
  await page.locator('[data-evidence-id="qEven-1"]').press('Enter');
  expect(await page.evaluate(() => window.cy.edges(':selected').map(edge => edge.id())))
    .toEqual(['e10', 'e11', 'e13', 'e14', 'e15', 'e16', 'e18', 'e20', 'e22']);

  const exported = await page.evaluate(() => window.ravenroot.serializeGraphML(window.ravenroot.activeDocument().graph));
  expect(exported).toContain('ravenroot.frontendPresentation.v1');
  expect(exported).toContain('ravenroot.frontendDrawingModel.v1');

  await page.locator('#drawing-model-full-flow').click();
  await expect(scene).toBeHidden();
  await expect(page.locator('.doc-canvas canvas').first()).toBeVisible();
});
