package cn.homewatch;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reads received video packet timestamps when an interrupted Matroska file has no duration footer. */
final class RecordingDurationProbe {
    private RecordingDurationProbe() {}

    static double receivedDuration(Path ffprobe, Path recording, java.util.function.BooleanSupplier cancelled) {
        Process process = null;
        try {
            if (cancelled.getAsBoolean()) return 0;
            process = new ProcessBuilder(ffprobe.toString(), "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "packet=pts_time,duration_time", "-of", "csv=p=0", recording.toString()).start();
            Process running = process;
            AtomicReference<Double> end = new AtomicReference<>(0.0);
            Thread output = Thread.startVirtualThread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        String[] fields = line.split(",", 3);
                        if (fields.length < 2) continue;
                        try {
                            double start = Double.parseDouble(fields[0]);
                            double duration = Double.parseDouble(fields[1]);
                            double value = start + Math.max(0, duration);
                            if (Double.isFinite(value) && value > end.get() && value < 366 * 86400.0) end.set(value);
                        } catch (NumberFormatException ignored) {}
                    }
                } catch (java.io.IOException ignored) {}
            });
            Thread errors = Thread.startVirtualThread(() -> {
                try (var input = running.getErrorStream()) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (java.io.IOException ignored) {}
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!running.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadline) return 0;
            }
            output.join(1000); errors.join(1000);
            // A truncated final cluster can make ffprobe fail after valid preceding packets.
            return cancelled.getAsBoolean() || output.isAlive() ? 0 : end.get();
        } catch (java.io.IOException ignored) {
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        return 0;
    }
}
