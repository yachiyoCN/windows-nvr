package cn.homewatch.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The whole profile and renewable session are encrypted; passwords and media are never retained. */
final class SessionStore {
    private static final String ALIAS = "recorder.login.v1";
    private final SharedPreferences prefs;
    private SecretKey cachedKey;
    private static long revision;
    SessionStore(Context context) { prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE); }
    private JSONObject profile() { try { return decrypt("profile.v2"); } catch (Exception e) { return new JSONObject(); } }
    String baseUrl() { return profile().optString("baseUrl", prefs.getString("baseUrl", "")); }
    String username() { return profile().optString("username", prefs.getString("username", "")); }
    String fingerprint() { return profile().optString("fingerprint", ""); }
    boolean needsHttpsMigration() { return baseUrl().toLowerCase(java.util.Locale.ROOT).startsWith("http://"); }
    synchronized void save(RecorderModels.Session session, boolean remember) throws Exception {
        synchronized (SessionStore.class) { write(session, remember); revision++; }
    }
    synchronized boolean save(RecorderModels.Session session, boolean remember, long expectedRevision) throws Exception {
        synchronized (SessionStore.class) {
            if (revision != expectedRevision) return false;
            write(session, remember); revision++; return true;
        }
    }
    synchronized long revision() { synchronized (SessionStore.class) { return revision; } }
    synchronized void update(RecorderModels.Session session, long expectedRevision) throws Exception {
        synchronized (SessionStore.class) { if (revision == expectedRevision) write(session, true); }
    }
    private void write(RecorderModels.Session session, boolean remember) throws Exception {
        boolean sameProfile = sameProfile(profile(), session);
        JSONObject profile = new JSONObject().put("baseUrl", session.baseUrl).put("username", session.username).put("fingerprint", session.fingerprint);
        SharedPreferences.Editor edit = prefs.edit().putString("profile.v2", encrypt("profile.v2", profile));
        if (!sameProfile || !remember) edit.remove("cameras.v1");
        if (remember) {
            JSONObject value = new JSONObject(profile.toString()).put("accessToken", session.token).put("refreshToken", session.refreshToken).put("expiresAt", session.expiresAt.toString());
            edit.putString("session.v2", encrypt("session.v2", value));
        } else edit.remove("session.v2");
        edit.remove("baseUrl").remove("username").remove("token").remove("iv").remove("expiresAt");
        if (!edit.commit()) throw new java.io.IOException("无法保存登录，请检查手机存储空间");
    }
    synchronized RecorderModels.Session load() {
        try {
            if (!prefs.contains("session.v2")) return null;
            JSONObject value = decrypt("session.v2"); String base = value.getString("baseUrl");
            if (!base.startsWith("https://")) { clearToken(); return null; }
            // An expired access token is expected: the persisted refresh token renews it automatically.
            return new RecorderModels.Session(SecureTransport.normalizeBase(base), value.getString("accessToken"), value.getString("refreshToken"),
                    value.getString("username"), Instant.parse(value.getString("expiresAt")), SecureTransport.normalizeFingerprint(value.optString("fingerprint")));
        } catch (Exception e) {
            // Keystore or storage may be temporarily unavailable. Keep the encrypted
            // session so a later launch can recover it rather than forcing a new login.
            return null;
        }
    }
    synchronized void clearToken() { synchronized (SessionStore.class) { revision++; prefs.edit().remove("session.v2").remove("cameras.v1").remove("token").remove("iv").remove("expiresAt").commit(); } }
    synchronized boolean clearToken(long expectedRevision) {
        synchronized (SessionStore.class) {
            if (revision != expectedRevision) return false;
            clearToken(); return true;
        }
    }
    /** Metadata only. A cached device is a navigation shortcut, never proof of access or online status. */
    synchronized List<RecorderModels.Camera> loadCameras(RecorderModels.Session session) {
        try {
            JSONObject value = decrypt("cameras.v1");
            long saved = value.getLong("savedAt"), now = System.currentTimeMillis();
            if (!sameProfile(value, session) || saved > now + 300_000 || now - saved > 86_400_000) return java.util.Collections.emptyList();
            JSONArray items = value.getJSONArray("cameras");
            if (items.length() > 16) return java.util.Collections.emptyList();
            List<RecorderModels.Camera> result = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = item.getString("id"), name = item.getString("name");
                int number = item.getInt("number");
                if (id.isEmpty() || id.length() > 128 || name.length() > 80 || number < 1 || number > 16) return java.util.Collections.emptyList();
                result.add(new RecorderModels.Camera(id, name, number, false, false));
            }
            return result;
        } catch (Exception ignored) { return java.util.Collections.emptyList(); }
    }
    synchronized boolean saveCameras(RecorderModels.Session session, List<RecorderModels.Camera> cameras, long expectedRevision) throws Exception {
        synchronized (SessionStore.class) {
            if (revision != expectedRevision || !prefs.contains("session.v2") || !sameProfile(profile(), session)) return false;
            JSONArray items = new JSONArray();
            for (RecorderModels.Camera camera : cameras) {
                items.put(new JSONObject().put("id", camera.id).put("name", camera.name).put("number", camera.number));
            }
            JSONObject value = new JSONObject().put("baseUrl", session.baseUrl).put("username", session.username)
                    .put("fingerprint", session.fingerprint).put("savedAt", System.currentTimeMillis()).put("cameras", items);
            if (!prefs.edit().putString("cameras.v1", encrypt("cameras.v1", value)).commit()) throw new java.io.IOException("设备列表缓存无法保存");
            return true;
        }
    }
    private static boolean sameProfile(JSONObject value, RecorderModels.Session session) {
        return session != null && session.baseUrl.equals(value.optString("baseUrl"))
                && session.username.equals(value.optString("username")) && session.fingerprint.equals(value.optString("fingerprint"));
    }
    private String encrypt(String scope, JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    private JSONObject decrypt(String scope) throws Exception {
        String[] parts = prefs.getString(scope, "").split(":", -1); if (parts.length != 2) throw new java.io.IOException("Missing encrypted profile");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))); cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }
    private synchronized SecretKey key() throws Exception {
        if (cachedKey != null) return cachedKey;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return cachedKey = (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return cachedKey = generator.generateKey();
    }
}
