// @vitest-environment node
import { EventEmitter } from 'node:events';
import { setImmediate } from 'node:timers/promises';
import { expect, test } from 'vitest';
import { measureHistoryResponses } from '../e2e/history-response-measurements.mjs';

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const response = body => ({ url: () => 'http://localhost/history?view=visits&limit=1', body,
  status: () => 200, request: () => ({ timing: () => ({ responseEnd: 12 }) }) });

test('stop detaches immediately but drains every held body before returning measurements', async () => {
  const page = new EventEmitter(), body = deferred(), collector = measureHistoryResponses(page);
  page.emit('response', response(() => body.promise));
  const stopped = collector.stop(); let finished = false;
  stopped.then(() => { finished = true; }, () => { finished = true; });
  expect(collector.stop()).toBe(stopped);
  await setImmediate(); expect(finished).toBe(false);
  let lateReads = 0; page.emit('response', response(() => { lateReads++; throw new Error('outside measurement phase'); }));
  const payload = Buffer.from('{"items":[{"id":"original"}]}'); body.resolve(payload);
  expect(await stopped).toEqual([{ view: 'visits', requestLimit: 1, bytes: payload.length, rows: 1,
    applicationCorrelationId: null, publicReference: null, cursor: null, status: 200, duration: 12 }]);
  expect(lateReads).toBe(0); expect(page.listenerCount('response')).toBe(0);
});

test('body errors are handled immediately and all original failures reject the stop barrier', async () => {
  const page = new EventEmitter(), first = deferred(), second = deferred(), collector = measureHistoryResponses(page);
  const unhandled = [], onUnhandled = error => unhandled.push(error);
  process.on('unhandledRejection', onUnhandled);
  try {
    page.emit('response', response(() => first.promise)); page.emit('response', response(() => second.promise));
    const original = new Error('response body lost'), other = new Error('second body lost');
    first.reject(original); second.reject(other);
    await setImmediate(); expect(unhandled).toEqual([]);
    const stopped = collector.stop();
    await expect(stopped).rejects.toMatchObject({ name: 'AggregateError', errors: [original, other] });
    expect(page.listenerCount('response')).toBe(0); expect(collector.pages).toEqual([]);
  } finally { process.off('unhandledRejection', onUnhandled); }
});
