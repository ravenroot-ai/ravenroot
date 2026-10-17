import { afterEach, describe, expect, it } from 'vitest';
import { RuntimeAuthorizationError, RuntimeRequestError, ProgramSourceRejectedError } from '../src/runtime-client.js';
import { productError } from '../src/product-error.js';
import { AssistantUnavailableError, failureFromEnvelope } from '../src/assistant-client.js';
import { CredentialLeakError, normalizeCredential } from '../src/credential-client.js';
import { GraphAuthoringClient } from '../src/graph-authoring-client.js';
import { createUiLocalization, UI_CATALOG_SCHEMA, uiErrorReason, useUiLocalization } from '../src/ui-text.js';

afterEach(() => useUiLocalization(createUiLocalization()));
function translate(messages) {
  useUiLocalization(createUiLocalization({ locale: 'fr', catalogs: { fr: { schema: UI_CATALOG_SCHEMA, locale: 'fr', messages } } }));
}

describe('error presentation metadata preserves diagnostic and transport identity', () => {
  it.each([Error, TypeError, RangeError])('retains the original %s instance, fields, cause, stack and serialized shape', ErrorClass => {
    const cause = new Error('original cause');
    const error = new ErrorClass('Original diagnostic', { cause });
    error.code = 'ORIGINAL_CODE';
    const before = JSON.stringify(error), stack = error.stack, fields = { ...error };
    expect(productError('common.unavailable', error)).toBe(error);
    expect(error).toBeInstanceOf(ErrorClass);
    expect(error.message).toBe('Original diagnostic');
    expect(error.cause).toBe(cause);
    expect(error.stack).toBe(stack);
    expect(JSON.stringify(error)).toBe(before);
    expect({ ...error }).toEqual(fields);
    expect(Object.getOwnPropertyDescriptor(error, 'uiMessageKey').enumerable).toBe(false);
    expect(Object.getOwnPropertyDescriptor(error, 'uiMessageParams').enumerable).toBe(false);
  });

  it('preserves server prose and correlation fields while translating assistant framing live', () => {
    const error = failureFromEnvelope(503, { code: 'PROVIDER_UNAVAILABLE', message: 'Exact provider prose', correlationId: 'ref-123' });
    const before = JSON.stringify(error), message = error.message;
    expect(error).toBeInstanceOf(AssistantUnavailableError);
    translate({ 'workspace.assistant.reference': 'Référence {correlationId} : {sentence}' });
    expect(uiErrorReason(error)).toBe('Référence ref-123 : Exact provider prose');
    expect(error.message).toBe(message);
    expect(JSON.stringify(error)).toBe(before);
    expect(JSON.parse(before)).toEqual({ name: 'AssistantUnavailableError', reason: null, code: 'PROVIDER_UNAVAILABLE', correlationId: 'ref-123', assistantReason: null, assistantReasonKnown: false });
  });

  it('retains credential leak class and protocol key without serializing presentation metadata', () => {
    let error;
    try { normalizeCredential({ reference: 'ref', accessToken: 'never render' }); } catch (caught) { error = caught; }
    expect(error).toBeInstanceOf(CredentialLeakError);
    const before = JSON.stringify(error), message = error.message;
    translate({ 'workspace.credential.unexpectedField': 'Champ interdit : {key}' });
    expect(uiErrorReason(error)).toBe('Champ interdit : accessToken');
    expect(error.message).toBe(message);
    expect(JSON.stringify(error)).toBe(before);
    expect(JSON.parse(before)).toEqual({ name: 'CredentialLeakError', key: 'accessToken' });
  });

  it('keeps graph-authoring server diagnostics exact and only localizes its absent-diagnostic fallback', async () => {
    const errorFor = async body => {
      const client = new GraphAuthoringClient('', { fetchImpl: async () => new Response(JSON.stringify(body), { status: 503 }) });
      return client.list().catch(error => error);
    };
    const external = await errorFor({ error: 'Exact server diagnostic' });
    const local = await errorFor({});
    translate({ 'workspace.graphAuthoring.requestFailed': 'Échec ({status})' });
    expect(uiErrorReason(external)).toBe('Exact server diagnostic');
    expect(external.uiMessageKey).toBeUndefined();
    expect(uiErrorReason(local)).toBe('Échec (503)');
    expect(local.message).toBe('Graph authoring request failed (503)');
    expect(JSON.stringify(local)).toBe('{}');
  });
});


it('keeps runtime request, authorization and program-rejection serialization at its existing wire fields', () => {
  const authorization = new RuntimeAuthorizationError('Authentication expired', 401);
  expect(JSON.parse(JSON.stringify(authorization))).toEqual({ name: 'RuntimeAuthorizationError', status: 401 });
  const request = new RuntimeRequestError('Exact server diagnostic', { status: 503, method: 'GET', path: '/v1/test', code: 'UPSTREAM', correlationId: 'ref', incidentId: 'incident', finding: 'finding' });
  expect(JSON.parse(JSON.stringify(request))).toEqual({ name: 'RuntimeRequestError', status: 503, method: 'GET', path: '/v1/test', code: 'UPSTREAM', correlationId: 'ref', incidentId: 'incident', finding: 'finding' });
  const rejected = new ProgramSourceRejectedError({ diagnostic: 'Exact compiler diagnostic', line: 4, column: 2, artifactId: 'author-artifact' });
  expect(JSON.parse(JSON.stringify(rejected))).toEqual({ name: 'ProgramSourceRejectedError', diagnostic: 'Exact compiler diagnostic', line: 4, column: 2, artifactId: 'author-artifact' });
  const messages = [authorization.message, request.message, rejected.message];
  translate({ 'workspace.runtime.auth.expired': 'Expiré', 'workspace.runtime.request.failure': '{reason} [{where}]{hint}', 'workspace.runtime.program.rejected': 'Refus : {diagnostic}' });
  expect(uiErrorReason(authorization)).toBe('Expiré');
  expect(uiErrorReason(request)).toBe('Exact server diagnostic [HTTP 503 GET /v1/test]');
  expect(uiErrorReason(rejected)).toBe('Refus : Exact compiler diagnostic');
  expect([authorization.message, request.message, rejected.message]).toEqual(messages);
});
