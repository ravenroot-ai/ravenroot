package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable graph definition as the store holds it.
 *
 * <p>Every instance a store returns from a read has had its bytes verified against the digest stored
 * beside them. A caller therefore never has to decide whether to trust what it received: an
 * unverifiable definition is a classified failure, not a value.</p>
 *
 * <p>{@code identity} is <em>a</em> logical version bound to this content, not the only one. The
 * same document may be bound under several versions, which is what happens when a graph is copied
 * and published under a new name; a read by content address reports the version the content was
 * first stored under, while a read that resolved a version reports the version it resolved.</p>
 *
 * @param key tenant-scoped address of this definition.
 * @param identity logical graph and version identity this content is bound under.
 * @param canonical the exact canonical executable GraphML and its content address.
 * @param storedAt instant at which the definition was first durably recorded.
 */
public record StoredGraphDefinition(GraphDefinitionKey key, GraphDefinitionIdentity identity,
                                    CanonicalGraphMl canonical, Instant storedAt) {

    /** Rejects an incomplete definition and an address that disagrees with the content it names. */
    public StoredGraphDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(canonical, "canonical");
        Objects.requireNonNull(storedAt, "storedAt");
        if (!key.contentId().equals(canonical.contentId())) {
            throw new IllegalArgumentException(
                    "the stored definition's key must address the content it carries");
        }
    }
}
