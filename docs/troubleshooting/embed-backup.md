# Embed access, backup, and recovery

Keep revoked access and suspect restored state isolated while validating attestations, one-time sessions, backup identity, and reconciliation.

## Embed registration is refused

**Diagnosis:** One or more of the seven operator attestations or deployment bindings is absent.

**Action:** Identify the accountable owner and record deployment, provenance, classification, retention, DSR suppression, takedown, and EEA residence. Do not invent a positive answer from graph content.

**Verify:** Show the stored acknowledgements and complete a launch from the exact registered host.

## Launch exchange or projection retrieval fails

**Diagnosis:** The launch was reused, expired, issued for another origin or deployment, or the registration/session was revoked.

**Action:** Discard the launch and local projection. Confirm registration and policy still hold, then request a new one-time launch through the host. Never reuse an operator credential in the browser.

**Verify:** Retrieve the read-only projection once, then prove that an expired or revoked session is denied.

## A restored deployment contains inconsistent state

**Diagnosis:** The backup was taken without a storage boundary, belongs to an incompatible release, or restoration was performed over a live target.

**Action:** Keep the target isolated, return to the last verified backup, and repeat drain, version check, restore, startup recovery, and reconciliation. Preserve the failed copy for incident analysis.

**Verify:** Verify readiness, retained execution results, event cursor continuity or an explicit gap, embed revocation state, and a bounded Test before promotion.

## Related contracts

- [Primary contract](../operator-guide/embed-operations.md)
- [Control procedure](../operator-guide/persistence-lifecycle.md)
- [Recovery bundle reference](../reference/backup-recovery.md)
