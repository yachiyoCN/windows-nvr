package cn.homewatch;

/** Reuses inference briefly only when local pixels remain nearly identical, including small subjects. */
final class PersonFrameReuse {
    private final int width, height;
    private byte[] prior;
    private double inferenceTime = Double.NEGATIVE_INFINITY;

    PersonFrameReuse(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height * 3 > Integer.MAX_VALUE) throw new IllegalArgumentException("frame size");
        this.width = width; this.height = height;
    }

    boolean mayReuse(byte[] frame, double time, boolean confirmationPending) {
        validate(frame, time);
        if (confirmationPending || prior == null || time - inferenceTime >= 0.76 || time < inferenceTime) return false;
        // Sampling every fourth pixel limits comparison to ~32K pixels at 960×540. A tiny local
        // change also invalidates the result; mean scene brightness alone would miss a distant person.
        int changed = 0, samples = 0;
        long absolute = 0;
        for (int y = 1; y < height; y += 4) {
            for (int x = 1; x < width; x += 4) {
                int offset = (y * width + x) * 3;
                int delta = Math.max(Math.abs((frame[offset] & 255) - (prior[offset] & 255)),
                        Math.max(Math.abs((frame[offset + 1] & 255) - (prior[offset + 1] & 255)),
                                Math.abs((frame[offset + 2] & 255) - (prior[offset + 2] & 255))));
                absolute += delta;
                if (delta >= 7 && ++changed > 12) return false;
                samples++;
            }
        }
        return changed <= 12 && absolute < samples * 0.30;
    }

    void inferred(byte[] frame, double seconds) {
        validate(frame, seconds);
        if (prior == null) prior = new byte[frame.length];
        System.arraycopy(frame, 0, prior, 0, frame.length);
        inferenceTime = seconds;
    }

    private void validate(byte[] frame, double time) {
        if (frame == null || frame.length != width * height * 3 || !Double.isFinite(time) || time < 0)
            throw new IllegalArgumentException("frame size or timestamp");
    }
}
