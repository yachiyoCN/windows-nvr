package cn.homewatch;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.*;

/** Explicit HTTPS camera trust, bound to one origin and a previously checked public key. */
public final class CameraTlsSupport {
    private static final Map<String, X509Certificate> CERTIFICATES = new HashMap<>();
    private static final Object LOCK = new Object();
    private static final String CERTIFICATE_DIRECTORY = "data/camera-security";
    private CameraTlsSupport() { }

    public static final class UnavailableException extends IOException {
        UnavailableException(String message) { super(message); }
        UnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** Used only by an explicit pairing/registration action with an already checked pin. */
    public static void storePin(Path assetsRoot, String cameraUrl, String spkiSha256) throws IOException {
        String origin = origin(cameraUrl), pin = normalizePin(spkiSha256);
        synchronized (LOCK) {
            Path file = assetsRoot.toAbsolutePath().normalize().resolve("config/camera-tls.properties");
            Properties pins = readPins(file);
            pins.setProperty(origin, pin);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pins.store(bytes, "Explicit camera HTTPS public-key pins; origin keys contain no credentials");
            RemotePrivateStorage.atomicWrite(file, bytes.toByteArray());
            CERTIFICATES.remove(file + "|" + origin);
        }
    }

    /** Explicit legacy pairing type; a plain 64-character pin always means full SPKI. */
    public static void storePublicKeyValuePin(Path assetsRoot, String cameraUrl, String keyValueSha256) throws IOException {
        String value = normalizePin(keyValueSha256);
        if (value.startsWith("KEYVALUE-SHA256:")) value = value.substring("KEYVALUE-SHA256:".length());
        storePin(assetsRoot, cameraUrl, "KEYVALUE-SHA256:" + value);
    }

    /** No configured pin uses normal CA and hostname validation; no global exception is installed. */
    public static void addInputOptions(List<String> command, Path assetsRoot, Path processRoot, String cameraUrl) throws IOException {
        URI uri = endpoint(cameraUrl);
        command.addAll(List.of("-tls_verify", "1", "-rw_timeout", "8000000"));
        Path pinsFile = assetsRoot.toAbsolutePath().normalize().resolve("config/camera-tls.properties");
        String origin = origin(cameraUrl), pin;
        synchronized (LOCK) { pin = readPins(pinsFile).getProperty(origin); }
        if (pin == null) return;
        pin = normalizePin(pin);
        String name = digest(origin.getBytes(StandardCharsets.US_ASCII)) + ".pem";
        Path directory = processRoot.toAbsolutePath().normalize().resolve(CERTIFICATE_DIRECTORY);
        Path certificateFile = directory.resolve(name);
        X509Certificate certificate;
        synchronized (LOCK) {
            String key = pinsFile + "|" + origin;
            certificate = CERTIFICATES.get(key);
            if (!acceptable(certificate, pin)) certificate = readCachedCertificate(certificateFile, pin);
            if (certificate == null) certificate = retrievePinnedCertificate(uri, pin);
            verifyPinnedCertificate(certificate, pin);
            // Older pairing reports hashed only the certificate BIT STRING. Keep
            // that type explicit, then migrate to full SPKI after proof of key possession.
            if (pin.startsWith("KEYVALUE-SHA256:")) {
                pin = digest(certificate.getPublicKey().getEncoded());
                Properties pins = readPins(pinsFile); pins.setProperty(origin, pin);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); pins.store(bytes, "Explicit camera HTTPS SPKI public-key pins");
                RemotePrivateStorage.atomicWrite(pinsFile, bytes.toByteArray());
            }
            Files.createDirectories(directory); protect(directory);
            RemotePrivateStorage.atomicWrite(certificateFile, pem(certificate));
            CERTIFICATES.put(key, certificate);
        }
        // GnuTLS's file loader does not accept Chinese absolute paths in this Windows build.
        // The process directory contains this ASCII relative path even when the project is Chinese.
        command.addAll(List.of("-ca_file", CERTIFICATE_DIRECTORY + "/" + name,
                "-verifyhost", certificateHostname(certificate, uri.getHost())));
    }

    static String origin(String value) throws IOException {
        URI uri = endpoint(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "https://" + host + ":" + (uri.getPort() > 0 ? uri.getPort() : 443);
    }

    private static URI endpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) { throw new UnavailableException("摄像头 HTTPS 地址无效。"); }
    }

    private static Properties readPins(Path file) throws IOException {
        Properties pins = new Properties();
        if (!Files.exists(file)) return pins;
        if (!Files.isRegularFile(file) || Files.size(file) > 65536)
            throw new UnavailableException("摄像头证书信任记录无效，请重新核对设备证书。");
        try (InputStream input = Files.newInputStream(file)) { pins.load(input); }
        return pins;
    }

    private static String normalizePin(String pin) throws IOException {
        String value = Objects.requireNonNullElse(pin, "").strip().toUpperCase(Locale.ROOT);
        String prefix = value.startsWith("KEYVALUE-SHA256:") ? "KEYVALUE-SHA256:" : "";
        if (!prefix.isEmpty()) value = value.substring(prefix.length());
        else if (value.startsWith("SPKI-SHA256:")) value = value.substring("SPKI-SHA256:".length());
        String normalized = value.replace(":", "");
        if (!normalized.matches("[0-9A-F]{64}")) throw new UnavailableException("摄像头公钥指纹格式无效，请重新核对设备证书。");
        return prefix + normalized;
    }

    private static X509Certificate readCachedCertificate(Path file, String pin) {
        try (InputStream input = Files.newInputStream(file)) {
            if (Files.size(file) > 65536) return null;
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            return acceptable(certificate, pin) ? certificate : null;
        } catch (IOException | GeneralSecurityException invalid) { return null; }
    }

    private static boolean acceptable(X509Certificate certificate, String pin) {
        if (certificate == null) return false;
        try { verifyPinnedCertificate(certificate, pin); return true; }
        catch (IOException invalid) { return false; }
    }

    private static void verifyPinnedCertificate(X509Certificate certificate, String pin) throws IOException {
        try {
            certificate.checkValidity();
            boolean keyValue = pin.startsWith("KEYVALUE-SHA256:");
            String actual = digest(keyValue ? publicKeyBitString(certificate.getPublicKey().getEncoded()) : certificate.getPublicKey().getEncoded());
            String expected = keyValue ? pin.substring("KEYVALUE-SHA256:".length()) : pin;
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII)))
                throw new UnavailableException("摄像头证书公钥已改变，连接已停止；请重新核对设备证书指纹。");
        } catch (CertificateExpiredException | CertificateNotYetValidException expired) {
            throw new UnavailableException("摄像头加密证书已过期或尚未生效，请检查设备日期与证书。", expired);
        }
    }

    private static X509Certificate retrievePinnedCertificate(URI uri, String pin) throws IOException {
        try {
            X509TrustManager trust = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("client certificate not accepted"); }
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain == null || chain.length == 0) throw new CertificateException("server certificate missing");
                    try { verifyPinnedCertificate(chain[0], pin); }
                    catch (IOException invalid) { throw new CertificateException(invalid.getMessage(), invalid); }
                }
            };
            SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, null);
            try (Socket tcp = new Socket()) {
                tcp.connect(new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 443), 5000);
                tcp.setSoTimeout(5000);
                try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(tcp, uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 443, false)) {
                    socket.setSoTimeout(5000); socket.startHandshake();
                    X509Certificate certificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
                    verifyPinnedCertificate(certificate, pin); return certificate;
                }
            }
        } catch (SSLHandshakeException rejected) {
            Throwable reason = rejected;
            while (reason != null) {
                if (reason instanceof UnavailableException explanation) throw explanation;
                reason = reason.getCause();
            }
            throw new UnavailableException("摄像头加密证书无法验证，连接已停止；请重新核对设备证书指纹。", rejected);
        } catch (GeneralSecurityException failure) { throw new UnavailableException("无法初始化摄像头加密证书验证。", failure); }
    }

    private static String certificateHostname(X509Certificate certificate, String endpointHost) throws IOException {
        try {
            Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> alternative : alternatives)
                    if (alternative.size() > 1 && Objects.equals(alternative.get(0), 7) && endpointHost.equalsIgnoreCase(Objects.toString(alternative.get(1)))) return endpointHost;
                for (List<?> alternative : alternatives)
                    if (alternative.size() > 1 && Objects.equals(alternative.get(0), 2)) {
                        String name = Objects.toString(alternative.get(1));
                        if (name.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) return name;
                    }
            }
            for (Rdn rdn : new LdapName(certificate.getSubjectX500Principal().getName()).getRdns())
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    String name = rdn.getValue().toString();
                    if (name.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) return name;
                }
            throw new UnavailableException("摄像头证书缺少可验证的设备名，请更新设备证书。");
        } catch (java.security.cert.CertificateParsingException | javax.naming.InvalidNameException invalid) {
            throw new UnavailableException("摄像头证书中的设备名无效。", invalid);
        }
    }

    private static String digest(byte[] bytes) throws IOException {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException unavailable) { throw new UnavailableException("系统不支持摄像头公钥验证。", unavailable); }
    }

    private static byte[] publicKeyBitString(byte[] spki) throws IOException {
        // DER SubjectPublicKeyInfo = SEQUENCE { AlgorithmIdentifier, BIT STRING }.
        // X509Certificate2.EncodedKeyValue hashes BIT STRING content after its unused-bit byte.
        try {
            int[] offset = {0};
            if ((spki[offset[0]++] & 255) != 0x30) throw new IllegalArgumentException();
            int sequenceLength = derLength(spki, offset);
            if (sequenceLength != spki.length - offset[0] || (spki[offset[0]++] & 255) != 0x30) throw new IllegalArgumentException();
            int algorithmLength = derLength(spki, offset); offset[0] += algorithmLength;
            if ((spki[offset[0]++] & 255) != 0x03) throw new IllegalArgumentException();
            int keyLength = derLength(spki, offset);
            if (keyLength < 2 || offset[0] + keyLength != spki.length || spki[offset[0]++] != 0) throw new IllegalArgumentException();
            return Arrays.copyOfRange(spki, offset[0], spki.length);
        } catch (RuntimeException invalid) { throw new UnavailableException("摄像头公钥格式无法核对，请重新读取标准 SPKI 指纹。", invalid); }
    }

    private static int derLength(byte[] bytes, int[] offset) {
        int first = bytes[offset[0]++] & 255;
        if (first < 128) return first;
        int count = first & 127; if (count == 0 || count > 3) throw new IllegalArgumentException();
        int length = 0; for (int i = 0; i < count; i++) length = (length << 8) | (bytes[offset[0]++] & 255);
        return length;
    }

    private static byte[] pem(X509Certificate certificate) throws IOException {
        try {
            return ("-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII);
        } catch (CertificateEncodingException invalid) { throw new UnavailableException("摄像头加密证书无法保存。", invalid); }
    }

    private static void protect(Path directory) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl != null) {
            String username = System.getProperty("user.name"), domain = System.getenv("USERDOMAIN");
            if (domain != null && !domain.isBlank()) username = domain + "\\" + username;
            UserPrincipal user = directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(username);
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build();
            acl.setAcl(List.of(entry));
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
        }
    }
}
