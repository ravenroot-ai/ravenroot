# Credentials, connectors, and egress

Provision privileged dependencies so references are usable but secret material and outbound authority remain contained.

## Operator procedure

1. Configure the credential backend and create secrets through the write-only API; inventory only metadata and minted references.
2. Install connector or model adapters from trusted packages and review their declared capabilities.
3. Set exact allowed hosts and agent-tool allowlists for the deployment; deny everything outside them.
4. Run provider verification and a non-destructive connector check before allowing an effectful graph.

Connector destinations that are numeric IP literals are subject to the same reserved-network policy
as hostnames. Mail (SMTP and IMAP), Telegram, AMQP 0-9-1, and Kafka refuse loopback, link-local,
private, any-local, multicast, broadcast, and carrier-grade NAT literals by default. This check runs
before credential resolution or client/socket creation. It covers dotted and compact IPv4 forms,
IPv6, IPv4-mapped IPv6, and scoped IPv6 zone identifiers; malformed numeric-looking values fail
closed. Hostnames are checked after resolution by the JVM-wide resolver boundary.

The trusted server composition reads `RAVENROOT_EGRESS_RESERVED_EXCEPTIONS`. First-party connectors
capture the same operator value as an immutable startup policy and use the same parser and address
classifier. Graph properties and payloads cannot add exceptions. Entries are comma-separated
`name:NETWORK` pairs. Bracket an IPv6 literal before the network suffix, for example
`[::1]:LOOPBACK` or `[fe80::1%eth0]:LINK_LOCAL`. Native IPv4 aliases and native, mapped, or compatible
IPv6 forms are distinct authorization keys; a grant for one spelling or address family does not
grant another. Zone identifiers are exact and case-sensitive, while URI `%25` is accepted as the
zone delimiter. Restart the process after changing the environment so every immutable connector
snapshot and the DNS guard receive the same policy.

## Authority

The operator grants deployment-level availability. Callers may use only resources they own, and GraphML may carry references but never secret values or new network rights.

## Verification

Verify that credential reads omit secret material, disallowed destinations fail closed, and adapter removal makes dependent nodes unavailable.

- [Contract](../security/input-secrets-egress.md)
- [Runbook](../troubleshooting/ai-extensions.md)
