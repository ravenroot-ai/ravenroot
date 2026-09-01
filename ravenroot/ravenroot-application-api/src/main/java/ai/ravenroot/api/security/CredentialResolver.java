package ai.ravenroot.api.security;

import java.util.Optional;

/**
 * Resolves opaque graph references at the trusted composition boundary.
 *
 * <h2>Implementations must be safe for concurrent use (ADR 0024 §3)</h2>
 * <p>One resolver serves every node that reads a binding, and those reads happen concurrently. A cache added here is shared mutable state on a security path and must be safe for concurrent use: an unsynchronised map would be a correctness defect in credential resolution, not merely a slow one.</p>
 *
 * <p>This is stated rather than newly imposed. Nothing here was ever documented as single-threaded;
 * it was true by accident, because one logical graph node was backed by one actor and an actor
 * handles one message at a time. ADR 0024 removes that serialisation deliberately, so an
 * implementation that relied on it is now racy. The implementations in this repository are already
 * safe; a third-party one never had a rule to follow, so here it is.</p>
 */
@FunctionalInterface
public interface CredentialResolver {
/**
 * Resolves a declared credential reference through the host's trusted credential boundary.
 * @param reference reference name, never credential material
 * @return a caller-owned secret value when the reference resolves, otherwise an empty optional
 */
    Optional<SecretValue> resolve(String reference);
}
