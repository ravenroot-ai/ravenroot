(() => {
  'use strict';

  const PROTOCOL_VERSION = 'ravenroot.embed/1';
  const EXCHANGE_PATH = '/v1/embed/exchange';
  const PROJECTION_PATH = '/v1/embed/projection';
  const encoder = new TextEncoder();
  const FAILURE_COPY = Object.freeze({
    error: 'The graph could not be displayed.',
    expired: 'This viewing session has expired.',
    offline: 'The graph service is unavailable.',
    incompatible: 'This graph requires a newer viewer.',
  });
  const THEMES = Object.freeze(['dark', 'light']);

  class EmbedRequestFailure extends Error {
    constructor(kind) {
      super('embed request failed');
      this.kind = kind;
    }
  }

  const exactKeys = (value, expected) => {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
    const actual = Object.keys(value).sort();
    return actual.length === expected.length
      && actual.every((key, index) => key === expected[index]);
  };

  const boundedString = (value, maximum = 256) =>
    typeof value === 'string' && value.length > 0 && value.length <= maximum;

  const base64url = (bytes) => {
    let binary = '';
    for (const byte of new Uint8Array(bytes)) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
  };

  const hex = (bytes) => Array.from(new Uint8Array(bytes),
    (byte) => byte.toString(16).padStart(2, '0')).join('');

  const int64 = (value) => {
    const bytes = new Uint8Array(8);
    new DataView(bytes.buffer).setBigInt64(0, BigInt(value));
    return bytes;
  };

  const field = (value) => {
    const bytes = encoder.encode(value);
    const framed = new Uint8Array(4 + bytes.length);
    new DataView(framed.buffer).setUint32(0, bytes.length);
    framed.set(bytes, 4);
    return framed;
  };

  const concat = (...parts) => {
    const result = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
    let offset = 0;
    for (const part of parts) {
      result.set(part, offset);
      offset += part.length;
    }
    return result;
  };

  const proofPayload = async (credential, revision, nonce, jti, path, issuedAt) => {
    const digest = hex(await crypto.subtle.digest('SHA-256', encoder.encode(credential)));
    return concat(
      field('ravenroot-embed-pop-v1'),
      field(digest),
      int64(revision),
      field(nonce),
      field(jti),
      field('POST'),
      field(path),
      int64(Date.parse(issuedAt)),
    );
  };

  const exchangeProofPayload = async (credential, revision, nonce, channelId,
    ackCorrelationId, jti, path, issuedAt) => {
    const digest = hex(await crypto.subtle.digest('SHA-256', encoder.encode(credential)));
    return concat(
      field('ravenroot-embed-pop-ack-v1'),
      field(digest),
      int64(revision),
      field(nonce),
      field(channelId),
      field(ackCorrelationId),
      field(jti),
      field('POST'),
      field(path),
      int64(Date.parse(issuedAt)),
    );
  };

  const sign = async (privateKey, credential, revision, nonce, jti, path, issuedAt) =>
    base64url(await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      privateKey,
      await proofPayload(credential, revision, nonce, jti, path, issuedAt),
    ));

  const signExchange = async (privateKey, credential, revision, nonce, channelId,
    ackCorrelationId, jti, issuedAt) => base64url(await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    privateKey,
    await exchangeProofPayload(credential, revision, nonce, channelId,
      ackCorrelationId, jti, EXCHANGE_PATH, issuedAt),
  ));

  const postJson = async (path, body, bearer) => {
    const headers = { 'Content-Type': 'application/json' };
    if (bearer !== undefined) headers.Authorization = `Bearer ${bearer}`;
    let response;
    try {
      response = await fetch(path, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
      });
    } catch {
      throw new EmbedRequestFailure('offline');
    }
    if (!response.ok) {
      if (response.status === 403) throw new EmbedRequestFailure('expired');
      if (response.status === 503) throw new EmbedRequestFailure('offline');
      throw new EmbedRequestFailure('error');
    }
    return response.json();
  };

  const readBootstrap = () => {
    const node = document.getElementById('ravenroot-embed-bootstrap');
    if (node === null) throw new Error('bootstrap unavailable');
    const value = JSON.parse(node.textContent);
    const keys = ['acknowledgementId', 'challenge', 'channelId', 'exchangeId', 'expiresAt',
      'grantRevision', 'parentOrigin', 'theme', 'viewerOrigin'];
    if (!exactKeys(value, keys)
        || !boundedString(value.exchangeId)
        || !boundedString(value.challenge)
        || !boundedString(value.channelId)
        || !boundedString(value.acknowledgementId)
        || !/^[1-9][0-9]{0,18}$/u.test(value.grantRevision)
        || !boundedString(value.expiresAt)
        || !boundedString(value.viewerOrigin, 2048)
        || !boundedString(value.parentOrigin, 2048)
        || (value.theme !== null && !THEMES.includes(value.theme))
        || location.origin !== value.viewerOrigin
        || window.parent === window) {
      throw new Error('bootstrap invalid');
    }
    if (Date.parse(value.expiresAt) <= Date.now()) throw new EmbedRequestFailure('expired');
    const revision = BigInt(value.grantRevision);
    if (revision > 9223372036854775807n) throw new Error('bootstrap invalid');
    return { ...value, revision };
  };

  const correlationId = () => crypto.randomUUID();

  const initialTheme = override => {
    if (override !== null) return override;
    const media = globalThis.matchMedia?.('(prefers-color-scheme: dark)');
    return media ? (media.matches ? 'dark' : 'light') : 'dark';
  };

  const showFailure = kind => {
    const state = Object.hasOwn(FAILURE_COPY, kind) ? kind : 'error';
    const root = document.getElementById('ravenroot-embed-viewer');
    const status = root?.querySelector('[data-viewer-status]');
    if (root === null || status === null) return;
    root.dataset.viewerState = state;
    status.textContent = FAILURE_COPY[state];
  };

  let viewer = null;
  const run = async () => {
    const bootstrap = readBootstrap();
    const theme = initialTheme(bootstrap.theme);
    if (document.documentElement.dataset.theme !== theme) {
      document.documentElement.dataset.theme = theme;
    }
    document.documentElement.style.colorScheme = theme;
    // The one-use launch ticket has served its purpose. Remove it before any network request, history
    // entry, referrer or parent-visible steady state can retain the credential-bearing query string.
    history.replaceState(null, '', '/v1/embed/launch');
    const sendToParent = (type, correlation) => window.parent.postMessage({
      protocolVersion: PROTOCOL_VERSION,
      channelId: bootstrap.channelId,
      correlationId: correlation,
      direction: 'viewer-to-parent',
      type,
    }, bootstrap.parentOrigin);

    let protocolReady = false;
    addEventListener('message', (event) => {
      const message = event.data;
      const keys = ['channelId', 'correlationId', 'direction', 'protocolVersion', 'type'];
      if (event.source !== window.parent
          || event.origin !== bootstrap.parentOrigin
          || event.ports.length !== 0
          || !exactKeys(message, keys)
          || message.protocolVersion !== PROTOCOL_VERSION
          || message.channelId !== bootstrap.channelId
          || message.direction !== 'parent-to-viewer'
          || !protocolReady
          || message.type !== 'PING'
          || !boundedString(message.correlationId)) return;
      sendToParent('PONG', message.correlationId);
    });

    const ackCorrelationId = await new Promise((resolve, reject) => {
      const correlation = correlationId();
      const timeout = setTimeout(() => {
        removeEventListener('message', receiveAck);
        reject(new EmbedRequestFailure('expired'));
      }, Math.max(1, Date.parse(bootstrap.expiresAt) - Date.now()));
      const receiveAck = (event) => {
        const message = event.data;
        const keys = ['channelId', 'correlationId', 'direction', 'protocolVersion', 'type'];
        if (event.source !== window.parent
            || event.origin !== bootstrap.parentOrigin
            || event.ports.length !== 0
            || !exactKeys(message, keys)
            || message.protocolVersion !== PROTOCOL_VERSION
            || message.channelId !== bootstrap.channelId
            || message.correlationId !== correlation
            || message.direction !== 'parent-to-viewer'
            || message.type !== 'ACK') return;
        clearTimeout(timeout);
        removeEventListener('message', receiveAck);
        resolve(correlation);
      };
      addEventListener('message', receiveAck);
      window.parent.postMessage({
        protocolVersion: PROTOCOL_VERSION,
        channelId: bootstrap.channelId,
        correlationId: correlation,
        direction: 'viewer-to-parent',
        type: 'HELLO',
        acknowledgementId: bootstrap.acknowledgementId,
      }, bootstrap.parentOrigin);
    });

    const keyPair = await crypto.subtle.generateKey(
      { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
    if (keyPair.privateKey.extractable !== false || keyPair.publicKey.extractable !== true) {
      throw new Error('key isolation unavailable');
    }
    const publicKey = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
    if (!boundedString(publicKey.x) || !boundedString(publicKey.y)) {
      throw new Error('public key unavailable');
    }

    const exchangeIssuedAt = new Date().toISOString();
    const exchangeJti = correlationId();
    const exchanged = await postJson(EXCHANGE_PATH, {
      exchangeId: bootstrap.exchangeId,
      channelId: bootstrap.channelId,
      ackCorrelationId,
      keyX: publicKey.x,
      keyY: publicKey.y,
      nonce: bootstrap.challenge,
      jti: exchangeJti,
      issuedAt: exchangeIssuedAt,
      signature: await signExchange(keyPair.privateKey, bootstrap.exchangeId, bootstrap.revision,
        bootstrap.challenge, bootstrap.channelId, ackCorrelationId, exchangeJti, exchangeIssuedAt),
    });
    if (!exactKeys(exchanged, ['bearer', 'challenge', 'expiresAt', 'tokenType'])
        || exchanged.tokenType !== 'Bearer'
        || !boundedString(exchanged.bearer)
        || !boundedString(exchanged.challenge)
        || !boundedString(exchanged.expiresAt)) {
      throw new Error('exchange response invalid');
    }

    const projectionIssuedAt = new Date().toISOString();
    const projectionJti = correlationId();
    const projection = await postJson(PROJECTION_PATH, {
      nonce: exchanged.challenge,
      jti: projectionJti,
      issuedAt: projectionIssuedAt,
      signature: await sign(keyPair.privateKey, exchanged.bearer, bootstrap.revision,
        exchanged.challenge, projectionJti, PROJECTION_PATH, projectionIssuedAt),
    }, exchanged.bearer);
    if (projection === null || typeof projection !== 'object' || Array.isArray(projection)) {
      throw new Error('projection response invalid');
    }

    // This direct module call is the only projection handoff. The value remains in the viewer realm
    // and this closure: it is never published on window, storage, the URL, or postMessage.
    const { createEmbedViewer } = await import('/embed-viewer.js');
    viewer = createEmbedViewer(document.getElementById('ravenroot-embed-viewer'), { theme });
    await viewer.mount(projection);
    protocolReady = true;
    sendToParent('READY', correlationId());
  };

  run().catch(async failure => {
    viewer?.destroy({ preserveState: true });
    // Stable, non-sensitive failure signal. There is deliberately no console output or error detail.
    showFailure(failure?.kind ?? 'error');
    try {
      const bootstrap = readBootstrap();
      window.parent.postMessage({
        protocolVersion: PROTOCOL_VERSION,
        channelId: bootstrap.channelId,
        correlationId: correlationId(),
        direction: 'viewer-to-parent',
        type: 'FAILED',
      }, bootstrap.parentOrigin);
    } catch {
      // A malformed bootstrap has no safe target and therefore produces no outbound message.
    }
  });
})();
