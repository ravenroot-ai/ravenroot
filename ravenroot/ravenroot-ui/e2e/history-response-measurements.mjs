/** Test-only wire measurements for one explicit history interaction phase. */
export function measureHistoryResponses(page) {
  const pages = [], tasks = [], errors = [];
  let stopped = false, drained;
  const onResponse = response => {
    if (stopped) return;
    const url = new URL(response.url());
    if (!url.pathname.endsWith('/history')) return;
    const task = (async () => {
      const body = await response.body(), data = JSON.parse(body);
      pages.push({ view: url.searchParams.get('view'), requestLimit: Number(url.searchParams.get('limit')), bytes: body.length,
        applicationCorrelationId: url.searchParams.get('applicationCorrelationId'), publicReference: url.searchParams.get('publicReference'), cursor: url.searchParams.get('cursor'),
        rows: data.items?.length, status: response.status(), duration: response.request().timing().responseEnd });
    })();
    // Observe failures immediately, then report every original error at the explicit barrier.
    tasks.push(task.catch(error => { errors.push(error); }));
  };
  page.on('response', onResponse);
  return {
    pages,
    stop() {
      if (!drained) {
        stopped = true;
        page.off('response', onResponse);
        drained = Promise.all(tasks).then(() => {
          if (errors.length) throw new AggregateError(errors, 'History response measurement failed');
          return pages;
        });
      }
      return drained;
    },
  };
}
