-- Run once as DBA. Reconcile existing roles/databases instead of deleting them.
\set ON_ERROR_STOP on
CREATE ROLE ravenroot_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password ravenroot_app
CREATE DATABASE ravenroot OWNER ravenroot_app;
REVOKE ALL ON DATABASE ravenroot FROM PUBLIC;
GRANT CONNECT ON DATABASE ravenroot TO ravenroot_app;
\connect ravenroot
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA ravenroot AUTHORIZATION ravenroot_app;
GRANT USAGE, CREATE ON SCHEMA ravenroot TO ravenroot_app;
ALTER ROLE ravenroot_app IN DATABASE ravenroot SET search_path TO ravenroot;
-- Optional separate Keycloak installation: its database must be independent.
\connect postgres
CREATE ROLE keycloak_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password keycloak_app
CREATE DATABASE keycloak OWNER keycloak_app;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak TO keycloak_app;
