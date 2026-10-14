package cn.homewatch;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.net.ssl.*;

/** A private recorder CA and stable server key. No HTTP listener or permissive trust manager. */
final class RemoteTls {
    private final SSLContext context;
    private final String publicKeyPin;
    private final Path publicCertificate;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] SIGNATURE_ALGORITHM = seq(oid("1.2.840.10045.4.3.2"));

    RemoteTls(RemotePrivateStorage storage) throws IOException {
        char[] password = storage.keyStorePassword();
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12"); Path file = storage.directory().resolve("recorder-tls.p12");
            if (Files.exists(file)) { try (InputStream input = Files.newInputStream(file)) { keys.load(input, password); } }
            else {
                keys.load(null, password); KeyPair authority = keyPair(), server = keyPair();
                byte[] name = name("Recorder Private CA " + UUID.randomUUID().toString().substring(0, 8));
                X509Certificate ca = certificate(name, name, authority.getPublic(), authority.getPrivate(), true, List.of(), Duration.ofDays(3650));
                X509Certificate leaf = certificate(name("Recorder"), name, server.getPublic(), authority.getPrivate(), false, subjectNames(), Duration.ofDays(730));
                keys.setKeyEntry("authority", authority.getPrivate(), password, new java.security.cert.Certificate[]{ca});
                keys.setKeyEntry("server", server.getPrivate(), password, new java.security.cert.Certificate[]{leaf, ca});
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); keys.store(bytes, password);
                RemotePrivateStorage.atomicWrite(file, bytes.toByteArray());
            }
            X509Certificate ca = (X509Certificate) keys.getCertificate("authority"); ca.checkValidity();
            PrivateKey authority = (PrivateKey) keys.getKey("authority", password);
            X509Certificate previous = (X509Certificate) keys.getCertificate("server");
            PrivateKey server = (PrivateKey) keys.getKey("server", password);
            // Refresh address SANs on start, retaining the same key and pairing fingerprint.
            X509Certificate leaf = certificate(name("Recorder"), ca.getSubjectX500Principal().getEncoded(), previous.getPublicKey(), authority,
                    false, subjectNames(), Duration.ofDays(730));
            keys.setKeyEntry("server", server, password, new java.security.cert.Certificate[]{leaf, ca});
            KeyStore listener = KeyStore.getInstance("PKCS12"); listener.load(null, password);
            listener.setKeyEntry("server", server, password, new java.security.cert.Certificate[]{leaf, ca});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(listener, password);
            context = SSLContext.getInstance("TLS"); context.init(factory.getKeyManagers(), null, RANDOM);
            publicKeyPin = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getPublicKey().getEncoded()));
            publicCertificate = storage.directory().resolve("录像机局域网证书.cer");
            RemotePrivateStorage.atomicWrite(publicCertificate, ca.getEncoded());
        } catch (GeneralSecurityException failure) { throw new IOException("加密连接证书无法加载，请检查安全数据或恢复私人备份。", failure); }
        finally { Arrays.fill(password, '\0'); }
    }
    SSLContext context() { return context; }
    String publicKeyPin() { return publicKeyPin; }
    Path publicCertificate() { return publicCertificate; }
    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM); return generator.generateKeyPair();
    }
    private static X509Certificate certificate(byte[] subject, byte[] issuer, PublicKey publicKey, PrivateKey signer,
            boolean authority, List<byte[]> names, Duration validity) throws GeneralSecurityException, IOException {
        Instant now = Instant.now(); List<byte[]> extensions = new ArrayList<>();
        extensions.add(extension("2.5.29.19", true, authority ? seq(tag(0x01, new byte[]{(byte)0xff}), integer(BigInteger.ZERO)) : seq()));
        extensions.add(extension("2.5.29.15", true, tag(0x03, authority ? new byte[]{1, (byte)0x86} : new byte[]{7, (byte)0x80})));
        if (!authority) {
            extensions.add(extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))));
            extensions.add(extension("2.5.29.17", false, seq(names.toArray(byte[][]::new))));
        }
        byte[] tbs = seq(tag(0xa0, integer(BigInteger.valueOf(2))), integer(new BigInteger(128, RANDOM).add(BigInteger.ONE)),
                SIGNATURE_ALGORITHM, issuer, seq(date(now.minus(Duration.ofDays(1))), date(now.plus(validity))), subject,
                publicKey.getEncoded(), tag(0xa3, seq(extensions.toArray(byte[][]::new))));
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(signer, RANDOM); signature.update(tbs);
        byte[] value = signature.sign(); byte[] bitString = new byte[value.length + 1]; System.arraycopy(value, 0, bitString, 1, value.length);
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(seq(tbs, SIGNATURE_ALGORITHM, tag(0x03, bitString))));
    }
    private static List<byte[]> subjectNames() throws IOException {
        List<byte[]> values = new ArrayList<>(); Set<String> unique = new HashSet<>();
        addName(values, unique, "localhost", false); addName(values, unique, "127.0.0.1", true); addName(values, unique, "::1", true);
        try {
            String host = InetAddress.getLocalHost().getHostName(); if (host.matches("[A-Za-z0-9._-]{1,253}")) addName(values, unique, host, false);
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement(); if (!network.isUp()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) if (!address.isAnyLocalAddress() && !address.isLinkLocalAddress())
                    addName(values, unique, address.getHostAddress().split("%", 2)[0], true);
            }
        } catch (SocketException | UnknownHostException unavailable) { /* Loopback remains usable. */ }
        return values;
    }
    private static void addName(List<byte[]> values, Set<String> unique, String name, boolean ip) throws IOException {
        if (unique.add(name)) values.add(tag(ip ? 0x87 : 0x82, ip ? InetAddress.getByName(name).getAddress() : name.getBytes(StandardCharsets.US_ASCII)));
    }
    private static byte[] name(String text) { return seq(tag(0x31, seq(oid("2.5.4.3"), tag(0x0c, text.getBytes(StandardCharsets.UTF_8))))); }
    private static byte[] date(Instant value) { return tag(0x18, DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC).format(value).getBytes(StandardCharsets.US_ASCII)); }
    private static byte[] extension(String oid, boolean critical, byte[] value) { return critical ? seq(oid(oid), tag(0x01, new byte[]{(byte)0xff}), tag(0x04, value)) : seq(oid(oid), tag(0x04, value)); }
    private static byte[] integer(BigInteger value) { return tag(0x02, value.toByteArray()); }
    private static byte[] seq(byte[]... values) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] value : values) out.writeBytes(value); return tag(0x30, out.toByteArray()); }
    private static byte[] tag(int type, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(type);
        if (value.length < 128) out.write(value.length); else {
            int count = value.length <= 255 ? 1 : value.length <= 65535 ? 2 : 3; out.write(0x80 | count);
            for (int i = count - 1; i >= 0; i--) out.write(value.length >>> (i * 8) & 255);
        }
        out.writeBytes(value); return out.toByteArray();
    }
    private static byte[] oid(String text) {
        String[] parts = text.split("\\."); ByteArrayOutputStream out = new ByteArrayOutputStream();
        long first = Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]); base128(out, first);
        for (int i = 2; i < parts.length; i++) base128(out, Long.parseLong(parts[i])); return tag(0x06, out.toByteArray());
    }
    private static void base128(ByteArrayOutputStream out, long value) {
        int groups = 1; for (long rest = value; (rest >>>= 7) != 0;) groups++;
        for (int i = groups - 1; i >= 0; i--) out.write((int)(value >>> (i * 7) & 127) | (i == 0 ? 0 : 128));
    }
}
