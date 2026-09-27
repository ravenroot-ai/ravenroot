package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL owned by the SQLite saga aggregate and its fenced application-command outbox. */
final class SqliteSagaStorage {
    private SqliteSagaStorage() { }

    static void write(Connection connection, ExecutionKey key, ExecutionBatch batch, Instant now,
                      SagaOutboxCapacity capacity) throws SQLException {
        for (SagaWrite write : batch.sagaWrites()) writeSnapshot(connection, key, write);
        for (SagaCommandIntent intent : batch.sagaCommands()) writeIntent(connection, key, intent, now, capacity);
        for (UUID messageId : batch.sagaCommandsToRearm()) rearm(connection, key, messageId, batch, now);
    }

    private static void rearm(Connection connection, ExecutionKey key, UUID messageId,
                              ExecutionBatch batch, Instant now) throws SQLException {
        try (var select = connection.prepareStatement("SELECT saga_id,status,broker_accepted_at_epoch_second FROM saga_command_outbox WHERE tenant_id=? AND process_instance_id=? AND message_id=?")) {
            bindKey(select, key); select.setString(3, messageId.toString());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next() || !SagaOutboxStatus.EXHAUSTED.name().equals(rows.getString(2))) {
                    throw invalid("only an exhausted command in this execution may be rearmed");
                }
                UUID sagaId = StoredUuid.required(rows, "saga_command_outbox", "saga_id", key.tenantId());
                if (batch.sagaWrites().stream().noneMatch(write -> write.snapshot().sagaId().equals(sagaId))) {
                    throw invalid("saga command rearm requires an atomic owning saga write");
                }
                String status = rows.getObject(3) == null ? SagaOutboxStatus.PENDING.name()
                        : SagaOutboxStatus.BROKER_ACCEPTED.name();
                try (var update = connection.prepareStatement("UPDATE saga_command_outbox SET status=?,attempts=0,owner=NULL,fencing_token=fencing_token+1,lease_expires_at_epoch_second=NULL,lease_expires_at_nano=NULL,next_attempt_at_epoch_second=?,next_attempt_at_nano=?,last_failure='operator rearmed bounded delivery' WHERE tenant_id=? AND process_instance_id=? AND message_id=? AND status='EXHAUSTED'")) {
                    update.setString(1, status); StoredInstant.bindValue(update, 2, now);
                    update.setString(4, key.tenantId()); update.setString(5, key.processInstanceId().toString());
                    update.setString(6, messageId.toString());
                    if (update.executeUpdate() != 1) throw invalid("concurrent saga command rearm");
                }
            }
        }
    }

    private static void writeSnapshot(Connection connection, ExecutionKey key, SagaWrite write) throws SQLException {
        SagaSnapshot snapshot = write.snapshot();
        if (!snapshot.key().equals(key)) throw invalid("saga snapshot scope differs from its execution batch");
        try (var select = connection.prepareStatement("SELECT revision, snapshot FROM saga_instance WHERE tenant_id=? AND process_instance_id=? AND saga_id=?")) {
            bindKey(select, key); select.setString(3, snapshot.sagaId().toString());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    if (write.expectedRevision() != 0 || snapshot.revision() != 1) throw conflict(key, "saga create revision mismatch");
                    try (var insert = connection.prepareStatement("INSERT INTO saga_instance (tenant_id,process_instance_id,saga_id,traversal_id,revision,disposition,graph_completed,snapshot) VALUES (?,?,?,?,?,?,?,?)")) {
                        bindKey(insert, key); insert.setString(3, snapshot.sagaId().toString()); insert.setString(4, snapshot.traversalId().toString()); insert.setLong(5, snapshot.revision()); insert.setString(6, snapshot.disposition().name()); insert.setInt(7, snapshot.graphCompleted() ? 1 : 0); insert.setBytes(8, SagaSnapshotCodec.encode(snapshot)); insert.executeUpdate();
                    }
                    return;
                }
                long revision = rows.getLong(1); SagaSnapshot current = SagaSnapshotCodec.decode(rows.getBytes(2));
                if (revision != write.expectedRevision() || snapshot.revision() != revision + 1) throw conflict(key, "saga revision mismatch");
                if (!current.definition().equals(snapshot.definition())) throw invalid("a persisted saga definition is immutable");
                if (!current.traversalId().equals(snapshot.traversalId())) throw invalid("a persisted saga traversal is immutable");
                try (var update = connection.prepareStatement("UPDATE saga_instance SET revision=?, disposition=?, graph_completed=?, snapshot=? WHERE tenant_id=? AND process_instance_id=? AND saga_id=? AND revision=?")) {
                    update.setLong(1, snapshot.revision()); update.setString(2, snapshot.disposition().name()); update.setInt(3, snapshot.graphCompleted() ? 1 : 0); update.setBytes(4, SagaSnapshotCodec.encode(snapshot)); update.setString(5, key.tenantId()); update.setString(6, key.processInstanceId().toString()); update.setString(7, snapshot.sagaId().toString()); update.setLong(8, revision);
                    if (update.executeUpdate() != 1) throw conflict(key, "concurrent saga update");
                }
            }
        }
    }

    private static void writeIntent(Connection connection, ExecutionKey key, SagaCommandIntent intent,
                                    Instant now, SagaOutboxCapacity capacity) throws SQLException {
        byte[] encoded = SagaCommandCodec.encode(intent);
        try (var existing = connection.prepareStatement("SELECT message_id, identity_fingerprint, intent FROM saga_command_outbox WHERE tenant_id=? AND (message_id=? OR operation_id=?)")) {
            existing.setString(1, key.tenantId()); existing.setString(2, intent.messageId().toString()); existing.setString(3, intent.operationId());
            try (ResultSet rows = existing.executeQuery()) {
                if (rows.next()) {
                    if (!rows.getString(1).equals(intent.messageId().toString()) || !rows.getString(2).equals(intent.identityFingerprint()) || !java.util.Arrays.equals(rows.getBytes(3), encoded)) throw invalid("saga command identity was reused with different content");
                    return;
                }
            }
        }
        ensureCapacity(connection, key.tenantId(), encoded.length, capacity);
        try (var insert = connection.prepareStatement("INSERT INTO saga_command_outbox (tenant_id,process_instance_id,message_id,saga_id,operation_id,identity_fingerprint,intent,status,attempts,owner,fencing_token,lease_expires_at_epoch_second,lease_expires_at_nano,next_attempt_at_epoch_second,next_attempt_at_nano,created_at_epoch_second,created_at_nano,broker_accepted_at_epoch_second,broker_accepted_at_nano,business_completed_at_epoch_second,business_completed_at_nano,last_failure) VALUES (?,?,?,?,?,?,?,?,0,NULL,0,NULL,NULL,?,?,?,?,NULL,NULL,NULL,NULL,'')")) {
            insert.setString(1,key.tenantId()); insert.setString(2,key.processInstanceId().toString()); insert.setString(3,intent.messageId().toString()); insert.setString(4,intent.sagaId().toString()); insert.setString(5,intent.operationId()); insert.setString(6,intent.identityFingerprint()); insert.setBytes(7,encoded); insert.setString(8,SagaOutboxStatus.PENDING.name()); StoredInstant.bindValue(insert,9,intent.notBefore()); StoredInstant.bindValue(insert,11,now); insert.executeUpdate();
        }
    }

    private static void ensureCapacity(Connection connection, String tenant, int additionalBytes,
                                       SagaOutboxCapacity capacity) throws SQLException {
        try (var select = connection.prepareStatement("SELECT COUNT(*),COALESCE(SUM(LENGTH(intent)),0) FROM saga_command_outbox WHERE tenant_id=? AND status NOT IN ('BUSINESS_COMPLETED','EXHAUSTED')")) {
            select.setString(1, tenant);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                if (rows.getLong(1) + 1 > capacity.maximumOutstandingCommands()
                        || rows.getLong(2) + additionalBytes > capacity.maximumOutstandingBytes()) {
                    throw invalid("saga outbox tenant capacity exceeded");
                }
            }
        }
    }

    static Optional<SagaSnapshot> load(Connection connection, ExecutionKey key, UUID sagaId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT snapshot FROM saga_instance WHERE tenant_id=? AND process_instance_id=? AND saga_id=?")) {
            bindKey(statement,key); statement.setString(3,sagaId.toString());
            try (ResultSet rows=statement.executeQuery()) { return rows.next()?Optional.of(SagaSnapshotCodec.decode(rows.getBytes(1))):Optional.empty(); }
        }
    }

    static List<SagaSnapshot> list(Connection connection, ExecutionKey key) throws SQLException {
        try (var statement=connection.prepareStatement("SELECT snapshot FROM saga_instance WHERE tenant_id=? AND process_instance_id=? ORDER BY saga_id")) {
            bindKey(statement,key); try(ResultSet rows=statement.executeQuery()){ var result=new ArrayList<SagaSnapshot>(); while(rows.next()) result.add(SagaSnapshotCodec.decode(rows.getBytes(1))); return List.copyOf(result); }
        }
    }

    static List<SagaSnapshot> completionCandidates(Connection connection, String tenant, int limit, Instant now)
            throws SQLException {
        var result = new ArrayList<SagaSnapshot>();
        try (var statement = connection.prepareStatement(
                "SELECT s.snapshot FROM saga_instance s JOIN process_instance p ON p.tenant_id=s.tenant_id AND p.process_instance_id=s.process_instance_id JOIN traversal t ON t.tenant_id=s.tenant_id AND t.process_instance_id=s.process_instance_id AND t.traversal_id=s.traversal_id LEFT JOIN lease l ON l.tenant_id=s.tenant_id AND l.process_instance_id=s.process_instance_id WHERE s.tenant_id=? AND p.status NOT IN ('COMPLETED','FAILED') AND t.status NOT IN ('COMPLETED','FAILED') AND s.graph_completed=1 AND s.disposition IN ('SUCCEEDED','COMPENSATED') AND (l.process_instance_id IS NULL OR l.expires_at_epoch_second<? OR (l.expires_at_epoch_second=? AND l.expires_at_nano<=?)) ORDER BY s.process_instance_id,s.saga_id LIMIT ?")) {
            statement.setString(1, tenant); statement.setLong(2, now.getEpochSecond());
            statement.setLong(3, now.getEpochSecond()); statement.setInt(4, now.getNano());
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(SagaSnapshotCodec.decode(rows.getBytes(1)));
                }
            }
        }
        return List.copyOf(result);
    }

    static List<SagaSnapshot> recoveryCandidates(Connection connection, String tenant, int limit, Instant now)
            throws SQLException {
        var result = new ArrayList<SagaSnapshot>();
        try (var statement = connection.prepareStatement(
                "SELECT s.snapshot FROM saga_instance s LEFT JOIN lease l ON l.tenant_id=s.tenant_id AND l.process_instance_id=s.process_instance_id WHERE s.tenant_id=? AND s.disposition<>'COMPENSATED' AND (s.disposition<>'SUCCEEDED' OR s.graph_completed=0) AND (l.process_instance_id IS NULL OR l.expires_at_epoch_second<? OR (l.expires_at_epoch_second=? AND l.expires_at_nano<=?)) ORDER BY s.process_instance_id,s.saga_id LIMIT ?")) {
            statement.setString(1, tenant); statement.setLong(2, now.getEpochSecond());
            statement.setLong(3, now.getEpochSecond()); statement.setInt(4, now.getNano());
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(SagaSnapshotCodec.decode(rows.getBytes(1)));
            }
        }
        return List.copyOf(result);
    }

    static List<SagaOutboxRecord> listCommands(Connection connection, ExecutionKey key) throws SQLException {
        var result = new ArrayList<SagaOutboxRecord>();
        try (var statement = connection.prepareStatement("SELECT message_id FROM saga_command_outbox WHERE tenant_id=? AND process_instance_id=? ORDER BY message_id")) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(connection, key.tenantId(),
                        StoredUuid.required(rows, "saga_command_outbox", "message_id", key.tenantId())));
            }
        }
        return List.copyOf(result);
    }

    static List<SagaOutboxRecord> claim(Connection connection,String tenant,String worker,int limit,Duration ttl,Instant now)throws SQLException{
        var answer=new ArrayList<SagaOutboxRecord>();
        try(var select=connection.prepareStatement("SELECT * FROM saga_command_outbox WHERE tenant_id=? AND status NOT IN ('BUSINESS_COMPLETED','EXHAUSTED') AND (status<>'CLAIMED' OR lease_expires_at_epoch_second<? OR (lease_expires_at_epoch_second=? AND lease_expires_at_nano<=?)) AND (next_attempt_at_epoch_second<? OR (next_attempt_at_epoch_second=? AND next_attempt_at_nano<=?)) ORDER BY next_attempt_at_epoch_second,next_attempt_at_nano,message_id LIMIT ?")){
            select.setString(1,tenant); select.setLong(2,now.getEpochSecond()); select.setLong(3,now.getEpochSecond()); select.setInt(4,now.getNano()); select.setLong(5,now.getEpochSecond()); select.setLong(6,now.getEpochSecond()); select.setInt(7,now.getNano()); select.setInt(8,limit);
            try(ResultSet rows=select.executeQuery()){while(rows.next()){
                UUID message=StoredUuid.required(rows,"saga_command_outbox","message_id",tenant); SagaOutboxRecord previous=read(connection,tenant,message);
                if(previous.attempts()>=previous.intent().maxAttempts()){exhaust(connection,previous,"delivery attempts exhausted after recovery",now);continue;}
                long fence=rows.getLong("fencing_token")+1; Instant expiry=now.plus(ttl);
                try(var update=connection.prepareStatement("UPDATE saga_command_outbox SET status='CLAIMED',owner=?,fencing_token=?,lease_expires_at_epoch_second=?,lease_expires_at_nano=?,attempts=attempts+1 WHERE tenant_id=? AND message_id=?")){
                    update.setString(1,worker);update.setLong(2,fence);StoredInstant.bindValue(update,3,expiry);update.setString(5,tenant);update.setString(6,message.toString());update.executeUpdate();
                }
                answer.add(read(connection,tenant,message));
            }}
        }
        return List.copyOf(answer);
    }

    static SagaOutboxRecord settle(Connection connection,String tenant,UUID message,String worker,long fence,SagaOutboxSettlement settlement,Instant now)throws SQLException{
        SagaOutboxRecord current=read(connection,tenant,message);
        if(current.status()!=SagaOutboxStatus.CLAIMED||!worker.equals(current.owner())||current.fencingToken()!=fence) throw invalid("stale saga outbox settlement");
        String status; Instant next=current.nextAttemptAt(),accepted=current.brokerAcceptedAt(),completed=current.businessCompletedAt(); String failure="";
        if(settlement instanceof SagaOutboxSettlement.BrokerAccepted){status="BROKER_ACCEPTED";accepted=now;next=now.plusSeconds(1);}
        else if(settlement instanceof SagaOutboxSettlement.BusinessCompleted){status="BUSINESS_COMPLETED";completed=now;SagaSnapshot saga=load(connection,current.key(),current.intent().sagaId()).orElseThrow(()->invalid("saga disappeared"));writeSnapshot(connection,current.key(),new SagaWrite(UUID.randomUUID(),saga.revision(),SagaCommandCompletion.fold(saga,current.intent(),now)));}
        else if(settlement instanceof SagaOutboxSettlement.Retry retry){status=current.attempts()>=current.intent().maxAttempts()?"EXHAUSTED":current.brokerAcceptedAt()==null?"PENDING":"BROKER_ACCEPTED";next=now.plus(retry.delay());failure=retry.safeReason();if("EXHAUSTED".equals(status))foldExhausted(connection,current,failure,now);}
        else {status="EXHAUSTED";failure=((SagaOutboxSettlement.Exhausted)settlement).safeReason();foldExhausted(connection,current,failure,now);}
        try(var update=connection.prepareStatement("UPDATE saga_command_outbox SET status=?,owner=NULL,lease_expires_at_epoch_second=NULL,lease_expires_at_nano=NULL,next_attempt_at_epoch_second=?,next_attempt_at_nano=?,broker_accepted_at_epoch_second=?,broker_accepted_at_nano=?,business_completed_at_epoch_second=?,business_completed_at_nano=?,last_failure=? WHERE tenant_id=? AND message_id=? AND owner=? AND fencing_token=?")){
            update.setString(1,status);StoredInstant.bindValue(update,2,next);bindNullable(update,4,accepted);bindNullable(update,6,completed);update.setString(8,failure);update.setString(9,tenant);update.setString(10,message.toString());update.setString(11,worker);update.setLong(12,fence);if(update.executeUpdate()!=1)throw invalid("stale saga outbox settlement");
        }
        return read(connection,tenant,message);
    }

    private static SagaOutboxRecord read(Connection connection,String tenant,UUID message)throws SQLException{
        try(var statement=connection.prepareStatement("SELECT * FROM saga_command_outbox WHERE tenant_id=? AND message_id=?")){statement.setString(1,tenant);statement.setString(2,message.toString());try(ResultSet r=statement.executeQuery()){if(!r.next())throw invalid("unknown saga outbox message");var key=new ExecutionKey(tenant,StoredUuid.required(r,"saga_command_outbox","process_instance_id",tenant));return new SagaOutboxRecord(key,SagaCommandCodec.decode(r.getBytes("intent")),SagaOutboxStatus.valueOf(r.getString("status")),r.getInt("attempts"),r.getString("owner"),r.getLong("fencing_token"),instant(r,"lease_expires_at"),StoredInstant.read(r,"next_attempt_at"),r.getString("last_failure"),StoredInstant.read(r,"created_at"),instant(r,"broker_accepted_at"),instant(r,"business_completed_at"));}}
    }
    private static void exhaust(Connection connection,SagaOutboxRecord record,String reason,Instant now)throws SQLException{foldExhausted(connection,record,reason,now);try(var update=connection.prepareStatement("UPDATE saga_command_outbox SET status='EXHAUSTED',owner=NULL,lease_expires_at_epoch_second=NULL,lease_expires_at_nano=NULL,last_failure=? WHERE tenant_id=? AND message_id=?")){update.setString(1,reason);update.setString(2,record.key().tenantId());update.setString(3,record.intent().messageId().toString());update.executeUpdate();}}
    private static void foldExhausted(Connection connection,SagaOutboxRecord record,String reason,Instant now)throws SQLException{SagaSnapshot saga=load(connection,record.key(),record.intent().sagaId()).orElseThrow(()->invalid("saga disappeared"));writeSnapshot(connection,record.key(),new SagaWrite(UUID.randomUUID(),saga.revision(),SagaCommandCompletion.exhausted(saga,record.intent(),now,reason)));}
    private static Instant instant(ResultSet r,String prefix)throws SQLException{return r.getObject(prefix+"_epoch_second")==null?null:StoredInstant.read(r,prefix);}
    private static void bindNullable(java.sql.PreparedStatement s,int index,Instant value)throws SQLException{if(value==null){s.setObject(index,null);s.setObject(index+1,null);}else StoredInstant.bindValue(s,index,value);}
    private static void bindKey(java.sql.PreparedStatement s,ExecutionKey key)throws SQLException{s.setString(1,key.tenantId());s.setString(2,key.processInstanceId().toString());}
    private static ExecutionStoreException invalid(String reason){return new ExecutionStoreException(ExecutionStoreFailure.invalid(reason));}
    private static ExecutionStoreException conflict(ExecutionKey key,String reason){return new ExecutionStoreException(new ExecutionStoreFailure.ConcurrencyConflict(key,RevisionExpectation.any(),-1));}
}
