package cn.homewatch;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Compact recorder management. Directory access always runs outside the Swing event thread. */
public final class RecorderDialog {
    private static final Color SURFACE = new Color(248, 248, 249);
    private static final Color BORDER = new Color(216, 218, 221);
    private static final Color TEXT = new Color(64, 67, 72);
    private static final Color MUTED = new Color(125, 130, 137);
    private static final Color RED = new Color(207, 44, 49);
    private static final Color GREEN = new Color(45, 147, 92);
    private static final Color SELECTION = new Color(251, 237, 237);
    private static final Font FONT = new Font(chooseFont(), Font.PLAIN, 13);
    private static final long GIB = 1024L * 1024 * 1024;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Component owner;
    private final Path root;
    private final RecorderService service;
    private final Runnable onToggleRecording;
    private final Consumer<Path> onPlay;
    private final AtomicBoolean refreshBusy = new AtomicBoolean();
    private final AtomicBoolean operationBusy = new AtomicBoolean();
    private final RecordingsModel model = new RecordingsModel();
    private final CardLayout pages = new CardLayout();
    private JPanel content;
    private JPanel pageContainer;
    private JToggleButton listTab;
    private JToggleButton settingsTab;
    private JDialog dialog;
    private JTable table;
    private JSpinner date;
    private JButton recordButton;
    private JButton playButton;
    private JButton saveButton;
    private JButton cleanButton;
    private JLabel countLabel;
    private JLabel recordState;
    private JLabel storageLabel;
    private JLabel noticeLabel;
    private JTextField directory;
    private JSpinner capacity;
    private JSpinner keepDays;
    private JSpinner segmentMinutes;
    private JCheckBox autoRecord;
    private Timer timer;
    private List<RecordingEntry> recordings = List.of();
    private LocalDate filterDate;
    private RecorderSettings latestSettings;
    private boolean requested;
    private boolean settingsLoaded;
    private volatile boolean disposed;
    private long noticeUntil;

    public RecorderDialog(Component owner, Path root, RecorderService service,
                          Runnable onToggleRecording, Consumer<Path> onPlay) {
        this.owner = owner;
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.service = Objects.requireNonNull(service);
        this.onToggleRecording = Objects.requireNonNull(onToggleRecording);
        this.onPlay = Objects.requireNonNull(onPlay);
        onEdtAndWait(this::buildUi);
        refresh();
    }

    public void showDialog() {
        onEdt(() -> {
            if (GraphicsEnvironment.isHeadless()) return;
            disposed = false;
            if (dialog == null || !dialog.isDisplayable()) {
                Window parent = owner instanceof Window w ? w : owner == null ? null : SwingUtilities.getWindowAncestor(owner);
                dialog = new JDialog(parent, "录像机", java.awt.Dialog.ModalityType.MODELESS);
                dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                dialog.setContentPane(content);
                dialog.setMinimumSize(new Dimension(800, 520));
                dialog.setSize(920, 640);
                dialog.setLocationRelativeTo(owner);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent e) {
                        timer.stop();
                        disposed = true;
                    }
                });
                content.registerKeyboardAction(e -> disposeDialog(), KeyStroke.getKeyStroke("ESCAPE"),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
            }
            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();
            timer.start();
            refresh();
        });
    }

    /** Request a fresh directory scan without blocking the user interface. */
    public void refresh() { requestRefresh(true); }

    public void showSettings() {
        onEdt(() -> {
            settingsTab.setSelected(true);
            pages.show(pageContainer, "settings");
            showDialog();
        });
    }

    public void disposeDialog() {
        onEdt(() -> {
            disposed = true;
            timer.stop();
            if (dialog != null) dialog.dispose();
        });
    }

    /** Render the real UI, including table and form, without creating a native window. */
    public BufferedImage renderPreview(int width, int height) {
        Snapshot snapshot = readSnapshot();
        BufferedImage[] output = new BufferedImage[1];
        onEdtAndWait(() -> {
            applySnapshot(snapshot);
            content.setSize(width, height);
            layoutTree(content);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            content.printAll(g);
            g.dispose();
            output[0] = image;
        });
        return output[0];
    }

    private void buildUi() {
        content = new JPanel(new BorderLayout());
        content.setBackground(SURFACE);
        content.setFont(FONT);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabs.setOpaque(false);
        listTab = new TabButton("录像列表");
        settingsTab = new TabButton("录像设置");
        ButtonGroup group = new ButtonGroup();
        group.add(listTab);
        group.add(settingsTab);
        listTab.setSelected(true);
        listTab.addActionListener(e -> pages.show(pageContainer, "list"));
        settingsTab.addActionListener(e -> pages.show(pageContainer, "settings"));
        tabs.add(listTab);
        tabs.add(settingsTab);
        header.add(tabs, BorderLayout.WEST);
        recordState = label("未录制");
        recordState.setForeground(MUTED);
        recordState.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 20));
        header.add(recordState, BorderLayout.EAST);
        content.add(header, BorderLayout.NORTH);

        pageContainer = new JPanel(pages);
        pageContainer.add(buildListPage(), "list");
        pageContainer.add(buildSettingsPage(), "settings");
        content.add(pageContainer, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        storageLabel = label("正在读取录像…");
        storageLabel.setForeground(MUTED);
        noticeLabel = label(" ");
        noticeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(storageLabel, BorderLayout.WEST);
        footer.add(noticeLabel, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        timer = new Timer(2000, e -> requestRefresh(false));
        setFonts(content);
    }

    private JPanel buildListPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Color.WHITE);
        page.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JPanel tools = new JPanel();
        tools.setOpaque(false);
        tools.setLayout(new BoxLayout(tools, BoxLayout.Y_AXIS));

        JPanel filterRow = new JPanel(new BorderLayout(12, 0));
        filterRow.setOpaque(false);
        JPanel filter = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        filter.setOpaque(false);
        filter.add(label("日期"));
        date = new JSpinner(new SpinnerDateModel());
        date.setEditor(new JSpinner.DateEditor(date, "yyyy-MM-dd"));
        date.setPreferredSize(new Dimension(133, 32));
        date.setBorder(BorderFactory.createLineBorder(BORDER));
        filter.add(date);
        filter.add(button("查询", () -> {
            try {
                date.commitEdit();
                filterDate = ((Date) date.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                updateRows();
            } catch (java.text.ParseException ex) {
                notice("日期格式为 yyyy-MM-dd", true);
            }
        }));
        filter.add(button("今天", () -> {
            filterDate = LocalDate.now();
            date.setValue(new Date());
            updateRows();
        }));
        filter.add(button("全部", () -> { filterDate = null; updateRows(); }));
        filterRow.add(filter, BorderLayout.WEST);
        recordButton = button("开始录制", () -> {
            try {
                onToggleRecording.run();
                refresh();
            } catch (RuntimeException e) { notice(message(e), true); }
        });
        recordButton.setForeground(RED);
        recordButton.setPreferredSize(new Dimension(104, 32));
        filterRow.add(recordButton, BorderLayout.EAST);
        tools.add(filterRow);
        tools.add(Box.createVerticalStrut(12));

        JPanel actionRow = new JPanel(new BorderLayout());
        actionRow.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        playButton = button("回放", this::playSelected);
        playButton.setEnabled(false);
        actions.add(playButton);
        actions.add(button("打开目录", this::openDirectory));
        actions.add(button("刷新", this::refresh));
        actionRow.add(actions, BorderLayout.WEST);
        countLabel = label("0 段录像");
        countLabel.setForeground(MUTED);
        countLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        actionRow.add(countLabel, BorderLayout.EAST);
        tools.add(actionRow);
        tools.add(Box.createVerticalStrut(12));
        page.add(tools, BorderLayout.NORTH);

        table = new JTable(model) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getRowCount() == 0) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setFont(FONT);
                    g.setColor(MUTED);
                    String text = filterDate == null ? "暂无录像" : "这一天没有录像";
                    int x = (getWidth() - g.getFontMetrics().stringWidth(text)) / 2;
                    g.drawString(text, Math.max(20, x), Math.max(70, getHeight() / 2));
                    g.dispose();
                }
            }
        };
        table.setRowHeight(40);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(SELECTION);
        table.setSelectionForeground(TEXT);
        table.setForeground(TEXT);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(100, 36));
        table.getTableHeader().setBackground(SURFACE);
        table.getTableHeader().setForeground(TEXT);
        table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(190);
        table.getColumnModel().getColumn(0).setMinWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setMinWidth(88);
        table.getColumnModel().getColumn(2).setPreferredWidth(95);
        table.getColumnModel().getColumn(2).setMinWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(430);
        table.getSelectionModel().addListSelectionListener(e -> {
            RecordingEntry selected = selectedEntry();
            playButton.setEnabled(selected != null && !selected.active());
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) { table.setRowSelectionInterval(row, row); playSelected(); }
                }
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private JPanel buildSettingsPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Color.WHITE);
        page.setBorder(BorderFactory.createEmptyBorder(27, 27, 20, 27));
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        directory = new JTextField(root.resolve("data/recordings").toString());
        directory.setColumns(30);
        directory.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        JPanel directoryRow = new JPanel(new BorderLayout(9, 0));
        directoryRow.setOpaque(false);
        directoryRow.add(directory, BorderLayout.CENTER);
        directoryRow.add(button("浏览…", this::chooseDirectory), BorderLayout.EAST);
        addFormRow(form, 0, "保存目录", directoryRow);
        capacity = spinner(new SpinnerNumberModel(20.0, 0.25, 102400.0, 0.25));
        capacity.setEditor(new JSpinner.NumberEditor(capacity, "0.##"));
        addFormRow(form, 1, "最大占用", unitRow(capacity, "GB"));
        keepDays = spinner(new SpinnerNumberModel(7, 1, 3650, 1));
        addFormRow(form, 2, "保存时长", unitRow(keepDays, "天"));
        segmentMinutes = spinner(new SpinnerNumberModel(10, 1, 60, 1));
        addFormRow(form, 3, "单段时长", unitRow(segmentMinutes, "分钟"));
        autoRecord = new JCheckBox("软件启动后自动录制");
        autoRecord.setOpaque(false);
        autoRecord.setForeground(TEXT);
        autoRecord.setBorder(BorderFactory.createEmptyBorder());
        addFormRow(form, 4, "", autoRecord);

        GridBagConstraints hintConstraints = new GridBagConstraints();
        hintConstraints.gridx = 1;
        hintConstraints.gridy = 5;
        hintConstraints.weightx = 1;
        hintConstraints.fill = GridBagConstraints.HORIZONTAL;
        hintConstraints.anchor = GridBagConstraints.WEST;
        hintConstraints.insets = new Insets(13, 0, 18, 0);
        JPanel hint = new JPanel(new BorderLayout());
        hint.setOpaque(false);
        hint.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(16, 0, 0, 0)));
        JLabel explanation = label("<html>达到空间或保留期限时自动删除最旧的本软件录像；<br>正在录制或打开回放的文件保留。</html>");
        explanation.setForeground(MUTED);
        hint.add(explanation);
        form.add(hint, hintConstraints);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        saveButton = button("保存设置", this::saveSettings);
        saveButton.setForeground(RED);
        actions.add(saveButton);
        actions.add(Box.createHorizontalStrut(12));
        cleanButton = button("立即清理", () -> runOperation("清理检查已完成", service::cleanupNow));
        cleanButton.setToolTipText("按当前空间上限和保存时长清理过期录像");
        actions.add(cleanButton);
        addFormRow(form, 6, "", actions);

        page.add(form, BorderLayout.NORTH);
        return page;
    }

    private void chooseDirectory() {
        if (GraphicsEnvironment.isHeadless()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择录像保存目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        try { chooser.setSelectedFile(resolveDirectory(directory.getText()).toFile()); }
        catch (RuntimeException ignored) { chooser.setSelectedFile(root.toFile()); }
        if (chooser.showOpenDialog(dialog == null ? owner : dialog) == JFileChooser.APPROVE_OPTION)
            directory.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
    }

    private void saveSettings() {
        try {
            capacity.commitEdit();
            keepDays.commitEdit();
            segmentMinutes.commitEdit();
            String dir = directory.getText().strip();
            if (dir.isBlank()) throw new IllegalArgumentException("请选择录像保存目录");
            Path path = resolveDirectory(dir);
            RecorderSettings next = new RecorderSettings(path.toString(),
                    Math.round(((Number) capacity.getValue()).doubleValue() * GIB),
                    ((Number) keepDays.getValue()).intValue(),
                    ((Number) segmentMinutes.getValue()).intValue(), autoRecord.isSelected());
            runOperation("设置已保存", () -> service.applySettings(next));
        } catch (Exception e) { notice(message(e), true); }
    }

    private void runOperation(String success, CheckedOperation operation) {
        if (!operationBusy.compareAndSet(false, true)) return;
        saveButton.setEnabled(false);
        cleanButton.setEnabled(false);
        notice("正在处理…", false);
        Thread.startVirtualThread(() -> {
            String error = null;
            Snapshot snapshot = null;
            try { operation.run(); service.refresh(); snapshot = readSnapshot(); }
            catch (Exception e) { error = message(e); }
            finally { operationBusy.set(false); }
            Snapshot result = snapshot;
            String failure = error;
            onEdt(() -> {
                saveButton.setEnabled(true);
                cleanButton.setEnabled(true);
                if (disposed) return;
                if (result != null) applySnapshot(result);
                notice(failure == null ? success : failure, failure != null);
            });
        });
    }

    private void openDirectory() {
        Thread.startVirtualThread(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                    throw new IllegalStateException("此电脑不支持打开目录");
                Desktop.getDesktop().open(resolveDirectory(service.settings().directory()).toFile());
            } catch (Exception e) { onEdt(() -> notice(message(e), true)); }
        });
    }

    private void playSelected() {
        RecordingEntry selected = selectedEntry();
        if (selected != null && !selected.active()) {
            try { onPlay.accept(selected.path()); }
            catch (RuntimeException failure) { notice(message(failure), true); }
        }
    }

    private RecordingEntry selectedEntry() {
        int row = table.getSelectedRow();
        return row >= 0 && row < model.entries.size() ? model.entries.get(table.convertRowIndexToModel(row)) : null;
    }

    private void requestRefresh(boolean scan) {
        if (disposed || !refreshBusy.compareAndSet(false, true)) return;
        Thread.startVirtualThread(() -> {
            try {
                if (scan) service.refresh();
                Snapshot snapshot = readSnapshot();
                onEdt(() -> { if (!disposed) applySnapshot(snapshot); });
            } catch (Exception e) { onEdt(() -> { if (!disposed) notice(message(e), true); }); }
            finally { refreshBusy.set(false); }
        });
    }

    private Snapshot readSnapshot() {
        return new Snapshot(service.settings(), List.copyOf(service.listRecordings()), service.usedBytes(),
                service.isRequested(), service.isRecording());
    }

    private void applySnapshot(Snapshot snapshot) {
        latestSettings = snapshot.settings();
        recordings = snapshot.recordings();
        requested = snapshot.requested();
        recordButton.setText(requested ? "停止录制" : "开始录制");
        recordState.setText(snapshot.recording() ? "● 正在录制" : requested ? "● 等待连接" : "未录制");
        recordState.setForeground(snapshot.recording() ? RED : MUTED);
        storageLabel.setText("已用 " + formatBytes(snapshot.bytes()) + " / " + formatBytes(latestSettings.maxBytes())
                + "  ·  保留 " + latestSettings.keepDays() + " 天");
        storageLabel.setToolTipText(resolveDirectory(latestSettings.directory()).toString());
        if (!settingsLoaded) {
            directory.setText(resolveDirectory(latestSettings.directory()).toString());
            directory.setCaretPosition(0);
            capacity.setValue(Math.max(0.25, Math.min(102400.0, (double) latestSettings.maxBytes() / GIB)));
            keepDays.setValue(Math.max(1, Math.min(3650, latestSettings.keepDays())));
            segmentMinutes.setValue(latestSettings.segmentMinutes());
            autoRecord.setSelected(latestSettings.autoRecord());
            settingsLoaded = true;
        }
        updateRows();
        if (System.currentTimeMillis() > noticeUntil) noticeLabel.setText(" ");
    }

    private void updateRows() {
        RecordingEntry selected = selectedEntry();
        Path selectedPath = selected == null ? null : selected.path();
        List<RecordingEntry> filtered = new ArrayList<>();
        for (RecordingEntry entry : recordings) {
            if (filterDate == null || entry.start().atZone(ZoneId.systemDefault()).toLocalDate().equals(filterDate))
                filtered.add(entry);
        }
        filtered.sort(Comparator.comparing(RecordingEntry::start).reversed());
        if (!model.entries.equals(filtered)) {
            model.entries = List.copyOf(filtered);
            model.fireTableDataChanged();
            if (selectedPath != null) {
                for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).path().equals(selectedPath)) {
                    table.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
        long active = filtered.stream().filter(RecordingEntry::active).count();
        countLabel.setText((filterDate == null ? "全部" : filterDate.toString()) + "  ·  "
                + filtered.size() + " 段录像" + (active > 0 ? "  ·  " + active + " 段录制中" : ""));
        RecordingEntry now = selectedEntry();
        playButton.setEnabled(now != null && !now.active());
    }

    private void notice(String message, boolean error) {
        String safe = message == null ? "操作失败" : message.replaceAll("[\\r\\n]+", " ");
        if (safe.length() > 80) safe = safe.substring(0, 77) + "…";
        noticeLabel.setText(safe);
        noticeLabel.setForeground(error ? RED : GREEN);
        noticeLabel.setToolTipText(message);
        noticeUntil = System.currentTimeMillis() + 10000;
    }

    private Path resolveDirectory(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "操作失败，请检查保存目录是否可用" : message;
    }

    private static String formatBytes(long value) {
        if (value >= GIB) return String.format(java.util.Locale.ROOT, "%.1f GB", (double) value / GIB);
        if (value >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", (double) value / (1024 * 1024));
        if (value >= 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", (double) value / 1024);
        return Math.max(0, value) + " B";
    }

    private static JPanel unitRow(JSpinner spinner, String unit) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row.setOpaque(false);
        row.add(spinner);
        row.add(label(unit));
        return row;
    }

    private static JSpinner spinner(SpinnerNumberModel model) {
        JSpinner spinner = new JSpinner(model);
        spinner.setPreferredSize(new Dimension(132, 34));
        spinner.setBorder(BorderFactory.createLineBorder(BORDER));
        return spinner;
    }

    private static void addFormRow(JPanel form, int row, String title, Component component) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.WEST;
        label.insets = new Insets(0, 0, 12, 22);
        JLabel name = label(title);
        name.setPreferredSize(new Dimension(72, 34));
        form.add(name, label);
        GridBagConstraints field = new GridBagConstraints();
        field.gridx = 1;
        field.gridy = row;
        field.weightx = 1;
        field.fill = GridBagConstraints.HORIZONTAL;
        field.anchor = GridBagConstraints.WEST;
        field.insets = new Insets(0, 0, 12, 0);
        form.add(component, field);
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT);
        label.setForeground(TEXT);
        return label;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new FlatButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static void setFonts(Component component) {
        component.setFont(FONT);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) setFonts(child);
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component component : container.getComponents())
            if (component instanceof Container child) layoutTree(child);
    }

    private static String chooseFont() {
        List<String> names = java.util.Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : List.of("Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC"))
            if (names.contains(name)) return name;
        return Font.SANS_SERIF;
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    private static void onEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else try { SwingUtilities.invokeAndWait(action); }
        catch (Exception e) { throw new IllegalStateException("无法创建录像机窗口", e); }
    }

    private record Snapshot(RecorderSettings settings, List<RecordingEntry> recordings, long bytes,
                            boolean requested, boolean recording) {}
    @FunctionalInterface private interface CheckedOperation { void run() throws Exception; }

    private static final class RecordingsModel extends AbstractTableModel {
        private List<RecordingEntry> entries = List.of();
        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int column) {
            return switch (column) { case 0 -> "开始时间"; case 1 -> "文件大小"; case 2 -> "状态"; default -> "文件名"; };
        }
        @Override public Object getValueAt(int row, int column) {
            RecordingEntry entry = entries.get(row);
            return switch (column) {
                case 0 -> DATE_TIME.format(entry.start().atZone(ZoneId.systemDefault()));
                case 1 -> formatBytes(entry.bytes());
                case 2 -> entry.active() ? "录制中" : "已完成";
                default -> entry.path().getFileName().toString();
            };
        }
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        HeaderRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                    BorderFactory.createEmptyBorder(0, 14, 0, 14)));
        }
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                                                                  boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            setBackground(SURFACE);
            setForeground(TEXT);
            setFont(FONT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                    BorderFactory.createEmptyBorder(0, 14, 0, 14)));
            return this;
        }
    }

    private static final class RowRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                                                                  boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            setFont(FONT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(240, 241, 242)),
                    BorderFactory.createEmptyBorder(0, 14, 0, 14)));
            setBackground(selected ? SELECTION : Color.WHITE);
            setForeground(col == 2 && "录制中".equals(value) ? RED : TEXT);
            setToolTipText(value == null ? null : value.toString());
            return this;
        }
    }

    private static final class FlatButton extends JButton {
        FlatButton(String text) {
            super(text);
            setFont(FONT);
            setForeground(TEXT);
            setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(getModel().isPressed() ? new Color(232, 233, 235)
                    : getModel().isRollover() && isEnabled() ? new Color(244, 244, 246) : Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(BORDER);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class TabButton extends JToggleButton {
        TabButton(String title) {
            super(title);
            setPreferredSize(new Dimension(115, 48));
            setBorder(BorderFactory.createEmptyBorder());
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
            setFont(FONT);
        }
        @Override protected void paintComponent(Graphics graphics) {
            setForeground(isSelected() ? RED : TEXT);
            Graphics2D g = (Graphics2D) graphics.create();
            if (getModel().isRollover()) {
                g.setColor(SURFACE);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            if (isSelected()) {
                g.setColor(RED);
                g.fillRect(22, getHeight() - 3, getWidth() - 44, 3);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
