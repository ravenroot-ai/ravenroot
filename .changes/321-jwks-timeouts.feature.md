Operators can bound how long the server waits when it retrieves the OIDC JSON Web Key Set, with
`RAVENROOT_AUTH_JWKS_CONNECT_TIMEOUT_SECONDS` and `RAVENROOT_AUTH_JWKS_REQUEST_TIMEOUT_SECONDS`: whole
seconds from 1 through 300, defaulting to 3 and 5, read once at startup. See the configuration
reference.
