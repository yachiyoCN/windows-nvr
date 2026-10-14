package cn.homewatch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Plays a camera without exposing its address or credentials in messages or logs. */
public final class StreamEngine implements AutoCloseable {
    public record Settings(String url, String transport, VideoQuality quality, boolean recordingTimestamp) {
        public Settings(String url, String transport) { this(url, transport, VideoQuality.AUTO, false); }
        public Settings(String url, String transport, VideoQuality quality) { this(url, transport, quality, false); }
        public Settings {
            url = Objects.requireNonNullElse(url, "").strip();
            transport = "udp".equalsIgnoreCase(transport) ? "udp" : "tcp";
            quality = Objects.requireNonNullElse(quality, VideoQuality.AUTO);
        }
        /** Unresolved addresses stay verbatim; a Selection contains the accessible actual address. */
        public String effectiveURL() { return url; }
        public boolean isHttpsVideo() { return url.regionMatches(true, 0, "https://", 0, 8); }
        public boolean hasHttpsCredentials() {
            if (!isHttpsVideo()) return false;
            try { return URI.create(url).getRawUserInfo() != null; }
            catch (RuntimeException ignored) { return false; }
        }
        public boolean isRawH264Video() {
            if (!isHttpsVideo()) return false;
            try { return "/video/h264".equalsIgnoreCase(URI.create(url).getPath()); }
            catch (RuntimeException ignored) { return false; }
        }
        public boolean isMultipartMjpegVideo() {
            if (!isHttpsVideo()) return false;
            try { return "/video/mjpeg".equalsIgnoreCase(URI.create(url).getPath()); }
            catch (RuntimeException ignored) { return false; }
        }
        @Override public String toString() { return "Settings[transport=" + transport + ", quality=" + quality.name()
                + ", recordingTimestamp=" + recordingTimestamp + "]"; }
    }

    public interface Events {
        void onState(String state, String detail);
        void onFrame(BufferedImage image);
        void onNotice(String message, boolean error);
        void onRecording(boolean active);
        void onAudio(boolean enabled);
        default void onSourceSelected(Settings selected, int width, int height, String profile) { }
        default void onFormat(int previewWidth, int previewHeight, String profile) { }
    }

    private static final int MAX_JPEG_BYTES = 12 * 1024 * 1024;
    private static final long FRAME_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Pattern URL_AUTH = Pattern.compile("(?i)((?:rtsps?|https?)://)[^\\s/@]+@");
    private static final Pattern ALL_STREAM_URLS = Pattern.compile("(?i)(?:rtsps?|https?)://[^\\s\\\"<>]+", Pattern.UNICODE_CASE);
    private final Object lock = new Object();
    private final Object logLock = new Object();
    private final Object processStartLock = new Object();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final Set<Recording> recordings = ConcurrentHashMap.newKeySet();
    private final Path root;
    private final Path assetsRoot;
    private final Path ffmpeg;
    private final Path ffplay;
    private final Path logFile;
    private final Events events;
    private long generation;
    private Session current;
    private volatile boolean connected;
    private volatile boolean recording;
    private volatile boolean audioEnabled;
    private volatile boolean closed;

    public StreamEngine(Path root, Events events) { this(root, root, events); }

    public StreamEngine(Path assetsRoot, Path root, Events events) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.assetsRoot = Objects.requireNonNull(assetsRoot).toAbsolutePath().normalize();
        this.events = Objects.requireNonNull(events);
        this.ffmpeg = this.assetsRoot.resolve("tools/ffmpeg.exe");
        this.ffplay = this.assetsRoot.resolve("tools/ffplay.exe");
        this.logFile = this.root.resolve("data/logs/stream.log");
    }

    public void connect(Settings settings) {
        Objects.requireNonNull(settings);
        Session old;
        Session next;
        synchronized (lock) {
            if (closed) return;
            if (settings.url().isBlank()
                    || !(settings.url().regionMatches(true, 0, "rtsp://", 0, 7)
                    || settings.url().regionMatches(true, 0, "rtsps://", 0, 8)
                    || settings.url().regionMatches(true, 0, "https://", 0, 8))) {
                notice("请输入有效的 RTSP、RTSPS 或 HTTPS 视频地址。", true);
                return;
            }
            old = current;
            if (old != null) old.stopped = true;
            next = new Session(++generation, settings);
            current = next;
            connected = false;
            if (recording) {
                recording = false;
                recordingEvent(false);
            }
            state("CONNECTING", "正在连接监控…");
        }
        if (old != null) background("camera-stop-old", () -> stopSession(old, true));
        background("camera-video", () -> supervise(next));
    }

    public void disconnect() {
        Session old;
        synchronized (lock) {
            old = current;
            current = null;
            generation++;
            if (old != null) old.stopped = true;
            connected = false;
            if (recording) {
                recording = false;
                recordingEvent(false);
            }
            state("DISCONNECTED", "已断开连接");
        }
        if (old != null) background("camera-disconnect", () -> stopSession(old, true));
    }

    public void setAudioEnabled(boolean enabled) {
        Session session;
        Process oldAudio = null;
        synchronized (lock) {
            if (closed) return;
            boolean changed = audioEnabled != enabled;
            audioEnabled = enabled;
            session = current;
            if (session != null) {
                if (changed || !enabled) {
                    session.audioToken++;
                    session.audioStarting = false;
                }
                if (!enabled) {
                    oldAudio = session.audio;
                    session.audio = null;
                }
            }
            audioEvent(enabled);
        }
        if (oldAudio != null) {
            Process process = oldAudio;
            background("camera-audio-stop", () -> terminate(process));
        }
        if (enabled && session != null) startAudio(session);
    }

    public void toggleRecording() {
        Session session;
        Recording stop = null;
        Recording start = null;
        synchronized (lock) {
            if (closed) return;
            session = current;
            if (session == null || !connected || !active(session)) {
                notice("画面连接成功后才能开始录像。", true);
                return;
            }
            if (session.recorder != null) {
                stop = session.recorder;
                session.recorder = null;
                recording = false;
                recordingEvent(false);
            } else {
                String filename = FILE_TIME.format(LocalDateTime.now()) + "-"
                        + UUID.randomUUID().toString().substring(0, 8) + ".mkv";
                start = new Recording(root.resolve("data/recordings").resolve(filename));
                recordings.add(start);
                session.recorder = start;
                recording = true;
                recordingEvent(true);
            }
        }
        if (stop != null) {
            Recording target = stop;
            background("camera-record-stop", () -> finishRecording(session, target, true));
        }
        if (start != null) {
            Recording target = start;
            background("camera-record", () -> runRecording(session, target));
        }
    }

    public boolean isConnected() { return connected; }
    public boolean isRecording() { return recording; }
    public boolean isAudioEnabled() { return audioEnabled; }

    @Override public void close() {
        Session old;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            old = current;
            current = null;
            generation++;
            connected = false;
            recording = false;
            if (old != null) old.stopped = true;
        }
        // The application should invoke close from a background thread when it exits.
        // Waiting here gives a recording time to write its final Matroska index.
        synchronized (processStartLock) {
            // Finish a launch that began before close, and prevent all future launches.
        }
        if (old != null) stopSession(old, false);
        for (Recording recorder : List.copyOf(recordings)) finishRecording(old, recorder, false);
        for (Process process : List.copyOf(processes)) terminate(process);
    }

    private void supervise(Session session) {
        if (!Files.isRegularFile(ffmpeg)) {
            fatal(session, "播放器组件缺失，请检查项目 tools 目录中的 ffmpeg.exe。");
            return;
        }
        try { Files.createDirectories(root); }
        catch (IOException failure) {
            fatal(session, "无法准备监控目录，请检查项目目录的写入权限。");
            return;
        }
        StreamProfileSelector.Selection selected = StreamProfileSelector.resolve(assetsRoot, session.settings,
                () -> !isActive(session), this::launch, this::terminate);
        synchronized (lock) {
            if (!active(session)) return;
            session.settings = selected.settings();
            session.profile = selected.profile();
            try { events.onSourceSelected(selected.settings(), selected.width(), selected.height(), selected.profile()); }
            catch (RuntimeException ignored) { }
        }
        while (isActive(session)) {
            VideoAttempt attempt = null;
            String failureReason = null;
            try {
                Files.createDirectories(logFile.getParent());
                List<String> command = inputCommand(ffmpeg, session.settings);
                command.addAll(List.of("-map", "0:v:0", "-an"));
                String filter = session.settings.quality().previewFilter();
                if (!filter.isBlank()) command.addAll(List.of("-vf", filter));
                command.addAll(List.of("-r", "15", "-q:v", "4", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"));
                Process process = launch(command);
                attempt = new VideoAttempt(process);
                boolean accepted;
                synchronized (lock) {
                    accepted = active(session);
                    if (accepted) session.attempt = attempt;
                }
                if (!accepted) { terminate(process); return; }
                VideoAttempt running = attempt;
                background("camera-video-log", () -> consumeErrors(process, session.settings, running.errors, running.stderrDone));
                background("camera-watchdog", () -> watchdog(session, running));
                readFrames(session, attempt);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof CameraTlsSupport.UnavailableException) {
                    fatal(session, failure.getMessage());
                    return;
                }
                if (failure instanceof java.net.SocketTimeoutException)
                    failureReason = "监控连接超时，请检查摄像头电源、网络和地址。";
                else if (failure instanceof java.net.ConnectException)
                    failureReason = "摄像头视频端口无法连接，请检查摄像头电源、地址和服务。";
                else if (failure instanceof java.net.NoRouteToHostException || failure instanceof java.net.UnknownHostException)
                    failureReason = "无法到达摄像头，请检查网络和设备地址。";
                if (isActive(session)) log(session.settings, "视频读取失败（" + failure.getClass().getSimpleName() + "）");
            } finally {
                if (attempt != null) {
                    attempt.finished = true;
                    terminate(attempt.process);
                    awaitLog(attempt.stderrDone);
                }
            }
            if (!isActive(session)) break;
            String reason = attempt == null
                    ? failureReason != null ? failureReason : "无法启动播放器，请检查 tools 目录及项目权限。"
                    : attempt.errors.friendly();
            if (attempt != null && attempt.errors.certificateRejected) { fatal(session, reason); return; }
            Process stopAudio;
            Recording stopRecording;
            synchronized (lock) {
                if (!active(session)) break;
                session.attempt = null;
                connected = false;
                session.audioToken++;
                session.audioStarting = false;
                stopAudio = session.audio;
                session.audio = null;
                stopRecording = session.recorder;
                session.recorder = null;
                if (stopRecording != null) {
                    recording = false;
                    recordingEvent(false);
                    notice("监控连接中断，正在保存本次录像。", true);
                }
                long retrySeconds = session.settings.isHttpsVideo() ? 30 : 3;
                state("RETRYING", reason + " " + retrySeconds + " 秒后自动重连…");
            }
            if (stopAudio != null) terminate(stopAudio);
            if (stopRecording != null) finishRecording(session, stopRecording, true);
            pauseRetry(session);
        }
        stopSession(session, false);
    }

    private List<String> inputCommand(Path executable, Settings settings) throws IOException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-hide_banner", "-loglevel", "warning"));
        if (settings.isHttpsVideo()) {
            CameraTlsSupport.addInputOptions(command, assetsRoot, root, settings.url());
            // This camera app counts a 401 challenge as a failed login. Send the
            // configured HTTP Basic credentials on the first HTTPS request.
            if (settings.hasHttpsCredentials()) command.addAll(List.of("-auth_type", "basic"));
            if (settings.isRawH264Video()) command.addAll(List.of("-analyzeduration", "500000", "-probesize", "65536", "-fpsprobesize", "0", "-f", "h264"));
            else if (settings.isMultipartMjpegVideo()) command.addAll(List.of("-f", "mpjpeg"));
        } else command.addAll(List.of("-rtsp_transport", settings.transport(), "-timeout", "8000000"));
        command.addAll(List.of("-i", settings.effectiveURL()));
        return command;
    }

    private void readFrames(Session session, VideoAttempt attempt) throws IOException {
        InputStream input = attempt.process.getInputStream();
        byte[] chunk = new byte[32 * 1024];
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(128 * 1024);
        boolean inFrame = false;
        int previous = -1;
        int count;
        while (isActive(session) && !attempt.finished && (count = input.read(chunk)) >= 0) {
            for (int i = 0; i < count; i++) {
                int value = chunk[i] & 0xff;
                if (!inFrame) {
                    if (previous == 0xff && value == 0xd8) {
                        jpeg.reset();
                        jpeg.write(0xff);
                        jpeg.write(0xd8);
                        inFrame = true;
                    }
                } else {
                    jpeg.write(value);
                    if (jpeg.size() > MAX_JPEG_BYTES) {
                        inFrame = false;
                        jpeg.reset();
                        log(session.settings, "跳过超出大小限制的视频帧");
                    } else if (previous == 0xff && value == 0xd9) {
                        inFrame = false;
                        BufferedImage image = decodeJpeg(jpeg.toByteArray());
                        jpeg.reset();
                        if (image != null) {
                            attempt.lastFrameNanos = System.nanoTime();
                            boolean first = false;
                            synchronized (lock) {
                                if (!active(session) || session.attempt != attempt || attempt.finished) return;
                                if (!connected) {
                                    connected = true;
                                    first = true;
                                    state("CONNECTED", "监控已连接 · " + session.settings.transport().toUpperCase(Locale.ROOT));
                                }
                                if (session.previewWidth != image.getWidth() || session.previewHeight != image.getHeight()) {
                                    session.previewWidth = image.getWidth();
                                    session.previewHeight = image.getHeight();
                                    try { events.onFormat(session.previewWidth, session.previewHeight, session.profile); }
                                    catch (RuntimeException ignored) { }
                                }
                                frameEvent(image);
                            }
                            if (first) startAudio(session);
                        }
                    }
                }
                previous = value;
            }
        }
    }

    private BufferedImage decodeJpeg(byte[] bytes) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                        || (long) width * height > 20_000_000L) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void watchdog(Session session, VideoAttempt attempt) {
        while (isActive(session) && !attempt.finished && attempt.process.isAlive()) {
            if (System.nanoTime() - attempt.lastFrameNanos > FRAME_TIMEOUT_NANOS) {
                attempt.errors.timeout = true;
                log(session.settings, "视频长时间未收到有效画面，重新连接");
                terminate(attempt.process);
                return;
            }
            pause(500);
        }
    }

    private void startAudio(Session session) {
        long token;
        boolean unsupported = false;
        synchronized (lock) {
            if (!active(session) || !connected || !audioEnabled || session.audio != null || session.audioStarting) return;
            // Android IP Camera's HTTPS endpoints are video-only elementary streams.
            // Do not launch a second ffplay process that can never produce audio.
            if (session.settings.isRawH264Video() || session.settings.isMultipartMjpegVideo()) {
                unsupported = true;
                token = 0;
            } else {
                session.audioStarting = true;
                token = ++session.audioToken;
            }
        }
        if (unsupported) {
            notice("此 HTTPS 摄像头视频流不含音频。", true);
            return;
        }
        background("camera-audio", () -> runAudio(session, token));
    }

    private void runAudio(Session session, long token) {
        Process process = null;
        ErrorSummary errors = new ErrorSummary();
        CountDownLatch stderrDone = new CountDownLatch(1);
        try {
            if (!Files.isRegularFile(ffplay)) throw new IOException("missing audio player");
            List<String> command = new ArrayList<>(List.of(ffplay.toString(), "-hide_banner", "-loglevel", "warning",
                    "-nodisp", "-autoexit", "-vn"));
            if (session.settings.isHttpsVideo()) {
                CameraTlsSupport.addInputOptions(command, assetsRoot, root, session.settings.url());
                if (session.settings.hasHttpsCredentials()) command.addAll(List.of("-auth_type", "basic"));
                if (session.settings.isRawH264Video()) command.addAll(List.of("-analyzeduration", "500000", "-probesize", "65536", "-fpsprobesize", "0", "-f", "h264"));
                else if (session.settings.isMultipartMjpegVideo()) command.addAll(List.of("-f", "mpjpeg"));
            } else command.addAll(List.of("-rtsp_transport", session.settings.transport(), "-timeout", "8000000"));
            command.addAll(List.of("-i", session.settings.effectiveURL()));
            process = launch(command);
            boolean accepted;
            synchronized (lock) {
                accepted = active(session) && connected && audioEnabled && session.audioToken == token;
                if (accepted) {
                    session.audioStarting = false;
                    session.audio = process;
                }
            }
            if (!accepted) { terminate(process); return; }
            Process playing = process;
            background("camera-audio-log", () -> consumeErrors(playing, session.settings, errors, stderrDone));
            background("camera-audio-output", () -> drain(playing.getInputStream()));
            process.waitFor();
            awaitLog(stderrDone);
        } catch (IOException failure) {
            log(session.settings, "无法启动音频播放（" + failure.getClass().getSimpleName() + "）");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) terminate(process);
            synchronized (lock) {
                if (active(session) && connected && audioEnabled && session.audioToken == token) {
                    session.audio = null;
                    session.audioStarting = false;
                    notice(!Files.isRegularFile(ffplay)
                            ? "音频播放器组件缺失，请检查 tools 目录中的 ffplay.exe。"
                            : "音频播放已停止。监控可能没有音轨，或音频设备不可用。可关闭再开启声音重试。", true);
                }
            }
        }
    }

    private void runRecording(Session session, Recording recorder) {
        Process process = null;
        try {
            Files.createDirectories(recorder.path.getParent());
            List<String> command = inputCommand(ffmpeg, session.settings);
            command.addAll(List.of("-map", "0:v:0", "-map", "0:a?"));
            if (session.settings.quality() == VideoQuality.AUTO) command.addAll(List.of("-c", "copy"));
            else command.addAll(List.of("-vf", session.settings.quality().previewFilter(),
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "copy"));
            command.addAll(List.of("-f", "matroska", "-n", recorder.path.toString()));
            synchronized (lock) {
                if (!active(session) || !connected || session.recorder != recorder || recorder.stopping.get()) return;
            }
            process = launch(command);
            synchronized (recorder) {
                recorder.process = process;
            }
            recorder.startFinished.countDown();
            Process writing = process;
            background("camera-record-log", () -> consumeErrors(writing, session.settings, recorder.errors, recorder.stderrDone));
            background("camera-record-output", () -> drain(writing.getInputStream()));
            boolean accepted;
            synchronized (lock) { accepted = active(session) && connected && session.recorder == recorder && !recorder.stopping.get(); }
            if (!accepted) { gracefulRecordingStop(recorder); return; }
            process.waitFor();
            awaitLog(recorder.stderrDone);
            synchronized (lock) {
                if (active(session) && session.recorder == recorder) {
                    session.recorder = null;
                    recording = false;
                    recordingEvent(false);
                    notice("录像已停止：" + recorder.errors.friendly() + " 已写入的片段保存在录像目录。", true);
                }
            }
        } catch (IOException failure) {
            synchronized (lock) {
                if (active(session) && session.recorder == recorder) {
                    session.recorder = null;
                    recording = false;
                    recordingEvent(false);
                    notice("无法开始录像，请检查项目目录的写入权限与可用空间。", true);
                }
            }
            log(session.settings, "录像失败（" + failure.getClass().getSimpleName() + "）");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            recorder.startFinished.countDown();
            if (process != null && process.isAlive()) gracefulRecordingStop(recorder);
            if (process != null) terminate(process);
            recordings.remove(recorder);
        }
    }

    private void finishRecording(Session session, Recording recorder, boolean notify) {
        if (!recorder.stopping.compareAndSet(false, true)) {
            try { recorder.stopFinished.await(5500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return;
        }
        try {
            // The process may still be starting when stop is requested.
            Process process;
            synchronized (recorder) { process = recorder.process; }
            if (process == null) {
                try { recorder.startFinished.await(3500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            gracefulRecordingStop(recorder);
            awaitLog(recorder.stderrDone);
            if (notify) {
                try {
                    if (Files.isRegularFile(recorder.path) && Files.size(recorder.path) > 0) {
                        notice("录像已保存：" + recorder.path.getFileName(), false);
                    } else {
                        notice("录像已停止，未收到可保存的片段。", true);
                    }
                } catch (IOException ignored) {
                    notice("录像已停止，可在项目的 data/recordings 目录查看文件。", false);
                }
            }
        } finally {
            recorder.stopFinished.countDown();
        }
    }

    private void gracefulRecordingStop(Recording recorder) {
        synchronized (recorder) {
            Process process = recorder.process;
            if (process == null || !process.isAlive()) return;
            try {
                process.getOutputStream().write("q\n".getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
                if (process.waitFor(3500, TimeUnit.MILLISECONDS)) return;
            } catch (IOException ignored) {
                // A failed input pipe means the process is already stopping.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            terminate(process);
        }
    }

    private void stopSession(Session session, boolean notifyRecording) {
        VideoAttempt attempt;
        Process audio;
        Recording recorder;
        synchronized (lock) {
            session.stopped = true;
            session.audioToken++;
            session.audioStarting = false;
            attempt = session.attempt;
            session.attempt = null;
            audio = session.audio;
            session.audio = null;
            recorder = session.recorder;
            session.recorder = null;
        }
        if (attempt != null) {
            attempt.finished = true;
            terminate(attempt.process);
        }
        if (audio != null) terminate(audio);
        if (recorder != null) finishRecording(session, recorder, notifyRecording);
    }

    private void consumeErrors(Process process, Settings settings, ErrorSummary summary, CountDownLatch done) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                summary.observe(line);
                log(settings, line);
            }
        } catch (IOException ignored) {
            // Pipe closure is expected during disconnect and shutdown.
        } finally {
            done.countDown();
        }
    }

    private void log(Settings settings, String message) {
        String safe = message.replace(settings.url(), "[监控地址]");
        safe = URL_AUTH.matcher(safe).replaceAll("$1[认证已隐藏]@");
        safe = ALL_STREAM_URLS.matcher(safe).replaceAll("[监控地址]");
        if (safe.length() > 4096) safe = safe.substring(0, 4096) + "…";
        synchronized (logLock) {
            try {
                Files.createDirectories(logFile.getParent());
                if (Files.exists(logFile) && Files.size(logFile) > 2 * 1024 * 1024) {
                    Files.move(logFile, logFile.resolveSibling("stream.previous.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(logFile, LocalDateTime.now() + " " + safe + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Failure to write a diagnostic log must not interrupt live video.
            }
        }
    }

    private void fatal(Session session, String detail) {
        synchronized (lock) {
            if (!active(session)) return;
            session.stopped = true;
            current = null;
            connected = false;
            state("ERROR", detail);
            notice(detail, true);
        }
    }

    private boolean active(Session session) { return !closed && current == session && generation == session.id && !session.stopped; }
    private boolean isActive(Session session) { synchronized (lock) { return active(session); } }

    private void pauseRetry(Session session) {
        // Android IP Camera returns 429 after rapid reconnects. Keep one long-lived
        // stream and back off this source before trying again.
        long delaySeconds = session.settings.isHttpsVideo() ? 30 : 3;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds);
        while (isActive(session) && System.nanoTime() < until) pause(100);
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void background(String name, Runnable work) {
        Thread.ofVirtual().name(name).start(work);
    }

    private Process launch(List<String> command) throws IOException {
        synchronized (processStartLock) {
            if (closed) throw new IOException("engine closed");
            Process process = new ProcessBuilder(command).directory(root.toFile()).start();
            processes.add(process);
            return process;
        }
    }

    private void terminate(Process process) {
        if (process == null) return;
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(800, TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            processes.remove(process);
        }
    }

    private static void awaitLog(CountDownLatch latch) {
        try { latch.await(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void drain(InputStream input) {
        try (input) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
        catch (IOException ignored) { }
    }

    private void state(String state, String detail) { try { events.onState(state, detail); } catch (RuntimeException ignored) { } }
    private void frameEvent(BufferedImage image) { try { events.onFrame(image); } catch (RuntimeException ignored) { } }
    private void notice(String message, boolean error) { try { events.onNotice(message, error); } catch (RuntimeException ignored) { } }
    private void recordingEvent(boolean active) { try { events.onRecording(active); } catch (RuntimeException ignored) { } }
    private void audioEvent(boolean enabled) { try { events.onAudio(enabled); } catch (RuntimeException ignored) { } }

    private static final class Session {
        final long id;
        volatile Settings settings;
        String profile = "原始地址";
        int previewWidth;
        int previewHeight;
        volatile boolean stopped;
        VideoAttempt attempt;
        Process audio;
        boolean audioStarting;
        long audioToken;
        Recording recorder;
        Session(long id, Settings settings) { this.id = id; this.settings = settings; }
    }

    private static final class VideoAttempt {
        final Process process;
        final ErrorSummary errors = new ErrorSummary();
        final CountDownLatch stderrDone = new CountDownLatch(1);
        volatile long lastFrameNanos = System.nanoTime();
        volatile boolean finished;
        VideoAttempt(Process process) { this.process = process; }
    }

    private static final class Recording {
        final Path path;
        final AtomicBoolean stopping = new AtomicBoolean();
        final CountDownLatch startFinished = new CountDownLatch(1);
        final CountDownLatch stopFinished = new CountDownLatch(1);
        final CountDownLatch stderrDone = new CountDownLatch(1);
        final ErrorSummary errors = new ErrorSummary();
        Process process;
        Recording(Path path) { this.path = path; }
    }

    private static final class ErrorSummary {
        private static final Pattern ERROR_URLS = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\\\"<>]+");
        private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
        volatile boolean optionMissing;
        volatile boolean unauthorized;
        volatile boolean forbidden;
        volatile boolean missing;
        volatile boolean timeout;
        volatile boolean refused;
        volatile boolean network;
        volatile boolean disk;
        volatile boolean connectionFailed;
        volatile boolean rateLimited;
        volatile boolean certificateRejected;
        void observe(String message) {
            // Do not classify query/path/credential text from a URL as an FFmpeg error.
            String text = ERROR_URLS.matcher(message).replaceAll("[地址]").toLowerCase(Locale.ROOT);
            if (text.contains("connection to") && text.contains("failed")) connectionFailed = true;
            if ((text.contains("option") && text.contains("not found")) || text.contains("unrecognized option")) optionMissing = true;
            if (text.matches(".*\\b401\\b.*") || text.contains("unauthorized")) unauthorized = true;
            if (text.matches(".*\\b403\\b.*") || text.contains("forbidden")) forbidden = true;
            if (text.matches(".*\\b404\\b.*") || text.contains("not found")) missing = true;
            if (text.matches(".*\\b429\\b.*") || text.contains("too many requests")) rateLimited = true;
            if (text.contains("certificate") && (text.contains("verification") || text.contains("verify")
                    || text.contains("not trusted") || text.contains("expired"))) certificateRejected = true;
            if (text.contains("timed out") || text.contains("timeout")) timeout = true;
            if (text.contains("refused")) refused = true;
            if (text.contains("unreachable") || text.contains("no route") || text.contains("resolve hostname")) network = true;
            if (text.contains("no space") || text.contains("permission denied")) disk = true;
            // Windows FFmpeg exposes CRT errno values when the socket layer has no text.
            if (WINDOWS) {
                if (text.contains("error number -138 occurred")) timeout = true;
                if (text.contains("error number -107 occurred")) refused = true;
                if (text.contains("error number -110 occurred") || text.contains("error number -118 occurred")) network = true;
            }
        }
        String friendly() {
            if (optionMissing) return "播放器组件与启动参数不兼容，请检查项目中的 FFmpeg 组件版本。";
            if (certificateRejected) return "摄像头加密证书不受信任或验证失败，请重新核对设备证书；连接保持强验证。";
            if (unauthorized) return "监控认证失败，请检查用户名和密码。";
            if (forbidden) return "监控拒绝访问，请检查该账号的预览权限。";
            if (missing) return "监控未找到此视频通道，请检查拉流地址。";
            if (rateLimited) return "摄像头暂时限制连接次数，请稍等后重试。";
            if (disk) return "文件写入失败，请检查项目目录权限与磁盘空间。";
            if (refused) return "监控端口拒绝连接，请检查摄像头和 RTSP 服务。";
            if (network) return "无法到达监控，请确认电脑与摄像头在同一网络。";
            if (timeout) return connectionFailed
                    ? "无法连上摄像头的视频端口（连接超时），请检查摄像头 IP、网络和电源。"
                    : "监控连接超时，请检查摄像头电源、网络和地址。";
            return "监控连接中断或视频流暂不可用。";
        }
    }
}
