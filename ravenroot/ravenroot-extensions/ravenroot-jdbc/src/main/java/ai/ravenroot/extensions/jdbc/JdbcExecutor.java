package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

final class JdbcExecutor {
    private final JdbcProfileResolver profiles;
    private final JdbcDriverLoader drivers;
    private final JdbcRuntime runtime;

    JdbcExecutor(JdbcProfileResolver profiles, JdbcDriverLoader drivers, JdbcRuntime runtime) {
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.drivers = java.util.Objects.requireNonNull(drivers);
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    java.util.concurrent.CompletableFuture<Object> execute(NodeMessage message, NodePackageServices services,
                                                            String profileName, String statementId,
                                                            JdbcStatementProfile.Kind kind) {
        try {
            String tenant = message.security().tenantId();
            JdbcProfile profile = profiles.resolve(tenant, profileName)
                    .orElseThrow(() -> new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE));
            if (!tenant.equals(profile.tenant()) || !profileName.equals(profile.name()))
                throw new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE);
            JdbcStatementProfile statement = profile.statement(statementId, kind);
            JdbcParameters parameters = JdbcParameters.parse(message.payload(), profile, statement);
            Duration deadline = Duration.ofMillis(profile.deadlineMs());
            return runtime.submit(tenant + "\0" + profile.name(), profile.maxConcurrency(), deadline,
                    control -> invoke(message, services, profile, statement, parameters, control));
        } catch (JdbcFailure failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        } catch (RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(new JdbcFailure(JdbcFailure.Code.EXECUTION_FAILED));
        }
    }

    private Object invoke(NodeMessage message, NodePackageServices services, JdbcProfile profile,
                          JdbcStatementProfile statement, JdbcParameters parameters,
                          JdbcRuntime.Control control) throws Exception {
        OutboundCall<CredentialLease> call = services.credentials().resolve(message, profile.credentialRef(),
                Duration.ofMillis(profile.deadlineMs()));
        control.credential(call);
        CredentialLease lease;
        try {
            lease = call.completion().toCompletableFuture().get(profile.deadlineMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); call.cancel(); throw new JdbcFailure(JdbcFailure.Code.CANCELLED);
        } catch (java.util.concurrent.TimeoutException timeout) {
            call.cancel(); throw new JdbcFailure(JdbcFailure.Code.DEADLINE_EXCEEDED);
        } catch (ExecutionException | CompletionException refused) {
            throw credentialFailure(refused.getCause());
        }
        try (lease) {
            char[] secret = lease.copy();
            try {
                return connectAndExecute(profile, statement, parameters, control, secret);
            } finally { Arrays.fill(secret, '\0'); }
        }
    }

    private Object connectAndExecute(JdbcProfile profile, JdbcStatementProfile statement,
                                     JdbcParameters parameters, JdbcRuntime.Control control,
                                     char[] passwordChars) throws Exception {
        Driver driver = drivers.load(profile);
        control.driverContext(driver.getClass().getClassLoader());
        return JdbcDriverLoader.inContext(driver,
                () -> executeInDriverContext(driver, profile, statement, parameters, control, passwordChars));
    }

    private Object executeInDriverContext(Driver driver, JdbcProfile profile, JdbcStatementProfile statement,
                                          JdbcParameters parameters, JdbcRuntime.Control control,
                                          char[] passwordChars) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", profile.username());
        // JDBC requires an immutable String at this last transport boundary; it is never retained by Ravenroot.
        String password = new String(passwordChars);
        properties.setProperty("password", password);
        Connection connection;
        try { connection = driver.connect(profile.url(), properties); }
        finally { properties.clear(); password = null; }
        if (connection == null) throw new JdbcFailure(JdbcFailure.Code.DRIVER_REFUSED);
        control.connection(connection);
        try (connection) {
            configure(connection, profile, statement.kind());
            return statement.kind() == JdbcStatementProfile.Kind.QUERY
                    ? query(connection, profile, statement, parameters, control)
                    : insert(connection, profile, statement, parameters, control);
        }
    }

    private static void configure(Connection connection, JdbcProfile profile, JdbcStatementProfile.Kind kind)
            throws SQLException {
        if (profile.schema() != null) {
            try { connection.setSchema(profile.schema()); }
            catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError unsupported) {
                throw new JdbcFailure(JdbcFailure.Code.SCHEMA_UNSUPPORTED);
            }
        }
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(profile.isolation());
        if (kind == JdbcStatementProfile.Kind.QUERY) {
            try { connection.setReadOnly(true); } catch (SQLFeatureNotSupportedException | AbstractMethodError ignored) { }
        }
        try { connection.setNetworkTimeout(Runnable::run, profile.deadlineMs()); }
        catch (SQLFeatureNotSupportedException | AbstractMethodError ignored) { }
    }

    private static Object query(Connection connection, JdbcProfile profile, JdbcStatementProfile statement,
                                JdbcParameters parameters, JdbcRuntime.Control control) throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement(statement.sql().jdbcSql())) {
            control.statement(prepared); configure(prepared, profile); parameters.bind(prepared, statement);
            try (ResultSet result = prepared.executeQuery()) { return JdbcResults.query(result, profile); }
        } finally { rollback(connection); }
    }

    private static Object insert(Connection connection, JdbcProfile profile, JdbcStatementProfile statement,
                                 JdbcParameters parameters, JdbcRuntime.Control control) throws SQLException {
        String[] keys = statement.generatedKeys().toArray(String[]::new);
        try (PreparedStatement prepared = keys.length == 0
                ? connection.prepareStatement(statement.sql().jdbcSql(), Statement.NO_GENERATED_KEYS)
                : connection.prepareStatement(statement.sql().jdbcSql(), keys)) {
            control.statement(prepared); configure(prepared, profile); parameters.bind(prepared, statement);
            int affected;
            try { affected = prepared.executeUpdate(); }
            catch (SQLException failure) { rollback(connection); throw failure; }
            Object result;
            try (ResultSet generated = keys.length == 0 ? null : prepared.getGeneratedKeys()) {
                result = JdbcResults.insert(affected, generated, statement, profile);
            }
            control.beginCommit();
            connection.commit();
            control.commitCompleted();
            return result;
        } catch (SQLException failure) {
            if (!control.commitStarted()) rollback(connection);
            throw failure;
        } catch (JdbcFailure failure) {
            if (!control.commitStarted()) rollback(connection);
            throw failure;
        }
    }

    private static void configure(PreparedStatement statement, JdbcProfile profile) throws SQLException {
        statement.setQueryTimeout(Math.max(1, (profile.deadlineMs() + 999) / 1000));
        statement.setMaxRows(profile.maxRows());
        statement.setFetchSize(Math.min(profile.maxRows(), 256));
    }
    private static void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }
    private static JdbcFailure credentialFailure(Throwable failure) {
        if (failure instanceof NodePackageServiceException service
                && service.reason() == NodePackageServiceException.Reason.DEADLINE_EXCEEDED)
            return new JdbcFailure(JdbcFailure.Code.DEADLINE_EXCEEDED);
        if (failure instanceof NodePackageServiceException service
                && service.reason() == NodePackageServiceException.Reason.CANCELLED)
            return new JdbcFailure(JdbcFailure.Code.CANCELLED);
        return new JdbcFailure(JdbcFailure.Code.CREDENTIAL_UNAVAILABLE);
    }
}
