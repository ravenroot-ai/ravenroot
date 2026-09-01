package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.SecretValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class KafkaTestSupport {
    static final String TENANT="tenant-a", PROFILE="orders", SECRET="correct-horse-battery-staple";
    static KafkaProfile profile(){return profile(TENANT,PROFILE,1000);}
    static KafkaProfile profile(String tenant,String name,int timeout){return profile(tenant,name,timeout,100);}
    static KafkaProfile profile(String tenant,String name,int timeout,int rate){return new KafkaProfile(tenant,name,List.of("broker.example.test:9093"),"use_all_dns_ips",true,"SCRAM-SHA-512","publisher","kafka-orders","ravenroot-orders","orders",Set.of("audit"),Set.of("trace","correlation"),true,31,true,"zstd","all",true,10,5,false,4,rate,timeout,4096,8192);}
    static NodeConfiguration configuration(){return configuration(Map.of());}
    static NodeConfiguration configuration(Map<String,Object> overrides){var p=new java.util.LinkedHashMap<String,Object>();p.put("clusterProfile",PROFILE);p.putAll(overrides);return new NodeConfiguration("produce",KafkaProduceNodeBehavior.BEHAVIOR,p);}
    static Map<String,Object> payload(){return Map.of("version","kafka.produce.v1","valueText","hello","correlationId","c-1");}
    static NodeMessage message(Object payload){UUID id=UUID.randomUUID();return new NodeMessage(new SecurityContext("request",TENANT,"subject", PrincipalType.WORKLOAD,"issuer"),id,id,id,id,Set.of(),"produce",payload,Map.of());}
    @SuppressWarnings("unchecked") static Map<String,Object> output(NodeAction action,Object payload){return(Map<String,Object>)action.handle(message(payload)).toCompletableFuture().join().payload();}
    static KafkaProduceNodeBehavior behavior(FakeProtocol protocol){return new KafkaProduceNodeBehavior(ref->Optional.of(new SecretValue(SECRET.toCharArray())),(tenant,name)->Optional.of(profile(tenant,name,1000)),protocol,new KafkaRuntimeControls(System::nanoTime,Runnable::run,32,16,128),System::nanoTime);}

    enum Event{ACK,SILENT,AUTH,TRANSIENT}
    static final class FakeProtocol implements KafkaProtocol{
        final ArrayDeque<Event> events=new ArrayDeque<>();final AtomicInteger creates=new AtomicInteger(),sends=new AtomicInteger(),flushes=new AtomicInteger(),closes=new AtomicInteger();final List<Record> records=new ArrayList<>();final List<char[]> passwords=new ArrayList<>();
        FakeProtocol(Event...e){events.addAll(List.of(e));}
        @Override public CreateAttempt beginCreate(KafkaProfile profile,char[] password,int timeout){creates.incrementAndGet();passwords.add(password);Event event=events.isEmpty()?Event.ACK:events.removeFirst();return new CreateAttempt(){
            @Override public void establish() throws ClientFailure{if(event==Event.TRANSIENT)throw new ClientFailure(FailureKind.TEMPORARY);}
            @Override public Client claim(){return new Client(){@Override public void send(Record record,Observer observer,int timeout){sends.incrementAndGet();records.add(record);if(event==Event.ACK)observer.acknowledged(new Metadata(record.topic(),record.partition()==null?2:record.partition(),42,1234,record.key()==null?-1:record.key().length,record.value().length));else if(event==Event.AUTH)observer.failed(new org.apache.kafka.common.errors.AuthenticationException("secret"));}@Override public void flush(){flushes.incrementAndGet();}@Override public void close(int timeout,Runnable revoked){closes.incrementAndGet();revoked.run();}};}
            @Override public void cancel(){}
        };}
    }
}
