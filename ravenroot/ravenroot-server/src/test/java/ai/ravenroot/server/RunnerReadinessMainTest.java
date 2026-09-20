package ai.ravenroot.server;

import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RunnerReadinessMainTest {
    @Test void onlyAnUnexpiredPositiveWorkerObservationIsReady(@TempDir Path root) throws Exception {
        var path = root.resolve("ready");
        var sink = RunnerReadinessMain.sink(path, Duration.ofSeconds(10));
        assertThrows(Exception.class, () -> RunnerReadinessMain.main(new String[]{path.toString()}));
        sink.accept(true); RunnerReadinessMain.main(new String[]{path.toString()});
        sink.accept(false); assertFalse(Files.exists(path));
        Files.writeString(path, Instant.EPOCH.toString());
        assertThrows(Exception.class, () -> RunnerReadinessMain.main(new String[]{path.toString()}));
        Files.writeString(path, "x".repeat(65));
        assertThrows(Exception.class, () -> RunnerReadinessMain.main(new String[]{path.toString()}));
        var link = root.resolve("link"); Files.createSymbolicLink(link, path);
        assertThrows(Exception.class, () -> RunnerReadinessMain.main(new String[]{link.toString()}));
    }
}
