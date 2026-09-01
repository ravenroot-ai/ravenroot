package ai.ravenroot.server.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Fail-closed service authentication configuration sourced by the executable adapter. */
public record AuthenticationConfiguration(InetSocketAddress bindAddress, RequestAuthenticator authenticator,
                                          String mode) {
    public static AuthenticationConfiguration fromEnvironment(Map<String, String> environment, int port) {
        return fromEnvironment(environment, port, Files.exists(Path.of("/.dockerenv")));
    }

    static AuthenticationConfiguration fromEnvironment(Map<String, String> environment, int port,
                                                       boolean containerized) {
        InetAddress bind = address(environment.getOrDefault("RAVENROOT_BIND_ADDRESS", "127.0.0.1"));
        String declared = environment.get("RAVENROOT_AUTH_MODE");
        String mode = declared == null ? defaultMode(bind) : declared.trim();
        var socket = new InetSocketAddress(bind, port);
        return switch (mode) {
            case "oidc" -> new AuthenticationConfiguration(socket, oidc(environment), mode);
            case "local-token" -> {
                requireLoopback(bind, mode);
                yield new AuthenticationConfiguration(socket,
                        new LocalTokenAuthenticator(required(environment, "RAVENROOT_AUTH_LOCAL_TOKEN")), mode);
            }
            case "disabled" -> {
                requireDisabledLoopbackOrContainerProxy(environment, bind, containerized);
                System.err.println("WARNING: Ravenroot authentication is explicitly disabled for local-only use");
                yield new AuthenticationConfiguration(socket, new DisabledLoopbackAuthenticator(), mode);
            }
            default -> throw new IllegalArgumentException("Unknown RAVENROOT_AUTH_MODE: " + mode);
        };
    }

    /**
     * The mode to use when {@code RAVENROOT_AUTH_MODE} is not set at all, which is decided by
     * exposure rather than by the product.
     *
     * <p>A loopback listener is reachable only from the machine it runs on, so a default of
     * {@code disabled} there costs no perimeter and lets someone evaluating Ravenroot start the jar
     * with no configuration. Any other bind faces a network, and there is no safe guess to make for
     * it: rather than choosing a mode, startup stops and names the variable the operator has to set.
     * That is the half of the previous fail-closed default worth keeping — it was never the value
     * {@code oidc} that protected the published image, it was the refusal to serve a network without
     * a deliberate decision.</p>
     *
     * <p>This is reached only when the variable is absent. A variable that is present but blank is
     * still an explicit declaration and keeps its existing "unknown mode" failure, so nothing about
     * a declared mode changes. The returned {@code disabled} flows through the ordinary
     * {@code case "disabled"} branch below, so its loopback guard and its warning both still run —
     * this method chooses a default, it does not grant an exemption.</p>
     */
    private static String defaultMode(InetAddress bind) {
        if (bind.isLoopbackAddress()) {
            return "disabled";
        }
        throw new IllegalArgumentException("RAVENROOT_AUTH_MODE must be set explicitly when the server "
                + "does not bind to a loopback address (bind address: " + bind.getHostAddress() + "). "
                + "Set RAVENROOT_AUTH_MODE=oidc with its issuer, audience and JWKS URI for a "
                + "network-facing deployment, or bind to 127.0.0.1 to evaluate Ravenroot locally.");
    }

    private static RequestAuthenticator oidc(Map<String, String> environment) {
        URI issuer = URI.create(required(environment, "RAVENROOT_AUTH_ISSUER"));
        String audience = required(environment, "RAVENROOT_AUTH_AUDIENCE");
        URI jwks = URI.create(required(environment, "RAVENROOT_AUTH_JWKS_URI"));
        String typeClaim = environment.getOrDefault("RAVENROOT_AUTH_PRINCIPAL_TYPE_CLAIM", "token_kind").trim();
        long skewSeconds = parseLong(environment, "RAVENROOT_AUTH_CLOCK_SKEW_SECONDS", 30);
        long cacheSeconds = parseLong(environment, "RAVENROOT_AUTH_JWKS_CACHE_SECONDS", 300);
        return new JwtRequestAuthenticator(issuer, audience, typeClaim, Duration.ofSeconds(skewSeconds),
                new JwkSetProvider(jwks, Duration.ofSeconds(cacheSeconds)));
    }

    private static InetAddress address(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!address.getHostAddress().equals(value) && !"localhost".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("RAVENROOT_BIND_ADDRESS must be an IP literal or localhost");
            }
            return address;
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("Invalid RAVENROOT_BIND_ADDRESS", error);
        }
    }

    private static void requireLoopback(InetAddress address, String mode) {
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("Authentication mode " + mode + " requires a loopback bind address");
        }
    }

    private static void requireDisabledLoopbackOrContainerProxy(Map<String, String> environment, InetAddress address,
                                                                boolean containerized) {
        if (address.isLoopbackAddress()) {
            return;
        }
        boolean wildcardIpv4 = "0.0.0.0".equals(address.getHostAddress());
        boolean explicitContainerGuard = "true".equals(environment.get("RAVENROOT_CONTAINER_LOOPBACK_ONLY"));
        boolean loopbackPublication = "127.0.0.1".equals(environment.get("RAVENROOT_LOCAL_HOST_BIND_ADDRESS"));
        if (containerized && wildcardIpv4 && explicitContainerGuard && loopbackPublication) {
            return;
        }
        throw new IllegalArgumentException("Authentication mode disabled requires a loopback bind, or a "
                + "containerized 0.0.0.0 listener explicitly published only on host 127.0.0.1");
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static long parseLong(Map<String, String> environment, String name, long defaultValue) {
        try {
            return Long.parseLong(environment.getOrDefault(name, Long.toString(defaultValue)));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }
}
