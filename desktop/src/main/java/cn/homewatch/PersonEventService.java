package cn.homewatch;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Analyses received local recording windows, including open files; images never leave the computer.
 * Results distinguish unanalysed clips from analysed clips without detections.
 */
public final class PersonEventService implements AutoCloseable {
    public enum Status { PENDING, ANALYZING, READY, FAILED, DISABLED }
    public enum Mode {
        BALANCED(3), ACCURATE(4);
        private final int fps;
        Mode(int fps) { this.fps = fps; }
        public int sampleRate() { return fps; }
    }
    public record DetectionSnapshot(Status status, List<PersonInterval> intervals, String detail) {
        public DetectionSnapshot { intervals = List.copyOf(intervals); }
    }

    private static final String VERSION = "person-class-region-confirmed-v5";
    private static final int FRAME_WIDTH = 960;
    private static final int FRAME_HEIGHT = 540;
    private static final Pattern FRAME_TIMESTAMP = Pattern.compile("\\bpts_time:([-+0-9.eE]+)");
    private static final Pattern FRAME_SIZE = Pattern.compile("\\bs:(\\d+)x(\\d+)\\b");
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(45);
    private static final long METADATA_RECHECK_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final Path root;
    private final Path assetsRoot;
    private static final java.util.concurrent.Semaphore ANALYSIS_SLOTS = new java.util.concurrent.Semaphore(2, true);
    private final Path ffmpeg;
    private final Path ffprobe;
    private final Path indexFile;
    private final Path settingsFile;
    private final Runnable listener;
    private final Object indexLock = new Object();
    private final Object persistenceLock = new Object();
    private final Map<Path, Cached> cache = new HashMap<>();
    private final Map<Path, Long> metadataChecks = new HashMap<>();
    private final Map<Path, Work> queued = new HashMap<>();
    private final Map<Path, Long> tokens = new HashMap<>();
    private final AtomicLong tokenSequence = new AtomicLong();
    private final PriorityBlockingQueue<Work> queue = new PriorityBlockingQueue<>(16,
            Comparator.comparing(Work::active).reversed().thenComparing(Work::start, Comparator.reverseOrder()));
    private final ExecutorService scanExecutor;
    private final ScheduledExecutorService watchdog;
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile List<RecordingEntry> latestEntries = List.of();
    private volatile Path activePath;
    private volatile Process activeProcess;
    private volatile long lastProgressNanos;
    private volatile boolean timedOut;
    private volatile String unavailable;
    private volatile PersonDetector detector;
    private volatile Mode mode = Mode.ACCURATE;
    private volatile List<PersonDetector.IgnoreRegion> ignoreRegions = List.of();
    private volatile String settingsLoadFailure;
    private long configurationGeneration;
    private final Thread worker;

    public PersonEventService(Path root, Runnable onChanged) { this(root, root, onChanged); }

    public PersonEventService(Path assetsRoot, Path root, Runnable onChanged) {
        this.assetsRoot = assetsRoot.toAbsolutePath().normalize();
        this.root = root.toAbsolutePath().normalize();
        ffmpeg = this.assetsRoot.resolve("tools/ffmpeg.exe");
        ffprobe = this.assetsRoot.resolve("tools/ffprobe.exe");
        indexFile = this.root.resolve("data/person-events.properties");
        settingsFile = this.root.resolve("config/person-detection.properties");
        listener = onChanged == null ? () -> {} : onChanged;
        scanExecutor = Executors.newSingleThreadExecutor(task -> daemon(task, "person-index-scan"));
        watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "person-analysis-watchdog"));
        // History can live on a slow external drive. Never validate it during Swing startup.
        scanExecutor.execute(() -> { loadSettings(); loadIndex(); notifyChanged(); });
        Path assets = this.assetsRoot.resolve("tools/person-detection");
        if (!Files.isRegularFile(assets.resolve("yolox_tiny.onnx"))
                || !Files.isRegularFile(assets.resolve("onnxruntime-1.20.0.jar"))) {
            unavailable = "未安装人形识别模型";
        }
        worker = daemon(this::runWorker, "person-analysis");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        watchdog.scheduleWithFixedDelay(() -> {
            Process process = activeProcess;
            if (process != null && process.isAlive() && lastProgressNanos > 0
                    && System.nanoTime() - lastProgressNanos > STALL_NANOS) {
                timedOut = true;
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    /** Supplies an immutable recording snapshot. Disk access and inference run outside Swing's thread. */
    public void refresh(List<RecordingEntry> recordings) {
        if (closed.get()) return;
        latestEntries = List.copyOf(recordings);
        if (scanPending.compareAndSet(false, true)) {
            try {
                scanExecutor.execute(() -> {
                    List<RecordingEntry> scanned;
                    do {
                        scanned = latestEntries;
                        reconcile(scanned);
                    } while (!closed.get() && scanned != latestEntries);
                    scanPending.set(false);
                    if (!closed.get() && scanned != latestEntries) refresh(latestEntries);
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) { scanPending.set(false); }
        }
    }

    /** Snapshot lookup is in-memory and suitable for drawing a timeline. */
    public DetectionSnapshot snapshot(Path recording) {
        Path key = normalize(recording);
        synchronized (indexLock) {
            Cached entry = cache.get(key);
            if (unavailable != null) return new DetectionSnapshot(Status.DISABLED, List.of(), unavailable);
            if (settingsLoadFailure != null) return new DetectionSnapshot(Status.DISABLED, List.of(), settingsLoadFailure);
            if (entry != null) return new DetectionSnapshot(entry.status, entry.intervals, entry.detail);
            return new DetectionSnapshot(Status.PENDING, List.of(), "等待人形识别");
        }
    }

    /** Combine this with playback protection when choosing completed recordings to delete. */
    public boolean isAnalyzing(Path recording) {
        return normalize(recording).equals(activePath);
    }

    public String availabilityDetail() {
        return unavailable != null ? unavailable : settingsLoadFailure != null ? settingsLoadFailure
                : "本地人形识别已启用，录制中持续更新，日夜画面复核与连续帧确认";
    }

    public Mode mode() { return mode; }
    public List<PersonDetector.IgnoreRegion> ignoreRegions() { return ignoreRegions; }

    public void setIgnoreRegions(List<PersonDetector.IgnoreRegion> regions) { setIgnoreRegions(regions, ignored -> {}); }

    /** Completion runs on the scan worker. A failed save leaves the applied configuration intact. */
    public void setIgnoreRegions(List<PersonDetector.IgnoreRegion> regions,
                                 java.util.function.Consumer<Throwable> completion) {
        List<PersonDetector.IgnoreRegion> requested = List.copyOf(regions);
        if (requested.size() > 8) throw new IllegalArgumentException("每个摄像头最多设置 8 个忽略区域");
        java.util.Objects.requireNonNull(completion);
        if (closed.get()) { completion.accept(new IOException("识别服务已关闭")); return; }
        try {
            scanExecutor.execute(() -> {
                Throwable failure = null;
                try {
                    if (closed.get()) throw new IOException("识别服务已关闭");
                    if (!requested.equals(ignoreRegions) || settingsLoadFailure != null) {
                        saveSettings(mode, requested, false, true);
                        synchronized (indexLock) { ignoreRegions = requested; settingsLoadFailure = null; configurationGeneration++; }
                        requeue(null);
                        try { saveIndex(); } catch (IOException indexFailure) { log("人员索引刷新保存失败，将自动重建", indexFailure); }
                    }
                } catch (Exception problem) { failure = problem; log("忽略区域保存失败", problem); }
                try { completion.accept(failure); } catch (RuntimeException ignored) {}
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            completion.accept(new IOException("识别服务已关闭", rejected));
        }
    }

    /** Changes the CPU / recall profile, invalidates previous results and queues fresh analysis. */
    public void setMode(Mode requested) {
        setMode(requested, ignored -> {});
    }

    public void setMode(Mode requested, java.util.function.Consumer<Throwable> completion) {
        java.util.Objects.requireNonNull(requested);
        java.util.Objects.requireNonNull(completion);
        if (closed.get()) { completion.accept(new IOException("识别服务已关闭")); return; }
        try {
            scanExecutor.execute(() -> {
                Throwable failure = null;
                try {
                    if (closed.get()) throw new IOException("识别服务已关闭");
                    if (mode != requested || settingsLoadFailure != null) {
                        saveSettings(requested, ignoreRegions, true, false);
                        synchronized (indexLock) { mode = requested; settingsLoadFailure = null; configurationGeneration++; }
                        requeue(null);
                    }
                } catch (Exception problem) { failure = problem; log("识别设置保存失败", problem); notifyChanged(); }
                try { completion.accept(failure); } catch (RuntimeException ignored) {}
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            completion.accept(new IOException("识别服务已关闭", rejected));
        }
    }

    /** Can be called while playing or analysing; it never waits on model inference or disk IO. */
    public void reanalyse() { scheduleScan(() -> requeue(null)); }
    public void reanalyse(Path path) { Path selected = normalize(path); scheduleScan(() -> requeue(selected)); }

    private void scheduleScan(Runnable action) {
        if (closed.get()) return;
        try { scanExecutor.execute(() -> { if (!closed.get()) action.run(); }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void requeue(Path selected) {
        synchronized (indexLock) {
            if (selected == null) {
                cache.clear(); metadataChecks.clear(); queued.clear(); tokens.clear(); queue.clear();
            } else {
                cache.remove(selected); metadataChecks.remove(selected); queued.remove(selected); tokens.remove(selected);
                queue.removeIf(task -> task.path.equals(selected));
            }
        }
        if (selected == null || selected.equals(activePath)) {
            Process process = activeProcess;
            if (process != null) process.destroy();
        }
        if (selected == null) reconcile(latestEntries);
        else {
            latestEntries.stream().filter(entry -> normalize(entry.path()).equals(selected))
                    .findFirst().ifPresent(entry -> reconcile(List.of(new RecordingEntry(selected, Instant.MAX,
                            entry.bytes(), entry.active(), entry.durationSeconds()))));
        }
        notifyChanged();
    }

    private void reconcile(List<RecordingEntry> entries) {
        if (closed.get() || unavailable != null || settingsLoadFailure != null) return;
        boolean changed = false;
        long now = System.nanoTime();
        for (RecordingEntry recording : entries) {
            Path path = normalize(recording.path());
            try {
                synchronized (indexLock) {
                    if (queued.containsKey(path) || path.equals(activePath)) continue;
                    Cached previous = cache.get(path);
                    long checked = metadataChecks.getOrDefault(path, 0L);
                    // The recorder snapshot supplies file size. Recheck unchanged completed
                    // clips once a minute, rather than stat every historical file every two seconds.
                    if (!recording.active() && previous != null && previous.complete && previous.signature.size == recording.bytes()
                            && now - checked < METADATA_RECHECK_NANOS) continue;
                    if (recording.active() && recording.durationSeconds() <= (previous == null ? 0 : previous.analyzedUntil) + 0.25) continue;
                    if (previous != null && previous.status == Status.FAILED && previous.signature.size == recording.bytes()
                            && now - checked < TimeUnit.SECONDS.toNanos(30)) continue;
                }
                Signature signature = signature(path);
                synchronized (indexLock) {
                    metadataChecks.put(path, now);
                    Cached previous = cache.get(path);
                    if (previous != null && previous.complete && previous.signature.equals(signature)) continue;
                    if (previous != null && (previous.complete || signature.size < previous.signature.size
                            || signature.modifiedMillis < previous.signature.modifiedMillis)) previous = null;
                    double from = previous == null ? 0 : Math.max(0, previous.analyzedUntil - 0.75);
                    Work task = new Work(path, signature, recording.start(), mode, tokenSequence.incrementAndGet(),
                            from, recording.durationSeconds(), recording.active(), ignoreRegions, configurationGeneration);
                    tokens.put(path, task.token);
                    queued.put(path, task);
                    cache.put(path, new Cached(signature, Status.PENDING,
                            previous == null ? List.of() : previous.intervals, "等待增量人形识别",
                            previous == null ? 0 : previous.analyzedUntil, false));
                    queue.offer(task);
                    changed = true;
                }
            } catch (IOException ignored) {
                // Retention may remove a file between taking the recording snapshot and scanning it.
            }
        }
        if (changed) notifyChanged();
    }

    private void runWorker() {
        try {
            while (!closed.get()) {
                Work task = queue.take();
                synchronized (indexLock) {
                    if (queued.get(task.path) != task) continue;
                    queued.remove(task.path);
                    Cached previous = cache.get(task.path);
                    cache.put(task.path, new Cached(task.signature, Status.ANALYZING,
                            previous == null ? List.of() : previous.intervals, "正在识别最新画面",
                            previous == null ? 0 : previous.analyzedUntil, false));
                    activePath = task.path;
                }
                notifyChanged();
                boolean analysisSlot = false;
                try {
                    ANALYSIS_SLOTS.acquire();
                    analysisSlot = true;
                    if (closed.get()) break;
                    if (!isCurrent(task)) continue;
                    if (!matches(task, signature(task.path))) {
                        invalidate(task);
                        continue;
                    }
                    if (detector == null) {
                        try { detector = SharedPersonDetector.acquire(assetsRoot); }
                        catch (Exception | LinkageError failure) {
                            unavailable = "人形识别模型无法启动，请检查本地运行库";
                            log("模型启动失败", failure);
                            notifyChanged();
                            return;
                        }
                    }
                    AnalysisResult result = analyse(task);
                    if (closed.get()) break;
                    if (!isCurrent(task)) continue;
                    Signature actual = signature(task.path);
                    if (!matches(task, actual)) {
                        invalidate(task);
                        continue;
                    }
                    synchronized (indexLock) {
                        if (!isCurrentLocked(task)) continue;
                        Cached previous = cache.get(task.path);
                        List<PersonInterval> intervals = join(previous == null ? List.of() : previous.intervals, result.intervals);
                        cache.put(task.path, new Cached(actual, Status.READY, intervals,
                                result.complete ? intervals.isEmpty() ? "已分析，未识别到人形" : "已分析，有人形片段"
                                        : "已分析收到的画面，持续更新人员事件", result.analyzedUntil, result.complete));
                    }
                    saveIndex();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    if (!closed.get() && isCurrent(task)) {
                        synchronized (indexLock) {
                            if (isCurrentLocked(task)) cache.put(task.path, new Cached(task.signature, Status.FAILED,
                                    cache.get(task.path) == null ? List.of() : cache.get(task.path).intervals,
                                    "这段录像的人形识别失败，稍后自动重试",
                                    cache.get(task.path) == null ? 0 : cache.get(task.path).analyzedUntil, false));
                        }
                        log("录像人形识别失败: " + task.path.getFileName(), failure);
                    }
                } finally {
                    if (analysisSlot) ANALYSIS_SLOTS.release();
                    stopOwnedProcess();
                    activePath = null;
                    notifyChanged();
                    refresh(latestEntries);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            stopOwnedProcess();
            PersonDetector current = detector;
            if (current != null) SharedPersonDetector.release(assetsRoot, current);
            activePath = null;
        }
    }

    private boolean isCurrent(Work task) {
        synchronized (indexLock) { return isCurrentLocked(task); }
    }

    private boolean isCurrentLocked(Work task) {
        Long current = tokens.get(task.path);
        return current != null && current == task.token && task.mode == mode && task.configuration == configurationGeneration;
    }

    private void invalidate(Work task) {
        synchronized (indexLock) {
            if (!isCurrentLocked(task)) return;
            Cached current = cache.get(task.path);
            if (current != null && current.signature.equals(task.signature)) cache.remove(task.path);
            metadataChecks.remove(task.path);
        }
        refresh(latestEntries);
    }

    private static boolean matches(Work task, Signature actual) {
        return task.active ? actual.size >= task.signature.size && actual.modifiedMillis >= task.signature.modifiedMillis
                : task.signature.equals(actual);
    }

    private AnalysisResult analyse(Work task) throws Exception {
        Path recording = task.path;
        double duration = task.toSeconds > 0 ? task.toSeconds : probeDuration(recording);
        double window = task.active ? 2.0 : 8.0;
        double end = duration > 0 ? Math.min(duration, task.fromSeconds + window) : task.fromSeconds + window;
        if (end <= task.fromSeconds) throw new IOException("当前录像窗口还没有可分析的画面");
        double intervalSeconds = 1.0 / task.mode.sampleRate();
        List<String> command = List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-threads", "2", "-ss", String.format(Locale.ROOT, "%.6f", task.fromSeconds), "-i", recording.toString(),
                "-t", String.format(Locale.ROOT, "%.6f", end - task.fromSeconds), "-an", "-sn", "-dn",
                "-vf", "select='isnan(prev_selected_t)+gte(t-prev_selected_t," + String.format(Locale.ROOT, "%.6f", intervalSeconds - 0.000001)
                        + ")',scale=960:540:force_original_aspect_ratio=decrease,showinfo,pad=960:540:0:0:color=0x727272",
                "-loglevel", "info", "-fps_mode", "passthrough", "-threads", "1", "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1");
        Process process = startOwnedProcess(command);
        BlockingQueue<FrameTimestamp> timestamps = new LinkedBlockingQueue<>();
        Thread errors = drainTimestamps(process.getErrorStream(), timestamps);
        int frameBytes = FRAME_WIDTH * FRAME_HEIGHT * 3;
        byte[] frame = new byte[frameBytes];
        PersonPresenceTracker tracker = new PersonPresenceTracker(intervalSeconds);
        PersonFrameReuse reuse = new PersonFrameReuse(FRAME_WIDTH, FRAME_HEIGHT);
        PersonDetector.DetectionResult priorDetection = null;
        int count = 0;
        double lastTime = 0;
        try (InputStream frames = new BufferedInputStream(process.getInputStream(), frameBytes * 2)) {
            while (!closed.get()) {
                int received = frames.readNBytes(frame, 0, frameBytes);
                if (received == 0) break;
                if (received != frameBytes) throw new IOException("录像帧数据不完整");
                FrameTimestamp time = timestamps.poll(5, TimeUnit.SECONDS);
                if (time == null) throw new IOException("录像帧时间戳缺失");
                if (!isCurrent(task)) throw new IOException("识别任务已被重新排队");
                lastProgressNanos = System.nanoTime();
                double seconds = task.fromSeconds + Math.max(0, time.seconds);
                boolean inferred = priorDetection == null || !reuse.mayReuse(frame, seconds, tracker.needsConfirmation());
                if (inferred) {
                    priorDetection = detector.detectPerson(frame, FRAME_WIDTH, FRAME_HEIGHT,
                            task.mode == Mode.ACCURATE ? PersonDetector.SearchMode.ACCURATE : PersonDetector.SearchMode.BALANCED);
                    priorDetection = PersonDetector.excludeRegions(priorDetection,
                            paddedRegions(task.ignored, time.width, time.height));
                    reuse.inferred(frame, seconds);
                }
                tracker.observe(seconds, priorDetection, inferred);
                lastTime = seconds;
                count++;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            }
        }
        if (closed.get()) throw new InterruptedException();
        if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("录像解码退出超时");
        if (timedOut) throw new IOException("录像解码超时");
        if (process.exitValue() != 0 && !task.active) throw new IOException("录像不能完整解码");
        errors.join(1000);
        if (count == 0) throw new IOException("录像没有可分析的视频帧");
        if (!(duration > 0)) duration = lastTime + intervalSeconds;
        activeProcess = null;
        // A brief crossing can occupy only one 4-fps sample. Confirm that candidate with nearby
        // real model frames instead of either colouring every isolated score or discarding it.
        for (PersonPresenceTracker.Candidate candidate : tracker.isolatedCandidates()) {
            if (closed.get()) throw new InterruptedException();
            if (!isCurrent(task)) throw new IOException("识别任务已被重新排队");
            tracker.addVerified(verifyContext(task, candidate, duration));
        }
        double analyzedUntil = Math.min(end, lastTime + intervalSeconds);
        return new AnalysisResult(tracker.finish(analyzedUntil), analyzedUntil,
                !task.active && duration > 0 && analyzedUntil >= duration - intervalSeconds - 0.03);
    }

    private static List<PersonInterval> join(List<PersonInterval> prior, List<PersonInterval> next) {
        List<PersonInterval> ordered = new ArrayList<>(prior); ordered.addAll(next);
        ordered.sort(Comparator.comparingDouble(PersonInterval::startSeconds));
        List<PersonInterval> merged = new ArrayList<>();
        for (PersonInterval interval : ordered) {
            if (!merged.isEmpty() && merged.getLast().endSeconds() >= interval.startSeconds()) {
                PersonInterval before = merged.removeLast();
                merged.add(new PersonInterval(before.startSeconds(), Math.max(before.endSeconds(), interval.endSeconds())));
            } else merged.add(interval);
        }
        return List.copyOf(merged);
    }

    private List<PersonInterval> verifyContext(Work task, PersonPresenceTracker.Candidate candidate,
            double duration) throws Exception {
        double start = Math.max(0, candidate.seconds() - 0.225);
        double length = Math.min(0.50, duration - start);
        if (length <= 0.04) return List.of();
        Process process = startOwnedProcess(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin",
                "-threads", "2", "-ss", String.format(Locale.ROOT, "%.6f", start), "-i", task.path.toString(),
                "-t", String.format(Locale.ROOT, "%.6f", length), "-an", "-sn", "-dn", "-vf",
                "select='isnan(prev_selected_t)+gte(t-prev_selected_t,0.064999)',scale=960:540:force_original_aspect_ratio=decrease,showinfo,pad=960:540:0:0:color=0x727272",
                "-loglevel", "info", "-fps_mode", "passthrough", "-frames:v", "9", "-threads", "1", "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1"));
        BlockingQueue<FrameTimestamp> timestamps = new LinkedBlockingQueue<>();
        Thread errors = drainTimestamps(process.getErrorStream(), timestamps);
        int bytes = FRAME_WIDTH * FRAME_HEIGHT * 3;
        byte[] frame = new byte[bytes];
        List<ContextFrame> contextFrames = new ArrayList<>();
        List<PersonInterval> verified = List.of();
        var original = new PersonDetector.DetectionResult(true, candidate.box().score(), 0,
                List.of(candidate.box()), candidate.monochrome());
        contextFrames.add(new ContextFrame(candidate.seconds(), original));
        try (InputStream frames = new BufferedInputStream(process.getInputStream(), bytes * 2)) {
            while (!closed.get() && isCurrent(task)) {
                int read = frames.readNBytes(frame, 0, bytes);
                if (read == 0) break;
                if (read != bytes) throw new IOException("复核视频帧不完整");
                FrameTimestamp relative = timestamps.poll(5, TimeUnit.SECONDS);
                if (relative == null) throw new IOException("复核帧时间戳缺失");
                lastProgressNanos = System.nanoTime();
                double seconds = start + relative.seconds;
                // Once independent nearby frames already confirm the candidate, drain the
                // decoder pipe without spending more model passes on the same short event.
                if (!verified.isEmpty()) continue;
                // A reread of the exact original timestamp is not independent temporal evidence.
                if (Math.abs(seconds - candidate.seconds()) < 0.035) continue;
                var detection = detector.detectPerson(frame, FRAME_WIDTH, FRAME_HEIGHT,
                        task.mode == Mode.ACCURATE ? PersonDetector.SearchMode.ACCURATE : PersonDetector.SearchMode.BALANCED);
                detection = PersonDetector.excludeRegions(detection,
                        paddedRegions(task.ignored, relative.width, relative.height));
                contextFrames.add(new ContextFrame(seconds, detection));
                verified = confirmedContext(contextFrames, duration);
            }
        } finally {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("人形复核解码进程未退出");
            }
            errors.join(500);
            activeProcess = null;
        }
        if (closed.get()) throw new InterruptedException();
        if (!isCurrent(task)) throw new IOException("识别任务已被重新排队");
        if (process.isAlive() || process.exitValue() != 0 || timedOut) throw new IOException("人形复核解码失败");
        return verified;
    }

    private static List<PersonInterval> confirmedContext(List<ContextFrame> observed, double duration) {
        List<ContextFrame> ordered = new ArrayList<>(observed);
        ordered.sort(Comparator.comparingDouble(ContextFrame::time));
        PersonPresenceTracker confirmation = new PersonPresenceTracker(1.0 / 15);
        ordered.forEach(frameResult -> confirmation.observe(frameResult.time, frameResult.detection, true));
        return confirmation.finish(duration);
    }

    /** Decode pads at the top-left; the editor's rectangles remain relative to actual camera pixels. */
    static List<PersonDetector.IgnoreRegion> paddedRegions(List<PersonDetector.IgnoreRegion> regions, int width, int height) {
        if (width <= 0 || height <= 0 || width > FRAME_WIDTH || height > FRAME_HEIGHT)
            throw new IllegalArgumentException("decoded content dimensions");
        if (regions.isEmpty() || width == FRAME_WIDTH && height == FRAME_HEIGHT) return regions;
        double scaleX = width / (double) FRAME_WIDTH, scaleY = height / (double) FRAME_HEIGHT;
        return regions.stream().map(region -> new PersonDetector.IgnoreRegion(region.x() * scaleX, region.y() * scaleY,
                region.width() * scaleX, region.height() * scaleY)).toList();
    }

    static List<PersonInterval> merge(List<Double> positives, double duration) {
        return merge(positives, duration, 0.25);
    }

    static List<PersonInterval> merge(List<Double> positives, double duration, double intervalSeconds) {
        if (positives.isEmpty()) return List.of();
        List<PersonInterval> intervals = new ArrayList<>();
        double start = positives.get(0), last = start;
        for (int i = 1; i < positives.size(); i++) {
            double next = positives.get(i);
            if (next - last <= intervalSeconds * 1.75) last = next;
            else {
                appendInterval(intervals, start, last, duration, intervalSeconds);
                start = last = next;
            }
        }
        appendInterval(intervals, start, last, duration, intervalSeconds);
        return List.copyOf(intervals);
    }

    private static void appendInterval(List<PersonInterval> intervals, double first, double last, double duration, double intervalSeconds) {
        double start = Math.max(0, first - intervalSeconds * 0.5);
        double end = Math.max(start, Math.min(duration, last + intervalSeconds));
        // Two separate detections can overlap after adding margins; keep the timeline non-overlapping.
        if (!intervals.isEmpty() && intervals.get(intervals.size() - 1).endSeconds() >= start) {
            PersonInterval prior = intervals.remove(intervals.size() - 1);
            start = prior.startSeconds();
            end = Math.max(end, prior.endSeconds());
        }
        intervals.add(new PersonInterval(start, end));
    }

    private double probeDuration(Path recording) throws Exception {
        Process process = startOwnedProcess(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
                "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", recording.toString()));
        drainErrors(process.getErrorStream());
        // Wait first: ffprobe's duration output is tiny and cannot fill its pipe.
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            stopOwnedProcess();
            throw new IOException("录像时长读取超时");
        }
        if (process.exitValue() != 0) throw new IOException("录像时长读取失败");
        String text = new String(process.getInputStream().readNBytes(256), StandardCharsets.UTF_8).trim();
        activeProcess = null;
        try {
            double duration = Double.parseDouble(text);
            if (Double.isFinite(duration) && duration > 0) return duration;
        } catch (NumberFormatException unknown) {}
        return RecordingDurationProbe.receivedDuration(ffprobe, recording, closed::get);
    }

    private Process startOwnedProcess(List<String> command) throws IOException {
        if (closed.get()) throw new IOException("分析服务已关闭");
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        activeProcess = process;
        lastProgressNanos = System.nanoTime();
        timedOut = false;
        if (closed.get()) process.destroy();
        return process;
    }

    private Thread drainErrors(InputStream input) {
        Thread reader = daemon(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = lines.readLine()) != null) {
                    if (count++ < 10) log("解码: " + line, null);
                }
            } catch (IOException ignored) {}
        }, "person-decoder-errors");
        reader.start();
        return reader;
    }

    private Thread drainTimestamps(InputStream input, BlockingQueue<FrameTimestamp> timestamps) {
        Thread reader = daemon(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                int errors = 0;
                while ((line = lines.readLine()) != null) {
                    if (line.contains("showinfo") && line.contains("pts_time:")) {
                        Matcher match = FRAME_TIMESTAMP.matcher(line);
                        Matcher dimensions = FRAME_SIZE.matcher(line);
                        if (match.find() && dimensions.find()) {
                            try {
                                double timestamp = Double.parseDouble(match.group(1));
                                int width=Integer.parseInt(dimensions.group(1)),height=Integer.parseInt(dimensions.group(2));
                                if (Double.isFinite(timestamp) && width > 0 && width <= FRAME_WIDTH && height > 0 && height <= FRAME_HEIGHT)
                                    timestamps.offer(new FrameTimestamp(timestamp, width, height));
                            } catch (NumberFormatException ignored) {}
                        }
                    } else if ((line.contains("Error") || line.contains("Invalid")) && errors++ < 5) {
                        log("解码: " + line, null);
                    }
                }
            } catch (IOException ignored) {}
        }, "person-frame-timestamps");
        reader.start();
        return reader;
    }

    private void stopOwnedProcess() {
        Process process = activeProcess;
        activeProcess = null;
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void loadIndex() {
        try {
            if (!Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS)) return;
            Properties values = new Properties();
            try (Reader source = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) { values.load(source); }
            if (!VERSION.equals(values.getProperty("version"))) return;
            if (!mode.name().equals(values.getProperty("mode"))) return;
            if (!encodedRegions(ignoreRegions).equals(values.getProperty("ignoreRegions", ""))) return;
            for (String name : values.stringPropertyNames()) {
                if (!name.startsWith("clip.") || !name.endsWith(".path")) continue;
                String prefix = name.substring(0, name.length() - 4);
                try {
                    Path path = normalize(Path.of(values.getProperty(name)));
                    Signature signature = new Signature(Long.parseLong(values.getProperty(prefix + "size")),
                            Long.parseLong(values.getProperty(prefix + "mtime")));
                    boolean complete = Boolean.parseBoolean(values.getProperty(prefix + "complete", "true"));
                    Signature actual = signature(path);
                    if (complete ? !signature.equals(actual) : actual.size < signature.size || actual.modifiedMillis < signature.modifiedMillis) continue;
                    double analyzedUntil = Double.parseDouble(values.getProperty(prefix + "analyzedUntil", "0"));
                    if (!Double.isFinite(analyzedUntil) || analyzedUntil < 0 || analyzedUntil > 366 * 86400.0) continue;
                    List<PersonInterval> intervals = new ArrayList<>();
                    String encoded = values.getProperty(prefix + "intervals", "");
                    if (!encoded.isEmpty()) for (String interval : encoded.split(";")) {
                        String[] limits = interval.split(",");
                        if (limits.length != 2) throw new IllegalArgumentException();
                        intervals.add(new PersonInterval(Double.parseDouble(limits[0]), Double.parseDouble(limits[1])));
                    }
                    synchronized (indexLock) {
                        cache.put(path, new Cached(signature, Status.READY, List.copyOf(intervals),
                                complete ? intervals.isEmpty() ? "已分析，未识别到人形" : "已分析，有人形片段" : "已保存人员事件，继续分析新画面",
                                analyzedUntil, complete));
                        metadataChecks.put(path, System.nanoTime());
                    }
                } catch (RuntimeException | IOException malformed) { /* Ignore stale / invalid per-file entries. */ }
            }
        } catch (IOException failure) { log("人形索引读取失败", failure); }
    }

    /** Stores READY results only; disk writes never hold the timeline's snapshot lock. */
    private void saveIndex() throws IOException {
        synchronized (persistenceLock) { saveIndexSerially(); }
    }

    private void saveIndexSerially() throws IOException {
        Map<Path, Cached> completed;
        Mode profile;
        List<PersonDetector.IgnoreRegion> regions;
        synchronized (indexLock) { completed = Map.copyOf(cache); profile = mode; regions = ignoreRegions; }
        Files.createDirectories(indexFile.getParent());
        if (Files.isSymbolicLink(indexFile)) throw new IOException("人形索引不能是符号链接");
        Properties values = new Properties();
        values.setProperty("version", VERSION);
        values.setProperty("mode", profile.name());
        values.setProperty("ignoreRegions", encodedRegions(regions));
        for (Map.Entry<Path, Cached> item : completed.entrySet()) {
            Cached data = item.getValue();
            if (data.status != Status.READY && data.analyzedUntil <= 0) continue;
            String prefix = "clip." + digest(item.getKey().toString()) + ".";
            values.setProperty(prefix + "path", item.getKey().toString());
            values.setProperty(prefix + "size", Long.toString(data.signature.size));
            values.setProperty(prefix + "mtime", Long.toString(data.signature.modifiedMillis));
            values.setProperty(prefix + "complete", Boolean.toString(data.complete));
            values.setProperty(prefix + "analyzedUntil", Double.toString(data.analyzedUntil));
            StringBuilder encoded = new StringBuilder();
            for (PersonInterval interval : data.intervals) {
                if (!encoded.isEmpty()) encoded.append(';');
                encoded.append(String.format(Locale.ROOT, "%.3f,%.3f", interval.startSeconds(), interval.endSeconds()));
            }
            values.setProperty(prefix + "intervals", encoded.toString());
        }
        Path temporary = indexFile.resolveSibling(indexFile.getFileName() + ".part");
        if (Files.isSymbolicLink(temporary)) throw new IOException("人形索引临时文件不能是符号链接");
        try (Writer target = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(target, "Offline person detections; offsets are seconds within each recording");
        }
        try {
            Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Signature signature(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("录像不是普通本地文件");
        }
        return new Signature(Files.size(path), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
    }

    private void loadSettings() {
        try {
            if (!Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) return;
            Properties settings = new Properties();
            try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) { settings.load(reader); }
            Exception invalidField = null;
            try { mode = Mode.valueOf(settings.getProperty("mode", "ACCURATE")); }
            catch (IllegalArgumentException invalid) { invalidField = invalid; }
            try { ignoreRegions = decodedRegions(settings.getProperty("ignoreRegions", "")); }
            catch (IllegalArgumentException invalid) { invalidField = invalid; }
            if (invalidField != null) throw new IOException("识别设置字段格式无效", invalidField);
        } catch (IOException | IllegalArgumentException invalid) {
            settingsLoadFailure = "识别设置无法读取，原文件已保留，请检查识别设置";
            log("识别设置无法读取，原文件已保留", invalid);
        }
    }

    private void saveSettings(Mode profile, List<PersonDetector.IgnoreRegion> regions,
                              boolean replaceMode, boolean replaceRegions) throws IOException {
        Properties settings = new Properties();
        if (Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("识别设置必须是普通文件");
            // Merge unknown settings instead of overwriting a user's configuration.
            try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) { settings.load(reader); }
            catch (IllegalArgumentException malformed) { throw new IOException("原识别设置格式损坏，已保留原文件", malformed); }
            try {
                if (!replaceMode) Mode.valueOf(settings.getProperty("mode", "ACCURATE"));
                if (!replaceRegions) decodedRegions(settings.getProperty("ignoreRegions", ""));
            } catch (IllegalArgumentException malformed) {
                throw new IOException("原识别设置的其他字段无效，已保留原文件", malformed);
            }
        }
        Files.createDirectories(settingsFile.getParent());
        settings.setProperty("mode", profile.name());
        settings.setProperty("ignoreRegions", encodedRegions(regions));
        Path temporary = settingsFile.resolveSibling(settingsFile.getFileName() + ".part");
        if (Files.isSymbolicLink(temporary)) throw new IOException("识别设置临时文件不能是符号链接");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                settings.store(writer, "Offline person recognition profile");
            }
            try { Files.move(temporary, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String encodedRegions(List<PersonDetector.IgnoreRegion> regions) {
        return regions.stream().map(region -> region.x() + "," + region.y() + "," + region.width() + "," + region.height())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static List<PersonDetector.IgnoreRegion> decodedRegions(String encoded) {
        if (encoded.isBlank()) return List.of();
        List<PersonDetector.IgnoreRegion> regions = new ArrayList<>();
        for (String rectangle : encoded.split(";", -1)) {
            if (regions.size() >= 8) throw new IllegalArgumentException("忽略区域数量无效");
            String[] parts = rectangle.split(",", -1);
            if (parts.length != 4) throw new IllegalArgumentException("忽略区域格式无效");
            regions.add(new PersonDetector.IgnoreRegion(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
        }
        return List.copyOf(regions);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static Path normalize(Path path) { return path.toAbsolutePath().normalize(); }
    private void notifyChanged() { try { listener.run(); } catch (RuntimeException ignored) {} }
    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private synchronized void log(String message, Throwable failure) {
        try {
            Path log = root.resolve("data/logs/person-detection.log");
            Files.createDirectories(log.getParent());
            String text = Instant.now() + " " + message;
            if (failure != null) text += " (" + failure.getClass().getSimpleName() + ") " + failure.getMessage();
            Files.writeString(log, text + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        queue.clear();
        scanExecutor.shutdownNow();
        watchdog.shutdownNow();
        Process process = activeProcess;
        if (process != null) process.destroy();
        worker.interrupt();
        // Preserve completed results even when close happens between inference and its disk write.
        // The application closes on a background thread; an optional Swing caller stays responsive.
        Runnable flush = () -> {
            try { if (Thread.currentThread() != worker) worker.join(3000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { saveIndex(); }
            catch (IOException failure) { log("人形索引关闭时保存失败", failure); }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) daemon(flush, "person-index-close").start();
        else flush.run();
    }

    private record Signature(long size, long modifiedMillis) {}
    private record Work(Path path, Signature signature, Instant start, Mode mode, long token,
                        double fromSeconds, double toSeconds, boolean active,
                        List<PersonDetector.IgnoreRegion> ignored, long configuration) {}
    private record Cached(Signature signature, Status status, List<PersonInterval> intervals, String detail,
                          double analyzedUntil, boolean complete) {
        Cached(Signature signature, Status status, List<PersonInterval> intervals, String detail) {
            this(signature, status, intervals, detail, 0, status == Status.READY);
        }
    }
    private record AnalysisResult(List<PersonInterval> intervals, double analyzedUntil, boolean complete) {}
    private record ContextFrame(double time, PersonDetector.DetectionResult detection) {}
    private record FrameTimestamp(double seconds, int width, int height) {}
}
