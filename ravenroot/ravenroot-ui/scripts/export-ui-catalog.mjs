import { ENGLISH_MESSAGES, UI_CATALOG_SCHEMA, normalizeUiLocale,
  uiLocaleDirection } from '../src/ui-text.js';

const requested = process.argv[2];
if (!requested) {
  process.stderr.write('usage: npm run export:i18n -- <locale>\n');
  process.exitCode = 2;
} else {
  const locale = normalizeUiLocale(requested);
  process.stdout.write(`${JSON.stringify({
    schema: UI_CATALOG_SCHEMA,
    locale,
    direction: uiLocaleDirection(locale),
    messages: ENGLISH_MESSAGES,
  }, null, 2)}\n`);
}
