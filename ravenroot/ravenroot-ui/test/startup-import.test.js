import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createStartupImportController,
  STARTUP_IMPORT_PHASE,
  StartupImportError,
  startupImportTarget,
} from '../src/startup-import.js';

import { createUiLocalization, UI_CATALOG_SCHEMA, useUiLocalization } from '../src/ui-text.js';
import { productError } from '../src/product-error.js';
afterEach(() => useUiLocalization(createUiLocalization()));

const PAGE = 'https://ui.example.test/editor?theme=dark';
const clients = new Map();
const binding = generation => ({ connectionGeneration: generation, authorityGeneration: generation,
  persistenceGeneration: generation,
  client: clients.get(generation) || clients.set(generation, { generation }).get(generation) });
const ready = generation => ({ kind: 'ready', binding: binding(generation) });
const tick = () => new Promise(resolve => setTimeout(resolve, 0));

describe('startup import target', () => {
  it('derives the display filename from the path without query or fragment', () => {
    expect(startupImportTarget('/graphs/my%20flow.graphml?token=secret#section', PAGE)).toMatchObject({
      filename: 'my flow.graphml', origin: 'https://ui.example.test', crossOrigin: false,
    });
  });

  it('rejects non-browser network protocols', () => {
    expect(() => startupImportTarget('file:///private/flow.graphml', PAGE))
      .toThrow('must use HTTP or HTTPS');
  });

  it('uses a neutral filename when the path ends in a slash', () => {
    expect(startupImportTarget('https://graphs.example.test/private/', PAGE).filename)
      .toBe('imported.graphml');
  });
});

describe('startup import orchestration', () => {
  it('waits for explicit readiness and starts a same-origin import exactly once', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run });
    expect(controller.snapshot().phase).toBe(STARTUP_IMPORT_PHASE.WAITING_RUNTIME);
    controller.updateRuntime(ready(1));
    controller.updateRuntime(ready(1));
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
    expect(controller.snapshot().phase).toBe(STARTUP_IMPORT_PHASE.COMPLETED);
  });

  it('requires an origin-only confirmation for cross-origin content', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({
      rawUrl: 'https://graphs.example.test/private/flow.graphml?token=not-displayed',
      pageUrl: PAGE,
      run,
    });
    controller.updateRuntime(ready(1));
    await tick();
    expect(run).not.toHaveBeenCalled();
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.WAITING_ORIGIN_CONFIRMATION,
      canConfirmOrigin: true,
    });
    expect(controller.snapshot().message).toContain('https://graphs.example.test');
    expect(controller.snapshot().message).not.toContain('token=');
    controller.confirmOrigin();
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('keeps cross-origin confirmation available while authentication is pending', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({
      rawUrl: 'https://graphs.example.test/flow.graphml', pageUrl: PAGE, run,
    });
    controller.updateRuntime({ kind: 'authentication-required' });
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.WAITING_AUTHENTICATION,
      canConfirmOrigin: true,
    });
    controller.confirmOrigin();
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.WAITING_AUTHENTICATION,
      canConfirmOrigin: false,
    });
    controller.runtimePending();
    controller.updateRuntime(ready(4));
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('keeps cross-origin confirmation available while service confirmation is pending', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({
      rawUrl: 'https://graphs.example.test/flow.graphml', pageUrl: PAGE, run,
    });
    controller.updateRuntime({ kind: 'service-confirmation-required' });
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.WAITING_SERVICE_CONFIRMATION,
      canConfirmOrigin: true,
    });
    controller.confirmOrigin();
    controller.runtimePending();
    controller.updateRuntime(ready(5));
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('resumes once after authentication succeeds', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run });
    controller.updateRuntime({ kind: 'authentication-required' });
    controller.runtimePending();
    controller.updateRuntime(ready(2));
    controller.updateRuntime(ready(2));
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
    expect(controller.snapshot().phase).toBe(STARTUP_IMPORT_PHASE.COMPLETED);
  });

  it('does not turn a generic failure into an automatic retry', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run });
    controller.updateRuntime({ kind: 'error', code: 'configuration-unavailable',
      message: 'Configuration is unavailable.' });
    controller.updateRuntime(ready(1));
    await tick();
    expect(run).not.toHaveBeenCalled();
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.FAILED,
      errorCode: 'configuration-unavailable',
      canRetry: true,
      canCancel: true,
    });
    expect(controller.retry()).toBe(true);
    controller.runtimePending();
    controller.updateRuntime(ready(2));
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('keeps cancellation terminal after a configuration failure', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run });
    controller.updateRuntime({ kind: 'error', code: 'configuration-unavailable',
      message: 'Configuration is unavailable.' });
    expect(controller.cancel()).toBe(true);
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.CANCELLED,
      canRetry: false,
      canCancel: false,
    });
    controller.runtimePending();
    controller.updateRuntime(ready(1));
    await tick();
    expect(run).not.toHaveBeenCalled();
  });

  it('aborts a stale flight and requires deliberate retry against the new binding', async () => {
    let resolveRun;
    const run = vi.fn(({ signal }) => new Promise((resolve, reject) => {
      resolveRun = resolve;
      signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run });
    controller.updateRuntime(ready(1));
    await tick();
    controller.runtimePending();
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.FAILED,
      errorCode: 'stale-authority',
    });
    controller.updateRuntime(ready(2));
    await tick();
    expect(run).toHaveBeenCalledTimes(1);
    resolveRun?.();
    expect(controller.retry()).toBe(true);
    controller.updateRuntime(ready(2));
    await tick();
    expect(run).toHaveBeenCalledTimes(2);
  });

  it('aborts a live flight when readiness becomes non-ready', async () => {
    const run = vi.fn(({ signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run });
    controller.updateRuntime(ready(1));
    await tick();
    controller.updateRuntime({ kind: 'authentication-required' });
    expect(controller.snapshot()).toMatchObject({
      phase: STARTUP_IMPORT_PHASE.FAILED,
      errorCode: 'stale-authority',
    });
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('keeps failed imports recoverable and cancellation terminal', async () => {
    const run = vi.fn()
      .mockRejectedValueOnce(new StartupImportError('not-found', 'The graph file was not found.'))
      .mockResolvedValueOnce(undefined);
    const controller = createStartupImportController({ rawUrl: '/missing.graphml', pageUrl: PAGE, run });
    controller.updateRuntime(ready(1));
    await tick();
    expect(controller.snapshot()).toMatchObject({ phase: STARTUP_IMPORT_PHASE.FAILED,
      errorCode: 'not-found', canRetry: true });
    controller.retry();
    controller.updateRuntime(ready(2));
    await tick();
    expect(run).toHaveBeenCalledTimes(2);
    const cancelled = createStartupImportController({ rawUrl: '/other.graphml', pageUrl: PAGE, run });
    expect(cancelled.cancel()).toBe(true);
    cancelled.updateRuntime(ready(3));
    await tick();
    expect(cancelled.snapshot().phase).toBe(STARTUP_IMPORT_PHASE.CANCELLED);
    expect(run).toHaveBeenCalledTimes(2);
  });
});


describe('startup import live presentation', () => {
  it('repaints pending, downloading, completed and failed state without rerunning or changing errors', async () => {
    const messages = {
      'workspace.import.waiting': 'Attente {filename}',
      'workspace.startup.progress.downloading': 'Importation {filename}',
      'workspace.startup.progress.completed': 'Importé {filename}',
      'workspace.startup.failure.not-found': 'Absent',
    };
    const french = () => useUiLocalization(createUiLocalization({ locale: 'fr', catalogs: { fr: { schema: UI_CATALOG_SCHEMA, locale: 'fr', messages } } }));
    let finish;
    const run = vi.fn(() => new Promise(resolve => { finish = resolve; }));
    const onChange = vi.fn();
    const controller = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run, onChange });
    french(); controller.refreshLocale();
    expect(onChange.mock.lastCall[0].message).toBe('Attente flow.graphml');
    useUiLocalization(createUiLocalization());
    controller.updateRuntime(ready(1));
    expect(controller.snapshot().message).toBe('Importing flow.graphml…');
    french(); controller.refreshLocale();
    expect(onChange.mock.lastCall[0].message).toBe('Importation flow.graphml');
    finish(); await tick();
    expect(controller.snapshot().message).toBe('Importé flow.graphml');
    useUiLocalization(createUiLocalization()); controller.refreshLocale();
    expect(controller.snapshot().message).toBe('Imported flow.graphml.');
    expect(run).toHaveBeenCalledTimes(1);
    const error = productError('workspace.startup.failure.not-found', new StartupImportError('not-found', 'The graph file was not found. Check the source and retry.'));
    const serialized = JSON.stringify(error);
    const failedRun = vi.fn().mockRejectedValue(error);
    const failed = createStartupImportController({ rawUrl: '/flow.graphml', pageUrl: PAGE, run: failedRun });
    failed.updateRuntime(ready(2)); await tick();
    french(); failed.refreshLocale();
    expect(failed.snapshot()).toMatchObject({ message: 'Absent', errorCode: 'not-found', phase: STARTUP_IMPORT_PHASE.FAILED });
    expect(failedRun).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(error)).toBe(serialized);
    expect(error.publicMessage).toBe(error.message);
  });
});
