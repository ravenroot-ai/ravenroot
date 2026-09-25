const INSPECTION_PATH = '/v1/graphs/inspect';

/**
 * Model the smallest successful graph-admission response exposed by the runtime.
 *
 * Existing browser specs provide only the runtime surfaces that matter to their scenario. Since
 * graph inspection is now a mandatory preflight, those synthetic runtimes must explicitly admit
 * the submitted graph before a Run or Play request can be exercised.
 */
export function respondWithSuccessfulGraphInspection(request, response, options = {}) {
  const pathname = new URL(request.url, 'http://runtime.fixture').pathname;
  if (pathname !== INSPECTION_PATH) return false;

  const origin = options.origin;
  const commonHeaders = {
    ...(origin ? { 'Access-Control-Allow-Origin': origin, Vary: 'Origin' } : {}),
    ...options.headers,
  };
  if (request.method === 'OPTIONS') {
    response.writeHead(204, {
      ...commonHeaders,
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'content-type, authorization',
    });
    response.end();
    return true;
  }
  if (request.method !== 'POST') return false;

  request.resume();
  const body = JSON.stringify({ valid: true, findings: [] });
  response.writeHead(200, {
    ...commonHeaders,
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  response.end(body);
  return true;
}
