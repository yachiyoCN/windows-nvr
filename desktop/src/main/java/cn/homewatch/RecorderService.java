package cn.homewatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** An independent, continuously segmented recorder with a conservative ownership ledger. */
public final class RecorderService implements NvrStorage, AutoCloseable {
    public interface Events {
        void onState(boolean recording, String detail);
        void onChanged();
        void onNotice(String message, boolean error);
        default boolean isPlaybackInUse(Path path) { return false; }
    }

    private static final long MIN_FREE = 500L * 1024 * 1024;
    private static final Pattern OWN_NAME = Pattern.compile(
            "HW_\\d{8}_\\d{6}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_\\d{5,}\\.mkv");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final Path root;
    private final Path assetsRoot;
    private final Path ffmpeg;
    private final Path indexFile;
    private final Path storageGuard;
    private final Events events;
    private final SharedStorageService sharedStorage;
    private final Object lifecycle = new Object();
    private final Object indexLock = new Object();
    private final Object applyLock = new Object();
    private final Map<String, Storage> storages = new LinkedHashMap<>();
    private final Map<String, SessionInfo> sessions = new LinkedHashMap<>();
    private final Map<String, OwnedFile> files = new LinkedHashMap<>();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final Set<String> preparingSessions = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    private final Thread supervisor;
    private volatile RecorderSettings settings;
    private volatile List<RecordingEntry> snapshot = List.of();
    private volatile long indexedBytes;
    private volatile boolean requested;
    private volatile boolean recording;
    private volatile boolean closed;
    private volatile boolean applying;
    private volatile boolean indexHealthy = true;
    private volatile RunningSession current;
    private StreamEngine.Settings camera;
    private long generation;
    private String lastState = "";
    private volatile String lastStateDetail = "录像已停止";
    private long lastIndexWriteNanos;
    private long lastNoticeMillis;
    private String lastNotice = "";

    public RecorderService(Path root, Events events) { this(root, root, events); }

    public RecorderService(Path assetsRoot, Path root, Events events) { this(assetsRoot, root, events, null); }

    public RecorderService(Path assetsRoot, Path root, Events events, SharedStorageService sharedStorage) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.assetsRoot = Objects.requireNonNull(assetsRoot).toAbsolutePath().normalize();
        this.events = Objects.requireNonNull(events);
        this.sharedStorage = sharedStorage;
        ffmpeg = assetsRoot.toAbsolutePath().normalize().resolve("tools/ffmpeg.exe");
        indexFile = this.root.resolve("data/recording-index.properties");
        storageGuard = assetsRoot.toAbsolutePath().normalize().resolve("tools/StorageGuard.exe");
        settings = RecorderSettings.defaults(this.root);
        try { settings = sharedStorage == null ? RecorderSettings.load(this.root) : sharedStorage.settings(); }
        catch (IOException invalid) { notice("原录像设置无法读取，已使用默认设置，请检查后重新保存。", true); }
        try {
            loadIndex();
            synchronized (indexLock) {
                // The former process is gone after application restart. Its last segment is now closed.
                for (SessionInfo info : List.copyOf(sessions.values())) scanSession(info, true);
                for (OwnedFile owned : files.values()) owned.closed = true;
                pruneEmptySessions();
                if (Files.exists(indexFile)) saveIndex();
                updateSnapshot();
            }
        } catch (IOException | RuntimeException failure) {
            indexHealthy = false;
            notice("录像索引无法读取，已暂停自动清理和录制。请保留 data 下的索引文件并检查目录权限。", true);
        }
        scheduler = sharedStorage == null ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "recorder-storage");
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (scheduler != null) {
            scheduler.scheduleWithFixedDelay(() -> safely(this::refresh), 2, 2, TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(() -> safely(this::cleanupNow), 30, 30, TimeUnit.SECONDS);
        }
        supervisor = Thread.ofVirtual().name("recorder-supervisor").start(this::supervise);
        if (sharedStorage != null) {
            try { sharedStorage.register(this); } catch (RuntimeException failure) { close(); throw failure; }
        }
    }

    public RecorderSettings settings() { return sharedStorage == null ? settings : sharedStorage.settings(); }
    public boolean isRecording() { return recording; }
    public boolean isRequested() { return requested; }
    public String stateDetail() { return lastStateDetail; }
    public List<RecordingEntry> listRecordings() { return snapshot; }
    public long usedBytes() { return indexedBytes; }

    /** Invoke from a background thread: the old segment is finalized before changing directories. */
    public void applySettings(RecorderSettings next) throws IOException {
        Objects.requireNonNull(next);
        if (sharedStorage != null) { sharedStorage.applySettings(next); return; }
        synchronized (applyLock) {
            validateDirectory(next.resolvedDirectory(root));
            synchronized (lifecycle) {
                if (closed) throw new IOException("录像机已关闭。");
                applying = true;
                generation++;
                lifecycle.notifyAll();
            }
            try {
                synchronized (lifecycle) {
                    while (current != null && !closed) {
                        try { lifecycle.wait(500); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("录像设置操作已中断。", interrupted);
                        }
                    }
                    if (closed) throw new IOException("录像机已关闭。");
                }
                next.save(root);
                settings = new RecorderSettings(next.resolvedDirectory(root).toString(), next.maxBytes(),
                        next.keepDays(), next.segmentMinutes(), next.autoRecord());
            } finally {
                synchronized (lifecycle) { applying = false; lifecycle.notifyAll(); }
            }
            cleanupNow();
            changed();
        }
    }

    public void start(StreamEngine.Settings camera) {
        Objects.requireNonNull(camera);
        if (camera.url().isBlank() || !(camera.url().regionMatches(true, 0, "rtsp://", 0, 7)
                || camera.url().regionMatches(true, 0, "rtsps://", 0, 8)
                || camera.url().regionMatches(true, 0, "https://", 0, 8))) {
            notice("请先在设置中填写有效的 RTSP、RTSPS 或 HTTPS 视频地址。", true);
            return;
        }
        synchronized (lifecycle) {
            if (closed) return;
            if (requested && camera.equals(this.camera)) return;
            this.camera = camera;
            requested = true;
            generation++;
            lifecycle.notifyAll();
        }
        state(false, "正在连接录像…");
    }

    /** Stops only recording. The live preview is unaffected. */
    public void stop() {
        synchronized (lifecycle) {
            requested = false;
            generation++;
            lifecycle.notifyAll();
        }
        state(false, current == null ? "录像已停止" : "正在保存最后一段录像…");
    }

    public void refresh() {
        if (closed) return;
        boolean enforcePolicy = false;
        synchronized (indexLock) {
            if (!indexHealthy) return;
            try {
                RunningSession running = current;
                int filesBefore = files.size();
                long completedBefore = files.values().stream().filter(file -> file.closed).count();
                boolean modified = running != null && scanSession(running.info, running.finished);
                enforcePolicy = files.values().stream().filter(file -> file.closed).count() != completedBefore;
                if (modified && (files.size() != filesBefore || enforcePolicy
                        || System.nanoTime() - lastIndexWriteNanos >= TimeUnit.SECONDS.toNanos(10))) saveIndex();
                updateSnapshot();
            } catch (IOException failure) {
                throttledNotice("录像目录或索引无法写入，正在检查保存状态。", true);
            }
        }
        // Enforce immediately at a segment boundary, keeping the quota overshoot to the open segment.
        if (enforcePolicy) {
            if (sharedStorage != null) sharedStorage.requestCleanup(); else cleanupNow();
        }
        changed();
    }

    /** Deletes only ledger-owned, closed files; unrelated and active files are never candidates. */
    public void cleanupNow() {
        if (sharedStorage != null) { sharedStorage.cleanupNow(); return; }
        if (closed || !indexHealthy) return;
        synchronized (indexLock) {
            try {
                RunningSession running = current;
                if (running != null) scanSession(running.info, running.finished);
                updateSnapshot();
                RecorderSettings policy = settings;
                Instant cutoff = Instant.now().minusSeconds(policy.keepDays() * 86400L);
                List<OwnedFile> candidates = files.values().stream()
                        .filter(owned -> owned.closed && !playbackInUse(owned.path))
                        .sorted(Comparator.comparing(owned -> owned.start)).toList();
                long ownedBytes = indexedBytes;
                Path currentDirectory = policy.resolvedDirectory(root);
                long available = freeBytes(currentDirectory);
                boolean deletedAny = false;
                for (OwnedFile owned : candidates) {
                    boolean expired = owned.start.isBefore(cutoff);
                    boolean exceedsLimit = ownedBytes > policy.maxBytes();
                    boolean storageFull = available < MIN_FREE
                            && sameFileStore(owned.path, currentDirectory);
                    if (!expired && !exceedsLimit && available >= MIN_FREE) break;
                    if (!expired && !exceedsLimit && !storageFull) continue;
                    // Verify again immediately before deleting; delete the single leaf only.
                    if (!isOwnedRegularFile(owned) || playbackInUse(owned.path)) continue;
                    long size = Files.size(owned.path);
                    try {
                        Files.delete(owned.path);
                        files.remove(owned.id);
                        deletedAny = true;
                        ownedBytes = Math.max(0, ownedBytes - size);
                        if (available < MIN_FREE) available = freeBytes(currentDirectory);
                    } catch (IOException locked) {
                        throttledNotice("部分录像正在播放或被占用，清理将在稍后重试。", false);
                    }
                }
                pruneEmptySessions();
                saveIndex();
                if (deletedAny) updateSnapshot();
            } catch (IOException failure) {
                throttledNotice("无法清理录像，请检查保存目录是否可访问。", true);
            }
        }
        changed();
    }

    private void supervise() {
        while (!closed) {
            StreamEngine.Settings source;
            RecorderSettings policy;
            long token;
            synchronized (lifecycle) {
                if (!requested || applying) { waitLifecycle(500); continue; }
                source = camera;
                policy = settings();
                token = generation;
            }
            RunningSession running = null;
            long retrySeconds = source.isHttpsVideo() ? 30 : 3;
            String reason = "连接中断，" + retrySeconds + " 秒后自动重试…";
            try {
                if (!indexHealthy) throw new IOException("索引不可用");
                if (!Files.isRegularFile(ffmpeg)) throw new IOException("录像组件不存在");
                long attemptToken = token;
                StreamProfileSelector.Selection profile = StreamProfileSelector.resolve(assetsRoot, source,
                        () -> !generationActive(attemptToken),
                        command -> launchProbe(command, attemptToken), this::terminate);
                synchronized (lifecycle) { if (!generationActive(token)) continue; }
                source = profile.settings();
                Path directory = validateDirectory(policy.resolvedDirectory(root));
                cleanupNow();
                if (freeBytes(directory) < MIN_FREE) {
                    state(false, "磁盘可用空间不足 500 MB，录像已暂停");
                    throttledNotice("录像盘空间不足，已暂停录制。请释放空间或更换保存目录；空间恢复后会自动继续。", true);
                    retryDelay(token);
                    continue;
                }
                SessionInfo info = registerSession(directory, policy.segmentMinutes());
                running = new RunningSession(info, token);
                synchronized (lifecycle) {
                    if (closed || applying || !requested || generation != token) continue;
                    current = running;
                    ProcessBuilder builder = new ProcessBuilder(command(info, source, policy, profile));
                    builder.directory(root.toFile());
                    running.process = builder.start();
                    processes.add(running.process);
                }
                RunningSession attempt = running;
                running.errorReader = Thread.ofVirtual().name("recorder-errors").start(() -> consumeErrors(attempt));
                Thread.ofVirtual().name("recorder-progress").start(() -> consumeProgress(attempt));
                long started = System.nanoTime();
                long lastActivity = started;
                long priorSize = -1;
                while (active(running) && running.process.isAlive()) {
                    // The storage worker refreshes every two seconds. Do not rescan days of footage
                    // on each watchdog tick; playback reads this same immutable snapshot.
                    long size = 0;
                    long activeSize = 0;
                    for (RecordingEntry entry : snapshot) {
                        if (entry.path().getFileName().toString().startsWith(info.prefix)) {
                            size = saturatingAdd(size, entry.bytes());
                            if (entry.active()) activeSize = Math.max(activeSize, entry.bytes());
                        }
                    }
                    if (size > 4096 && size != priorSize) {
                        lastActivity = System.nanoTime();
                        priorSize = size;
                        state(true, "正在录制 · 自动分段保存");
                    }
                    // Static scenes can produce tiny packets buffered by the container. A moving
                    // media clock proves reception even before the OS reports a growing file.
                    if (running.lastPacketNanos > lastActivity) {
                        lastActivity = running.lastPacketNanos;
                        state(true, "正在录制 · 自动分段保存");
                    }
                    if (System.nanoTime() - lastActivity > TimeUnit.SECONDS.toNanos(20)) {
                        reason = "设备待唤醒 / 录像流中断 · 已收到的录像已保存，自动重试…";
                        break;
                    }
                    if (running.mediaSeconds > 0 && System.nanoTime() - lastActivity > TimeUnit.SECONDS.toNanos(5))
                        state(true, "录像流间歇 · 已收到画面正在保存");
                    if (freeBytes(directory) < MIN_FREE) {
                        cleanupNow();
                        if (freeBytes(directory) < MIN_FREE) {
                            reason = "磁盘空间不足，录像已暂停";
                            throttledNotice("录像盘空间不足，已保存当前片段并暂停。空间恢复后会自动继续。", true);
                            break;
                        }
                    }
                    // Segmenting depends on camera keyframes. Bound even a malformed never-ending segment.
                    if (activeSize > policy.maxBytes() - policy.maxBytes() / 4) {
                        reason = "当前片段达到容量上限，正在保存并开启新片段…";
                        break;
                    }
                    synchronized (lifecycle) { waitLifecycle(750); }
                }
                if (!running.process.isAlive() && running.errorReader != null) {
                    try { running.errorReader.join(250); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (!active(running)) reason = applying ? "正在更新录像设置…" : "录像已停止";
                else if (running.certificateRejected) {
                    reason = running.error;
                    stopTrustFailureRetry(token);
                    throttledNotice(reason, true);
                } else if (running.error != null) reason = running.error;
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof CameraTlsSupport.UnavailableException) {
                    reason = failure.getMessage();
                    stopTrustFailureRetry(token);
                    throttledNotice(reason, true);
                } else if (failure instanceof RecordingTimestamp.UnavailableException) {
                    reason = failure.getMessage(); throttledNotice(reason, true);
                } else if (failure instanceof java.net.SocketTimeoutException) {
                    reason = "录像连接超时，" + retrySeconds + " 秒后自动重试…";
                } else if (failure instanceof java.net.ConnectException) {
                    reason = "录像视频端口暂时无法连接，" + retrySeconds + " 秒后自动重试…";
                } else if (failure instanceof java.net.NoRouteToHostException || failure instanceof java.net.UnknownHostException) {
                    reason = "录像网络暂时无法连接，" + retrySeconds + " 秒后自动重试…";
                } else {
                    reason = "录像启动失败，请检查保存目录、监控连接和录像组件。" + retrySeconds + " 秒后重试…";
                    throttledNotice("录像暂时无法开始。请检查监控地址、保存目录权限和 tools 中的录像组件。", true);
                }
            } finally {
                if (running != null) finish(running);
                state(false, reason);
                cleanupNow();
            }
            retryDelay(token);
        }
    }

    private List<String> command(SessionInfo info, StreamEngine.Settings source, RecorderSettings policy,
                                 StreamProfileSelector.Selection profile) throws IOException {
        // Some cameras expose audio and video RTP clocks with different initial offsets. Align
        // copied packets to their shared receive clock so a short clip cannot report hours of audio.
        List<String> result = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "warning", "-n",
                "-nostats", "-stats_period", "0.5", "-progress", "pipe:1"));
        if (source.isHttpsVideo()) {
            CameraTlsSupport.addInputOptions(result, assetsRoot, root, source.url());
            if (source.hasHttpsCredentials()) result.addAll(List.of("-auth_type", "basic"));
            if (source.isRawH264Video()) result.addAll(List.of("-analyzeduration", "500000", "-probesize", "65536", "-fpsprobesize", "0", "-f", "h264"));
            else if (source.isMultipartMjpegVideo()) result.addAll(List.of("-f", "mpjpeg"));
        } else result.addAll(List.of("-rtsp_transport", source.transport(), "-timeout", "8000000"));
        result.addAll(List.of("-use_wallclock_as_timestamps", "1", "-i", source.url(),
                "-map", "0:v:0", "-map", "0:a?"));
        boolean scale = source.quality() != VideoQuality.AUTO
                && !(profile.dimensionsKnown() && source.quality().includes(profile.width(), profile.height()));
        if (!source.recordingTimestamp() && !scale)
            result.addAll(List.of("-c", "copy"));
        else {
            String filter = scale ? source.quality().previewFilter() : "";
            if (source.recordingTimestamp()) filter += (filter.isBlank() ? "" : ",") + RecordingTimestamp.filter();
            result.addAll(List.of("-vf", filter, "-c:v", "libx264",
                    "-preset", "veryfast", "-crf", "20", "-threads:v", "2", "-c:a", "copy", "-fps_mode:v", "vfr"));
            if (source.recordingTimestamp()) result.addAll(List.of("-tune", "zerolatency"));
        }
        result.addAll(List.of("-avoid_negative_ts", "make_zero", "-f", "segment",
                "-segment_time", Integer.toString(policy.segmentMinutes() * 60), "-reset_timestamps", "1",
                "-segment_format", "matroska", "-segment_format_options", "flush_packets=1:cluster_time_limit=500",
                "-segment_list", info.list.toString(),
                "-segment_list_type", "csv", "-segment_list_size", "0",
                info.directory.resolve(info.prefix + "%05d.mkv").toString()));
        return result;
    }

    private boolean generationActive(long token) {
        synchronized (lifecycle) { return !closed && requested && !applying && generation == token; }
    }
    private void stopTrustFailureRetry(long token) {
        synchronized (lifecycle) {
            if (!closed && requested && !applying && generation == token) {
                requested = false;
                generation++;
                lifecycle.notifyAll();
            }
        }
    }
    private Process launchProbe(List<String> command, long token) throws IOException {
        synchronized (lifecycle) {
            if (!generationActive(token)) throw new IOException("录像操作已取消。");
            Process process = new ProcessBuilder(command).directory(root.toFile()).start();
            processes.add(process); return process;
        }
    }

    private void consumeProgress(RunningSession running) {
        long lastMediaTime = Long.MIN_VALUE;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                running.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("out_time_us=")) continue;
                try {
                    long mediaTime = Long.parseLong(line.substring("out_time_us=".length()));
                    if (mediaTime >= 0 && mediaTime != lastMediaTime) {
                        lastMediaTime = mediaTime;
                        running.mediaSeconds = Math.max(running.mediaSeconds, mediaTime / 1_000_000.0);
                        running.lastPacketNanos = System.nanoTime();
                    }
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void consumeErrors(RunningSession running) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                running.process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (running.certificateRejected) continue;
                String lower = line.replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\\\"<>]+", "[地址]")
                        .toLowerCase(java.util.Locale.ROOT);
                long retrySeconds = camera != null && camera.isHttpsVideo() ? 30 : 3;
                // Keep only categories. Camera credentials never reach UI, logs, or the index.
                if (lower.contains("certificate") && (lower.contains("verification") || lower.contains("verify")
                        || lower.contains("not trusted") || lower.contains("expired"))) {
                    running.error = "摄像头加密证书不受信任或验证失败，请重新核对设备证书。";
                    running.certificateRejected = true;
                } else if (lower.matches(".*\\b429\\b.*") || lower.contains("too many requests"))
                    running.error = "摄像头暂时限制连接次数，" + retrySeconds + " 秒后自动重试…";
                else if (lower.matches(".*\\b401\\b.*") || lower.contains("unauthorized"))
                    running.error = "录像认证失败，请检查监控设置。" + retrySeconds + " 秒后重试…";
                else if (lower.contains("timed out") || lower.contains("timeout"))
                    running.error = "录像连接超时，" + retrySeconds + " 秒后自动重试…";
                else if (lower.contains("no space left"))
                    running.error = "保存目录空间不足，录像已暂停";
                else if (lower.contains("permission denied"))
                    running.error = "保存目录无法写入，请检查目录权限。";
                else if (lower.contains("drawtext") || lower.contains("fontfile") || lower.contains("could not load font"))
                    running.error = "录像时间水印无法生成，请检查 Windows 字体或关闭该摄像头的录像时间水印。";
            }
        } catch (IOException ignored) { /* A closing process naturally closes stderr. */ }
    }

    private boolean active(RunningSession running) {
        synchronized (lifecycle) {
            return !closed && requested && !applying && generation == running.token && current == running;
        }
    }

    private void finish(RunningSession running) {
        synchronized (running) {
            if (running.finished) return;
            terminate(running.process);
            running.finished = true;
            preparingSessions.remove(running.info.id);
            synchronized (indexLock) {
                try {
                    scanSession(running.info, true);
                    if (files.values().stream().noneMatch(owned -> owned.sessionId.equals(running.info.id)))
                        sessions.remove(running.info.id);
                    saveIndex();
                    updateSnapshot();
                    Files.deleteIfExists(running.info.list);
                } catch (IOException failure) {
                    throttledNotice("最后一段录像的索引未能保存，请检查项目目录权限。", true);
                }
            }
            synchronized (lifecycle) {
                if (current == running) current = null;
                lifecycle.notifyAll();
            }
            changed();
        }
    }

    private void terminate(Process process) {
        if (process == null) return;
        try {
            if (process.isAlive()) {
                try { process.getOutputStream().write(new byte[]{'q', '\n'}); process.getOutputStream().flush(); }
                catch (IOException ignored) { }
                if (!process.waitFor(3500, TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(400, TimeUnit.MILLISECONDS);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally { processes.remove(process); }
    }

    private SessionInfo registerSession(Path directory, int minutes) throws IOException {
        synchronized (indexLock) {
            String storageId = null;
            for (Storage storage : storages.values()) {
                if (storage.path.equals(directory)) { storageId = storage.id; break; }
            }
            if (storageId == null) {
                storageId = UUID.randomUUID().toString();
                StorageDirectoryGuard.Proof proof = StorageDirectoryGuard.inspect(storageGuard, directory);
                storages.put(storageId, new Storage(storageId, directory, proof.real(), proof.identity()));
            } else if (!validStorage(storages.get(storageId))) {
                throw new IOException("保存目录指向发生变化");
            }
            String id = UUID.randomUUID().toString();
            Instant start = Instant.now();
            String prefix = "HW_" + FILE_TIME.format(LocalDateTime.ofInstant(start, ZoneId.systemDefault())) + "_" + id + "_";
            Files.createDirectories(root.resolve("data/temp"));
            Path list = root.resolve("data/temp/recording-" + id + ".csv");
            SessionInfo info = new SessionInfo(id, storageId, directory, prefix, start, minutes, list);
            sessions.put(id, info);
            preparingSessions.add(id);
            try { saveIndex(); } // Ownership is durable before ffmpeg creates its first file.
            catch (IOException failure) { sessions.remove(id); preparingSessions.remove(id); throw failure; }
            return info;
        }
    }

    private boolean scanSession(SessionInfo info, boolean ended) throws IOException {
        Storage storage = storages.get(info.storageId);
        if (!validStorage(storage)) return false;
        Map<String, SegmentTimes> completed = completedSegments(info.list);
        boolean modified = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(info.directory, info.prefix + "*.mkv")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (!OWN_NAME.matcher(name).matches() || !name.startsWith(info.prefix)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
                String id = info.id + ":" + name.substring(info.prefix.length(), name.length() - 4);
                boolean completedFile = ended || completed.containsKey(name);
                OwnedFile existing = files.get(id);
                if (existing == null) {
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    Instant start = attributes.creationTime().toInstant();
                    if (start.equals(Instant.EPOCH)) start = attributes.lastModifiedTime().toInstant();
                    if (completed.containsKey(name)) start = info.start.plusMillis((long) (completed.get(name).start * 1000));
                    files.put(id, new OwnedFile(id, info.id, path.toAbsolutePath().normalize(), start, completedFile));
                    modified = true;
                } else if (completedFile && !existing.closed) {
                    existing.closed = true;
                    modified = true;
                }
                OwnedFile owned = files.get(id);
                SegmentTimes times = completed.get(name);
                double duration = times == null ? activeDuration(info, completed, ended) : Math.max(0, times.end - times.start);
                if (duration > owned.durationSeconds) { owned.durationSeconds = duration; modified = true; }
            }
        }
        if (ended) {
            for (OwnedFile owned : files.values()) {
                if (owned.sessionId.equals(info.id) && !owned.closed) { owned.closed = true; modified = true; }
            }
        }
        return modified;
    }

    private double activeDuration(SessionInfo info, Map<String, SegmentTimes> completed, boolean ended) {
        RunningSession running = current;
        if (running == null || !running.info.id.equals(info.id)) return 0;
        double boundary = completed.values().stream().mapToDouble(SegmentTimes::end).max().orElse(0);
        // Allow the current Matroska cluster to flush before exposing a cursor position.
        return Math.max(0, running.mediaSeconds - boundary - (ended ? 0 : 1.0));
    }

    private Map<String, SegmentTimes> completedSegments(Path list) throws IOException {
        Map<String, SegmentTimes> result = new HashMap<>();
        if (!Files.isRegularFile(list, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(list)) return result;
        try (BufferedReader reader = Files.newBufferedReader(list, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> fields = csvFields(line);
                if (fields.size() < 3) continue;
                try {
                    String filename = Path.of(fields.get(0)).getFileName().toString();
                    double start = Double.parseDouble(fields.get(1));
                    double end = Double.parseDouble(fields.get(2));
                    if (Double.isFinite(start) && start >= 0 && Double.isFinite(end) && end >= start)
                        result.put(filename, new SegmentTimes(start, end));
                } catch (RuntimeException partial) { /* Ignore a not-yet-complete CSV line. */ }
            }
        }
        return result;
    }

    private static List<String> csvFields(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quote && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quote = !quote;
            } else if (ch == ',' && !quote) { result.add(field.toString()); field.setLength(0); }
            else field.append(ch);
        }
        result.add(field.toString());
        return quote ? List.of() : result;
    }

    private void updateSnapshot() throws IOException {
        List<RecordingEntry> entries = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Map<String, Boolean> readableStorages = new HashMap<>();
        long ownedTotal = 0;
        for (OwnedFile owned : files.values()) {
            if (!isOwnedReadableFile(owned, readableStorages)) continue;
            long bytes = Files.size(owned.path);
            entries.add(new RecordingEntry(owned.path, owned.start, bytes, !owned.closed, owned.durationSeconds));
            ownedTotal = saturatingAdd(ownedTotal, bytes);
            seen.add(owned.path);
        }
        // Old manual recordings remain playable, but ownership is never inferred for cleanup.
        Path legacy = root.resolve("data/recordings");
        boolean legacyAlreadyVerified = storages.values().stream()
                .anyMatch(storage -> storage.path.equals(legacy) && Boolean.TRUE.equals(readableStorages.get(storage.id)));
        if (Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(legacy)
                && (legacyAlreadyVerified || StorageDirectoryGuard.inspect(storageGuard, legacy).real().equals(legacy))) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacy, "*.mkv")) {
                for (Path path : stream) {
                    path = path.toAbsolutePath().normalize();
                    if (seen.contains(path) || path.getFileName().toString().startsWith("HW_")
                            || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    entries.add(new RecordingEntry(path, Files.getLastModifiedTime(path).toInstant(), Files.size(path), false));
                }
            }
        }
        entries.sort(Comparator.comparing(RecordingEntry::start).reversed());
        snapshot = List.copyOf(entries);
        indexedBytes = ownedTotal;
    }

    private boolean isOwnedReadableFile(OwnedFile owned, Map<String, Boolean> readableStorages) {
        try {
            SessionInfo info = sessions.get(owned.sessionId);
            if (info == null) return false;
            Storage storage = storages.get(info.storageId);
            if (storage == null) return false;
            Boolean readable = readableStorages.get(storage.id);
            if (readable == null) {
                readable = validStorage(storage);
                readableStorages.put(storage.id, readable);
            }
            String name = owned.path.getFileName().toString();
            // The registered parent has been resolved once for this snapshot. A regular leaf read
            // with NOFOLLOW cannot introduce another link. Deletion performs full fresh realpath checks.
            return readable && owned.path.getParent().equals(storage.path)
                    && OWN_NAME.matcher(name).matches() && name.startsWith(info.prefix)
                    && !Files.isSymbolicLink(owned.path)
                    && Files.isRegularFile(owned.path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    private boolean isOwnedRegularFile(OwnedFile owned) {
        try {
            SessionInfo session = sessions.get(owned.sessionId);
            if (session == null) return false;
            Storage storage = storages.get(session.storageId);
            String name = owned.path.getFileName().toString();
            return OWN_NAME.matcher(name).matches() && name.startsWith(session.prefix)
                    && owned.path.getParent().equals(storage.path) && validStorage(storage)
                    && !Files.isSymbolicLink(owned.path)
                    && Files.isRegularFile(owned.path, LinkOption.NOFOLLOW_LINKS)
                    && provenLeafParent(owned.path, storage);
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private boolean validStorage(Storage storage) throws IOException {
        if (storage == null || !Files.isDirectory(storage.path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(storage.path)) return false;
        StorageDirectoryGuard.Proof proof = StorageDirectoryGuard.inspect(storageGuard, storage.path);
        return storage.path.equals(storage.real) && storage.real.equals(proof.real())
                && storage.identity.equals(proof.identity());
    }

    private boolean provenLeafParent(Path leaf, Storage storage) throws IOException {
        try { return leaf.toRealPath().getParent().equals(storage.real); }
        catch (java.nio.file.AccessDeniedException canonicalizationRestricted) {
            // The parent just passed handle-based canonical path AND persisted volume/file ID checks.
            // A NOFOLLOW regular leaf cannot redirect traversal; only this single leaf will be unlinked.
            BasicFileAttributes attributes = Files.readAttributes(leaf, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink()
                    && leaf.getParent().equals(storage.real);
        }
    }

    private Path validateDirectory(Path directory) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        // Check existing parents before creating any missing child, including Windows junctions.
        Path existing = directory;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || Files.isSymbolicLink(existing)
                || !StorageDirectoryGuard.inspect(storageGuard, existing).real().equals(existing))
            throw new IOException("保存目录不能使用符号链接或目录联接。");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || (!directory.equals(existing)
                && !StorageDirectoryGuard.inspect(storageGuard, directory).real().equals(directory)))
            throw new IOException("保存目录不能使用符号链接或目录联接。");
        Path probe = Files.createTempFile(directory, ".homewatch-write-", ".tmp");
        Files.delete(probe);
        return directory;
    }

    private long freeBytes(Path directory) {
        try {
            Path existing = directory;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            return existing == null ? 0 : Files.getFileStore(existing).getUsableSpace();
        } catch (IOException | RuntimeException unavailable) { return 0; }
    }

    private boolean sameFileStore(Path file, Path directory) {
        try {
            Path existing = directory;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            return existing != null && Files.getFileStore(file).equals(Files.getFileStore(existing));
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    private void pruneEmptySessions() {
        Set<String> occupied = new HashSet<>(preparingSessions);
        for (OwnedFile owned : files.values()) occupied.add(owned.sessionId);
        RunningSession running = current;
        if (running != null) occupied.add(running.info.id);
        sessions.keySet().removeIf(id -> !occupied.contains(id));
    }

    private boolean playbackInUse(Path path) {
        try { return events.isPlaybackInUse(path); }
        catch (RuntimeException failure) { return true; }
    }

    private void loadIndex() throws IOException {
        if (!Files.exists(indexFile)) return;
        if (Files.isSymbolicLink(indexFile)) throw new IOException("索引不能是符号链接");
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) { values.load(reader); }
        if (!"2".equals(values.getProperty("version"))) throw new IOException("索引格式不支持");
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith("storage.") && key.endsWith(".path")) {
                String id = key.substring(8, key.length() - 5);
                UUID.fromString(id);
                Path path = Path.of(values.getProperty(key)).toAbsolutePath().normalize();
                Path real = Path.of(values.getProperty("storage." + id + ".real")).toAbsolutePath().normalize();
                String identity = values.getProperty("storage." + id + ".identity");
                if (identity == null || identity.isBlank()) throw new IOException("保存目录身份缺失");
                storages.put(id, new Storage(id, path, real, identity));
            }
        }
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith("session.") && key.endsWith(".prefix")) {
                String id = key.substring(8, key.length() - 7);
                UUID.fromString(id);
                String base = "session." + id + ".";
                String storageId = values.getProperty(base + "storage");
                Storage storage = storages.get(storageId);
                String prefix = values.getProperty(key);
                if (storage == null || !prefix.matches("HW_\\d{8}_\\d{6}_" + Pattern.quote(id) + "_"))
                    throw new IOException("索引会话归属无效");
                SessionInfo info = new SessionInfo(id, storageId, storage.path, prefix,
                        Instant.ofEpochMilli(Long.parseLong(values.getProperty(base + "start"))),
                        Integer.parseInt(values.getProperty(base + "minutes")),
                        root.resolve("data/temp/recording-" + id + ".csv"));
                sessions.put(id, info);
            }
        }
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith("file.") && key.endsWith(".path")) {
                String id = key.substring(5, key.length() - 5);
                String base = "file." + id + ".";
                String sessionId = values.getProperty(base + "session");
                SessionInfo info = sessions.get(sessionId);
                Path path = Path.of(values.getProperty(key)).toAbsolutePath().normalize();
                if (info == null || !path.getParent().equals(info.directory)
                        || !OWN_NAME.matcher(path.getFileName().toString()).matches()
                        || !path.getFileName().toString().startsWith(info.prefix))
                    throw new IOException("录像文件归属无效");
                OwnedFile owned = new OwnedFile(id, sessionId, path,
                        Instant.ofEpochMilli(Long.parseLong(values.getProperty(base + "start"))), true);
                double duration = Double.parseDouble(values.getProperty(base + "duration", "0"));
                if (Double.isFinite(duration) && duration >= 0) owned.durationSeconds = duration;
                files.put(id, owned);
            }
        }
    }

    private void saveIndex() throws IOException {
        if (!indexHealthy) throw new IOException("索引不可用");
        Properties values = new Properties();
        values.setProperty("version", "2");
        for (Storage storage : storages.values()) {
            values.setProperty("storage." + storage.id + ".path", storage.path.toString());
            values.setProperty("storage." + storage.id + ".real", storage.real.toString());
            values.setProperty("storage." + storage.id + ".identity", storage.identity);
        }
        for (SessionInfo info : sessions.values()) {
            String base = "session." + info.id + ".";
            values.setProperty(base + "storage", info.storageId);
            values.setProperty(base + "prefix", info.prefix);
            values.setProperty(base + "start", Long.toString(info.start.toEpochMilli()));
            values.setProperty(base + "minutes", Integer.toString(info.minutes));
        }
        for (OwnedFile owned : files.values()) {
            String base = "file." + owned.id + ".";
            values.setProperty(base + "path", owned.path.toString());
            values.setProperty(base + "session", owned.sessionId);
            values.setProperty(base + "start", Long.toString(owned.start.toEpochMilli()));
            values.setProperty(base + "closed", Boolean.toString(owned.closed));
            values.setProperty(base + "duration", Double.toString(owned.durationSeconds));
        }
        Files.createDirectories(indexFile.getParent());
        Path temporary = Files.createTempFile(indexFile.getParent(), "recording-index-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "HomeWatch owned recording files - do not remove while retaining recordings");
            }
            try { Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
        lastIndexWriteNanos = System.nanoTime();
    }

    private void retryDelay(long token) {
        long delay = camera != null && camera.isHttpsVideo() ? 30_000 : 3_000;
        synchronized (lifecycle) {
            if (!closed && requested && !applying && generation == token) waitLifecycle(delay);
        }
    }

    private void waitLifecycle(long millis) {
        try { lifecycle.wait(millis); }
        catch (InterruptedException ignored) { /* close also sets the closed flag. */ }
    }

    private void state(boolean active, String detail) {
        synchronized (lifecycle) {
            recording = active;
            lastStateDetail = detail;
            String key = active + ":" + detail;
            if (key.equals(lastState)) return;
            lastState = key;
        }
        try { events.onState(active, detail); } catch (RuntimeException ignored) { }
    }

    private void notice(String message, boolean error) {
        try { events.onNotice(message, error); } catch (RuntimeException ignored) { }
    }

    private synchronized void throttledNotice(String message, boolean error) {
        if (closed) return;
        long now = System.currentTimeMillis();
        if (message.equals(lastNotice) && now - lastNoticeMillis < 60000) return;
        lastNotice = message;
        lastNoticeMillis = now;
        notice(message, error);
    }

    private void changed() { try { events.onChanged(); } catch (RuntimeException ignored) { } }
    private void safely(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { } }
    private static long saturatingAdd(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }

    /** Invoke from a background thread so the last Matroska segment can finalize gracefully. */
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            requested = false;
            generation++;
            lifecycle.notifyAll();
        }
        if (scheduler != null) scheduler.shutdownNow();
        RunningSession running = current;
        if (running != null) finish(running);
        for (Process process : List.copyOf(processes)) terminate(process);
        try { supervisor.join(6000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        state(false, "录像已停止");
        if (sharedStorage != null) sharedStorage.unregister(this);
    }

    /** Coordinator changes never take an index lock while waiting for the recording worker. */
    void beginSharedSettingsChange() {
        synchronized (lifecycle) { if (closed) return; applying = true; generation++; lifecycle.notifyAll(); }
    }
    void awaitSharedSettingsChange() throws IOException {
        synchronized (lifecycle) {
            while (current != null && !closed) {
                try { lifecycle.wait(500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("录像设置操作已中断。", interrupted); }
            }
        }
    }
    void endSharedSettingsChange() {
        synchronized (lifecycle) { applying = false; lifecycle.notifyAll(); }
        changed();
    }

    record RetentionCandidate(RecorderService owner, String id, Path path, Instant start, long bytes) {}
    List<RetentionCandidate> retentionCandidates() {
        if (closed || !indexHealthy) return List.of();
        synchronized (indexLock) {
            try {
                RunningSession running = current;
                if (running != null && scanSession(running.info, running.finished)) saveIndex();
                updateSnapshot();
                Map<Path, Long> sizes = new HashMap<>();
                for (RecordingEntry entry : snapshot) sizes.put(entry.path(), entry.bytes());
                return files.values().stream().filter(owned -> owned.closed && !playbackInUse(owned.path) && sizes.containsKey(owned.path))
                        .map(owned -> new RetentionCandidate(this, owned.id, owned.path, owned.start, sizes.get(owned.path))).toList();
            } catch (IOException failure) {
                throttledNotice("无法检查共享录像存储，请检查保存目录是否可访问。", true);
                return List.of();
            }
        }
    }
    /** Caller holds only the coordinator cleanup lock. Revalidate every ownership/protection rule. */
    long deleteOwned(RetentionCandidate candidate) {
        long removed = -1;
        synchronized (indexLock) {
            if (closed || !indexHealthy || candidate.owner() != this) return -1;
            OwnedFile owned = files.get(candidate.id());
            if (owned == null || !owned.path.equals(candidate.path()) || !owned.start.equals(candidate.start())
                    || !owned.closed || playbackInUse(owned.path) || !isOwnedRegularFile(owned)) return -1;
            try {
                long size = Files.size(owned.path);
                Files.delete(owned.path);
                removed = size;
                files.remove(owned.id); pruneEmptySessions();
                updateSnapshot(); saveIndex();
            } catch (IOException locked) { throttledNotice("部分录像正在播放或被占用，清理将在稍后重试。", false); }
        }
        if (removed >= 0) changed();
        return removed;
    }

    private record Storage(String id, Path path, Path real, String identity) {}
    private record SessionInfo(String id, String storageId, Path directory, String prefix,
                               Instant start, int minutes, Path list) {}
    private static final class OwnedFile {
        final String id;
        final String sessionId;
        final Path path;
        final Instant start;
        boolean closed;
        double durationSeconds;
        OwnedFile(String id, String sessionId, Path path, Instant start, boolean closed) {
            this.id = id; this.sessionId = sessionId; this.path = path; this.start = start; this.closed = closed;
        }
    }
    private static final class RunningSession {
        final SessionInfo info;
        final long token;
        volatile Process process;
        volatile Thread errorReader;
        volatile boolean finished;
        volatile boolean certificateRejected;
        volatile String error;
        volatile long lastPacketNanos;
        volatile double mediaSeconds;
        RunningSession(SessionInfo info, long token) { this.info = info; this.token = token; }
    }
    private record SegmentTimes(double start, double end) {}
}
