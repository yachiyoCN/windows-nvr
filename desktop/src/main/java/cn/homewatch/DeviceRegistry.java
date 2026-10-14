package cn.homewatch;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Private channel registry. Legacy channel keeps the original archive without moving any files. */
public final class DeviceRegistry {
    public static final int MAX_DEVICES = 16;
    public record Device(String id, HomeMonitor.CameraConfig config) {
        public Path dataRoot(Path applicationRoot) {
            return id.equals("legacy") ? applicationRoot : applicationRoot.resolve("data/devices").resolve(id);
        }
    }
    private final Path root;
    private final List<Device> devices = new ArrayList<>();
    private String selectedId = "";
    private int layout = 1;

    public DeviceRegistry(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Path file = this.root.resolve("config/devices.properties");
        if (!Files.exists(file)) {
            devices.add(new Device("legacy", HomeMonitor.CameraConfig.load(this.root)));
            selectedId = "legacy";
            return;
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
        for (String id : values.getProperty("order", "").split(",")) {
            if (id.isBlank()) continue;
            if (!(id.equals("legacy") || id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    || devices.size() >= MAX_DEVICES || devices.stream().anyMatch(d -> d.id().equals(id)))
                throw new IOException("设备列表无效，请保留配置文件并检查设备设置。");
            String key = "device." + id + ".";
            devices.add(new Device(id, new HomeMonitor.CameraConfig(values.getProperty(key + "name", "摄像头"),
                    values.getProperty(key + "url", ""), "udp".equalsIgnoreCase(values.getProperty(key + "transport")) ? "udp" : "tcp",
                    Boolean.parseBoolean(values.getProperty(key + "autoConnect", "true")), VideoQuality.parse(values.getProperty(key + "quality")),
                    Boolean.parseBoolean(values.getProperty(key + "recordingTimestamp", "false")))));
        }
        if (devices.isEmpty()) throw new IOException("设备列表为空，请保留配置文件并检查设备设置。");
        selectedId = values.getProperty("selected", "");
        if (devices.stream().noneMatch(d -> d.id().equals(selectedId))) selectedId = devices.isEmpty() ? "" : devices.getFirst().id();
        String storedLayout = values.getProperty("layout", "1");
        layout = storedLayout.equals("4") ? 4 : storedLayout.equals("9") ? 9 : 1;
    }
    public synchronized List<Device> devices() { return List.copyOf(devices); }
    public synchronized String selectedId() { return selectedId; }
    public synchronized int layout() { return layout; }
    public synchronized Device add(HomeMonitor.CameraConfig config) throws IOException {
        validate(config, null);
        if (devices.size() >= MAX_DEVICES) throw new IllegalArgumentException("最多可添加 16 台摄像头。");
        Device device = new Device(UUID.randomUUID().toString(), config);
        devices.add(device);
        String previous = selectedId;
        selectedId = device.id();
        try { save(); } catch (IOException failure) { devices.remove(device); selectedId = previous; throw failure; }
        return device;
    }
    public synchronized void update(String id, HomeMonitor.CameraConfig config) throws IOException {
        validate(config, id);
        int index = indexOf(id);
        Device before = devices.get(index);
        devices.set(index, new Device(id, config));
        try { save(); } catch (IOException failure) { devices.set(index, before); throw failure; }
    }
    public synchronized void remove(String id) throws IOException {
        if (devices.size() <= 1) throw new IllegalArgumentException("请至少保留一台设备。可以先添加新设备，再移除此设备。");
        int index = indexOf(id);
        Device previous = devices.remove(index);
        String oldSelection = selectedId;
        if (id.equals(selectedId)) selectedId = devices.get(Math.min(index, devices.size() - 1)).id();
        try { save(); } catch (IOException failure) { devices.add(index, previous); selectedId = oldSelection; throw failure; }
    }
    public synchronized void select(String id) throws IOException {
        indexOf(id);
        String old = selectedId;
        selectedId = id;
        try { save(); } catch (IOException failure) { selectedId = old; throw failure; }
    }
    public synchronized void setLayout(int tiles) throws IOException {
        int old = layout;
        layout = tiles == 4 || tiles == 9 ? tiles : 1;
        try { save(); } catch (IOException failure) { layout = old; throw failure; }
    }
    private int indexOf(String id) {
        for (int i = 0; i < devices.size(); i++) if (devices.get(i).id().equals(id)) return i;
        throw new IllegalArgumentException("设备已被移除。");
    }
    private void validate(HomeMonitor.CameraConfig config, String replacingId) {
        HomeMonitor.CameraConfig.validateUrl(config.url());
        if (config.name().isBlank() || config.name().length() > 40) throw new IllegalArgumentException("设备名称请填写 1 至 40 个字符。");
        if (devices.stream().anyMatch(d -> !d.id().equals(replacingId) && d.config().url().equals(config.url())))
            throw new IllegalArgumentException("这个播放地址已经添加，请在设备列表中选择已有设备。");
    }
    public synchronized void save() throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("order", String.join(",", devices.stream().map(Device::id).toList()));
        values.setProperty("selected", selectedId);
        values.setProperty("layout", Integer.toString(layout));
        for (Device device : devices) {
            String key = "device." + device.id() + ".";
            values.setProperty(key + "name", device.config().name());
            values.setProperty(key + "url", device.config().url());
            values.setProperty(key + "transport", device.config().transport());
            values.setProperty(key + "autoConnect", Boolean.toString(device.config().autoConnect()));
            values.setProperty(key + "quality", device.config().quality().name());
            values.setProperty(key + "recordingTimestamp", Boolean.toString(device.config().recordingTimestamp()));
        }
        Path file = root.resolve("config/devices.properties");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "devices-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp)) { values.store(writer, "HomeWatch private devices"); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
