# Authentication and browser access

Diagnose authentication independently from browser-origin enforcement, then prove both allowed and denied paths after the correction.

## A valid-looking token is rejected

**Diagnosis:** The token fails signature, issuer, audience, expiry, local-token equality, or resource ownership checks.

**Action:** Identify the authentication mode and use server time plus OIDC metadata to isolate the failed check. Correct issuer/audience configuration or rotate through the secret channel; never log the token.

**Verify:** Prove one permitted request and one denied request with sanitized identity diagnostics.

## The workspace loads but API calls fail in the browser

**Diagnosis:** The request origin or Host header is not an exact allowlist member, or UI and API were published across different origins.

**Action:** Publish UI and API on the reviewed origin and add only exact origin and host values. Do not introduce a wildcard as a workaround.

**Verify:** Reload from the intended URL and verify both `/v1/status` and a denied-origin request.

## An SSE stream closes after connecting

**Diagnosis:** The periodic authentication revalidation detected expiry, revocation, or loss of resource authority.

**Action:** Refresh identity through the normal client flow and reconnect using the last persisted cursor. Reconcile a retention gap if declared.

**Verify:** Keep the stream open beyond the 30-second default revalidation interval and verify ordered delivery.

## Related contracts

- [Primary contract](../operator-guide/identity-browser.md)
- [Control procedure](../security/trust-identity.md)
