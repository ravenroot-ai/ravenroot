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
  const request = async (path = '', body, personal = false) => {
    // Personal OAuth is same-origin only. Existing cross-origin system connections remain available.
    if (origin.origin !== location.origin) throw new Error(uiText('personal.sameOrigin'));
    const token = String(await tokenProvider.getAccessToken?.() || '');
    const response = await fetch(`${origin.href.replace(/\/$/, '')}${path.startsWith('/v1/') ? path : `/v1/personal${path}`}`, {
      method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { Accept: 'application/json', ...(personal ? { 'X-Ravenroot-Execution-Context': 'personal' } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(uiText('personal.state', { state: data.state || data.code || response.status }));
    return data;
  };
  async function catalog() {
    const requestGeneration = generation;
    models.replaceChildren(); currentModel = ''; onCatalog([]);
    if (!state?.identity || disposed) return;
    try {
      const data = await request('/models');
      if (disposed || generation !== requestGeneration) return;
      onCatalog(data.models || []);
      for (const model of data.models || []) {
        const option = document.createElement('option'); option.value = model.slug; option.textContent = model.display_name; models.append(option);
      }
      currentModel = models.value;
    } catch (error) { if (generation !== requestGeneration || disposed) return; health.textContent = error.message; }
    onMode(currentMode, currentModel, Boolean(state?.assistantAuthorized));
  }
  async function refresh() {
    try {
      const next = await request();
      if (disposed) return;
      state = next;
      const identity = state.identity ? `${state.identity}:${(state.connections || []).find(item => item.selected)?.id || ''}` : null;
      if (priorIdentity !== identity) {
        generation += 1;
        currentMode = 'system'; mode.value = currentMode; resultId.value = ''; resultText.textContent = ''; registration.value = '';
        onIdentity(state.identity || null); priorIdentity = identity;
        onMode(currentMode, '', false);
      }
      health.textContent = uiText('personal.state', { state: state.state });
      connection.replaceChildren();
      for (const item of state.connections || []) {
        const option = document.createElement('option'); option.value = item.id;
        option.textContent = uiText('personal.connectionHealth', { label: item.label, health: item.health });
        option.selected = item.selected; connection.append(option);
      }
      await catalog();
    } catch (error) { health.textContent = error.message; }
  }
  function button(key, action) {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'btn'; button.textContent = uiText(key);
    button.addEventListener('click', async () => { button.disabled = true; try { await action(); } catch (error) { health.textContent = error.message; } finally { password.value = ''; button.disabled = false; } });
    controls.append(button); return button;
  }
  button('personal.signIn', async () => { const data = await request('/sign-in', {}); location.assign(data.authorizationUrl); });
  button('personal.addAccount', async () => { const data = await request('/sign-in', { newConnection: true }); location.assign(data.authorizationUrl); });
  const registration = document.createElement('input'); registration.autocomplete = 'off';
  registration.setAttribute('aria-label', uiText('personal.registrationCode')); controls.append(registration);
  button('personal.createCode', async () => { const data = await request('/registration', {}); registration.value = data.registration; });
  button('personal.useCode', async () => { const code = registration.value; registration.value = ''; const data = await request('/sign-in', { registration: code }); location.assign(data.authorizationUrl); });
  controls.append(connection);
  connection.addEventListener('change', async () => { await request('/select', { connection: connection.value }); onIdentity(null); await refresh(); });
  button('personal.reconnect', async () => { const data = await request('/sign-in', { connection: connection.value, enablePlanUse: true }); location.assign(data.authorizationUrl); });
  button('personal.disconnect', async () => { const data = await request('/disconnect', { connection: connection.value }); onIdentity(null); await refresh(); health.textContent = uiText('personal.state', { state: data.state }); });
  button('personal.signOut', async () => { await request('/sign-out', {}); state = null; onIdentity(null); currentMode = 'system'; mode.value = 'system'; onMode('system', '', false); onHistory('system'); await refresh(); });
  controls.append(mode, models);
  mode.addEventListener('change', () => { currentMode = mode.value; onIdentity(null); onMode(currentMode, currentModel, Boolean(state?.assistantAuthorized)); });
  models.addEventListener('change', () => { currentModel = models.value; onMode(currentMode, currentModel, Boolean(state?.assistantAuthorized)); });
  button('personal.authorize', async () => { await request('/authorize', {}); await refresh(); });
  controls.append(password, purpose);
  button('personal.protect', async () => { const passphrase = password.value; password.value = ''; await request('/protect', { passphrase }); await refresh(); });
  button('personal.unlock', async () => { const passphrase = password.value; password.value = ''; await request('/unlock', { passphrase, purpose: purpose.value }); await refresh(); });
  button('personal.lock', async () => { await request('/lock', {}); await refresh(); });
  controls.append(history); history.addEventListener('change', () => onHistory(history.value));
  const deployed = document.createElement('select'); deployed.setAttribute('aria-label', uiText('personal.deployment'));
  const payload = document.createElement('input'); payload.setAttribute('aria-label', uiText('personal.input')); controls.append(deployed, payload);
  button('personal.loadDeployments', async () => { const data = await request('/v1/deployments'); deployed.replaceChildren(); for (const item of data.deployments || []) { const option = document.createElement('option'); option.value = item.deploymentId; option.textContent = item.deploymentId; deployed.append(option); } });
  button('personal.runDeployment', async () => {
    const view = await request(`/v1/deployments/${encodeURIComponent(deployed.value)}/view`);
    const source = view.source || view.view?.source;
    if (!source) throw new Error(uiText('personal.deploymentUnavailable'));
    const result = await request('/executions', { deploymentId: source.deploymentId, incarnationId: source.incarnationId, graphVersion: source.graphVersion,
      payload: { contract: 'ravenroot.payload/1', schema: 'ravenroot.text', schemaVersion: '1', kind: 'SCALAR', value: payload.value } });
    history.value = 'personal'; onHistory('personal'); onRun(result); resultId.value = result.executionId;
    health.textContent = uiText('personal.started', { id: result.executionId });
  });
  const resultId = document.createElement('input'); resultId.setAttribute('aria-label', uiText('personal.executionId'));
  const resultText = document.createElement('pre'); resultText.setAttribute('aria-label', uiText('personal.executionResult'));
  controls.append(resultId);
  button('personal.readResult', async () => {
    const data = await request(`/v1/executions/${encodeURIComponent(resultId.value)}`, undefined, true);
    resultText.textContent = JSON.stringify(data, null, 2);
  });
  controls.append(resultText);
  host.prepend(root); void refresh();
  return { refresh, showExecution(id) { resultId.value = id; resultText.textContent = ''; },
    destroy() { disposed = true; generation += 1; onCatalog([]); password.value = ''; registration.value = ''; resultId.value = ''; resultText.textContent = ''; root.remove(); } };
}
