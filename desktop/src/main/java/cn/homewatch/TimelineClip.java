package cn.homewatch;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** One recording with its verified playback duration (active durations are provisional). */
public record TimelineClip(Path path, Instant start, double durationSeconds, boolean active) {
    public TimelineClip {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(start, "start");
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0)
            throw new IllegalArgumentException("invalid recording duration");
    }

    public Instant end() {
        long seconds = (long) durationSeconds;
        long nanos = Math.round((durationSeconds - seconds) * 1_000_000_000L);
        return start.plusSeconds(seconds).plusNanos(nanos);
    }
}
