package ai.ravenroot.testkit.sandbox;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;

/** Standalone, separately launched CPU-service premise calibration for the hosted issue-138 matrix. */
final class CpuServiceCalibration {

    private static final long TARGET_CPU_NANOS = Duration.ofSeconds(4).toNanos();
    private static final long RUNNABLE_WALL_MILLIS = Duration.ofSeconds(6).toMillis();

    private CpuServiceCalibration() {
    }

    public static void main(String[] arguments) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean supported = bean.isCurrentThreadCpuTimeSupported();
        try {
            if (supported && !bean.isThreadCpuTimeEnabled()) {
                bean.setThreadCpuTimeEnabled(true);
            }
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            supported = false;
        }

        Instant startedAt = Instant.now();
        long wallStart = System.nanoTime();
        long cpuStart = supported ? bean.getCurrentThreadCpuTime() : -1L;
        long accumulator = 1L;
        if (cpuStart >= 0L) {
            while (bean.getCurrentThreadCpuTime() - cpuStart < TARGET_CPU_NANOS) {
                accumulator = accumulator * 31L + 7L;
                if (accumulator == Long.MIN_VALUE) {
                    accumulator = 1L;
                }
            }
        } else {
            supported = false;
        }
        long wallMillis = Duration.ofNanos(System.nanoTime() - wallStart).toMillis();
        long cpuMillis = supported
                ? Duration.ofNanos(bean.getCurrentThreadCpuTime() - cpuStart).toMillis() : -1L;
        boolean runnable = supported && cpuMillis >= 4_000L && wallMillis <= RUNNABLE_WALL_MILLIS;

        System.out.println("RAVENROOT_CPU_CALIBRATION={\"startedAt\":\"" + startedAt
                + "\",\"supported\":" + supported
                + ",\"cpuMillis\":" + cpuMillis
                + ",\"wallMillis\":" + wallMillis
                + ",\"runnable\":" + runnable
                + ",\"availableProcessors\":" + Runtime.getRuntime().availableProcessors() + "}");
    }
}
