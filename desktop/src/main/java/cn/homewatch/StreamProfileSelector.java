package cn.homewatch;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves accessible native stream profiles without changing camera settings. */
public final class StreamProfileSelector {
    private static final Pattern CHANNELS = Pattern.compile("(?i)^(/Streaming/Channels/)([0-9]+)(01|02)(/?)$");
    private static final Pattern DIMENSIONS = Pattern.compile("(?m)^\\s*(\\d+),(\\d+)\\s*$");
    private static final long TOTAL_NANOS = TimeUnit.MILLISECONDS.toNanos(4000);
    private static final long CANDIDATE_NANOS = TimeUnit.MILLISECONDS.toNanos(2000);

    public record Selection(StreamEngine.Settings settings, int width, int height, String profile) {
        public Selection {
            Objects.requireNonNull(settings);
            profile = Objects.requireNonNullElse(profile, "原始地址");
        }
        public boolean dimensionsKnown() { return width > 0 && height > 0; }
    }

    record Candidate(String url, String profile) {
        @Override public String toString() { return profile; }
    }

    @FunctionalInterface interface ProcessLauncher {
        Process launch(List<String> command) throws IOException;
    }

    private StreamProfileSelector() { }

    /** Must run off the Swing event thread. Both profiles together take at most about 4 seconds. */
    public static Selection resolve(Path assetsRoot, StreamEngine.Settings settings) {
        return resolve(assetsRoot, settings, () -> false);
    }

    public static Selection resolve(Path assetsRoot, StreamEngine.Settings settings, BooleanSupplier cancelled) {
        return resolve(assetsRoot, settings, cancelled,
                command -> new ProcessBuilder(command).directory(assetsRoot.toFile()).start(),
                StreamProfileSelector::terminate);
    }

    // The live player supplies its tracked launcher so close() also owns an in-flight probe.
    static Selection resolve(Path assetsRoot, StreamEngine.Settings settings, BooleanSupplier cancelled,
            ProcessLauncher launcher, Consumer<Process> cleanup) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(cancelled);
        Path probe = assetsRoot.toAbsolutePath().normalize().resolve("tools/ffprobe.exe");
        if (!Files.isRegularFile(probe) || cancelled.getAsBoolean()) return original(settings);
        // The Android IP Camera app exposes one authenticated HTTPS elementary stream,
        // not RTSP profiles. Let FFmpeg decode the configured endpoint directly.
        if (settings.isHttpsVideo()) return original(settings);
        long deadline = System.nanoTime() + TOTAL_NANOS;
        List<Selection> accessible = new ArrayList<>();
        List<Candidate> candidates = candidates(settings);
        List<Future<Selection>> tasks = new ArrayList<>();
        // Known cameras expose at most two profiles. Probe them together, then
        // use the remaining overall budget to retry a temporarily slow main stream.
        try (var executor = Executors.newFixedThreadPool(candidates.size(), action ->
                Thread.ofPlatform().daemon().name("camera-profile-probe").unstarted(action))) {
            try {
                for (Candidate candidate : candidates) tasks.add(executor.submit(() -> {
                    long remaining = deadline - System.nanoTime();
                    return remaining <= 0 ? null : probeSelection(probe, settings, candidate,
                            Math.min(remaining, CANDIDATE_NANOS), cancelled, launcher, cleanup);
                }));
                for (Future<Selection> task : tasks) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || cancelled.getAsBoolean()) break;
                    Selection selection = task.get(remaining, TimeUnit.NANOSECONDS);
                    if (selection != null) accessible.add(selection);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // Preserve accessible results and the user's original fallback.
            } finally {
                for (Future<Selection> task : tasks) if (!task.isDone()) task.cancel(true);
                executor.shutdownNow();
            }
        }
        if (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            for (Candidate candidate : candidates) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                if (candidate.profile().equals("主码流")
                        && accessible.stream().noneMatch(value -> value.settings().url().equals(candidate.url()))) {
                    Selection selection = probeSelection(probe, settings, candidate,
                            Math.min(remaining, CANDIDATE_NANOS), cancelled, launcher, cleanup);
                    if (selection != null) accessible.add(selection);
                    break;
                }
            }
        }
        return best(settings, accessible);
    }

    private static Selection probeSelection(Path probe, StreamEngine.Settings settings, Candidate candidate,
            long budgetNanos, BooleanSupplier cancelled, ProcessLauncher launcher, Consumer<Process> cleanup) {
        int[] dimensions = probe(probe, settings, candidate, budgetNanos, cancelled, launcher, cleanup);
        return dimensions == null ? null : new Selection(
                new StreamEngine.Settings(candidate.url(), settings.transport(), settings.quality(), settings.recordingTimestamp()),
                dimensions[0], dimensions[1], candidate.profile());
    }

    static Selection best(StreamEngine.Settings settings, List<Selection> accessible) {
        if (accessible.isEmpty()) return original(settings);
        Comparator<Selection> byPixels = Comparator.comparingLong(value -> (long) value.width() * value.height());
        if (settings.quality() == VideoQuality.AUTO) return accessible.stream().max(byPixels).orElseThrow();
        VideoQuality quality = settings.quality();
        // Compare the detail available after applying the local ceiling: a 1080p
        // main stream scaled to 720p retains more detail than a native 360p substream.
        Comparator<Selection> byOutputPixels = Comparator
                .comparingDouble((Selection value) -> effectivePixels(quality, value))
                .thenComparingInt(value -> quality.includes(value.width(), value.height()) ? 1 : 0)
                .thenComparing(byPixels.reversed());
        return accessible.stream().max(byOutputPixels).orElseThrow();
    }

    private static double effectivePixels(VideoQuality quality, Selection value) {
        double scale = Math.min(1.0, Math.min((double) quality.maxWidth() / value.width(),
                (double) quality.maxHeight() / value.height()));
        return Math.round((long) value.width() * value.height() * scale * scale);
    }

    private static Selection original(StreamEngine.Settings settings) {
        String profile = candidates(settings).stream().filter(candidate -> candidate.url().equals(settings.url()))
                .map(Candidate::profile).findFirst().orElse("原始地址");
        return new Selection(settings, 0, 0, profile);
    }

    static List<Candidate> candidates(StreamEngine.Settings settings) {
        String original = settings.url();
        try {
            URI uri = URI.create(original);
            String path = uri.getRawPath();
            if (path == null || uri.getRawAuthority() == null) return List.of(new Candidate(original, "原始地址"));
            Matcher match = CHANNELS.matcher(path);
            if (!match.matches()) return List.of(new Candidate(original, "原始地址"));
            String prefix = uri.getScheme() + "://" + uri.getRawAuthority();
            String suffix = (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            String channel = match.group(1) + match.group(2);
            String trailing = match.group(4);
            return List.of(new Candidate(prefix + channel + "01" + trailing + suffix, "主码流"),
                    new Candidate(prefix + channel + "02" + trailing + suffix, "子码流"));
        } catch (IllegalArgumentException ignored) {
            // Non-standard vendor paths still get the original address verbatim.
            return List.of(new Candidate(original, "原始地址"));
        }
    }

    private static int[] probe(Path executable, StreamEngine.Settings settings, Candidate candidate,
            long budgetNanos, BooleanSupplier cancelled, ProcessLauncher launcher, Consumer<Process> cleanup) {
        Process process = null;
        try {
            if (cancelled.getAsBoolean()) return null;
            long deadline = System.nanoTime() + budgetNanos;
            process = launcher.launch(List.of(executable.toString(), "-v", "error", "-rtsp_transport", settings.transport(),
                    "-timeout", "1500000", "-analyzeduration", "800000", "-probesize", "65536", "-fpsprobesize", "0", "-threads", "1",
                    "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", candidate.url()));
            Process running = process;
            Thread.ofPlatform().daemon().name("camera-profile-errors").start(() -> {
                try (var input = running.getErrorStream()) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (IOException ignored) { }
            });
            while (process.isAlive()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                process.waitFor(Math.min(75, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
            }
            if (process.exitValue() != 0 || cancelled.getAsBoolean()) return null;
            String output = new String(process.getInputStream().readNBytes(1024), StandardCharsets.US_ASCII);
            Matcher dimensions = DIMENSIONS.matcher(output);
            if (!dimensions.find()) return null;
            int width = Integer.parseInt(dimensions.group(1));
            int height = Integer.parseInt(dimensions.group(2));
            return width > 0 && height > 0 && width <= 32768 && height <= 32768 ? new int[] { width, height } : null;
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                cleanup.accept(process);
            }
        }
    }

    private static void terminate(Process process) {
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(200, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }
}
