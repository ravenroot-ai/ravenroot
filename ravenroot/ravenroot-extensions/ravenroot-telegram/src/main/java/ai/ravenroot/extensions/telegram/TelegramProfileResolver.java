package ai.ravenroot.extensions.telegram;

import java.util.Optional;

@FunctionalInterface
public interface TelegramProfileResolver {
    Optional<TelegramProfile> resolve(String tenant, String profile);
}
