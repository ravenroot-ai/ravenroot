package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Bounded, authority-first invocation-scoped Kafka producer. */
public final class KafkaProduceNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "kafka.produce";
    private static final String VERSION = "kafka.produce.v1";
    private static final Set<String> CONFIG = Set.of("clusterProfile", "topic", "timeoutMs", "maxConcurrency", "maxRecordBytes", "correlationId");
    private static final Set<String> PAYLOAD = Set.of("version", "topic", "keyText", "keyJson", "keyBase64",
            "valueText", "valueJson", "valueBase64", "partition", "timestamp", "headers", "correlationId");
    private static final PayloadLimits JSON_LIMITS = new PayloadLimits(1_048_576, 16, 128, 512, 1_048_576, 256);

    private final CredentialResolver credentials;
    private final KafkaProfileResolver profiles;
    private final KafkaProtocol protocol;
    private final KafkaRuntimeControls controls;
    private final LongSupplier ticker;
    private final ReservedNetworkPolicy destinationPolicy;

    public KafkaProduceNodeBehavior() { this(new EnvironmentKafkaCredentialResolver(), new EnvironmentKafkaProfileResolver()); }
    public KafkaProduceNodeBehavior(CredentialResolver credentials, KafkaProfileResolver profiles) {
        this(credentials, profiles, new ApacheKafkaProtocol(), KafkaRuntimeControls.PRODUCTION, System::nanoTime);
    }
    KafkaProduceNodeBehavior(CredentialResolver credentials, KafkaProfileResolver profiles, KafkaProtocol protocol,
                             KafkaRuntimeControls controls, LongSupplier ticker) {
        this(credentials, profiles, protocol, controls, ticker,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }
    KafkaProduceNodeBehavior(CredentialResolver credentials, KafkaProfileResolver profiles, KafkaProtocol protocol,
                             KafkaRuntimeControls controls, LongSupplier ticker,
                             ReservedNetworkPolicy destinationPolicy) {
        this.credentials = Objects.requireNonNull(credentials); this.profiles = Objects.requireNonNull(profiles);
        this.protocol = Objects.requireNonNull(protocol); this.controls = Objects.requireNonNull(controls);
        this.ticker = Objects.requireNonNull(ticker);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override public NodeTypeDescriptor descriptor() {
        var p = new ArrayList<NodePropertyDescriptor>();
        p.add(NodePropertyDescriptor.required("clusterProfile", "Cluster profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; endpoints and credentials never come from GraphML."));
        p.add(optional("topic", "Topic", NodePropertyType.STRING, "Exact topic authorized by the profile."));
        p.add(optional("timeoutMs", "Deadline (ms)", NodePropertyType.INTEGER, "May only tighten the profile deadline."));
        p.add(optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER, "May only tighten profile admission."));
        p.add(optional("maxRecordBytes", "Record bytes", NodePropertyType.INTEGER, "May only tighten the serialized record ceiling."));
        p.add(optional("correlationId", "Correlation id", NodePropertyType.STRING, "Safe result correlation metadata."));
        // PERS-04 (ADR 0022). Declared as an author assertion, on the same footing as
        // amqp.publish: the consumer, not this node, is what can make a repeat harmless.
        //
        // Read the word "idempotent" in this type's own description carefully before touching this.
        // It refers to ProducerConfig.ENABLE_IDEMPOTENCE, which deduplicates the CLIENT'S OWN retries
        // within one producer session, by (producer id, epoch, sequence). It says nothing about the
        // case here. A recovery re-dispatch is a fresh send() -- a new sequence number in the same
        // session, or an entirely new producer id after a restart -- so the broker sees a distinct
        // record and appends it. Concluding "enable.idempotence is on, therefore repeatable" is the
        // one wrong inference this comment exists to block.
        //
        // What does make it safe is a consumer that discards a record key or header it has already
        // processed, which is domain knowledge the graph author has and the node does not. No
        // default: an instance that says nothing parks.
        p.add(ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(
                "Whether producing this record again after a crash of unknown outcome is safe. "
                        + "Producer idempotence does not cover this; say repeatable only where the "
                        + "consumer discards a record it has already processed."));
        return new NodeTypeDescriptor(BEHAVIOR, "Produce Kafka record", "Kafka", "Produces one bounded idempotent Kafka record.",
                "actor", false, List.copyOf(p), Set.of("network", "credential-reference", "side-effect"));
    }
    private static NodePropertyDescriptor optional(String name, String label, NodePropertyType type, String description) {
        return NodePropertyDescriptor.optional(name, label, type, description, "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        ConcurrentHashMap<String, KafkaRuntimeControls.Gate> actionGates = new ConcurrentHashMap<>();
        return message -> {
            KafkaRuntimeControls.Admission admission = null;
            final Settings settings; final Input input;
            try {
                settings = Settings.from(configuration, profiles, destinationPolicy, message.tenantId());
                input = Input.from(message.payload(), settings);
                admission = controls.acquire(message.tenantId(), settings.profile, settings.maxConcurrency, actionGates);
                if (!admission.acquired()) return CompletableFuture.completedFuture(result("TEMPORARY_FAILURE", "LOCAL_CAPACITY", input, 0, null));
                if (!controls.rates.allow(message.tenantId(), settings.profile.name(), settings.profile.maxPerSecond())) {
                    admission.release(); return CompletableFuture.completedFuture(result("RATE_LIMITED", "LOCAL_RATE_LIMIT", input, 0, null));
                }
            } catch (Refusal refusal) {
                if (admission != null) admission.release();
                return CompletableFuture.completedFuture(result(refusal.status, refusal.reason, null, 0, null));
            } catch (RuntimeException invalid) {
                if (admission != null) admission.release();
                return CompletableFuture.completedFuture(result("REJECTED", "INVALID_REQUEST", null, 0, null));
            }
            KafkaRuntimeControls.Admission held = admission;
            try {
                return CompletableFuture.supplyAsync(() -> execute(settings, input, held), controls.executor);
            } catch (RuntimeException rejected) {
                held.release(); return CompletableFuture.completedFuture(result("TEMPORARY_FAILURE", "LOCAL_CAPACITY", input, 0, null));
            }
        };
    }

    private NodeResult execute(Settings settings, Input input, KafkaRuntimeControls.Admission admission) {
        long deadline = deadline(ticker.getAsLong(), settings.timeoutMs);
        SecretValue secret = null; char[] password = null; CloseHandoff cleanup = null;
        try {
            Optional<SecretValue> resolved;
            try { resolved = credentials.resolve(settings.profile.credentialRef()); }
            catch (RuntimeException unavailable) { return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", input, 0, null); }
            if (resolved == null || resolved.isEmpty()) return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", input, 0, null);
            secret = resolved.get(); password = secret.copy();
            if (password.length == 0) return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", input, 0, null);
            KafkaProtocol.Client client;
            try { client = createClient(settings.profile, password, deadline); }
            catch (DeadlineExceeded timeout) { return result("TEMPORARY_FAILURE", "CLIENT_TIMEOUT", input, 1, null); }
            catch (KafkaProtocol.ClientFailure failure) { return result(failure.kind() == KafkaProtocol.FailureKind.PERMANENT
                    ? "PERMANENT_FAILURE" : "TEMPORARY_FAILURE", failure.kind() == KafkaProtocol.FailureKind.PERMANENT
                    ? "CLIENT_REJECTED" : "CLIENT_UNAVAILABLE", input, 1, null); }
            cleanup = new CloseHandoff(client, deadline, admission);
            Tracker tracker = new Tracker(settings.profile.allowAutoCreate());
            int budget = remainingMillis(deadline);
            if (budget == 0) return result("TEMPORARY_FAILURE", "CLIENT_TIMEOUT", input, 1, null);
            sendWithinDeadline(client, input.record(), tracker, budget, deadline, cleanup);
            Outcome outcome = tracker.current();
            if (outcome == null) outcome = tracker.await(remainingNanos(deadline));
            return result(outcome.status, outcome.reason, input, 1, outcome.metadata);
        } finally {
            if (cleanup == null) admission.release(); else cleanup.startAndAwait();
            if (password != null) Arrays.fill(password, '\0');
            if (secret != null) try { secret.close(); } catch (RuntimeException ignored) { }
        }
    }

    private KafkaProtocol.Client createClient(KafkaProfile profile, char[] password, long deadline)
            throws KafkaProtocol.ClientFailure, DeadlineExceeded {
        int budget = remainingMillis(deadline); if (budget == 0) throw new DeadlineExceeded();
        char[] copy = password.clone(); KafkaProtocol.CreateAttempt attempt;
        try { attempt = protocol.beginCreate(profile, copy, budget); } finally { Arrays.fill(copy, '\0'); }
        FutureTask<Void> task = new FutureTask<>(() -> { attempt.establish(); return null; });
        Thread worker = Thread.ofVirtual().name("ravenroot-kafka-create").start(task);
        try { task.get(remainingNanos(deadline), TimeUnit.NANOSECONDS); }
        catch (TimeoutException | InterruptedException timeout) { if (timeout instanceof InterruptedException) Thread.currentThread().interrupt(); worker.interrupt(); attempt.cancel(); throw new DeadlineExceeded(); }
        catch (ExecutionException failed) {
            attempt.cancel(); Throwable cause = failed.getCause();
            if (cause instanceof KafkaProtocol.ClientFailure classified) throw classified;
            throw new KafkaProtocol.ClientFailure(KafkaProtocol.FailureKind.TEMPORARY);
        }
        if (remainingNanos(deadline) == 0) { attempt.cancel(); throw new DeadlineExceeded(); }
        return attempt.claim();
    }

    private void sendWithinDeadline(KafkaProtocol.Client client, KafkaProtocol.Record record, Tracker tracker,
                                    int budget, long deadline, CloseHandoff cleanup) {
        FutureTask<Void> task = new FutureTask<>(() -> { client.send(record, tracker, budget); client.flush(); return null; });
        Thread worker = Thread.ofVirtual().name("ravenroot-kafka-send").start(task);
        try { task.get(remainingNanos(deadline), TimeUnit.NANOSECONDS); }
        catch (TimeoutException | InterruptedException timeout) {
            if (timeout instanceof InterruptedException) Thread.currentThread().interrupt();
            tracker.ambiguous("DELIVERY_STATE_UNKNOWN"); cleanup.start(); worker.interrupt();
        } catch (ExecutionException failed) { tracker.failed(failed.getCause()); }
    }

    private final class CloseHandoff {
        private final KafkaProtocol.Client client;
        private final long deadline;
        private final KafkaRuntimeControls.Admission admission;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean acknowledged = new AtomicBoolean();
        private final CountDownLatch acknowledgment = new CountDownLatch(1);

        private CloseHandoff(KafkaProtocol.Client client, long deadline,
                             KafkaRuntimeControls.Admission admission) {
            this.client = client; this.deadline = deadline; this.admission = admission;
        }

        private void start() {
            if (!started.compareAndSet(false, true)) return;
            controls.trackCleanup(this);
            try { controls.cleanupExecutor.execute(this::launch); }
            catch (RuntimeException rejected) {
                try { Thread.ofVirtual().name("ravenroot-kafka-cleanup-fallback").start(this::launch); }
                catch (RuntimeException unavailable) { /* Remain tracked and admitted: fail closed. */ }
            }
        }

        private void startAndAwait() {
            start(); long remaining = remainingNanos(deadline); if (remaining == 0) return;
            try { acknowledgment.await(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        private void launch() {
            FutureTask<Void> close = new FutureTask<>(() -> {
                try { client.close(remainingMillis(deadline), this::acknowledge); }
                finally { acknowledge(); controls.finishCleanup(this); }
                return null;
            });
            final Thread worker;
            try { worker = Thread.ofVirtual().name("ravenroot-kafka-close").start(close); }
            catch (RuntimeException unavailable) { return; }
            long remaining = remainingNanos(deadline);
            if (remaining == 0) { worker.interrupt(); return; }
            Thread.ofVirtual().name("ravenroot-kafka-close-deadline").start(() -> {
                try { close.get(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); worker.interrupt(); }
                catch (TimeoutException timeout) { worker.interrupt(); }
                catch (ExecutionException ignored) { }
            });
        }

        private void acknowledge() {
            if (!acknowledged.compareAndSet(false, true)) return;
            admission.release(); acknowledgment.countDown();
        }
    }

    private static NodeResult result(String status, String reason, Input input, int attempt, KafkaProtocol.Metadata metadata) {
        Map<String,Object> out = new LinkedHashMap<>(); out.put("version", VERSION); out.put("status", status);
        out.put("reason", reason); out.put("attemptCount", attempt);
        if (input != null) { out.put("topic", input.topic); out.put("keyBytes", input.key == null ? 0 : input.key.length);
            out.put("valueBytes", input.value.length); if (KafkaText.safeCorrelation(input.correlationId)) out.put("correlationId", input.correlationId); }
        if (metadata != null) { out.put("partition", metadata.partition()); out.put("offset", metadata.offset());
            out.put("brokerTimestamp", metadata.timestamp()); }
        return NodeResult.continueWith(out);
    }

    private record Settings(KafkaProfile profile, String topic, int timeoutMs, int maxConcurrency, int maxRecordBytes, String correlationId) {
        static Settings from(NodeConfiguration c, KafkaProfileResolver resolver,
                             ReservedNetworkPolicy destinationPolicy, String tenant) {
            for (String key : c.properties().keySet()) if (!CONFIG.contains(key)) throw new Refusal("REJECTED", "UNKNOWN_GRAPH_PROPERTY");
            String name = c.property("clusterProfile").orElseThrow(() -> new Refusal("REJECTED", "CLUSTER_PROFILE_REQUIRED"));
            KafkaProfile p;
            try { p = resolver.resolve(tenant, name).orElse(null); } catch (RuntimeException failure) { p = null; }
            if (p == null) throw new Refusal("PERMANENT_FAILURE", "CLUSTER_PROFILE_UNAVAILABLE");
            if (!tenant.equals(p.tenant()) || !name.equals(p.name())) throw new Refusal("REJECTED", "CLUSTER_PROFILE_FORBIDDEN");
            try { EnvironmentKafkaProfileResolver.requireDestinations(
                    String.join(",", p.bootstrapServers()), destinationPolicy); }
            catch (SecurityException refused) { throw new Refusal("PERMANENT_FAILURE", "CLUSTER_PROFILE_UNAVAILABLE"); }
            String topic = c.property("topic", p.defaultTopic()); if (!p.allowsTopic(topic)) throw new Refusal("REJECTED", "TOPIC_FORBIDDEN");
            return new Settings(p, topic, tighten(c,"timeoutMs",p.timeoutMs(),100), tighten(c,"maxConcurrency",p.maxConcurrency(),1),
                    tighten(c,"maxRecordBytes",p.maxRecordBytes(),1), optional(c,"correlationId",128));
        }
        private static int tighten(NodeConfiguration c,String name,int ceiling,int minimum) {
            String raw=c.property(name,""); if(raw.isEmpty()) return ceiling;
            try { int value=Integer.parseInt(raw); if(value<minimum||value>ceiling) throw new NumberFormatException(); return value; }
            catch(NumberFormatException invalid){ throw new Refusal("REJECTED","INVALID_TIGHTENING"); }
        }
        private static String optional(NodeConfiguration c,String name,int max) {
            if(!c.properties().containsKey(name)) return null; Object raw=c.properties().get(name);
            if(!(raw instanceof String value)||!KafkaText.safeCorrelation(value)) throw new Refusal("REJECTED","INVALID_GRAPH_PROPERTY");
            return value;
        }
    }

    private record Input(String topic, byte[] key, byte[] value, Integer partition, Long timestamp,
                         Map<String,byte[]> headers, String correlationId) {
        static Input from(Object raw, Settings s) {
            if(!(raw instanceof Map<?,?> map)) throw new Refusal("REJECTED","INVALID_PAYLOAD");
            for(Object key:map.keySet()) if(!(key instanceof String name)||!PAYLOAD.contains(name)) throw new Refusal("REJECTED","UNKNOWN_PAYLOAD_FIELD");
            if(!VERSION.equals(map.get("version"))) throw new Refusal("REJECTED","UNSUPPORTED_PAYLOAD_VERSION");
            String topic=map.containsKey("topic")?string(map.get("topic"),249):s.topic; if(!s.profile.allowsTopic(topic)) throw new Refusal("REJECTED","TOPIC_FORBIDDEN");
            byte[] key=variant(map,"key",false,s.maxRecordBytes); byte[] value=variant(map,"value",true,s.maxRecordBytes);
            Integer partition=null; if(map.containsKey("partition")){ if(!s.profile.allowPartition()) throw new Refusal("REJECTED","PARTITION_FORBIDDEN"); partition=integer(map.get("partition")); if(partition<0||partition>s.profile.maxPartition()) throw new Refusal("REJECTED","PARTITION_FORBIDDEN"); }
            Long timestamp=null; if(map.containsKey("timestamp")){ if(!s.profile.allowTimestamp()) throw new Refusal("REJECTED","TIMESTAMP_FORBIDDEN"); timestamp=longInteger(map.get("timestamp")); if(timestamp<0) throw new Refusal("REJECTED","INVALID_PAYLOAD"); }
            Map<String,byte[]> headers=new LinkedHashMap<>(); int size=(key==null?0:key.length)+value.length;
            if(map.containsKey("headers")){ if(!(map.get("headers") instanceof Map<?,?> h)||h.size()>32) throw new Refusal("REJECTED","INVALID_HEADER");
                for(var e:h.entrySet()){ if(!(e.getKey() instanceof String name)||!(e.getValue() instanceof String text)) throw new Refusal("REJECTED","INVALID_HEADER"); byte[] nameBytes=utf8(name,"INVALID_HEADER"); if(!s.profile.allowsHeader(name))throw new Refusal("REJECTED","INVALID_HEADER"); byte[] bytes=utf8(text,"INVALID_HEADER"); if(bytes.length>1024) throw new Refusal("REJECTED","INVALID_HEADER"); headers.put(name,bytes); size+=nameBytes.length+bytes.length; }}
            if(size>s.maxRecordBytes) throw new Refusal("REJECTED","RECORD_TOO_LARGE");
            String correlation=s.correlationId; if(map.containsKey("correlationId")) { correlation=map.get("correlationId")==null?null:(map.get("correlationId") instanceof String text?text:null); if(correlation!=null&&!KafkaText.safeCorrelation(correlation))throw new Refusal("REJECTED","INVALID_PAYLOAD"); }
            return new Input(topic,key,value,partition,timestamp,Map.copyOf(headers),correlation);
        }
        KafkaProtocol.Record record(){ return new KafkaProtocol.Record(topic,partition,timestamp,key,value,headers); }
        @Override public byte[] key(){return key==null?null:key.clone();} @Override public byte[] value(){return value.clone();}
        private static byte[] variant(Map<?,?> map,String prefix,boolean required,int max){ int count=0; for(String suffix:Set.of("Text","Json","Base64")) if(map.containsKey(prefix+suffix)) count++; if(required&&count!=1||!required&&count>1) throw new Refusal("REJECTED",required?"VALUE_VARIANT_REQUIRED":"INVALID_KEY_VARIANT"); if(count==0)return null;
            byte[] bytes; if(map.containsKey(prefix+"Text")) bytes=utf8(string(map.get(prefix+"Text"),max),"INVALID_PAYLOAD"); else if(map.containsKey(prefix+"Json")){ try{Object source=KafkaText.validatedJsonSource(map.get(prefix+"Json"),JSON_LIMITS);bytes=KafkaText.utf8(PayloadJson.write(PayloadValue.fromJava(source,JSON_LIMITS)));}catch(RuntimeException invalid){throw new Refusal("REJECTED","INVALID_JSON");}} else try{bytes=Base64.getDecoder().decode(string(map.get(prefix+"Base64"),max*2+4));}catch(IllegalArgumentException invalid){throw new Refusal("REJECTED","INVALID_BASE64");} if(bytes.length>max)throw new Refusal("REJECTED","RECORD_TOO_LARGE"); return bytes; }
        private static byte[] utf8(String value,String reason){try{return KafkaText.utf8(value);}catch(IllegalArgumentException malformed){throw new Refusal("REJECTED",reason);}}
        private static String string(Object raw,int max){if(!(raw instanceof String value)||value.length()>max||value.contains("\0"))throw new Refusal("REJECTED","INVALID_PAYLOAD");return value;}
        private static int integer(Object raw){long value=longInteger(raw);if(value>Integer.MAX_VALUE)throw new Refusal("REJECTED","INVALID_PAYLOAD");return(int)value;}
        private static long longInteger(Object raw){if(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long)return((Number)raw).longValue();throw new Refusal("REJECTED","INVALID_PAYLOAD");}
    }

    private static final class Tracker implements KafkaProtocol.Observer {
        private final boolean autoCreate; private final AtomicReference<Outcome> outcome=new AtomicReference<>(); private final CompletableFuture<Outcome> done=new CompletableFuture<>();
        private Tracker(boolean autoCreate){this.autoCreate=autoCreate;}
        @Override public void acknowledged(KafkaProtocol.Metadata metadata){complete(new Outcome("ACKNOWLEDGED","ACKNOWLEDGED",metadata));}
        @Override public void failed(Throwable failure){ if(terminal(failure,autoCreate))complete(new Outcome("PERMANENT_FAILURE","BROKER_REJECTED",null)); else ambiguous("DELIVERY_STATE_UNKNOWN"); }
        void ambiguous(String reason){complete(new Outcome("AMBIGUOUS",reason,null));}
        Outcome current(){return outcome.get();}
        Outcome await(long nanos){if(nanos<=0){ambiguous("DELIVERY_TIMEOUT");return outcome.get();}try{return done.get(nanos,TimeUnit.NANOSECONDS);}catch(Exception timeout){ambiguous("DELIVERY_TIMEOUT");return outcome.get();}}
        private void complete(Outcome value){if(outcome.compareAndSet(null,value))done.complete(value);}
        private static boolean terminal(Throwable failure,boolean autoCreate){for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof AuthenticationException||c instanceof AuthorizationException||c instanceof ConfigException||c instanceof SerializationException||!autoCreate&&c instanceof UnknownTopicOrPartitionException)return true;return false;}
    }
    private record Outcome(String status,String reason,KafkaProtocol.Metadata metadata){}
    private static final class Refusal extends RuntimeException {
        private final String status;
        private final String reason;
        private Refusal(String status,String reason){super(reason);this.status=status;this.reason=reason;}
    }
    private static final class DeadlineExceeded extends Exception { }
    private static long deadline(long now,int ms){try{return Math.addExact(now,TimeUnit.MILLISECONDS.toNanos(ms));}catch(ArithmeticException overflow){return Long.MAX_VALUE;}}
    private long remainingNanos(long deadline){try{return Math.max(0,Math.subtractExact(deadline,ticker.getAsLong()));}catch(ArithmeticException overflow){return 0;}}
    private int remainingMillis(long deadline){long n=remainingNanos(deadline);if(n==0)return 0;long ms=TimeUnit.NANOSECONDS.toMillis(n);if(TimeUnit.MILLISECONDS.toNanos(ms)<n)ms++;return(int)Math.min(Integer.MAX_VALUE,ms);}
}
