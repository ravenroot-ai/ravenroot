package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCapability;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.testkit.audit.AuditTrailContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR 0013 conformance suite, run against the durable, file-backed adapter.
 *
 * <p>{@code trailId} maps to a subdirectory under the per-test temporary directory, so a reopen
 * genuinely reconnects to the same bytes on disk, exactly as {@code SqliteExecutionStoreContractTest}
 * does for PERS-03. This is the only in-tree adapter that declares {@link AuditCapability#DURABLE}, so
 * this is the run in which the reopen assertion executes rather than reporting as a skip.</p>
 */
class FileAuditTrailContractTest extends AuditTrailContract {

    @TempDir
    Path rootDirectory;

    @Override
    protected AuditTrail createTrail(String trailId, Clock clock) {
        return new FileAuditTrail(rootDirectory.resolve(trailId), clock, Duration.ofHours(24));
    }

    @Test
    void declaresDurableSoTheReopenAssertionIsNotSkippedHere() {
        assertTrue(trail().supports(AuditCapability.DURABLE),
                "the conformance suite's reopen assertion silently degrades to a skip if this capability "
                        + "is ever dropped from capabilities()");
    }
}
