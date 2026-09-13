**PostgreSQL JDBC driver 42.7.7.** This release ships the PostgreSQL JDBC driver at 42.7.7 as a
runtime dependency of the PostgreSQL persistence module (`ravenroot-persistence-postgresql`), which
`ravenroot-server` includes. That version is affected by CVE-2026-54291 (GHSA-j92g-9f8w-j867) and
CVE-2026-42198 (GHSA-98qh-xjc8-98pq). The release was made knowingly with both; the driver upgrade to
42.7.12 ships in the next release (#336). The JDBC extension (`ravenroot-jdbc`) ships no driver:
operators supply their own when they build its bundle, and its driver loader accepts the
multi-release PostgreSQL JDBC 42.7.12 jar, so a bundle can use the fixed driver.
