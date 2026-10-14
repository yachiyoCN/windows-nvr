package cn.homewatch;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/** All channel sidebars edit one recorder-wide draft; credentials are never read back from disk. */
public final class RemoteAccessPanel extends JPanel implements AutoCloseable {
    private static final Color TEXT = new Color(60, 64, 69);
    private static final Color MUTED = new Color(119, 126, 135);
    private static final Color RED = new Color(202, 43, 47);
    private static final Color GREEN = new Color(42, 137, 83);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    // Constructed and observed only on Swing's event thread.
    private static final WeakHashMap<RemoteAccessService, Draft> DRAFTS = new WeakHashMap<>();
    private final RemoteAccessService service;
    private final Draft draft;
    private final BiConsumer<String, Boolean> notice;
    private final JCheckBox webEnabled = checkBox("Web 在线查看 · 8079");
    private final JTextField username = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JCheckBox showPassword = checkBox("显示新密码");
    private final JLabel passwordHint = label("设置密码后可开启 Web");
    private final JLabel webState = label("Web 未开启");
    private final JComboBox<String> webAddress = new JComboBox<>();
    private final JButton openWeb = button("打开", false);
    private final JCheckBox apiEnabled = checkBox("Android / API 服务 · 9079");
    private final JPasswordField apiKey = new JPasswordField();
    private final JCheckBox showKey = checkBox("显示新密钥");
    private final JButton generateKey = button("生成密钥", false);
    private final JButton copyKey = button("复制", false);
    private final JLabel keyHint = label("生成密钥并保存后可开启 API");
    private final JLabel apiState = label("API 未开启");
    private final JComboBox<String> apiAddress = new JComboBox<>();
    private final JButton copyAddress = button("复制地址", false);
    private final JComboBox<String> pairingAddress = new JComboBox<>();
    private final JLabel securityState = label("先开启 Web 或 App API");
    private final JTextArea fingerprint = new JTextArea(3, 1);
    private final JButton copyFingerprint = button("复制安全指纹", false);
    private final JButton exportPairing = button("导出手机连接文件", true);
    private final JButton exportCertificate = button("导出网页证书", false);
    private final JButton loginRecords = button("登录记录", false);
    private final JButton save = button("保存远程设置", true);
    private final JLabel operationNotice = label(" ");
    private final JTabbedPane tabs = new JTabbedPane();
    private RemoteUsersPanel usersPanel;
    private Consumer<Boolean> dirtyListener = ignored -> {};
    private boolean loading;
    private boolean syncingAddresses;
    private boolean exportBusy;
    private boolean auditBusy;
    private boolean closed;

    public RemoteAccessPanel(RemoteAccessService service, BiConsumer<String, Boolean> notice) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("远程设置须在界面线程创建");
        this.service = Objects.requireNonNull(service);
        this.notice = Objects.requireNonNull(notice);
        draft = DRAFTS.computeIfAbsent(service, key -> new Draft(key.settings()));
        draft.views.add(this);
        buildUi();
        applyDraft();
        refresh();
        // Network-interface lookup may consult drivers; keep it outside Swing's thread.
        if (draft.addresses == null && !draft.addressLookup) {
            draft.addressLookup = true;
            Thread.startVirtualThread(() -> {
                List<String> addresses = localAddresses();
                SwingUtilities.invokeLater(() -> {
                    draft.addresses = addresses;
                    draft.addressLookup = false;
                    for (RemoteAccessPanel view : List.copyOf(draft.views)) view.refresh();
                });
            });
        }
    }

    public void setDirtyListener(Consumer<Boolean> listener) {
        dirtyListener = Objects.requireNonNull(listener);
        dirtyListener.accept(draft.dirty);
    }

    public JComponent firstControl() { return tabs.getSelectedIndex() == 1 ? apiEnabled : tabs.getSelectedIndex() == 2 ? usersPanel : tabs.getSelectedIndex() == 3 ? pairingAddress : webEnabled; }

    public void refresh() {
        if (closed) return;
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        if (usersPanel != null) usersPanel.refresh();
        RemoteAccessService.State state = service.state();
        boolean webRunning = state.webRunning();
        boolean apiRunning = state.apiRunning();
        webState.setText(webRunning ? "Web 运行中" : service.settings().webEnabled() ? "Web 未启动" : "Web 未开启");
        webState.setForeground(webRunning ? GREEN : service.settings().webEnabled() ? RED : MUTED);
        apiState.setText(apiRunning ? "API 运行中" : service.settings().apiEnabled() ? "API 未启动" : "API 未开启");
        apiState.setForeground(apiRunning ? GREEN : service.settings().apiEnabled() ? RED : MUTED);
        syncingAddresses = true;
        try {
            syncAddresses(webAddress, 8079);
            syncAddresses(apiAddress, 9079);
            syncAddresses(pairingAddress, 9079);
            pairingAddress.setSelectedItem(apiAddress.getSelectedItem());
        } finally { syncingAddresses = false; }
        webAddress.setEnabled(webRunning);
        apiAddress.setEnabled(apiRunning);
        copyAddress.setEnabled(apiRunning);
        openWeb.setEnabled(webRunning && !draft.busy);
        openWeb.setToolTipText(webRunning ? "在浏览器打开选中的访问地址" : "保存并开启 Web 后可打开");
        refreshSecurity();
        if (!draft.dirty && !draft.busy && !draft.error) {
            String message = Objects.requireNonNullElse(state.message(), "");
            showNotice(message.isBlank() ? "设置已保存" : message,
                    service.settings().webEnabled() && !webRunning || service.settings().apiEnabled() && !apiRunning);
        }
    }

    @Override public void close() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::close); return; }
        if (closed) return;
        closed = true;
        draft.views.remove(this);
        if (draft.views.isEmpty()) {
            Arrays.fill(draft.password, (char) 0); Arrays.fill(draft.apiKey, (char) 0);
            DRAFTS.remove(service);
        }
    }

    private void buildUi() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        JLabel hint = label("<html>本机和局域网使用 HTTPS 安全连接</html>");
        hint.setForeground(MUTED);
        add(hint); gap(5);
        tabs.setName("remote.tabs");
        tabs.setFont(FONT);
        tabs.setForeground(TEXT);
        tabs.setBackground(new Color(248, 249, 250));
        tabs.addTab("Web 查看", webControls());
        tabs.addTab("App API", apiControls());
        usersPanel = new RemoteUsersPanel(service, (message, error) -> { showNotice(message, error); notice.accept(message, error); });
        tabs.addTab("用户", usersPanel);
        tabs.addTab("加密连接", securityControls());
        add(tabs); gap(6);
        JPanel actions = row(); actions.add(save); add(actions); gap(5);
        add(operationNotice);
        named(webEnabled, "remote.webEnabled"); named(apiEnabled, "remote.apiEnabled");
        named(showPassword, "remote.showPassword"); named(showKey, "remote.showKey");
        named(generateKey, "remote.generateKey"); named(copyKey, "remote.copyKey");
        named(openWeb, "remote.openWeb"); named(save, "remote.save");
        named(copyAddress, "remote.copyAddress");
        named(operationNotice, "remote.notice");
        webEnabled.addActionListener(event -> captureDraft());
        apiEnabled.addActionListener(event -> captureDraft());
        watch(username); watch(password); watch(apiKey);
        showPassword.addActionListener(event -> password.setEchoChar(showPassword.isSelected() ? (char) 0 : '●'));
        showKey.addActionListener(event -> apiKey.setEchoChar(showKey.isSelected() ? (char) 0 : '●'));
        generateKey.addActionListener(event -> {
            apiKey.setText(RemoteAccessSettings.generateApiKey());
            apiKey.setCaretPosition(0);
            draft.message = "保存后密钥不能读回，请先复制";
            draft.error = false; draft.publish();
        });
        copyKey.addActionListener(event -> copyDraftKey());
        openWeb.addActionListener(event -> openWeb());
        copyFingerprint.addActionListener(event -> {
            String value = service.connectionFingerprint();
            if (!value.matches("[0-9A-Fa-f]{64}")) return;
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null); showNotice("安全指纹已复制", false); }
            catch (RuntimeException unavailable) { showNotice("无法复制，可选中指纹手动复制", true); }
        });
        apiAddress.addActionListener(event -> syncPairingSelection(apiAddress, pairingAddress));
        pairingAddress.addActionListener(event -> syncPairingSelection(pairingAddress, apiAddress));
        exportPairing.addActionListener(event -> exportPublicConnection(true));
        exportCertificate.addActionListener(event -> exportPublicConnection(false));
        loginRecords.addActionListener(event -> showLoginRecords());
        copyAddress.addActionListener(event -> {
            Object selected = apiAddress.getSelectedItem();
            if (selected == null) return;
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(fullUrl(selected.toString())), null);
                showNotice("API 地址已复制", false);
            } catch (RuntimeException unavailable) { showNotice("复制失败，请手动输入 API 地址", true); }
        });
        save.addActionListener(event -> save());
        tabs.addChangeListener(event -> {
            if (loading || closed) return;
            draft.tabIndex = tabs.getSelectedIndex(); draft.publish();
        });
        alignChildren(this);
    }

    private JPanel webControls() {
        JPanel panel = controls();
        webEnabled.setFont(FONT.deriveFont(Font.BOLD));
        panel.add(webEnabled); gap(panel, 4);
        styleField(username, "remote.webUsername");
        styleField(password, "remote.webPassword");
        password.setEchoChar('●');
        password.setToolTipText("新密码至少 8 位；已设置后留空保持原密码");
        username.setToolTipText("Web 登录用户名，最多 64 个字符");
        JPanel webForm = form();
        formRow(webForm, 0, "用户名", username);
        formRow(webForm, 1, "新密码", password);
        panel.add(webForm);
        panel.add(showPassword); gap(panel, 2);
        passwordHint.setForeground(MUTED); panel.add(passwordHint); gap(panel, 4);
        panel.add(webState); gap(panel, 3);
        NvrUi.styleCombo(webAddress);
        webAddress.setName("remote.webAddress");
        webAddress.setToolTipText("HTTPS 本机或局域网访问地址；首次使用请在“加密连接”导出网页证书");
        JPanel webOpen = new JPanel(new BorderLayout(5, 0));
        webOpen.setOpaque(false);
        webOpen.add(webAddress, BorderLayout.CENTER); webOpen.add(openWeb, BorderLayout.EAST);
        panel.add(webOpen);
        alignChildren(panel);
        return panel;
    }

    private JPanel apiControls() {
        JPanel panel = controls();
        apiEnabled.setFont(FONT.deriveFont(Font.BOLD));
        panel.add(apiEnabled); gap(panel, 4);
        styleField(apiKey, "remote.apiKey");
        apiKey.setEchoChar('●');
        apiKey.setToolTipText("可选管理员密钥：全通道权限；App 用户用各自用户名和密码登录，留空保持已保存密钥");
        JPanel apiForm = form(); formRow(apiForm, 0, "管理密钥", apiKey); panel.add(apiForm);
        JPanel keyActions = row(); keyActions.add(generateKey); keyActions.add(Box.createHorizontalStrut(6));
        keyActions.add(copyKey); panel.add(keyActions); gap(panel, 3);
        panel.add(showKey); gap(panel, 2);
        keyHint.setForeground(MUTED); panel.add(keyHint); gap(panel, 4);
        panel.add(apiState); gap(panel, 3);
        NvrUi.styleCombo(apiAddress); apiAddress.setName("remote.apiAddress");
        apiAddress.setToolTipText("HTTPS 手机地址，端口 9079；选择电脑的局域网地址，在“加密连接”导出手机连接文件");
        JPanel apiLink = new JPanel(new BorderLayout(5, 0));
        apiLink.setOpaque(false);
        apiLink.add(apiAddress, BorderLayout.CENTER); apiLink.add(copyAddress, BorderLayout.EAST);
        panel.add(apiLink);
        alignChildren(panel);
        return panel;
    }

    private JScrollPane securityControls() {
        JPanel panel = new WidthTrackingPanel();
        panel.setOpaque(false); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7));
        securityState.setForeground(MUTED); named(securityState, "remote.securityState");
        panel.add(securityState); gap(panel, 8);
        panel.add(label("手机连接地址 · 9079")); gap(panel, 3);
        NvrUi.styleCombo(pairingAddress); named(pairingAddress, "remote.pairingAddress");
        pairingAddress.setToolTipText("选择手机同一 Wi-Fi 下的电脑地址；不要选 127.0.0.1");
        panel.add(pairingAddress); gap(panel, 5);
        named(exportPairing, "remote.exportPairing"); panel.add(exportPairing); gap(panel, 6);
        panel.add(help("手机首次导入连接文件。<br>核对指纹后再登录。<br>文件不含账号、密码或凭据。")); gap(panel, 10);
        panel.add(label("安全指纹")); gap(panel, 3);
        fingerprint.setEditable(false); fingerprint.setLineWrap(true); fingerprint.setWrapStyleWord(false);
        fingerprint.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        fingerprint.setForeground(TEXT); fingerprint.setBackground(new Color(248, 249, 250));
        fingerprint.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(216, 218, 221)), BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        named(fingerprint, "remote.fingerprint"); panel.add(fingerprint); gap(panel, 4);
        named(copyFingerprint, "remote.copyFingerprint"); panel.add(copyFingerprint); gap(panel, 10);
        named(exportCertificate, "remote.exportCertificate"); panel.add(exportCertificate); gap(panel, 5);
        panel.add(help("网页首次导出并信任本机证书。<br>仅含公钥，私钥保存在本机。")); gap(panel, 9);
        panel.add(help("保持登录：30 天。<br>手机自动续期，电脑重启仍可用。<br>退出或权限变更后需重新登录。")); gap(panel, 10);
        named(loginRecords, "remote.loginRecords"); panel.add(loginRecords); gap(panel, 4);
        panel.add(help("查看最近的登录、退出和权限变更。<br>记录不含密码或登录凭据。"));
        alignChildren(panel);
        JScrollPane scroll = new JScrollPane(panel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false); scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(235, 280));
        scroll.setMinimumSize(new Dimension(0, 120));
        named(scroll, "remote.securityScroll");
        return scroll;
    }

    private static JLabel help(String html) {
        JLabel result = label("<html>" + html + "</html>"); result.setForeground(MUTED);
        result.setFont(FONT.deriveFont(11f)); return result;
    }

    private void showLoginRecords() {
        if (closed || auditBusy) return;
        auditBusy = true; loginRecords.setEnabled(false); loginRecords.setText("正在读取…");
        Thread.startVirtualThread(() -> {
            String value = "", error = null;
            try { value = formatLoginRecords(service.recentLoginRecords()); }
            catch (Exception failure) { error = "无法读取登录记录，请检查安全数据目录权限"; }
            String content = value, result = error;
            SwingUtilities.invokeLater(() -> {
                auditBusy = false; if (closed) return; loginRecords.setEnabled(true); loginRecords.setText("登录记录");
                if (result != null) { showNotice(result, true); return; }
                JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "登录记录", Dialog.ModalityType.APPLICATION_MODAL);
                named(dialog.getRootPane(), "remote.loginRecords.dialog");
                JPanel body = new JPanel(new BorderLayout(8, 8));
                body.setBackground(new Color(248, 249, 250)); body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
                body.add(label("最近 100 条 · 时间使用本机时区"), BorderLayout.NORTH);
                JTextArea text = new JTextArea(content); text.setEditable(false); text.setFont(FONT);
                text.setForeground(TEXT); text.setLineWrap(true); text.setWrapStyleWord(false); text.setCaretPosition(0);
                named(text, "remote.loginRecords.text"); body.add(new JScrollPane(text), BorderLayout.CENTER);
                JButton closeButton = button("关闭", false); closeButton.addActionListener(event -> dialog.dispose());
                JPanel actions = new JPanel(new BorderLayout()); actions.setOpaque(false);
                actions.add(label("不记录密码、令牌或摄像头地址"), BorderLayout.CENTER); actions.add(closeButton, BorderLayout.EAST);
                body.add(actions, BorderLayout.SOUTH);
                dialog.setContentPane(body); dialog.setSize(570, 440); dialog.setMinimumSize(new Dimension(380, 280));
                dialog.setLocationRelativeTo(this); dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE); dialog.setVisible(true);
            });
        });
    }

    static String formatLoginRecords(List<String> lines) {
        if (lines.isEmpty()) return "暂无登录记录";
        Map<String, String> events = Map.ofEntries(
                Map.entry("web-login", "网页登录"), Map.entry("web-login-failed", "网页登录失败"), Map.entry("web-logout", "网页退出"),
                Map.entry("app-login", "手机登录"), Map.entry("app-login-failed", "手机登录失败"), Map.entry("app-logout", "手机退出"),
                Map.entry("app-refresh", "手机登录续期"), Map.entry("app-refresh-replay", "拒绝重复使用旧凭据"),
                Map.entry("app-refresh-rejected", "手机续期失败"), Map.entry("user-created", "创建用户"),
                Map.entry("user-updated", "修改用户或权限"), Map.entry("user-deleted", "删除用户"));
        StringBuilder result = new StringBuilder();
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        // Most recent first, with text only; control characters cannot create forged rows.
        for (int index = lines.size() - 1; index >= Math.max(0, lines.size() - 100); index--) {
            try {
                Map<String, Object> record = RemoteJson.object(lines.get(index));
                String stamp = time.format(Instant.parse(String.valueOf(record.get("time"))));
                String event = events.getOrDefault(String.valueOf(record.get("event")), "其他登录操作");
                result.append(stamp).append("  ").append(event).append('\n')
                        .append("账号：").append(auditText(record.get("username"))).append("    来源：").append(auditText(record.get("client"))).append("\n\n");
            } catch (RuntimeException invalid) { result.append("有一条记录无法读取\n\n"); }
        }
        return result.toString();
    }

    private static String auditText(Object value) {
        String text = Objects.toString(value, "").replaceAll("[\\p{Cntrl}]", " ").strip();
        return text.isEmpty() ? "—" : text.length() > 128 ? text.substring(0, 128) + "…" : text;
    }

    private void syncPairingSelection(JComboBox<String> source, JComboBox<String> target) {
        if (syncingAddresses || closed) return;
        syncingAddresses = true;
        try { target.setSelectedItem(source.getSelectedItem()); }
        finally { syncingAddresses = false; }
        refreshSecurity();
    }

    private void refreshSecurity() {
        String pin = service.connectionFingerprint();
        boolean ready = pin.matches("[0-9A-Fa-f]{64}");
        fingerprint.setText(ready ? pin : "开启并保存远程服务后生成");
        fingerprint.setCaretPosition(0);
        boolean apiRunning = service.state().apiRunning();
        pairingAddress.setEnabled(apiRunning && !exportBusy);
        copyFingerprint.setEnabled(ready && !exportBusy);
        exportCertificate.setEnabled(ready && service.browserCertificate() != null && !exportBusy);
        Object selected = pairingAddress.getSelectedItem();
        boolean lan = selected != null && !isLoopback(selected.toString());
        exportPairing.setEnabled(ready && apiRunning && lan && !exportBusy);
        exportPairing.setToolTipText(!apiRunning ? "先在 App API 页开启并保存" : !lan ? "请选择局域网地址；127.0.0.1 不能用于手机" : "导出地址和安全指纹，不含账号或密码");
        securityState.setText(ready ? "<html>HTTPS 已准备<br>首次连接只需配对一次</html>" : "<html>先开启 Web 或 App API 并保存</html>");
    }

    private void exportPublicConnection(boolean phone) {
        if (closed || exportBusy) return;
        Path certificate = service.browserCertificate();
        String pin = service.connectionFingerprint();
        Object selected = pairingAddress.getSelectedItem();
        if (certificate == null || !pin.matches("[0-9A-Fa-f]{64}") || phone && (selected == null || isLoopback(selected.toString()) || !service.state().apiRunning())) return;
        String address = selected == null ? "" : fullUrl(selected.toString());
        Path defaultDirectory = certificate.getParent().getParent().resolve("export").toAbsolutePath().normalize();
        exportBusy = true; refreshSecurity(); showNotice("正在准备导出…", false);
        Thread.startVirtualThread(() -> {
            String error = null;
            try { Files.createDirectories(defaultDirectory); } catch (Exception failure) { error = "无法准备导出目录，请检查目录权限"; }
            String result = error;
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                if (result != null) { exportBusy = false; refreshSecurity(); showNotice(result, true); return; }
                choosePublicExport(phone, certificate, address, pin, defaultDirectory);
            });
        });
    }

    private void choosePublicExport(boolean phone, Path certificate, String address, String pin, Path defaultDirectory) {
        String extension = phone ? "json" : "cer";
        JFileChooser chooser = new JFileChooser(defaultDirectory.toFile());
        chooser.setDialogTitle(phone ? "导出手机连接文件" : "导出网页公钥证书");
        chooser.setFileFilter(new FileNameExtensionFilter(phone ? "手机连接文件 (*.json)" : "网页公钥证书 (*.cer)", extension));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(defaultDirectory.resolve(phone ? "录像机手机连接.json" : "录像机网页证书.cer").toFile());
        chooser.setName("remote.exportDialog");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { exportBusy = false; refreshSecurity(); showNotice("已取消导出", false); return; }
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)) target = target.resolveSibling(target.getFileName() + "." + extension);
        if (target.startsWith(certificate.getParent().toAbsolutePath().normalize())) { exportBusy = false; refreshSecurity(); showNotice("请选择安全数据目录以外的导出位置", true); return; }
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "该文件已存在，是否替换？", "确认导出", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            exportBusy = false; refreshSecurity(); showNotice("已取消导出", false); return;
        }
        Path destination = target;
        Thread.startVirtualThread(() -> {
            String error = null;
            try {
                if (phone) Files.writeString(destination, pairingJson(address, pin), StandardCharsets.UTF_8);
                else Files.copy(certificate, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) { error = "导出失败，请检查文件位置和目录权限"; }
            String result = error;
            SwingUtilities.invokeLater(() -> {
                exportBusy = false; if (closed) return; refreshSecurity();
                showNotice(result == null ? "已导出：" + destination.getFileName() : result, result != null);
            });
        });
    }

    static String pairingJson(String address, String pin) {
        URI uri = URI.create(address);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() != 9079 || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getPath().isEmpty() || !pin.matches("[0-9A-Fa-f]{64}")) throw new IllegalArgumentException("连接信息无效");
        return RemoteJson.encode(Map.of("version", 1, "apiUrl", address, "publicKeySha256", pin, "name", "录像机")) + "\n";
    }

    private static boolean isLoopback(String address) {
        try { return InetAddress.getByName(URI.create(fullUrl(address)).getHost()).isLoopbackAddress(); }
        catch (Exception invalid) { return true; }
    }

    private static JPanel controls() {
        JPanel panel = new JPanel(); panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7));
        return panel;
    }

    private static void alignChildren(JPanel panel) {
        for (Component child : panel.getComponents()) {
            if (child instanceof JComponent c) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (!(child instanceof JLabel)) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
            }
        }
    }

    private void captureDraft() {
        if (loading || closed || draft.busy) return;
        draft.webEnabled = webEnabled.isSelected();
        draft.username = username.getText();
        Arrays.fill(draft.password, (char) 0);
        draft.password = password.getPassword();
        draft.apiEnabled = apiEnabled.isSelected();
        Arrays.fill(draft.apiKey, (char) 0);
        draft.apiKey = apiKey.getPassword();
        RemoteAccessSettings saved = service.settings();
        draft.dirty = draft.webEnabled != saved.webEnabled() || !draft.username.equals(saved.webUsername())
                || draft.password.length > 0 || draft.apiEnabled != saved.apiEnabled() || draft.apiKey.length > 0;
        draft.message = draft.dirty ? "修改未保存，保存后生效" : "设置已保存";
        draft.error = false;
        draft.publish();
    }

    private void applyDraft() {
        if (closed) return;
        loading = true;
        try {
            webEnabled.setSelected(draft.webEnabled); apiEnabled.setSelected(draft.apiEnabled);
            tabs.setSelectedIndex(draft.tabIndex);
            if (!username.getText().equals(draft.username)) username.setText(draft.username);
            replaceSecret(password, draft.password); replaceSecret(apiKey, draft.apiKey);
            RemoteAccessSettings saved = service.settings();
            passwordHint.setText(saved.webConfigured() ? "密码已设置，留空保持原密码" : "首次启用请设置至少 8 位密码");
            keyHint.setText(saved.apiConfigured() ? "管理员密钥已保存，留空保持" : "App 用户登录无需管理员密钥");
            save.setEnabled(draft.dirty && !draft.busy);
            save.setToolTipText(draft.dirty ? "保存设置并更新两个服务；新密钥请先复制" : "远程设置已保存");
            for (JComponent c : new JComponent[] {webEnabled, username, password, showPassword, apiEnabled, apiKey, showKey, generateKey})
                c.setEnabled(!draft.busy);
            copyKey.setEnabled(!draft.busy && draft.apiKey.length > 0);
            copyKey.setToolTipText(draft.apiKey.length > 0 ? "复制当前输入的新密钥" : "生成或输入新密钥后可复制；已保存密钥无法读回");
            dirtyListener.accept(draft.dirty);
            showNotice(draft.message, draft.error || draft.dirty && !draft.busy);
        } finally { loading = false; }
        refresh(); revalidate(); repaint();
    }

    private void save() {
        if (closed || draft.busy || !draft.dirty) return;
        boolean nextWebEnabled = draft.webEnabled, nextApiEnabled = draft.apiEnabled;
        String nextUsername = draft.username.strip();
        char[] nextPassword = draft.password.clone(), nextApiKey = draft.apiKey.clone();
        draft.busy = true; draft.error = false;
        draft.message = "正在保存并更新服务…"; draft.publish();
        Thread.startVirtualThread(() -> {
            String error = null;
            try {
                RemoteAccessSettings next = service.settings().update(nextWebEnabled, nextUsername,
                        new String(nextPassword), nextApiEnabled, new String(nextApiKey));
                service.apply(next);
            }
            catch (Exception failure) { error = safeMessage(failure, "保存失败，请检查端口是否被占用"); }
            finally { Arrays.fill(nextPassword, (char) 0); Arrays.fill(nextApiKey, (char) 0); }
            String result = error;
            SwingUtilities.invokeLater(() -> {
                draft.busy = false;
                if (result == null) {
                    draft.reset(service.settings());
                    draft.message = "远程设置已保存并生效";
                } else { draft.message = result; draft.error = true; }
                draft.publish();
                if (!closed) notice.accept(draft.message, draft.error);
            });
        });
    }

    private void copyDraftKey() {
        char[] value = apiKey.getPassword();
        if (value.length == 0) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(new String(value)), null);
            showNotice("新密钥已复制，保存远程设置后生效", false);
        } catch (RuntimeException unavailable) { showNotice("复制失败，可显示密钥后手动复制", true); }
        finally { Arrays.fill(value, (char) 0); }
    }

    private void openWeb() {
        Object selected = webAddress.getSelectedItem();
        if (selected == null || GraphicsEnvironment.isHeadless()) return;
        Thread.startVirtualThread(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    throw new IllegalStateException("请复制访问地址到浏览器打开");
                Desktop.getDesktop().browse(URI.create(fullUrl(selected.toString())));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> showNotice("请复制访问地址到浏览器打开", true));
            }
        });
    }

    private void syncAddresses(JComboBox<String> target, int port) {
        List<String> hosts = draft.addresses == null ? List.of("127.0.0.1") : draft.addresses;
        if (target != webAddress) hosts = hosts.stream().sorted(java.util.Comparator.comparingInt(host -> host.startsWith("127.") ? 2 : siteLocal(host) ? 0 : 1)).toList();
        List<String> addresses = hosts.stream().map(host -> host + ":" + port).toList();
        boolean same = target.getItemCount() == addresses.size();
        for (int i = 0; same && i < addresses.size(); i++) same = addresses.get(i).equals(target.getItemAt(i));
        if (same) return;
        Object selected = target.getSelectedItem();
        boolean onlyLoopback = target.getItemCount() == 1 && target.getItemAt(0).startsWith("127.");
        target.removeAllItems(); addresses.forEach(target::addItem);
        if (selected != null && addresses.contains(selected.toString()) && (target == webAddress || !onlyLoopback)) target.setSelectedItem(selected);
    }

    private static boolean siteLocal(String host) {
        try { return InetAddress.getByName(host).isSiteLocalAddress(); } catch (Exception invalid) { return false; }
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static List<String> localAddresses() {
        List<String> result = new ArrayList<>(); result.add("127.0.0.1");
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                        if (!result.contains(address.getHostAddress())) result.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { /* The loopback URL remains usable. */ }
        return List.copyOf(result);
    }

    private void showNotice(String text, boolean error) {
        String safe = Objects.requireNonNullElse(text, "");
        operationNotice.setText("<html>" + wrapNotice(safe) + "</html>");
        operationNotice.setForeground(error ? RED : MUTED);
        operationNotice.setToolTipText(safe);
    }

    private void watch(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { captureDraft(); }
            @Override public void removeUpdate(DocumentEvent e) { captureDraft(); }
            @Override public void changedUpdate(DocumentEvent e) { captureDraft(); }
        });
    }

    private void gap(int height) { add(Box.createVerticalStrut(height)); }
    private static void gap(JPanel panel, int height) { panel.add(Box.createVerticalStrut(height)); }
    private static JPanel form() { JPanel p = new JPanel(new GridBagLayout()); p.setOpaque(false); return p; }
    private static JPanel row() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS)); p.setOpaque(false); return p; }
    private static void formRow(JPanel panel, int row, String title, Component field) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0; label.gridy = row; label.anchor = GridBagConstraints.WEST; label.insets = new Insets(0, 0, 4, 8);
        JLabel caption = label(title); caption.setPreferredSize(new Dimension(48, 30)); panel.add(caption, label);
        GridBagConstraints control = new GridBagConstraints();
        control.gridx = 1; control.gridy = row; control.weightx = 1; control.fill = GridBagConstraints.HORIZONTAL;
        control.insets = new Insets(0, 0, 4, 0); panel.add(field, control);
    }
    private static JLabel label(String text) {
        JLabel result = new JLabel(text); result.setFont(FONT); result.setForeground(TEXT); return result;
    }
    private static JCheckBox checkBox(String text) {
        JCheckBox result = new JCheckBox(text); result.setOpaque(false); result.setFont(FONT);
        result.setForeground(TEXT); result.setBorder(BorderFactory.createEmptyBorder()); return result;
    }
    private static JButton button(String text, boolean primary) { return NvrUi.button(text, null, primary, null); }
    private static void styleField(JTextField field, String name) {
        NvrUi.styleField(field); field.setColumns(1); field.setPreferredSize(new Dimension(180, 32)); named(field, name);
    }
    private static void named(JComponent c, String name) { c.setName(name); c.getAccessibleContext().setAccessibleName(name); }
    private static void replaceSecret(JPasswordField field, char[] desired) {
        char[] current = field.getPassword();
        try { if (!Arrays.equals(current, desired)) field.setText(new String(desired)); }
        finally { Arrays.fill(current, (char) 0); }
    }
    private static String safeMessage(Exception e, String fallback) {
        String value = e.getMessage(); return value == null || value.isBlank() ? fallback : value.replaceAll("[\\r\\n]+", " ");
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String fullUrl(String address) { return address.contains("://") ? address : "https://" + address; }
    private static String wrapNotice(String text) {
        StringBuilder html = new StringBuilder();
        int units = 0;
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i); i += Character.charCount(point);
            int width = point > 255 ? 2 : 1;
            if (units + width > 34) { html.append("<br>"); units = 0; }
            html.append(escape(new String(Character.toChars(point)))); units += width;
        }
        return html.toString();
    }

    private static final class Draft {
        final List<RemoteAccessPanel> views = new ArrayList<>();
        boolean webEnabled, apiEnabled, dirty, busy, error, addressLookup;
        int tabIndex;
        String username, message = "设置已保存";
        char[] password = new char[0], apiKey = new char[0];
        List<String> addresses;
        Draft(RemoteAccessSettings settings) { reset(settings); }
        void reset(RemoteAccessSettings settings) {
            Arrays.fill(password, (char) 0); Arrays.fill(apiKey, (char) 0);
            password = new char[0]; apiKey = new char[0];
            webEnabled = settings.webEnabled(); username = settings.webUsername(); apiEnabled = settings.apiEnabled();
            dirty = false; error = false;
        }
        void publish() { for (RemoteAccessPanel view : List.copyOf(views)) view.applyDraft(); }
    }
}
