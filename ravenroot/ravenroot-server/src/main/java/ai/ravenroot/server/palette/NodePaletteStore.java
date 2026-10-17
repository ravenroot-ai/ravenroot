package ai.ravenroot.server.palette;

import java.time.Instant;
import java.util.List;

/** Durable personal node palettes scoped to one exact authenticated author. */
public interface NodePaletteStore {
    int MAX_PALETTES_PER_OWNER = 32;
    int MAX_TEMPLATES_PER_PALETTE = 64;

    record Owner(String tenantId, String issuer, String subject) {
        public Owner {
            tenantId = required(tenantId, "tenantId");
            issuer = required(issuer, "issuer");
            subject = required(subject, "subject");
        }
    }

    record Palette(String id, String name, long version, Instant createdAt, Instant updatedAt) { }

    record Template(String id, String paletteId, String name, String kind, String payload,
                    long version, Instant createdAt, Instant updatedAt) { }

    enum Failure { NOT_FOUND, CONFLICT, LIMIT_REACHED, DUPLICATE_NAME, UNAVAILABLE }

    final class StoreException extends RuntimeException {
        private final Failure failure;

        public StoreException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    List<Palette> listPalettes(Owner owner);

    List<Template> listTemplates(Owner owner);

    Palette createPalette(Owner owner, String name);

    Palette renamePalette(Owner owner, String id, long expectedVersion, String name);

    void deletePalette(Owner owner, String id, long expectedVersion);

    Template createTemplate(Owner owner, String paletteId, String name, String kind, String payload);

    Template updateTemplate(Owner owner, String id, long expectedVersion, String paletteId, String name);

    void deleteTemplate(Owner owner, String id, long expectedVersion);

    Template findTemplate(Owner owner, String id);

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
