package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.node.NodeConfiguration;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramInputPropertyTest {
    @Property(tries = 80) void rejectsDecimalChatIdentifiersOutsideSigned64(@ForAll("outsideSigned64") String chat) {
        assertInvalid(payload(Map.of("chatId", chat, "text", "bounded")));
    }

    @Property(tries = 60) void rejectsCallbackDataBeyondTheUtf8ByteLimit(@ForAll("oversizedCallbacks") String callback) {
        assertInvalid(payload(Map.of("chatId", "-100123", "text", "bounded", "inlineKeyboard",
                java.util.List.of(java.util.List.of(Map.of("text", "button", "callbackData", callback))))));
    }

    @Property(tries = 40) void rejectsEntityBoundariesThatSplitAnAstralCharacter(@ForAll int prefix) {
        int boundedPrefix = Math.floorMod(prefix, 20);
        String text = "a".repeat(boundedPrefix) + "🌍" + "z";
        var entity = Map.of("type", "bold", "offset", boundedPrefix, "length", 1);
        assertInvalid(payload(Map.of("chatId", "-100123", "text", text, "entities", java.util.List.of(entity))));
    }

    @Provide Arbitrary<String> outsideSigned64() {
        BigInteger high = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        return Arbitraries.bigIntegers().between(high, high.add(BigInteger.TEN.pow(20))).map(BigInteger::toString);
    }
    @Provide Arbitrary<String> oversizedCallbacks() {
        return Arbitraries.strings().withCharRange('é', 'é').ofMinLength(33).ofMaxLength(80);
    }

    private static Map<String, Object> payload(Map<String, Object> fields) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "telegram.send.v1");
        value.putAll(fields);
        return value;
    }
    private static void assertInvalid(Map<String, Object> payload) {
        String tenant = "property-tenant";
        AtomicInteger credentials = new AtomicInteger();
        var profile = new TelegramProfile(tenant, TelegramTestSupport.PROFILE, "unused", Set.of("*"),
                Set.of("sendMessage", "sendPhoto"), Set.of("example.test"), false,
                2, 30, 100, 100, 4_096, 1_024, 20, 0);
        var behavior = new TelegramSendNodeBehavior(reference -> { credentials.incrementAndGet(); return Optional.empty(); },
                (requestedTenant, name) -> Optional.of(profile), HttpClient.newHttpClient(),
                java.net.URI.create("https://localhost"));
        var action = behavior.create(new NodeConfiguration("telegram", "telegram.send", Map.of("botProfile", TelegramTestSupport.PROFILE)));
        CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join());
        assertInstanceOf(TelegramSendException.class, failure.getCause());
        assertEquals(TelegramSendException.Code.INVALID_INPUT, ((TelegramSendException) failure.getCause()).code());
        assertEquals(0, credentials.get());
    }
}
