# UI internationalization

Ravenroot owns one English source catalog for product labels, commands, status text, dialogs,
accessibility text, workspace notices, and embedded-viewer states. English is the only catalog
included in production assets. A deployment or embedding host may add validated catalogs without
changing graph data, authorization, or runtime behavior.

The catalog schema is `ravenroot.ui-catalog/v1`. Message keys are stable semantic identifiers;
copy changes do not rename a key. The complete English source is assembled as `ENGLISH_MESSAGES`
in `ravenroot-ui/src/ui-text.js` from `ui-static-text.js` and `ui-dynamic-text.js`. From the
`ravenroot-ui` directory, create a complete English-valued translation template with:

```sh
npm run --silent export:i18n -- en-XA > ui-catalog.en-XA.json
```

Translate the values while keeping every key and named placeholder. The template is documentation
and deployment input; it is not bundled back into Ravenroot.

## Catalog contract

A catalog may be complete or partial. Missing keys use fallback. This synthetic English pseudo
catalog shows strings, named parameters, and plurals without publishing a production translation:

```json
{
  "schema": "ravenroot.ui-catalog/v1",
  "locale": "en-XA",
  "direction": "ltr",
  "messages": {
    "commands.file.open.label": "[Open…]",
    "workspace.app.nodes.selected": {
      "one": "[One node selected]",
      "other": "[{count} nodes selected]"
    }
  }
}
```

Locale tags are canonicalized with `Intl.getCanonicalLocales`; underscores become hyphens. Locale
tags are at most 64 characters. A catalog has at most 4,096 keys and each string or plural variant
has at most 8,192 characters. Unknown keys, invalid plural categories, an absent `other` plural
variant, and parameters that differ from the corresponding English variant are rejected. Supported
plural categories are `zero`, `one`, `two`, `few`, `many`, and `other`.

Catalog values are plain text. Markup and quotes are displayed as text and cannot create elements or
attributes. Parameters use meaningful names such as `{count}`, `{name}`, and `{status}`. Counts use
`Intl.NumberFormat` and `Intl.PluralRules`; dates use `Intl.DateTimeFormat`; durations use localized
unit formatting. `direction` may be `ltr` or `rtl`. When omitted, Ravenroot derives it from the
locale. Applying a locale updates the document `lang` and `dir` attributes.

## Workspace deployment and selection

The browser optionally loads `/ui-locales.json` from the same origin and UI deployment root as
`index.html`. Redirects and cross-origin final responses are refused. The file is limited to 512 KiB.
If either the response headers or body stalls for three seconds, startup continues with stored
catalogs and the built-in English fallback.
For example, deploy this file as `<ui-root>/ui-locales.json`:

```json
{
  "schema": "ravenroot.ui-locales/v1",
  "defaultLocale": "en-XA",
  "catalogs": [{
    "schema": "ravenroot.ui-catalog/v1",
    "locale": "en-XA",
    "direction": "ltr",
    "messages": {
      "commands.file.open.label": "[Open…]",
      "workspace.app.nodes.selected": {
        "one": "[One node selected]",
        "other": "[{count} nodes selected]"
      }
    }
  }]
}
```

The active locale is chosen in this order:

1. The user's explicit **View → Language…** selection.
2. The deployment's `defaultLocale`.
3. The browser language list.
4. English.

The selected locale resolves each key independently through exact locale, base language, then
English. For example, `fr-CA` checks `fr-CA`, `fr`, and `en`. A missing source key or invalid runtime
parameter renders the bounded `Unavailable` diagnostic; it never renders a raw key or `undefined`.

The explicit choice is stored in local storage under `ravenroot.ui.locale.v1`. Choosing **Automatic**
removes that preference and reevaluates the deployment and browser languages. Imported catalogs are
validated and stored in IndexedDB database `ravenroot-ui-catalogs-v1`; the dialog can remove an
imported catalog, but it cannot remove a configured catalog or replace built-in English. Import
files are checked against the same 512 KiB limit before their contents are read. Import,
removal, storage, and validation failures show localized generic product text while technical detail
is sent only to the diagnostic callback.

Language changes apply without a page reload. Open documents, dirty history, graph selection,
inspector drafts, open dialogs, and independent workspace panes remain in place. Surfaces opened or
loaded after the change use the current locale as well.

## Embedded viewer

The optional `locale` and `catalog` fields on `POST /v1/embed/sessions` are presentation-only. They
do not change the grant, capabilities, tenant, source, origin checks, or expiry. `catalog` is a JSON
string because the session request keeps its existing flat string-valued wire shape. The initial
catalog string is limited to 49,152 UTF-16 code units after decoding the outer request JSON and must be a JSON object envelope. This counts a supplementary Unicode character as two units; it is not a UTF-8 byte limit. The catalog-bearing session-creation request is limited to 303,104 UTF-8 bytes in total, including the JSON envelope, whitespace, and escapes. This reserves 8,192 bytes for the envelope plus six bytes per catalog code unit, so even a catalog fully escaped as `\uXXXX` fits. Catalog-free session creation and every other embed route retain their 8,192-byte request limit. Catalog validation still limits each individual message to 8,192 UTF-16 units and requires known message keys with matching placeholders. Authentication, origin, authorization, and CSP checks are unchanged.

A registered-source request may include:

```json
{
  "registrationId": "orders-dashboard",
  "locale": "en-XA",
  "catalog": "{\"schema\":\"ravenroot.ui-catalog/v1\",\"locale\":\"en-XA\",\"messages\":{\"embed.state.loading\":\"[Loading graph…]\"}}"
}
```

A dynamic deployment request uses the same additive fields with its complete existing authority
shape:

```json
{
  "deploymentId": "orders",
  "incarnationId": "01JEXAMPLE",
  "graphVersion": "sha256:example",
  "parentOrigin": "https://host.example",
  "locale": "en-XA",
  "catalog": "{\"schema\":\"ravenroot.ui-catalog/v1\",\"locale\":\"en-XA\",\"messages\":{\"embed.state.loading\":\"[Loading graph…]\"}}"
}
```

Each iframe creates a separate localization context. After the normal `READY` handshake, a host can
change one viewer without reloading it. `catalog` is required by the exact message shape but may be
`null` when the viewer already has the requested catalog:

```js
const correlationId = crypto.randomUUID();
iframe.contentWindow.postMessage({
  protocolVersion: 'ravenroot.embed/1',
  channelId,
  correlationId,
  direction: 'parent-to-viewer',
  type: 'SET_LOCALE',
  locale: 'en-XA',
  catalog: {
    schema: 'ravenroot.ui-catalog/v1',
    locale: 'en-XA',
    direction: 'ltr',
    messages: { 'embed.state.loading': '[Loading graph…]' },
  },
}, viewerOrigin);
```

A successful change returns to the parent origin:

```js
{
  protocolVersion: 'ravenroot.embed/1',
  channelId,
  correlationId,
  direction: 'viewer-to-parent',
  type: 'LOCALE_CHANGED',
  locale: 'en-XA',
}
```

The viewer validates the locale and the entire catalog before registering either value. Invalid
messages are ignored atomically. Origin, source window, channel, direction, correlation, protocol,
and post-handshake checks remain mandatory. An iframe that fails before loading the viewer module
uses a valid selected catalog for its failure state when possible and otherwise uses the keyed
server-rendered English fallback. Backend diagnostic content remains authored content inside this
localized product frame.

## Ownership and verification

Ravenroot localizes product chrome, including product-owned plugin controls. Graph and node names,
task prompts, backend diagnostic details, provider-authored names and descriptions, and model output
remain authored content. Provider packages that own additional UI copy should use keys under their
package namespace and must not redefine Ravenroot core keys.

The source audit parses static HTML, Java-generated embed HTML, DOM sinks, templates and conditional
builders, indirect bindings, presentation records, and supported localization aliases. Local wrapper signatures are derived from their parameter flow into DOM and dialog sinks, including transitive calls, returned values, deferred callbacks, and opaque HTML helpers; fixtures reject unkeyed values passed through renamed wrappers. Metadata such as labels, display names, descriptions, and validation errors is checked even when consumed by another module. Persisted document identities, renderer expressions, and provider-authored diagnostics require narrowly documented exclusions when they resemble product text. It checks
missing and unused keys, named parameters, constrained line-specific exclusions, HTML-context
escaping, and production catalog contents. Run the supported checks from `ravenroot-ui`:

```sh
npm run check:i18n
npm test
npm run build
npx playwright test e2e/ui-localization.spec.js
```

Server-side embed request and shell coverage runs from `ravenroot`:

```sh
mvn -pl ravenroot-server -Dtest=EmbedLaunchTicketAuthorityTest,EmbedBrowserHttpIntegrationTest test
```
