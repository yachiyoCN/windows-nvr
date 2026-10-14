package cn.homewatch;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Multi-channel NVR controller. Viewing never owns the recording lifecycle. */
public final class HomeMonitor implements MonitorWindow.Listener {
    private final Path root;
    private final MonitorWindow window;
    private final DeviceRegistry registry;
    private final SharedStorageService storage;
    private final StorageDraft storageDraft;
    private final RemoteAccessService remoteAccess;
    private final ConcurrentHashMap<Path, AtomicLong> remotePlaybackUses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Channel> channels = new CopyOnWriteArrayList<>();
    private volatile Channel selected;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong layoutRevision = new AtomicLong();
    // An explicit All command controls new channels for this run without rewriting storage preferences.
    private volatile Boolean allRecordingOverride;
    private FileChannel instanceChannel;
    private FileLock instanceLock;
    private Timer archiveTimer, activationTimer;
    private DeviceManagerPanel deviceManager;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final class Channel implements AutoCloseable {
        final String id;
        final Path dataRoot;
        volatile CameraConfig config;
        volatile BufferedImage frame;
        volatile String state = "DISCONNECTED", detail = "等待连接摄像头";
        final AtomicBoolean wantConnection = new AtomicBoolean(), closed = new AtomicBoolean(), frameDispatch = new AtomicBoolean();
        boolean preferredAudio;
        volatile Boolean recordingOverride;
        StreamEngine engine;
        RecorderService recorder;
        RecordingCatalog catalog;
        PersonEventService persons;
        PlaybackWindow playback;
        NvrSettingsPanel settings;
        RemoteCameraPreview remotePreview;
        Channel(DeviceRegistry.Device device) throws IOException {
            id = device.id(); config = device.config(); dataRoot = device.dataRoot(root);
            try {
            prepareDirectories(dataRoot);
            engine = new StreamEngine(root, dataRoot, new StreamEngine.Events() {
                public void onState(String state, String detail) {
                    Channel.this.state = state; Channel.this.detail = detail;
                    if (state.equals("DISCONNECTED") || state.equals("ERROR")) wantConnection.set(false);
                    refreshChannelState(Channel.this);
                }
                public void onFrame(BufferedImage image) {
                    if (closed.get()) return;
                    frame = image;
                    window.setDeviceFrame(id, image);
                    if (frameDispatch.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> {
                        frameDispatch.set(false);
                        if (selected == Channel.this && !closed.get()) window.setFrame(frame);
                    });
                }
                public void onNotice(String message, boolean error) { channelNotice(Channel.this, message, error); }
                public void onRecording(boolean active) { }
                public void onFormat(int width, int height, String profile) {
                    if (settings != null) settings.setVideoFormat(width, height, profile);
                }
                public void onAudio(boolean enabled) {
                    SwingUtilities.invokeLater(() -> { if (selected == Channel.this && !closed.get()) window.setAudioEnabled(enabled); });
                }
            });
            recorder = new RecorderService(root, dataRoot, new RecorderService.Events() {
                public void onState(boolean active, String detail) {
                    refreshChannelState(Channel.this);
                    if (detail == null || detail.isBlank()) return;
                    boolean failure = !active && recorder != null && recorder.isRequested()
                            && (detail.startsWith("录像认证失败") || detail.startsWith("录像连接超时")
                            || detail.startsWith("保存目录空间不足") || detail.startsWith("保存目录无法写入")
                            || detail.startsWith("录像时间水印"));
                    if (failure) channelNotice(Channel.this, detail, true);
                    else SwingUtilities.invokeLater(() -> {
                        if (selected == Channel.this && !closed.get()) window.showProgressNotice(detail);
                    });
                }
                public void onChanged() { if (catalog != null && persons != null) refreshArchive(); }
                public boolean isPlaybackInUse(Path path) {
                    return (persons != null && persons.isAnalyzing(path))
                            || remotePlaybackUses.containsKey(path.toAbsolutePath().normalize())
                            || channels.stream().anyMatch(c -> c.playback != null && path.equals(c.playback.recordingPath()));
                }
                public void onNotice(String message, boolean error) { channelNotice(Channel.this, message, error); }
            }, storage);
            catalog = new RecordingCatalog(root, dataRoot, this::refreshPlayback);
            persons = new PersonEventService(root, dataRoot, this::refreshPlayback);
            playback = new PlaybackWindow(root, catalog, persons, HomeMonitor.this::onLive, window::showSettings, true);
            playback.setEmbeddedHostCallbacks(() -> enterPlayback(false), window::toggleFullscreen);
            settings = new NvrSettingsPanel(root, recorder, persons, storage, storageDraft, new NvrSettingsPanel.Listener() {
                public void saveCamera(String name, String url, String transport, boolean autoConnect) throws Exception {
                    saveCameraSettings(Channel.this, name, url, transport, autoConnect, config.quality(), config.recordingTimestamp());
                }
                public void saveCamera(String name, String url, String transport, boolean autoConnect, VideoQuality quality) throws Exception {
                    saveCameraSettings(Channel.this, name, url, transport, autoConnect, quality, config.recordingTimestamp());
                }
                public void saveCamera(String name, String url, String transport, boolean autoConnect, VideoQuality quality, boolean recordingTimestamp) throws Exception {
                    saveCameraSettings(Channel.this, name, url, transport, autoConnect, quality, recordingTimestamp);
                }
                public void notice(String message, boolean error) { channelNotice(Channel.this, message, error); }
            });
            settings.setCameraValues(config.name(), config.url(), config.transport(), config.autoConnect(), config.quality(), config.recordingTimestamp());
            settings.setDetectionFrameSupplier(() -> remotePreview == null
                    ? (engine.isConnected() ? frame : null) : remotePreview.latestFrame());
            settings.setRemoteAccessService(remoteAccess);
            remotePreview = new RemoteCameraPreview(root, dataRoot,
                    () -> new StreamEngine.Settings(config.url(), config.transport(), config.quality()),
                    () -> !closed.get() && engine.isConnected(), () -> frame);
            } catch (IOException | RuntimeException failure) { close(); throw failure; }
        }
        void refreshArchive() {
            if (closed.get()) return;
            var entries = recorder.listRecordings(); catalog.refresh(entries); persons.refresh(entries);
        }
        void refreshPlayback() { if (!closed.get() && playback != null) playback.refreshTimeline(); }
        void start() {
            playback.showWindow(); refreshArchive();
            Boolean channelOverride = recordingOverride, groupOverride = allRecordingOverride;
            boolean startRecorder = channelOverride != null ? channelOverride
                    : groupOverride != null ? groupOverride : recorder.settings().autoRecord();
            if (startRecorder && !config.url().isBlank()) startRecording(this);
            if (config.autoConnect() && !config.url().isBlank()) connect(this);
        }
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (settings != null) settings.close();
            if (remotePreview != null) remotePreview.close();
            if (recorder != null) recorder.close();
            if (persons != null) persons.close();
            if (catalog != null) catalog.close();
            if (playback != null) playback.disposeWindow();
            if (engine != null) engine.close();
        }
    }

    private HomeMonitor(Path root, CameraConfig ignoredLegacy) throws IOException {
        this.root = root;
        instanceChannel = FileChannel.open(root.resolve("data/.instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        instanceLock = instanceChannel.tryLock();
        if (instanceLock == null) {
            instanceChannel.close();
            Files.writeString(root.resolve("data/.activate"), "show", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            throw new AlreadyRunningException();
        }
        try { registry = new DeviceRegistry(root); }
        catch (IOException failure) { releaseLock(); throw failure; }
        try { storage = new SharedStorageService(root); }
        catch (IOException failure) { releaseLock(); throw failure; }
        storageDraft = new StorageDraft(root, storage.settings());
        window = new MonitorWindow(this);
        try { remoteAccess = new RemoteAccessService(root, root, new RemoteBackend(), state -> {}); }
        catch (IOException failure) { storage.close(); window.disposeWindow(); releaseLock(); throw failure; }
        try {
            for (var device : registry.devices()) channels.add(new Channel(device));
        } catch (IOException | RuntimeException failure) {
            for (Channel c : channels) c.close();
            remoteAccess.close();
            storage.close();
            releaseLock(); throw failure;
        }
        selected = channels.stream().filter(c -> c.id.equals(registry.selectedId())).findFirst().orElse(channels.isEmpty() ? null : channels.getFirst());
        updateDevices();
        window.setGridLayout(registry.layout());
        attachSelected();
        archiveTimer = new Timer(2000, e -> { if (!closing.get()) for (Channel c : channels) c.refreshArchive(); });
        activationTimer = new Timer(500, e -> {
            if (closing.get()) return;
            try { if (Files.deleteIfExists(root.resolve("data/.activate"))) window.showWindow(); } catch (IOException ignored) { }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownServices, "home-monitor-shutdown"));
    }
    private static void prepareDirectories(Path root) throws IOException {
        for (String dir : new String[]{"config", "data/screenshots", "data/recordings", "data/logs", "data/temp", "data/prefs"}) Files.createDirectories(root.resolve(dir));
    }
    private void start() {
        window.showWindow(); activationTimer.start(); archiveTimer.start();
        Thread.startVirtualThread(() -> {
            try { remoteAccess.start(); }
            catch (IOException unavailable) {
                if (!closing.get()) window.showNotice("远程服务未能启动，请打开远程访问查看设置。", true);
            }
        });
        Timer startup = new Timer(400, e -> {
            if (closing.get()) return;
            for (Channel c : channels) c.start();
            if (selected != null && selected.config.url().isBlank()) window.showCameraSettings();
        });
        startup.setRepeats(false); startup.start();
    }
    private void updateDevices() {
        var summaries = channels.stream().map(c -> new MonitorWindow.DeviceSummary(c.id, c.config.name(), c.config.publicEndpoint())).toList();
        String id = selected == null ? registry.selectedId() : selected.id;
        window.setDevices(summaries, id);
        refreshRecordingGroupState();
        if (deviceManager != null) deviceManager.setDevices(summaries, id);
    }
    private void attachSelected() {
        Channel c = selected; if (c == null) return;
        window.attachConsole(c.playback, c.settings);
        window.setFrame(c.frame);
        window.setCameraInfo(c.config.name(), c.config.publicEndpoint(), c.config.transport().toUpperCase());
        window.setConnectionState(c.state, c.detail);
        window.setRecordingState(c.recorder.isRequested(), c.recorder.isRecording(), c.recorder.stateDetail());
        refreshRecordingGroupState();
        window.setAudioEnabled(c.engine.isAudioEnabled());
    }
    private void refreshChannelState(Channel c) {
        SwingUtilities.invokeLater(() -> {
            if (closing.get() || c.closed.get()) return;
            window.setDeviceState(c.id, c.state, c.recorder != null && c.recorder.isRecording());
            if (selected == c) {
                window.setConnectionState(c.state, c.detail);
                if (c.recorder != null) window.setRecordingState(c.recorder.isRequested(), c.recorder.isRecording(), c.recorder.stateDetail());
            }
            refreshRecordingGroupState();
        });
    }
    private void refreshRecordingGroupState() {
        int configured=0, requested=0, active=0, unconfigured=0;
        for (Channel channel : channels) {
            if (channel.closed.get() || channel.recorder==null) continue;
            if (channel.config.url().isBlank()) unconfigured++; else configured++;
            if (channel.recorder.isRequested()) requested++;
            if (channel.recorder.isRecording()) active++;
        }
        window.setRecordingGroupState(configured,requested,active,unconfigured);
    }
    private void channelNotice(Channel c, String message, boolean error) {
        SwingUtilities.invokeLater(() -> {
            if (closing.get() || c.closed.get()) return;
            if (selected == c || error) window.showNotice((selected == c ? "" : c.config.name() + "：") + message, error);
        });
    }
    private void connect(Channel c) {
        try {
            CameraConfig.validateUrl(c.config.url());
            if (!Files.isRegularFile(root.resolve("tools/ffmpeg.exe"))) throw new IllegalArgumentException("缺少视频组件，请重新解压完整程序。");
            c.wantConnection.set(true);
            c.engine.connect(new StreamEngine.Settings(c.config.url(), c.config.transport(), c.config.quality()));
        } catch (IllegalArgumentException ex) { c.wantConnection.set(false); channelNotice(c, ex.getMessage(), true); }
    }
    @Override public void onConnectToggle() {
        Channel c = selected; if (closing.get() || c == null) return;
        if (c.wantConnection.getAndSet(false)) c.engine.disconnect(); else connect(c);
    }
    @Override public void onAudioToggle() {
        Channel c = selected; if (closing.get() || c == null) return;
        if (window.isPlaybackMode()) c.playback.setAudioEnabled(!c.playback.isAudioEnabled());
        else { c.preferredAudio = !c.engine.isAudioEnabled(); c.engine.setAudioEnabled(c.preferredAudio); }
    }
    @Override public void onSnapshot() {
        Channel c = selected; if (closing.get() || c == null) return;
        BufferedImage frame = window.currentFrame();
        if (frame == null) { window.showNotice("当前通道显示画面后，就可以截图。", false); return; }
        Thread.startVirtualThread(() -> {
            String safeName = c.config.name().replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = c.dataRoot.resolve("data/screenshots/" + safeName + "_" + FILE_TIME.format(LocalDateTime.now()) + ".png");
            try {
                if (!ImageIO.write(frame, "png", target.toFile())) throw new IOException("没有可用的PNG编码器");
                channelNotice(c, "截图已保存：" + target.getFileName(), false);
            } catch (IOException ex) { channelNotice(c, "截图保存失败，请检查保存目录的空间和写入权限。", true); }
        });
    }
    @Override public void onRecordingToggle() {
        Channel c = selected; if (closing.get() || c == null) return;
        c.recordingOverride = !c.recorder.isRequested();
        if (!c.recordingOverride) c.recorder.stop(); else startRecording(c);
        refreshRecordingGroupState();
    }
    @Override public synchronized void onStartAllRecording() {
        if (closing.get()) return;
        allRecordingOverride=true;
        int started=0, unavailable=0;
        for (Channel channel : channels) {
            if (channel.closed.get()) continue;
            channel.recordingOverride=null;
            if (channel.config.url().isBlank()) { unavailable++; continue; }
            if (startRecording(channel)) started++;
        }
        refreshRecordingGroupState();
        window.showNotice(started>0 ? "已开启全部 "+started+" 路录像"+(unavailable>0?"；另有 "+unavailable+" 路尚未设置地址":"")
                +"。可在录像机设置中开启下次启动自动录制全部通道。" : "请先添加摄像头或填写播放地址，再开启全部录像。", started==0);
    }
    @Override public synchronized void onStopAllRecording() {
        if (closing.get()) return;
        allRecordingOverride=false;
        for (Channel channel : channels) {
            if (channel.closed.get()) continue;
            channel.recordingOverride=null;
            channel.recorder.stop();
        }
        refreshRecordingGroupState();
        window.showNotice("已停止全部摄像头录像，正在保存最后一段。实时预览继续。", false);
    }
    private boolean startRecording(Channel c) {
        try { CameraConfig.validateUrl(c.config.url()); c.recorder.start(new StreamEngine.Settings(c.config.url(), c.config.transport(), c.config.quality(), c.config.recordingTimestamp())); return c.recorder.isRequested(); }
        catch (IllegalArgumentException ex) { channelNotice(c, ex.getMessage(), true); return false; }
    }
    @Override public void onPlayback() { enterPlayback(true); }
    private void enterPlayback(boolean play) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> enterPlayback(play)); return; }
        Channel c = selected; if (closing.get() || c == null) return;
        c.refreshArchive(); c.engine.setAudioEnabled(false); window.setPlaybackMode(true);
        if (play) c.playback.play();
    }
    @Override public void onLive() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::onLive); return; }
        Channel c = selected; if (closing.get() || c == null) return;
        window.setPlaybackMode(false); c.engine.setAudioEnabled(c.preferredAudio);
        if (!c.wantConnection.get()) connect(c);
    }
    @Override public void onSelectDevice(String id) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> onSelectDevice(id)); return; }
        if (closing.get() || selected != null && selected.id.equals(id)) return;
        Channel next = channels.stream().filter(c -> c.id.equals(id) && !c.closed.get()).findFirst().orElse(null);
        if (next == null) return;
        if (selected != null) { selected.engine.setAudioEnabled(false); selected.playback.pause(); selected.playback.setAudioEnabled(false); }
        selected = next;
        attachSelected(); updateDevices();
        if (!window.isPlaybackMode()) next.engine.setAudioEnabled(next.preferredAudio);
        Thread.startVirtualThread(() -> {
            try { synchronized (registry) { if (selected == next && !next.closed.get()) registry.select(id); } }
            catch (IOException | IllegalArgumentException failure) { channelNotice(next, "通道选择未能保存，重启后会恢复上次选择。", true); }
        });
    }
    @Override public void onLayoutChanged(int tiles) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> onLayoutChanged(tiles)); return; }
        if (closing.get()) return;
        window.setGridLayout(tiles);
        long revision = layoutRevision.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try { synchronized (registry) { if (!closing.get() && layoutRevision.get() == revision) registry.setLayout(tiles); } }
            catch (IOException failure) { window.showNotice("分屏设置未能保存。", true); }
        });
    }
    @Override public void onAddDevice() { onManageDevices(); }
    @Override public void onManageDevices() {
        if (closing.get()) return;
        if (deviceManager == null) deviceManager = new DeviceManagerPanel(new DeviceManagerPanel.Listener() {
            public void add(String name, String url, String transport, boolean autoConnect) throws Exception { addDevice(name, url, transport, autoConnect); }
            public void remove(String id) throws Exception { removeDevice(id); }
            public void cancel() { window.showSettings(); }
            public void notice(String message, boolean error) { window.showNotice(message, error); }
        });
        updateDevices(); window.showDeviceManager(deviceManager);
    }
    private synchronized void addDevice(String name, String url, String transport, boolean autoConnect) throws IOException {
        if (closing.get()) throw new IOException("软件正在关闭。");
        CameraConfig config = new CameraConfig(name.trim(), url.trim(), "udp".equalsIgnoreCase(transport) ? "udp" : "tcp", autoConnect);
        DeviceRegistry.Device device = registry.add(config);
        Channel c;
        try { c = new Channel(device); }
        catch (IOException | RuntimeException failure) { registry.remove(device.id()); throw failure; }
        channels.add(c);
        if (closing.get()) { c.close(); return; }
        SwingUtilities.invokeLater(() -> {
            if (closing.get()) return;
            updateDevices(); onSelectDevice(c.id); window.showSettings(); c.start();
            window.showNotice("已添加“" + c.config.name() + "”。所有摄像头共用录像存储设置。", false);
        });
    }
    private synchronized void removeDevice(String id) throws IOException {
        if (closing.get()) throw new IOException("软件正在关闭。");
        Channel c = channels.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("设备已移除。"));
        registry.remove(id);
        // Close and finalize recordings before removing a channel. Its files and private indexes remain untouched.
        c.close(); channels.remove(c);
        SwingUtilities.invokeLater(() -> {
            if (closing.get()) return;
            if (selected == c) { selected = null; onSelectDevice(registry.selectedId()); }
            updateDevices(); window.showSettings();
            window.showNotice("设备已移除，已有录像和索引已保留。", false);
        });
    }
    @Override public void onRemoveDevice(String id) {
        Thread.startVirtualThread(() -> { try { removeDevice(id); } catch (IOException | IllegalArgumentException failure) { window.showNotice(failure.getMessage(), true); } });
    }
    @Override public void onOpenFolder() {
        Channel c = selected; if (c == null) return;
        Thread.startVirtualThread(() -> {
            try { Desktop.getDesktop().open(storage.settings().resolvedDirectory(root).toFile()); }
            catch (Exception ex) { channelNotice(c, "录像保存在录像机的公共录像目录中。", false); }
        });
    }
    @Override public void onSettings() { if (!closing.get()) window.toggleSettingsPanel(); }
    private synchronized void saveCameraSettings(Channel c, String name, String url, String transport, boolean autoConnect, VideoQuality quality, boolean recordingTimestamp) throws IOException {
        if (closing.get() || c.closed.get()) throw new IOException("设备已关闭。");
        CameraConfig next = new CameraConfig(name == null || name.isBlank() ? "摄像头" : name.trim(), url == null ? "" : url.trim(),
                "udp".equalsIgnoreCase(transport) ? "udp" : "tcp", autoConnect, quality, recordingTimestamp);
        registry.update(c.id, next);
        boolean sourceChanged = !next.url().equals(c.config.url()) || !next.transport().equalsIgnoreCase(c.config.transport()) || next.quality() != c.config.quality();
        boolean recordingChanged = sourceChanged || next.recordingTimestamp() != c.config.recordingTimestamp();
        boolean wasUnconfigured = c.config.url().isBlank();
        boolean reconnect = sourceChanged && c.wantConnection.get();
        if (reconnect) c.engine.disconnect();
        c.config = next;
        if (recordingChanged && c.recorder.isRequested()) c.recorder.start(new StreamEngine.Settings(next.url(), next.transport(), next.quality(), next.recordingTimestamp()));
        else if (wasUnconfigured && !next.url().isBlank()) {
            Boolean channelOverride=c.recordingOverride, groupOverride=allRecordingOverride;
            if (channelOverride != null ? channelOverride : groupOverride != null ? groupOverride : storage.settings().autoRecord()) startRecording(c);
        }
        if (sourceChanged) c.settings.setVideoFormat(0, 0, "");
        if (reconnect && !closing.get()) connect(c);
        SwingUtilities.invokeLater(() -> {
            if (closing.get()) return;
            updateDevices();
            if (selected == c) window.setCameraInfo(c.config.name(), c.config.publicEndpoint(), c.config.transport().toUpperCase());
        });
    }
    @Override public void onClose() {
        if (!closing.compareAndSet(false, true)) return;
        activationTimer.stop(); archiveTimer.stop();
        window.showNotice("正在关闭各通道并保存录像…", false);
        Thread.startVirtualThread(() -> { shutdownServices(); window.disposeWindow(); System.exit(0); });
    }
    private synchronized void shutdownServices() {
        closing.set(true);
        remoteAccess.close();
        for (Channel c : channels) c.close();
        storage.close();
        remotePlaybackUses.clear();
        releaseLock();
    }

    /** HTTP adapters expose view data; camera connection secrets stay inside the recorder. */
    private final class RemoteBackend implements RemoteAccessService.Backend {
        private Channel channel(String id) {
            return channels.stream().filter(c -> !c.closed.get() && c.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("摄像头不存在"));
        }
        public RemoteAccessService.ServerSnapshot serverSnapshot() {
            RecorderSettings policy = storage.settings();
            return new RemoteAccessService.ServerSnapshot(policy.maxBytes(), storage.usedBytes(), policy.keepDays(), policy.segmentMinutes());
        }
        public List<RemoteAccessService.CameraInfo> cameras() {
            List<RemoteAccessService.CameraInfo> result = new ArrayList<>();
            int number = 0;
            for (Channel c : channels) {
                number++;
                if (c.closed.get()) continue;
                BufferedImage current = c.remotePreview == null ? c.frame : c.remotePreview.latestFrame();
                result.add(new RemoteAccessService.CameraInfo(c.id, c.config.name(), number, current != null,
                        c.recorder.isRecording(), current == null ? 0 : current.getWidth(), current == null ? 0 : current.getHeight()));
            }
            return List.copyOf(result);
        }
        public List<RemoteAccessService.RemoteRecording> recordings(String channelId, Instant from, Instant to) {
            if (channelId != null && !channelId.isBlank()) channel(channelId);
            List<RemoteAccessService.RemoteRecording> result = new ArrayList<>();
            for (Channel c : channels) {
                if (c.closed.get() || channelId != null && !channelId.isBlank() && !c.id.equals(channelId)) continue;
                var entries = c.recorder.listRecordings().stream().collect(java.util.stream.Collectors.toMap(
                        RecordingEntry::path, item -> item, (first, second) -> second));
                for (TimelineClip clip : c.catalog.snapshot()) {
                    if (from != null && !clip.end().isAfter(from) || to != null && !clip.start().isBefore(to)) continue;
                    PersonEventService.DetectionSnapshot persons = c.persons.snapshot(clip.path());
                    RecordingEntry entry = entries.get(clip.path());
                    if (entry == null) continue;
                    result.add(new RemoteAccessService.RemoteRecording(c.id, clip.path(), clip.start(), clip.durationSeconds(),
                            entry.bytes(), clip.active(), persons.intervals(), persons.status().name()));
                }
            }
            result.sort(java.util.Comparator.comparing(RemoteAccessService.RemoteRecording::start));
            return List.copyOf(result);
        }
        public BufferedImage latestFrame(String channelId) {
            Channel c = channel(channelId);
            return c.remotePreview == null ? null : c.remotePreview.latestFrame();
        }
        public AutoCloseable acquireCamera(String channelId) {
            if (closing.get()) throw new IllegalStateException("录像机正在关闭");
            return channel(channelId).remotePreview.acquire();
        }
        public AutoCloseable acquireRecording(Path path) {
            if (closing.get()) throw new IllegalStateException("录像机正在关闭");
            Path normalized = path.toAbsolutePath().normalize();
            remotePlaybackUses.compute(normalized, (key, references) -> {
                if (references == null) references = new AtomicLong();
                references.incrementAndGet(); return references;
            });
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) remotePlaybackUses.computeIfPresent(normalized,
                        (key, references) -> references.decrementAndGet() <= 0 ? null : references);
            };
        }
    }
    private synchronized void releaseLock() {
        try { if (instanceLock != null && instanceLock.isValid()) instanceLock.release(); } catch (IOException ignored) {}
        try { if (instanceChannel != null && instanceChannel.isOpen()) instanceChannel.close(); } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        try {
            Path root = findRoot();
            for (String dir : new String[]{"config", "data/screenshots", "data/recordings", "data/logs", "data/temp", "data/prefs"}) Files.createDirectories(root.resolve(dir));
            System.setProperty("java.io.tmpdir", root.resolve("data/temp").toString());
            ImageIO.setUseCache(false);
            CameraConfig config = CameraConfig.load(root);
            if (args.length > 0 && args[0].equals("--check")) {
                DeviceRegistry registry = new DeviceRegistry(root);
                for (var device : registry.devices()) CameraConfig.validateUrl(device.config().url());
                if (!Files.isRegularFile(root.resolve("tools/ffmpeg.exe"))) throw new IOException("视频组件未安装");
                System.out.println("项目目录：" + root);
                System.out.println("Java：" + System.getProperty("java.version"));
                System.out.println("摄像头数量：" + registry.devices().size());
                for (var device : registry.devices()) System.out.println("摄像头：" + device.config().name() + " / " + device.config().publicEndpoint());
                System.out.println("配置与视频组件检查通过；这项检查不代表摄像头在线。");
                return;
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            Font uiFont = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
            for (Object key : UIManager.getDefaults().keySet()) {
                if (UIManager.get(key) instanceof Font) UIManager.put(key, uiFont);
            }
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    Files.writeString(root.resolve("data/logs/app.log"), LocalDateTime.now() + " " + thread.getName()
                            + " " + error.getClass().getSimpleName() + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {}
            });
            SwingUtilities.invokeLater(() -> {
                try { new HomeMonitor(root, config).start(); }
                catch (AlreadyRunningException ex) { System.exit(0); }
                catch (IOException ex) { JOptionPane.showMessageDialog(null, ex.getMessage(), AppIdentity.NAME, JOptionPane.INFORMATION_MESSAGE); }
            });
        } catch (Exception ex) {
            String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "软件无法启动，请确认完整项目已解压，且项目目录可以写入。";
            if (GraphicsEnvironment.isHeadless()) { System.err.println(message); System.exit(1); }
            else JOptionPane.showMessageDialog(null, message, AppIdentity.NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class AlreadyRunningException extends IOException {}

    private static Path findRoot() throws Exception {
        String specified = System.getProperty("homewatch.root");
        if (specified != null && !specified.isBlank()) return Path.of(specified).toAbsolutePath().normalize();
        Path code = Path.of(HomeMonitor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(code) && code.getParent().getFileName().toString().equals("app")) return code.getParent().getParent();
        return Path.of("").toAbsolutePath().normalize();
    }

    record CameraConfig(String name, String url, String transport, boolean autoConnect, VideoQuality quality, boolean recordingTimestamp) {
        CameraConfig(String name, String url, String transport, boolean autoConnect) { this(name, url, transport, autoConnect, VideoQuality.AUTO, false); }
        CameraConfig(String name, String url, String transport, boolean autoConnect, VideoQuality quality) { this(name, url, transport, autoConnect, quality, false); }
        CameraConfig { quality = quality == null ? VideoQuality.AUTO : quality; }
        static CameraConfig load(Path root) throws IOException {
            Properties p = new Properties();
            Path file = root.resolve("config/camera.properties");
            if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file)) { p.load(reader); }
            String transport = p.getProperty("transport", "tcp");
            return new CameraConfig(p.getProperty("name", "摄像头 1"), p.getProperty("url", ""),
                    transport.equalsIgnoreCase("udp") ? "udp" : "tcp", Boolean.parseBoolean(p.getProperty("autoConnect", "true")), VideoQuality.parse(p.getProperty("quality")),
                    Boolean.parseBoolean(p.getProperty("recordingTimestamp", "false")));
        }
        void save(Path root) throws IOException {
            Properties p = new Properties();
            p.setProperty("name", name); p.setProperty("url", url); p.setProperty("transport", transport); p.setProperty("autoConnect", Boolean.toString(autoConnect));
            p.setProperty("quality", quality.name());
            p.setProperty("recordingTimestamp", Boolean.toString(recordingTimestamp));
            Path file = root.resolve("config/camera.properties");
            Path temp = root.resolve("config/camera.properties.tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) { p.store(writer, "Home Monitor - local camera configuration"); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        }
        String publicEndpoint() {
            try { URI uri = URI.create(url); return uri.getHost() == null ? "尚未配置" : uri.getHost() + ":"
                    + (uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 554); }
            catch (Exception ex) { return "尚未配置"; }
        }
        static void validateUrl(String url) {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("请填写摄像头的 RTSP 或 HTTPS 视频地址。");
            try {
                URI uri = URI.create(url);
                if (!("rtsp".equalsIgnoreCase(uri.getScheme()) || "rtsps".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getHost().isBlank()) throw new Exception();
                if (uri.getPort() == 0 || uri.getPort() > 65535) throw new Exception();
                if (url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0 || url.indexOf('\0') >= 0) throw new Exception();
            } catch (Exception ex) { throw new IllegalArgumentException("请输入有效的 RTSP、RTSPS 或 HTTPS 视频地址，例如 rtsp://账号:密码@摄像头IP:554/视频路径；密码中的特殊字符需要 URL 编码。"); }
        }
    }
}
