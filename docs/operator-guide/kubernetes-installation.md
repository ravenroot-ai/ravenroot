# Install the complete Ravenroot service on Kubernetes

This procedure installs the complete backend plus UI with PostgreSQL execution persistence, OIDC
through Keycloak, and a PVC for the remaining local stores. It describes the current `dev`
implementation and the planned `0.6.1-alpha.1` deliverables. Wait for that release to be published
before resolving its image; these examples do not assert that the new version is already available.
The [UI-only procedure](kubernetes-ui-only.md) instead connects to a backend you already run.

The manifests are concrete templates, not a managed database or IAM service. They have been checked
against source contracts and exercised for UI runtime behavior; they have not been deployed to your
cluster. Replace image digests, domains, Gateway references, StorageClass and Secrets before use.
Keep your customized copies outside the source tree. A production database/IAM operator may replace
the single-instance examples without changing Ravenroot's connection contract.

## 1. Collect prerequisites and select immutable images

You need Kubernetes access to a namespace, Linux amd64 nodes, a StorageClass suitable for SQLite/WAL,
PostgreSQL 17 (the tested version), an OIDC provider, DNS, trusted HTTPS certificates, and a Gateway
API controller. The supplied routes assume an existing `public-https` Gateway in `gateway-system`
with an `https` listener, allowed routes from your namespace and certificates for
`ravenroot.example.test` and `identity.example.test`. Do not replace another application's root route.
If you use an Ingress controller instead, translate the routes and explicitly disable SSE buffering
and configure a long stream timeout. Provision database and PVC backups and verify a restore first.

```sh
kubectl create namespace ravenroot
kubectl config set-context --current --namespace=ravenroot
# Once published, inspect the version and select its immutable index digest.
docker buildx imagetools inspect ghcr.io/ravenroot-ai/ravenroot:0.6.1-alpha.1
```

Download or copy [full.yaml](../examples/kubernetes/full.yaml),
[full-routes.yaml](../examples/kubernetes/full-routes.yaml) and the prerequisite templates linked below.
Replace the Ravenroot image with `ghcr.io/ravenroot-ai/ravenroot@sha256:<verified-index-digest>`.
The release carries Linux amd64 runtime plus provenance/SBOM attestations; do not assume an ARM
runtime. The full Java image, Maven artifacts, JAR and complete distribution ZIP remain available.
Verify release checksums and provenance as described in [Releasing Ravenroot](../governance/releasing.md).

## 2. Prepare PostgreSQL and its separate databases

Prefer an existing DBA-managed PostgreSQL cluster. Ravenroot requires **one dedicated database per
deployment**, not a schema inside another application's database: the migration advisory lock is
database-wide. Keycloak needs another database and role, even on the same database server.
Ravenroot's role needs table DML plus `USAGE, CREATE` on its schema **on every startup**; do not revoke
CREATE after the first migration. Migrations run automatically and only move forward. Keep host clocks
synchronized and allocate connection capacity for the default pool of 10 per Ravenroot process.

For a new single-instance example, customize [postgresql.yaml](../examples/kubernetes/postgresql.yaml).
Select a tested PostgreSQL 17 bookworm image and pin its digest, provide a certificate whose SAN
includes `postgres.ravenroot.svc.cluster.local`, and create these objects through your secret system:

```sh
kubectl create secret generic postgres-bootstrap --from-file=password=./postgres-bootstrap-password
kubectl create secret tls postgres-tls --cert=./postgres-server.crt --key=./postgres-server.key
kubectl create configmap postgres-ca --from-file=ca.crt=./postgres-ca.crt
kubectl apply -f postgresql.yaml
kubectl rollout status statefulset/postgres
```

The password files are operator-created, permission-restricted inputs, not example credentials.
The TLS Secret is root-owned/group-readable for PostgreSQL UID/GID 999; verify your selected image
and storage driver preserve the ownership and permissions. The StatefulSet is a starting topology,
not HA, automated backup, failover or a substitute for database administration. Restrict ingress to
5432 to approved database clients. The mounted `pg_hba.conf` admits TLS/SCRAM network
connections and rejects non-TLS connections; narrow its address rules to your cluster/client ranges.

Run [prepare-postgresql.sql](../examples/kubernetes/prepare-postgresql.sql) once as DBA through a
verified TLS connection; it prompts for application passwords instead of putting them in SQL:

```sh
psql "host=postgres.ravenroot.svc.cluster.local port=5432 dbname=postgres user=postgres sslmode=verify-full sslrootcert=./postgres-ca.crt" \
  -f prepare-postgresql.sql
```

Run this from an authorized database administration host/pod with network access, or use an approved
TLS tunnel. Reconcile existing roles/databases before running it; never drop them to make an example
pass. Omit the final Keycloak block if IAM already owns its database. Create `ravenroot-database`
with key `password` and, for a new Keycloak, `keycloak-secrets` with `database-password` and
`bootstrap-password`. Use your secret manager, or `kubectl create secret generic ... --from-file=...`.

The Ravenroot JDBC URL in `full.yaml` uses `currentSchema=ravenroot`, `sslmode=verify-full` and the
mounted CA. Change the host to the DBA-provided certificate name if reusing a database. Do not set a
nonblank `RAVENROOT_EXECUTION_STORE_DIR`: PostgreSQL selection refuses that SQLite-only setting.
See the [complete PostgreSQL contract](../reference/postgresql-persistence.md).

## 3. Reuse or install Keycloak with verified HTTPS

An existing OIDC provider that emits Ravenroot's claims can replace Keycloak. If Keycloak already
exists, skip its deployment and use its issuer, audience and HTTPS JWKS endpoint. A browser session
proxy alone does not provide the bearer token Ravenroot expects.

For a new Keycloak, build [Keycloak.Containerfile](../examples/kubernetes/Keycloak.Containerfile)
using an IAM-approved immutable base, push it to your own registry, then put its digest in
[keycloak.yaml](../examples/kubernetes/keycloak.yaml):

```sh
docker build -f Keycloak.Containerfile \
  --build-arg KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:VERSION@sha256:DIGEST \
  -t registry.example.test/iam/keycloak:approved .
# Publish using your approved registry process, inspect the digest, and update keycloak.yaml.
kubectl apply -f keycloak.yaml
kubectl apply -f full-routes.yaml
kubectl rollout status deployment/keycloak
```

The optimized build enables PostgreSQL, health and metrics. The example starts in production mode,
terminates public HTTPS at the Gateway, and trusts proxy headers only where the Gateway overwrites
them and network policy prevents clients reaching the internal HTTP listener. Port 9000 is internal
management only; it is not a Service or public route. Keycloak uses its own database, not Ravenroot's.
After first login, create named administrators and remove the bootstrap environment variables and
bootstrap password from the workload. Follow the current primary [container](https://www.keycloak.org/server/containers),
[health](https://www.keycloak.org/observability/health) and
[proxy](https://www.keycloak.org/server/reverseproxy) procedures for your selected version, including
HA/cache topology when required.

Configure realm `ravenroot` and verify the issuer and `jwks_uri` from:
`https://identity.example.test/realms/ravenroot/.well-known/openid-configuration`.
The Ravenroot pod must resolve/reach that HTTPS endpoint and trust its CA. For private PKI, install
the CA into a separately prepared Java truststore and mount it read-only; configure JVM truststore
settings without disabling hostname/certificate verification. PostgreSQL's CA mount does not alter
Java's HTTPS truststore.

## 4. Map token claims and grant narrow authority

For a new realm, download [keycloak-realm.json](../examples/kubernetes/keycloak-realm.json).
In the Keycloak admin console choose **Create realm**, browse to this JSON, confirm realm name
`ravenroot`, and create it. The file defines the API roles, public workstation clients, explicit
protocol mapper types/configuration and default client scopes; it contains no users, passwords or
client secrets. Set `example-tenant` to your intended tenant before import. For an existing realm,
merge the intended clients/scopes through your IAM process instead of overwriting its configuration.

Create a named human user through your approved identity process. Under **Users → user → Role
mapping → Assign role → Filter by clients**, assign `ravenroot-api`'s `OPERATOR` role for the
execution account. Create a separate administrative account with `PLATFORM_ADMIN` and use
`ravenroot-admin-workstation` for its runtime probe; the example's scope mappings prevent the
ordinary workstation client from requesting that role. No role is granted merely by importing
this realm. Verify **Clients → workstation → Client scopes** contains the listed default scopes,
**Advanced → OAuth 2.0 Device Authorization Grant** is enabled, and token roles match the user's
assignment. Console labels may vary by your approved Keycloak version; the JSON supplies the exact
mapper provider names and keys. Inspect **Client scopes → ravenroot-user → Mappers** for the
audience, hardcoded kind/tenant and client-role mappers. Limit this hardcoded tenant to the intended
client/realm; a multi-tenant IAM deployment needs its own authoritative tenant mapping.

If configuring manually, create audience/client `ravenroot-api`. For human testing, create public client
`ravenroot-workstation` with Device Authorization Grant enabled, client authentication disabled,
Direct Access Grants and Implicit Flow disabled. This is a workstation flow, not a UI callback.
Use client-scoped protocol mappers and include these values in access tokens:

| Claim | Mapping/requirement |
|---|---|
| `iss`, `aud`, `exp`, `sub` | Exact issuer, audience includes `ravenroot-api`, unexpired access token, user subject |
| `token_kind` | String `user` for humans; `workload` for workload principals |
| `tenant_id` | Nonblank tenant, e.g. `example-tenant`; scope hardcoded values to the intended client/tenant |
| `roles` | Top-level array of assigned Ravenroot client roles, e.g. `OPERATOR`; unknown roles are rejected |
| `scope` | Space-separated granted Ravenroot scopes; assign desired client scopes and include them in token scope |

Use RS256 signing with a `kid` published in JWKS. Ravenroot does not automatically consume Keycloak's
`realm_access.roles` or `resource_access` as its top-level `roles`. Turn Full Scope Allowed off and
map only required application roles; do not emit unrelated Keycloak realm roles in `roles`.

A first execution operator generally needs `ravenroot.read`, `ravenroot.graph.inspect`,
`ravenroot.execute`, `ravenroot.observe` and `ravenroot.execution.control` with role `OPERATOR`.
`/v1/runtime` requires `PLATFORM_ADMIN` **and** `ravenroot.observe`; use a separate administrator
for that probe. Artifact management/approval and audit need their distinct roles/scopes. Tenant,
resource ownership and separation-of-duty checks still apply. Verify actual JWT claims locally;
never upload a bearer token to an online decoder. See [identity](identity-browser.md) and
[authorization](../security/trust-identity.md).

## 5. Deploy the full service and keep local data persistent

Customize `full.yaml`, including its origins, JDBC connection and storage class. It mounts
`/opt/ravenroot/data` for all local stores and `/tmp` separately. The deployment runs UID/GID 10001,
read-only root, no capabilities and no service-account token. A single replica and `Recreate` avoid
concurrent writers to SQLite/WAL. PostgreSQL does not authorize increasing the server replica count.
The example disables programmable execution because it supplies no sandbox supervisor; ordinary
graph execution remains separate. Enabling programmable artifacts requires the documented sandbox
and provenance contract, not merely changing this example's flag.

| Durable content in the standalone composition | Storage |
|---|---|
| Execution state/results, graph definitions, execution manifests and event journal | PostgreSQL |
| Security access/decision/artifact audit | `FileAuditTrail` files on PVC (`data/audit`) |
| Programmable artifact registry | SQLite on PVC (`data/artifact-store`) |
| Author-entered credentials | SQLite on PVC (`data/credentials`), secret values are plaintext at rest |
| Assistant consent fallback, when used | SQLite on PVC (`data/assistant-consent`) |
| Legacy embed registrations, when enabled | SQLite at explicitly configured PVC directory |
| Worker cache and operational logs | Cache under PVC; stdout managed by cluster logging |

The manifest omits legacy embed registration settings because that mode is disabled here; if you
enable it, configure its durable directory under the PVC and meet its single-process startup policy.
Modern authority backing and optional assistant mode can select other stores; back up the state your
chosen composition actually uses. The **execution journal is not security audit**. Selecting
PostgreSQL does not move `FileAuditTrail`, artifacts or credentials there. There is no standalone
security-audit PostgreSQL selector in this implementation. If PostgreSQL security audit is required,
that is additional backend work. Protect PVC snapshots/access because credential values are readable
by anyone who can read the volume; require storage encryption/rotation under your operator policy.

```sh
kubectl apply --dry-run=server -f full.yaml
kubectl apply -f full.yaml
kubectl rollout status deployment/ravenroot --timeout=600s
kubectl get pods,pvc,httproute
```

`/health` is unauthenticated process liveness; `/ready` is unauthenticated admission readiness and
fails on drain/degraded dependencies. Do not use readiness as restart liveness. Startup probe allows
cold startup; tune it for migration/IO load. The sample 360-second termination budget must cover your
active-deployment count and in-flight executions; follow [deployment sizing](deployment-startup.md)
rather than assuming Kubernetes' 30-second default is enough. Add ingress/egress NetworkPolicies for
Gateway, database, JWKS/DNS and explicitly required node connectors; do not accidentally block JWKS
refresh or grant arbitrary egress to graph-authored destinations.

## 6. Obtain a token, connect, and verify rejection and acceptance

The UI has **Service token** and **Service URL** fields. OIDC server configuration does not add an
automatic native Keycloak login, callback or token refresh to this UI. Obtain your own access token
using your organization's approved OIDC tool or the executable
[obtain-token.py](../examples/kubernetes/obtain-token.py) workstation Device Authorization helper:

```sh
python3 obtain-token.py \
  --issuer https://identity.example.test/realms/ravenroot \
  --client ravenroot-workstation --output ./operator-access-token.txt
# Repeat as a separate administrative user when verifying /v1/runtime:
python3 obtain-token.py \
  --issuer https://identity.example.test/realms/ravenroot \
  --client ravenroot-admin-workstation --output ./admin-access-token.txt
```

Open the verification URI printed by the helper and enter the temporary user code. It honors the
provider's poll interval, increases it by five seconds on `slow_down`, stops on expiry/denial and
writes the token with mode 0600 using exclusive creation; it never prints the access token or asks
for your password. Keep the output on your own workstation, copy it into the UI and then delete it.
Use the workstation's trusted CA setup for HTTPS; do not disable verification for private PKI.
Client scopes from step 4 must appear in the resulting token. For exact flow semantics see
[Keycloak OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers).

Open `https://ravenroot.example.test/`, leave Service URL blank for same origin, paste the token into
Service token and connect. Replace it after expiration; never put it in GraphML or a manifest.
Using a permission-restricted local bearer-header file with your token, verify:

```sh
curl --fail https://ravenroot.example.test/health
curl --fail https://ravenroot.example.test/ready
# Expected 401 without a bearer token:
curl -i https://ravenroot.example.test/v1/node-types
curl --fail --header @./bearer-header https://ravenroot.example.test/v1/node-types
# Use the separate PLATFORM_ADMIN token for this probe:
curl --fail --header @./admin-bearer-header https://ravenroot.example.test/v1/runtime
curl --no-buffer --header @./bearer-header https://ravenroot.example.test/v1/events
```

Exercise a graph, observe events incrementally, test a denied scope and expiration, then restart the
pod and confirm durable definitions/state and local credentials/artifacts survive. An expired stream
closes when SSE authentication is revalidated (default 30 seconds). Inspect `/ready`, pod events and
logs for refused configuration; a missing Secret is not permission to disable authentication.

## 7. Back up, upgrade and recover

Back up the whole PostgreSQL database and PVC state with a coordinated, tested procedure. Store
migrations are forward-only: keep the prior image digest, take a restorable snapshot before upgrade,
and restore data for a downgrade that cannot read a newer schema. A replacement UI alone has no
backend schema migration. Check [persistence and recovery](persistence-lifecycle.md),
[PostgreSQL restore](../reference/postgresql-persistence.md) and
[backup boundary](../reference/backup-recovery.md) before production use.
