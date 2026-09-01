package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict Base64 JSON profile; GraphML supplies only the opaque profile name. */
public final class EnvironmentWebSocketProfileResolver implements WebSocketProfileResolver {
    private static final PayloadLimits LIMITS = new PayloadLimits(128 * 1024, 12, 128, 2048, 16 * 1024, 128);
    private static final Set<String> FIELDS = Set.of("destination", "headers", "subprotocols", "credentialBindingId", "credentialReference", "maximumMessageBytes", "maximumFragments", "timeoutMs", "reconnectBackoffMs", "maxConcurrency", "maxBufferedEvents");
    private final Map<String,String> environment;
    public EnvironmentWebSocketProfileResolver() { this(System.getenv()); }
    EnvironmentWebSocketProfileResolver(Map<String,String> environment) { this.environment = Map.copyOf(environment); }
    @Override public Optional<WebSocketProfile> resolve(String name) { try {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) return Optional.empty(); String encoded=environment.get(variable(name)); if(encoded==null||encoded.length()>180000)return Optional.empty(); byte[] bytes=Base64.getDecoder().decode(encoded); if(!Base64.getEncoder().encodeToString(bytes).equals(encoded))return Optional.empty(); Object raw=PayloadJson.read(bytes,LIMITS).toJava(); if(!(raw instanceof Map<?,?> source)||source.keySet().stream().anyMatch(k->!(k instanceof String)))return Optional.empty(); Map<String,Object> map=new LinkedHashMap<>(); source.forEach((k,v)->map.put((String)k,v)); if(!map.keySet().equals(FIELDS))return Optional.empty(); return Optional.of(new WebSocketProfile(name,URI.create(text(map,"destination",2048)),headers(map.get("headers")),strings(map.get("subprotocols"),16,128),nullable(map,"credentialBindingId"),nullable(map,"credentialReference"),integer(map,"maximumMessageBytes",1,16*1024*1024),integer(map,"maximumFragments",1,1024),integer(map,"timeoutMs",1,300000),integer(map,"reconnectBackoffMs",1,300000),integer(map,"maxConcurrency",1,256),integer(map,"maxBufferedEvents",1,65536)));
    } catch(RuntimeException bad){return Optional.empty();} }
    static String variable(String name) { return "RAVENROOT_WEBSOCKET_PROFILE_" + EnvironmentKeyCodec.hex(name); }
    private static String text(Map<String,Object> map,String key,int max){Object v=map.get(key);if(!(v instanceof String s)||s.isBlank()||s.length()>max)throw new IllegalArgumentException(key);return s;}
    private static String nullable(Map<String,Object> map,String key){Object v=map.get(key);if(v==null)return null;if(!(v instanceof String s)||s.isBlank()||s.length()>256)throw new IllegalArgumentException(key);return s;}
    private static int integer(Map<String,Object> map,String key,int min,int max){Object v=map.get(key);if(!(v instanceof Number n)||n.longValue()!=n.doubleValue()||n.longValue()<min||n.longValue()>max)throw new IllegalArgumentException(key);return (int)n.longValue();}
    private static List<String> strings(Object raw,int max,int length){if(!(raw instanceof List<?> list)||list.size()>max)throw new IllegalArgumentException();return list.stream().map(v->{if(!(v instanceof String s)||s.isBlank()||s.length()>length)throw new IllegalArgumentException();return s;}).toList();}
    private static Map<String,List<String>> headers(Object raw){if(!(raw instanceof Map<?,?> map)||map.size()>32)throw new IllegalArgumentException();Map<String,List<String>> out=new LinkedHashMap<>();map.forEach((k,v)->{if(!(k instanceof String s))throw new IllegalArgumentException();out.put(s,strings(v,8,512));});return Map.copyOf(out);}
}
