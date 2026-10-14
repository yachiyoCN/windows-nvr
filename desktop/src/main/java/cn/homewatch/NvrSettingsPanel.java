package cn.homewatch;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Icon;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Inline recorder controls. Persistent work and archive lookups never run on Swing's thread. */
public final class NvrSettingsPanel extends JPanel implements AutoCloseable {
    public interface Listener {
        void saveCamera(String name, String url, String transport, boolean autoConnect) throws Exception;
        default void saveCamera(String name, String url, String transport, boolean autoConnect, VideoQuality quality) throws Exception {
            saveCamera(name, url, transport, autoConnect);
        }
        default void saveCamera(String name, String url, String transport, boolean autoConnect, VideoQuality quality,
                                boolean recordingTimestamp) throws Exception {
            saveCamera(name, url, transport, autoConnect, quality);
        }
        void notice(String message, boolean error);
    }

    private static final Color SURFACE = new Color(248, 249, 250);
    private static final Color BORDER = new Color(218, 221, 224);
    private static final Color TEXT = new Color(60, 64, 69);
    private static final Color MUTED = new Color(119, 126, 135);
    private static final Color RED = new Color(202, 43, 47);
    private static final Color GREEN = new Color(42, 137, 83);
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);

    private final Path root;
    private final RecorderService recorder;
    private final NvrStorage storage;
    private final StorageDraft draft;
    private final PersonEventService persons;
    private final Listener listener;
    private final AtomicBoolean refreshBusy = new AtomicBoolean();
    private final AtomicBoolean storageBusy;
    private final AtomicBoolean cameraBusy = new AtomicBoolean();
    private final JTextField directory = new JTextField() {
        @Override public void doLayout() {
            super.doLayout();
            // Seeded before layout, a long path otherwise retains the previous width's scroll.
            // Move only the viewport; keep the user's text, caret and selection untouched.
            if (hasFocus() || storageDirty || getDocument().getLength() == 0) return;
            try {
                java.awt.geom.Rectangle2D end = modelToView2D(getDocument().getLength());
                if (end != null) scrollRectToVisible(end.getBounds());
            } catch (javax.swing.text.BadLocationException ignored) { }
        }
    };
    private final JSpinner capacity = new JSpinner(new SpinnerNumberModel(20.0, 0.25, 102400.0, 0.25));
    private final JSpinner keepDays = new JSpinner(new SpinnerNumberModel(7, 1, 36500, 1));
    private final JSpinner segmentMinutes = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JCheckBox autoRecord = checkBox("启动时自动录制全部通道");
    private final JLabel storageState = label("未录制");
    private final JLabel storageUsage = label("正在读取录像容量…");
    private final JProgressBar usageBar = new JProgressBar(0, 1000);
    private final JLabel storageNotice = label(" ");
    private final JButton saveStorage = button("保存录像设置");
    private final JButton browse = button("浏览");
    private final JButton cleanStorage = button("清理旧录像");
    private final JComboBox<String> personMode = new JComboBox<>(new String[] {"准确优先", "均衡模式"});
    private final JButton reanalyse = button("重新识别");
    private final JButton ignoreRegions = button("忽略区域");
    private final JButton clearIgnoreRegions = button("清空");
    private final JLabel ignoreRegionsState = label("未设置忽略区域");
    private java.util.function.Supplier<java.awt.image.BufferedImage> detectionFrameSupplier = () -> null;
    private boolean ignoreRegionsBusy;
    private boolean personModeBusy;
    private final JLabel personState = label("识别完成后标为橙色");
    private final JLabel personNotice = label(" ");
    private final JTextField cameraName = new JTextField();
    private final JPasswordField cameraUrl = new JPasswordField();
    private final JComboBox<String> cameraTransport = new JComboBox<>(new String[] {"TCP", "UDP"});
    private final JComboBox<VideoQuality> cameraQuality = new JComboBox<>(VideoQuality.values());
    private final JLabel videoFormat = label("实际画质：连接后显示");
    private final JCheckBox showPassword = checkBox("显示地址");
    private final JCheckBox autoConnect = checkBox("启动软件后自动连接");
    private final JCheckBox recordingTimestamp = checkBox("录像时间水印");
    private final JButton saveCamera = button("保存设备设置");
    private final JLabel cameraNotice = label(" ");
    private Collapsible storageSection;
    private Collapsible cameraSection;
    private Collapsible detectionSection;
    private Collapsible remoteSection;
    private final JPanel remoteContents = new JPanel(new BorderLayout());
    private RemoteAccessPanel remoteAccess;
    private boolean storageLoaded;
    private boolean loadingStorage;
    private boolean loadingCamera;
    private boolean storageDirty;
    private boolean cameraDirty;
    private boolean syncingPersonMode;
    private PersonEventService.Mode pendingPersonMode;
    private JScrollPane sectionsScroll;
    private final List<SidebarGap> sidebarGaps = new ArrayList<>();
    private final List<JPanel> compactRows = new ArrayList<>();
    private StorageSummaryListener storageSummaryListener;
    private volatile boolean closed;

    @FunctionalInterface public interface StorageSummaryListener {
        void update(RecorderSettings settings, long usedBytes, boolean dirty);
    }
    public void setStorageSummaryListener(StorageSummaryListener summaryListener) {
        onEdt(() -> { storageSummaryListener = summaryListener; publishStorageSummary(); });
    }
    private void publishStorageSummary() {
        if (storageSummaryListener != null) storageSummaryListener.update(storage.settings(), storage.usedBytes(), storageDirty);
    }

    public NvrSettingsPanel(Path root, RecorderService recorder, PersonEventService persons, Listener listener) {
        this(root, recorder, persons, recorder, new StorageDraft(root, recorder.settings()), listener);
    }
    NvrSettingsPanel(Path root, RecorderService recorder, PersonEventService persons, NvrStorage storage, StorageDraft draft, Listener listener) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.recorder = Objects.requireNonNull(recorder);
        this.storage = Objects.requireNonNull(storage);
        this.draft = Objects.requireNonNull(draft);
        storageBusy = draft.busy;
        this.persons = Objects.requireNonNull(persons);
        this.listener = Objects.requireNonNull(listener);
        onEdtAndWait(() -> { buildUi(); draft.add(this); applyStorageDraft(); });
        refresh();
    }

    public void setCameraValues(String name, String url, String transport, boolean automatic) {
        setCameraValues(name, url, transport, automatic, VideoQuality.AUTO);
    }
    public void setVideoFormat(int width, int height, String profile) {
        onEdt(() -> { if (!closed) videoFormat.setText(width > 0 && height > 0 ? "实际画质：" + width + " × " + height : "实际画质：连接后显示"); });
    }
    public void setCameraValues(String name, String url, String transport, boolean automatic, VideoQuality quality) {
        setCameraValues(name, url, transport, automatic, quality, false);
    }
    public void setCameraValues(String name, String url, String transport, boolean automatic, VideoQuality quality,
                                boolean timestamp) {
        onEdt(() -> {
            if (closed) return;
            // A periodic device update must never replace settings the user is entering.
            // Returning to this channel during an asynchronous save can still supply
            // the previous configuration. Keep the submitted draft until completion,
            // including failed saves where the user must be able to retry it.
            if (cameraDirty) return;
            loadingCamera = true;
            try {
                cameraName.setText(Objects.requireNonNullElse(name, ""));
                cameraUrl.setText(Objects.requireNonNullElse(url, ""));
                cameraUrl.setCaretPosition(0);
                cameraTransport.setSelectedItem("UDP".equalsIgnoreCase(transport) ? "UDP" : "TCP");
                cameraQuality.setSelectedItem(quality == null ? VideoQuality.AUTO : quality);
                autoConnect.setSelected(automatic);
                recordingTimestamp.setSelected(timestamp);
                cameraDirty = false;
                updateCameraDirtyState();
            } finally { loadingCamera = false; }
        });
    }

    /** Read only the services' in-memory snapshots; even large archives cannot freeze the form. */
    public void refresh() {
        if (closed || !refreshBusy.compareAndSet(false, true)) return;
        onEdt(() -> { if (!closed && remoteAccess != null) remoteAccess.refresh(); });
        Thread.startVirtualThread(() -> {
            try {
                List<RecordingEntry> entries = recorder.listRecordings();
                int ready = 0, pending = 0, analyzing = 0, failed = 0, completed = 0;
                boolean disabled = persons.snapshot(root.resolve("data/recordings")).status()
                        == PersonEventService.Status.DISABLED;
                for (RecordingEntry entry : entries) {
                    if (entry.active() && entry.durationSeconds() <= 0) continue;
                    completed++;
                    switch (persons.snapshot(entry.path()).status()) {
                        case READY -> ready++;
                        case PENDING -> pending++;
                        case ANALYZING -> analyzing++;
                        case FAILED -> failed++;
                        case DISABLED -> disabled = true;
                    }
                }
                Snapshot snapshot = new Snapshot(storage.settings(), storage.usedBytes(),
                        storage.isRequested(), storage.isRecording(), persons.mode(),
                        persons.availabilityDetail(), completed, ready, pending, analyzing, failed, disabled);
                onEdt(() -> { if (!closed) applySnapshot(snapshot); });
            } finally { refreshBusy.set(false); }
        });
    }

    public void expandStorage() { revealSection(storageSection, capacity); }
    public void expandCamera() { revealSection(cameraSection, cameraName); }
    public void expandDetection() { revealSection(detectionSection, personMode); }
    public void setDetectionFrameSupplier(java.util.function.Supplier<java.awt.image.BufferedImage> supplier) {
        detectionFrameSupplier = Objects.requireNonNull(supplier);
    }
    public boolean isRemoteAccessExpanded() { return remoteSection != null && remoteSection.header.isSelected(); }
    public void setRemoteAccessService(RemoteAccessService service) {
        Objects.requireNonNull(service);
        onEdt(() -> {
            if (closed) return;
            if (remoteAccess != null) remoteAccess.close();
            remoteContents.removeAll();
            remoteAccess = new RemoteAccessPanel(service, listener::notice);
            remoteAccess.setDirtyListener(remoteSection::setDirty);
            remoteContents.add(remoteAccess, BorderLayout.CENTER);
            remoteContents.revalidate(); remoteContents.repaint();
        });
    }
    public void expandRemoteAccess() {
        onEdt(() -> {
            if (closed) return;
            storageSection.setExpanded(false);
            detectionSection.setExpanded(false);
            cameraSection.setExpanded(false);
            revealSection(remoteSection, remoteAccess == null ? remoteContents : remoteAccess.firstControl());
        });
    }
    private void revealSection(Collapsible section, JComponent firstControl) {
        onEdt(() -> {
            if (closed) return;
            section.setExpanded(true);
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                sectionsScroll.getViewport().getView().validate();
                section.scrollRectToVisible(new java.awt.Rectangle(0, 0, Math.max(1, section.getWidth()),
                        Math.min(section.getHeight(), sectionsScroll.getViewport().getHeight())));
                if (section == storageSection) sectionsScroll.getVerticalScrollBar().setValue(0);
                firstControl.requestFocusInWindow();
            });
        });
    }
    @Override public void close() { closed = true; onEdt(() -> {
        draft.remove(this); if (remoteAccess != null) remoteAccess.close();
    }); }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(SURFACE);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER));
        setPreferredSize(new Dimension(308, 560));
        setMinimumSize(new Dimension(270, 200));

        SidebarContents sections = new SidebarContents();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.setBackground(SURFACE);
        storageSection = new Collapsible("公共录像存储 · 所有摄像头", storageControls(), true);
        detectionSection = new Collapsible("人员标记", detectionControls(), false);
        cameraSection = new Collapsible("当前摄像头 · 画质与连接", cameraControls(), false);
        remoteContents.setOpaque(false);
        remoteContents.add(label("正在准备远程访问设置…"));
        remoteSection = new Collapsible("远程访问 · 所有摄像头", remoteContents, false);
        sections.add(storageSection);
        sections.add(detectionSection);
        sections.add(cameraSection);
        sections.add(remoteSection);
        sections.add(Box.createVerticalGlue());
        JScrollPane scroll = new JScrollPane(sections,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sectionsScroll = scroll;
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(SURFACE);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateSidebarSpacing(); }
        });
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
        applyFonts(this);
        named(directory, "storage.directory");
        named(capacity, "storage.maxGB");
        named(keepDays, "storage.keepDays");
        named(segmentMinutes, "storage.segmentMinutes");
        named(autoRecord, "storage.autoRecord");
        named(saveStorage, "storage.save");
        named(browse, "storage.browse");
        named(cleanStorage, "storage.clean");
        named(personMode, "person.mode");
        named(reanalyse, "person.reanalyse");
        named(ignoreRegions, "person.ignoreRegions");
        named(clearIgnoreRegions, "person.clearIgnoreRegions");
        named(cameraName, "camera.name");
        named(cameraUrl, "camera.url");
        named(cameraTransport, "camera.transport");
        named(cameraQuality, "camera.quality");
        named(videoFormat, "camera.format");
        named(autoConnect, "camera.autoConnect");
        named(recordingTimestamp, "camera.recordingTimestamp");
        named(saveCamera, "camera.save");
        named(showPassword, "camera.showPassword");
        // This snapshot is already loaded in memory; seed fields before they can be edited.
        loadStorageValues(storage.settings());
        storageLoaded = true;
        watchStorageEdits();
        watchCameraEdits();
        updateStorageDirtyState();
        updateCameraDirtyState();
    }

    private JPanel storageControls() {
        JPanel panel = verticalPanel();
        storageState.setForeground(MUTED);
        panel.add(storageState);
        panel.add(sidebarGap(6, 3));
        panel.add(label("保存目录"));
        panel.add(sidebarGap(5, 3));
        directory.setText(root.resolve("data/recordings").toString());
        styleField(directory);
        directory.setColumns(1);
        directory.setToolTipText(directory.getText());
        browse.setToolTipText("选择保存录像的文件夹");
        browse.addActionListener(e -> chooseDirectory());
        JPanel directoryRow = new JPanel(new BorderLayout(5, 0));
        directoryRow.setOpaque(false);
        directoryRow.add(directory, BorderLayout.CENTER);
        directoryRow.add(browse, BorderLayout.EAST);
        panel.add(directoryRow);
        panel.add(sidebarGap(10, 5));
        capacity.setEditor(new JSpinner.NumberEditor(capacity, "0.##"));
        JPanel limits = new JPanel(new GridBagLayout());
        limits.setOpaque(false);
        formRow(limits, 0, "最大占用", unitRow(capacity, "GB"));
        formRow(limits, 1, "保留天数", unitRow(keepDays, "天"));
        compactRows.add(limits);
        capacity.setToolTipText("录像总容量上限，修改后点击保存录像设置生效");
        keepDays.setToolTipText("最长保留天数；容量先满时，会提前清理最旧录像");
        panel.add(limits);
        panel.add(autoRecord);
        panel.add(sidebarGap(9, 2));
        storageUsage.setForeground(MUTED);
        panel.add(storageUsage);
        panel.add(sidebarGap(5, 3));
        usageBar.setForeground(RED);
        usageBar.setBackground(new Color(229, 232, 235));
        usageBar.setBorder(BorderFactory.createEmptyBorder());
        usageBar.setPreferredSize(new Dimension(250, 5));
        usageBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
        panel.add(usageBar);
        panel.add(sidebarGap(8, 3));
        JLabel hint = label("<html>容量或天数先达到哪个，就按哪个<br>自动清理最旧录像</html>");
        hint.setForeground(MUTED);
        hint.setToolTipText("容量上限和保留天数同时生效；正在写入、回放或识别的文件暂缓清理，容量可能暂时超过上限");
        panel.add(hint);

        JPanel advanced = new JPanel(new GridBagLayout());
        advanced.setOpaque(false);
        formRow(advanced, 0, "文件时长", unitRow(segmentMinutes, "分钟"));
        JLabel segmentHint = label("<html>后台分文件保存，回放连续播放</html>");
        segmentHint.setForeground(MUTED);
        GridBagConstraints explanation = new GridBagConstraints();
        explanation.gridx = 0;
        explanation.gridy = 1;
        explanation.gridwidth = 2;
        explanation.anchor = GridBagConstraints.WEST;
        advanced.add(segmentHint, explanation);
        Collapsible extra = new Collapsible("更多录像设置", advanced, false);
        extra.setBorder(BorderFactory.createEmptyBorder());
        extra.header.setFont(FONT);
        extra.body.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        panel.add(extra);
        JPanel actions = actionRow();
        NvrUi.styleButton(saveStorage, true);
        saveStorage.addActionListener(e -> saveStorageSettings());
        cleanStorage.addActionListener(e -> runStorageOperation("已检查所有摄像头的旧录像", storage::cleanupNow, false));
        cleanStorage.setToolTipText("按已保存的容量上限和保存时长清理最旧录像");
        actions.add(saveStorage);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(cleanStorage);
        panel.add(actions);
        panel.add(Box.createVerticalStrut(5));
        storageNotice.setForeground(MUTED);
        panel.add(storageNotice);
        alignChildren(panel);
        return panel;
    }

    private JPanel detectionControls() {
        JPanel panel = verticalPanel();
        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);
        NvrUi.styleCombo(personMode);
        controls.add(personMode, BorderLayout.CENTER);
        controls.add(reanalyse, BorderLayout.EAST);
        reanalyse.setEnabled(false);
        reanalyse.setToolTipText("收到录像画面后可重新识别");
        personMode.setToolTipText("准确优先更容易标出短暂经过的人；均衡模式减少处理负担");
        personMode.addActionListener(e -> {
            if (syncingPersonMode || closed || personModeBusy) return;
            pendingPersonMode = personMode.getSelectedIndex() == 0
                    ? PersonEventService.Mode.ACCURATE : PersonEventService.Mode.BALANCED;
            personModeBusy = true; personMode.setEnabled(false);
            inlineNotice(personNotice, "正在保存当前摄像头的识别模式…", false);
            persons.setMode(pendingPersonMode, failure -> onEdt(() -> {
                personModeBusy = false; pendingPersonMode = null;
                if (closed) return;
                personMode.setEnabled(true);
                inlineNotice(personNotice, failure == null ? "识别模式已保存，将重新标记当前摄像头录像"
                        : safeMessage(failure, "识别模式保存失败，原设置保留"), failure != null);
                refresh();
            }));
        });
        reanalyse.addActionListener(e -> {
            if (closed) return;
            persons.reanalyse();
            inlineNotice(personNotice, "已安排重新识别已有录像", false);
        });
        panel.add(controls);
        panel.add(Box.createVerticalStrut(8));
        personState.setForeground(MUTED);
        panel.add(personState);
        panel.add(Box.createVerticalStrut(4));
        JLabel hint = label("<html>录制中持续识别，橙色表示人员事件</html>");
        hint.setForeground(MUTED);
        panel.add(hint);
        panel.add(Box.createVerticalStrut(8));
        JPanel regionsRow = new JPanel(new BorderLayout(6, 0));
        regionsRow.setOpaque(false);
        regionsRow.add(ignoreRegionsState, BorderLayout.CENTER);
        JPanel regionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        regionActions.setOpaque(false);
        regionActions.add(ignoreRegions); regionActions.add(clearIgnoreRegions);
        regionsRow.add(regionActions, BorderLayout.EAST);
        ignoreRegions.setToolTipText("框住显示器、电视等误报区域，只排除人员识别，不影响录像");
        ignoreRegions.addActionListener(e -> editIgnoreRegions());
        clearIgnoreRegions.setToolTipText("清空当前摄像头的忽略区域，可在未连接时使用");
        clearIgnoreRegions.addActionListener(e -> saveIgnoreRegions(List.of()));
        panel.add(regionsRow);
        JLabel regionHint = label("<html>当前摄像头可排除显示器、电视区域<br>区域内的真人也不会生成人员标记</html>");
        regionHint.setForeground(MUTED);
        panel.add(regionHint);
        panel.add(personNotice);
        alignChildren(panel);
        return panel;
    }

    private void editIgnoreRegions() {
        if (closed || ignoreRegionsBusy) return;
        java.awt.image.BufferedImage frame = detectionFrameSupplier.get();
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            inlineNotice(personNotice, "请先打开当前摄像头预览，再设置忽略区域", true);
            return;
        }
        List<PersonDetector.IgnoreRegion> draft = PersonIgnoreRegionDialog.choose(
                SwingUtilities.getWindowAncestor(this), frame, persons.ignoreRegions());
        if (draft == null || closed) return;
        saveIgnoreRegions(draft);
    }

    private void saveIgnoreRegions(List<PersonDetector.IgnoreRegion> regions) {
        if (closed || ignoreRegionsBusy) return;
        ignoreRegionsBusy = true; ignoreRegions.setEnabled(false); clearIgnoreRegions.setEnabled(false);
        inlineNotice(personNotice, "正在保存当前摄像头的忽略区域…", false);
        persons.setIgnoreRegions(regions, failure -> onEdt(() -> {
            ignoreRegionsBusy = false;
            if (closed) return;
            ignoreRegions.setEnabled(true);
            clearIgnoreRegions.setEnabled(!persons.ignoreRegions().isEmpty());
            inlineNotice(personNotice, failure == null
                    ? "忽略区域已保存，将重新标记当前摄像头的录像"
                    : safeMessage(failure, "忽略区域保存失败，原设置保留"), failure != null);
            refresh();
        }));
    }

    private JPanel cameraControls() {
        JPanel panel = verticalPanel();
        panel.add(label("设备名称"));
        panel.add(sidebarGap(5, 2));
        styleField(cameraName);
        panel.add(cameraName);
        panel.add(sidebarGap(10, 3));
        panel.add(label("监控地址（RTSP / HTTPS 视频）"));
        panel.add(sidebarGap(5, 2));
        styleField(cameraUrl);
        cameraUrl.setEchoChar('●');
        cameraUrl.setToolTipText("支持 rtsp://、rtsps:// 或 HTTPS 视频地址；地址包含连接信息，默认隐藏");
        panel.add(cameraUrl);
        showPassword.addActionListener(e -> cameraUrl.setEchoChar(showPassword.isSelected() ? (char) 0 : '●'));
        panel.add(sidebarGap(4, 0));
        panel.add(showPassword);
        JPanel transport = new JPanel(new GridBagLayout());
        transport.setOpaque(false);
        formRow(transport, 0, "传输方式", cameraTransport);
        formRow(transport, 1, "分辨率", cameraQuality);
        compactRows.add(transport);
        panel.add(sidebarGap(7, 2));
        panel.add(transport);
        videoFormat.setForeground(MUTED);
        panel.add(videoFormat);
        JLabel qualityHint = label("<html>用于预览和新录像，保持画面比例<br>自动选择可用的最高画质</html>");
        qualityHint.setForeground(MUTED); panel.add(qualityHint);
        panel.add(autoConnect);
        recordingTimestamp.setToolTipText("只对当前摄像头的新录像生效，使用录像机电脑的时间");
        panel.add(recordingTimestamp);
        JLabel timestampHint = label("<html>使用录像机时间，写入新录像</html>");
        timestampHint.setForeground(MUTED);
        panel.add(timestampHint);
        panel.add(sidebarGap(10, 0));
        JPanel actions = actionRow();
        NvrUi.styleCombo(cameraTransport);
        NvrUi.styleCombo(cameraQuality);
        NvrUi.styleButton(saveCamera, true);
        saveCamera.addActionListener(e -> saveCameraSettings());
        actions.add(saveCamera);
        panel.add(actions);
        panel.add(Box.createVerticalStrut(5));
        panel.add(cameraNotice);
        alignChildren(panel);
        return panel;
    }

    private Component sidebarGap(int normal, int compact) {
        SidebarGap gap = new SidebarGap(normal, compact); sidebarGaps.add(gap); return gap;
    }
    private void updateSidebarSpacing() {
        boolean compact = sectionsScroll.getViewport().getHeight() < 450;
        for (SidebarGap gap : sidebarGaps) gap.update(compact);
        for (JPanel row : compactRows) {
            GridBagLayout layout = (GridBagLayout) row.getLayout();
            for (Component child : row.getComponents()) {
                GridBagConstraints constraints = layout.getConstraints(child);
                constraints.insets.bottom = compact ? 3 : 7; layout.setConstraints(child, constraints);
            }
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height)); row.revalidate();
        }
        sectionsScroll.getViewport().getView().revalidate();
    }
    private static final class SidebarGap extends Box.Filler {
        private final int normal, compact;
        SidebarGap(int normal, int compact) {
            super(new Dimension(0, normal), new Dimension(0, normal), new Dimension(Integer.MAX_VALUE, normal));
            this.normal = normal; this.compact = compact;
        }
        void update(boolean tight) {
            int height = tight ? compact : normal;
            changeShape(new Dimension(0, height), new Dimension(0, height), new Dimension(Integer.MAX_VALUE, height));
        }
    }

    private void chooseDirectory() {
        if (closed || GraphicsEnvironment.isHeadless()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择录像保存目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        try { chooser.setSelectedFile(resolveDirectory(directory.getText().strip()).toFile()); }
        catch (RuntimeException invalid) { chooser.setSelectedFile(root.toFile()); }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directory.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
            directory.setCaretPosition(directory.getText().length());
        }
    }

    private void saveStorageSettings() {
        if (closed) return;
        try {
            capacity.commitEdit();
            keepDays.commitEdit();
            segmentMinutes.commitEdit();
            String value = directory.getText().strip();
            if (value.isBlank()) throw new IllegalArgumentException("请选择录像保存目录");
            RecorderSettings next = new RecorderSettings(resolveDirectory(value).toString(),
                    Math.round(((Number) capacity.getValue()).doubleValue() * GIB),
                    ((Number) keepDays.getValue()).intValue(),
                    ((Number) segmentMinutes.getValue()).intValue(), autoRecord.isSelected());
            captureStorageDraft();
            runStorageOperation("公共录像设置已保存", () -> storage.applySettings(next), true);
        } catch (Exception failure) {
            draft.notice = safeMessage(failure, "请检查保存目录和数字设置");
            draft.error = true;
            draft.publish(null);
        }
    }

    private void runStorageOperation(String success, CheckedOperation operation, boolean reloadFields) {
        if (closed || !storageBusy.compareAndSet(false, true)) return;
        draft.notice = reloadFields ? "正在保存公共录像设置…" : "正在清理所有摄像头的旧录像…";
        draft.error = false;
        draft.publish(this);
        setStorageEnabled(false);
        inlineNotice(storageNotice, reloadFields ? "正在保存录像设置…" : "正在按保存规则清理旧录像…", false);
        Thread.startVirtualThread(() -> {
            String error = null;
            try { operation.run(); }
            catch (Exception failure) { error = safeMessage(failure, "保存失败，请检查目录是否可写"); }
            String finalError = error;
            onEdt(() -> {
                storageBusy.set(false);
                if (finalError == null && reloadFields) {
                    draft.reset(root, storage.settings());
                }
                draft.notice = finalError == null ? success : finalError;
                draft.error = finalError != null;
                draft.publish(null);
                if (!closed) { listener.notice(draft.notice, draft.error); refresh(); }
            });
        });
    }

    private void setStorageEnabled(boolean enabled) {
        directory.setEnabled(enabled);
        browse.setEnabled(enabled);
        capacity.setEnabled(enabled);
        keepDays.setEnabled(enabled);
        segmentMinutes.setEnabled(enabled);
        autoRecord.setEnabled(enabled);
        saveStorage.setEnabled(enabled && storageDirty);
        cleanStorage.setEnabled(enabled && !storageDirty);
    }

    private void saveCameraSettings() {
        if (closed || cameraBusy.get()) return;
        String name = cameraName.getText().strip();
        char[] secret = cameraUrl.getPassword();
        String url;
        try { url = new String(secret).strip(); }
        finally { Arrays.fill(secret, '\0'); }
        if (name.isBlank()) {
            inlineNotice(cameraNotice, "请填写设备名称", true);
            return;
        }
        if (!url.regionMatches(true, 0, "rtsp://", 0, 7) && !url.regionMatches(true, 0, "rtsps://", 0, 8)
                && !url.regionMatches(true, 0, "https://", 0, 8)) {
            inlineNotice(cameraNotice, "监控地址须以 rtsp://、rtsps:// 或 https:// 开头", true);
            return;
        }
        String transport = (String) cameraTransport.getSelectedItem();
        boolean automatic = autoConnect.isSelected();
        VideoQuality quality = (VideoQuality) cameraQuality.getSelectedItem();
        boolean timestamp = recordingTimestamp.isSelected();
        if (!cameraBusy.compareAndSet(false, true)) return;
        setCameraEnabled(false);
        inlineNotice(cameraNotice, "正在保存设备设置…", false);
        Thread.startVirtualThread(() -> {
            boolean succeeded = false;
            try { listener.saveCamera(name, url, transport, automatic, quality, timestamp); succeeded = true; }
            catch (Exception ignored) { /* Camera failures deliberately omit addresses and passwords. */ }
            boolean saved = succeeded;
            onEdt(() -> {
                cameraBusy.set(false);
                if (closed) return;
                if (saved) cameraDirty = false;
                setCameraEnabled(true);
                updateCameraDirtyState();
                inlineNotice(cameraNotice, saved ? "设备设置已保存" : "设备设置保存失败，请检查地址和目录权限", !saved);
            });
        });
    }

    private void setCameraEnabled(boolean enabled) {
        cameraName.setEnabled(enabled);
        cameraUrl.setEnabled(enabled);
        cameraTransport.setEnabled(enabled);
        cameraQuality.setEnabled(enabled);
        showPassword.setEnabled(enabled);
        autoConnect.setEnabled(enabled);
        recordingTimestamp.setEnabled(enabled);
        saveCamera.setEnabled(enabled && cameraDirty);
    }

    private void applySnapshot(Snapshot snapshot) {
        RecorderSettings settings = snapshot.settings();
        storageState.setText(snapshot.recording() ? "● 有摄像头正在录制" : snapshot.requested() ? "● 正在连接录像" : "○ 所有摄像头均未录制");
        storageState.setForeground(snapshot.recording() ? RED : MUTED);
        storageUsage.setText("总占用：" + formatBytes(snapshot.bytes()) + " / " + formatBytes(settings.maxBytes()));
        publishStorageSummary();
        double ratio = settings.maxBytes() == 0 ? 0 : (double) snapshot.bytes() / settings.maxBytes();
        usageBar.setValue((int) Math.max(0, Math.min(1000, Math.round(ratio * 1000))));
        storageUsage.setToolTipText("录像容量上限；保留 " + settings.keepDays() + " 天");
        if (!storageLoaded && !storageBusy.get() && settings.equals(storage.settings())) {
            loadStorageValues(settings);
            storageLoaded = true;
        }
        if (pendingPersonMode == snapshot.mode()) pendingPersonMode = null;
        if (pendingPersonMode == null) {
            syncingPersonMode = true;
            try { personMode.setSelectedIndex(snapshot.mode() == PersonEventService.Mode.ACCURATE ? 0 : 1); }
            finally { syncingPersonMode = false; }
        }
        String detectionState;
        if (snapshot.disabled()) detectionState = "人员识别暂不可用";
        else if (snapshot.analyzing() > 0) detectionState = "正在识别新画面 · 已更新 " + snapshot.ready() + " / " + snapshot.completed();
        else if (snapshot.pending() > 0) detectionState = "等待识别 · 已更新 " + snapshot.ready() + " / " + snapshot.completed();
        else if (snapshot.failed() > 0) detectionState = "已标记 " + snapshot.ready() + " 段 · " + snapshot.failed() + " 段待重试";
        else if (snapshot.completed() > 0) detectionState = "已有录像人员标记已更新";
        else detectionState = "等待收到录像画面";
        personState.setText(detectionState);
        personState.setToolTipText(snapshot.availability());
        int regionCount = persons.ignoreRegions().size();
        ignoreRegionsState.setText(regionCount == 0 ? "未设置" : "忽略 " + regionCount + " 个区域");
        ignoreRegionsState.setToolTipText("仅作用于当前摄像头，录像照常保存");
        ignoreRegions.setEnabled(!ignoreRegionsBusy);
        clearIgnoreRegions.setEnabled(!ignoreRegionsBusy && regionCount > 0);
        reanalyse.setEnabled(!snapshot.disabled() && snapshot.completed() > 0);
        reanalyse.setToolTipText(snapshot.disabled() ? snapshot.availability()
                : snapshot.completed() == 0 ? "收到录像画面后可重新识别"
                : "重新分析当前摄像头的录像；录制中的人员事件继续更新");
    }

    private void loadStorageValues(RecorderSettings settings) {
        loadingStorage = true;
        try {
            directory.setText(settings.resolvedDirectory(root).toString());
            directory.setCaretPosition(directory.getText().length());
            directory.setToolTipText(directory.getText());
            capacity.setValue(Math.max(0.25, Math.min(102400.0, (double) settings.maxBytes() / GIB)));
            keepDays.setValue(settings.keepDays());
            segmentMinutes.setValue(settings.segmentMinutes());
            autoRecord.setSelected(settings.autoRecord());
        } finally { loadingStorage = false; }
    }

    private void watchStorageEdits() {
        Runnable changed = () -> {
            directory.setToolTipText(directory.getText());
            if (loadingStorage || closed) return;
            storageDirty = true;
            draft.error = false;
            captureStorageDraft();
            updateStorageDirtyState();
            draft.publish(this);
        };
        watchText(directory, changed);
        for (JSpinner spinner : new JSpinner[] {capacity, keepDays, segmentMinutes}) {
            spinner.addChangeListener(event -> changed.run());
            if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor)
                watchText(editor.getTextField(), changed);
        }
        autoRecord.addActionListener(event -> changed.run());
    }

    private static String spinnerText(JSpinner spinner) {
        return ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().getText();
    }
    private static void restoreSpinner(JSpinner spinner, String text) {
        try {
            Number current = (Number) spinner.getValue();
            if (current instanceof Double) spinner.setValue(Double.valueOf(text));
            else spinner.setValue(Integer.valueOf(text));
        } catch (RuntimeException invalidDraft) { /* Keep invalid input editable until Save validates it. */ }
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText(text);
    }
    private void captureStorageDraft() {
        draft.directory = directory.getText(); draft.capacity = spinnerText(capacity);
        draft.days = spinnerText(keepDays); draft.segment = spinnerText(segmentMinutes);
        draft.automatic = autoRecord.isSelected(); draft.dirty = storageDirty;
    }
    void applyStorageDraft() {
        if (closed) return;
        loadingStorage = true;
        try {
            directory.setText(draft.directory); directory.setToolTipText(draft.directory);
            directory.setCaretPosition(directory.getText().length());
            restoreSpinner(capacity, draft.capacity); restoreSpinner(keepDays, draft.days);
            restoreSpinner(segmentMinutes, draft.segment); autoRecord.setSelected(draft.automatic);
            storageDirty = draft.dirty; storageLoaded = true;
        } finally { loadingStorage = false; }
        setStorageEnabled(!storageBusy.get()); updateStorageDirtyState();
        if (storageBusy.get() || !storageDirty || draft.error) {
            storageNotice.setText("<html><div style='width:220px'>" + escapeHtml(draft.notice) + "</div></html>");
            storageNotice.setForeground(draft.error ? RED : MUTED);
        }
        revalidate();
    }

    private void watchCameraEdits() {
        Runnable changed = () -> {
            if (loadingCamera || closed) return;
            cameraDirty = true;
            updateCameraDirtyState();
        };
        watchText(cameraName, changed);
        watchText(cameraUrl, changed);
        cameraTransport.addActionListener(event -> changed.run());
        cameraQuality.addActionListener(event -> changed.run());
        autoConnect.addActionListener(event -> changed.run());
        recordingTimestamp.addActionListener(event -> changed.run());
    }

    private void updateStorageDirtyState() {
        if (storageSection != null) storageSection.setDirty(storageDirty);
        saveStorage.setEnabled(storageDirty && !storageBusy.get());
        saveStorage.setToolTipText(storageDirty ? "保存当前修改" : "录像设置已保存");
        cleanStorage.setEnabled(!storageDirty && !storageBusy.get());
        cleanStorage.setToolTipText(storageDirty ? "先保存修改，再按存储策略清理"
                : "立即按已保存的容量上限和保存时长检查最旧录像");
        if (storageDirty && !storageBusy.get()) {
            storageNotice.setText("<html>修改未保存，点击保存录像设置后生效</html>");
            storageNotice.setForeground(RED);
        }
        publishStorageSummary();
    }

    private void updateCameraDirtyState() {
        if (cameraSection != null) cameraSection.setDirty(cameraDirty);
        saveCamera.setEnabled(cameraDirty && !cameraBusy.get());
        saveCamera.setToolTipText(cameraDirty ? "保存当前设备设置" : "设备设置已保存");
        if (cameraDirty && !cameraBusy.get()) {
            cameraNotice.setText("<html>修改未保存，点击保存设备设置后生效</html>");
            cameraNotice.setForeground(RED);
        }
    }

    private static void watchText(JTextField field, Runnable changed) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { changed.run(); }
            @Override public void removeUpdate(DocumentEvent event) { changed.run(); }
            @Override public void changedUpdate(DocumentEvent event) { changed.run(); }
        });
    }

    private void inlineNotice(JLabel label, String message, boolean error) {
        String safe = Objects.requireNonNullElse(message, "操作失败").replaceAll("[\\r\\n]+", " ");
        label.setText("<html><div style='width:240px'>" + escapeHtml(safe) + "</div></html>");
        label.setForeground(error ? RED : GREEN);
        label.setToolTipText(safe);
        listener.notice(safe, error);
        revalidate();
    }

    private Path resolveDirectory(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private static String safeMessage(Throwable failure, String fallback) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return fallback;
        return message.replaceAll("(?i)(?:rtsps?|https?)://[^\\s]+", "[监控地址]").replaceAll("[\\r\\n]+", " ");
    }

    private static String formatBytes(long bytes) {
        return bytes >= GIB ? String.format(Locale.ROOT, "%.1f GB", (double) bytes / GIB)
                : bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", (double) bytes / (1024 * 1024))
                : bytes >= 1024 ? String.format(Locale.ROOT, "%.1f KB", (double) bytes / 1024)
                : Math.max(0, bytes) + " B";
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel actionRow() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel unitRow(JSpinner spinner, String unit) {
        NvrUi.styleSpinner(spinner);
        spinner.setPreferredSize(new Dimension(98, 32));
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        panel.add(spinner, BorderLayout.CENTER);
        JLabel suffix = label(unit);
        suffix.setPreferredSize(new Dimension(30, 29));
        panel.add(suffix, BorderLayout.EAST);
        return panel;
    }

    private static void formRow(JPanel panel, int row, String title, Component field) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.WEST;
        label.insets = new Insets(0, 0, 7, 9);
        JLabel name = label(title);
        name.setPreferredSize(new Dimension(66, 29));
        panel.add(name, label);
        GridBagConstraints control = new GridBagConstraints();
        control.gridx = 1;
        control.gridy = row;
        control.weightx = 1;
        control.fill = GridBagConstraints.HORIZONTAL;
        control.insets = new Insets(0, 0, 7, 0);
        panel.add(field, control);
    }

    private static void alignChildren(JPanel panel) {
        for (Component child : panel.getComponents()) {
            if (child instanceof JComponent component) {
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (!(child instanceof JLabel)) component.setMaximumSize(
                        new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
            }
        }
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT);
        label.setForeground(TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JCheckBox checkBox(String text) {
        JCheckBox checkbox = new JCheckBox(text);
        checkbox.setFont(FONT);
        checkbox.setOpaque(false);
        checkbox.setForeground(TEXT);
        checkbox.setBorder(BorderFactory.createEmptyBorder());
        return checkbox;
    }

    private static JButton button(String title) {
        return NvrUi.button(title, null, false, null);
    }

    private static void styleField(JTextField field) {
        NvrUi.styleField(field);
        field.setPreferredSize(new Dimension(245, 32));
    }

    private static void named(JComponent component, String name) {
        component.setName(name);
        component.getAccessibleContext().setAccessibleName(name);
    }

    private static void applyFonts(Component component) {
        component.setFont(component instanceof JToggleButton ? FONT.deriveFont(Font.BOLD) : FONT);
        if (component instanceof Container parent) for (Component child : parent.getComponents()) applyFonts(child);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    private static void onEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else try { SwingUtilities.invokeAndWait(action); }
        catch (Exception failure) { throw new IllegalStateException("无法创建录像机设置", failure); }
    }

    private record Snapshot(RecorderSettings settings, long bytes, boolean requested, boolean recording,
                            PersonEventService.Mode mode, String availability, int completed, int ready,
                            int pending, int analyzing, int failed, boolean disabled) {}
    @FunctionalInterface private interface CheckedOperation { void run() throws Exception; }

    private static final class SidebarContents extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(308, 560); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int direction, int amount) { return 18; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int direction, int amount) { return Math.max(18, visible.height - 36); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class Collapsible extends JPanel {
        private final String title;
        private final JToggleButton header;
        private final JPanel body;
        private boolean dirty;
        Collapsible(String title, JPanel body, boolean expanded) {
            super(new BorderLayout());
            this.title = title;
            this.body = body;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
            header = new JToggleButton();
            header.setSelected(expanded);
            header.setOpaque(false);
            header.setContentAreaFilled(false);
            header.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            header.setHorizontalAlignment(SwingConstants.LEFT);
            header.setIconTextGap(7);
            header.setForeground(TEXT);
            header.setFont(FONT.deriveFont(Font.BOLD));
            header.setFocusPainted(true);
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            header.addActionListener(e -> setExpanded(header.isSelected()));
            body.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
            add(header, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            setExpanded(expanded);
        }
        void setExpanded(boolean expanded) {
            header.setSelected(expanded);
            header.setText(dirty ? title + " · 未保存" : title);
            header.setIcon(new ChevronIcon(expanded));
            body.setVisible(expanded);
            revalidate();
            repaint();
        }
        void setDirty(boolean dirty) {
            this.dirty = dirty;
            header.setText(dirty ? title + " · 未保存" : title);
        }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private record ChevronIcon(boolean expanded) implements Icon {
        @Override public int getIconWidth() { return 9; }
        @Override public int getIconHeight() { return 9; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(TEXT);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (expanded) {
                g.drawLine(1, 3, 4, 6);
                g.drawLine(4, 6, 7, 3);
            } else {
                g.drawLine(3, 1, 6, 4);
                g.drawLine(6, 4, 3, 7);
            }
            g.dispose();
        }
    }
}
