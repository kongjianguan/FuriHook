package dev.furihook.config;

public final class DetectionConfig {
    public static final int MAX_SNAPSHOT_UTF16 = 2048;
    public static final int MAX_TRACKED_VIEWS = 256;
    public static final int QUEUE_CAPACITY = 16;
    public static final long VIEW_INTERVAL_MS = 500;
    public static final int CAPTURES_PER_SECOND = 40;
    public static final int LOGS_PER_SECOND = 10;

    private DetectionConfig() {
    }
}
