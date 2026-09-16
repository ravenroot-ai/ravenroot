**The JDBC extension cannot yet load the fixed PostgreSQL driver.** The extension (`ravenroot-jdbc`)
ships no driver: operators supply their own when they build its bundle. Its driver loader does not yet
accept multi-release driver jars, and PostgreSQL JDBC 42.7.12, the first release that fixes
CVE-2026-54291 (GHSA-j92g-9f8w-j867) and CVE-2026-42198 (GHSA-98qh-xjc8-98pq), is one, so a JDBC
bundle cannot use the fixed driver until that limitation is lifted (#280). The runtime PostgreSQL
execution store is not affected: it ships 42.7.12.
