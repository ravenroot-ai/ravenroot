# Publication boundary policies

Use `boundary-guard` immediately before a node or provider adapter that can create an external effect. The guard evaluates one typed publication candidate against an operator-owned immutable profile and returns only `continue` or `violation`.

The guard is validation, not publication authority. `continue` means only that the exact candidate satisfied the exact pinned policy revision during that evaluation. It does not grant network, credential, repository, messaging, storage, or provider authority. An effecting provider must evaluate the same candidate again immediately before the effect, after its own authorization and destination checks, so a delayed or replaced value cannot reuse an earlier decision.

## Candidate contract

The Java SPI is in `ai.ravenroot.api.publication`. A graph payload may use the equivalent map shape:

```json
{
  "contract": "ravenroot.publication-candidate/1",
  "destination": {
    "type": "provider-defined-type",
    "address": "provider-defined-address"
  },
  "resources": [
    {
      "path": "logical/path.txt",
      "artifactType": "document",
      "mediaType": "text/plain",
      "language": "en",
      "content": {
        "encoding": "utf-8",
        "fragments": ["first fragment", "second fragment"]
      }
    }
  ],
  "provenance": {
    "sourceType": "provider-defined-source",
    "sourceId": "stable-source-id",
    "sourceVersion": "immutable-source-version",
    "contentDigest": "sha256:64-lowercase-hex-digits"
  }
}
```

Binary content uses `encoding: base64`; each fragment is decoded independently. Fragment boundaries are retained because credentials and references can be split across structured values. The provenance digest binds the ordered resource metadata, fragment boundaries, and decoded content. Missing or mismatched provenance is a violation.

The node configuration pins three values:

- `policyId`
- `policyVersion`
- `policyDigest`

The digest is calculated by `PublicationPolicy` from the ID, version, effective candidate-size limit, ordered rules, rule identifiers, and every rule parameter. A resolver returning a different digest is policy drift and fails closed. This prevents a recovered execution from silently applying new rules under an old profile name.

## Operator configuration

Profiles are Java data, not GraphML programs. Construct `PublicationPolicy` from declarative `PublicationRule` values, retain it in an immutable operator registry, and compose the core catalog with `BehaviorRegistry.standard(environment, resolver, auditSink)`. The existing `standard()` overload still advertises `boundary-guard`, but its empty resolver produces `violation` for every candidate.

A production audit sink should retain `PublicationAuditEvent`. The event can represent only runtime IDs, policy ID/version/digest, stable rule ID, fixed reason, candidate byte count, and resource count. It has no field for a destination address, logical path, content, matched value, offset, provenance value, or candidate digest. If the sink fails, the node changes the result to `violation`.

Keep provider-specific profiles in the provider bundle or its composition root. Core defines no repository names, host paths, message destinations, credential formats, or provider policy. A bundle can call the same `PublicationBoundaryGuard` and `PublicationPolicyEvaluator` contracts before its effect; it must not treat an earlier graph outcome as a transferable authorization token.

## Declarative rules

The standard evaluator composes these generic rule families in profile order and stops at the first violation:

- exact destination type and address allowlists;
- logical-path refusal for absolute, home-relative, parent-traversal, and operator-defined private prefixes;
- secret, credential, private-identifier, and private-reference literal signatures;
- exact language tags with optional subtag allowance;
- artifact-type allowlists and an explicit binary-content choice;
- required companion-file suffix pairs;
- complete provenance with an optional source-type allowlist.

Sensitive signatures support substring, token, and token-prefix matching. A profile can enable bounded one-layer percent/base64 inspection, joining across declared fragments, and a conservative Unicode confusable skeleton. These operations are deterministic and bounded by both the policy candidate limit and the rule normalization limit. Binary content is rejected when a text-inspection rule applies, rather than being interpreted heuristically.

## Violation handling and limitations

Missing profiles, malformed candidates, oversized candidates, incomplete provenance, unsupported content, digest drift, evaluator failures, and audit failures are ordinary `violation` results. A violation payload contains fixed metadata and messages only; it never includes the candidate or matched value. `continue` passes the original payload object unchanged.

The standard scanner is deliberately conservative. It recognizes configured literals, common Greek and Cyrillic lookalikes, percent encoding, and one base64 layer; it is not a data-loss-prevention classifier, natural-language detector, recursive decoder, archive inspector, malware scanner, or proof that unlisted private data is absent. False positives should be handled by revising and versioning the operator profile, testing the candidate again, and pinning the new digest. Do not bypass the guard, weaken a rule in graph content, or silently redact the candidate.
