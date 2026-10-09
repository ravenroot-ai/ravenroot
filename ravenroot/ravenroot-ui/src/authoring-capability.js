export const AUTHORING_CAPABILITY_STATE = Object.freeze({
  PENDING: 'pending',
  LOCAL: 'local',
  GIT: 'git',
  UNAVAILABLE: 'unavailable',
});

export function pendingAuthoringCapability() {
  return Object.freeze({ state: AUTHORING_CAPABILITY_STATE.PENDING, error: null });
}

export function unavailableAuthoringCapability(error) {
  return Object.freeze({ state: AUTHORING_CAPABILITY_STATE.UNAVAILABLE,
    error: error instanceof Error ? error : new Error('Graph authoring capabilities are unavailable') });
}

export function authoringCapabilityFromConfiguration(configuration) {
  if (!configuration || typeof configuration !== 'object' || Array.isArray(configuration)) {
    throw new Error('Runtime graph authoring capability is unavailable');
  }
  const advertised = configuration.graphAuthoring;
  // A successfully validated legacy configuration with no authoring field proves the historical
  // local-only contract. A failed or pending request never reaches this branch.
  if (advertised == null || advertised.mode === 'local') {
    return Object.freeze({ state: AUTHORING_CAPABILITY_STATE.LOCAL, error: null });
  }
  if (advertised.mode === 'git' && advertised.provider === 'github') {
    return Object.freeze({ state: AUTHORING_CAPABILITY_STATE.GIT, error: null });
  }
  throw new Error('Runtime graph authoring capability is malformed');
}

export async function loadAuthoringCapability(client) {
  try {
    const configuration = await client.configuration();
    return Object.freeze({ client, configuration, error: null,
      capability: authoringCapabilityFromConfiguration(configuration) });
  } catch (error) {
    return Object.freeze({ client, configuration: null, error,
      capability: unavailableAuthoringCapability(error) });
  }
}

export function primaryPersistenceTarget(capability) {
  return capability?.state === AUTHORING_CAPABILITY_STATE.LOCAL ? 'local'
    : capability?.state === AUTHORING_CAPABILITY_STATE.GIT ? 'git' : 'blocked';
}

export function runPrimaryPersistence(capability, actions) {
  const target = primaryPersistenceTarget(capability);
  if (target === 'local') return actions.local();
  if (target === 'git') return actions.git();
  return actions.blocked();
}
