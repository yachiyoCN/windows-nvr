package cn.homewatch;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Device changes happen inline; persistence and service changes run outside the Swing thread. */
public final class DeviceManagerPanel extends JPanel implements AutoCloseable {
    public interface Listener {
        void add(String name, String url, String transport, boolean autoConnect) throws Exception;
        default void remove(String id) throws Exception { throw new IllegalStateException("移除操作暂不可用"); }
        default void cancel() {}
        default void notice(String message, boolean error) {}
    }
    private static final Color TEXT = new Color(59, 64, 70), MUTED = new Color(112, 120, 132);
    private static final Color RED = new Color(202, 43, 47), GREEN = new Color(38, 128, 77);
    private final Listener listener;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final JTextField name = new JTextField();
    private final JPasswordField url = new JPasswordField();
    private final JTextField host = new JTextField();
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(554, 1, 65535, 1));
    private final JTextField username = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JTextField path = new JTextField("/Streaming/Channels/101");
    private final JCheckBox fullAddress = new JCheckBox("使用完整拉流地址");
    private final JPanel basicAddress = vertical();
    private final JPanel fullAddressFields = vertical();
    private final JButton cancelRemove = NvrUi.button("取消", null, false, this::hideConfirmation);
    private final JComboBox<String> transport = new JComboBox<>(new String[] {"TCP", "UDP"});
    private final JCheckBox autoConnect = new JCheckBox("启动软件后自动连接", true);
    private final JCheckBox showAddress = new JCheckBox("显示地址");
    private final JButton add = NvrUi.button("添加设备", null, true, this::addDevice);
    private final JComboBox<MonitorWindow.DeviceSummary> removeDevice = new JComboBox<>();
    private final JButton remove = NvrUi.button("移除设备", null, false, this::confirmRemoval);
    private final JButton confirmRemove = NvrUi.button("确认移除", null, true, this::removeDevice);
    private final JLabel notice = label(" ");
    private final JLabel deviceCount = label(" ");
    private final JLabel removeNotice = label(" ");
    private final JPanel confirmation = vertical();
    private boolean updatingDevices;
    private List<MonitorWindow.DeviceSummary> devices = List.of();
    private volatile boolean closed;

    public DeviceManagerPanel(Listener listener) {
        this.listener = Objects.requireNonNull(listener);
        setName("devices.manager");
        setLayout(new BorderLayout());
        setBackground(new Color(248, 249, 250));
        JPanel form = new FormContents(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS)); form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        form.add(deviceCount);
        form.add(Box.createVerticalStrut(12));
        JLabel title = label("添加摄像头"); title.setFont(NvrUi.FONT.deriveFont(Font.BOLD)); form.add(title);
        form.add(Box.createVerticalStrut(9));
        form.add(label("设备名称")); form.add(Box.createVerticalStrut(4));
        NvrUi.styleField(name); name.setColumns(1); name.setName("devices.add.name"); form.add(name);
        form.add(Box.createVerticalStrut(10));
        styleInput(host, "devices.add.host");
        styleInput(username, "devices.add.username");
        styleInput(password, "devices.add.password"); password.setEchoChar('●');
        styleInput(path, "devices.add.path");
        NvrUi.styleSpinner(port); port.setName("devices.add.port");
        basicAddress.add(pair("IP 或主机名", host, "端口", port, 3, 1));
        basicAddress.add(Box.createVerticalStrut(7));
        basicAddress.add(pair("用户名", username, "密码／验证码", password, 1, 1));
        basicAddress.add(Box.createVerticalStrut(7));
        basicAddress.add(label("视频路径")); basicAddress.add(Box.createVerticalStrut(4)); basicAddress.add(path);
        path.setToolTipText("海康／荧石常用主码流路径，可按设备实际要求修改");
        form.add(basicAddress);
        fullAddressFields.add(label("完整监控地址（RTSP 或 HTTPS 视频）")); fullAddressFields.add(Box.createVerticalStrut(4));
        NvrUi.styleField(url); url.setColumns(1); url.setEchoChar('●'); url.setName("devices.add.url");
        url.setToolTipText("例如 rtsp://用户名:密码@设备地址:554/视频路径，或 HTTPS 视频地址；账号密码默认隐藏");
        fullAddressFields.add(url); fullAddressFields.setVisible(false); form.add(fullAddressFields);
        styleCheck(fullAddress); fullAddress.setName("devices.add.fullAddress");
        fullAddress.addActionListener(event -> {
            basicAddress.setVisible(!fullAddress.isSelected()); fullAddressFields.setVisible(fullAddress.isSelected());
            revalidate(); repaint();
        }); form.add(fullAddress);
        styleCheck(showAddress); showAddress.setName("devices.add.showAddress");
        showAddress.setText("显示密码／地址");
        showAddress.addActionListener(event -> { url.setEchoChar(showAddress.isSelected() ? (char) 0 : '●');
            password.setEchoChar(showAddress.isSelected() ? (char) 0 : '●'); });
        form.add(showAddress); form.add(Box.createVerticalStrut(7));
        JPanel transportRow = new JPanel(new BorderLayout(8, 0)); transportRow.setOpaque(false);
        transportRow.add(label("传输方式"), BorderLayout.WEST);
        NvrUi.styleCombo(transport); transport.setName("devices.add.transport"); transportRow.add(transport, BorderLayout.CENTER);
        form.add(transportRow); form.add(Box.createVerticalStrut(5));
        styleCheck(autoConnect); autoConnect.setName("devices.add.autoConnect"); form.add(autoConnect);
        form.add(Box.createVerticalStrut(10));
        add.setName("devices.add.save");
        form.add(Box.createVerticalStrut(6));
        notice.setName("devices.add.notice"); form.add(notice);
        form.add(Box.createVerticalStrut(6));
        JLabel hint = label("<html>各摄像头可单独设置画质和连接。<br>录像目录、容量和保留天数共用。</html>");
        hint.setForeground(MUTED); form.add(hint);
        form.add(Box.createVerticalStrut(20));
        JLabel existing = label("已有设备"); existing.setFont(NvrUi.FONT.deriveFont(Font.BOLD)); form.add(existing);
        form.add(Box.createVerticalStrut(7));
        NvrUi.styleCombo(removeDevice); removeDevice.setName("devices.remove.target");
        removeDevice.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                JLabel item = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof MonitorWindow.DeviceSummary summary) {
                    item.setText(summary.name()); item.setToolTipText(summary.name() + " · " + summary.endpoint());
                }
                return item;
            }
        });
        removeDevice.addActionListener(event -> { if (!updatingDevices) hideConfirmation(); });
        form.add(removeDevice); form.add(Box.createVerticalStrut(7));
        remove.setName("devices.remove.open"); form.add(remove);
        form.add(Box.createVerticalStrut(7));
        removeNotice.setForeground(RED); confirmation.add(removeNotice); confirmation.add(Box.createVerticalStrut(6));
        JPanel removalActions = new JPanel(); removalActions.setLayout(new BoxLayout(removalActions, BoxLayout.X_AXIS));
        removalActions.setOpaque(false);
        confirmRemove.setName("devices.remove.confirm"); removalActions.add(confirmRemove);
        removalActions.add(Box.createHorizontalStrut(6));
        cancelRemove.setName("devices.remove.cancel"); removalActions.add(cancelRemove); confirmation.add(removalActions);
        confirmation.setVisible(false); form.add(confirmation);
        for (JPanel section : List.of(basicAddress, fullAddressFields, confirmation)) {
            for (Component component : section.getComponents()) {
                if (component instanceof javax.swing.JComponent item) item.setAlignmentX(LEFT_ALIGNMENT);
            }
        }
        for (Component component : form.getComponents()) if (component instanceof javax.swing.JComponent item) {
            item.setAlignmentX(LEFT_ALIGNMENT);
            if (!(component instanceof JLabel) && component != basicAddress && component != fullAddressFields && component != confirmation)
                item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
        }
        JScrollPane scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
        JButton back = NvrUi.button("返回录像存储", null, false, listener::cancel); back.setName("devices.manager.back");
        JPanel bottom = new JPanel(new BorderLayout(6, 0)); bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12)); bottom.add(add, BorderLayout.WEST);
        bottom.add(back, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH);
        setDevices(List.of(), "");
    }

    public void setDevices(List<MonitorWindow.DeviceSummary> summaries, String selectedId) {
        onEdt(() -> {
            if (closed) return;
            String previous = removeDevice.getSelectedItem() instanceof MonitorWindow.DeviceSummary item ? item.id() : selectedId;
            devices = List.copyOf(summaries);
            updatingDevices = true;
            try {
                removeDevice.removeAllItems();
                MonitorWindow.DeviceSummary chosen = null;
                for (MonitorWindow.DeviceSummary item : devices) {
                    removeDevice.addItem(item);
                    if (item.id().equals(previous)) chosen = item;
                }
                if (chosen != null) removeDevice.setSelectedItem(chosen);
            } finally { updatingDevices = false; }
            hideConfirmation();
            deviceCount.setText("已添加 " + devices.size() + " 台摄像头 / 最多 16 台");
            setEnabledState();
        });
    }
    public boolean isBusy() { return busy.get(); }
    public void showAddForm() { onEdt(() -> name.requestFocusInWindow()); }
    @Override public void close() { closed = true; }

    private void addDevice() {
        if (closed || busy.get()) return;
        String deviceName = name.getText().strip();
        String address;
        if (deviceName.isBlank()) { showNotice("请填写设备名称", true); name.requestFocusInWindow(); return; }
        try {
            if (fullAddress.isSelected()) {
                char[] value = url.getPassword();
                try { address = new String(value).strip(); } finally { Arrays.fill(value, '\0'); }
            } else {
                port.commitEdit();
                String deviceHost = host.getText().strip();
                if (deviceHost.isBlank()) { showNotice("请填写摄像头 IP 或主机名", true); host.requestFocusInWindow(); return; }
                String devicePath = path.getText().strip();
                if (devicePath.isBlank()) devicePath = "/Streaming/Channels/101";
                if (!devicePath.startsWith("/")) devicePath = "/" + devicePath;
                char[] secret = password.getPassword();
                String credentials;
                try {
                    String user = username.getText().strip();
                    credentials = user.isBlank() && secret.length == 0 ? null
                            : encode(user) + ":" + encode(new String(secret));
                } finally { Arrays.fill(secret, '\0'); }
                // The component constructor validates the host and encodes the path independently.
                URI location = new URI("rtsp", null, deviceHost, ((Number) port.getValue()).intValue(), devicePath, null, null);
                address = "rtsp://" + (credentials == null ? "" : credentials + "@") + location.getRawAuthority() + location.getRawPath();
            }
            URI parsed = URI.create(address);
            if (!("rtsp".equalsIgnoreCase(parsed.getScheme()) || "rtsps".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null || parsed.getHost().isBlank()) throw new IllegalArgumentException();
        } catch (Exception invalid) { showNotice("请检查摄像头地址、端口和视频路径", true); return; }
        if (devices.size() >= 16) { showNotice("已达到 16 台设备上限", true); return; }
        String selectedTransport = String.valueOf(transport.getSelectedItem());
        boolean automatic = autoConnect.isSelected();
        runOperation("正在添加设备…", "设备已添加，可开始预览和配置录像", () -> {
            listener.add(deviceName, address, selectedTransport, automatic);
        }, true);
    }
    private void confirmRemoval() {
        if (busy.get() || !(removeDevice.getSelectedItem() instanceof MonitorWindow.DeviceSummary item)) return;
        removeNotice.setText("<html>移除“" + html(item.name()) + "”？<br>会停止该设备连接与录制，保留已有录像。</html>");
        confirmation.setVisible(true); revalidate(); repaint();
        SwingUtilities.invokeLater(() -> {
            if (closed || !confirmation.isVisible()) return;
            confirmRemove.scrollRectToVisible(new java.awt.Rectangle(0, 0, confirmRemove.getWidth(), confirmRemove.getHeight()));
            confirmRemove.requestFocusInWindow();
        });
    }
    private void hideConfirmation() { confirmation.setVisible(false); revalidate(); repaint(); }
    private void removeDevice() {
        if (!(removeDevice.getSelectedItem() instanceof MonitorWindow.DeviceSummary item)) return;
        runOperation("正在移除设备并保存录像…", "设备已移除，已有录像保留在原保存目录", () -> listener.remove(item.id()), false);
    }
    private void runOperation(String pending, String success, Operation operation, boolean clearAddForm) {
        if (closed || !busy.compareAndSet(false, true)) return;
        setEnabledState(); showNotice(pending, false);
        Thread.startVirtualThread(() -> {
            boolean saved; String error = "操作失败，请检查设备信息和程序目录权限";
            try { operation.run(); saved = true; }
            catch (Exception failure) {
                saved = false;
                if (failure instanceof IllegalArgumentException && failure.getMessage() != null && !failure.getMessage().isBlank())
                    error = failure.getMessage().replaceAll("(?i)(?:rtsps?|https?)://[^\\s]+", "[监控地址]").replaceAll("[\\r\\n]+", " ");
            }
            boolean completed = saved;
            String failureNotice = error;
            onEdt(() -> {
                busy.set(false);
                if (closed) return;
                if (completed && clearAddForm) { name.setText(""); url.setText(""); host.setText(""); username.setText(""); password.setText("");
                    showAddress.setSelected(false); url.setEchoChar('●'); password.setEchoChar('●'); }
                if (completed) hideConfirmation();
                setEnabledState();
                showNotice(completed ? success : failureNotice, !completed);
            });
        });
    }
    private void setEnabledState() {
        boolean enabled = !busy.get();
        name.setEnabled(enabled); url.setEnabled(enabled); transport.setEnabled(enabled);
        host.setEnabled(enabled); port.setEnabled(enabled); username.setEnabled(enabled); password.setEnabled(enabled);
        path.setEnabled(enabled); fullAddress.setEnabled(enabled);
        autoConnect.setEnabled(enabled); showAddress.setEnabled(enabled);
        add.setEnabled(enabled && devices.size() < 16);
        removeDevice.setEnabled(enabled && !devices.isEmpty()); remove.setEnabled(enabled && !devices.isEmpty());
        confirmRemove.setEnabled(enabled && !devices.isEmpty());
        cancelRemove.setEnabled(enabled);
    }
    private void showNotice(String message, boolean error) {
        notice.setText("<html><div style='width:225px'>" + html(message) + "</div></html>");
        notice.setForeground(error ? RED : GREEN); listener.notice(message, error); revalidate();
    }
    private static String html(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static JPanel vertical() { JPanel panel = new JPanel() {
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }; panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setOpaque(false); return panel; }
    private static void styleInput(JTextField field, String name) { NvrUi.styleField(field); field.setColumns(1); field.setName(name); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static JPanel pair(String firstLabel, Component first, String secondLabel, Component second, double firstWeight, double secondWeight) {
        JPanel row = new JPanel(new GridBagLayout()); row.setOpaque(false);
        for (int column = 0; column < 2; column++) {
            GridBagConstraints label = new GridBagConstraints(); label.gridx = column; label.gridy = 0;
            label.weightx = column == 0 ? firstWeight : secondWeight; label.fill = GridBagConstraints.HORIZONTAL;
            label.insets = new Insets(0, column == 0 ? 0 : 7, 4, 0);
            row.add(label(column == 0 ? firstLabel : secondLabel), label);
            GridBagConstraints field = new GridBagConstraints(); field.gridx = column; field.gridy = 1;
            field.weightx = label.weightx; field.fill = GridBagConstraints.HORIZONTAL; field.insets = new Insets(0, column == 0 ? 0 : 7, 0, 0);
            row.add(column == 0 ? first : second, field);
        }
        return row;
    }
    private static final class FormContents extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(260, 500); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle rectangle, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle rectangle, int orientation, int direction) { return Math.max(18, rectangle.height - 36); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static JLabel label(String text) { JLabel label = new JLabel(text); label.setFont(NvrUi.FONT); label.setForeground(TEXT); label.setAlignmentX(LEFT_ALIGNMENT); return label; }
    private static void styleCheck(JCheckBox checkbox) { checkbox.setFont(NvrUi.FONT); checkbox.setForeground(TEXT); checkbox.setOpaque(false); checkbox.setAlignmentX(LEFT_ALIGNMENT); }
    private static void onEdt(Runnable action) { if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action); }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
