package ai.ravenroot.extensions.jdbc;

import java.util.Optional;

interface JdbcProfileResolver {
    Optional<JdbcProfile> resolve(String tenant, String profile);
}
