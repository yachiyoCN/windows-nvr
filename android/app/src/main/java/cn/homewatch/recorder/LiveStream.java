package cn.homewatch.recorder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A cancellable single-viewer stream. Reconnection never bypasses revoked server permissions. */
final class LiveStream implements AutoCloseable {
    interface Listener { void frame(Bitmap bitmap); void state(String text); void expired(); }
    private final RecorderApi api;
    private final String camera;
    private final Listener listener;
    private final RecorderApi.RequestControl request = new RecorderApi.RequestControl();
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private final Thread thread;
    LiveStream(RecorderApi api, String camera, Listener listener) {
        this.api = api; this.camera = camera; this.listener = listener;
        thread = new Thread(this::run, "Recorder-live"); thread.setDaemon(true); thread.start();
    }
    private void run() {
        int failures = 0;
        while (!closed) {
            try {
                listener.state("正在连接…"); connection = api.live(camera, request);
                if (closed) break;
                try (InputStream stream = new BufferedInputStream(connection.getInputStream(), 65536)) {
                    while (!closed) {
                        String line; do { line = line(stream); } while (line != null && !line.startsWith("--"));
                        if (line == null) break;
                        int size = -1;
                        while ((line = line(stream)) != null && !line.isEmpty()) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) size = Integer.parseInt(line.substring(15).trim());
                        }
                        if (size <= 0 || size > 4 * 1024 * 1024) throw new IOException("实时画面数据不完整");
                        byte[] frame = new byte[size]; int offset = 0;
                        while (offset < size) { int read = stream.read(frame, offset, size - offset); if (read < 0) throw new IOException("实时画面连接中断"); offset += read; }
                        Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.length);
                        if (bitmap != null) {
                            if (!closed) { failures = 0; listener.frame(bitmap); }
                            else bitmap.recycle();
                        }
                    }
                }
            } catch (RecorderApi.Failure failure) {
                if (failure.status == 401) { if (!closed) listener.expired(); return; }
                if (failure.status == 403) { if (!closed) listener.state("无权查看这台摄像头"); return; }
                if (!closed) listener.state(failure.getMessage() + " · 正在重试");
            } catch (Exception failure) { if (!closed) listener.state("连接中断 · 正在重试"); }
            finally { HttpURLConnection c = connection; connection = null; if (c != null) { request.release(c); c.disconnect(); } }
            if (!closed) { try { Thread.sleep(Math.min(8000, 250L << Math.min(failures++, 5))); } catch (InterruptedException ignored) { } }
        }
    }
    private static String line(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int b;
        while ((b = stream.read()) >= 0) {
            if (b == '\n') return new String(out.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
            if (out.size() >= 8192) throw new IOException("实时画面响应头过长"); out.write(b);
        }
        return out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }
    @Override public void close() {
        closed = true; request.cancel(); thread.interrupt();
    }
}
