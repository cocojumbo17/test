package ua.probe.byd;

/** Last temperatures received from the car. A missing value is null. */
final class Reading {

    /** After three missed read intervals the values are shown as "last known, update pending". */
    static final long STALE_AFTER_MS = 3 * RetryPolicy.NORMAL_INTERVAL_MS;

    final Integer inside;
    final Integer outside;
    final long timeMs;

    Reading(Integer inside, Integer outside, long timeMs) {
        this.inside = inside;
        this.outside = outside;
        this.timeMs = timeMs;
    }

    boolean isStale(long nowMs) {
        return nowMs - timeMs > STALE_AFTER_MS;
    }
}
