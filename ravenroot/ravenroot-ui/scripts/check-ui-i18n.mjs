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
const fail = (file, line, message) => {
  const failure = `${file}:${line}: ${message}`;
  if (!failures.includes(failure)) failures.push(failure);
};
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
  && ((node.callee.type === 'Identifier' && ['uiText', 'uiMessage', 't'].includes(node.callee.name))
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

function bindingValue(bindings, identifier) {
  return typeof bindings === 'function' ? bindings(identifier) : bindings.get(identifier.name);
}

function createBindingResolver(ast) {
  const declarations = [];
  walk.ancestor(ast, {
    VariableDeclarator(node, ancestors) {
      if (node.id.type !== 'Identifier' || !node.init) return;
      const scope = [...ancestors].reverse().find(ancestor => ancestor !== node
        && ['BlockStatement', 'Program', 'FunctionDeclaration', 'FunctionExpression',
          'ArrowFunctionExpression'].includes(ancestor.type)) || ast;
      declarations.push({ name: node.id.name, value: node.init, scope, start: node.start });
    },
  });
  walk.ancestor(ast, { AssignmentExpression(node, ancestors) {
    if (node.left.type !== 'Identifier') return;
    const declaration = declarations.filter(item => item.name === node.left.name
      && item.scope.start <= node.start && item.scope.end >= node.end)
      .sort((a, b) => (a.scope.end - a.scope.start) - (b.scope.end - b.scope.start))[0];
    if (declaration) declarations.push({ ...declaration, value: node.right, start: node.start });
  } });
  return identifier => declarations.filter(declaration => declaration.name === identifier.name
      && declaration.start < identifier.start
      && declaration.scope.start <= identifier.start && declaration.scope.end >= identifier.end)
    .sort((left, right) => (left.scope.end - left.scope.start) - (right.scope.end - right.scope.start)
      || right.start - left.start)[0]?.value;
}

function outputLiterals(node, output = [], bindings = new Map(), seen = new Set()) {
  if (!node) return output;
  if (localizationCall(node)) {
    outputLiterals(node.arguments[1], output, bindings, seen);
    return output;
  }
  if (node.type === 'Identifier' && !seen.has(node.start)) {
    const value = bindingValue(bindings, node);
    if (!value) return output;
    seen.add(node.start);
    outputLiterals(value, output, bindings, seen);
    return output;
  }
  if (node.type === 'MemberExpression' && !node.computed && node.object.type === 'Identifier') {
    const object = bindingValue(bindings, node.object);
    if (object?.type === 'ObjectExpression') {
      const property = object.properties.find(item => item.type === 'Property'
        && (item.key.name ?? item.key.value) === node.property.name);
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
  if (node.type === 'ObjectExpression') {
    node.properties.forEach(property => outputLiterals(property.value, output, bindings, seen));
  } else if (node.type === 'ArrayExpression') {
    node.elements.forEach(element => outputLiterals(element, output, bindings, seen));
  }
  return output;
}

// Derive wrapper signatures from their bodies, rather than recognizing wrapper names.
// A parameter reaching a DOM/native-dialog sink makes that argument a sink at every call,
// transitively. Return expressions and lexical aliases are followed at the use site.
function presentationFlow(ast) {
  const bindings = createBindingResolver(ast);
  const functions = [];
  const owners = new Map();
  const calls = [];
  const sinks = [];
  const signatures = new Map();
  const functionTypes = new Set(['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression']);
  const nameOf = node => node?.type === 'MemberExpression'
    ? (node.computed ? node.property.value : node.property.name) : node?.name;
  const primitiveArguments = new Map([
    ['alert', [0]], ['confirm', [0]], ['prompt', [0]], ['announce', [0]],
    ['announceGraph', [0]], ['showToast', [0]], ['showInspectorMessage', [0]],
    ['addActivityMessage', [0, 1]], ['contextualHelpButtonHtml', [0, 1]],
    // Injected presentation callbacks are part of the UI component contract.
    ['notify', [0]], ['setCustomValidity', [0]],
  ]);
  walk.ancestor(ast, {
    Function(node, ancestors) {
      const parent = ancestors.at(-2);
      const name = node.id?.name || (parent?.type === 'VariableDeclarator' ? parent.id.name
        : parent?.type === 'Property' ? nameOf(parent.key) : null);
      const scope = [...ancestors.slice(0, -1)].reverse().find(item =>
        functionTypes.has(item.type) || item.type === 'Program') || ast;
      functions.push({ node, name, scope });
      signatures.set(node, new Set());
    },
    CallExpression(node, ancestors) { calls.push(node); owners.set(node,
      [...ancestors.slice(0, -1)].reverse().find(item => functionTypes.has(item.type))); },
    AssignmentExpression(node, ancestors) {
      if (node.left.type !== 'MemberExpression') return;
      const property = nameOf(node.left);
      if (['textContent', 'innerText', 'title', 'placeholder', 'ariaLabel', 'ariaDescription',
        'ariaValueText', 'innerHTML', 'outerHTML'].includes(property)) sinks.push({ expression: node.right, html: ['innerHTML', 'outerHTML'].includes(property),
        owner: [...ancestors].reverse().find(item => functionTypes.has(item.type)) });
    },
  });
  const resolveFunction = callee => {
    if (functionTypes.has(callee?.type)) return callee;
    if (callee?.type !== 'Identifier') return null;
    const alias = bindingValue(bindings, callee);
    if (functionTypes.has(alias?.type)) return alias;
    if (alias?.type === 'Identifier' && alias.name !== callee.name) return resolveFunction(alias);
    return functions.filter(item => item.name === callee.name && item.scope.start <= callee.start
      && item.scope.end >= callee.end).sort((a, b) =>
      (a.scope.end - a.scope.start) - (b.scope.end - b.scope.start))[0]?.node;
  };
  // Resolve callable parameters from invocation sites and aliases. A monotone
  // fixed point handles forwarding chains without recursively rescanning every call.
  const callbackBindings = new Map(functions.map(({ node }) => [node, new Map()]));
  const resolveCallables = (callee, owner, seen = new Set()) => {
    if (!callee || seen.has(callee)) return [];
    const next = new Set(seen).add(callee);
    const direct = resolveFunction(callee);
    if (direct) return [direct];
    if (callee.type !== 'Identifier') return [];
    const alias = bindingValue(bindings, callee);
    if (alias && alias !== callee) {
      const targets = resolveCallables(alias, owner, next);
      if (targets.length) return targets;
    }
    return [...(callbackBindings.get(owner)?.get(callee.name) || [])];
  };
  let callbacksChanged = true;
  while (callbacksChanged) {
    callbacksChanged = false;
    for (const call of calls) {
      for (const target of resolveCallables(call.callee, owners.get(call))) {
        target.params.forEach((raw, index) => {
          const param = raw.type === 'AssignmentPattern' ? raw.left : raw;
          if (param.type !== 'Identifier') return;
          const parameters = callbackBindings.get(target);
          if (!parameters.has(param.name)) parameters.set(param.name, new Set());
          const values = parameters.get(param.name);
          for (const callback of resolveCallables(call.arguments[index], owners.get(call))) {
            if (!values.has(callback)) { values.add(callback); callbacksChanged = true; }
          }
        });
      }
    }
  }
  const returns = fn => {
    if (fn.body.type !== 'BlockStatement') return [fn.body];
    const result = [];
    walk.ancestor(fn.body, { ReturnStatement(node, ancestors) {
      if (!ancestors.some(item => functionTypes.has(item.type))) result.push(node.argument);
    } });
    return result;
  };
  function trace(node, owner, onLiteral, visiting = new Set(), substitutions = new Map()) {
    if (!node || visiting.has(node)) return;
    const next = new Set(visiting).add(node);
    const recur = child => trace(child, owner, onLiteral, next, substitutions);
    if (node.type === 'ChainExpression') return recur(node.expression);
    if (functionTypes.has(node.type)) return returns(node).forEach(recur);
    if (localizationCall(node)) return recur(node.arguments[1]);
    if (node.type === 'Identifier') {
      if (substitutions.has(node.name)) return recur(substitutions.get(node.name));
      owner?.params.forEach((raw, index) => {
        const param = raw.type === 'AssignmentPattern' ? raw.left : raw;
        if (param.type === 'Identifier' && param.name === node.name) {
          signatures.get(owner)?.add(String(index));
          if (raw.type === 'AssignmentPattern') recur(raw.right);
        } else if (param.type === 'ObjectPattern') {
          for (const property of param.properties) {
            const value = property.value?.type === 'AssignmentPattern' ? property.value.left : property.value;
            if (value?.name === node.name) {
              signatures.get(owner)?.add(`${index}.${property.key.name || property.key.value}`);
              if (property.value.type === 'AssignmentPattern') recur(property.value.right);
            }
          }
        }
      });
      const value = bindingValue(bindings, node);
      if (value) recur(value);
    } else if (node.type === 'Literal') {
      if (typeof node.value === 'string' && hasWords(node.value)) onLiteral(node);
    } else if (node.type === 'TemplateLiteral') {
      node.quasis.filter(item => hasWords(item.value.cooked)).forEach(onLiteral);
      node.expressions.forEach(recur);
    } else if (node.type === 'ConditionalExpression') {
      recur(node.consequent); recur(node.alternate);
    } else if (node.type === 'LogicalExpression' || (node.type === 'BinaryExpression' && node.operator === '+')) {
      recur(node.left); recur(node.right);
    } else if (node.type === 'ObjectExpression') {
      node.properties.forEach(item => recur(item.value));
    } else if (node.type === 'ArrayExpression') node.elements.forEach(recur);
    else if (node.type === 'MemberExpression') {
      let object = node.object;
      if (object.type === 'Identifier') object = bindingValue(bindings, object) || object;
      if (object.type === 'CallExpression' && nameOf(object.callee) === 'freeze') object = object.arguments[0];
      if (object?.type === 'ObjectExpression') {
        object.properties.filter(item => node.computed && node.property.type !== 'Literal'
          || (item.key?.name ?? item.key?.value) === nameOf(node)).forEach(item => recur(item.value));
      }
    } else if (node.type === 'CallExpression') {
      const name = nameOf(node.callee);
      if (['escapeHtml', 'escapeAttribute', 'String'].includes(name)) return recur(node.arguments[0]);
      if (node.callee.type === 'MemberExpression' && ['join', 'trim', 'toString'].includes(name)) return recur(node.callee.object);
      if (node.callee.type === 'Identifier' && owner?.params.some(param => param.name === node.callee.name)) recur(node.callee);
      const fn = resolveFunction(node.callee);
      if (fn && !next.has(fn)) {
        const mapped = new Map(substitutions);
        fn.params.forEach((parameter, index) => {
          const param = parameter.type === 'AssignmentPattern' ? parameter.left : parameter;
          if (param.type === 'Identifier' && node.arguments[index]) mapped.set(param.name, node.arguments[index]);
        });
        for (const value of returns(fn)) trace(value, owner, onLiteral, new Set(next).add(fn), mapped);
      }
    }
  }
  function traceHtml(node, owner, onLiteral, seen = new Set(), substitutions = new Map()) {
    if (!node || seen.has(node)) return;
    const next = new Set(seen).add(node);
    const recur = child => traceHtml(child, owner, onLiteral, next, substitutions);
    if (localizationCall(node)) { onLiteral(node); return; }
    if (node.type === 'Identifier') {
      if (substitutions.has(node.name)) return recur(substitutions.get(node.name));
      const value = bindingValue(bindings, node);
      if (value) recur(value); else trace(node, owner, onLiteral);
    } else if (node.type === 'ConditionalExpression') { recur(node.consequent); recur(node.alternate); }
    else if (node.type === 'LogicalExpression' || node.type === 'BinaryExpression') { recur(node.left); recur(node.right); }
    else if (node.type === 'Literal' || node.type === 'TemplateLiteral') {
      const expressions = node.expressions || [];
      const html = node.type === 'Literal' ? String(node.value || '') : node.quasis
        .map((item, index) => item.value.cooked + (index < expressions.length ? `__EXPR_${index}__` : '')).join('');
      const inspectText = text => {
        if (hasWords(text.replaceAll(/__EXPR_\d+__/gu, ''))) onLiteral(node);
        for (const match of text.matchAll(/__EXPR_(\d+)__/gu)) recur(expressions[Number(match[1])]);
      };
      const visit = item => {
        if (item.nodeName === '#text') inspectText(item.value);
        for (const attr of item.attrs || []) if (/^(?:title|placeholder|aria-(?:label|description|valuetext))$/u.test(attr.name)) inspectText(attr.value);
        for (const child of item.childNodes || []) visit(child);
      };
      visit(parseFragment(html));
    } else if (node.type === 'CallExpression') {
      const name = nameOf(node.callee);
      if (['escapeHtml', 'escapeAttribute'].includes(name)) trace(node.arguments[0], owner, onLiteral);
      else if (name !== 'htmlText') {
        const fn = resolveFunction(node.callee);
        if (fn && !next.has(fn)) {
          const mapped = new Map(substitutions);
          fn.params.forEach((param, index) => { if (param.type === 'Identifier' && node.arguments[index]) mapped.set(param.name, node.arguments[index]); });
          returns(fn).forEach(value => traceHtml(value, fn, onLiteral, new Set(next).add(fn), mapped));
        }
      }
    }
  }
  const inspect = onLiteral => {
    for (const { expression, owner, html } of sinks) (html ? traceHtml : trace)(expression, owner, onLiteral);
    for (const call of calls) {
      const name = nameOf(call.callee);
      if (name === 'insertAdjacentHTML') traceHtml(call.arguments[1], owners.get(call), onLiteral);
      const arguments_ = new Set(primitiveArguments.get(name) || []);
      for (const target of resolveCallables(call.callee, owners.get(call))) {
        for (const path of signatures.get(target) || []) arguments_.add(path);
      }
      for (const path of arguments_) {
        const [index, property] = String(path).split('.');
        let value = call.arguments[index];
        if (property) {
          if (value?.type === 'Identifier') value = bindingValue(bindings, value);
          value = value?.properties?.find(item => (item.key?.name ?? item.key?.value) === property)?.value;
        }
        trace(value, owners.get(call), onLiteral);
      }
      if (name === 'setAttribute' && /^(?:title|placeholder|aria-(?:label|description|valuetext))$/u
        .test(call.arguments[0]?.value || '')) trace(call.arguments[1], owners.get(call), onLiteral);
    }
  };
  let count = -1;
  while (count !== [...signatures.values()].reduce((sum, set) => sum + set.size, 0)) {
    count = [...signatures.values()].reduce((sum, set) => sum + set.size, 0);
    inspect(() => {});
  }
  return { inspect, resolveFunction, returns, bindings };
}

export function findIndirectPresentationLiterals(source) {
  const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType: 'module', locations: true });
  const found = new Map();
  presentationFlow(ast).inspect(node => found.set(node.start, node));
  return [...found.values()];
}

export function findPresentationSinkViolations(source, { sourceType = 'module' } = {}) {
  const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType, locations: true });
  const bindings = createBindingResolver(ast);
  const violations = [];
  presentationFlow(ast).inspect(item => violations.push(item.loc.start.line));
  const inspect = node => violations.push(...outputLiterals(node, [], bindings).map(item => item.loc.start.line));
  const presentationRecordProperties = new Set(['label', 'shortLabel', 'caption', 'help', 'title',
    'message', 'emptyText', 'placeholder', 'ariaLabel', 'ariaDescription', 'error', 'warning', 'description', 'detail', 'displayName', 'category']);
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
    'ariaLabel', 'ariaDescription', 'error', 'warning', 'description', 'detail', 'displayName', 'category',
  ]);
  for (const entry of sources) {
    const { file, sourceType } = entry;
    const fileName = path.basename(file);
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    const lines = source.split('\n');
    const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType, locations: true });
    const bindings = createBindingResolver(ast);
    const reportLiteral = literal => {
      const line = literal.loc.start.line;
      const exemption = [lines[line - 1], lines[line - 2]].find(value =>
        /i18n-audit-exempt: (?:authored-content|protocol-token|diagnostic-content) - .{15,}/u.test(value || ''));
      if (!exemption) {
        fail(file, line, 'inline product text reaches a presentation sink');
      }
    };
    presentationFlow(ast).inspect(reportLiteral);
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
        if (presentationRecordProperties.has(name)) report(node.value);
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
