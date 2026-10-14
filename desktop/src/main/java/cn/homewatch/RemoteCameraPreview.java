package cn.homewatch;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A demand-only preview for remote viewers when the desktop preview has been stopped. */
final class RemoteCameraPreview implements AutoCloseable {
    private final Supplier<StreamEngine.Settings> settings;
    private final BooleanSupplier desktopOnline;
    private final Supplier<BufferedImage> desktopFrame;
    private final ScheduledExecutorService scheduler;
    private final StreamEngine engine;
    private volatile BufferedImage frame;
    private volatile long lastFrameNanos;
    private int viewers;
    private long idleSince;
    private boolean requested;
    private volatile boolean closed;
    private StreamEngine.Settings connectedSettings;

    RemoteCameraPreview(Path assetsRoot, Path channelRoot, Supplier<StreamEngine.Settings> settings,
                        BooleanSupplier desktopOnline, Supplier<BufferedImage> desktopFrame) {
        this.settings = settings;
        this.desktopOnline = desktopOnline;
        this.desktopFrame = desktopFrame;
        engine = new StreamEngine(assetsRoot, channelRoot, new StreamEngine.Events() {
            public void onState(String state, String detail) {
                if (state.equals("ERROR") || state.equals("DISCONNECTED")) { frame = null; lastFrameNanos = 0; }
            }
            public void onFrame(BufferedImage image) {
                if (!closed) { frame = image; lastFrameNanos = System.nanoTime(); }
            }
            public void onNotice(String message, boolean error) {}
            public void onRecording(boolean active) {}
            public void onAudio(boolean enabled) {}
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread worker = new Thread(task, "remote-camera-demand"); worker.setDaemon(true); return worker;
        });
        scheduler.scheduleWithFixedDelay(this::maintain, 0, 1, TimeUnit.SECONDS);
    }

    synchronized AutoCloseable acquire() {
        if (closed) throw new IllegalStateException("设备已关闭");
        viewers++; idleSince = 0;
        scheduler.execute(this::maintain);
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (RemoteCameraPreview.this) {
                viewers = Math.max(0, viewers - 1);
                if (viewers == 0) idleSince = System.nanoTime();
            }
        };
    }

    BufferedImage latestFrame() {
        if (desktopOnline.getAsBoolean()) return desktopFrame.get();
        synchronized (this) {
            return !closed && engine.isConnected() && System.nanoTime() - lastFrameNanos < TimeUnit.SECONDS.toNanos(12)
                    ? frame : null;
        }
    }

    private synchronized void maintain() {
        if (closed) return;
        boolean idle = viewers == 0 && (idleSince == 0 || System.nanoTime() - idleSince >= TimeUnit.SECONDS.toNanos(10));
        if (idle || desktopOnline.getAsBoolean()) {
            if (requested) { requested = false; connectedSettings = null; engine.disconnect(); }
            return;
        }
        StreamEngine.Settings next = settings.get();
        // Recording-only options belong to RecorderService. Changing the timestamp
        // overlay must not tear down an already healthy remote live preview.
        if (!requested || !samePreviewSettings(next, connectedSettings)) {
            connectedSettings = next; requested = true;
            engine.connect(next);
        }
    }

    private static boolean samePreviewSettings(StreamEngine.Settings first, StreamEngine.Settings second) {
        return first != null && second != null
                && first.url().equals(second.url())
                && first.transport().equals(second.transport())
                && first.quality() == second.quality();
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true; viewers = 0; frame = null;
        }
        scheduler.shutdownNow();
        engine.close();
    }
}
