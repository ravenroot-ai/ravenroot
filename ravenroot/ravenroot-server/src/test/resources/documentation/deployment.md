# Deployment contract fixture

## Limits

| Variable | Default |
|---|---:|
| `RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS` | `3600` |

The active-execution allocation uses ceiling `64`, reserve `8`, per-tenant quota `56`.
See [residual risks](#residual-risks-rate-limits-and-quotas).

### Residual risks (rate limits and quotas)

The active-execution ceiling is global. When it is reached, new submissions receive
`ACTIVE_EXECUTION_CEILING_REACHED`. `MAX_TRACKED_TENANTS` is a service ceiling as well as a
retention bound.

## Public exposure checklist

Behind a proxy, configure both `RAVENROOT_TRUSTED_PROXY_HOPS` and
`RAVENROOT_TRUSTED_PROXY_ADDRESSES`. Leaving them unset behind a proxy places all clients in a
single bucket.
