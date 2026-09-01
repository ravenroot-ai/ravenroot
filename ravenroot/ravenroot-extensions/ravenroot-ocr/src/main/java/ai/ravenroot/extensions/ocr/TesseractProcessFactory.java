package ai.ravenroot.extensions.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/** Package-private process seam used to prove command and lifecycle behavior deterministically. */
@FunctionalInterface
interface TesseractProcessFactory {
    TesseractProcess start(OcrInvocation invocation) throws IOException;

    interface TesseractProcess {
        InputStream stdout();
        InputStream stderr();
        void closeStdin();
        boolean await(Duration bound) throws InterruptedException;
        int exitCode();
        List<ProcessRef> descendants();
        ProcessRef root();
    }

    interface ProcessRef {
        boolean alive();
        void destroy();
        void destroyForcibly();
        boolean await(Duration bound) throws InterruptedException;
    }
}
