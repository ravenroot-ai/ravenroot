package ai.ravenroot.server;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/** JRE-only health probe used by OCI images without adding curl or wget. */
public final class RavenrootHealthcheck {
    private RavenrootHealthcheck() {
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("RAVENROOT_PORT", "8080"));
        if (!isHealthy(port)) {
            System.err.println("Ravenroot health endpoint is unavailable on port " + port);
            System.exit(1);
        }
    }

    static boolean isHealthy(int port) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(2_000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (IOException | IllegalArgumentException error) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
