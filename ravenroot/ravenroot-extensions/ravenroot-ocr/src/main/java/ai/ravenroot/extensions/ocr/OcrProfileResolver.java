package ai.ravenroot.extensions.ocr;

import java.util.Optional;

/** Resolves an operator-owned profile under the delivered tenant. */
@FunctionalInterface
public interface OcrProfileResolver {
    Optional<OcrProfile> resolve(String tenantId, String profileName);
}
