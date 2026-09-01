// Why the node palette is empty, in the user's words.
//
// An empty palette has several causes and they are not interchangeable: a service that answered
// "you must authenticate" is a very different situation from a service that did not answer at all.
// Saying "catalog unavailable" for both is what made a working, fully registered node catalog look
// like a removed feature. The client never assumes which case it is in — it reports what the
// service actually answered.
//
// `error` is the failure raised by the catalog request, or null when there was none.
// `catalog` is the array the service returned, or null when no request has completed.
// `pending` is true while a request is in flight and no answer has arrived yet.

const EDITABLE_TAIL = 'Custom and unknown behaviors remain editable.';

export function catalogEmptyState(error, catalog, pending = false) {
  if (error) return failureState(error);
  if (catalog === null || catalog === undefined) {
    // "Connect to the service" is an instruction. Once the page connects on its own it is an
    // instruction the user can neither act on nor decline, so while the request is in flight the
    // palette says what is happening instead of asking for something that is already under way.
    if (pending) {
      return { kind: 'connecting', message: 'Connecting to the service to load programmable nodes…' };
    }
    return { kind: 'disconnected', message: 'Connect to the service to load programmable nodes.' };
  }
  if (!catalog.length) {
    return { kind: 'empty', message: `The service answered with an empty node catalog. ${EDITABLE_TAIL}` };
  }
  return { kind: 'available', message: '' };
}

function failureState(error) {
  const status = Number(error?.status) || 0;
  if (status === 401) {
    return {
      kind: 'authentication-required',
      message: 'The service replied that authentication is required (HTTP 401). '
        + `Paste an access token to load the node catalog. ${EDITABLE_TAIL}`,
    };
  }
  if (status === 403) {
    return {
      kind: 'access-revoked',
      message: 'The service refused the node catalog for this token (HTTP 403). '
        + `Access has been revoked. ${EDITABLE_TAIL}`,
    };
  }
  // Do not add "The service did not answer" for a
  // status-less error (`error.status` null/undefined -- the fetch promise itself rejected) and drop
  // it once `error.status` became a number (a response WAS received: a 404, a 500, anything not
  // 401/403). The status-less case is NOT "the service did not answer": a rejected fetch is a
  // response the browser could not read, and the established example is a response the service
  // actually sent -- `BrowserOriginPolicy.reject` answers 403 with a JSON body but never calls
  // `applyCorsResponse`, so the browser withholds it from the page and reports a rejected promise
  // indistinguishable from a network failure. Attributing that silence to the service is the same
  // false attribution of an unknowable cause. Neither case may say what the
  // service did or did not do -- only `reason(error)`, which is already the client's own observation
  // (and, for the status-less case, already enumerates the possibilities via `runtime-client.js`'s
  // hint) -- so there is nothing left to add here, and both cases share one sentence.
  return {
    kind: 'unreachable',
    message: `Node catalog unreachable: ${reason(error)}. ${EDITABLE_TAIL}`,
  };
}

function reason(error) {
  const message = String(error?.message || '').trim();
  return message || 'the request failed';
}
