package cn.homewatch.recorder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Blocking operations run on the activity's background executor. */
final class RecorderApi {
    static final class Failure extends IOException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    final String baseUrl;
    private final SecureTransport transport;
    // The foreground and the scheduled renewal can share a rotating refresh grant.
    // Serializing only one RecorderApi instance is insufficient after Activity recreation.
    private static final Object RENEWAL_LOCK = new Object();
    private static final Map<String, WeakReference<SharedSession>> RENEWALS = new HashMap<>();
    private static final class SharedSession {
        volatile RecorderModels.Session value;
        volatile Failure invalid;
        final ArrayDeque<String> aliases = new ArrayDeque<>();
        SharedSession(RecorderModels.Session value) { this.value = value; }
    }
    private final SharedSession shared;
    private volatile String savedRefreshToken;
    private volatile boolean closed;
    interface SessionListener { void renewed(RecorderModels.Session value) throws Exception; }
    private final SessionListener listener;
    RecorderApi(RecorderModels.Session session, SessionListener listener) throws Exception {
        this(session.baseUrl, session.fingerprint, session, listener);
    }
    private RecorderApi(String base, String pin, RecorderModels.Session session, SessionListener listener) throws Exception {
        transport = new SecureTransport(base, pin); baseUrl = transport.baseUrl;
        this.shared = session == null ? null : bind(session); this.listener = listener;
        savedRefreshToken = session == null ? "" : session.refreshToken;
    }
    private static String renewalKey(RecorderModels.Session value) {
        return value.baseUrl + "\n" + value.fingerprint + "\n" + value.username + "\n" + value.refreshToken;
    }
    private static SharedSession bind(RecorderModels.Session value) {
        synchronized (RENEWAL_LOCK) {
            RENEWALS.entrySet().removeIf(entry -> entry.getValue().get() == null);
            WeakReference<SharedSession> reference = RENEWALS.get(renewalKey(value));
            SharedSession result = reference == null ? null : reference.get();
            if (result == null) { result = new SharedSession(value); remember(result, value); }
            return result;
        }
    }
    private static void remember(SharedSession state, RecorderModels.Session value) {
        String key = renewalKey(value); state.aliases.addLast(key);
        RENEWALS.put(key, new WeakReference<>(state));
        // Active clients retain the shared object directly. A bounded set of aliases
        // also lets newly-created clients adopt a recent persisted grant after rotation.
        while (state.aliases.size() > 32) {
            String old = state.aliases.removeFirst(); WeakReference<SharedSession> reference = RENEWALS.get(old);
            if (reference != null && reference.get() == state) RENEWALS.remove(old);
        }
    }
    static String normalizeBase(String input) { return SecureTransport.normalizeBase(input); }
    static RecorderModels.Session login(String base, String username, String password, String pin) throws Exception {
        JSONObject body = new JSONObject().put("username", username).put("password", password);
        RecorderApi login = new RecorderApi(base, pin, null, null);
        return parseSession(base, pin, login.rawJson("POST", "/api/v1/auth/login", body, ""));
    }
    private static RecorderModels.Session parseSession(String base, String pin, JSONObject response) throws Exception {
        String token = response.getString("accessToken");
        String refresh = response.getString("refreshToken");
        if (token.length() < 16 || refresh.length() < 16 || !"Bearer".equalsIgnoreCase(response.optString("tokenType", "Bearer")))
            throw new IOException("录像机返回的登录信息不完整");
        JSONObject user = response.getJSONObject("user");
        return new RecorderModels.Session(base, token, refresh, user.getString("username"), Instant.parse(response.getString("expiresAt")), pin);
    }
    void verifySession() throws Exception { json("GET", "/api/v1/auth/me", null); }
    void logout() throws Exception {
        synchronized (RENEWAL_LOCK) {
            RecorderModels.Session value = currentSession();
            if (value != null) {
                shared.invalid = new Failure(401, "会话已退出");
                rawJson("POST", "/api/v1/auth/logout", new JSONObject().put("refreshToken", value.refreshToken), value.token);
            }
        }
    }
    void close() { closed = true; }
    RecorderModels.Session currentSession() { return shared == null ? null : shared.value; }
    void prepareMedia() throws Exception { ensureAccess(false, ""); }
    /** A network-constrained periodic job extends the existing grant without retaining a password. */
    void renewRememberedSession() throws Exception { ensureAccess(false, "", true); }
    void installMediaSecurity() { transport.installForMedia(); }
    private void ensureAccess(boolean rejected, String rejectedToken) throws Exception {
        ensureAccess(rejected, rejectedToken, false);
    }
    private void ensureAccess(boolean rejected, String rejectedToken, boolean force) throws Exception {
        RecorderModels.Session observed = currentSession();
        // Background maintenance must not stall a video start while the current
        // access token is still valid and its grant is already saved locally.
        if (!rejected && !force && !closed && observed != null && shared.invalid == null
                && (listener == null || observed.refreshToken.equals(savedRefreshToken))
                && observed.expiresAt.isAfter(Instant.now().plusSeconds(20))) return;
        synchronized (RENEWAL_LOCK) {
            if (closed) throw new Failure(401, "会话已退出");
            RecorderModels.Session value = currentSession(); if (value == null) return;
            if (shared.invalid != null) throw shared.invalid;
            persistRenewal(value);
            if (rejected && !value.token.equals(rejectedToken)) return;
            if (force && observed != null && !value.refreshToken.equals(observed.refreshToken)) return;
            // The existing recorder reuses access tokens with >30 seconds left.
            // Refreshing at 60 seconds therefore rotated the grant again every status poll.
            if (!rejected && !force && value.expiresAt.isAfter(Instant.now().plusSeconds(20))) return;
            RecorderModels.Session renewed;
            try {
                renewed = parseSession(baseUrl, value.fingerprint,
                        rawJson("POST", "/api/v1/auth/refresh", new JSONObject().put("refreshToken", value.refreshToken), ""));
            } catch (Failure failure) {
                if (failure.status == 401) shared.invalid = failure;
                throw failure;
            }
            if (!value.username.equals(renewed.username)) throw new IOException("续期用户与当前账号不一致");
            shared.value = renewed; remember(shared, renewed);
            // Persist a received rotation even if the Activity stopped during the request.
            // A failed commit is retried before the next request instead of losing the new grant.
            persistRenewal(renewed);
            if (closed) throw new Failure(401, "会话已退出");
        }
    }
    private void persistRenewal(RecorderModels.Session value) throws Exception {
        if (listener != null && !value.refreshToken.equals(savedRefreshToken)) {
            listener.renewed(value); savedRefreshToken = value.refreshToken;
        }
    }
    RecorderModels.Server server() throws Exception {
        JSONObject value = json("GET", "/api/v1/server", null);
        return new RecorderModels.Server(Instant.parse(value.getString("serverTime")), ZoneId.of(value.getString("timezone")),
                value.getLong("usedBytes"), value.getLong("maxBytes"), value.getInt("keepDays"));
    }
    List<RecorderModels.Camera> cameras() throws Exception {
        JSONArray array = json("GET", "/api/v1/cameras", null).getJSONArray("cameras");
        List<RecorderModels.Camera> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject c = array.getJSONObject(i);
            result.add(new RecorderModels.Camera(c.getString("id"), c.getString("name"), c.getInt("number"),
                    c.optBoolean("online"), c.optBoolean("recording")));
        }
        return result;
    }
    Instant lastRecording(String camera) throws Exception {
        JSONObject value = json("GET", "/api/v1/recording-bounds?channelId=" + encode(camera), null);
        return value.isNull("lastRecording") ? null : Instant.parse(value.getString("lastRecording"));
    }
    List<RecorderModels.Recording> recordings(String camera, Instant from, Instant to) throws Exception {
        String path = "/api/v1/recordings?channelId=" + encode(camera) + "&from=" + encode(from.toString()) + "&to=" + encode(to.toString());
        JSONArray array = json("GET", path, null).getJSONArray("recordings");
        List<RecorderModels.Recording> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject r = array.getJSONObject(i); JSONArray ps = r.optJSONArray("persons");
            List<RecorderModels.Person> persons = new ArrayList<>();
            if (ps != null) for (int n = 0; n < ps.length(); n++) {
                JSONObject p = ps.getJSONObject(n);
                persons.add(new RecorderModels.Person(p.getDouble("startSeconds"), p.getDouble("endSeconds")));
            }
            result.add(new RecorderModels.Recording(r.getString("id"), r.getString("channelId"), Instant.parse(r.getString("start")),
                    Instant.parse(r.getString("end")), r.getDouble("durationSeconds"), r.optBoolean("active"), persons, r.optString("personStatus", "PENDING")));
        }
        return result;
    }
    String mediaUrl(PlaybackPlanner.Window window) {
        return baseUrl + "/api/v1/recordings/" + encode(window.recording.id) + "/media?offsetSeconds="
                + window.offsetSeconds + "&durationSeconds=" + window.durationSeconds;
    }
    java.util.Map<String, String> authorizationHeaders() {
        java.util.Map<String, String> headers = new java.util.HashMap<>(); headers.put("Authorization", "Bearer " + currentSession().token);
        headers.put("android-allow-cross-domain-redirect", "0"); return headers;
    }
    /** Owns a request before headers arrive, so navigation can cancel it independently. */
    static final class RequestControl implements AutoCloseable {
        private boolean cancelled;
        private HttpURLConnection connection;
        synchronized boolean isCancelled() { return cancelled; }
        synchronized void check() throws InterruptedIOException { if (cancelled) throw new InterruptedIOException("实时画面请求已取消"); }
        void attach(HttpURLConnection value) throws InterruptedIOException {
            synchronized (this) { if (!cancelled) { connection = value; return; } }
            value.disconnect(); throw new InterruptedIOException("实时画面请求已取消");
        }
        synchronized void release(HttpURLConnection value) { if (connection == value) connection = null; }
        void cancel() {
            HttpURLConnection value;
            synchronized (this) { cancelled = true; value = connection; connection = null; }
            if (value != null) value.disconnect();
        }
        @Override public void close() { cancel(); }
    }
    HttpURLConnection live(String id) throws Exception {
        return live(id, new RequestControl());
    }
    HttpURLConnection live(String id, RequestControl request) throws Exception {
        request.check(); ensureAccess(false, ""); request.check(); String token = currentSession().token;
        try { return live(id, token, request); } catch (Failure failure) {
            if (failure.status != 401) throw failure; request.check(); ensureAccess(true, token); request.check();
            return live(id, currentSession().token, request);
        }
    }
    private HttpURLConnection live(String id, String token, RequestControl request) throws Exception {
        HttpURLConnection connection = open("GET", "/api/v1/cameras/" + encode(id) + "/live.mjpg", token);
        try {
            request.attach(connection);
            connection.setReadTimeout(15000);
            int code = connection.getResponseCode();
            if (code != 200) throw failure(connection, code);
            request.check();
            return connection;
        } catch (Exception e) { request.release(connection); connection.disconnect(); throw e; }
    }
    byte[] poster(String id, RequestControl request) throws Exception {
        request.check(); ensureAccess(false, ""); request.check(); String token = currentSession().token;
        try { return poster(id, token, request); } catch (Failure failure) {
            if (failure.status != 401) throw failure; request.check(); ensureAccess(true, token); request.check();
            return poster(id, currentSession().token, request);
        }
    }
    private byte[] poster(String id, String token, RequestControl request) throws Exception {
        HttpURLConnection connection = open("GET", "/api/v1/cameras/" + encode(id) + "/live.jpg", token);
        boolean fullyRead = false;
        try {
            request.attach(connection); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "image/jpeg");
            int code = connection.getResponseCode(); boolean success = code == 200;
            byte[] bytes;
            try (InputStream stream = success ? connection.getInputStream() : connection.getErrorStream()) {
                if (stream == null) throw new Failure(code, failureMessage(code));
                request.check(); bytes = read(stream, success ? 4 * 1024 * 1024 : 32768); fullyRead = true;
            }
            request.check();
            if (!success) throw failure(code, bytes);
            String type = connection.getContentType();
            if (type == null || !"image/jpeg".equalsIgnoreCase(type.split(";", 2)[0].trim()))
                throw new IOException("摄像头返回的预览图片格式不正确");
            return bytes;
        } finally { request.release(connection); if (!fullyRead) connection.disconnect(); }
    }
    private JSONObject json(String method, String path, JSONObject body) throws Exception {
        ensureAccess(false, ""); String token = currentSession() == null ? "" : currentSession().token;
        try { return rawJson(method, path, body, token); } catch (Failure failure) {
            if (failure.status != 401 || currentSession() == null) throw failure;
            ensureAccess(true, token); return rawJson(method, path, body, currentSession().token);
        }
    }
    private JSONObject rawJson(String method, String path, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = open(method, path, token);
        boolean fullyRead = false;
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int code = connection.getResponseCode(); boolean success = code >= 200 && code < 300;
            byte[] bytes;
            try (InputStream stream = success ? connection.getInputStream() : connection.getErrorStream()) {
                if (stream == null) throw new Failure(code, failureMessage(code));
                bytes = read(stream, success ? 16 * 1024 * 1024 : 32768);
                fullyRead = true;
            }
            if (!success) throw failure(code, bytes);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            // Closing a fully consumed response returns the authenticated socket to
            // Android's HTTPS pool. Aborted / oversized responses must be discarded.
            if (!fullyRead) connection.disconnect();
        }
    }
    private HttpURLConnection open(String method, String path, String token) throws Exception {
        if (!path.startsWith("/api/v1/") || path.contains("\\") || path.contains("\r") || path.contains("\n")) throw new IOException("接口地址无效");
        HttpURLConnection connection = transport.open(new URL(baseUrl + path));
        connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }
    private static Failure failure(HttpURLConnection connection, int code) {
        String message = failureMessage(code);
        try (InputStream stream = connection.getErrorStream()) {
            if (stream != null) return failure(code, read(stream, 32768));
        } catch (Exception ignored) { }
        return new Failure(code, message);
    }
    private static String failureMessage(int code) { return code == 401 ? "登录已失效，请重新登录" : "录像机连接失败（" + code + "）"; }
    private static Failure failure(int code, byte[] bytes) {
        String message = failureMessage(code);
        try { message = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optString("message", message); } catch (Exception ignored) { }
        return new Failure(code, message);
    }
    static byte[] read(InputStream stream, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) >= 0) { if (out.size() + count > maximum) throw new IOException("返回内容超过限制"); out.write(buffer, 0, count); }
        return out.toByteArray();
    }
    private static String encode(String value) { try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
