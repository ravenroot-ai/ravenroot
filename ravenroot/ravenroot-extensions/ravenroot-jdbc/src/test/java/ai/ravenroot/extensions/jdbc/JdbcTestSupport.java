package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

final class JdbcTestSupport {
    private JdbcTestSupport() { }

    static JdbcProfile profile(JdbcStatementProfile statement) {
        return profile("tenant-a", "main", statement, 1_000, 1);
    }
    static JdbcProfile profile(String tenant, String name, JdbcStatementProfile statement,
                               int deadlineMs, int maxConcurrency) {
        return new JdbcProfile(tenant, name, "fake-driver", "test.Driver", "a".repeat(64),
                "jdbc:test://db.example/app", "operator-user", "db-password",
                Connection.TRANSACTION_READ_COMMITTED, deadlineMs, maxConcurrency, 16, 4096,
                16, 8, 1024, 16384, 8, Map.of(statement.id(), statement));
    }
    static JdbcStatementProfile query(String sql) {
        return new JdbcStatementProfile("find", JdbcStatementProfile.Kind.QUERY, NamedSql.parse(sql), Set.of());
    }
    static JdbcStatementProfile insert(String sql) {
        return new JdbcStatementProfile("add", JdbcStatementProfile.Kind.INSERT, NamedSql.parse(sql), Set.of("id"));
    }
    static NodeMessage message(String tenant, Object payload) {
        return new NodeMessage(new SecurityContext("request", tenant, "tester", PrincipalType.USER, "issuer"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Set.of(), "jdbc",
                payload, Map.of("trace", "kept"));
    }
    static Object parameters(Map<String, Object> values) {
        return Map.of("contract", "jdbc.parameters.v1", "parameters", values);
    }
    static NodePackageServices services(String password, AtomicInteger resolutions) {
        NodePackageServices deny = NodePackageServices.unavailable();
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION); }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return (message, reference, deadline) -> {
                    resolutions.incrementAndGet();
                    return OutboundCall.completed(new CredentialLease(password.toCharArray()));
                };
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() { return deny.outboundHttp(); }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() { return deny.outboundWebSocket(); }
        };
    }
    static JdbcFailure failure(Throwable thrown) {
        Throwable current = thrown;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        if (!(current instanceof JdbcFailure typed)) throw new AssertionError("unexpected " + current, current);
        return typed;
    }
}
