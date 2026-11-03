import { productError } from './product-error.js';

/** Restoring a browser observation uses GETs only, never registration, restore or START. */
export async function readSavedDeploymentView(client, binding) {
  const status = await client.deployment(binding.deploymentId);
  if (!binding.registrationIdentity || status.registrationIdentity !== binding.registrationIdentity
      || status.graphVersion !== binding.graphVersion)
    throw productError('history.sourceChanged', new Error('Saved deployment identity changed'));
  const envelope = await client.deploymentView(binding.deploymentId);
  if (envelope.source.deploymentId !== binding.deploymentId || envelope.source.graphVersion !== binding.graphVersion
      || envelope.source.incarnationId !== status.runtimeIncarnationId)
    throw productError('history.sourceChanged', new Error('Deployment changed during observation restore'));
  return envelope;
}
