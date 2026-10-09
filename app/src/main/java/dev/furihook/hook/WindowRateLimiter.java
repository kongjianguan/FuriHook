package dev.furihook.hook;

final class WindowRateLimiter {
    private final int limit;
    private long startMs = -1;
    private int count;

    WindowRateLimiter(int limit) {
        this.limit = limit;
    }

    synchronized boolean acquire(long nowMs) {
        if (startMs < 0 || nowMs - startMs >= 1000) {
            startMs = nowMs;
            count = 0;
        }
        if (count >= limit) {
            return false;
        }
        count++;
        return true;
    }
}
