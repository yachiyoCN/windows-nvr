package cn.homewatch;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerDateModel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;

/** A compact recording player with an embedded video surface and seek bar. */
public final class PlaybackWindow implements AutoCloseable {
    private static final Color SURFACE = new Color(248, 248, 249);
    private static final Color BORDER = new Color(216, 218, 221);
    private static final Color TEXT = new Color(63, 66, 70);
    private static final Color MUTED = new Color(125, 130, 136);
    private static final Color RED = new Color(207, 44, 49);
    private static final Color VIDEO = new Color(24, 26, 29);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private final Path rootDirectory;
    private volatile Path recording;
    private final RecordingCatalog catalog;
    private final PersonEventService persons;
    private final Runnable onLive;
    private final boolean embedded;
    private volatile boolean liveMode;
    private volatile BufferedImage liveFrame;
    private final AtomicBoolean liveRepaintPending = new AtomicBoolean();
    private String liveState = "DISCONNECTED";
    private String liveDetail = "等待连接摄像头";
    private String channelName = "";
    private boolean liveAudio;
    private Runnable onEnterPlayback = () -> {};
    private Runnable onHostFullscreen = () -> {};
    private Runnable onSettings = () -> {};
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile PlaybackEngine engine;
    private Instant recordingStart;
    private final DateTimeFormatter clockFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private volatile long sourceGeneration;
    private double desiredOffset;
    private boolean desiredPlay;
    private boolean sourceReady;
    private boolean positioningSource;
    private boolean dragging;
    private boolean dragResume;
    private boolean gap;
    private volatile Instant cursorTime;
    private double playbackSpeed = 1;
    private boolean audioOn;
    private JSpinner searchStartPicker;
    private JSpinner searchEndPicker;
    private volatile Instant queryStart;
    private volatile Instant queryEnd;
    private boolean autoSelectPending;
    private JLabel rangeLabel;
    private String queryError;
    private JLabel detectionLabel;
    private JLabel filenameLabel;
    private JPanel root;
    private JFrame window;
    private VideoPanel video;
    private JPanel videoHost;
    private JPanel controls, scrubber, timeTools, archiveInfo, playbackBar, heading;
    private final CardLayout videoCards = new CardLayout();
    private JComponent liveSurface;
    private JLabel stateLabel;
    private JLabel timeLabel;
    private JLabel resolutionLabel;
    private JButton playButton;
    private JButton stopButton;
    private JButton audioButton;
    private JButton fullscreenButton;
    private JButton clearQueryButton;
    private JButton rewindButton, forwardButton;
    private JComboBox<String> speedBox;
    private JComboBox<String> scaleBox;
    private boolean syncingScale;
    private RecordingTimeline timeline;
    private Timer timer;
    private Timer crossSeekTimer;
    private TimelineClip pendingClip;
    private Instant pendingTime;
    private volatile BufferedImage frame;
    private String state = "LOADING";
    private String detail = "正在读取录像…";
    private boolean started;
    private boolean fullscreen;
    private boolean videoFullscreen;
    private Rectangle normalBounds;
    private int normalState;

    public PlaybackWindow(Path rootDirectory, Path recording) {
        this(rootDirectory, recording, null, null, null, () -> {});
    }

    public PlaybackWindow(Path rootDirectory, Path recording, RecordingCatalog catalog,
                          PersonEventService persons, Instant start, Runnable onLive) {
        this(rootDirectory, recording, catalog, persons, start, onLive, null, false);
    }

    private PlaybackWindow(Path rootDirectory, Path recording, RecordingCatalog catalog,
                           PersonEventService persons, Instant start, Runnable onLive,
                           Runnable onSettings, boolean embedded) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.recording = recording == null ? null : recording.toAbsolutePath().normalize();
        this.catalog = catalog;
        this.persons = persons;
        this.onLive = onLive == null ? () -> {} : onLive;
        this.onSettings = onSettings == null ? () -> {} : onSettings;
        this.embedded = embedded;
        this.liveMode = embedded;
        this.recordingStart = start == null ? this.recording == null ? Instant.now() : fallbackStart(this.recording) : start;
        this.cursorTime = recordingStart;
        onEdtAndWait(this::buildUi);
        onEdtAndWait(() -> {
            if (this.recording != null) openClip(new TimelineClip(this.recording, recordingStart, 0, false), 0, false);
            else { autoSelectPending = true; showGap("还没有收到可回放画面，可以选择时间查询"); updateTimelineData(); }
            if (embedded) updateVideoStatus();
        });
    }

    public PlaybackWindow(Path rootDirectory, RecordingCatalog catalog, PersonEventService persons, Runnable onLive) {
        this(rootDirectory, (Path) null, catalog, persons, null, onLive);
    }

    public PlaybackWindow(Path rootDirectory, RecordingCatalog catalog, PersonEventService persons,
                          Runnable onLive, Runnable onSettings) {
        this(rootDirectory, null, catalog, persons, null, onLive, onSettings, false);
    }

    /** Shared video surface and archive controls, without a second desktop window. */
    public PlaybackWindow(Path rootDirectory, RecordingCatalog catalog, PersonEventService persons,
                          Runnable onLive, Runnable onSettings, boolean embedded) {
        this(rootDirectory, null, catalog, persons, null, onLive, onSettings, embedded);
    }

    public JComponent component() { return root; }

    /** The host removes application chrome while this player keeps only useful video controls. */
    public void setVideoFullscreen(boolean fullscreen) {
        onEdt(() -> { videoFullscreen=fullscreen; updateFullscreenLayout(); });
    }
    public boolean isVideoFullscreen() { return videoFullscreen; }

    public void setChannelName(String name) {
        onEdt(() -> { channelName = name == null ? "" : name.strip(); video.repaint(); });
    }

    /** Supplies the NVR live grid; null detaches it and restores this player's live surface. */
    public void setLiveSurface(JComponent surface) {
        onEdt(() -> {
            if (closed.get() || liveSurface == surface) return;
            if (liveSurface != null) videoHost.remove(liveSurface);
            liveSurface = surface;
            if (surface != null) videoHost.add(surface, "live");
            updateVideoSurface();
        });
    }

    private void updateVideoSurface() {
        if (videoHost == null) return;
        videoCards.show(videoHost, liveMode && liveSurface != null ? "live" : "archive");
        videoHost.revalidate();
        videoHost.repaint();
        updateFullscreenLayout();
    }
    public boolean isLiveMode() { return liveMode; }
    public BufferedImage currentFrame() { return liveMode ? liveFrame : frame; }
    public boolean isAudioEnabled() { return audioOn; }
    public String playbackDetail() { return liveMode ? liveDetail : detail; }
    public boolean isSourceAvailable() { return !liveMode && sourceReady && !gap; }
    public boolean isPlaybackRequested() { return !liveMode && desiredPlay; }

    public void setEmbeddedHostCallbacks(Runnable onEnterPlayback, Runnable onFullscreen) {
        onEdt(() -> {
            this.onEnterPlayback = onEnterPlayback == null ? () -> {} : onEnterPlayback;
            this.onHostFullscreen = onFullscreen == null ? () -> {} : onFullscreen;
        });
    }

    public void setLiveMode(boolean live) {
        onEdt(() -> applyLiveMode(live));
    }

    private void applyLiveMode(boolean live) {
        applyLiveMode(live, false);
    }

    private void applyLiveMode(boolean live, boolean preserveTimelineView) {
        if (!embedded || closed.get() || liveMode == live) return;
        liveMode = live;
        timeLabel.setVisible(!live);
        if (live) {
            desiredPlay = false;
            dragging = false;
            cancelCrossSeek();
            timeline.cancelInteraction();
            if (engine != null) { engine.pause(); engine.setAudioEnabled(false); }
            queryError = null;
            queryStart = queryEnd = null;
            timeline.clearRange();
            Instant now = Instant.now();
            Duration half = Duration.ofMinutes(15).dividedBy(2);
            timeline.showRange(now.minus(half), now.plus(half));
            timeline.setPosition(now);
            syncScale();
            updateTimelineData();
        } else {
            if (engine != null) engine.setAudioEnabled(audioOn);
            if (cursorTime != null) timeline.setPosition(cursorTime);
            if (!preserveTimelineView) showPlaybackPosition();
        }
        updateVideoStatus();
        updateVideoSurface();
        updateTime();
        video.repaint();
    }

    private void enterPlayback() {
        enterPlayback(false);
    }

    private void enterPlayback(boolean preserveTimelineView) {
        if (!embedded || !liveMode) return;
        applyLiveMode(false, preserveTimelineView);
        onEnterPlayback.run();
    }

    /** Explicit mode changes keep the archive playhead visible; dragging keeps its own view. */
    private void showPlaybackPosition() {
        if (cursorTime == null || timeline.isAdjusting()) return;
        timeline.setPosition(cursorTime);
        syncScale();
    }

    public void setLiveFrame(BufferedImage image) {
        if (closed.get()) return;
        liveFrame = image;
        if (!liveRepaintPending.compareAndSet(false, true)) return;
        onEdt(() -> {
            liveRepaintPending.set(false);
            if (!closed.get() && liveMode) { updateVideoStatus(); video.repaint(); }
        });
    }

    public void setLiveStatus(String state, String detail) {
        onEdt(() -> {
            if (closed.get()) return;
            liveState = state == null ? "DISCONNECTED" : state;
            liveDetail = detail == null ? "" : detail;
            if (liveMode) { updateVideoStatus(); video.repaint(); }
        });
    }

    public void setLiveAudio(boolean enabled) {
        onEdt(() -> { liveAudio = enabled; if (liveMode) video.repaint(); });
    }

    public void setAudioEnabled(boolean enabled) {
        onEdt(() -> {
            audioOn = enabled;
            if (engine != null) engine.setAudioEnabled(enabled && !liveMode);
        });
    }

    private void updateVideoStatus() {
        stateLabel.setText(liveMode ? liveDetail : detail);
        stateLabel.setToolTipText(liveMode ? liveDetail : detail);
        stateLabel.setForeground("ERROR".equals(liveMode ? liveState : state) ? RED : MUTED);
        BufferedImage visible = currentFrame();
        resolutionLabel.setText(visible == null ? "—" : visible.getWidth() + " × " + visible.getHeight());
        boolean playing = !liveMode && isPlaying();
        boolean completedAvailable = filteredClips().stream().anyMatch(clip -> clip.durationSeconds() > 0);
        boolean replayFromStart = atRecordingEnd() && nextCompletedClip() == null;
        playButton.setText(liveMode ? completedAvailable ? "回看录像" : "暂无录像"
                : playing ? "暂停" : replayFromStart ? "重新播放" : "播放");
        playButton.setIcon(new ControlIcon(playing ? "pause" : "play", Color.WHITE));
        playButton.setEnabled(liveMode ? completedAvailable : sourceReady && !gap || gap && completedAvailable);
        stopButton.setEnabled(!liveMode && sourceReady && !gap);
        speedBox.setEnabled(!liveMode && sourceReady && !gap);
        rewindButton.setEnabled(!liveMode && completedAvailable);
        forwardButton.setEnabled(!liveMode && completedAvailable);
        updateFullscreenLayout();
    }

    private void updateFullscreenLayout() {
        if(controls==null || playbackBar==null || fullscreenButton==null)return;
        boolean liveOnly=videoFullscreen && liveMode;
        timeTools.setVisible(!videoFullscreen);
        scrubber.setVisible(!liveOnly);
        playButton.setVisible(!liveOnly);
        stopButton.setVisible(!liveOnly && (!embedded || videoFullscreen));
        rewindButton.setVisible(!liveOnly);forwardButton.setVisible(!liveOnly);
        speedBox.setVisible(!liveOnly);scaleBox.setVisible(!liveOnly);
        fullscreenButton.setVisible(videoFullscreen || !embedded);
        fullscreenButton.setText(videoFullscreen?"退出全屏":"画面全屏");
        fullscreenButton.setToolTipText(videoFullscreen?"退出画面全屏（Esc 或双击画面）":"仅画面全屏，保留播放控制");
        if(heading!=null && !embedded)heading.setVisible(!videoFullscreen);
        Container target=videoFullscreen?playbackBar:archiveInfo;
        if(timeLabel.getParent()!=target){
            if(timeLabel.getParent()!=null)timeLabel.getParent().remove(timeLabel);
            target.add(timeLabel,BorderLayout.EAST);
        }
        int height=videoFullscreen?118:root.getHeight()<550?118:140;
        if(timeline.getPreferredSize().height!=height)timeline.setPreferredSize(new Dimension(800,height));
        controls.revalidate();root.revalidate();
    }

    private static boolean inputOwnsKeys() {
        Component focus=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus instanceof JTextComponent || focus instanceof JComboBox<?>
                || focus!=null && SwingUtilities.getAncestorOfClass(JSpinner.class,focus)!=null;
    }

    private static Instant fallbackStart(Path file) {
        try { return Files.getLastModifiedTime(file).toInstant(); }
        catch (Exception ignored) { return Instant.now(); }
    }

    private void openClip(TimelineClip clip, double offset, boolean play) {
        if (closed.get()) return;
        PlaybackEngine old = engine;
        recording = clip.path();
        recordingStart = clip.start();
        Instant target = recordingStart.plusMillis((long)(Math.max(0, offset)*1000));
        cursorTime = target;
        timeLabel.setText(clockFormat.format(target));
        timeline.setPosition(target);
        desiredOffset = Math.max(0, offset);
        desiredPlay = play;
        sourceReady = false;
        gap = false;
        long token = ++sourceGeneration;
        AtomicLong stateRevision = new AtomicLong();
        frame = null;
        state = "LOADING";
        detail = "正在读取录像…";
        updateVideoStatus();
        playButton.setEnabled(false);
        stopButton.setEnabled(false);
        speedBox.setEnabled(false);
        audioButton.setEnabled(false);
        video.repaint();
        if (old != null) Thread.startVirtualThread(old::close);
        filenameLabel.setText("连续时间轴 · 左右拖动浏览录像");
        filenameLabel.setToolTipText("所有已保存录像按实际时间排列，可跨日期拖动或查询时间区间");
        PlaybackEngine replacement = new PlaybackEngine(rootDirectory, recording, new PlaybackEngine.Events() {
            @Override public void onReady(double duration) {
                onEdt(() -> {
                    if (closed.get() || token != sourceGeneration) return;
                    sourceReady = true;
                    timeline.setEnabled(true);
                    playButton.setEnabled(true);
                    stopButton.setEnabled(!liveMode);
                    speedBox.setEnabled(!liveMode);
                    audioButton.setEnabled(true);
                    if (gap) return;
                    PlaybackEngine current = engine;
                    // Configuration can synchronously report READY at offset zero. Keep the
                    // selected clock stable until the requested seek has been applied.
                    positioningSource = true;
                    try {
                        current.setSpeed(playbackSpeed);
                        current.setAudioEnabled(audioOn && !liveMode);
                        if (dragging) {
                            current.beginScrub();
                            current.scrubTo(desiredOffset);
                        } else {
                            current.seek(desiredOffset);
                            if (desiredPlay && !liveMode) current.play();
                        }
                    } finally { positioningSource = false; }
                    refreshTimeline();
                    updateTime();
                });
            }
            @Override public void onState(String next, String message) {
                long revision = stateRevision.incrementAndGet();
                onEdt(() -> {
                    if (closed.get() || token != sourceGeneration || gap || revision != stateRevision.get()) return;
                    state = next;
                    detail = message;
                    updateVideoStatus();
                    if ("ERROR".equals(next) && engine.durationSeconds() == 0) {
                        playButton.setEnabled(false);
                        stopButton.setEnabled(false);
                    }
                    video.repaint();
                    updateTime();
                    if ("ENDED".equals(next) && desiredPlay && !dragging && !liveMode) advanceClip();
                });
            }
            @Override public void onFrame(BufferedImage image) {
                onEdt(() -> {
                    if (closed.get() || token != sourceGeneration || gap) return;
                    frame = image;
                    if (!liveMode) { updateVideoStatus(); video.repaint(); }
                });
            }
            @Override public void onAudio(boolean enabled) {
                onEdt(() -> {
                    if (closed.get() || token != sourceGeneration) return;
                    audioButton.setText(enabled ? "声音 开" : "声音 关");
                    audioButton.setForeground(enabled ? RED : TEXT);
                    audioButton.setIcon(new ControlIcon(enabled ? "speaker" : "mute", enabled ? RED : MUTED));
                });
            }
        }, clip.durationSeconds(), clip.active());
        engine = replacement;
        if (closed.get()) { replacement.close(); return; }
        replacement.prepare();
        if (window != null) window.setTitle("录像回放 · 连续时间轴");
    }

    public void showWindow() {
        onEdt(() -> {
            if (closed.get()) return;
            if (window != null) {
                window.setExtendedState(window.getExtendedState() & ~JFrame.ICONIFIED);
                window.setVisible(true);
                window.toFront();
                window.requestFocus();
            }
            if (!started) {
                started = true;
                timer.start();
                desiredPlay = !liveMode;
                if (sourceReady && !gap && engine != null && !liveMode) engine.play();
            }
        });
    }

    public void play() { onEdt(() -> {
        if (closed.get()) return;
        enterPlayback();
        desiredPlay = true;
        if (queryEnd != null && cursorTime != null && (cursorTime.isBefore(queryStart) || !cursorTime.isBefore(queryEnd))) {
            selectFirstInRange(); return;
        }
        if (gap) { playAvailableClip(); return; }
        if (atRecordingEnd()) {
            if (advanceClip()) return;
            desiredPlay = true;
        }
        if (!gap && sourceReady && engine != null) engine.play();
    }); }
    public void pause() { onEdt(() -> { desiredPlay = false; if (engine != null) engine.pause(); }); }
    public void seek(double seconds) { onEdt(() -> {
        double offset = Math.max(0, Double.isFinite(seconds) ? seconds : 0);
        seekOnTimeline(recordingStart.plusMillis((long) (offset * 1000)), false);
        if (!closed.get()) showPlaybackPosition();
    }); }
    public boolean isPlaying() { return !liveMode && engine != null && engine.isPlaying(); }
    public Path recordingPath() { return recording; }
    public double positionSeconds() { return engine == null ? 0 : engine.positionSeconds(); }
    public double durationSeconds() { return engine == null ? 0 : engine.durationSeconds(); }
    public boolean isClosed() { return closed.get(); }
    public void refreshTimeline() { onEdt(this::updateTimelineData); }
    public Instant selectedTime() { return cursorTime; }
    public void seekTime(Instant time) { onEdt(() -> {
        seekOnTimeline(time, false);
        if (!closed.get()) showPlaybackPosition();
    }); }
    /** Relative seeks use the recording clock and cross file boundaries instead of stopping at one segment. */
    public void seekBySeconds(int seconds) { onEdt(() -> {
        if (closed.get() || liveMode || seconds==0) return;
        List<TimelineClip> available=filteredClips().stream().filter(c -> c.durationSeconds()>0)
                .sorted(java.util.Comparator.comparing(TimelineClip::start)).toList();
        if(available.isEmpty())return;
        if(engine!=null && sourceReady && !gap)updateTime();
        Instant origin=cursorTime==null?available.getFirst().start():cursorTime;
        Instant target=constrainQuerySeek(origin.plusSeconds(seconds));
        Instant first=queryStart!=null && queryStart.isAfter(available.getFirst().start())?queryStart:available.getFirst().start();
        Instant last=available.getLast().end().minusMillis(1);
        if(queryEnd!=null && !last.isBefore(queryEnd))last=queryEnd.minusMillis(1);
        if(target.isBefore(first))target=first;
        if(target.isAfter(last))target=last;
        if(clipAt(target)==null){
            Instant selected=target;
            if(seconds>0)target=available.stream().filter(c -> c.start().isAfter(selected)).map(TimelineClip::start).findFirst().orElse(last);
            else target=available.stream().filter(c -> !c.end().isAfter(selected)).map(c -> c.end().minusMillis(1)).reduce((a,b)->b).orElse(first);
        }
        seekOnTimeline(target,false);showPlaybackPosition();
    }); }
    public Instant getQueryStart() { return queryStart; }
    public Instant getQueryEnd() { return queryEnd; }

    public void queryTimeRange(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start) || Duration.between(start, end).toMillis() < 1)
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        onEdt(() -> {
            if (closed.get()) return;
            enterPlayback(true);
            queryError = null;
            cancelCrossSeek();
            dragging = false;
            queryStart = start;
            queryEnd = end;
            searchStartPicker.setValue(Date.from(start));
            searchEndPicker.setValue(Date.from(end));
            timeline.setRange(start, end);
            syncScale();
            autoSelectPending = true;
            selectFirstInRange();
            updateTimelineData();
        });
    }

    public void clearQuery() {
        onEdt(() -> {
            if (closed.get()) return;
            queryError = null;
            queryStart = queryEnd = null;
            timeline.clearRange();
            // Removing a filter must not move the view or seek the video.
            syncScale();
            updateTimelineData();
        });
    }

    /** Close is safe from any thread. Blocking process cleanup stays off the UI thread. */
    public void closeWindow() {
        if (!closed.compareAndSet(false, true)) return;
        onEdt(() -> {
            timer.stop();
            timeline.cancelInteraction();
            crossSeekTimer.stop();
            pendingClip = null;
            pendingTime = null;
            if (window != null) window.dispose();
            frame = null;
            liveFrame = null;
        });
        PlaybackEngine current = engine;
        if (current == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            Thread closer = new Thread(current::close, "recording-window-close");
            closer.setDaemon(true);
            closer.start();
        } else current.close();
    }

    public void disposeWindow() { closeWindow(); }
    @Override public void close() { closeWindow(); }

    /** Produces a preview without opening a desktop window or starting a video process. */
    public BufferedImage renderPreview(int width, int height) {
        BufferedImage[] result = new BufferedImage[1];
        onEdtAndWait(() -> {
            syncScale();
            root.setSize(width, height);
            layoutTree(root);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            root.printAll(graphics);
            graphics.dispose();
            result[0] = image;
        });
        return result[0];
    }

    private void buildUi() {
        root = new JPanel(new BorderLayout());
        root.setFont(FONT);
        root.setBackground(SURFACE);
        heading = new JPanel(new BorderLayout(16, 0));
        heading.setBackground(SURFACE);
        heading.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(12, 18, 12, 18)));
        JLabel title = label("录像回放", new Font(FONT.getName(), Font.BOLD, 15), TEXT);
        title.setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, RED));
        title.setBorder(BorderFactory.createCompoundBorder(title.getBorder(), BorderFactory.createEmptyBorder(2, 10, 2, 12)));
        filenameLabel = label("连续时间轴 · 左右拖动浏览录像", FONT, MUTED);
        heading.add(title, BorderLayout.WEST);
        heading.add(filenameLabel, BorderLayout.CENTER);
        JButton liveButton = button("实时预览", "play", false);
        liveButton.addActionListener(event -> { onLive.run(); closeWindow(); });
        JPanel headingActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        headingActions.setOpaque(false);
        JButton settingsButton = button("录像设置", "", false);
        settingsButton.addActionListener(event -> onSettings.run());
        headingActions.add(settingsButton);
        headingActions.add(liveButton);
        heading.add(headingActions, BorderLayout.EAST);
        if (!embedded) root.add(heading, BorderLayout.NORTH);

        video = new VideoPanel();
        video.setBackground(VIDEO);
        video.setPreferredSize(new Dimension(1050, 520));
        video.setMinimumSize(new Dimension(160, 90));
        video.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 2) toggleFullscreen();
            }
        });
        videoHost = new JPanel(videoCards);
        videoHost.setBackground(VIDEO);
        videoHost.add(video, "archive");
        video.setToolTipText("双击画面全屏，按 Esc 返回");
        root.add(videoHost, BorderLayout.CENTER);

        controls = new JPanel(new BorderLayout(0, 6));
        controls.setBackground(SURFACE);
        controls.setBorder(BorderFactory.createEmptyBorder(8, embedded ? 12 : 18, 8, embedded ? 12 : 18));
        scrubber = new JPanel(new BorderLayout(0, 5));
        scrubber.setOpaque(false);
        timeline = new RecordingTimeline(this::seekOnTimeline);
        timeline.setDay(recordingStart.atZone(ZoneId.systemDefault()).toLocalDate());
        timeline.setPosition(recordingStart);
        timeline.setViewWindow(Duration.ofMinutes(15));
        timeTools = new JPanel(new BorderLayout(0, 5));
        timeTools.setOpaque(false);
        JPanel queryTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        queryTools.setOpaque(false);
        Instant dayStart = recordingStart.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant();
        searchStartPicker = dateTimePicker(dayStart);
        searchEndPicker = dateTimePicker(dayStart.plus(Duration.ofDays(1)));
        queryTools.add(label("开始", FONT.deriveFont(12f), TEXT));
        queryTools.add(searchStartPicker);
        queryTools.add(label("结束", FONT.deriveFont(12f), TEXT));
        queryTools.add(searchEndPicker);
        JButton queryButton = button("查询", "", true);
        queryButton.addActionListener(event -> {
            try {
                searchStartPicker.commitEdit();
                searchEndPicker.commitEdit();
                queryTimeRange(((Date) searchStartPicker.getValue()).toInstant(), ((Date) searchEndPicker.getValue()).toInstant());
            } catch (Exception invalid) {
                queryError = "时间无效，请检查起止时间";
                rangeLabel.setText(queryError);
                rangeLabel.setForeground(RED);
                rangeLabel.setToolTipText("请填写有效日期和时间，结束时间必须晚于开始时间");
                clearQueryButton.setEnabled(true);
            }
        });
        clearQueryButton = button("取消筛选", "", false);
        clearQueryButton.setToolTipText("显示全部录像，保留当前画面、时间与时间轴位置");
        clearQueryButton.addActionListener(event -> clearQuery());
        JButton detectionSettings = button("识别设置", "", false);
        detectionSettings.addActionListener(event -> showDetectionSettings());
        queryTools.add(queryButton);
        queryTools.add(clearQueryButton);
        if (!embedded) queryTools.add(detectionSettings);
        timeTools.add(queryTools, BorderLayout.NORTH);
        scaleBox = new JComboBox<>(new String[]{"24 小时", "6 小时", "1 小时", "15 分钟", "1 分钟", "查询范围", "自定义"});
        NvrUi.styleCombo(scaleBox);
        scaleBox.setPreferredSize(new Dimension(100, 32));
        scaleBox.setToolTipText("时间轴显示范围；也可在时间轴上滚动鼠标缩放");
        scaleBox.setSelectedIndex(3);
        scaleBox.addActionListener(event -> applyScale());
        rangeLabel = label("全部录像 · 可跨日期拖动", FONT.deriveFont(12f), MUTED);
        timeLabel = label(clockFormat.format(recordingStart), FONT.deriveFont(12f), TEXT);
        timeLabel.setVisible(!liveMode);
        timeLabel.setHorizontalAlignment(JLabel.RIGHT);
        timeLabel.setPreferredSize(new Dimension(164, 32));
        timeLabel.setToolTipText("当前画面对应的实际时间");
        scrubber.add(timeline, BorderLayout.CENTER);
        detectionLabel = label("人员事件：等待分析", FONT.deriveFont(12f), MUTED);
        archiveInfo = new JPanel(new BorderLayout(12, 0));
        archiveInfo.setOpaque(false);
        archiveInfo.add(rangeLabel, BorderLayout.WEST);
        archiveInfo.add(detectionLabel, BorderLayout.CENTER);
        archiveInfo.add(timeLabel, BorderLayout.EAST);
        timeTools.add(archiveInfo, BorderLayout.SOUTH);
        scrubber.add(timeTools, BorderLayout.SOUTH);
        controls.add(scrubber, BorderLayout.CENTER);

        JPanel bar = new JPanel(new BorderLayout(16, 0));
        playbackBar=bar;
        bar.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        playButton = button("播放", "play", true);
        playButton.setName("playback.playPause");
        playButton.setToolTipText("播放或暂停所选时间的录像（空格键）");
        playButton.setEnabled(false);
        playButton.addActionListener(event -> { if (isPlaying()) pause(); else play(); });
        stopButton = button("停止", "stop", false);
        stopButton.setToolTipText("停止回放，回到当前录像或查询的起点");
        stopButton.setEnabled(false);
        stopButton.addActionListener(event -> stopPlayback());
        rewindButton=button("后退10秒", "", false);rewindButton.setName("playback.rewind10");
        rewindButton.setToolTipText("后退 10 秒，可以跨录像文件（Alt + 左方向键）");
        rewindButton.addActionListener(event -> seekBySeconds(-10));rewindButton.setEnabled(false);
        forwardButton=button("前进10秒", "", false);forwardButton.setName("playback.forward10");
        forwardButton.setToolTipText("前进 10 秒，可以跨录像文件（Alt + 右方向键）");
        forwardButton.addActionListener(event -> seekBySeconds(10));forwardButton.setEnabled(false);
        audioButton = button("声音 关", "mute", false);
        audioButton.setEnabled(false);
        audioButton.addActionListener(event -> setAudioEnabled(!audioOn));
        speedBox = new JComboBox<>(new String[] {"0.5 倍", "1 倍", "2 倍", "4 倍", "8 倍"});
        speedBox.setName("playback.speed");
        NvrUi.styleCombo(speedBox);
        speedBox.setSelectedIndex(1);
        speedBox.setFocusable(false);
        speedBox.setPreferredSize(new Dimension(78, 32));
        speedBox.setEnabled(false);
        speedBox.setToolTipText("回放速度");
        speedBox.addActionListener(event -> {
            playbackSpeed = switch (speedBox.getSelectedIndex()) { case 0 -> 0.5; case 2 -> 2.0; case 3 -> 4.0; case 4 -> 8.0; default -> 1.0; };
            if(engine != null) engine.setSpeed(playbackSpeed);
        });
        fullscreenButton = button("画面全屏", "full", false);
        fullscreenButton.setName("playback.fullscreen");
        fullscreenButton.addActionListener(event -> toggleFullscreen());
        left.add(playButton);
        left.add(stopButton);stopButton.setVisible(!embedded);
        left.add(rewindButton);left.add(forwardButton);
        if (!embedded) left.add(audioButton);
        left.add(speedBox);
        left.add(scaleBox);
        left.add(fullscreenButton);fullscreenButton.setVisible(!embedded);
        bar.add(left, BorderLayout.WEST);
        stateLabel = label(detail, new Font(FONT.getName(), Font.PLAIN, 12), MUTED);
        resolutionLabel = label("—", new Font(FONT.getName(), Font.PLAIN, 12), MUTED);
        resolutionLabel.setPreferredSize(new Dimension(84, 24));
        resolutionLabel.setMinimumSize(new Dimension(84, 24));
        resolutionLabel.setMaximumSize(new Dimension(84, 24));
        if (!embedded) {
            JPanel playerInfo = new JPanel(new BorderLayout(12, 0));
            playerInfo.setOpaque(false);
            playerInfo.add(stateLabel, BorderLayout.CENTER);
            playerInfo.add(resolutionLabel, BorderLayout.EAST);
            controls.add(playerInfo, BorderLayout.SOUTH);
        }
        controls.add(bar, BorderLayout.NORTH);
        root.add(controls, BorderLayout.SOUTH);
        if (embedded) root.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                int height = videoFullscreen ? 118 : root.getHeight() < 550 ? 118 : 140;
                if (timeline.getPreferredSize().height != height) {
                    timeline.setPreferredSize(new Dimension(800, height));
                    timeline.revalidate();
                    controls.revalidate();
                    root.revalidate();
                }
            }
        });
        if (!embedded) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "exitFull");
            root.getActionMap().put("exitFull", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) { if (fullscreen) toggleFullscreen(); }
            });
        }
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "togglePlay");
        root.getActionMap().put("togglePlay", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focus instanceof JTextComponent || focus instanceof javax.swing.AbstractButton
                        || focus != null && SwingUtilities.getAncestorOfClass(JSpinner.class, focus) != null
                        || focus instanceof JComboBox<?>) return;
                if (playButton.isEnabled()) { if (isPlaying()) pause(); else play(); }
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt LEFT"), "rewind10");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt RIGHT"), "forward10");
        root.getActionMap().put("rewind10",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){if(!inputOwnsKeys())seekBySeconds(-10);}});
        root.getActionMap().put("forward10",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){if(!inputOwnsKeys())seekBySeconds(10);}});
        timer = new Timer(200, event -> { updateTime(); syncScale(); if (++refreshTick % 10 == 0) updateTimelineData(); });
        crossSeekTimer = new Timer(250, event -> {
            TimelineClip clip = pendingClip;
            Instant target = pendingTime;
            pendingClip = null;
            pendingTime = null;
            if (closed.get() || !dragging || clip == null || target == null) return;
            openClip(clip, Duration.between(clip.start(), target).toNanos() / 1.0e9, false);
        });
        crossSeekTimer.setRepeats(false);
        if (!embedded && !GraphicsEnvironment.isHeadless()) {
            window = new JFrame("录像回放 · 连续时间轴");
            window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            window.setContentPane(root);
            window.setIconImages(AppIdentity.icons());
            window.setMinimumSize(new Dimension(880, 540));
            window.setSize(1120, 800);
            window.setLocationRelativeTo(null);
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { closeWindow(); }
            });
        }
    }

    private void updateTime() {
        if (liveMode) {
            Instant now = Instant.now();
            timeLabel.setText("");
            timeline.setPosition(now);
            video.repaint();
            return;
        }
        if (engine == null || !sourceReady || positioningSource || dragging || gap) return;
        cursorTime = recordingStart.plusMillis((long) (engine.positionSeconds() * 1000));
        if (queryEnd != null && !cursorTime.isBefore(queryEnd)) {
            desiredPlay = false;
            if (engine.isPlaying()) engine.pause();
            cursorTime = queryEnd;
            state = "PAUSED";
            detail = "已到查询结束时间，点击播放从起点重新播放";
            updateVideoStatus();
        }
        timeLabel.setText(clockFormat.format(cursorTime));
        timeline.setPosition(cursorTime);
    }

    private int refreshTick;

    private static JSpinner dateTimePicker(Instant instant) {
        JSpinner picker = new JSpinner(new SpinnerDateModel(Date.from(instant), null, null, java.util.Calendar.MINUTE));
        picker.setEditor(new JSpinner.DateEditor(picker, "yyyy-MM-dd HH:mm:ss"));
        ((JSpinner.DateEditor) picker.getEditor()).getFormat().setLenient(false);
        NvrUi.styleSpinner(picker);
        picker.setPreferredSize(new Dimension(178, 32));
        return picker;
    }

    private void showDetectionSettings() {
        if (embedded) { onSettings.run(); return; }
        if (persons == null) return;
        JComboBox<String> mode = new JComboBox<>(new String[]{"精细识别（更少漏检）", "均衡识别（占用较低）"});
        mode.setSelectedIndex(persons.mode() == PersonEventService.Mode.ACCURATE ? 0 : 1);
        JPanel options = new JPanel(new java.awt.GridLayout(0, 1, 0, 9));
        options.add(label("针对快速经过和远处小人物，默认使用精细识别。", FONT, TEXT));
        options.add(mode);
        JButton reanalyse = button("重新识别已有录像", "", false);
        reanalyse.addActionListener(event -> {
            persons.reanalyse();
            reanalyse.setText("已排队重新识别");
            reanalyse.setEnabled(false);
            refreshTimeline();
        });
        options.add(reanalyse);
        if (JOptionPane.showConfirmDialog(window, options, "人员识别设置", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            persons.setMode(mode.getSelectedIndex() == 0 ? PersonEventService.Mode.ACCURATE : PersonEventService.Mode.BALANCED);
            refreshTimeline();
        }
    }

    private void showGap(String message) {
        gap = true;
        if (engine != null) engine.pause();
        frame = null;
        state = "GAP";
        detail = message;
        updateVideoStatus();
        video.repaint();
    }

    private void stopPlayback() {
        desiredPlay = false;
        dragging = false;
        cancelCrossSeek();
        if (engine == null || gap) return;
        if (queryStart == null) engine.stop();
        else {
            Instant target = recordingStart.isAfter(queryStart) ? recordingStart : queryStart;
            desiredOffset = Math.max(0, Duration.between(recordingStart, target).toNanos()/1.0e9);
            if (sourceReady) engine.endScrub(desiredOffset, false);
            cursorTime = target;
            timeLabel.setText(clockFormat.format(target));
            timeline.setPosition(target);
            stateLabel.setText("已停止");
        }
    }

    private void selectFirstInRange() {
        List<TimelineClip> completed = filteredClips().stream().filter(clip -> clip.durationSeconds() > 0).toList();
        if (completed.isEmpty()) {
            cursorTime = queryStart == null ? cursorTime : queryStart;
            if (cursorTime != null) { timeLabel.setText(clockFormat.format(cursorTime)); timeline.setPosition(cursorTime); }
            showGap(queryStart == null ? "还没有收到可回放画面" : "查询时间区间内没有收到录像画面");
            return;
        }
        TimelineClip selected = queryStart == null ? completed.getLast() : completed.getFirst();
        Instant time = queryStart == null || selected.start().isAfter(queryStart) ? selected.start() : queryStart;
        autoSelectPending = false;
        desiredPlay = started && !liveMode;
        openClip(selected, Duration.between(selected.start(), time).toNanos()/1.0e9, desiredPlay);
        if (queryStart == null) {
            searchStartPicker.setValue(Date.from(selected.start().atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
            searchEndPicker.setValue(Date.from(selected.start().atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()));
            applyScale();
        }
    }

    /** A play press on gray time advances to available footage, rather than doing nothing. */
    private void playAvailableClip() {
        List<TimelineClip> completed = filteredClips().stream().filter(clip -> clip.durationSeconds() > 0).toList();
        if (completed.isEmpty()) {
            desiredPlay = false;
            autoSelectPending = true;
            showGap(queryStart == null ? "暂无可回放录像，请先开启录制" : "查询时间内没有收到录像画面");
            return;
        }
        Instant selected = cursorTime == null ? completed.getLast().start() : cursorTime;
        TimelineClip next = completed.stream().filter(clip -> clip.end().isAfter(selected)).findFirst()
                .orElse(completed.getLast());
        Instant target = selected.isBefore(next.start()) || !selected.isBefore(next.end()) ? next.start() : selected;
        if (queryStart != null && target.isBefore(queryStart)) target = queryStart;
        autoSelectPending = false;
        openClip(next, Duration.between(next.start(), target).toNanos() / 1.0e9, true);
    }

    private void applyScale() {
        if (syncingScale) return;
        if (scaleBox.getSelectedIndex() == 5) {
            if (queryStart != null) timeline.setViewWindow(Duration.between(queryStart, queryEnd));
            return;
        }
        if (scaleBox.getSelectedIndex() == 6) return;
        timeline.setViewWindow(switch (scaleBox.getSelectedIndex()) {
            case 0 -> Duration.ofDays(1); case 1 -> Duration.ofHours(6); case 2 -> Duration.ofHours(1);
            case 4 -> Duration.ofMinutes(1); default -> Duration.ofMinutes(15);
        });
    }

    private void syncScale() {
        if (scaleBox == null || timeline == null) return;
        long visible = Duration.between(timeline.getViewStart(), timeline.getViewEnd()).toMillis();
        int index = 6;
        if (queryStart != null && Math.abs(visible - Duration.between(queryStart, queryEnd).toMillis()) <= 2) index = 5;
        else {
            long[] durations = {86_400_000, 21_600_000, 3_600_000, 900_000, 60_000};
            for (int i = 0; i < durations.length; i++) if (Math.abs(visible - durations[i]) <= 2) { index = i; break; }
        }
        if (scaleBox.getSelectedIndex() != index) {
            syncingScale = true;
            try { scaleBox.setSelectedIndex(index); }
            finally { syncingScale = false; }
        }
    }

    private void cancelCrossSeek() {
        crossSeekTimer.stop();
        pendingClip = null;
        pendingTime = null;
    }

    private List<TimelineClip> clips() {
        List<TimelineClip> result = catalog == null ? List.of() : catalog.snapshot();
        if (result.stream().noneMatch(clip -> clip.path().equals(recording)) && engine != null && engine.durationSeconds() > 0) {
            List<TimelineClip> joined = new ArrayList<>(result);
            joined.add(new TimelineClip(recording, recordingStart, engine.durationSeconds(), false));
            joined.sort(java.util.Comparator.comparing(TimelineClip::start));
            result = List.copyOf(joined);
        }
        return result;
    }

    private List<TimelineClip> filteredClips() {
        List<TimelineClip> all = clips();
        if (queryStart == null || queryEnd == null) return all;
        return all.stream().filter(clip -> clip.end().isAfter(queryStart) && clip.start().isBefore(queryEnd)).toList();
    }

    private void updateTimelineData() {
        if (closed.get()) return;
        List<RecordingTimeline.Span> recorded = new ArrayList<>();
        List<RecordingTimeline.Span> detected = new ArrayList<>();
        int pending = 0, analyzing = 0;
        List<TimelineClip> visible = filteredClips();
        if (engine != null && sourceReady && recording != null)
            clips().stream().filter(clip -> clip.path().equals(recording)).findFirst()
                    .ifPresent(clip -> engine.updateAvailable(clip.durationSeconds(), clip.active()));
        rangeLabel.setText(queryError == null ? queryStart == null ? "全部录像" : "已筛选时间范围" : queryError);
        rangeLabel.setForeground(queryError == null ? MUTED : RED);
        if (queryError == null) rangeLabel.setToolTipText(queryStart == null
                ? "所有已保存录像按实际日期连续排列；拖动时间轴可跨日期浏览"
                : clockFormat.format(queryStart) + " 至 " + clockFormat.format(queryEnd));
        clearQueryButton.setEnabled(queryStart != null || queryError != null);
        for (TimelineClip clip : visible) {
            recorded.add(new RecordingTimeline.Span(clip.start(), clip.end()));
            if (persons == null) continue;
            PersonEventService.DetectionSnapshot snapshot = persons.snapshot(clip.path());
            for (PersonInterval interval : snapshot.intervals()) {
                double start = Math.min(clip.durationSeconds(), interval.startSeconds());
                double end = Math.min(clip.durationSeconds(), interval.endSeconds());
                if (end > start) detected.add(new RecordingTimeline.Span(clip.start().plusMillis((long)(start*1000)),
                        clip.start().plusMillis((long)(end*1000))));
            }
            if (snapshot.status() == PersonEventService.Status.ANALYZING) analyzing++;
            else if (snapshot.status() == PersonEventService.Status.PENDING) pending++;
        }
        timeline.setRecordings(recorded);
        timeline.setPersons(detected);
        if (persons == null) detectionLabel.setText("人员识别未启用");
        else if (recording == null) {
            String progress = analysisProgress(analyzing, pending);
            detectionLabel.setText(progress.isBlank() ? "新画面自动识别人员事件" : "人员事件 · " + progress);
            detectionLabel.setToolTipText(persons.availabilityDetail() + "；蓝色录像尚未分析完成时，不能据此判断无人");
        }
        else {
            PersonEventService.DetectionSnapshot selected = persons.snapshot(recording);
            detectionLabel.setText(switch(selected.status()) {
                case READY -> selected.intervals().isEmpty() ? "当前录像未标出人员事件" : "橙色为人员事件";
                case ANALYZING -> "当前录像人员分析中…";
                case PENDING -> "当前录像等待人员分析";
                case FAILED -> "人员识别失败，可重试";
                case DISABLED -> "人员识别不可用";
            } + (analyzing + pending > 0 ? " · " + analysisProgress(analyzing, pending) : ""));
            detectionLabel.setToolTipText(selected.detail() + "；橙色为自动识别的人员事件，未出现橙色不代表确定无人");
        }
        if (autoSelectPending && !visible.isEmpty()) selectFirstInRange();
        updateVideoStatus();
    }
    private static String analysisProgress(int analyzing, int pending) {
        if (analyzing > 0 && pending > 0) return "分析 " + analyzing + " 段 · 等待 " + pending + " 段";
        if (analyzing > 0) return "分析 " + analyzing + " 段";
        if (pending > 0) return "等待 " + pending + " 段";
        return "";
    }

    private TimelineClip clipAt(Instant time) {
        if (queryStart != null && (time.isBefore(queryStart) || !time.isBefore(queryEnd))) return null;
        return filteredClips().stream().filter(clip -> clip.durationSeconds() > 0 && !time.isBefore(clip.start()) && time.isBefore(clip.end()))
                .max(java.util.Comparator.comparing(TimelineClip::start)).orElse(null);
    }

    private void seekOnTimeline(Instant requestedTime, boolean adjusting) {
        if (closed.get()) return;
        Instant time = constrainQuerySeek(requestedTime);
        boolean fromLive = liveMode;
        enterPlayback(true);
        if (fromLive) desiredPlay = true;
        autoSelectPending = false;
        if (adjusting && !dragging) { dragResume = desiredPlay; dragging = true; if (engine != null) engine.beginScrub(); }
        boolean resume = dragging ? dragResume : desiredPlay;
        cursorTime = time;
        timeLabel.setText(clockFormat.format(time));
        timeline.setPosition(time);
        TimelineClip clip = clipAt(time);
        if (clip == null) {
            cancelCrossSeek();
            boolean outsideQuery = queryStart != null && (time.isBefore(queryStart) || !time.isBefore(queryEnd));
            boolean recordingNow = !outsideQuery && filteredClips().stream().anyMatch(item -> item.active()
                    && !time.isBefore(item.start()) && time.isBefore(item.end()));
            showGap(outsideQuery ? "所选时刻在查询范围外，点击取消筛选继续浏览"
                    : recordingNow ? "最新画面正在写入，请稍后定位" : "这个时间没有收到录像画面");
            if (!adjusting) { dragging = false; desiredPlay = false; }
            return;
        }
        double offset = Duration.between(clip.start(), time).toNanos() / 1.0e9;
        if (adjusting && !clip.path().equals(recording)) {
            pendingClip = clip;
            pendingTime = time;
            gap = true;
            frame = null;
            state = "LOADING";
            detail = "正在定位所选时间…";
            stateLabel.setText(detail);
            resolutionLabel.setText("—");
            video.repaint();
            if (!crossSeekTimer.isRunning()) crossSeekTimer.start();
            return;
        }
        cancelCrossSeek();
        if (!clip.path().equals(recording)) openClip(clip, offset, adjusting ? false : resume);
        desiredOffset = offset;
        gap = false;
        if (adjusting) {
            if (sourceReady) { engine.beginScrub(); engine.scrubTo(offset); }
        } else {
            dragging = false;
            desiredPlay = resume;
            if (sourceReady) engine.endScrub(offset, resume);
        }
    }

    private Instant constrainQuerySeek(Instant time) {
        if (queryStart == null) return time;
        if (time.isBefore(queryStart)) return queryStart;
        Instant latest = queryEnd.minusMillis(1);
        return time.isAfter(latest) ? latest : time;
    }

    private boolean atRecordingEnd() {
        PlaybackEngine current = engine;
        return !liveMode && sourceReady && !gap && current != null && current.durationSeconds() > 0
                && current.positionSeconds() >= current.durationSeconds() - .01;
    }

    private TimelineClip nextCompletedClip() {
        return filteredClips().stream().filter(clip -> clip.durationSeconds() > 0 && !clip.path().equals(recording)
                        && clip.start().isAfter(recordingStart))
                .min(java.util.Comparator.comparing(TimelineClip::start)).orElse(null);
    }

    private boolean advanceClip() {
        TimelineClip next = nextCompletedClip();
        if (next != null) {
            Instant target = queryStart != null && next.start().isBefore(queryStart) ? queryStart : next.start();
            openClip(next, Duration.between(next.start(), target).toNanos()/1.0e9, true);
            return true;
        }
        desiredPlay = false;
        state = "ENDED";
        detail = "已到已保存录像末尾，点击重新播放重看当前录像";
        updateVideoStatus();
        return false;
    }

    private void toggleFullscreen() {
        if (embedded) { if (!closed.get()) onHostFullscreen.run(); return; }
        if (window == null || closed.get()) return;
        if (!fullscreen) {
            normalBounds = window.getBounds();
            normalState = window.getExtendedState();
            fullscreen = true;
            Rectangle screen = window.getGraphicsConfiguration().getBounds();
            window.dispose();
            window.setUndecorated(true);
            window.setExtendedState(JFrame.NORMAL);
            window.setBounds(screen);
        } else {
            fullscreen = false;
            window.dispose();
            window.setUndecorated(false);
            if (normalBounds != null) window.setBounds(normalBounds);
            window.setExtendedState(normalState);
        }
        videoFullscreen=fullscreen;
        updateFullscreenLayout();
        window.setVisible(true);
    }

    private static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    private static JButton button(String text, String icon, boolean primary) {
        return NvrUi.button(text, icon.isEmpty() ? null : new ControlIcon(icon, primary ? Color.WHITE : MUTED), primary, null);
    }

    private static String formatTime(double seconds) {
        long value = Math.max(0, (long) seconds);
        return value >= 3600 ? String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", value / 60, value % 60);
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    private static void onEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); return; }
        try { SwingUtilities.invokeAndWait(action); }
        catch (Exception failure) { throw new IllegalStateException("无法创建回放界面", failure); }
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layoutTree(nested);
    }

    private final class VideoPanel extends JPanel {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            BufferedImage current = currentFrame();
            if (current != null) {
                double ratio = Math.min((double) getWidth() / current.getWidth(), (double) getHeight() / current.getHeight());
                int width = (int) Math.round(current.getWidth() * ratio);
                int height = (int) Math.round(current.getHeight() * ratio);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(current, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
            } else {
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g.setColor(new Color(87, 92, 99));
                g.setStroke(new BasicStroke(2));
                g.drawRoundRect(cx - 29, cy - 50, 58, 43, 4, 4);
                g.drawPolygon(new int[] {cx - 6, cx - 6, cx + 10}, new int[] {cy - 40, cy - 18, cy - 29}, 3);
                g.setFont(FONT.deriveFont(14f));
                String visibleState = liveMode ? liveState : state;
                g.setColor("ERROR".equals(visibleState) ? new Color(223, 130, 133) : new Color(159, 164, 171));
                String text = liveMode ? liveDetail : "ERROR".equals(state) ? "录像无法播放" : "GAP".equals(state) ? detail : "正在加载录像";
                g.drawString(text, cx - g.getFontMetrics().stringWidth(text) / 2, cy + 22);
                g.setFont(FONT.deriveFont(12f));
                g.setColor(new Color(113, 119, 127));
                String hint = liveMode ? "拖动下方时间轴查看录像，双击画面全屏" : "ERROR".equals(state) ? detail : "GAP".equals(state) ? "拖到蓝色或橙色时段查看录像" : "使用下方时间轴跳转，双击画面全屏";
                g.drawString(hint, cx - g.getFontMetrics().stringWidth(hint) / 2, cy + 49);
            }
            if (embedded) {
                boolean staleLive = liveMode && current != null && !"CONNECTED".equals(liveState);
                if (staleLive) {
                    g.setColor(new Color(0, 0, 0, 88));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    String warning = switch (liveState) {
                        case "CONNECTING", "RETRYING" -> "画面已停止更新，正在重新连接";
                        case "ERROR" -> "连接失败，当前显示最后一帧";
                        default -> "摄像头已断开，当前显示最后一帧";
                    };
                    g.setFont(FONT.deriveFont(14f));
                    int warningWidth = g.getFontMetrics().stringWidth(warning);
                    int warningX = (getWidth() - warningWidth) / 2;
                    int warningY = getHeight() / 2;
                    g.setColor(new Color(0, 0, 0, 170));
                    g.fillRoundRect(warningX - 16, warningY - 23, warningWidth + 32, 38, 5, 5);
                    g.setColor(new Color(245, 196, 111));
                    g.drawString(warning, warningX, warningY + 2);
                }
                g.setFont(FONT.deriveFont(12f));
                if (!channelName.isBlank() && getHeight() >= 44) {
                    String label = fitVideoLabel(g, channelName, Math.max(20, getWidth() - 28));
                    int labelWidth = g.getFontMetrics().stringWidth(label);
                    g.setColor(new Color(0, 0, 0, 112));
                    g.fillRect(8, getHeight() - 29, labelWidth + 12, 21);
                    g.setColor(new Color(244, 246, 248));
                    g.drawString(label, 14, getHeight() - 14);
                }
                if (!liveMode && getWidth() >= 130 && getHeight() >= 60) {
                    String badge = gap ? "无录像" : "ERROR".equals(state) ? "回放异常"
                            : !sourceReady ? "正在加载" : isPlaying() ? "回放" : "回放 · 暂停";
                    int badgeWidth = g.getFontMetrics().stringWidth(badge) + 30;
                    int badgeX = getWidth() - badgeWidth - 8;
                    g.setColor(new Color(0, 0, 0, 112));
                    g.fillRect(badgeX, 8, badgeWidth, 21);
                    g.setColor(new Color(244, 246, 248));
                    g.fillPolygon(new int[] {badgeX + 9, badgeX + 9, badgeX + 15}, new int[] {14, 23, 18}, 3);
                    g.drawString(badge, badgeX + 21, 23);
                }
            }
            g.dispose();
        }
    }

    private static String fitVideoLabel(Graphics2D g, String label, int width) {
        if (g.getFontMetrics().stringWidth(label) <= width) return label;
        int end = label.length();
        while (end > 0 && g.getFontMetrics().stringWidth(label.substring(0, end) + "…") > width) end--;
        return label.substring(0, end) + "…";
    }

    private static final class ControlIcon implements javax.swing.Icon {
        private final String type;
        private final Color color;
        ControlIcon(String type, Color color) { this.type = type; this.color = color; }
        @Override public int getIconWidth() { return 15; }
        @Override public int getIconHeight() { return 15; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isEnabled() ? color : new Color(173, 177, 182));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (type) {
                case "play" -> g.fillPolygon(new int[] {4, 4, 12}, new int[] {2, 13, 7}, 3);
                case "pause" -> { g.fillRect(3, 3, 3, 9); g.fillRect(9, 3, 3, 9); }
                case "stop" -> g.fillRect(3, 3, 9, 9);
                case "mute", "speaker" -> {
                    g.drawPolygon(new int[] {1, 5, 9, 9, 5, 1}, new int[] {5, 5, 2, 13, 10, 10}, 6);
                    if (type.equals("mute")) { g.drawLine(11, 5, 14, 10); g.drawLine(14, 5, 11, 10); }
                    else g.drawArc(9, 4, 5, 7, -70, 140);
                }
                case "full" -> {
                    g.drawLine(2, 6, 2, 2); g.drawLine(2, 2, 6, 2);
                    g.drawLine(9, 2, 13, 2); g.drawLine(13, 2, 13, 6);
                    g.drawLine(2, 9, 2, 13); g.drawLine(2, 13, 6, 13);
                    g.drawLine(9, 13, 13, 13); g.drawLine(13, 9, 13, 13);
                }
                default -> { }
            }
            g.dispose();
        }
    }
}
