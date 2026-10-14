package cn.homewatch.recorder;

/** Small bounds for server streaming: reserve the next connection without repeatedly fetching tiny tails. */
final class PlaybackBufferPolicy {
    static boolean prefetch(double secondsRemaining, float speed, double followingSeconds, boolean active) {
        return secondsRemaining > 0 && secondsRemaining <= Math.max(8, speed * 8)
                && followingSeconds >= (active ? 4 : 0.5);
    }
    static boolean resumeTail(double seconds, boolean active) { return seconds >= (active ? 4 : 0.5); }
    static boolean localSeek(int durationMillis, int bufferedPercent, long positionMillis) {
        return durationMillis > 0 && positionMillis >= 0 && positionMillis < durationMillis - 250
                && positionMillis <= (long) durationMillis * Math.max(0, Math.min(100, bufferedPercent)) / 100 - 250;
    }
}
