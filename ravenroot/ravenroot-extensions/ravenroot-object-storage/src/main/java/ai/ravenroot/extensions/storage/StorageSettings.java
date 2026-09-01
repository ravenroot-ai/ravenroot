package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.node.NodeConfiguration;

import java.net.URI;

record StorageSettings(StorageProfile profile, StorageProfile.Operation operation, URI destination,
                       String encoding, int maxBytes, int timeoutMs, int maxConcurrency) {
    static StorageSettings compile(NodeConfiguration configuration, StorageProfileResolver resolver,
                                   StorageProfile.Operation operation) {
        try {
            String profileName = configuration.requiredProperty("storageProfile");
            StorageProfile profile = resolver.resolve(profileName)
                    .orElseThrow(() -> StorageException.of(StorageException.Code.CONFIGURATION));
            if (!profile.allowedOperations().contains(operation)) {
                throw StorageException.of(StorageException.Code.CONFIGURATION);
            }
            URI destination = StorageUri.destination(profile, configuration.requiredProperty("key"));
            String encoding = configuration.property("encoding", "base64");
            if (operation == StorageProfile.Operation.GET
                    && !(encoding.equals("text") || encoding.equals("base64"))) {
                throw StorageException.of(StorageException.Code.CONFIGURATION);
            }
            int bytes = tighten(configuration, "maxBytes", profile.maxObjectBytes());
            int timeout = tighten(configuration, "timeoutMs", profile.timeoutMs());
            int concurrency = tighten(configuration, "maxConcurrency", profile.maxConcurrency());
            return new StorageSettings(profile, operation, destination, encoding, bytes, timeout, concurrency);
        } catch (StorageException safe) {
            throw safe;
        } catch (RuntimeException invalid) {
            throw StorageException.of(StorageException.Code.CONFIGURATION);
        }
    }

    private static int tighten(NodeConfiguration configuration, String name, int ceiling) {
        String value = configuration.property(name).orElse(null);
        if (value == null) return ceiling;
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > ceiling) throw StorageException.of(StorageException.Code.CONFIGURATION);
        return parsed;
    }
}
