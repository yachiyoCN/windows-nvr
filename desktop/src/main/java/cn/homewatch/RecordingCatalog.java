package cn.homewatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background duration index; open recordings use the recorder's flushed media clock. */
public final class RecordingCatalog implements AutoCloseable {
    private record Stamp(long bytes, long modified) {}
    private record Cached(Stamp stamp, double duration) {}
    private record Item(RecordingEntry entry, Stamp stamp, long checkedNanos) {}
    private record Failure(Stamp stamp, long attemptedNanos) {}
    private record Job(Path path, Instant start, Stamp stamp) {}
    private static final long PROBE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long METADATA_RECHECK_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Path ffprobe;
    private final Path cacheFile;
    private final Runnable onChanged;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "recording-catalog"));
    private final ExecutorService prober = Executors.newSingleThreadExecutor(r -> daemon(r, "recording-duration-probe"));
    // Only the catalog worker changes these maps/queue.
    private final Map<Path, Cached> cache = new HashMap<>();
    private final Map<Path, Failure> failures = new HashMap<>();
    private Map<Path, Item> items = Map.of();
    private final PriorityQueue<Job> pending = new PriorityQueue<>(Comparator.comparing(Job::start).reversed());
    private volatile List<TimelineClip> snapshot = List.of();
    private volatile Process currentProcess;
    private boolean probing;
    private boolean pumpScheduled;
    private Job runningJob;
    private long lastProbeNanos;

    public RecordingCatalog(Path root, Runnable onChanged) { this(root, root, onChanged); }

    public RecordingCatalog(Path assetsRoot, Path root, Runnable onChanged) {
        Path project = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        ffprobe = assetsRoot.toAbsolutePath().normalize().resolve("tools/ffprobe.exe");
        cacheFile = project.resolve("data/recording-times.properties");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
        execute(this::readCache);
    }

    /** Nonblocking: metadata work and process startup are always performed in the background. */
    public void refresh(List<RecordingEntry> entries) {
        if (closed.get()) return;
        List<RecordingEntry> copy = List.copyOf(Objects.requireNonNull(entries, "entries"));
        execute(() -> update(copy));
    }

    public List<TimelineClip> snapshot() { return snapshot; }

    private void update(List<RecordingEntry> entries) {
        if (closed.get()) return;
        Map<Path, Item> next = new LinkedHashMap<>();
        long metadataNow = System.nanoTime();
        for (RecordingEntry entry : entries) {
            if (entry == null || entry.path() == null || entry.start() == null) continue;
            Path path = entry.path().toAbsolutePath().normalize();
            Item previous = items.get(path);
            // Closed app recordings do not change between scans. The supplied size catches
            // growth immediately; a periodic mtime validation also catches external edits.
            if (!entry.active() && previous != null && !previous.entry().active()
                    && previous.stamp().bytes() == entry.bytes()
                    && metadataNow - previous.checkedNanos() < METADATA_RECHECK_NANOS) {
                next.put(path, new Item(new RecordingEntry(path, entry.start(), entry.bytes(), false, entry.durationSeconds()),
                        previous.stamp(), previous.checkedNanos()));
                continue;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) continue;
                RecordingEntry normalized = new RecordingEntry(path, entry.start(), attributes.size(), entry.active(), entry.durationSeconds());
                next.put(path, new Item(normalized, new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis()), metadataNow));
            } catch (IOException | SecurityException ignored) {}
        }
        items = next;
        boolean cacheChanged = cache.keySet().removeIf(path -> !items.containsKey(path));
        failures.keySet().retainAll(items.keySet());
        pending.clear();
        long now = System.nanoTime();
        for (Map.Entry<Path, Item> entry : items.entrySet()) {
            Path path = entry.getKey();
            Item item = entry.getValue();
            if (item.entry().active()) continue;
            Cached known = cache.get(path);
            if (known != null && !known.stamp().equals(item.stamp())) {
                cache.remove(path);
                cacheChanged = true;
                known = null;
            }
            if (known != null) continue;
            if (runningJob != null && runningJob.path().equals(path) && runningJob.stamp().equals(item.stamp())) continue;
            Failure failure = failures.get(path);
            if (failure != null && failure.stamp().equals(item.stamp()) && now - failure.attemptedNanos() < FAILURE_RETRY_NANOS) continue;
            pending.add(new Job(path, item.entry().start(), item.stamp()));
        }
        publish();
        if (cacheChanged) writeCache();
        schedulePump();
    }

    private void publish() {
        if (closed.get()) return;
        ArrayList<TimelineClip> clips = new ArrayList<>();
        for (Map.Entry<Path, Item> entry : items.entrySet()) {
            Item item = entry.getValue();
            RecordingEntry recording = item.entry();
            if (recording.active()) {
                if (recording.durationSeconds() > 0)
                    clips.add(new TimelineClip(entry.getKey(), recording.start(), recording.durationSeconds(), true));
            } else {
                Cached known = cache.get(entry.getKey());
                if (known != null && known.stamp().equals(item.stamp()))
                    clips.add(new TimelineClip(entry.getKey(), recording.start(), known.duration(), false));
                else if (recording.durationSeconds() > 0)
                    clips.add(new TimelineClip(entry.getKey(), recording.start(), recording.durationSeconds(), false));
            }
        }
        clips.sort(Comparator.comparing(TimelineClip::start).thenComparing(clip -> clip.path().toString()));
        List<TimelineClip> next = List.copyOf(clips);
        if (!next.equals(snapshot)) {
            snapshot = next;
            try { onChanged.run(); } catch (RuntimeException ignored) {}
        }
    }

    private void schedulePump() {
        if (closed.get() || probing || pending.isEmpty() || pumpScheduled) return;
        pumpScheduled = true;
        long delay = Math.max(0, PROBE_INTERVAL_NANOS - (System.nanoTime() - lastProbeNanos));
        worker.schedule(this::pump, delay, TimeUnit.NANOSECONDS);
    }

    private void pump() {
        pumpScheduled = false;
        if (closed.get() || probing) return;
        Job next;
        while ((next = pending.poll()) != null) {
            Item item = items.get(next.path());
            if (item == null || item.entry().active() || !item.stamp().equals(next.stamp()) || cache.containsKey(next.path())) continue;
            Job job = next;
            probing = true;
            runningJob = job;
            lastProbeNanos = System.nanoTime();
            try {
                prober.execute(() -> {
                    double duration = probe(job);
                    execute(() -> completed(job, duration));
                });
            } catch (RejectedExecutionException ignored) {}
            break;
        }
    }

    private void completed(Job job, double duration) {
        probing = false;
        runningJob = null;
        if (closed.get()) return;
        Item item = items.get(job.path());
        if (item != null && !item.entry().active() && item.stamp().equals(job.stamp())) {
            Stamp actual = stamp(job.path());
            if (job.stamp().equals(actual)) {
                if (Double.isFinite(duration) && duration > 0) {
                    cache.put(job.path(), new Cached(job.stamp(), duration));
                    failures.remove(job.path());
                    writeCache();
                    publish();
                } else failures.put(job.path(), new Failure(job.stamp(), System.nanoTime()));
            }
        }
        schedulePump();
    }

    private double probe(Job job) {
        if (closed.get() || !Files.isRegularFile(ffprobe)) return Double.NaN;
        Process process = null;
        Thread drainer = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            process = new ProcessBuilder(ffprobe.toString(), "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", job.path().toString()).redirectErrorStream(true).start();
            currentProcess = process;
            if (closed.get()) process.destroyForcibly();
            InputStream pipe = process.getInputStream();
            drainer = daemon(() -> drain(pipe, output), "recording-probe-output");
            drainer.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return Double.NaN;
            }
            drainer.join(1000);
            if (closed.get() || drainer.isAlive()) return Double.NaN;
            for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
                try {
                    double seconds = Double.parseDouble(line.strip());
                    if (Double.isFinite(seconds) && seconds > 0 && seconds < 366 * 24 * 3600.0) return seconds;
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
                if (currentProcess == process) currentProcess = null;
            }
            if (drainer != null && drainer.isAlive()) drainer.interrupt();
        }
        double received = RecordingDurationProbe.receivedDuration(ffprobe, job.path(), closed::get);
        return received > 0 ? received : Double.NaN;
    }

    private static void drain(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4096];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int remaining = 8192 - output.size();
                if (remaining > 0) output.write(buffer, 0, Math.min(count, remaining));
            }
        } catch (IOException ignored) {}
    }

    private void readCache() {
        if (closed.get() || !Files.isRegularFile(cacheFile)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(cacheFile)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                if (!key.endsWith(".path")) continue;
                String prefix = key.substring(0, key.length() - 5);
                try {
                    Path path = Path.of(properties.getProperty(key)).toAbsolutePath().normalize();
                    long bytes = Long.parseLong(properties.getProperty(prefix + ".bytes"));
                    long modified = Long.parseLong(properties.getProperty(prefix + ".modified"));
                    double duration = Double.parseDouble(properties.getProperty(prefix + ".duration"));
                    if (bytes >= 0 && Double.isFinite(duration) && duration > 0 && duration < 366 * 24 * 3600.0)
                        cache.put(path, new Cached(new Stamp(bytes, modified), duration));
                } catch (RuntimeException ignored) {}
            }
        } catch (IOException | IllegalArgumentException ignored) {}
    }

    private void writeCache() {
        if (closed.get()) return;
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        for (Map.Entry<Path, Cached> entry : cache.entrySet()) {
            String key = digest(entry.getKey().toString());
            Cached value = entry.getValue();
            properties.setProperty(key + ".path", entry.getKey().toString());
            properties.setProperty(key + ".bytes", Long.toString(value.stamp().bytes()));
            properties.setProperty(key + ".modified", Long.toString(value.stamp().modified()));
            properties.setProperty(key + ".duration", Double.toString(value.duration()));
        }
        Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(cacheFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "Verified recording durations"); }
            try { Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException | SecurityException ignored) {}
    }

    private static Stamp stamp(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile() ? new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis()) : null;
        } catch (IOException | SecurityException ignored) { return null; }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void execute(Runnable action) {
        if (closed.get()) return;
        try { worker.execute(action); } catch (RejectedExecutionException ignored) {}
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow();
        prober.shutdownNow();
        Process process = currentProcess;
        if (process != null) process.destroyForcibly();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) {}
    }
}
