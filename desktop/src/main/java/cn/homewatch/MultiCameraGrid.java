package cn.homewatch;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** One live video surface with a bounded number of tiles and no per-frame Swing tasks. */
public final class MultiCameraGrid extends JPanel {
    private static final Color BACKGROUND = new Color(24, 26, 29);
    private static final Color SELECTED = new Color(196, 82, 85);
    private static final Color TEXT = new Color(231, 234, 237);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    private final Consumer<String> onSelect;
    private final Consumer<String> onFocus;
    private final Runnable onAdd;
    private final ConcurrentHashMap<String, BufferedImage> frames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean repaintQueued = new AtomicBoolean();
    private volatile List<MonitorWindow.DeviceSummary> devices = List.of();
    private volatile String selectedId = "";
    private volatile int tiles = 1;
    private int page;
    private Runnable viewListener = () -> {};

    public MultiCameraGrid(Consumer<String> onSelect, Consumer<String> onFocus, Runnable onAdd) {
        this.onSelect = Objects.requireNonNull(onSelect);
        this.onFocus = Objects.requireNonNull(onFocus);
        this.onAdd = Objects.requireNonNull(onAdd);
        setBackground(BACKGROUND);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("单击选择通道，双击放大或恢复分屏");
        setName("devices.grid");
        getAccessibleContext().setAccessibleName("实时多通道画面");
        getAccessibleContext().setAccessibleDescription("单击选择通道，双击放大或恢复分屏；方向键切换，回车放大或恢复选中通道");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getButton() != MouseEvent.BUTTON1) return;
                requestFocusInWindow();
                MonitorWindow.DeviceSummary device = deviceAt(event.getX(), event.getY());
                if (device == null) {
                    // A click on the narrow separator is not an Add Device command.
                    if (event.getClickCount() == 1 && emptyTileAt(event.getX(), event.getY())) onAdd.run();
                    return;
                }
                select(device.id());
                if (event.getClickCount() == 2) onFocus.accept(device.id());
            }
        });
        bind("LEFT", -1); bind("RIGHT", 1);
        bind("UP", -3); bind("DOWN", 3);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "focusChannel");
        getActionMap().put("focusChannel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (!selectedId.isBlank()) onFocus.accept(selectedId);
            }
        });
    }

    public void setDevices(List<MonitorWindow.DeviceSummary> summaries, String selected) {
        onEdt(() -> {
            devices = List.copyOf(summaries);
            selectedId = Objects.requireNonNullElse(selected, "");
            var ids = devices.stream().map(MonitorWindow.DeviceSummary::id).toList();
            frames.keySet().retainAll(ids);
            states.keySet().retainAll(ids);
            ensureSelectedPage();
            viewListener.run(); repaint();
        });
    }

    public void setFrame(String id, BufferedImage frame) {
        if (id == null || id.isBlank()) return;
        if (frame == null) frames.remove(id); else frames.put(id, frame);
        queueRepaint();
    }

    public BufferedImage frame(String id) { return id == null ? null : frames.get(id); }

    public void setState(String id, String state, boolean recording) {
        if (id == null || id.isBlank()) return;
        states.put(id, new DeviceState(Objects.requireNonNullElse(state, "DISCONNECTED"), recording));
        queueRepaint();
    }

    public void setTiles(int count) {
        if (count != 1 && count != 4 && count != 9) throw new IllegalArgumentException("分屏数量须为 1、4 或 9");
        onEdt(() -> { tiles = count; ensureSelectedPage(); viewListener.run(); repaint(); });
    }

    public int tileCount() { return tiles; }
    public int pageIndex() { return page; }
    public int pageCount() { return Math.max(1, (devices.size() + tiles - 1) / tiles); }
    public String selectedId() { return selectedId; }
    public List<String> visibleDeviceIds() {
        return visibleDevices().stream().map(MonitorWindow.DeviceSummary::id).toList();
    }
    public void setViewListener(Runnable listener) { onEdt(() -> viewListener = Objects.requireNonNull(listener)); }
    public void changePage(int delta) {
        onEdt(() -> {
            int target = Math.max(0, Math.min(pageCount() - 1, page + delta));
            if (target == page) return;
            page = target;
            int first = page * tiles;
            if (first < devices.size()) select(devices.get(first).id());
            viewListener.run(); repaint();
        });
    }
    @Override public String getToolTipText(MouseEvent event) {
        MonitorWindow.DeviceSummary device = deviceAt(event.getX(), event.getY());
        if (device == null) return emptyTileAt(event.getX(), event.getY()) ? "添加摄像头" : null;
        DeviceState state = states.getOrDefault(device.id(), new DeviceState("DISCONNECTED", false));
        return channelName(device) + " · " + connectionLabel(state.connection())
                + (state.recording() ? " · 正在录像" : " · 未录像") + "；双击放大或恢复分屏";
    }

    private void bind(String key, int delta) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        getActionMap().put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                int current = indexOf(selectedId);
                int step = Math.abs(delta) == 3 ? (int) Math.sqrt(tiles) * Integer.signum(delta) : delta;
                int next = Math.max(0, Math.min(devices.size() - 1, current + step));
                if (next >= 0 && next < devices.size()) select(devices.get(next).id());
            }
        });
    }
    private void select(String id) {
        selectedId = id; ensureSelectedPage(); repaint();
        onSelect.accept(id); viewListener.run();
    }
    private int indexOf(String id) {
        for (int i = 0; i < devices.size(); i++) if (devices.get(i).id().equals(id)) return i;
        return -1;
    }
    private void ensureSelectedPage() {
        int index = indexOf(selectedId);
        page = index < 0 ? Math.min(page, pageCount() - 1) : index / tiles;
    }
    private List<MonitorWindow.DeviceSummary> visibleDevices() {
        int first = Math.min(devices.size(), page * tiles);
        return new ArrayList<>(devices.subList(first, Math.min(devices.size(), first + tiles)));
    }
    private Rectangle tileBounds(int index) {
        int side = (int) Math.sqrt(tiles);
        int column = index % side, row = index / side;
        int left = column * getWidth() / side, top = row * getHeight() / side;
        return new Rectangle(left + 2, top + 2,
                (column + 1) * getWidth() / side - left - 4,
                (row + 1) * getHeight() / side - top - 4);
    }
    private MonitorWindow.DeviceSummary deviceAt(int x, int y) {
        List<MonitorWindow.DeviceSummary> visible = visibleDevices();
        for (int i = 0; i < visible.size(); i++) if (tileBounds(i).contains(x, y)) return visible.get(i);
        return null;
    }
    private boolean emptyTileAt(int x, int y) {
        int count = visibleDevices().size();
        for (int i = count; i < tiles; i++) if (tileBounds(i).contains(x, y)) return true;
        return false;
    }
    private String channelName(MonitorWindow.DeviceSummary device) {
        return String.format(java.util.Locale.ROOT, "CH%02d  %s", indexOf(device.id()) + 1, device.name());
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(FONT);
        List<MonitorWindow.DeviceSummary> visible = visibleDevices();
        for (int i = 0; i < tiles; i++) paintTile(g, tileBounds(i), i < visible.size() ? visible.get(i) : null);
        g.dispose();
    }

    private void paintTile(Graphics2D canvas, Rectangle tile, MonitorWindow.DeviceSummary device) {
        if (tile.width <= 0 || tile.height <= 0) return;
        Graphics2D g = (Graphics2D) canvas.create(tile.x, tile.y, tile.width, tile.height);
        int width = tile.width, height = tile.height;
        g.setColor(new Color(19, 21, 24)); g.fillRect(0, 0, width, height);
        if (device == null) {
            g.setColor(new Color(112, 120, 130));
            center(g, "+ 添加设备", width, height / 2 + 4);
            g.setColor(new Color(51, 56, 63)); g.drawRect(0, 0, width - 1, height - 1);
            g.dispose(); return;
        }
        DeviceState state = states.getOrDefault(device.id(), new DeviceState("DISCONNECTED", false));
        BufferedImage frame = frames.get(device.id());
        if (frame != null) {
            double scale = Math.min((double) width / frame.getWidth(), (double) height / frame.getHeight());
            int imageWidth = Math.max(1, (int) Math.round(frame.getWidth() * scale));
            int imageHeight = Math.max(1, (int) Math.round(frame.getHeight() * scale));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(frame, (width - imageWidth) / 2, (height - imageHeight) / 2,
                    imageWidth, imageHeight, null);
            if (!"CONNECTED".equals(state.connection())) {
                g.setColor(new Color(0, 0, 0, 130)); g.fillRect(0, 0, width, height);
                g.setColor(TEXT); center(g, "预览暂停 · " + connectionLabel(state.connection()), width, height / 2 + 4);
                if (state.recording() && height >= 90)
                    center(g, "独立录像继续保存", width, height / 2 + 27);
            }
        } else {
            g.setColor(new Color(169, 176, 185)); center(g, connectionLabel(state.connection()), width, height / 2 + 4);
        }
        boolean selected = device.id().equals(selectedId);
        if (height >= 44 && width >= 64) {
            String name = fit(g, channelName(device), Math.max(24, width - 28));
            int nameWidth = g.getFontMetrics().stringWidth(name);
            g.setColor(new Color(0, 0, 0, 112));
            g.fillRect(8, height - 29, nameWidth + 12, 21);
            g.setColor(new Color(244, 246, 248));
            g.drawString(name, 14, height - 14);
        }
        if (state.recording() && height >= 60 && width >= 80) {
            g.setColor(new Color(0, 0, 0, 112)); g.fillRect(width - 61, 8, 53, 21);
            g.setColor(new Color(227, 78, 82)); g.fillOval(width - 52, 15, 6, 6);
            g.setColor(new Color(244, 246, 248)); g.drawString("REC", width - 40, 23);
        }
        g.setColor(selected ? isFocusOwner() ? SELECTED.brighter() : SELECTED : new Color(57, 62, 68));
        g.setStroke(new BasicStroke(selected ? 1.5f : 1f)); g.drawRect(1, 1, width - 3, height - 3);
        g.dispose();
    }
    private static String fit(Graphics2D g, String value, int width) {
        if (g.getFontMetrics().stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && g.getFontMetrics().stringWidth(value.substring(0, end) + "…") > width) end--;
        return value.substring(0, end) + "…";
    }
    private static void center(Graphics2D g, String text, int width, int y) {
        g.drawString(text, Math.max(5, (width - g.getFontMetrics().stringWidth(text)) / 2), y);
    }
    private static String connectionLabel(String state) {
        return switch (state) { case "CONNECTED" -> "在线"; case "CONNECTING" -> "连接中";
            case "RETRYING" -> "重连中"; case "ERROR" -> "连接失败"; default -> "未连接"; };
    }
    private void queueRepaint() {
        if (repaintQueued.compareAndSet(false, true)) onEdt(() -> { repaintQueued.set(false); repaint(); });
    }
    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }
    private record DeviceState(String connection, boolean recording) {}
}
