# Embedded-viewer operations

Provision and withdraw read-only graph delivery through explicit human attestations and auditable session controls.

## Operator procedure

1. Identify the exact source, host origin, accountable owner, and takedown route. Choose an immutable
   snapshot or one process-local live deployment; do not provision both inputs.
2. For a snapshot, attest deployment, provenance, classification, retention, DSR suppression,
   takedown, and EEA residence gates. For a live source, authorize the named deployment and review its
   current graph version before issuing a session.
3. Register the source, use `embed-registration show` to verify `viewer-source-version=1` and the
   expected `source`, and test one launch-to-exchange flow from the allowed host.
4. For a live source, verify observation while `READY`, across a stop/start lifecycle, and across a
   transient reconnect. Confirm that graph replacement, undeploy, replay gap, and revocation end the
   existing attachment instead of following another source.
5. Review session, projection, observation, and revocation audit evidence; revoke the registration
   immediately when any approved fact no longer holds.

## Authority

Ravenroot records and enforces operator statements; it does not derive them from GraphML or consult another policy service. The host cannot self-register or alter an acknowledgement.

## Verification

After revocation, confirm that new launches fail, outstanding sessions cannot retrieve projections or
continue observation, and the audit record identifies the operator action. An observation cursor is
process-local and cannot survive a replay gap or source replacement; the recovery step is a fresh
projection/session after policy review, never cursor fabrication or GraphML fallback.

- [Contract](../reference/embed-extension-contracts.md)
- [Runbook](../troubleshooting/embed-backup.md)
