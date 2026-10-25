package ai.ravenroot.server.personal;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Explicit deployment registration; no issued user's client ID is a built-in application credential. */
public record PersonalOAuthConfiguration(String mode, URI callback, String clientId, String clientSecret,
        Path directory, Path keyFile, Set<String> allowedHosts, String assistantModel) {
    public static PersonalOAuthConfiguration fromEnvironment(Map<String, String> env) {
        String mode = env.getOrDefault("RAVENROOT_PERSONAL_OPENAI_MODE", "disabled");
        if (!Set.of("disabled", "loopback", "registered").contains(mode)) throw new IllegalArgumentException("Invalid personal OpenAI mode");
        if (mode.equals("disabled")) return null;
        URI callback;
        try { callback = URI.create(env.getOrDefault("RAVENROOT_PERSONAL_CALLBACK", "http://127.0.0.1:8080/auth/callback")); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid personal callback configuration"); }
        if (callback.getUserInfo() != null || callback.getQuery() != null || callback.getFragment() != null
                || callback.getHost() == null || !callback.getPath().equals("/auth/callback")) throw new IllegalArgumentException("Invalid personal callback configuration");
        if (mode.equals("loopback") && !(callback.getScheme().equals("http")
                && callback.getHost().equals("127.0.0.1") && callback.getPath().equals("/auth/callback")))
            throw new IllegalArgumentException("Direct OpenAI registration requires http://127.0.0.1:<port>/auth/callback");
        if (mode.equals("registered") && !callback.getScheme().equals("https"))
            throw new IllegalArgumentException("Registered remote OpenAI callback requires HTTPS");
        String client = env.getOrDefault("RAVENROOT_PERSONAL_OPENAI_CLIENT_ID", "");
        if (mode.equals("registered") && (client.isBlank() || client.equals("dynamic_agent_client")))
            throw new IllegalArgumentException("Remote personal OpenAI requires a provisioned eligible registration");
        Path directory = Path.of(env.getOrDefault("RAVENROOT_PERSONAL_STORE_DIR", "/opt/ravenroot/data/personal"));
        Path key = Path.of(env.getOrDefault("RAVENROOT_PERSONAL_KEY_FILE", directory.resolve("storage.key").toString()));
        Set<String> hosts = new HashSet<>();
        for (String host : env.getOrDefault("RAVENROOT_PERSONAL_ALLOWED_HOSTS", "").split(",")) if (!host.isBlank()) hosts.add(host.strip());
        return new PersonalOAuthConfiguration(mode, callback, client,
                env.getOrDefault("RAVENROOT_PERSONAL_OPENAI_CLIENT_SECRET", ""), directory, key,
                Set.copyOf(hosts), env.getOrDefault("RAVENROOT_PERSONAL_ASSISTANT_MODEL", ""));
    }
    public boolean loopback() { return mode.equals("loopback"); }
    public boolean networkReady() { return allowedHosts.containsAll(Set.of("auth.openai.com", "api.openai.com")); }
    @Override public String toString() { return "PersonalOAuthConfiguration[mode=" + mode + "]"; }
}
