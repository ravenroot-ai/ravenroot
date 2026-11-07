import { uiText } from './ui-text.js';

// Personal credentials stay in HttpOnly cookies/server storage. This module holds presentation only.
export function mountPersonalConnections({ host, baseUrl, tokenProvider, onMode, onIdentity, onHistory, onRun, onCatalog = () => {} }) {
  const origin = new URL(baseUrl || location.origin, location.origin);
  const root = document.createElement('section');
  root.className = 'personal-connections';
  const label = document.createElement('h3'); label.textContent = uiText('personal.title'); root.append(label);
  const health = document.createElement('p'); health.setAttribute('role', 'status'); root.append(health);
  const controls = document.createElement('div'); root.append(controls);
  let state = null;
  let priorIdentity = null;
  let currentMode = 'system';
  let currentModel = '';
  let generation = 0;
  let disposed = false;
  const retired = Symbol('retired personal identity');
  const isCurrent = expected => !disposed && generation === expected;
  const connection = document.createElement('select'); connection.setAttribute('aria-label', uiText('personal.account'));
  const mode = document.createElement('select'); mode.setAttribute('aria-label', uiText('personal.assistantMode'));
  for (const [value, key] of [['system', 'personal.system'], ['personal', 'personal.personal']]) {
    const option = document.createElement('option'); option.value = value; option.textContent = uiText(key); mode.append(option);
  }
  const models = document.createElement('select'); models.setAttribute('aria-label', uiText('personal.model'));
  const history = document.createElement('select'); history.setAttribute('aria-label', uiText('personal.history'));
  for (const [value, key] of [['system', 'personal.systemHistory'], ['personal', 'personal.privateHistory']]) {
    const option = document.createElement('option'); option.value = value; option.textContent = uiText(key); history.append(option);
  }
  const password = document.createElement('input'); password.type = 'password'; password.autocomplete = 'off';
  password.placeholder = uiText('personal.passphrase'); password.setAttribute('aria-label', uiText('personal.passphrase'));
  const purpose = document.createElement('select'); purpose.setAttribute('aria-label', uiText('personal.unlockPurpose'));
  for (const [value, key] of [['execution', 'personal.nextExecution'], ['assistant', 'personal.assistantSession']]) {
    const option = document.createElement('option'); option.value = value; option.textContent = uiText(key); purpose.append(option);
  }
  const request = async (path = '', body, personal = false, expected = generation) => {
    // Personal OAuth is same-origin only. Existing cross-origin system connections remain available.
    if (origin.origin !== location.origin) throw new Error(uiText('personal.sameOrigin'));
    const token = String(await tokenProvider.getAccessToken?.() || '');
    if (!isCurrent(expected)) throw retired;
    const response = await fetch(`${origin.href.replace(/\/$/, '')}${path.startsWith('/v1/') ? path : `/v1/personal${path}`}`, {
      method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { Accept: 'application/json', ...(personal ? { 'X-Ravenroot-Execution-Context': 'personal' } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(uiText('personal.state', { state: data.state || data.code || response.status }));
    return data;
  };
  // Check in the continuation that applies the result, including after the body has arrived.
  async function perform(path, body, apply, personal = false) {
    const expected = generation;
    try {
      const data = await request(path, body, personal, expected);
      if (isCurrent(expected)) return await apply(data);
    } catch (error) {
      if (isCurrent(expected) && error !== retired) health.textContent = error.message;
    }
  }
  function retireIdentity(identity = null) {
    generation += 1; state = null; priorIdentity = null;
    currentMode = 'system'; mode.value = currentMode; currentModel = ''; history.value = 'system';
    resultId.value = ''; resultText.textContent = ''; registration.value = ''; password.value = '';
    payload.value = ''; connection.replaceChildren(); models.replaceChildren(); deployed.replaceChildren();
    onIdentity(identity); onMode('system', '', false); onHistory('system'); onCatalog([]);
  }
  async function changeIdentity(path, body) {
    const expected = generation;
    try {
      const data = await request(path, body, false, expected);
      if (disposed) return;
      // A completed mutation changes server authority even if another mutation also completed.
      // Retire immediately; the follow-up status request must not be the invalidation boundary.
      retireIdentity();
      const refreshedGeneration = await refresh();
      if (path === '/disconnect' && isCurrent(refreshedGeneration))
        health.textContent = uiText('personal.state', { state: data.state });
    } catch (error) {
      if (isCurrent(expected) && error !== retired) health.textContent = error.message;
    }
  }
  async function catalog() {
    const expected = generation;
    models.replaceChildren(); currentModel = ''; onCatalog([]);
    if (!state?.identity || disposed) return;
    await perform('/models', undefined, data => {
      onCatalog(data.models || []);
      for (const model of data.models || []) {
        const option = document.createElement('option'); option.value = model.slug; option.textContent = model.display_name; models.append(option);
      }
      currentModel = models.value;
    });
    if (isCurrent(expected)) onMode(currentMode, currentModel, Boolean(state?.assistantAuthorized));
  }
  async function refresh() {
    return perform('', undefined, async next => {
      const identity = next.identity ? `${next.identity}:${(next.connections || []).find(item => item.selected)?.id || ''}` : null;
      if (priorIdentity !== identity) retireIdentity(next.identity || null);
      state = next;
      priorIdentity = identity;
      health.textContent = uiText('personal.state', { state: state.state });
      connection.replaceChildren();
      for (const item of state.connections || []) {
        const option = document.createElement('option'); option.value = item.id;
        option.textContent = uiText('personal.connectionHealth', { label: item.label, health: item.health });
        option.selected = item.selected; connection.append(option);
      }
      const refreshedGeneration = generation;
      await catalog();
      return isCurrent(refreshedGeneration) ? refreshedGeneration : null;
    });
  }
  function button(key, action) {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'btn'; button.textContent = uiText(key);
    button.addEventListener('click', async () => {
      const expected = generation;
      button.disabled = true;
      try { await action(); }
      catch (error) { if (isCurrent(expected) && error !== retired) health.textContent = error.message; }
      finally { if (isCurrent(expected)) password.value = ''; button.disabled = false; }
    });
    controls.append(button); return button;
  }
  const redirect = data => location.assign(data.authorizationUrl);
  button('personal.signIn', () => perform('/sign-in', {}, redirect));
  button('personal.addAccount', () => perform('/sign-in', { newConnection: true }, redirect));
  const registration = document.createElement('input'); registration.autocomplete = 'off';
  registration.setAttribute('aria-label', uiText('personal.registrationCode')); controls.append(registration);
  button('personal.createCode', () => perform('/registration', {}, data => { registration.value = data.registration; }));
  button('personal.useCode', () => { const code = registration.value; registration.value = ''; return perform('/sign-in', { registration: code }, redirect); });
  controls.append(connection);
  connection.addEventListener('change', () => { void changeIdentity('/select', { connection: connection.value }); });
  button('personal.reconnect', () => perform('/sign-in', { connection: connection.value, enablePlanUse: true }, redirect));
  button('personal.disconnect', () => changeIdentity('/disconnect', { connection: connection.value }));
  button('personal.signOut', () => changeIdentity('/sign-out', {}));
  controls.append(mode, models);
  mode.addEventListener('change', () => { currentMode = mode.value; onIdentity(null); onMode(currentMode, currentModel, Boolean(state?.assistantAuthorized)); });
  models.addEventListener('change', () => { currentModel = models.value; onMode(currentMode, currentModel, Boolean(state?.assistantAuthorized)); });
  button('personal.authorize', () => perform('/authorize', {}, refresh));
  controls.append(password, purpose);
  button('personal.protect', () => { const passphrase = password.value; password.value = ''; return perform('/protect', { passphrase }, refresh); });
  button('personal.unlock', () => { const passphrase = password.value; password.value = ''; return perform('/unlock', { passphrase, purpose: purpose.value }, refresh); });
  button('personal.lock', () => perform('/lock', {}, refresh));
  controls.append(history); history.addEventListener('change', () => onHistory(history.value));
  const deployed = document.createElement('select'); deployed.setAttribute('aria-label', uiText('personal.deployment'));
  const payload = document.createElement('input'); payload.setAttribute('aria-label', uiText('personal.input')); controls.append(deployed, payload);
  button('personal.loadDeployments', () => perform('/v1/deployments', undefined, data => { deployed.replaceChildren(); for (const item of data.deployments || []) { const option = document.createElement('option'); option.value = item.deploymentId; option.textContent = item.deploymentId; deployed.append(option); } }));
  button('personal.runDeployment', () => perform(`/v1/deployments/${encodeURIComponent(deployed.value)}/view`, undefined, view => {
    const source = view.source || view.view?.source;
    if (!source) throw new Error(uiText('personal.deploymentUnavailable'));
    return perform('/executions', { deploymentId: source.deploymentId, incarnationId: source.incarnationId, graphVersion: source.graphVersion,
      payload: { contract: 'ravenroot.payload/1', schema: 'ravenroot.text', schemaVersion: '1', kind: 'SCALAR', value: payload.value } }, result => {
      history.value = 'personal'; onHistory('personal'); onRun(result); resultId.value = result.executionId;
      health.textContent = uiText('personal.started', { id: result.executionId });
    });
  }));
  const resultId = document.createElement('input'); resultId.setAttribute('aria-label', uiText('personal.executionId'));
  const resultText = document.createElement('pre'); resultText.setAttribute('aria-label', uiText('personal.executionResult'));
  controls.append(resultId);
  button('personal.readResult', () => perform(`/v1/executions/${encodeURIComponent(resultId.value)}`, undefined, data => {
    resultText.textContent = JSON.stringify(data, null, 2);
  }, true));
  controls.append(resultText);
  host.prepend(root); void refresh();
  return { refresh, showExecution(id) { if (!disposed) { resultId.value = id; resultText.textContent = ''; } },
    destroy() { if (!disposed) { retireIdentity(); disposed = true; root.remove(); } } };
}
