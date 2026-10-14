package cn.homewatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Global remote credentials. Only salted password hashes and API key digests reach disk. */
public record RemoteAccessSettings(boolean webEnabled, String webUsername, String webPasswordHash,
                                   boolean apiEnabled, String apiKeyHash) {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    public RemoteAccessSettings {
        webUsername = Objects.requireNonNullElse(webUsername, "").strip();
        webPasswordHash = Objects.requireNonNullElse(webPasswordHash, "");
        apiKeyHash = Objects.requireNonNullElse(apiKeyHash, "");
        if (webUsername.length() > 64 || webUsername.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Web 用户名请使用 1 至 64 个普通字符。");
        if (!webPasswordHash.isBlank() && !validPasswordHash(webPasswordHash))
            throw new IllegalArgumentException("Web 密码配置无效，请重新设置密码。");
        if (!apiKeyHash.isBlank() && !apiKeyHash.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("App API 密钥配置无效，请重新设置密钥。");
        if (webEnabled && (webUsername.isBlank() || webPasswordHash.isBlank()))
            throw new IllegalArgumentException("启用 Web 前请设置用户名和密码。");
        // API can also authenticate recorder-managed users; the service validates enabled users.
    }
    public static RemoteAccessSettings defaults() { return new RemoteAccessSettings(false, "admin", "", false, ""); }
    public boolean webConfigured() { return !webUsername.isBlank() && !webPasswordHash.isBlank(); }
    public boolean apiConfigured() { return !apiKeyHash.isBlank(); }
    public RemoteAccessSettings update(boolean web, String user, String newPassword, boolean api, String newKey) {
        String password = Objects.requireNonNullElse(newPassword, "");
        String key = Objects.requireNonNullElse(newKey, "");
        if (!password.isEmpty() && (password.length() < 8 || password.length() > 256))
            throw new IllegalArgumentException("Web 密码请使用 8 至 256 个字符。");
        if (!key.isEmpty() && (key.length() < 24 || key.length() > 256 || !key.matches("[A-Za-z0-9._~+/-]+={0,2}")))
            throw new IllegalArgumentException("App API 密钥请使用 24 至 256 个字母、数字或 . _ ~ + / -，也可以点击生成。");
        return new RemoteAccessSettings(web, user, password.isEmpty() ? webPasswordHash : hashPassword(password),
                api, key.isEmpty() ? apiKeyHash : digestKey(key));
    }
    public static String generateApiKey() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public boolean verifyPassword(String password) {
        return webConfigured() && verifyPasswordHash(webPasswordHash, password);
    }
    static boolean verifyPasswordHash(String hash, String password) {
        if (hash == null || hash.isBlank() || password == null || password.length() > 256) return false;
        try {
            String[] parts = hash.split(":");
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException failure) { return false; }
    }
    public boolean verifyApiKey(String key) { return key != null && key.length() <= 256 && apiConfigured()
            && MessageDigest.isEqual(apiKeyHash.getBytes(StandardCharsets.US_ASCII), digestKey(key).getBytes(StandardCharsets.US_ASCII)); }
    static String hashPassword(String value) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2:" + ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(value, salt, ITERATIONS));
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("系统不支持密码加密。", failure); }
        finally { spec.clearPassword(); }
    }
    static boolean validPasswordHash(String hash) {
        try { String[] p = hash.split(":"); return p.length == 4 && p[0].equals("pbkdf2") && Integer.parseInt(p[1]) >= 100_000
                && Integer.parseInt(p[1]) <= 1_000_000 && Base64.getDecoder().decode(p[2]).length == 16 && Base64.getDecoder().decode(p[3]).length == 32; }
        catch (RuntimeException invalid) { return false; }
    }
    static String digestKey(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static RemoteAccessSettings load(Path root) throws IOException {
        Path file = root.resolve("config/remote-access.properties");
        if (!Files.exists(file)) return defaults();
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        try { return new RemoteAccessSettings(Boolean.parseBoolean(p.getProperty("webEnabled", "false")), p.getProperty("webUsername", "admin"),
                p.getProperty("webPasswordHash", ""), Boolean.parseBoolean(p.getProperty("apiEnabled", "false")), p.getProperty("apiKeyHash", "")); }
        catch (IllegalArgumentException invalid) { throw new IOException("远程服务配置无效，请在软件中重新设置。", invalid); }
    }
    public void save(Path root) throws IOException {
        Path file = root.resolve("config/remote-access.properties"); Files.createDirectories(file.getParent());
        Properties p = new Properties(); p.setProperty("webEnabled", Boolean.toString(webEnabled)); p.setProperty("webUsername", webUsername);
        p.setProperty("webPasswordHash", webPasswordHash); p.setProperty("apiEnabled", Boolean.toString(apiEnabled)); p.setProperty("apiKeyHash", apiKeyHash);
        Path temporary = Files.createTempFile(file.getParent(), "remote-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { p.store(writer, "Recorder remote access (hashed credentials)"); }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException failure) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public String toString() { return "RemoteAccessSettings[webEnabled=" + webEnabled + ", apiEnabled=" + apiEnabled + "]"; }
}
