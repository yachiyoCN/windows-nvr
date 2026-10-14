package cn.homewatch;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Plays local recordings inside Swing. No decoded frames are saved to disk. */
public final class PlaybackEngine implements AutoCloseable {
    public interface Events {
        void onReady(double durationSeconds);
        void onState(String state, String detail);
        void onFrame(BufferedImage image);
        void onAudio(boolean enabled);
    }

    private static final double FPS = 15.0;
    private static final long SCRUB_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final int MAX_JPEG_BYTES = 16 * 1024 * 1024;
    private final Object lock = new Object();
    private final Object processLock = new Object();
    private final Object logLock = new Object();
    private final Path ffmpeg;
    private final Path ffprobe;
    private final Path ffplay;
    private final Path recording;
    private final Path logFile;
    private final Events events;
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    // One decoder owns all video launches. A cancelled seek finishes cleanup before
    // the next seek can launch; a fast mouse drag cannot create parallel FFmpeg jobs.
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "recording-decoder");
        thread.setDaemon(true);
        return thread;
    });
    private volatile double duration;
    private volatile boolean growing;
    private final double receivedDuration;
    private volatile boolean playing;
    private volatile boolean audioEnabled;
    private volatile boolean closed;
    private boolean ready;
    private boolean requestedPlaying;
    private boolean prepared;
    private double position;
    private double speed = 1.0;
    private long generation;
    private boolean scrubbing;
    private long scrubGeneration;
    private long scrubRequest;
    private double scrubTarget;
    private Session current;

    public PlaybackEngine(Path root, Path recording, Events events) {
        this(root, recording, events, 0, false);
    }

    public PlaybackEngine(Path root, Path recording, Events events, double receivedDuration, boolean growing) {
        Path project = root.toAbsolutePath().normalize();
        this.ffmpeg = project.resolve("tools/ffmpeg.exe");
        this.ffprobe = project.resolve("tools/ffprobe.exe");
        this.ffplay = project.resolve("tools/ffplay.exe");
        this.logFile = project.resolve("data/logs/playback.log");
        this.recording = recording.toAbsolutePath().normalize();
        this.events = java.util.Objects.requireNonNull(events);
        this.receivedDuration = Math.max(0, receivedDuration);
        this.growing = growing;
    }

    /** A catalog update extends an open file without moving a paused cursor or restarting a decoder. */
    public void updateAvailable(double seconds, boolean activeFile) {
        synchronized (lock) {
            if (closed) return;
            boolean wasGrowing = growing;
            growing = activeFile;
            if (Double.isFinite(seconds) && seconds > duration) duration = seconds;
            if (!ready || !requestedPlaying || playing || scrubbing || current != null) return;
            if (duration > position + 0.2) restartLocked(true, "PLAYING", "正在回放");
            else if (wasGrowing && !growing) {
                requestedPlaying = false;
                state("ENDED", "回放结束");
            }
        }
    }

    public void prepare() {
        synchronized (lock) {
            if (closed || prepared) return;
            prepared = true;
        }
        state("LOADING", "正在读取录像…");
        background("recording-probe", this::probe);
    }

    public void play() {
        synchronized (lock) {
            if (closed) return;
            requestedPlaying = true;
            if (!ready) { prepare(); return; }
            if (playing) return;
            if (position >= duration - 0.01 && !growing) position = 0;
            restartLocked(true, "PLAYING", "正在回放");
        }
    }

    public void pause() {
        synchronized (lock) {
            if (closed) return;
            requestedPlaying = false;
            position = positionLocked();
            cancelScrubLocked();
            cancelLocked();
            playing = false;
            state("PAUSED", "已暂停");
        }
    }

    public void stop() {
        synchronized (lock) {
            if (closed) return;
            requestedPlaying = false;
            position = 0;
            if (ready) restartLocked(false, "STOPPED", "已停止");
            else { cancelLocked(); playing = false; state("STOPPED", "已停止"); }
        }
    }

    public void seek(double seconds) {
        synchronized (lock) {
            if (closed || !ready) return;
            boolean resume = playing || growing && requestedPlaying;
            position = seekPosition(seconds);
            restartLocked(resume, resume ? "PLAYING" : "PAUSED", resume ? "正在回放" : "已暂停");
        }
    }

    /** Freezes playback once at the start of a mouse drag. This never waits for FFmpeg. */
    public void beginScrub() {
        synchronized (lock) {
            if (closed || !ready || scrubbing) return;
            position = positionLocked();
            cancelLocked();
            playing = false;
            requestedPlaying = false;
            scrubbing = true;
            scrubTarget = position;
            scrubRequest++;
            long token = ++scrubGeneration;
            state("PAUSED", "拖动定位…");
            decoder.execute(() -> renderScrub(token));
        }
    }

    /** Queues only the newest cursor position. A single worker previews at about 3 fps. */
    public void scrubTo(double seconds) {
        synchronized (lock) {
            if (closed || !ready || !scrubbing) return;
            position = scrubTarget = seekPosition(seconds);
            scrubRequest++;
            lock.notifyAll();
        }
    }

    /** Seeks precisely on release and restores the playback state saved by the UI. */
    public void endScrub(double seconds, boolean resume) {
        synchronized (lock) {
            if (closed || !ready) return;
            position = seekPosition(seconds);
            restartLocked(resume, resume ? "PLAYING" : "PAUSED", resume ? "正在回放" : "已暂停");
        }
    }

    public boolean isScrubbing() { synchronized (lock) { return scrubbing; } }

    private double seekPosition(double seconds) {
        double target = Math.max(0, Math.min(duration, Double.isFinite(seconds) ? seconds : 0));
        return target >= duration ? Math.max(0, duration - 0.05) : target;
    }

    private void cancelScrubLocked() {
        if (!scrubbing) return;
        scrubbing = false;
        scrubGeneration++;
        lock.notifyAll();
    }

    private void renderScrub(long token) {
        long handled = -1;
        long lastStart = 0;
        while (true) {
            Session sample;
            synchronized (lock) {
                while (!closed && scrubbing && token == scrubGeneration) {
                    long delay = lastStart + SCRUB_INTERVAL_NANOS - System.nanoTime();
                    if (scrubRequest != handled && delay <= 0) break;
                    try {
                        if (scrubRequest == handled) lock.wait();
                        else TimeUnit.NANOSECONDS.timedWait(lock, delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (closed || !scrubbing || token != scrubGeneration) return;
                handled = scrubRequest;
                sample = new Session(++generation, scrubTarget, speed, false, false);
                current = sample;
                lastStart = System.nanoTime();
            }
            render(sample);
            synchronized (lock) {
                if (current == sample) current = null;
            }
        }
    }

    public void setSpeed(double multiplier) {
        if (!(multiplier == 0.5 || multiplier == 1.0 || multiplier == 2.0 || multiplier == 4.0 || multiplier == 8.0)) return;
        synchronized (lock) {
            if (closed || multiplier == speed) return;
            position = positionLocked();
            speed = multiplier;
            if (ready && playing) restartLocked(true, "PLAYING", "正在回放");
        }
    }

    public void setAudioEnabled(boolean enabled) {
        synchronized (lock) {
            if (closed || audioEnabled == enabled) return;
            if (enabled && !Files.isRegularFile(ffplay)) {
                state(playing ? "PLAYING" : "PAUSED", "声音组件缺失，当前保持静音");
                return;
            }
            audioEnabled = enabled;
            events.onAudio(enabled);
            if (ready && playing) {
                position = positionLocked();
                restartLocked(true, "PLAYING", "正在回放");
            }
        }
    }

    public boolean isPlaying() { synchronized (lock) { return playing || growing && requestedPlaying && ready; } }
    public boolean isAudioEnabled() { return audioEnabled; }
    public double durationSeconds() { return duration; }
    public double speed() { synchronized (lock) { return speed; } }
    public double positionSeconds() { synchronized (lock) { return positionLocked(); } }

    private double positionLocked() {
        Session session = current;
        if (playing && session != null && session.clockStart != 0) {
            return Math.min(duration, session.start + (System.nanoTime() - session.clockStart) / 1.0e9 * session.speed);
        }
        return position;
    }

    private void probe() {
        Process process = null;
        try {
            if (!Files.isRegularFile(recording)) throw new PlaybackFailure("这段录像已不存在，请刷新录像列表。");
            if (!Files.isRegularFile(ffprobe) || !Files.isRegularFile(ffmpeg)) {
                throw new PlaybackFailure("回放组件缺失，请检查项目中的 tools 目录。");
            }
            process = launch(List.of(ffprobe.toString(), "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=codec_type,duration:format=duration", "-of",
                    "default=noprint_wrappers=1", recording.toString()));
            Process probing = process;
            StringBuilder result = new StringBuilder();
            StringBuilder errors = new StringBuilder();
            Thread reader = background("recording-probe-output", () -> consumeText(probing.getInputStream(), result));
            Thread stderr = background("recording-probe-errors", () -> consumeText(probing.getErrorStream(), errors));
            if (!process.waitFor(12, TimeUnit.SECONDS)) throw new PlaybackFailure("读取录像超时，请稍后重试。");
            reader.join(1000);
            stderr.join(1000);
            if (process.exitValue() != 0) {
                log("读取录像失败：" + errors.toString().strip());
                throw new PlaybackFailure("这段录像无法读取，可能尚未录制完成或文件已损坏。");
            }
            boolean video = false;
            double length = 0;
            for (String line : result.toString().split("\\R")) {
                if (line.equals("codec_type=video")) video = true;
                if (line.startsWith("duration=")) {
                    try { length = Math.max(length, Double.parseDouble(line.substring(9).strip())); }
                    catch (NumberFormatException ignored) { }
                }
            }
            if (!video) throw new PlaybackFailure("这段文件中没有可回放的监控画面。");
            if (!Double.isFinite(length) || length <= 0) length = receivedDuration;
            if (growing) length = receivedDuration;
            if (!growing && (!Double.isFinite(length) || length <= 0))
                length = RecordingDurationProbe.receivedDuration(ffprobe, recording, () -> closed);
            if (!Double.isFinite(length) || length <= 0) throw new PlaybackFailure("这段录像还没有可读取的画面，请稍后重试。");
            synchronized (lock) {
                if (closed) return;
                duration = length;
                ready = true;
                events.onReady(duration);
                if (requestedPlaying) restartLocked(true, "PLAYING", "正在回放");
                else restartLocked(false, "READY", "录像已就绪");
            }
        } catch (PlaybackFailure failure) {
            fail(failure.getMessage());
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log("读取录像失败（" + failure.getClass().getSimpleName() + "）");
            fail("无法打开这段录像，请检查文件和播放器组件。");
        } finally {
            terminate(process);
        }
    }

    /** Caller holds lock; mutations never wait for a media process on the EDT. */
    private void restartLocked(boolean play, String state, String detail) {
        cancelScrubLocked();
        cancelLocked();
        playing = play;
        requestedPlaying = play;
        Session session = new Session(++generation, position, speed, play, audioEnabled);
        current = session;
        state(state, detail);
        decoder.execute(() -> render(session));
    }

    private void cancelLocked() {
        Session old = current;
        current = null;
        generation++;
        if (old != null) {
            old.stopped = true;
            Process video = old.video;
            Process audio = old.audio;
            if (video != null || audio != null) background("recording-stop", () -> {
                terminate(video);
                terminate(audio);
            });
        }
    }

    private void render(Session session) {
        Process process = null;
        try {
            if (!active(session)) return;
            // At fast speeds FFmpeg drops intermediate frames before JPEG encoding. The UI
            // receives at most 15 frames/sec rather than decoding 120 at 8x speed.
            double mediaFps = session.play ? FPS / Math.max(1, session.speed) : FPS;
            List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-threads", "2", "-ss", decimal(session.start), "-i", recording.toString(), "-map", "0:v:0", "-an",
                    "-filter_threads", "1", "-vf", "fps="+decimal(mediaFps), "-q:v", "4", "-threads:v", "1"));
            if (!session.play) command.addAll(List.of("-frames:v", "1"));
            command.addAll(List.of("-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"));
            process = launch(command);
            synchronized (lock) {
                if (!active(session)) return;
                session.video = process;
            }
            Process decoding = process;
            background("recording-playback-errors", () -> consumeErrors(decoding));
            int frameIndex = 0;
            try (BufferedInputStream input = new BufferedInputStream(process.getInputStream(), 128 * 1024)) {
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
                int previous = -1;
                boolean inside = false;
                int next;
                while (active(session) && (next = input.read()) >= 0) {
                    if (!inside) {
                        if (previous == 0xff && next == 0xd8) {
                            inside = true;
                            jpeg.reset();
                            jpeg.write(0xff);
                            jpeg.write(0xd8);
                        }
                    } else {
                        jpeg.write(next);
                        if (jpeg.size() > MAX_JPEG_BYTES) throw new IOException("Frame too large");
                        if (previous == 0xff && next == 0xd9) {
                            BufferedImage image;
                            try (MemoryCacheImageInputStream bytes = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg.toByteArray()))) {
                                var readers = ImageIO.getImageReaders(bytes);
                                if (!readers.hasNext()) throw new IOException("Invalid frame");
                                ImageReader reader = readers.next();
                                try {
                                    reader.setInput(bytes, true, true);
                                    int width = reader.getWidth(0);
                                    int height = reader.getHeight(0);
                                    if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                                            || (long) width * height > 20_000_000L) throw new IOException("Frame dimensions invalid");
                                    image = reader.read(0);
                                } finally {
                                    reader.dispose();
                                }
                            }
                            if (image == null) throw new IOException("Invalid frame");
                            if (frameIndex == 0) {
                                synchronized (lock) {
                                    if (!active(session)) return;
                                    session.clockStart = System.nanoTime();
                                }
                                if (session.play && session.withAudio) background("recording-playback-audio", () -> startAudio(session));
                            }
                            if (!waitForFrame(session, frameIndex / mediaFps)) return;
                            if (active(session)) events.onFrame(image);
                            frameIndex++;
                            inside = false;
                            jpeg.reset();
                            if (!session.play) return;
                        }
                    }
                    previous = next;
                }
            }
            if (!active(session)) return;
            if (frameIndex == 0) throw new PlaybackFailure("这段录像暂时无法解码，请检查文件是否已录制完成。");
            if (session.play) {
                int exit = process.waitFor();
                if (exit != 0 && !growing) throw new PlaybackFailure("回放中断，录像文件可能不完整。");
                double decodedEnd = Math.min(duration, session.start + frameIndex / mediaFps);
                if (!waitForFrame(session, Math.max(0, decodedEnd - session.start))) return;
                synchronized (lock) {
                    if (!active(session)) return;
                    position = growing ? decodedEnd : duration;
                    playing = false;
                    requestedPlaying = growing;
                    session.stopped = true;
                    current = null;
                    state(growing ? "WAITING" : "ENDED", growing ? "已到最新录像，等待新画面…" : "回放结束");
                }
            }
        } catch (PlaybackFailure failure) {
            sessionFailure(session, failure.getMessage());
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (active(session)) {
                log("回放失败（" + failure.getClass().getSimpleName() + "）");
                sessionFailure(session, "无法回放这段录像，请检查文件是否完整。");
            }
        } finally {
            terminate(process);
            if (session.play) terminate(session.audio);
        }
    }

    private boolean waitForFrame(Session session, double mediaSeconds) throws InterruptedException {
        if (!session.play) return active(session);
        long target = session.clockStart + (long) (mediaSeconds / session.speed * 1.0e9);
        while (active(session)) {
            long left = target - System.nanoTime();
            if (left <= 0) return true;
            TimeUnit.NANOSECONDS.sleep(Math.min(left, TimeUnit.MILLISECONDS.toNanos(40)));
        }
        return false;
    }

    private void startAudio(Session session) {
        Process process = null;
        try {
            if (!active(session)) return;
            double at = session.start + Math.max(0, (System.nanoTime() - session.clockStart) / 1.0e9 * session.speed);
            List<String> command = new ArrayList<>(List.of(ffplay.toString(), "-hide_banner", "-loglevel", "error", "-nostats",
                    "-nodisp", "-autoexit", "-vn", "-ss", decimal(at), "-af", audioSpeedFilter(session.speed),
                    "-i", recording.toString()));
            process = launch(command);
            synchronized (lock) {
                if (!active(session)) return;
                session.audio = process;
            }
            Process sound = process;
            background("recording-audio-errors", () -> consumeErrors(sound));
            process.getOutputStream().close();
            process.getInputStream().close();
            process.waitFor();
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (active(session)) log("播放声音失败（" + failure.getClass().getSimpleName() + "）");
        } finally {
            terminate(process);
        }
    }

    private static String audioSpeedFilter(double speed) {
        if(speed==8) return "atempo=2,atempo=2,atempo=2";
        if(speed==4) return "atempo=2,atempo=2";
        return "atempo="+decimal(speed);
    }

    private boolean active(Session session) {
        synchronized (lock) { return !closed && !session.stopped && current == session && session.generation == generation; }
    }

    private void sessionFailure(Session session, String message) {
        synchronized (lock) {
            if (!active(session)) return;
            position = positionLocked();
            playing = false;
            requestedPlaying = false;
            cancelLocked();
            state("ERROR", message);
        }
    }

    private void fail(String message) {
        synchronized (lock) {
            if (closed) return;
            playing = false;
            requestedPlaying = false;
            state("ERROR", message);
        }
    }

    private void state(String state, String detail) {
        if (!closed) events.onState(state, detail);
    }

    private Process launch(List<String> command) throws IOException {
        synchronized (processLock) {
            if (closed) throw new IOException("Playback closed");
            Process process = new ProcessBuilder(command).start();
            processes.add(process);
            return process;
        }
    }

    private void consumeErrors(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                if (++lines <= 30 && !closed) log(line.length() > 600 ? line.substring(0, 600) : line);
            }
        } catch (IOException ignored) { }
    }

    private static void consumeText(java.io.InputStream input, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int bytes = 0;
            while ((line = reader.readLine()) != null) {
                if (bytes < 64 * 1024) { output.append(line).append('\n'); bytes += line.length(); }
            }
        } catch (IOException ignored) { }
    }

    private void log(String text) {
        synchronized (logLock) {
            try {
                Files.createDirectories(logFile.getParent());
                if (Files.exists(logFile) && Files.size(logFile) > 1024 * 1024) {
                    Files.writeString(logFile, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
                Files.writeString(logFile, LocalDateTime.now() + " " + text + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }
    }

    private void terminate(Process process) {
        if (process == null) return;
        try {
            process.destroy();
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(800, TimeUnit.MILLISECONDS);
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

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            position = positionLocked();
            closed = true;
            playing = false;
            requestedPlaying = false;
            cancelScrubLocked();
            cancelLocked();
        }
        decoder.shutdownNow();
        synchronized (processLock) { /* Prevent a launch racing with close. */ }
        for (Process process : List.copyOf(processes)) terminate(process);
    }

    private static String decimal(double value) { return String.format(Locale.ROOT, "%.4f", value); }
    private static Thread background(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static final class Session {
        final long generation;
        final double start;
        final double speed;
        final boolean play;
        final boolean withAudio;
        volatile boolean stopped;
        volatile long clockStart;
        volatile Process video;
        volatile Process audio;
        Session(long generation, double start, double speed, boolean play, boolean audio) {
            this.generation = generation;
            this.start = start;
            this.speed = speed;
            this.play = play;
            this.withAudio = audio;
        }
    }

    private static final class PlaybackFailure extends Exception {
        PlaybackFailure(String message) { super(message); }
    }
}
