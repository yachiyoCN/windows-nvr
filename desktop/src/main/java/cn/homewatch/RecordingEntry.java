package cn.homewatch;

import java.nio.file.Path;
import java.time.Instant;

/** A recording snapshot. Active entries expose only the received, flushed playback range. */
public record RecordingEntry(Path path, Instant start, long bytes, boolean active, double durationSeconds) {
    public RecordingEntry(Path path, Instant start, long bytes, boolean active) {
        this(path, start, bytes, active, 0);
    }
    public RecordingEntry {
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0) durationSeconds = 0;
    }
}
