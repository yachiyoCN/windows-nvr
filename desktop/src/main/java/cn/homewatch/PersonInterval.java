package cn.homewatch;

/** Approximate person presence within a recording, measured from its first frame. */
public record PersonInterval(double startSeconds, double endSeconds) {
    public PersonInterval {
        if (!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)
                || startSeconds < 0 || endSeconds < startSeconds) {
            throw new IllegalArgumentException("Invalid person interval");
        }
    }
}
