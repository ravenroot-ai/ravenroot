import { uiText, uiErrorReason } from './ui-text.js';
import { productError } from './product-error.js';

export const STARTUP_IMPORT_PHASE = Object.freeze({
  WAITING_RUNTIME: 'waiting-runtime',
  WAITING_AUTHENTICATION: 'waiting-authentication',
  WAITING_SERVICE_CONFIRMATION: 'waiting-service-confirmation',
  WAITING_ORIGIN_CONFIRMATION: 'waiting-origin-confirmation',
  DOWNLOADING: 'downloading',
  FAILED: 'failed',
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
});

export class StartupImportError extends Error {
  constructor(code, publicMessage) {
    super(publicMessage);
    this.name = 'StartupImportError';
    this.code = code;
    this.publicMessage = publicMessage;
  }
}

function decodedFilename(pathname) {
  // i18n-audit-exempt: authored-content - Import filename is retained as the document's stable identity.
  const segment = pathname.split('/').at(-1) || 'imported.graphml';
  try {
    // i18n-audit-exempt: authored-content - Import filename is retained as the document's stable identity.
    return decodeURIComponent(segment) || 'imported.graphml';
  } catch {
    // i18n-audit-exempt: authored-content - Import filename is retained as the document's stable identity.
    return segment || 'imported.graphml';
  }
}

export function startupImportTarget(rawUrl, pageUrl) {
  const url = new URL(rawUrl, pageUrl);
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw productError('startupImport.url.unsupported', new StartupImportError('unsupported-url',
      'The graph URL must use HTTP or HTTPS.'));
  }
  const page = new URL(pageUrl);
  return Object.freeze({
    url: url.href,
    filename: decodedFilename(url.pathname),
    origin: url.origin,
    crossOrigin: url.origin !== page.origin,
  });
}

function publicFailure(error) {
  if (error instanceof StartupImportError) {
    return { code: error.code, get message() { return error.uiMessageKey ? uiErrorReason(error) : error.publicMessage; } };
  }
  if (error?.name === 'AbortError') {
    return { code: 'aborted', get message() { return uiText('startupImport.cancelled'); } };
  }
  return { code: 'import-failed', get message() { return uiText('startupImport.failed'); } };
}

function sameBinding(left, right) {
  return Boolean(left && right && left.connectionGeneration === right.connectionGeneration
    && left.authorityGeneration === right.authorityGeneration
    && left.persistenceGeneration === right.persistenceGeneration
    && left.client === right.client);
}

/**
 * One-shot coordinator for the optional `?file=` startup import.
 *
 * The controller owns no workspace or DOM state. Runtime readiness and the final commit are injected,
 * which keeps retries, cancellation and stale-authority rejection deterministic in unit tests.
 */
export function createStartupImportController({ rawUrl, pageUrl, run, onChange = () => {} }) {
  const target = startupImportTarget(rawUrl, pageUrl);
  let phase = target.crossOrigin
    ? STARTUP_IMPORT_PHASE.WAITING_ORIGIN_CONFIRMATION
    : STARTUP_IMPORT_PHASE.WAITING_RUNTIME;
  let message = () => target.crossOrigin
    ? uiText("workspace.import.confirm", { origin: target.origin, filename: target.filename })
    : uiText("workspace.import.waiting", { filename: target.filename });
  let errorCode = null;
  let originConfirmed = !target.crossOrigin;
  let runtimeOutcome = null;
  let generation = 0;
  let flight = null;

  const snapshot = () => Object.freeze({ phase, message: typeof message === 'function' ? message() : message, errorCode, target,
    canConfirmOrigin: target.crossOrigin && !originConfirmed
      && [STARTUP_IMPORT_PHASE.WAITING_ORIGIN_CONFIRMATION,
        STARTUP_IMPORT_PHASE.WAITING_RUNTIME,
        STARTUP_IMPORT_PHASE.WAITING_AUTHENTICATION,
        STARTUP_IMPORT_PHASE.WAITING_SERVICE_CONFIRMATION].includes(phase),
    canRetry: phase === STARTUP_IMPORT_PHASE.FAILED,
    canCancel: ![STARTUP_IMPORT_PHASE.COMPLETED, STARTUP_IMPORT_PHASE.CANCELLED].includes(phase),
  });

  function publish() {
    onChange(snapshot());
  }

  function set(nextPhase, nextMessage, nextErrorCode = null) {
    phase = nextPhase;
    message = nextMessage;
    errorCode = nextErrorCode;
    publish();
  }

  function abortFlight() {
    generation += 1;
    flight?.controller.abort();
    flight = null;
  }

  async function startIfReady() {
    if (!originConfirmed || runtimeOutcome?.kind !== 'ready'
        || ![STARTUP_IMPORT_PHASE.WAITING_RUNTIME,
          STARTUP_IMPORT_PHASE.WAITING_AUTHENTICATION,
          STARTUP_IMPORT_PHASE.WAITING_SERVICE_CONFIRMATION,
          STARTUP_IMPORT_PHASE.WAITING_ORIGIN_CONFIRMATION].includes(phase)) return false;
    const binding = runtimeOutcome.binding;
    const currentGeneration = ++generation;
    const controller = new AbortController();
    flight = { controller, binding, generation: currentGeneration };
    set(STARTUP_IMPORT_PHASE.DOWNLOADING, () => (uiText("workspace.startup.progress.downloading", { filename: target.filename })));
    try {
      await run({ target, binding, signal: controller.signal, generation: currentGeneration });
      if (flight?.generation !== currentGeneration || generation !== currentGeneration) return false;
      flight = null;
      set(STARTUP_IMPORT_PHASE.COMPLETED, () => (uiText("workspace.startup.progress.completed", { filename: target.filename })));
      return true;
    } catch (error) {
      if (flight?.generation !== currentGeneration || generation !== currentGeneration) return false;
      flight = null;
      const failure = publicFailure(error);
      set(STARTUP_IMPORT_PHASE.FAILED, () => (failure.message), failure.code);
      return false;
    }
  }

  function runtimePending() {
    if ([STARTUP_IMPORT_PHASE.COMPLETED, STARTUP_IMPORT_PHASE.CANCELLED].includes(phase)) return;
    runtimeOutcome = { kind: 'pending' };
    if (phase === STARTUP_IMPORT_PHASE.DOWNLOADING) {
      abortFlight();
      set(STARTUP_IMPORT_PHASE.FAILED,
        () => (uiText("workspace.startup.import.the.service.or.workspace.changed.during.the.import.retry")),
        'stale-authority');
    } else if (phase !== STARTUP_IMPORT_PHASE.FAILED && originConfirmed) {
      set(STARTUP_IMPORT_PHASE.WAITING_RUNTIME,
        () => (uiText("workspace.import.waiting", { filename: target.filename })));
    }
  }

  function updateRuntime(outcome) {
    if ([STARTUP_IMPORT_PHASE.COMPLETED, STARTUP_IMPORT_PHASE.CANCELLED].includes(phase)) return;
    if (phase === STARTUP_IMPORT_PHASE.DOWNLOADING
        && (outcome.kind !== 'ready' || !sameBinding(flight?.binding, outcome.binding))) {
      runtimeOutcome = outcome;
      abortFlight();
      set(STARTUP_IMPORT_PHASE.FAILED,
        () => (uiText("workspace.startup.import.the.service.or.workspace.changed.during.the.import.retry")),
        'stale-authority');
      return;
    }
    runtimeOutcome = outcome;
    if (phase === STARTUP_IMPORT_PHASE.FAILED) return;
    if (outcome.kind === 'ready') {
      if (!originConfirmed) {
        set(STARTUP_IMPORT_PHASE.WAITING_ORIGIN_CONFIRMATION,
          () => (uiText("workspace.import.confirm", { origin: target.origin, filename: target.filename })));
      } else {
        void startIfReady();
      }
    } else if (outcome.kind === 'authentication-required') {
      set(STARTUP_IMPORT_PHASE.WAITING_AUTHENTICATION,
        () => (uiText("workspace.startup.import.authenticate.with.the.ravenroot.service.to.import", { value: originConfirmed ? ''
          : uiText("workspace.import.and.confirm", { origin: target.origin }), filename: target.filename })));
    } else if (outcome.kind === 'service-confirmation-required') {
      set(STARTUP_IMPORT_PHASE.WAITING_SERVICE_CONFIRMATION,
        () => (uiText("workspace.startup.import.confirm.the.ravenroot.service.connection.to.import", { value: originConfirmed ? ''
          : uiText("workspace.import.and.origin", { origin: target.origin }), filename: target.filename })));
    } else if (outcome.kind === 'error') {
      set(STARTUP_IMPORT_PHASE.FAILED,
        () => (outcome.message || uiText("workspace.startup.import.the.service.is.not.ready.for.graph.imports.retry")),
        outcome.code || 'runtime-unavailable');
    }
  }

  function confirmOrigin() {
    if (!target.crossOrigin || originConfirmed || !snapshot().canConfirmOrigin) return false;
    originConfirmed = true;
    if (runtimeOutcome?.kind === 'authentication-required') {
      set(STARTUP_IMPORT_PHASE.WAITING_AUTHENTICATION,
        () => (uiText("workspace.startup.import.authenticate.with.the.ravenroot.service.to.import.alternative", { filename: target.filename })));
    } else if (runtimeOutcome?.kind === 'service-confirmation-required') {
      set(STARTUP_IMPORT_PHASE.WAITING_SERVICE_CONFIRMATION,
        () => (uiText("workspace.startup.import.confirm.the.ravenroot.service.connection.to.import.alternative", { filename: target.filename })));
    } else {
      set(STARTUP_IMPORT_PHASE.WAITING_RUNTIME,
        () => (uiText("workspace.import.waiting", { filename: target.filename })));
      void startIfReady();
    }
    return true;
  }

  function retry() {
    if (phase !== STARTUP_IMPORT_PHASE.FAILED) return false;
    abortFlight();
    runtimeOutcome = null;
    set(originConfirmed ? STARTUP_IMPORT_PHASE.WAITING_RUNTIME
      : STARTUP_IMPORT_PHASE.WAITING_ORIGIN_CONFIRMATION,
    () => (originConfirmed ? uiText("workspace.import.waiting", { filename: target.filename })
      : uiText("workspace.import.confirm", { origin: target.origin, filename: target.filename })));
    return true;
  }

  function cancel() {
    if ([STARTUP_IMPORT_PHASE.COMPLETED, STARTUP_IMPORT_PHASE.CANCELLED].includes(phase)) return false;
    abortFlight();
    set(STARTUP_IMPORT_PHASE.CANCELLED, () => (uiText("workspace.startup.import.cancelled.import.of", { filename: target.filename })));
    return true;
  }

  publish();
  return Object.freeze({ snapshot, refreshLocale: publish, runtimePending, updateRuntime, confirmOrigin, retry, cancel });
}
