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
  const ownerOf = ancestors => [...ancestors].reverse().find(item =>
    ['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression', 'Program'].includes(item.type));
  const identifierOwners = new Map();
  walk.ancestor(ast, { Identifier(node, ancestors) { identifierOwners.set(node, ownerOf(ancestors)); } });
  const collectPattern = (pattern, value, scope, start, origin, owner) => {
    if (!pattern || !value) return;
    if (pattern.type === 'Identifier') {
      declarations.push({ name: pattern.name, value, scope, start, origin, owner });
      return;
    }
    if (pattern.type === 'AssignmentPattern') {
      collectPattern(pattern.left, value, scope, start, origin, owner);
      return;
    }
    if (pattern.type === 'ArrayPattern') {
      pattern.elements.forEach((entry, index) => {
        if (!entry) return;
        const values = value.type === 'ArrayExpression'
          ? value.elements.flatMap(item => item?.type === 'ArrayExpression' && item.elements[index]
            ? [item.elements[index]] : [])
          : [];
        if (values.length) collectPattern(entry,
          values.length === 1 ? values[0] : { type: 'ArrayExpression', elements: values },
          scope, start, origin, owner);
      });
      return;
    }
    if (pattern.type === 'ObjectPattern') {
      pattern.properties.forEach(entry => {
        const key = entry.key?.name ?? entry.key?.value;
        const objects = value.type === 'ObjectExpression' ? [value]
          : value.type === 'ArrayExpression' ? value.elements.filter(item => item?.type === 'ObjectExpression') : [];
        const values = objects.flatMap(object => object.properties
          .filter(item => (item.key?.name ?? item.key?.value) === key).map(item => item.value));
        if (values.length) collectPattern(entry.value,
          values.length === 1 ? values[0] : { type: 'ArrayExpression', elements: values },
          scope, start, origin, owner);
      });
    }
  };
  walk.ancestor(ast, {
    VariableDeclarator(node, ancestors) {
      if (!node.init) return;
      const scope = [...ancestors].reverse().find(ancestor => ancestor !== node
        && ['BlockStatement', 'Program', 'FunctionDeclaration', 'FunctionExpression',
          'ArrowFunctionExpression'].includes(ancestor.type)) || ast;
      collectPattern(node.id, node.init, scope, node.start, node, ownerOf(ancestors));
    },
    ForOfStatement(node, ancestors) {
      if (node.left.type !== 'VariableDeclaration') return;
      const declaration = node.left.declarations[0];
      const scope = node.body.type === 'BlockStatement' ? node.body : node;
      collectPattern(declaration.id, node.right, scope, node.start, declaration, ownerOf(ancestors));
    },
  });
  walk.ancestor(ast, { AssignmentExpression(node, ancestors) {
    if (node.left.type !== 'Identifier') return;
    const declaration = declarations.filter(item => item.name === node.left.name
      && item.scope.start <= node.start && item.scope.end >= node.end)
      .sort((a, b) => (a.scope.end - a.scope.start) - (b.scope.end - b.scope.start))[0];
    if (declaration) declarations.push({ ...declaration, value: node.right, start: node.start });
  } });
  return identifier => {
    const visible = declarations.filter(item => item.name === identifier.name
      && item.scope.start <= identifier.start && item.scope.end >= identifier.end)
      .sort((a, b) => (a.scope.end - a.scope.start) - (b.scope.end - b.scope.start) || b.start - a.start);
    if (!visible.length) return undefined;
    const nearest = visible[0];
    // A closure can run after its declaration, so later writes to the captured binding
    // are possible sources. Preserve ordinary lexical ordering for a direct local read.
    if (identifierOwners.get(identifier) !== nearest.owner) {
      const values = visible.filter(item => item.origin === nearest.origin).map(item => item.value);
      return values.length === 1 ? values[0] : { type: 'ArrayExpression', elements: values };
    }
    return visible.find(item => item.start < identifier.start)?.value;
  };
}

function outputLiterals(node, output = [], bindings = new Map(), seen = new Set()) {
  if (!node) return output;
  if (localizationCall(node)) {
    outputLiterals(node.arguments[1], output, bindings, seen);
    return output;
  }
  if (node.type === 'CallExpression' && ['String', 'escapeHtml', 'escapeAttribute'].includes(node.callee.name)) {
    return outputLiterals(node.arguments[0], output, bindings, seen);
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
function presentationFlow(ast, isExempt = () => false) {
  const bindings = createBindingResolver(ast);
  const functions = [];
  const owners = new Map();
  const identifierOwners = new Map();
  const calls = [];
  const sinks = [];
  const catches = [];
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
    Identifier(node, ancestors) { identifierOwners.set(node, [...ancestors].reverse().find(item => functionTypes.has(item.type))); },
    CallExpression(node, ancestors) { calls.push(node); owners.set(node,
      [...ancestors.slice(0, -1)].reverse().find(item => functionTypes.has(item.type))); },
    TryStatement(node, ancestors) {
      if (node.handler?.param?.type === 'Identifier') catches.push({
        name: node.handler.param.name, scope: node.handler.body, block: node.block,
        owner: [...ancestors].reverse().find(item => functionTypes.has(item.type)),
      });
    },
    Property(node, ancestors) {
      const name = node.key?.name ?? node.key?.value;
      if (isExempt(node)) return;
      if (['label', 'shortLabel', 'caption', 'help', 'title', 'message', 'emptyText', 'placeholder',
        'ariaLabel', 'ariaDescription', 'error', 'warning', 'description', 'detail', 'displayName', 'category'].includes(name)) {
        sinks.push({ expression: node.value, localizedError: name === 'error', owner: [...ancestors].reverse().find(item => functionTypes.has(item.type)) });
      }
    },
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
  const mapCallbackValues = (mapped, parameter, collection) => {
    const source = collection?.type === 'Identifier' ? bindingValue(bindings, collection) : collection;
    if (parameter?.type === 'Identifier') {
      mapped.set(parameter.name, source || collection);
      return;
    }
    if (parameter?.type !== 'ObjectPattern' || source?.type !== 'ArrayExpression') return;
    for (const entry of parameter.properties) {
      const key = entry.key?.name ?? entry.key?.value;
      const values = source.elements.flatMap(item => item?.type === 'ObjectExpression'
        ? item.properties.filter(property => (property.key?.name ?? property.key?.value) === key)
          .map(property => property.value)
        : []);
      const target = entry.value?.type === 'AssignmentPattern' ? entry.value.left : entry.value;
      if (target?.type === 'Identifier' && values.length) {
        mapped.set(target.name, values.length === 1 ? values[0] : { type: 'ArrayExpression', elements: values });
      }
    }
  };
  const pushedValues = (identifier, owner) => {
    const body = owner?.type === 'Program' ? owner : owner?.body;
    if (!body) return [];
    const values = [];
    walk.ancestor(body, { CallExpression(call, ancestors) {
      if (ancestors.slice(0, -1).some(item => functionTypes.has(item.type) && item !== owner)) return;
      if (call.callee.type === 'MemberExpression' && call.callee.object.type === 'Identifier'
          && call.callee.object.name === identifier.name && call.callee.property.name === 'push') {
        values.push(...call.arguments);
      }
    } });
    return values;
  };
  // Propagate locally thrown errors through calls/callbacks to catch bindings. Keep
  // developer diagnostics separate from productError's structured presentation key.
  const exceptionBlocks = new Map();
  for (const { node } of functions) exceptionBlocks.set(node.body, { owner: node, throws: new Map(), calls: new Set() });
  for (const caught of catches) exceptionBlocks.set(caught.block, { owner: caught.owner, throws: new Map(), calls: new Set() });
  for (const [block, summary] of exceptionBlocks) {
    const handled = (node, ancestors) => ancestors.some(item => item.type === 'TryStatement'
      && item.handler && item.block.start <= node.start && item.block.end >= node.end);
    walk.ancestor(block, {
      ThrowStatement(node, ancestors) {
        if (!ancestors.some(item => functionTypes.has(item.type)) && !handled(node, ancestors)) {
          summary.throws.set(node, { node: node.argument, owner: summary.owner });
        }
      },
      CallExpression(node, ancestors) {
        if (ancestors.some(item => functionTypes.has(item.type)) || handled(node, ancestors)) return;
        for (const fn of resolveCallables(node.callee, summary.owner)) summary.calls.add(fn.body);
      },
    });
  }
  let exceptionsChanged = true;
  while (exceptionsChanged) {
    exceptionsChanged = false;
    for (const summary of exceptionBlocks.values()) for (const block of summary.calls) {
      for (const [site, thrown] of exceptionBlocks.get(block)?.throws || []) {
        if (!summary.throws.has(site)) { summary.throws.set(site, thrown); exceptionsChanged = true; }
      }
    }
  }
  const thrownFrom = block => [...(exceptionBlocks.get(block)?.throws.values() || [])];
  function trace(node, owner, onLiteral, visiting = new Set(), substitutions = new Map(), localizedError = false) {
    if (!node || visiting.has(node)) return;
    const next = new Set(visiting).add(node);
    const recur = child => trace(child, owner, onLiteral, next, substitutions, localizedError);
    if (node.type === 'ChainExpression') return recur(node.expression);
    if (functionTypes.has(node.type)) return returns(node).forEach(recur);
    if (localizationCall(node)) return recur(node.arguments[1]);
    if (node.type === 'Identifier') {
      if (substitutions.has(node.name)) return recur(substitutions.get(node.name));
      const caught = catches.filter(item => item.name === node.name && item.scope.start <= node.start
        && item.scope.end >= node.end).sort((a, b) => (a.scope.end - a.scope.start) - (b.scope.end - b.scope.start))[0];
      if (caught) for (const thrown of thrownFrom(caught.block, caught.owner)) {
        trace(thrown.node, thrown.owner, onLiteral, next, substitutions, localizedError);
      }
      const parameterOwner = identifierOwners.get(node) || owner;
      parameterOwner?.params.forEach((raw, index) => {
        const param = raw.type === 'AssignmentPattern' ? raw.left : raw;
        if (param.type === 'Identifier' && param.name === node.name) {
          signatures.get(parameterOwner)?.add(String(index) + (localizedError ? ':error' : ''));
          if (raw.type === 'AssignmentPattern') recur(raw.right);
        } else if (param.type === 'ObjectPattern') {
          for (const property of param.properties) {
            const value = property.value?.type === 'AssignmentPattern' ? property.value.left : property.value;
            if (value?.name === node.name) {
              signatures.get(parameterOwner)?.add(`${index}.${property.key.name || property.key.value}${localizedError ? ':error' : ''}`);
              if (property.value.type === 'AssignmentPattern') recur(property.value.right);
            }
          }
        }
      });
      const value = bindingValue(bindings, node);
      if (value) recur(value);
      pushedValues(node, owner).forEach(recur);
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
      const project = (object, seen = new Set()) => {
        if (!object || seen.has(object)) return false;
        const visited = new Set(seen).add(object);
        if (object.type === 'Identifier') {
          const value = bindingValue(bindings, object);
          if (value && project(value, visited)) return true;
        }
        if (object.type === 'CallExpression' && nameOf(object.callee) === 'freeze') return project(object.arguments[0], visited);
        if (object.type === 'CallExpression') {
          const fn = resolveFunction(object.callee);
          if (fn && !visited.has(fn)) return returns(fn).map(value => project(value, new Set(visited).add(fn))).some(Boolean);
        }
        if (object.type === 'ArrayExpression') return object.elements.map(value => project(value, visited)).some(Boolean);
        if (object.type === 'ObjectExpression') {
          object.properties.filter(item => node.computed && node.property.type !== 'Literal'
            || (item.key?.name ?? item.key?.value) === nameOf(node)).forEach(item => recur(item.value));
          return true;
        }
        return false;
      };
      if (!project(node.object) && nameOf(node) === 'message') trace(node.object, owner, onLiteral, next, substitutions, false);
    } else if (node.type === 'NewExpression' && /^(?:Error|TypeError|RangeError)$/u.test(nameOf(node.callee))) {
      recur(node.arguments[0]);
    } else if (node.type === 'CallExpression') {
      const name = nameOf(node.callee);
      if (name === 'uiErrorReason') return trace(node.arguments[0], owner, onLiteral, next, substitutions, true);
      if (name === 'productError') { recur(node.arguments[localizedError ? 2 : 1]); return; }
      if (['escapeHtml', 'escapeAttribute', 'String'].includes(name)) return recur(node.arguments[0]);
      if (node.callee.type === 'MemberExpression' && ['join', 'trim', 'toString'].includes(name)) return recur(node.callee.object);
      if (node.callee.type === 'MemberExpression' && ['map', 'flatMap'].includes(name)) {
        const callback = resolveFunction(node.arguments[0]);
        if (!callback) return recur(node.arguments[0]);
        const mapped = new Map(substitutions);
        const first = callback.params[0]?.type === 'AssignmentPattern'
          ? callback.params[0].left : callback.params[0];
        // When the callback presents the entire collection element (rather than selecting an
        // object member), the producing call itself is a presentation flow. This catches the common
        // validator shape that returns an array populated through `push()`.
        if (first?.type === 'Identifier') recur(node.callee.object);
        mapCallbackValues(mapped, first, node.callee.object);
        return returns(callback).forEach(value => trace(value, callback, onLiteral,
          new Set(next).add(callback), mapped, localizedError));
      }
      if (node.callee.type === 'Identifier' && owner?.params.some(param => param.name === node.callee.name)) recur(node.callee);
      const fn = resolveFunction(node.callee);
      if (fn && !next.has(fn)) {
        const mapped = new Map(substitutions);
        fn.params.forEach((parameter, index) => {
          const param = parameter.type === 'AssignmentPattern' ? parameter.left : parameter;
          if (param.type === 'Identifier' && node.arguments[index]) mapped.set(param.name, node.arguments[index]);
        });
        for (const value of returns(fn)) trace(value, fn, onLiteral, new Set(next).add(fn), mapped, localizedError);
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
      pushedValues(node, owner).forEach(recur);
    } else if (node.type === 'ConditionalExpression') { recur(node.consequent); recur(node.alternate); }
    else if (node.type === 'LogicalExpression' || node.type === 'BinaryExpression') { recur(node.left); recur(node.right); }
    else if (node.type === 'Literal' || node.type === 'TemplateLiteral') {
      const expressions = node.expressions || [];
      const html = node.type === 'Literal' ? String(node.value || '') : node.quasis
        .map((item, index) => item.value.cooked + (index < expressions.length ? `__EXPR_${index}__` : '')).join('');
      // A helper may return one syntactic attribute fragment which is interpolated into a larger
      // start tag. parse5 necessarily reads that isolated fragment as a text node; it is still HTML
      // structure, not copy. Keep the allow-list to attributes whose values are machine data so a
      // product sentence cannot disappear merely because it begins with whitespace.
      if (/^\s+(?:(?:disabled|required|selected|checked|readonly|multiple)(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?|(?:value|min|max|step|data-[\w-]+)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))\s*$/u.test(html)) {
        expressions.forEach(recur);
        return;
      }
      const structural = html.replaceAll(/__EXPR_\d+__/gu, '');
      if (/^\s*(?:value|min|max|step|data-[\w-]+)\s*=\s*(?:""|'')\s*>?\s*$/u.test(structural)) {
        expressions.forEach(recur);
        return;
      }
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
      else if (node.callee.type === 'MemberExpression' && ['join', 'trim', 'toString'].includes(name)) {
        recur(node.callee.object);
      } else if (node.callee.type === 'MemberExpression' && ['map', 'flatMap'].includes(name)) {
        const callback = resolveFunction(node.arguments[0]);
        if (!callback) recur(node.arguments[0]);
        else {
          const mapped = new Map(substitutions);
          const first = callback.params[0]?.type === 'AssignmentPattern'
            ? callback.params[0].left : callback.params[0];
          if (first?.type === 'Identifier') {
            trace(node.callee.object, owner, onLiteral, new Set(next), substitutions);
          }
          mapCallbackValues(mapped, first, node.callee.object);
          returns(callback).forEach(value => traceHtml(value, callback, onLiteral,
            new Set(next).add(callback), mapped));
        }
      }
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
    for (const { expression, owner, html, localizedError } of sinks) {
      if (html) traceHtml(expression, owner, onLiteral);
      else trace(expression, owner, onLiteral, new Set(), new Map(), localizedError);
    }
    for (const call of calls) {
      const name = nameOf(call.callee);
      if (name === 'html') traceHtml(call.arguments[0], owners.get(call), onLiteral);
      if (name === 'insertAdjacentHTML') traceHtml(call.arguments[1], owners.get(call), onLiteral);
      const arguments_ = new Set(primitiveArguments.get(name) || []);
      // Both localization contexts and D3 expose .text(). Follow the argument's
      // lexical key shape; plain copy and callbacks remain presentation sinks.
      const catalogKey = (value, seen = new Set()) => {
        if (!value || seen.has(value)) return false;
        const next = new Set(seen).add(value);
        if (value.type === 'Identifier') return catalogKey(bindingValue(bindings, value), next);
        const prefix = value.type === 'Literal' ? value.value
          : value.type === 'TemplateLiteral' ? value.quasis[0]?.value.cooked : null;
        return typeof prefix === 'string' && /^[a-zA-Z][\w-]*(?:\.[\w-]*)+$/u.test(prefix);
      };
      if (name === 'text' && call.callee.type === 'MemberExpression' && call.arguments.length === 1
          && !catalogKey(call.arguments[0])) arguments_.add(0);
      for (const target of resolveCallables(call.callee, owners.get(call))) {
        for (const path of signatures.get(target) || []) arguments_.add(path);
      }
      for (const path of arguments_) {
        const [argumentPath, kind] = String(path).split(':');
        const [index, property] = argumentPath.split('.');
        let value = call.arguments[index];
        if (property) {
          if (value?.type === 'Identifier') value = bindingValue(bindings, value);
          value = value?.properties?.find(item => (item.key?.name ?? item.key?.value) === property)?.value;
        }
        trace(value, owners.get(call), onLiteral, new Set(), new Map(), kind === 'error');
      }
      if (['setAttribute', 'attr'].includes(name) && /^(?:title|placeholder|aria-(?:label|description|valuetext))$/u
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

// Catalog coverage alone cannot prove that a retained notice will repaint. Trace eager
// translation into activity recipes separately, retaining the ordinary authored-data boundary.
export function findRetainedMessageViolations(source) {
  const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType: 'module', locations: true });
  const bindings = createBindingResolver(ast);
  const found = new Set();
  const eager = (node, seen = new Set()) => {
    if (!node || seen.has(node)) return;
    seen = new Set(seen).add(node);
    if (['ArrowFunctionExpression', 'FunctionExpression', 'FunctionDeclaration'].includes(node.type)) return;
    if (node.type === 'Identifier') { eager(bindings(node), seen); return; }
    if (node.type === 'CallExpression') {
      const name = node.callee.type === 'MemberExpression' ? node.callee.property.name : node.callee.name;
      if (name === 'uiComputed') return;
      if (['uiText', 'uiErrorReason', 't'].includes(name) || name === 'text') {
        found.add(node.loc.start.line); return;
      }
      node.arguments.forEach(argument => eager(argument, seen));
      return;
    }
    for (const [key, value] of Object.entries(node)) {
      if (key === 'loc') continue;
      if (Array.isArray(value)) value.forEach(child => child?.type && eager(child, seen));
      else if (value?.type) eager(value, seen);
    }
  };
  walk.ancestor(ast, {
    CallExpression(node, ancestors) {
      if (ancestors.some(parent => parent !== node && parent.type === 'CallExpression'
          && parent.callee.name === 'uiComputed')) return;
      if (node.callee.name === 'uiMessage') eager(node.arguments[1]);
      if (node.callee.name === 'addActivityMessage') node.arguments.slice(0, 2).forEach(argument => eager(argument));
    },
  });
  return [...found].sort((a, b) => a - b);
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

// Follow exported return values into presentation sinks in importing modules. The ordinary flow
// analysis above is deliberately lexical; this second pass supplies the missing module boundary
// without recognizing validator names or maintaining a list of known English messages.
export function findCrossModulePresentationSinkViolations(sources) {
  const modules = new Map(Object.entries(sources).map(([file, source]) => {
    const ast = acorn.parse(source, { ecmaVersion: 'latest', sourceType: 'module', locations: true });
    return [file, { file, source, ast, bindings: createBindingResolver(ast) }];
  }));
  const functionTypes = new Set(['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression']);
  const functions = module => {
    const result = new Map();
    walk.simple(module.ast, {
      FunctionDeclaration(node) { if (node.id?.name) result.set(node.id.name, node); },
      VariableDeclarator(node) {
        if (node.id.type === 'Identifier' && functionTypes.has(node.init?.type)) result.set(node.id.name, node.init);
      },
    });
    return result;
  };
  const functionsByModule = new Map([...modules].map(([file, module]) => [file, functions(module)]));
  const exported = new Map();
  for (const [file, module] of modules) {
    walk.simple(module.ast, {
      ExportNamedDeclaration(node) {
        if (node.declaration?.type === 'FunctionDeclaration') {
          exported.set(`${file}:${node.declaration.id.name}`, node.declaration);
        }
        if (node.declaration?.type === 'VariableDeclaration') {
          node.declaration.declarations.forEach(item => {
            if (item.id.type === 'Identifier' && functionTypes.has(item.init?.type)) {
              exported.set(`${file}:${item.id.name}`, item.init);
            }
          });
        }
        node.specifiers.forEach(specifier => {
          const local = specifier.local?.name;
          const name = specifier.exported?.name;
          const fn = functionsByModule.get(file)?.get(local);
          if (name && fn) exported.set(`${file}:${name}`, fn);
        });
      },
    });
  }
  const resolveModule = (from, specifier) => {
    if (!specifier.startsWith('.')) return null;
    let candidate = path.posix.normalize(path.posix.join(path.posix.dirname(from), specifier));
    if (!path.posix.extname(candidate)) candidate += '.js';
    return modules.has(candidate) ? candidate : null;
  };
  const importsByModule = new Map();
  for (const [file, module] of modules) {
    const imports = new Map();
    walk.simple(module.ast, { ImportDeclaration(node) {
      const target = resolveModule(file, node.source.value);
      if (!target) return;
      node.specifiers.forEach(specifier => {
        if (specifier.type === 'ImportSpecifier') {
          imports.set(specifier.local.name, { file: target, name: specifier.imported.name });
        }
      });
    } });
    importsByModule.set(file, imports);
  }
  const summaries = new Map();
  const summarize = (file, name, visiting = new Set()) => {
    const id = `${file}:${name}`;
    if (summaries.has(id)) return summaries.get(id);
    if (visiting.has(id)) return [];
    const fn = exported.get(id);
    const module = modules.get(file);
    if (!fn || !module) return [];
    const literals = new Map();
    const sourceLines = module.source.split('\n');
    const recordLiteral = node => {
      const line = node.loc.start.line;
      const exempt = [sourceLines[line - 1], sourceLines[line - 2]].some(value =>
        /i18n-audit-exempt: (?:authored-content|protocol-token|diagnostic-content) - .{15,}/u.test(value || ''));
      if (!exempt) literals.set(node.start, node);
    };
    const next = new Set(visiting).add(id);
    const localFunctions = functionsByModule.get(file);
    const returns = [];
    if (fn.body.type !== 'BlockStatement') returns.push(fn.body);
    else walk.ancestor(fn.body, { ReturnStatement(node, ancestors) {
      if (!ancestors.slice(0, -1).some(item => functionTypes.has(item.type))) returns.push(node.argument);
    } });
    const collect = (node, substitutions = new Map(), seen = new Set()) => {
      if (!node || seen.has(node)) return;
      const walked = new Set(seen).add(node);
      if (localizationCall(node)) return;
      if (node.type === 'Literal') {
        if (typeof node.value === 'string' && hasWords(node.value)) recordLiteral(node);
      } else if (node.type === 'TemplateLiteral') {
        node.quasis.filter(item => hasWords(item.value.cooked)).forEach(recordLiteral);
      } else if (node.type === 'Identifier') {
        if (substitutions.has(node.name)) collect(substitutions.get(node.name), substitutions, walked);
        else {
          const value = bindingValue(module.bindings, node);
          if (value) collect(value, substitutions, walked);
          if (fn.body.type === 'BlockStatement') walk.ancestor(fn.body, { CallExpression(call, ancestors) {
            if (ancestors.slice(0, -1).some(item => functionTypes.has(item.type))) return;
            if (call.callee.type === 'MemberExpression' && call.callee.object.type === 'Identifier'
                && call.callee.object.name === node.name && call.callee.property.name === 'push') {
              call.arguments.forEach(argument => collect(argument, substitutions, walked));
            }
          } });
        }
      } else if (node.type === 'ArrayExpression') node.elements.forEach(item => collect(item, substitutions, walked));
      else if (node.type === 'ObjectExpression') node.properties
        .filter(item => ['label', 'shortLabel', 'caption', 'help', 'title', 'message', 'emptyText',
          'placeholder', 'ariaLabel', 'ariaDescription', 'error', 'warning', 'description', 'detail',
          'displayName', 'category', 'reason'].includes(item.key?.name ?? item.key?.value))
        .forEach(item => collect(item.value, substitutions, walked));
      else if (node.type === 'ConditionalExpression') {
        collect(node.consequent, substitutions, walked); collect(node.alternate, substitutions, walked);
      } else if (node.type === 'LogicalExpression' || node.type === 'BinaryExpression') {
        collect(node.left, substitutions, walked); collect(node.right, substitutions, walked);
      } else if (node.type === 'CallExpression') {
        const calleeName = node.callee.type === 'Identifier' ? node.callee.name : null;
        const local = calleeName && localFunctions.get(calleeName);
        if (local) {
          const mapped = new Map(substitutions);
          local.params.forEach((parameter, index) => {
            const target = parameter.type === 'AssignmentPattern' ? parameter.left : parameter;
            if (target.type === 'Identifier' && node.arguments[index]) mapped.set(target.name, node.arguments[index]);
          });
          const localReturns = [];
          if (local.body.type !== 'BlockStatement') localReturns.push(local.body);
          else walk.ancestor(local.body, { ReturnStatement(statement, ancestors) {
            if (!ancestors.slice(0, -1).some(item => functionTypes.has(item.type))) localReturns.push(statement.argument);
          } });
          localReturns.forEach(value => collect(value, mapped, walked));
        }
      }
    };
    returns.forEach(value => collect(value));
    const result = [...literals.values()].map(node => ({ file, line: node.loc.start.line }));
    summaries.set(id, result);
    return result;
  };
  const findings = new Set();
  if (modules.size === 1) {
    for (const [file, module] of modules) {
      findPresentationSinkViolations(module.source).forEach(line => findings.add(`${file}:${line}`));
    }
  }
  for (const [file, module] of modules) {
    const imports = importsByModule.get(file);
    if (!imports.size) continue;
    const bindings = module.bindings;
    const importedSummaries = (node, seen = new Set(), presentationBoundary = false) => {
      if (!node || seen.has(node)) return [];
      const next = new Set(seen).add(node);
      if (node.type === 'Identifier') {
        const imported = imports.get(node.name);
        if (imported) return presentationBoundary ? summarize(imported.file, imported.name) : [];
        const value = bindingValue(bindings, node);
        return value ? importedSummaries(value, next, presentationBoundary) : [];
      }
      if (node.type === 'CallExpression') {
        const direct = node.callee.type === 'Identifier' ? imports.get(node.callee.name) : null;
        if (direct) return presentationBoundary ? summarize(direct.file, direct.name) : [];
        if (localizationCall(node)) {
          return node.arguments.slice(1).flatMap(item => importedSummaries(item, next, true));
        }
        const name = node.callee.type === 'MemberExpression'
          ? (node.callee.computed ? node.callee.property.value : node.callee.property.name) : node.callee.name;
        if (node.callee.type === 'MemberExpression' && ['map', 'flatMap', 'join', 'trim', 'toString'].includes(name)) {
          return importedSummaries(node.callee.object, next,
            presentationBoundary || ['map', 'flatMap'].includes(name));
        }
        if (node.callee.type === 'Identifier' && ['escapeHtml', 'escapeAttribute', 'String'].includes(name)) {
          return node.arguments.flatMap(item => importedSummaries(item, next, presentationBoundary));
        }
        return [];
      }
      if (node.type === 'MemberExpression') return importedSummaries(node.object, next,
        presentationBoundary || (node.computed ? node.property.value : node.property.name) === 'reason');
      if (node.type === 'TemplateLiteral') return node.expressions.flatMap(item => importedSummaries(item, next, presentationBoundary));
      if (node.type === 'ArrayExpression') return node.elements.flatMap(item => importedSummaries(item, next, presentationBoundary));
      if (node.type === 'ObjectExpression') return node.properties.flatMap(item => importedSummaries(item.value, next, presentationBoundary));
      if (node.type === 'ConditionalExpression') return [
        ...importedSummaries(node.consequent, next, presentationBoundary),
        ...importedSummaries(node.alternate, next, presentationBoundary)];
      if (node.type === 'LogicalExpression' || node.type === 'BinaryExpression') return [
        ...importedSummaries(node.left, next, presentationBoundary),
        ...importedSummaries(node.right, next, presentationBoundary)];
      return [];
    };
    const record = expression => importedSummaries(expression, new Set(), true)
      .forEach(item => findings.add(`${item.file}:${item.line}`));
    walk.simple(module.ast, {
      AssignmentExpression(node) {
        if (node.left.type !== 'MemberExpression') return;
        const property = node.left.computed ? node.left.property.value : node.left.property.name;
        if (['textContent', 'innerText', 'innerHTML', 'title', 'placeholder', 'ariaLabel', 'ariaDescription',
          'ariaValueText', 'outerHTML'].includes(property)) record(node.right);
      },
      CallExpression(node) {
        const name = node.callee.type === 'MemberExpression'
          ? (node.callee.computed ? node.callee.property.value : node.callee.property.name) : node.callee.name;
        if (['alert', 'confirm', 'prompt', 'announce', 'announceGraph', 'showToast', 'showInspectorMessage',
          'addActivityMessage'].includes(name)) node.arguments.slice(0, 2).forEach(record);
        if (['setAttribute', 'attr'].includes(name)
            && /^(?:title|placeholder|aria-(?:label|description|valuetext))$/u.test(node.arguments[0]?.value || '')) {
          record(node.arguments[1]);
        }
      },
    });
  }
  return [...findings].sort();
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
    presentationFlow(ast, node => [lines[node.loc.start.line - 1], lines[node.loc.start.line - 2]].some(value =>
      /i18n-audit-exempt: (?:authored-content|protocol-token|diagnostic-content) - .{15,}/u.test(value || ''))).inspect(reportLiteral);
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
        if (['setAttribute', 'attr'].includes(name) && node.arguments[0]?.type === 'Literal'
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
  const moduleSources = Object.fromEntries(sources.filter(entry => entry.sourceType === 'module')
    .map(entry => [entry.file, fs.readFileSync(path.join(root, entry.file), 'utf8')]));
  for (const [file, source] of Object.entries(moduleSources)) {
    for (const line of findRetainedMessageViolations(source)) {
      fail(file, line, 'eager translation loses retained activity message semantics');
    }
  }
  for (const finding of findCrossModulePresentationSinkViolations(moduleSources)) {
    const separator = finding.lastIndexOf(':');
    const file = finding.slice(0, separator);
    const line = Number(finding.slice(separator + 1));
    const lines = moduleSources[file]?.split('\n') || [];
    const exempt = [lines[line - 1], lines[line - 2]].some(value =>
      /i18n-audit-exempt: (?:authored-content|protocol-token|diagnostic-content) - .{15,}/u.test(value || ''));
    if (!exempt) fail(file, line, 'cross-module product text reaches a presentation sink');
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
