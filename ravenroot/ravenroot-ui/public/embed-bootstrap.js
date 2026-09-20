(() => {
  'use strict';

  const PROTOCOL_VERSION = 'ravenroot.embed/1';
  const EXCHANGE_PATH = '/v1/embed/exchange';
  const PROJECTION_PATH = '/v1/embed/projection';
  const OBSERVATION_PATH = '/v1/embed/observation';
  const RUNS_PATH = '/v1/embed/runs';
  const START_EXECUTION_PATH = '/v1/embed/executions';
  const MAX_STREAM_FRAME_BYTES = 64 * 1024;
  const MAX_OBSERVATION_RETRIES = 5;
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

  const postStream = async (path, body, bearer, signal) => {
    let response;
    try {
      response = await fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream',
          Authorization: `Bearer ${bearer}` },
        body: JSON.stringify(body), credentials: 'omit', cache: 'no-store', redirect: 'error',
        referrerPolicy: 'no-referrer', signal,
      });
    } catch {
      throw new EmbedRequestFailure('offline');
    }
    if (response.status === 403) throw new EmbedRequestFailure('expired');
    if (!response.ok || !response.body?.getReader) throw new EmbedRequestFailure('offline');
    return response.body.getReader();
  };

  const parseFrame = raw => {
    let type = 'message';
    let id = '';
    const data = [];
    for (const line of raw.split('\n')) {
      if (!line || line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const fieldName = colon < 0 ? line : line.slice(0, colon);
      const fieldValue = (colon < 0 ? '' : line.slice(colon + 1)).replace(/^ /u, '');
      if (fieldName === 'event') type = fieldValue;
      else if (fieldName === 'id') id = fieldValue;
      else if (fieldName === 'data') data.push(fieldValue);
    }
    return { type, id, data: data.join('\n') };
  };

  const readObservation = async (reader, viewerInstance, source, signal, generation) => {
    const decoder = new TextDecoder();
    let buffer = '';
    let cursor = '';
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n');
      if (buffer.length > MAX_STREAM_FRAME_BYTES && !buffer.includes('\n\n')) {
        await reader.cancel();
        throw new EmbedRequestFailure('error');
      }
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (raw.length > MAX_STREAM_FRAME_BYTES) throw new EmbedRequestFailure('error');
        const parsed = parseFrame(raw);
        if (!parsed.data) continue;
        const payload = JSON.parse(parsed.data);
        const type = parsed.type === 'source-gap' ? 'gap'
          : parsed.type === 'source-invalidated' ? 'invalidated'
            : parsed.type === 'runtime-reset' ? 'reset' : parsed.type;
        viewerInstance.observe({
          type,
          deploymentId: payload.deploymentId ?? source.deploymentId,
          graphVersion: payload.graphVersion ?? source.graphVersion,
          incarnationId: payload.incarnationId ?? source.incarnationId,
          processInstanceId: payload.processInstanceId ?? source.processInstanceId ?? null,
          cursor: parsed.id || payload.cursor,
          ...(type === 'execution' ? { event: {
            type: payload.event?.type ?? payload.type,
            executionId: payload.event?.executionId ?? payload.event?.traversalId
              ?? payload.executionId ?? payload.traversalId,
            nodeId: payload.event?.nodeId ?? payload.nodeId ?? null,
            edgeId: payload.event?.edgeId ?? payload.edgeId ?? null,
            activeInstances: Number(payload.event?.activeInstances ?? payload.activeInstances) || 0,
            inFlightArrivals: Number(payload.event?.inFlightArrivals ?? payload.inFlightArrivals) || 0,
            fallback: Boolean(payload.event?.fallback ?? payload.fallback),
            occurredAt: payload.event?.occurredAt ?? payload.occurredAt ?? null,
            publicReason: payload.event?.publicReason ?? payload.publicReason ?? null,
            description: payload.event?.description ?? payload.description ?? '',
            sequence: Number(payload.event?.sequence ?? payload.sequence) || undefined,
          } } : {}),
          ...(type === 'lifecycle' ? { lifecycle: payload.lifecycle } : {}),
          ...(['gap', 'invalidated'].includes(type) ? { reason: payload.reason } : {}),
        }, generation);
        cursor = parsed.id || cursor;
        if (parsed.type === 'source-gap' || parsed.type === 'source-invalidated') {
          await reader.cancel();
          return { cursor, terminal: true };
        }
      }
      if (done) return { cursor, terminal: false };
    }
    return { cursor, terminal: true };
  };

  const readBootstrap = () => {
    const node = document.getElementById('ravenroot-embed-bootstrap');
    if (node === null) throw new Error('bootstrap unavailable');
    const value = JSON.parse(node.textContent);
    const v1Keys = ['acknowledgementId', 'challenge', 'channelId', 'exchangeId', 'expiresAt',
      'grantRevision', 'parentOrigin', 'theme', 'viewerOrigin'];
    const v2Keys = [...v1Keys, 'showStartExecution', 'viewerSourceVersion'];
    const v2 = value?.viewerSourceVersion === '2';
    if (!exactKeys(value, v2 ? v2Keys : v1Keys)
        || !boundedString(value.exchangeId)
        || !boundedString(value.challenge)
        || !boundedString(value.channelId)
        || !boundedString(value.acknowledgementId)
        || !/^[1-9][0-9]{0,18}$/u.test(value.grantRevision)
        || !boundedString(value.expiresAt)
        || !boundedString(value.viewerOrigin, 2048)
        || !boundedString(value.parentOrigin, 2048)
        || (value.theme !== null && !THEMES.includes(value.theme))
        || (v2 && typeof value.showStartExecution !== 'boolean')
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
  let observation = null;
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
    let selectedProcess = null;
    let selectedGeneration = 0;
    let refreshTimer = null;
    const signedBody = async (path, extra = {}) => {
      const issuedAt = new Date().toISOString();
      const jti = correlationId();
      return { nonce: exchanged.challenge, jti, issuedAt,
        signature: await sign(keyPair.privateKey, exchanged.bearer, bootstrap.revision,
          exchanged.challenge, jti, path, issuedAt), ...extra };
    };
    const observeSelected = (processInstanceId, generation) => {
      observation?.abort();
      observation = null;
      if (!processInstanceId) return;
      const controller = new AbortController();
      observation = controller;
      void (async () => {
        let cursor = '';
        for (let attempt = 0; attempt <= MAX_OBSERVATION_RETRIES && !controller.signal.aborted; attempt += 1) {
          try {
            const request = await signedBody(OBSERVATION_PATH, { cursor, processInstanceId });
            const reader = await postStream(OBSERVATION_PATH, request, exchanged.bearer, controller.signal);
            const result = await readObservation(reader, viewer,
              { ...projection.source, processInstanceId }, controller.signal, generation);
            cursor = result.cursor;
            if (result.terminal || controller.signal.aborted || generation !== selectedGeneration) return;
          } catch (failure) {
            if (controller.signal.aborted) return;
            if (failure?.kind === 'expired' || attempt === MAX_OBSERVATION_RETRIES) throw failure;
          }
          await new Promise(resolve => setTimeout(resolve, Math.min(5_000, 500 * (attempt + 1))));
        }
      })().catch(failure => {
        if (!controller.signal.aborted) showFailure(failure?.kind ?? 'error');
      });
    };
    const selectRun = (processInstanceId, generation) => {
      selectedProcess = processInstanceId;
      selectedGeneration = generation;
      observeSelected(processInstanceId, generation);
    };
    const refreshRuns = async () => {
      const envelope = await postJson(RUNS_PATH, await signedBody(RUNS_PATH), exchanged.bearer);
      if (!exactKeys(envelope, ['deploymentId', 'graphVersion', 'incarnationId', 'runs'])
          || !Array.isArray(envelope.runs)
          || envelope.deploymentId !== projection.source?.deploymentId
          || envelope.graphVersion !== projection.source?.graphVersion
          || envelope.incarnationId !== projection.source?.incarnationId) {
        throw new EmbedRequestFailure('error');
      }
      const next = viewer.updateRuns(envelope, selectedProcess);
      if (selectedProcess && !next) {
        viewer.clearRuntime('The selected run is no longer authorized.');
        observation?.abort();
      }
      selectedProcess = next;
    };
    const startExecution = async () => {
      const requestId = correlationId();
      await postJson(START_EXECUTION_PATH,
        await signedBody(START_EXECUTION_PATH, { requestId }), exchanged.bearer);
      await refreshRuns();
    };
    viewer = createEmbedViewer(document.getElementById('ravenroot-embed-viewer'), {
      theme, onRunSelected: selectRun, onStartExecution: () => {
        void startExecution().catch(failure => showFailure(failure?.kind ?? 'error'));
      },
    });
    await viewer.mount(projection);
    if (projection.viewerSourceVersion === '2' && projection.source?.kind === 'deployment') {
      await refreshRuns();
      refreshTimer = setInterval(() => {
        void refreshRuns().catch(failure => {
          clearInterval(refreshTimer);
          observation?.abort();
          viewer.clearRuntime(failure?.kind === 'expired'
            ? 'Live observation authorization ended.' : 'Run list is temporarily unavailable.');
        });
      }, 2_000);
      addEventListener('pagehide', () => {
        clearInterval(refreshTimer);
        observation?.abort();
      }, { once: true });
    } else if (projection.viewerSourceVersion === '1' && projection.source?.kind === 'deployment') {
      observation = new AbortController();
      addEventListener('pagehide', () => observation?.abort(), { once: true });
      void (async () => {
        let cursor = '';
        for (let attempt = 0; attempt <= MAX_OBSERVATION_RETRIES && !observation.signal.aborted; attempt += 1) {
          try {
            const issuedAt = new Date().toISOString();
            const jti = correlationId();
            const request = {
              nonce: exchanged.challenge,
              jti,
              issuedAt,
              signature: await sign(keyPair.privateKey, exchanged.bearer, bootstrap.revision,
                exchanged.challenge, jti, OBSERVATION_PATH, issuedAt),
              cursor,
            };
            const reader = await postStream(OBSERVATION_PATH, request, exchanged.bearer, observation.signal);
            const result = await readObservation(reader, viewer, projection.source, observation.signal);
            cursor = result.cursor;
            if (result.terminal || observation.signal.aborted) return;
          } catch (failure) {
            if (observation.signal.aborted) return;
            if (failure?.kind === 'expired' || attempt === MAX_OBSERVATION_RETRIES) throw failure;
          }
          await new Promise(resolve => setTimeout(resolve, Math.min(5_000, 500 * (attempt + 1))));
        }
        if (!observation.signal.aborted) throw new EmbedRequestFailure('offline');
      })().catch(failure => {
        if (!observation?.signal.aborted) showFailure(failure?.kind ?? 'error');
      });
    }
    protocolReady = true;
    sendToParent('READY', correlationId());
  };

  run().catch(async failure => {
    observation?.abort();
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
