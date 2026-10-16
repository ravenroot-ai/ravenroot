import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import * as acorn from 'acorn';
import * as walk from 'acorn-walk';
import { parse, parseFragment } from 'parse5';
import { ENGLISH_MESSAGES } from '../src/ui-text.js';

const failures = [];
const references = new Set();
const root = path.resolve(import.meta.dirname, '..');
const fail = (file, line, message) => failures.push(`${file}:${line}: ${message}`);
const hasWords = value => /[A-Za-z]{2}/u.test(value || '');
const escapeRegex = value => value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
const attributes = node => Object.fromEntries((node.attrs || []).map(item => [item.name, item.value]));

function auditHtml() {
  const file = 'index.html';
  const document = parse(fs.readFileSync(path.join(root, file), 'utf8'), { sourceCodeLocationInfo: true });
  function visit(node, ancestors = []) {
    const attrs = attributes(node);
    for (const state of ['error', 'expired', 'offline', 'incompatible']) {
      if (attrs[`data-i18n-${state}`]) references.add(`embed.state.${state}`);
    }
    for (const name of ['data-i18n', 'data-i18n-html', 'data-i18n-title',
      'data-i18n-placeholder', 'data-i18n-aria-label', 'data-i18n-aria-description',
      'data-i18n-aria-valuetext']) {
      if (attrs[name]) references.add(attrs[name]);
    }
    if (node.nodeName === '#text' && hasWords(node.value)) {
      const parent = ancestors.at(-1);
      const parentAttrs = attributes(parent);
      const covered = parentAttrs['data-i18n'] || ancestors.some(item => attributes(item)['data-i18n-html']);
      if (!covered && !['script', 'style'].includes(parent?.tagName)) {
        fail(file, node.sourceCodeLocation?.startLine || 1, 'visible text has no message key');
      }
    }
    for (const name of ['title', 'placeholder', 'aria-label', 'aria-description', 'aria-valuetext']) {
      if (hasWords(attrs[name]) && !attrs[`data-i18n-${name}`]) {
        fail(file, node.sourceCodeLocation?.startLine || 1, `${name} has no message key`);
      }
    }
    for (const child of node.childNodes || []) visit(child, [...ancestors, node]);
  }
  visit(document);
}

const localizationCall = node => node?.type === 'CallExpression'
  && ((node.callee.type === 'Identifier' && ['uiText', 't'].includes(node.callee.name))
    || (node.callee.type === 'MemberExpression' && node.callee.property.name === 'text'));

function unsafeLocalizedHtml(node, output = []) {
  if (!node) return output;
  if (localizationCall(node)) {
    output.push(node);
    return output;
  }
  if (node.type === 'CallExpression'
      && node.callee.type === 'Identifier'
      && ['escapeHtml', 'escapeAttribute', 'htmlText'].includes(node.callee.name)) return output;
  if (node.type === 'TemplateLiteral') {
    node.expressions.forEach(expression => unsafeLocalizedHtml(expression, output));
  } else if (node.type === 'ConditionalExpression') {
    unsafeLocalizedHtml(node.consequent, output); unsafeLocalizedHtml(node.alternate, output);
  } else if (node.type === 'LogicalExpression' || (node.type === 'BinaryExpression' && node.operator === '+')) {
    unsafeLocalizedHtml(node.left, output); unsafeLocalizedHtml(node.right, output);
  }
  return output;
}

function recordKeyExpression(node) {
  if (!node) return;
  if (node.type === 'Literal' && typeof node.value === 'string') references.add(node.value);
  else if (node.type === 'TemplateLiteral') {
    const pattern = new RegExp(`^${node.quasis.map(quasi => escapeRegex(quasi.value.cooked))
      .join('.+')}$`, 'u');
    for (const candidate of Object.keys(ENGLISH_MESSAGES)) if (pattern.test(candidate)) references.add(candidate);
  } else if (node.type === 'ConditionalExpression') {
    recordKeyExpression(node.consequent); recordKeyExpression(node.alternate);
  } else if (node.type === 'LogicalExpression') {
    recordKeyExpression(node.left); recordKeyExpression(node.right);
  }
}

function outputLiterals(node, output = [], bindings = new Map(), seen = new Set()) {
  if (!node) return output;
  if (localizationCall(node)) return output;
  if (node.type === 'Identifier' && bindings.has(node.name) && !seen.has(node.name)) {
    seen.add(node.name);
    outputLiterals(bindings.get(node.name), output, bindings, seen);
    return output;
  }
  if (node.type === 'MemberExpression' && !node.computed && node.object.type === 'Identifier') {
    const object = bindings.get(node.object.name);
    if (object?.type === 'ObjectExpression') {
      const property = object.properties.find(item => (item.key.name ?? item.key.value) === node.property.name);
      if (property) outputLiterals(property.value, output, bindings, seen);
    }
    return output;
  }
  if (node.type === 'Literal') {
    if (typeof node.value === 'string' && hasWords(node.value)) output.push(node);
    return output;
  }
  if (node.type === 'TemplateLiteral') {
    for (const quasi of node.quasis) if (hasWords(quasi.value.cooked)) output.push(quasi);
    for (const expression of node.expressions) outputLiterals(expression, output, bindings, seen);
    return output;
  }
  if (node.type === 'ConditionalExpression') {
    outputLiterals(node.consequent, output, bindings, seen);
    outputLiterals(node.alternate, output, bindings, seen); return output;
  }
  if (node.type === 'LogicalExpression' || (node.type === 'BinaryExpression' && node.operator === '+')) {
    outputLiterals(node.left, output, bindings, seen);
    outputLiterals(node.right, output, bindings, seen); return output;
  }
  return output;
}

export function findPresentationSinkViolations(source, { sourceType = 'module' } = {}) {
  const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType, locations: true });
  const bindings = new Map();
  walk.simple(ast, { VariableDeclarator(node) {
    if (node.id.type === 'Identifier' && node.init) {
      bindings.set(node.id.name, bindings.has(node.id.name) ? null : node.init);
    }
  } });
  const violations = [];
  const inspect = node => violations.push(...outputLiterals(node, [], bindings).map(item => item.loc.start.line));
  const presentationRecordProperties = new Set(['label', 'shortLabel', 'caption', 'help', 'title',
    'message', 'emptyText', 'placeholder', 'ariaLabel', 'ariaDescription']);
  walk.simple(ast, {
    AssignmentExpression(node) {
      if (node.left.type === 'MemberExpression'
          && ['textContent', 'innerText', 'title', 'placeholder', 'ariaLabel']
            .includes(node.left.property.name)) inspect(node.right);
      if (node.left.type === 'MemberExpression' && node.left.property.name === 'innerHTML') {
        violations.push(...unsafeLocalizedHtml(node.right).map(item => item.loc.start.line));
      }
    },
    CallExpression(node) {
      const name = node.callee.type === 'MemberExpression' ? node.callee.property.name : node.callee.name;
      if (['alert', 'confirm', 'announce', 'announceGraph', 'showToast',
        'showInspectorMessage'].includes(name)) node.arguments.slice(0, 2).forEach(inspect);
      if (name === 'addActivityMessage') node.arguments.slice(0, 2).forEach(inspect);
      if (/(?:render|dialog|menu|presentation)/iu.test(name || '')) {
        for (const argument of node.arguments.filter(item => item.type === 'ObjectExpression')) {
          for (const property of argument.properties) {
            if (['label', 'help', 'title', 'message', 'emptyText', 'placeholder',
              'ariaLabel', 'ariaDescription'].includes(property.key?.name ?? property.key?.value)) {
              inspect(property.value);
            }
          }
        }
      }
    },
    ReturnStatement(node) {
      const inspectRecord = value => {
        if (!value) return;
        if (value.type === 'ObjectExpression') {
          value.properties.forEach(property => {
            if (presentationRecordProperties.has(property.key?.name ?? property.key?.value)) {
              inspect(property.value);
            }
          });
        } else if (value.type === 'ConditionalExpression') {
          inspectRecord(value.consequent); inspectRecord(value.alternate);
        }
      };
      inspectRecord(node.argument);
    },
    Property(node) {
      if (presentationRecordProperties.has(node.key?.name ?? node.key?.value)) inspect(node.value);
    },
  });
  return [...new Set(violations)].sort((left, right) => left - right);
}

function auditJavascript() {
  const sources = fs.readdirSync(path.join(root, 'src')).filter(file => file.endsWith('.js'))
    .map(file => ({ file: `src/${file}`, sourceType: 'module' }));
  sources.push({ file: 'public/embed-bootstrap.js', sourceType: 'script' });
  const presentationProperties = new Set(['textContent', 'innerText', 'innerHTML', 'title', 'placeholder', 'ariaLabel']);
  const presentationAttributes = /^(?:title|placeholder|aria-(?:label|description|valuetext))$/u;
  const presentationRecordProperties = new Set([
    'label', 'shortLabel', 'caption', 'help', 'title', 'message', 'emptyText', 'placeholder',
    'ariaLabel', 'ariaDescription',
  ]);
  for (const entry of sources) {
    const { file, sourceType } = entry;
    const fileName = path.basename(file);
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    const lines = source.split('\n');
    const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType, locations: true });
    const bindings = new Map();
    walk.simple(ast, {
      VariableDeclarator(node) {
        if (node.id.type === 'Identifier' && node.init) {
          bindings.set(node.id.name, bindings.has(node.id.name) ? null : node.init);
        }
      },
    });
    const reportLiteral = literal => {
      const line = literal.loc.start.line;
      const exemption = [lines[line - 1], lines[line - 2]].find(value =>
        /i18n-audit-exempt: (?:authored-content|protocol-token|diagnostic-content) - .{15,}/u.test(value || ''));
      if (!exemption) {
        fail(file, line, 'inline product text reaches a presentation sink');
      }
    };
    const report = node => {
      for (const literal of outputLiterals(node, [], bindings)) {
        reportLiteral(literal);
      }
    };
    const reportPresentationMetadata = (node, reportAllValues = false) => {
      if (!node) return;
      if (node.type === 'ObjectExpression') {
        for (const property of node.properties) {
          const name = property.key?.name ?? property.key?.value;
          if (reportAllValues || presentationRecordProperties.has(name)) report(property.value);
          else reportPresentationMetadata(property.value, false);
        }
      } else if (node.type === 'ArrayExpression') {
        node.elements.forEach(element => reportPresentationMetadata(element, reportAllValues));
      } else if (node.type === 'CallExpression') {
        if (node.callee.type === 'Identifier' && node.callee.name === 'localizedLookup') {
          for (const argument of node.arguments.filter(item => item.type === 'ObjectExpression')) {
            argument.properties.forEach(property => recordKeyExpression(property.value));
          }
          return;
        }
        node.arguments.forEach(argument => reportPresentationMetadata(argument, reportAllValues));
      }
    };
    const reportHtml = node => {
      if (!node) return;
      if (localizationCall(node)) {
        fail(file, node.loc.start.line, 'unescaped catalog value reaches an HTML presentation sink');
        return;
      }
      if (node.type === 'ConditionalExpression') {
        reportHtml(node.consequent); reportHtml(node.alternate); return;
      }
      if (node.type === 'LogicalExpression' || (node.type === 'BinaryExpression' && node.operator === '+')) {
        reportHtml(node.left); reportHtml(node.right); return;
      }
      if (node.type !== 'Literal' && node.type !== 'TemplateLiteral') return;
      const expressions = node.type === 'TemplateLiteral' ? node.expressions : [];
      const html = node.type === 'TemplateLiteral'
        ? node.quasis.map((quasi, index) => `${quasi.value.cooked}${index < expressions.length ? `__I18N_EXPR_${index}__` : ''}`).join('')
        : String(node.value || '');
      const fragment = parseFragment(html, { sourceCodeLocationInfo: true });
      let violation = false;
      const presentationExpressions = new Set();
      const recordExpressions = value => {
        for (const match of String(value || '').matchAll(/__I18N_EXPR_(\d+)__/gu)) {
          presentationExpressions.add(Number(match[1]));
        }
      };
      const inspect = current => {
        const attrs = attributes(current);
        if (current.nodeName === '#text') recordExpressions(current.value);
        if (current.nodeName === '#text' && hasWords(current.value?.replaceAll(/__I18N_EXPR_\d+__/gu, ''))) {
          violation = true;
        }
        for (const name of ['title', 'placeholder', 'aria-label', 'aria-description', 'aria-valuetext']) {
          recordExpressions(attrs[name]);
          if (hasWords(attrs[name]?.replaceAll(/__I18N_EXPR_\d+__/gu, ''))) violation = true;
        }
        for (const child of current.childNodes || []) inspect(child);
      };
      inspect(fragment);
      if (violation) reportLiteral(node);
      for (const index of presentationExpressions) reportHtml(expressions[index]);
    };
    walk.simple(ast, {
      Literal(node) {
        if (typeof node.value !== 'string') return;
        if (Object.hasOwn(ENGLISH_MESSAGES, node.value)) references.add(node.value);
        for (const suffix of ['label', 'help']) {
          const commandKey = `commands.${node.value}.${suffix}`;
          if (Object.hasOwn(ENGLISH_MESSAGES, commandKey)) references.add(commandKey);
          for (const candidate of Object.keys(ENGLISH_MESSAGES)) {
            if (candidate.startsWith('commands.')
                && candidate.endsWith(`.${node.value}.${suffix}`)) references.add(candidate);
          }
        }
      },
      CallExpression(node) {
        if (localizationCall(node)) {
          const key = node.arguments[0];
          recordKeyExpression(key);
          if (key?.type === 'Literal') {
            const message = ENGLISH_MESSAGES[key.value];
            const variants = typeof message === 'string' ? [message] : Object.values(message || {});
            const required = new Set(variants.flatMap(value => [...value.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/gu)]
              .map(match => match[1])));
            if (typeof message === 'object') required.add('count');
            const params = node.arguments[1];
            if (params?.type === 'ObjectExpression') {
              const supplied = new Set(params.properties.map(property => property.key?.name ?? property.key?.value));
              for (const placeholder of required) {
                if (!supplied.has(placeholder)) fail(file, node.loc.start.line,
                  `message ${key.value} requires parameter ${placeholder}`);
              }
            } else if (required.size) {
              fail(file, node.loc.start.line, `message ${key.value} requires parameters`);
            }
          }
        }
        const name = node.callee.type === 'MemberExpression' ? node.callee.property.name : node.callee.name;
        if (name === 'setAttribute' && node.arguments[0]?.type === 'Literal'
            && presentationAttributes.test(node.arguments[0].value)) report(node.arguments[1]);
        if (['alert', 'confirm', 'announce', 'announceGraph', 'showToast',
          'showInspectorMessage'].includes(name)) {
          node.arguments.slice(0, 2).forEach(report);
        }
        if (name === 'addActivityMessage') node.arguments.slice(0, 2).forEach(report);
        if (name === 'contextualHelpButtonHtml') node.arguments.slice(0, 2).forEach(report);
        if (name === 'element' && fileName === 'runner-panel.js') report(node.arguments[1]);
        if (name === 'element' && fileName === 'human-task-ui.js') report(node.arguments[3]);
        if (/(?:render|dialog|menu|presentation)/iu.test(name || '')) {
          for (const argument of node.arguments.filter(item => item.type === 'ObjectExpression')) {
            for (const property of argument.properties) {
              const propertyName = property.key?.name ?? property.key?.value;
              if (['label', 'help', 'title', 'message', 'emptyText', 'placeholder',
                'ariaLabel', 'ariaDescription'].includes(propertyName)) report(property.value);
            }
          }
        }
      },
      AssignmentExpression(node) {
        if (node.left.type !== 'MemberExpression') return;
        const property = node.left.property.name;
        const datasetProperty = node.left.object?.type === 'MemberExpression'
          && node.left.object.property.name === 'dataset'
          && ['tooltip', 'tooltipDisabled', 'contextualHelp', 'contextualHelpTitle'].includes(property);
        if (property === 'innerHTML') reportHtml(node.right);
        else if (presentationProperties.has(property) || datasetProperty) report(node.right);
      },
      Property(node) {
        const name = node.key?.name ?? node.key?.value;
        if (/Key$/u.test(name || '')
            && (node.value.type !== 'Literal' || Object.hasOwn(ENGLISH_MESSAGES, node.value.value))) {
          recordKeyExpression(node.value);
        }
      },
      VariableDeclarator(node) {
        if (node.id.type !== 'Identifier') return;
        if (/_KEY_BY_TYPE$/u.test(node.id.name)) {
          const value = node.init?.type === 'CallExpression' ? node.init.arguments[0] : node.init;
          value?.properties?.forEach(property => recordKeyExpression(property.value));
          return;
        }
        const presentationCollection = /(?:LABELS?|MESSAGES?|OPTIONS|TYPES|CLASSES)$/u.test(node.id.name);
        const textMap = /(?:_TEXT|DESCRIPTION_BY_TYPE)$/u.test(node.id.name);
        if (presentationCollection || textMap) reportPresentationMetadata(node.init, textMap);
      },
      ReturnStatement(node) {
        const inspectRecord = value => {
          if (!value) return;
          if (value.type === 'ObjectExpression') {
            for (const property of value.properties) {
              if (presentationRecordProperties.has(property.key?.name ?? property.key?.value)) {
                report(property.value);
              }
            }
          } else if (value.type === 'ConditionalExpression') {
            inspectRecord(value.consequent); inspectRecord(value.alternate);
          }
        };
        inspectRecord(node.argument);
      },
    });
  }
}

function auditEmbedShell() {
  const file = '../ravenroot-server/src/main/java/ai/ravenroot/server/embed/EmbedBrowserHttpHandler.java';
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const start = source.indexOf('String viewControls =');
  const end = source.indexOf('bytes(exchange, 200', start);
  if (start < 0 || end < 0) {
    fail(file, 1, 'could not locate the embedded viewer HTML shell');
    return;
  }
  const literals = source.slice(start, end).match(/"(?:\\.|[^"\\])*"/gu) || [];
  const html = literals.map(literal => {
    try { return JSON.parse(literal); } catch { return ''; }
  }).join('');
  const document = parseFragment(html);
  const visit = node => {
    const attrs = attributes(node);
    for (const state of ['error', 'expired', 'offline', 'incompatible']) {
      if (attrs[`data-i18n-${state}`]) references.add(`embed.state.${state}`);
    }
    for (const [attribute, keyAttribute] of [
      ['title', 'data-i18n-title'],
      ['aria-label', 'data-i18n-aria-label'],
      ['aria-description', 'data-i18n-aria-description'],
      ['aria-valuetext', 'data-i18n-aria-valuetext'],
    ]) {
      if (hasWords(attrs[attribute]) && !attrs[keyAttribute]) {
        fail(file, 1, `embedded shell ${attribute} has no message key`);
      }
      if (attrs[keyAttribute]) references.add(attrs[keyAttribute]);
    }
    if (attrs['data-i18n']) references.add(attrs['data-i18n']);
    if (node.nodeName === '#text' && hasWords(node.value)) {
      const parentAttrs = attributes(node.parentNode);
      if (!parentAttrs['data-i18n']) fail(file, 1, 'embedded shell visible text has no message key');
    }
    for (const child of node.childNodes || []) visit(child);
  };
  visit(document);
}

function auditCatalog() {
  const keyPattern = /^[A-Za-z][A-Za-z0-9]*(?:[.-][A-Za-z0-9]+)+$/u;
  for (const [key, message] of Object.entries(ENGLISH_MESSAGES)) {
    if (!keyPattern.test(key)) fail('src/ui-text.js', 1, `invalid message key ${key}`);
    const variants = typeof message === 'string' ? { other: message } : message;
    if (!variants || typeof variants.other !== 'string') {
      fail('src/ui-text.js', 1, `${key} has no other form`);
    }
  }
  for (const key of references) {
    if (!Object.hasOwn(ENGLISH_MESSAGES, key)) fail('catalog references', 1, `missing English message ${key}`);
  }
  for (const key of Object.keys(ENGLISH_MESSAGES)) {
    if (!references.has(key)) fail('catalog entries', 1, `unused English message ${key}`);
  }
  const productionCatalogs = fs.readdirSync(path.join(root, 'public'))
    .filter(file => /^ui-locales(?:\..+)?\.json$/u.test(file));
  for (const file of productionCatalogs) {
    try {
      const value = JSON.parse(fs.readFileSync(path.join(root, 'public', file), 'utf8'));
      const locales = value.catalogs?.map(catalog => catalog.locale) ?? [value.locale];
      if (locales.some(locale => locale && locale !== 'en')) {
        fail(`public/${file}`, 1, 'only the built-in English catalog may ship');
      }
    } catch { fail(`public/${file}`, 1, 'production locale configuration must be valid JSON'); }
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  auditHtml();
  auditJavascript();
  auditEmbedShell();
  auditCatalog();
  if (failures.length) {
    console.error(failures.join('\n'));
    process.exitCode = 1;
  } else {
    console.log(`UI localization coverage: ${Object.keys(ENGLISH_MESSAGES).length} English messages; ${references.size} keyed presentation references; 0 inline sink violations.`);
  }
}
