package cn.homewatch;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Recorder-local secrets: private OS permissions, authenticated encryption and atomic writes. */
final class RemotePrivateStorage {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final byte[] key;

    RemotePrivateStorage(Path root) throws IOException {
        directory = root.resolve("data/remote-security");
        Files.createDirectories(directory);
        protect(directory);
        Path keyPath = directory.resolve("storage.key");
        if (!Files.exists(keyPath)) {
            byte[] generated = new byte[32]; RANDOM.nextBytes(generated);
            try { Files.write(keyPath, generated, StandardOpenOption.CREATE_NEW); }
            finally { Arrays.fill(generated, (byte) 0); }
        }
        protect(keyPath);
        key = Files.readAllBytes(keyPath);
        if (key.length != 32) throw new IOException("远程安全密钥无效，请从私人备份恢复。");
    }
    Path directory() { return directory; }
    char[] keyStorePassword() { return Base64.getEncoder().encodeToString(key).toCharArray(); }
    Properties readProperties(String name) throws IOException {
        Properties values = new Properties(); Path path = directory.resolve(name);
        if (!Files.exists(path)) return values;
        try {
            byte[] encrypted = Files.readAllBytes(path);
            if (encrypted.length < 30 || encrypted.length > 2_000_000 || encrypted[0] != 1) throw new IOException("远程登录记录格式无效。");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(encrypted, 13, encrypted.length - 13);
            try (InputStream input = new ByteArrayInputStream(plain)) { values.load(input); }
            finally { Arrays.fill(plain, (byte) 0); }
            return values;
        } catch (GeneralSecurityException invalid) { throw new IOException("远程登录记录无法验证，请从私人备份恢复。", invalid); }
    }
    void writeProperties(String name, Properties values) throws IOException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); values.store(bytes, "Recorder encrypted authentication state");
            byte[] plain = bytes.toByteArray(), nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted;
            try { encrypted = cipher.doFinal(plain); } finally { Arrays.fill(plain, (byte) 0); }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(1); output.write(nonce); output.write(encrypted);
            atomicWrite(directory.resolve(name), output.toByteArray());
        } catch (GeneralSecurityException failure) { throw new IOException("系统无法加密远程登录记录。", failure); }
    }
    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".private-", ".tmp");
        try {
            protect(temporary); Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            protect(path);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void protect(Path path) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            String name = System.getProperty("user.name"); String domain = System.getenv("USERDOMAIN");
            if (domain != null && !domain.isBlank()) name = domain + "\\" + name;
            UserPrincipal current = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(entry.build()));
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }
}
