package ua.probe.byd;

/**
 * When to read again. Right after engine start the car's ADB is often not ready yet,
 * so failed reads are retried quickly (2, 4, 8, 16 s) before falling back to the normal interval.
 */
final class RetryPolicy {

    static final long NORMAL_INTERVAL_MS = 30_000;
    private static final long FIRST_RETRY_MS = 2_000;
    private static final int MAX_DOUBLINGS = 4;

    private RetryPolicy() { }

    static long nextDelayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return NORMAL_INTERVAL_MS;
        long retry = FIRST_RETRY_MS << Math.min(consecutiveFailures - 1, MAX_DOUBLINGS);
        return Math.min(NORMAL_INTERVAL_MS, retry);
    }
}
