# API, documentation, and release discipline

Treat OpenAPI, CLI help, GraphML, SPI signatures, persisted schema, events, and this manual as one versioned product surface.

## Required practice

- Classify a change by the compatibility surface it affects before editing implementation.
- Update machine schema, examples, tests, English reference, task guide, and recovery runbook in the same review unit.
- Run link, heading-parity, duplicate-prose, forbidden-internal-language, example, and schema-conformance checks.
- Build release artifacts from the reviewed commit and record reproducible checksums with the release.

## Boundary

Reference owns exact contracts, audience guides own procedures, Architecture owns invariants, and Troubleshooting owns recovery. Cross-link instead of copying a partial contract.

## References

- [Related contract](../editorial-guide.md)
- [Related guide](../governance/compatibility-releases.md)
