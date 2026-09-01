package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExecutorTest {
    @Test void queryUsesOneCredentialExactBindingsReadOnlyRollbackAndBoundedOrderedRows() {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id,name FROM users WHERE name=:name");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State();
        AtomicInteger resolutions = new AtomicInteger();
        JdbcExecutor executor = executor(profile, state);

        Object raw = executor.execute(JdbcTestSupport.message("tenant-a",
                        JdbcTestSupport.parameters(Map.of("name", "x' OR 1=1 --"))),
                JdbcTestSupport.services("rotated-secret", resolutions), "main", "find",
                JdbcStatementProfile.Kind.QUERY).join();

        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        assertEquals("jdbc.query.result.v1", result.get("contract"));
        assertEquals(List.of("id", "name"), result.get("columns"));
        assertEquals(List.of(List.of(1L, "Ada")), result.get("rows"));
        assertEquals("SELECT id,name FROM users WHERE name=?", state.sql);
        assertEquals("x' OR 1=1 --", state.bindings.get(1));
        assertEquals("rotated-secret", state.password);
        assertEquals(1, resolutions.get());
        assertTrue(state.readOnly); assertFalse(state.autoCommit);
        assertEquals(1, state.rollbacks.get()); assertEquals(1, state.closes.get());
    }

    @Test void insertCommitsOnceAndProjectsOnlyAllowlistedGeneratedKeys() {
        JdbcStatementProfile statement = JdbcTestSupport.insert("INSERT INTO users(name) VALUES (:name)");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State();
        Object raw = executor(profile, state).execute(JdbcTestSupport.message("tenant-a",
                        JdbcTestSupport.parameters(Map.of("name", "Ada"))),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "add",
                JdbcStatementProfile.Kind.INSERT).join();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        assertEquals(1L, result.get("affectedRows"));
        assertEquals(List.of(Map.of("id", 41L)), result.get("generatedKeys"));
        assertEquals(1, state.commits.get()); assertEquals(0, state.rollbacks.get());
    }

    @Test void operatorSchemaIsAppliedToOnlyThatFreshConnection() {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile base = JdbcTestSupport.profile(statement);
        JdbcProfile profile = new JdbcProfile(base.tenant(), base.name(), base.driverId(), base.driverClass(),
                base.driverSha256(), base.url(), base.username(), base.credentialRef(), "accounting",
                base.isolation(), base.deadlineMs(), base.maxConcurrency(), base.maxParameters(),
                base.maxParameterBytes(), base.maxRows(), base.maxColumns(), base.maxCellBytes(),
                base.maxTotalBytes(), base.maxGeneratedKeyRows(), base.statements());
        FakeJdbc.State state = new FakeJdbc.State();
        state.columns = List.of("id"); state.rows = List.of(List.of(1L));

        executor(profile, state).execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                JdbcStatementProfile.Kind.QUERY).join();

        assertEquals("accounting", state.schema);
        assertEquals(1, state.closes.get());
    }

    @Test void unsupportedConfiguredSchemaFailsWithStableRedactedCodeAndClosesConnection() {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile base = JdbcTestSupport.profile(statement);
        JdbcProfile profile = new JdbcProfile(base.tenant(), base.name(), base.driverId(), base.driverClass(),
                base.driverSha256(), base.url(), base.username(), base.credentialRef(), "accounting",
                base.isolation(), base.deadlineMs(), base.maxConcurrency(), base.maxParameters(),
                base.maxParameterBytes(), base.maxRows(), base.maxColumns(), base.maxCellBytes(),
                base.maxTotalBytes(), base.maxGeneratedKeyRows(), base.statements());
        FakeJdbc.State state = new FakeJdbc.State(); state.schemaUnsupported = true;

        CompletionException thrown = assertThrows(CompletionException.class, () -> executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join());

        JdbcFailure failure = JdbcTestSupport.failure(thrown);
        assertEquals(JdbcFailure.Code.SCHEMA_UNSUPPORTED, failure.code());
        assertEquals("JDBC_SCHEMA_UNSUPPORTED", failure.getMessage());
        assertEquals(1, state.closes.get());
    }

    @Test void anyFailureAfterCommitStartsIsStableAmbiguousAndNeverRetried() {
        JdbcStatementProfile statement = JdbcTestSupport.insert("INSERT INTO users(name) VALUES (:name)");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State(); state.commitFailure = true;
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of("name", "Ada"))),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "add",
                        JdbcStatementProfile.Kind.INSERT).join());
        JdbcFailure failure = JdbcTestSupport.failure(thrown);
        assertEquals(JdbcFailure.Code.AMBIGUOUS_COMMIT, failure.code());
        assertEquals("JDBC_AMBIGUOUS_COMMIT", failure.getMessage());
        assertEquals(1, state.commits.get());
    }

    @Test void deadlineDuringCommitIsAmbiguousAndCommitIsNeverRetried() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.insert("INSERT INTO users(name) VALUES (:name)");
        JdbcProfile profile = JdbcTestSupport.profile("tenant-a", "main", statement, 100, 1);
        FakeJdbc.State state = new FakeJdbc.State(); state.blockCommit();
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of("name", "Ada"))),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "add",
                        JdbcStatementProfile.Kind.INSERT).join());
        assertEquals(JdbcFailure.Code.AMBIGUOUS_COMMIT, JdbcTestSupport.failure(thrown).code());
        assertEquals(1, state.commits.get());
        for (int attempt = 0; attempt < 100 && state.aborts.get() + state.closes.get() == 0; attempt++) Thread.sleep(5);
        assertTrue(state.aborts.get() > 0 || state.closes.get() > 0);
    }

    @Test void cancellationWinningBeforeCommitPreventsCommitAndPublishesOneStableTerminal() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.insert("INSERT INTO users(name) VALUES (:name)");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State();
        state.blockGeneratedKeyClose();
        var future = executor(profile, state).execute(JdbcTestSupport.message("tenant-a",
                        JdbcTestSupport.parameters(Map.of("name", "Ada"))),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "add",
                JdbcStatementProfile.Kind.INSERT);
        assertTrue(state.keyCloseEntered.await(1, TimeUnit.SECONDS));
        AtomicInteger terminals = new AtomicInteger();
        future.whenComplete((value, failure) -> terminals.incrementAndGet());

        assertTrue(future.cancel(true));
        assertEquals(JdbcFailure.Code.CANCELLED,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, future::join)).code());
        state.keyCloseRelease.countDown();
        for (int attempt = 0; attempt < 100 && state.rollbacks.get() == 0; attempt++) Thread.sleep(5);

        assertEquals(0, state.commits.get(), "a cancellation handshake winner must make commit unreachable");
        assertEquals(1, state.rollbacks.get());
        assertEquals(1, terminals.get());
    }

    @Test void commitWinningBeforeCancellationPublishesAmbiguousExactlyOnce() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.insert("INSERT INTO users(name) VALUES (:name)");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State();
        state.blockCommit();
        var future = executor(profile, state).execute(JdbcTestSupport.message("tenant-a",
                        JdbcTestSupport.parameters(Map.of("name", "Ada"))),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "add",
                JdbcStatementProfile.Kind.INSERT);
        assertTrue(state.commitEntered.await(1, TimeUnit.SECONDS));
        AtomicInteger terminals = new AtomicInteger();
        future.whenComplete((value, failure) -> terminals.incrementAndGet());

        assertTrue(future.cancel(true));
        assertEquals(JdbcFailure.Code.AMBIGUOUS_COMMIT,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, future::join)).code());
        state.commitRelease.countDown();
        for (int attempt = 0; attempt < 100 && state.closes.get() == 0; attempt++) Thread.sleep(5);

        assertEquals(1, state.commits.get());
        assertEquals(1, terminals.get());
        assertEquals(JdbcFailure.Code.AMBIGUOUS_COMMIT,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, future::join)).code());
    }

    @Test void failureAfterSuccessfulCommitStillReportsAmbiguous() {
        JdbcStatementProfile statement = JdbcTestSupport.insert("INSERT INTO users(name) VALUES (:name)");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State(); state.closeFailure = true;
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of("name", "Ada"))),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "add",
                        JdbcStatementProfile.Kind.INSERT).join());
        assertEquals(JdbcFailure.Code.AMBIGUOUS_COMMIT, JdbcTestSupport.failure(thrown).code());
        assertEquals(1, state.commits.get());
    }

    @Test void resultOverflowIsSanitizedAndRollsBack() {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id,name FROM users");
        JdbcProfile base = JdbcTestSupport.profile(statement);
        JdbcProfile profile = new JdbcProfile(base.tenant(), base.name(), base.driverId(), base.driverClass(), base.driverSha256(),
                base.url(), base.username(), base.credentialRef(), base.isolation(), base.deadlineMs(),
                base.maxConcurrency(), base.maxParameters(), base.maxParameterBytes(), 1, base.maxColumns(),
                base.maxCellBytes(), base.maxTotalBytes(), base.maxGeneratedKeyRows(), base.statements());
        FakeJdbc.State state = new FakeJdbc.State(); state.rows = List.of(List.of(1L, "A"), List.of(2L, "B"));
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join());
        assertEquals(JdbcFailure.Code.RESULT_LIMIT_EXCEEDED, JdbcTestSupport.failure(thrown).code());
        assertEquals(1, state.rollbacks.get());
    }

    @Test void queryPreservesCanonicalSqlNulls() {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id,name FROM users");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State();
        state.rows = java.util.Collections.singletonList(java.util.Arrays.asList(1L, null));
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join();
        @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) result.get("rows");
        assertEquals(java.util.Arrays.asList(1L, null), rows.getFirst());
    }

    @Test void admissionRefusesConcurrentSameTenantProfileAndCancellationUnwindsResources() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State(); state.columns = List.of("id"); state.rows = List.of(List.of(1L)); state.block();
        JdbcExecutor executor = executor(profile, state);
        var first = executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find", JdbcStatementProfile.Kind.QUERY);
        assertTrue(state.entered(1, TimeUnit.SECONDS));
        var second = executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find", JdbcStatementProfile.Kind.QUERY);
        assertEquals(JdbcFailure.Code.ADMISSION_REFUSED,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, second::join)).code());
        first.cancel(true);
        for (int attempt = 0; attempt < 100 && state.closes.get() == 0; attempt++) Thread.sleep(5);
        assertTrue(state.cancels.get() > 0 || state.aborts.get() > 0);
        assertTrue(state.closes.get() > 0);
        state.release.countDown();
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("pw2", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join();
                return;
            } catch (CompletionException refused) {
                if (JdbcTestSupport.failure(refused).code() != JdbcFailure.Code.ADMISSION_REFUSED) throw refused;
                Thread.sleep(5);
            }
        }
        throw new AssertionError("cancelled invocation retained admission");
    }

    @Test void deadlineCancelsStatementAbortsConnectionAndReportsStableCode() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile profile = JdbcTestSupport.profile("tenant-a", "main", statement, 100, 1);
        FakeJdbc.State state = new FakeJdbc.State(); state.columns = List.of("id"); state.rows = List.of(List.of(1L)); state.block();
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor(profile, state)
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join());
        assertEquals(JdbcFailure.Code.DEADLINE_EXCEEDED, JdbcTestSupport.failure(thrown).code());
        for (int attempt = 0; attempt < 100 && state.closes.get() == 0; attempt++) Thread.sleep(5);
        assertTrue(state.cancels.get() > 0 || state.aborts.get() > 0);
        assertTrue(state.closes.get() > 0);
    }

    @Test void blockingStatementCancelCannotSuppressAbortCloseOrPermitRelease() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile profile = JdbcTestSupport.profile("tenant-a", "main", statement, 100, 1);
        FakeJdbc.State state = new FakeJdbc.State();
        state.columns = List.of("id"); state.rows = List.of(List.of(1L)); state.block(); state.blockCancel();
        JdbcExecutor executor = executor(profile, state);

        CompletionException thrown = assertThrows(CompletionException.class, () -> executor
                .execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join());

        assertEquals(JdbcFailure.Code.DEADLINE_EXCEEDED, JdbcTestSupport.failure(thrown).code());
        assertTrue(state.cancelEntered.await(1, TimeUnit.SECONDS));
        for (int attempt = 0; attempt < 100 && state.aborts.get() + state.closes.get() < 2; attempt++) Thread.sleep(5);
        assertTrue(state.aborts.get() > 0, "connection abort must run independently of blocked cancel");
        assertTrue(state.closes.get() > 0, "connection close must run independently of blocked cancel");

        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("next", new AtomicInteger()), "main", "find",
                        JdbcStatementProfile.Kind.QUERY).join();
                state.cancelRelease.countDown();
                return;
            } catch (CompletionException refused) {
                if (JdbcTestSupport.failure(refused).code() != JdbcFailure.Code.ADMISSION_REFUSED) throw refused;
                Thread.sleep(5);
            }
        }
        state.cancelRelease.countDown();
        throw new AssertionError("blocked Statement.cancel retained admission after the worker unwound");
    }

    @Test void connectionPublishedAfterCancellationIsClosedEvenWhenDriverIgnoredInterrupt() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State(); state.columns = List.of("id"); state.rows = List.of(List.of(1L));
        state.blockConnectIgnoringInterrupt();
        var call = executor(profile, state).execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find", JdbcStatementProfile.Kind.QUERY);
        assertTrue(state.connectEntered.await(1, TimeUnit.SECONDS));
        call.cancel(true); state.connectRelease.countDown();
        for (int attempt = 0; attempt < 100 && state.closes.get() == 0; attempt++) Thread.sleep(5);
        assertTrue(state.closes.get() > 0);
    }

    @Test void credentialsRotatePerInvocationAndTenantAdmissionIsIndependent() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile tenantA = JdbcTestSupport.profile("tenant-a", "main", statement, 1_000, 1);
        JdbcProfile tenantB = JdbcTestSupport.profile("tenant-b", "main", statement, 1_000, 1);
        FakeJdbc.State stateA = new FakeJdbc.State(); stateA.columns = List.of("id"); stateA.rows = List.of(List.of(1L)); stateA.block();
        FakeJdbc.State stateB = new FakeJdbc.State(); stateB.columns = List.of("id"); stateB.rows = List.of(List.of(2L)); stateB.block();
        JdbcRuntime runtime = new JdbcRuntime(System::nanoTime);
        JdbcExecutor executor = new JdbcExecutor((tenant, name) -> Optional.of(tenant.equals("tenant-a") ? tenantA : tenantB),
                profile -> new FakeJdbc(profile.tenant().equals("tenant-a") ? stateA : stateB), runtime);
        var a = executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("secret-a", new AtomicInteger()), "main", "find", JdbcStatementProfile.Kind.QUERY);
        var b = executor.execute(JdbcTestSupport.message("tenant-b", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("secret-b", new AtomicInteger()), "main", "find", JdbcStatementProfile.Kind.QUERY);
        assertTrue(stateA.entered(1, TimeUnit.SECONDS)); assertTrue(stateB.entered(1, TimeUnit.SECONDS));
        assertEquals("secret-a", stateA.password); assertEquals("secret-b", stateB.password);
        stateA.release.countDown(); stateB.release.countDown(); a.join(); b.join();

        AtomicInteger resolutions = new AtomicInteger();
        FakeJdbc.State rotated = new FakeJdbc.State(); rotated.columns = List.of("id"); rotated.rows = List.of(List.of(1L));
        JdbcExecutor rotating = executor(tenantA, rotated);
        rotating.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("old", resolutions), "main", "find", JdbcStatementProfile.Kind.QUERY)
                .thenCompose(ignored -> rotating.execute(
                        JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                        JdbcTestSupport.services("new", resolutions), "main", "find",
                        JdbcStatementProfile.Kind.QUERY))
                .join();
        assertEquals(2, resolutions.get()); assertEquals("new", rotated.password);
    }

    @Test void concurrentPostgresqlAndMysqlProfilesSelectDistinctOperatorConnections() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile base = JdbcTestSupport.profile(statement);
        JdbcProfile postgresql = new JdbcProfile("tenant-a", "orders-pg", "postgresql-42.7.7",
                base.driverClass(), base.driverSha256(), "jdbc:postgresql://pg.internal:5432/orders",
                "orders_app", "orders-db-password", "accounting", base.isolation(), 1_000, 1,
                base.maxParameters(), base.maxParameterBytes(), base.maxRows(), base.maxColumns(),
                base.maxCellBytes(), base.maxTotalBytes(), base.maxGeneratedKeyRows(), base.statements());
        JdbcProfile mysql = new JdbcProfile("tenant-a", "crm-mysql", "mysql-connector-j-9.5.0",
                base.driverClass(), base.driverSha256(), "jdbc:mysql://mysql.internal:3306/crm",
                "crm_app", "crm-db-password", null, base.isolation(), 1_000, 1,
                base.maxParameters(), base.maxParameterBytes(), base.maxRows(), base.maxColumns(),
                base.maxCellBytes(), base.maxTotalBytes(), base.maxGeneratedKeyRows(), base.statements());
        FakeJdbc.State pgState = new FakeJdbc.State(); pgState.columns = List.of("id");
        pgState.rows = List.of(List.of(1L)); pgState.block();
        FakeJdbc.State mysqlState = new FakeJdbc.State(); mysqlState.columns = List.of("id");
        mysqlState.rows = List.of(List.of(2L)); mysqlState.block();
        JdbcExecutor executor = new JdbcExecutor((tenant, name) -> Optional.of(
                "orders-pg".equals(name) ? postgresql : mysql),
                profile -> new FakeJdbc(profile.driverId().equals(postgresql.driverId()) ? pgState : mysqlState),
                new JdbcRuntime(System::nanoTime));

        var pgCall = executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("pg-secret", new AtomicInteger()), "orders-pg", "find",
                JdbcStatementProfile.Kind.QUERY);
        var mysqlCall = executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                JdbcTestSupport.services("mysql-secret", new AtomicInteger()), "crm-mysql", "find",
                JdbcStatementProfile.Kind.QUERY);

        assertTrue(pgState.entered(1, TimeUnit.SECONDS));
        assertTrue(mysqlState.entered(1, TimeUnit.SECONDS));
        assertEquals("jdbc:postgresql://pg.internal:5432/orders", pgState.url);
        assertEquals("orders_app", pgState.user); assertEquals("pg-secret", pgState.password);
        assertEquals("accounting", pgState.schema);
        assertEquals("jdbc:mysql://mysql.internal:3306/crm", mysqlState.url);
        assertEquals("crm_app", mysqlState.user); assertEquals("mysql-secret", mysqlState.password);
        assertEquals(null, mysqlState.schema);
        pgState.release.countDown(); mysqlState.release.countDown();
        pgCall.join(); mysqlCall.join();
    }

    @Test void everyJdbcCallRunsUnderTheExactDriverClassLoaderContext() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id,name FROM users");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        FakeJdbc.State state = new FakeJdbc.State();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger mismatches = new AtomicInteger();
        try (var driverLoader = new URLClassLoader(new java.net.URL[0], getClass().getClassLoader())) {
            Driver delegate = new FakeJdbc(state);
            Driver checked = (Driver) Proxy.newProxyInstance(driverLoader, new Class<?>[]{Driver.class},
                    (proxy, method, arguments) -> invokeChecked(delegate, method, arguments,
                            driverLoader, calls, mismatches));
            JdbcExecutor executor = new JdbcExecutor((tenant, name) -> Optional.of(profile), ignored -> checked,
                    new JdbcRuntime(System::nanoTime));

            executor.execute(JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                    JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                    JdbcStatementProfile.Kind.QUERY).join();

            assertTrue(calls.get() > 15, "connect, configuration, statement, result and cleanup must be observed");
            assertEquals(0, mismatches.get(), "no JDBC callback may escape the driver's private TCCL");
        }
    }

    @Test void cancellationJdbcCallbacksAlsoUseTheExactDriverContext() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT id FROM users");
        JdbcProfile profile = JdbcTestSupport.profile("tenant-a", "main", statement, 100, 1);
        FakeJdbc.State state = new FakeJdbc.State();
        state.columns = List.of("id"); state.rows = List.of(List.of(1L)); state.block();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger mismatches = new AtomicInteger();
        try (var driverLoader = new URLClassLoader(new java.net.URL[0], getClass().getClassLoader())) {
            Driver delegate = new FakeJdbc(state);
            Driver checked = (Driver) Proxy.newProxyInstance(driverLoader, new Class<?>[]{Driver.class},
                    (proxy, method, arguments) -> invokeChecked(delegate, method, arguments,
                            driverLoader, calls, mismatches));
            JdbcExecutor executor = new JdbcExecutor((tenant, name) -> Optional.of(profile), ignored -> checked,
                    new JdbcRuntime(System::nanoTime));

            assertThrows(CompletionException.class, () -> executor.execute(
                    JdbcTestSupport.message("tenant-a", JdbcTestSupport.parameters(Map.of())),
                    JdbcTestSupport.services("pw", new AtomicInteger()), "main", "find",
                    JdbcStatementProfile.Kind.QUERY).join());
            for (int attempt = 0; attempt < 100 && state.aborts.get() + state.closes.get() < 2; attempt++) {
                Thread.sleep(5);
            }

            assertTrue(state.cancels.get() > 0);
            assertTrue(state.aborts.get() > 0);
            assertTrue(state.closes.get() > 0);
            assertEquals(0, mismatches.get(), "cancel/abort/close lanes must retain the private driver TCCL");
        }
    }

    private static Object invokeChecked(Object target, java.lang.reflect.Method method, Object[] arguments,
                                        ClassLoader expected, AtomicInteger calls, AtomicInteger mismatches)
            throws Throwable {
        calls.incrementAndGet();
        if (Thread.currentThread().getContextClassLoader() != expected) mismatches.incrementAndGet();
        Object result;
        try { result = method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
        if (result instanceof Connection connection)
            return checkedProxy(Connection.class, connection, expected, calls, mismatches);
        if (result instanceof PreparedStatement statement)
            return checkedProxy(PreparedStatement.class, statement, expected, calls, mismatches);
        if (result instanceof ResultSet resultSet)
            return checkedProxy(ResultSet.class, resultSet, expected, calls, mismatches);
        if (result instanceof ResultSetMetaData metadata)
            return checkedProxy(ResultSetMetaData.class, metadata, expected, calls, mismatches);
        return result;
    }

    private static <T> T checkedProxy(Class<T> contract, T target, ClassLoader expected,
                                      AtomicInteger calls, AtomicInteger mismatches) {
        return contract.cast(Proxy.newProxyInstance(expected, new Class<?>[]{contract},
                (proxy, method, arguments) -> invokeChecked(target, method, arguments,
                        expected, calls, mismatches)));
    }

    private static JdbcExecutor executor(JdbcProfile profile, FakeJdbc.State state) {
        return new JdbcExecutor((tenant, name) -> tenant.equals(profile.tenant()) && name.equals(profile.name())
                ? Optional.of(profile) : Optional.empty(), ignored -> new FakeJdbc(state), new JdbcRuntime(System::nanoTime));
    }
}
