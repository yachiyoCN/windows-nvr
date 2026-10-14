package cn.homewatch.recorder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** The computer is trusted by an explicitly confirmed SHA-256 public-key fingerprint or system CA. */
final class SecureTransport {
    private static final SSLSocketFactory SYSTEM_FACTORY = HttpsURLConnection.getDefaultSSLSocketFactory();
    private static final HostnameVerifier SYSTEM_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier();
    // Android's HTTPS pool includes the factory identity in a connection's key.
    // Keep one policy per confirmed origin and key across Activity / API recreation.
    // No credentials, frames or fingerprint files are written by this small cache.
    private static final Map<String, Policy> POLICIES = new LinkedHashMap<>(8, 0.75f, true);
    private static final class Policy {
        final SSLSocketFactory factory;
        final HostnameVerifier verifier;
        Policy(SSLSocketFactory factory, HostnameVerifier verifier) { this.factory = factory; this.verifier = verifier; }
    }
    final String baseUrl, fingerprint;
    private final String host;
    private final int port;
    private final SSLSocketFactory factory;
    private final HostnameVerifier verifier;

    SecureTransport(String base, String pin) throws Exception {
        baseUrl = normalizeBase(base); fingerprint = normalizeFingerprint(pin);
        URI uri = URI.create(baseUrl); host = uri.getHost(); port = uri.getPort() < 0 ? 443 : uri.getPort();
        Policy policy = policy(host, port, fingerprint); factory = policy.factory; verifier = policy.verifier;
    }
    private static synchronized Policy policy(String host, int port, String fingerprint) throws Exception {
        String key = host.toLowerCase(Locale.ROOT) + ":" + port + "\n" + fingerprint;
        Policy cached = POLICIES.get(key); if (cached != null) return cached;
        SSLSocketFactory factory; HostnameVerifier verifier;
        if (fingerprint.isEmpty()) { factory = new BoundFactory(SYSTEM_FACTORY, host, port); verifier = SYSTEM_VERIFIER; }
        else {
            byte[] expected = decode(fingerprint);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException { throw new CertificateException("Client certificate is not supported"); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                    if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
                    chain[0].checkValidity();
                    try {
                        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded())))
                            throw new CertificateException("录像机连接指纹不匹配，请在电脑上重新核对");
                    } catch (java.security.NoSuchAlgorithmException impossible) { throw new CertificateException(impossible); }
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            factory = new BoundFactory(context.getSocketFactory(), host, port);
            verifier = (candidate, session) -> host.equalsIgnoreCase(candidate) && matches(session, expected);
        }
        Policy created = new Policy(factory, verifier); POLICIES.put(key, created);
        while (POLICIES.size() > 8) POLICIES.remove(POLICIES.keySet().iterator().next());
        return created;
    }
    HttpsURLConnection open(URL url) throws IOException {
        int candidatePort = url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
        if (!url.getProtocol().equals("https") || !host.equalsIgnoreCase(url.getHost()) || candidatePort != port)
            throw new IOException("连接目标与已确认的录像机不一致");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(factory); connection.setHostnameVerifier(verifier);
        connection.setInstanceFollowRedirects(false); return connection;
    }
    // Android's native MediaHTTPConnection uses these process-local HTTPS defaults.
    // Both socket destination and peer key remain bound to this one confirmed recorder.
    void installForMedia() { HttpsURLConnection.setDefaultSSLSocketFactory(factory); HttpsURLConnection.setDefaultHostnameVerifier(verifier); }
    static void resetMedia() { HttpsURLConnection.setDefaultSSLSocketFactory(SYSTEM_FACTORY); HttpsURLConnection.setDefaultHostnameVerifier(SYSTEM_VERIFIER); }
    static String normalizeBase(String input) {
        String raw = input.trim(); if (!raw.contains("://")) raw = "https://" + raw;
        try {
            URI uri = new URI(raw); String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("请输入录像机地址，例如 https://电脑IP:9079");
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.endsWith("/api/v1")) path = path.substring(0, path.length() - 7);
            if (!path.isEmpty()) throw new IllegalArgumentException("请输入录像机根地址，不要加入网页或接口路径");
            int port = uri.getPort(); if (port == -1) port = 9079;
            if (port < 1 || port > 65535) throw new IllegalArgumentException("录像机端口必须在 1 到 65535 之间");
            return new URI("https", null, uri.getHost(), port, "", null, null).toString();
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("录像机地址格式不正确"); }
    }
    static String normalizeFingerprint(String input) {
        String value = input.trim().replaceFirst("(?i)^SHA-?256\\s*[:=]\\s*", "").replaceAll("[:\\s-]", "").toUpperCase(Locale.ROOT);
        if (!value.isEmpty() && !value.matches("[0-9A-F]{64}")) throw new IllegalArgumentException("连接指纹应为电脑显示的 64 位 SHA-256 公钥指纹");
        return value;
    }
    private static byte[] decode(String value) { byte[] out = new byte[32]; for (int n = 0; n < out.length; n++) out[n] = (byte) Integer.parseInt(value.substring(n * 2, n * 2 + 2), 16); return out; }
    private static boolean matches(SSLSession session, byte[] expected) {
        try { X509Certificate cert = (X509Certificate) session.getPeerCertificates()[0]; cert.checkValidity(); return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded())); }
        catch (Exception e) { return false; }
    }
    private static final class BoundFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate; private final String host; private final int port;
        BoundFactory(SSLSocketFactory delegate, String host, int port) { this.delegate = delegate; this.host = host; this.port = port; }
        private void target(String candidate, int candidatePort) throws IOException { if (!host.equalsIgnoreCase(candidate) || candidatePort != port) throw new IOException("拒绝连接未确认的录像机地址或端口"); }
        private void address(InetAddress candidate, int candidatePort) throws IOException {
            if (candidate == null || candidatePort != port) throw new IOException("拒绝连接未确认的录像机地址或端口");
            // toString retains an explicitly supplied name without doing a reverse DNS lookup.
            // A nameless resolved address is allowed only when it belongs to our configured host.
            String value = candidate.toString(); int slash = value.indexOf('/');
            String name = slash < 0 ? "" : value.substring(0, slash);
            if (!name.isEmpty() && !host.equalsIgnoreCase(name) && !candidate.getHostAddress().equalsIgnoreCase(name))
                throw new IOException("拒绝连接未确认的录像机地址或端口");
            for (InetAddress resolved : InetAddress.getAllByName(host)) if (resolved.equals(candidate)) return;
            throw new IOException("解析地址与已确认的录像机不一致");
        }
        private Socket resolvedSocket(InetAddress destination, int candidatePort, InetAddress local, int localPort) throws IOException {
            address(destination, candidatePort);
            Socket plain = new Socket(); boolean complete = false;
            try {
                if (local != null) plain.bind(new InetSocketAddress(local, localPort));
                plain.connect(new InetSocketAddress(destination, port));
                // Preserve the configured domain for TLS SNI and peer identity, even if DNS lost its name.
                Socket secure = delegate.createSocket(plain, host, port, true); complete = true; return secure;
            } finally { if (!complete) plain.close(); }
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket socket, String h, int p, boolean close) throws IOException {
            target(h, p);
            if (socket != null && socket.isConnected()) address(socket.getInetAddress(), socket.getPort());
            return delegate.createSocket(socket, host, port, close);
        }
        @Override public Socket createSocket(String h, int p) throws IOException { target(h, p); return delegate.createSocket(host, port); }
        @Override public Socket createSocket(String h, int p, InetAddress local, int lp) throws IOException { target(h, p); return delegate.createSocket(host, port, local, lp); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return resolvedSocket(h, p, null, 0); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress local, int lp) throws IOException { return resolvedSocket(h, p, local, lp); }
        @Override public Socket createSocket() { return new Socket() {
            @Override public void connect(SocketAddress destination) throws IOException { connect(destination, 0); }
            @Override public void connect(SocketAddress destination, int timeout) throws IOException {
                if (!(destination instanceof InetSocketAddress)) throw new IOException("HTTPS目标无效");
                InetSocketAddress endpoint = (InetSocketAddress) destination;
                if (endpoint.isUnresolved()) {
                    BoundFactory.this.target(endpoint.getHostString(), endpoint.getPort());
                    endpoint = new InetSocketAddress(InetAddress.getByName(host), port);
                }
                BoundFactory.this.address(endpoint.getAddress(), endpoint.getPort());
                super.connect(endpoint, timeout);
            }
        }; }
    }
}
