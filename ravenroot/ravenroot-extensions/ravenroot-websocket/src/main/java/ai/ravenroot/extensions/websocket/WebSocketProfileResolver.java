package ai.ravenroot.extensions.websocket;
import java.util.Optional;
public interface WebSocketProfileResolver { Optional<WebSocketProfile> resolve(String profileName); }
