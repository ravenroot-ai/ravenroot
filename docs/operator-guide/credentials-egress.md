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
zone delimiter in the exception declaration. A connector host keeps its raw zone identity and is
bound to the scope selected by the resolver: raw `%2` and `%252` therefore remain distinct physical
scopes. Restart the process after changing the environment so every immutable connector snapshot
and the DNS guard receive the same policy.

HTTP allowlists and fixed HTTP origins apply the same identity rule. DNS names and IPv6 address
digits are case-normalized, while every byte after the first `%` remains exact through admission,
plaintext proof matching, the stored transport URI, and request signing. The same rule applies to
node-package origin, credential-placement and signing grants, and Telegram URL-host profiles
(including environment configuration). Telegram button and callback URLs must satisfy both their
profile and the reserved-address policy; passing one never widens the other. The original URL is
forwarded to Telegram, without Ravenroot fetching that button or callback destination.

Unset or blank configuration preserves the shipped localhost-only exception
`localhost:LOOPBACK`; it does not authorize other loopback names or addresses. The development
benchmark harness may export a broader named exception set when its variable is absent. That
harness-only value is separate from the production default and is never inferred by the server.

## Trusted networks and plaintext transports

`RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY` is an administrator-only, canonical padded Base64 encoding
of strict JSON. It lets a deployment admit known internal ranges and, separately, allow an
unencrypted hop when encryption terminates in an authenticated service mesh, tunnel, or local proxy.
A graph may select an existing profile, but no graph, workflow, payload, redirect, or remote response
can create or widen a rule or service grant.

Trusted-network host matching follows resolved-host spelling, separately from the legacy exception
parser above. Matching outer IPv6 brackets are accepted, and DNS names and IPv6 hexadecimal digits
are case-normalized, but the complete zone suffix is exact and case-sensitive. `%2` and `%252`
remain different because the resolver assigns them scope IDs 2 and 252; this boundary does not
decode `%25`.

The decoded document has exactly this schema. Unknown or missing members, duplicate values, wildcard
hosts/profiles, non-network CIDRs, noncanonical Base64, and unknown protocols fail closed:

```json
{
  "version": 1,
  "rules": [{
    "name": "orders-mesh",
    "protocols": ["amqp091"],
    "ports": [5672],
    "hosts": ["rabbitmq.orders.svc.cluster.local"],
    "addresses": ["10.96.0.0/12"],
    "profiles": ["acme/orders"],
    "allowPlaintext": true
  }]
}
```

Supported protocol names are `http`, `websocket`, `amqp091`, `kafka`, `smtp`, `imap`, `otlp`, `git`,
`assistant`, `jwks`, and `runner`. Every rule names at least
one protocol, at least one finite port, and at least one host or address range. Profiles are exact
connector-specific names: AMQP, Kafka, SMTP, and IMAP use `tenant/profile`; managed HTTP and WebSocket use
the node-package id. An empty `profiles` list applies to every profile within the other finite scope.
There is no wildcard and no global off switch.

Admission, transport encryption, and certificate verification are separate decisions:

- `addresses` grants reserved-address admission. A hostname alone never trusts an arbitrary reserved
  answer. When a rule has both `hosts` and `addresses`, both must match, and the same rule must admit
  every DNS answer. A CIDR-only rule is useful for numeric Kubernetes service addresses and remains
  bounded by protocol and port.
- `allowPlaintext` permits a cleartext transport only within that same complete rule. It does not add
  an endpoint to a connector profile, add an origin to a service grant, or grant a credential.
  Credential-bearing plaintext therefore needs the ordinary exact profile/grant and a matching rule.
- TLS continues to verify the certificate chain and hostname. This policy has no certificate-bypass,
  trust-all, or hostname-verification-disable capability.

DNS is checked twice. The connector validates the complete answer set under its protocol/profile
rule before opening a client. The JVM resolver repeats the address check when the connection resolves
the name and refuses a mixed answer rather than dropping only unsafe members. Because that resolver
has no protocol/profile parameter, overlapping explicit-host rules use their address-range
intersection at connection time. Disjoint ranges for one hostname fail closed; consolidate them into
one shared envelope or use different hostnames. CIDR-only rules cannot safely authorize an arbitrary
DNS name at that context-free boundary, so use them with numeric endpoints or add an exact hostname.
HTTP clients do not follow redirects, so a redirect cannot move credentials to another origin.

For a Kubernetes broker named `rabbitmq.orders.svc.cluster.local` with ClusterIP `10.101.4.12`, name
the DNS identity and cluster range, exact AMQP port, and exact `tenant/profile`. Set
`allowPlaintext:true` only when the pod-to-sidecar hop is the intended cleartext segment and the mesh
authenticates and encrypts the next hop. A managed HTTP rule uses protocol `http` and the node-package
id in `profiles`; its service grant must still list the exact HTTP origin and credential binding.

An IMAP hop through an authenticated mesh or TLS-terminating proxy uses protocol `imap`, the exact
IMAP port and `tenant/profile`, and the same hostname/address intersection. Only after installing
that rule may an administrator change the profile security mode to `PLAIN`. Queries, mutations, and
long-lived consumers repeat admission and plaintext checks immediately before each connection.
`IMAPS` and required `STARTTLS` keep their existing certificate and hostname verification.

Kafka rules must cover more than `bootstrap.servers`. Kafka uses bootstrap endpoints to discover
advertised brokers and group coordinators, so every advertised hostname and port must fit the exact
`kafka` tenant/profile rule. Ravenroot's pinned Kafka 4.1.2 adapter validates full metadata updates
atomically, registers direct coordinator paths, resolves the original endpoint again immediately
before every socket connection or reconnect, and permits only an address from that endpoint's fresh
admitted set. A reused node id, numeric alias, changed port, failed metadata batch, or stale DNS answer
cannot inherit a bootstrap grant. TLS connections keep Kafka's standard chain, hostname, and SNI
verification. The guarded consumer uses Kafka's classic group protocol; a Kafka client upgrade must
recompile this version-pinned adapter before deployment.

### Private PKI

Mount the internal CA as a PKCS12 or JKS trust store and configure the JVM with
`-Djavax.net.ssl.trustStore=/run/ravenroot/internal-ca.p12` and the corresponding protected
`javax.net.ssl.trustStorePassword`. Ravenroot's JSSE-backed clients keep chain and hostname
verification and use the default JVM trust-manager configuration. Rotate the mounted store and
restart the process. A private CA is not a reason to set `allowPlaintext`; TLS termination is.

### Migration and precedence

`RAVENROOT_EGRESS_RESERVED_EXCEPTIONS` remains admission-only compatibility input. It never permits
plaintext or changes certificate verification. A new rule may admit the same destination without a
duplicate legacy entry. When an explicit hostname appears in multiple new rules, connection-time DNS
uses the conservative intersection described above; legacy entries do not widen plaintext, protocol,
port, or profile scope. Migrate one connector at a time, verify it, then remove a redundant legacy
entry.

Existing IMAP profiles remain `IMAPS` or required `STARTTLS` until an administrator edits them.
To migrate one profile, install and verify its exact `imap` rule first, then change that profile to
`PLAIN` and restart. A missing, nonmatching, or admission-only rule refuses the profile; an unused
plaintext rule never downgrades an encrypted profile.

### Connector coverage and protocol limits

- Managed HTTP/WebSocket profiles (AI/LLM, MCP, GitHub, Matrix, Mattermost, OpenAPI, Teams, and generic
  WebSocket) support scoped plaintext while retaining exact service grants. Slack, Discord, and
  Telegram production origins remain fixed HTTPS endpoints and are not converted into proxy settings.
- AMQP 0-9-1, Kafka producer/consumer, SMTP, and IMAP support authenticated non-loopback plaintext
  only through an exact rule. IMAP queries, mutations, and consumers require an exact-scope proof
  from the administrator resolver and revalidate the destination at transport time; ordinary public
  constructors remain limited to `IMAPS` and required `STARTTLS`.
  Kafka producer, consumer, DLQ, advertised-broker, coordinator, refresh, and reconnect paths share
  the exact profile rule and revalidate at the final socket boundary.
- S3-compatible object storage may use signed HTTP only when both its exact SigV4 service grant and
  a matching trusted-network plaintext rule admit the origin; HTTPS remains the default.
- Assistant, JWKS, runner control/model, and OTLP administrator endpoints apply scoped admission and
  plaintext rules. Redirects remain disabled.
- The native `kubectl` runner manager retains a CA-verified HTTPS kubeconfig. Kubernetes supports
  HTTP, but the `kubectl` subprocess does not expose Ravenroot-controlled DNS pinning or redirect
  boundaries. Ravenroot therefore cannot revalidate the complete answer set at the actual
  connection or prove that a plaintext redirect stayed within the authorized scope. Use the
  private-PKI trust path above for Kubernetes API servers.
- JDBC URLs are driver-defined and may be opaque. Ravenroot does not invent generic TLS or port
  semantics for unknown vendors; configure the driver's TLS properties and JVM trust store.
  PostgreSQL execution-store TLS remains deployment-infrastructure configuration.
- Git workspace HTTPS remains the default. Administrator-authorized HTTP, including credentialed
  HTTP, uses an exact `git` rule. Immediately before each remote fetch Ravenroot validates every DNS
  answer, pins that set into Git/libcurl with `http.curloptResolve`, and keeps redirects disabled.
  Local `file:` remotes remain credential-free.

Policy failures use stable, secret-free causes such as `OUTBOUND_DESTINATION_POLICY_REFUSED`,
`OUTBOUND_TRANSPORT_ENCRYPTION_REQUIRED`, and `OUTBOUND_DESTINATION_UNRESOLVED`. They omit raw policy,
URL, hostname, username, credential reference, and secret values.

## Authority

The operator grants deployment-level availability. Callers may use only resources they own, and GraphML may carry references but never secret values or new network rights.

## Verification

Verify that credential reads omit secret material, disallowed destinations fail closed, and adapter removal makes dependent nodes unavailable.

- [Contract](../security/input-secrets-egress.md)
- [Runbook](../troubleshooting/ai-extensions.md)
