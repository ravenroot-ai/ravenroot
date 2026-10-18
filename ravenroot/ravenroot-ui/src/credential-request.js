import { productError } from './product-error.js';
/** Capture the same generation sent on the wire; legacy providers remain read-only. */
export async function requestCredential(provider) {
  const snapshot = await provider?.getAccessTokenSnapshot?.();
  if (typeof provider?.getAccessTokenSnapshot === 'function' && (!snapshot
      || typeof snapshot !== 'object' || typeof snapshot.accessToken !== 'string')) {
    throw productError("workspace.error.credential.request.access.token.provider.returned.an.invalid.credential.snapshot", new Error('Access token provider returned an invalid credential snapshot'));
  }
  return { snapshot, token: snapshot ? snapshot.accessToken : String(await provider?.getAccessToken?.() || '') };
}

export async function invalidateRejectedCredential(response, provider, credential) {
  if (response.status !== 401 || !credential.snapshot) return;
  try { await provider.clearAccessTokenIfCurrent?.(credential.snapshot); } catch { /* preserve typed HTTP failure */ }
}
