/** Stable identities survive a browser reload without storing operator bindings or credentials. */
export async function releaseOperationIdentity(parts) {
  const canonical = value => Array.isArray(value) ? value.map(canonical)
    : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
  const bytes = new TextEncoder().encode(JSON.stringify(canonical(parts)));
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return `release-${Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')}`;
}

/** Observe only exact reviewed source bytes. Closing the modal cancels reads and timers. */
export async function observeReleaseProposal({ client, candidate, feedback, assertCurrent, decode,
  interval = 5000, attempts = 12, timeout = 60000 }) {
  const signal = AbortSignal.any([feedback.signal, AbortSignal.timeout(timeout)]);
  let exactRelease = false;
  try {
    for (let attempt = 0; attempt < attempts && !signal.aborted; attempt++) {
      assertCurrent();
      let observed;
      try { observed = await client.open(candidate.documentId, 'release', { signal }); }
      catch (error) { if (error.status !== 404) throw error; }
      assertCurrent();
      if (signal.aborted) return;
      exactRelease = observed?.graphId === candidate.graphId && observed.releaseVersion === candidate.releaseVersion
        && decode(observed) === candidate.graphMl;
      if (exactRelease) {
        if (observed.published) { feedback.published(); return; }
        feedback.released();
      }
      await new Promise(resolve => {
        const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); };
        const timer = setTimeout(done, interval);
        signal.addEventListener('abort', done, { once: true });
        if (signal.aborted) done();
      });
    }
  } catch (error) {
    // Unknown/missing/advanced source is not evidence that this candidate was released.
  }
  if (!feedback.signal.aborted && !exactRelease) feedback.pending();
}
