package cn.homewatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable recorder-managed viewer accounts; passwords only persist as salted hashes. */
final class RemoteUserStore {
    static final int MAX_USERS = 64;
    record User(String id, String username, String passwordHash, boolean enabled, Set<String> channelIds) {
        User {
            if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("用户编号无效。");
            username = Objects.requireNonNullElse(username, "").strip();
            if (username.isBlank() || username.length() > 64 || username.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("用户名请使用 1 至 64 个普通字符。");
            if (!RemoteAccessSettings.validPasswordHash(passwordHash)) throw new IllegalArgumentException("用户密码配置无效。");
            channelIds = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(channelIds)));
            if (channelIds.size() > 16 || channelIds.stream().anyMatch(idValue -> idValue == null || !idValue.matches("[A-Za-z0-9_-]{1,64}")))
                throw new IllegalArgumentException("用户的摄像头权限配置无效。");
        }
        boolean verify(String password) { boolean valid=RemoteAccessSettings.verifyPasswordHash(passwordHash, password);return enabled&&valid; }
        @Override public String toString() { return "RemoteUser[id=" + id + ", enabled=" + enabled + "]"; }
    }
    private final List<User> users;
    RemoteUserStore(List<User> users) {
        this.users = List.copyOf(users);
        if (users.size() > MAX_USERS) throw new IllegalArgumentException("最多可创建 64 个用户。");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (User user : users) if (!ids.add(user.id()) || !names.add(user.username().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("用户名或用户编号重复。");
    }
    List<User> users() { return users; }
    User find(String id) { return users.stream().filter(user -> user.id().equals(id)).findFirst().orElse(null); }
    User named(String name) { return users.stream().filter(user -> user.username().equals(name)).findFirst().orElse(null); }
    boolean hasEnabledUsers() { return users.stream().anyMatch(User::enabled); }
    RemoteUserStore update(String id, String username, String password, boolean enabled, Set<String> channels) {
        User before = id == null || id.isBlank() ? null : find(id);
        if (id != null && !id.isBlank() && before == null) throw new IllegalArgumentException("该用户已被删除，请刷新列表。");
        String value = Objects.requireNonNullElse(password, "");
        if (before == null && value.isEmpty()) throw new IllegalArgumentException("新用户请设置至少 8 位密码。");
        if (!value.isEmpty() && (value.length() < 8 || value.length() > 256)) throw new IllegalArgumentException("用户密码请使用 8 至 256 个字符。");
        User next = new User(before == null ? UUID.randomUUID().toString() : before.id(), username,
                value.isEmpty() ? before.passwordHash() : RemoteAccessSettings.hashPassword(value), enabled, channels);
        List<User> copy = new ArrayList<>(users);
        if (before == null) copy.add(next); else copy.set(copy.indexOf(before), next);
        return new RemoteUserStore(copy);
    }
    RemoteUserStore remove(String id) {
        if (find(id) == null) throw new IllegalArgumentException("该用户已被删除，请刷新列表。");
        return new RemoteUserStore(users.stream().filter(user -> !user.id().equals(id)).toList());
    }
    static RemoteUserStore load(Path root) throws IOException {
        Path file = root.resolve("config/remote-users.properties");
        if (!Files.exists(file)) return new RemoteUserStore(List.of());
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
        try {
            List<User> list = new ArrayList<>();
            for (String id : values.getProperty("order", "").split(",")) {
                if (id.isBlank()) continue;
                String key = "user." + id + ".";
                Set<String> channels = new LinkedHashSet<>();
                for (String channel : values.getProperty(key + "channels", "").split(",")) if (!channel.isBlank()) channels.add(channel);
                list.add(new User(id, values.getProperty(key + "username", ""), values.getProperty(key + "passwordHash", ""),
                        Boolean.parseBoolean(values.getProperty(key + "enabled", "false")), channels));
            }
            return new RemoteUserStore(list);
        } catch (RuntimeException invalid) { throw new IOException("远程用户配置无效，请在软件中检查用户。", invalid); }
    }
    void save(Path root) throws IOException {
        Path file = root.resolve("config/remote-users.properties"); Files.createDirectories(file.getParent());
        Properties values = new Properties(); values.setProperty("version", "1");
        values.setProperty("order", String.join(",", users.stream().map(User::id).toList()));
        for (User user : users) {
            String key = "user." + user.id() + ".";
            values.setProperty(key + "username", user.username()); values.setProperty(key + "passwordHash", user.passwordHash());
            values.setProperty(key + "enabled", Boolean.toString(user.enabled())); values.setProperty(key + "channels", String.join(",", user.channelIds()));
        }
        Path temporary = Files.createTempFile(file.getParent(), "users-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "Recorder viewer accounts (hashed passwords)"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
