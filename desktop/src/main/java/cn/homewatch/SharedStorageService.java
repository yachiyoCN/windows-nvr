package cn.homewatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** One recording budget and policy, with channel-owned files kept in their original locations. */
public final class SharedStorageService implements NvrStorage, AutoCloseable {
    private static final long MIN_FREE = 500L * 1024 * 1024;
    private final Path root;
    private final Path storageGuard;
    private final Object changes = new Object();
    private final ReentrantLock cleaning = new ReentrantLock();
    private final CopyOnWriteArrayList<RecorderService> recorders = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean cleanupQueued = new AtomicBoolean();
    private volatile RecorderSettings settings;
    private volatile boolean changing, closed;

    public SharedStorageService(Path applicationRoot) throws IOException {
        root = applicationRoot.toAbsolutePath().normalize();
        storageGuard = root.resolve("tools/StorageGuard.exe");
        settings = normalized(RecorderSettings.load(root));
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nvr-shared-storage"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            if (!closed) for (RecorderService recorder : recorders) safely(recorder::refresh);
        }, 2, 2, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::requestCleanup, 30, 30, TimeUnit.SECONDS);
    }
    public void register(RecorderService recorder) {
        synchronized (changes) {
            if (closed) throw new IllegalStateException("录像存储已关闭。");
            recorders.addIfAbsent(recorder);
        }
    }
    public void unregister(RecorderService recorder) { synchronized (changes) { recorders.remove(recorder); } }
    public List<RecorderService> recorders() { return List.copyOf(recorders); }
    @Override public RecorderSettings settings() { return settings; }
    @Override public long usedBytes() {
        long total = 0;
        for (RecorderService recorder : recorders) total = saturatingAdd(total, recorder.usedBytes());
        return total;
    }
    @Override public boolean isRequested() { return recorders.stream().anyMatch(RecorderService::isRequested); }
    @Override public boolean isRecording() { return recorders.stream().anyMatch(RecorderService::isRecording); }
    public List<RecordingEntry> listRecordings() {
        return recorders.stream().flatMap(recorder -> recorder.listRecordings().stream())
                .sorted(Comparator.comparing(RecordingEntry::start).reversed()).toList();
    }

    /** Off EDT: validate, seal all open segments, save once, then resume every prior recording intent. */
    @Override public void applySettings(RecorderSettings requested) throws IOException {
        RecorderSettings next = normalized(requested);
        synchronized (changes) {
            if (closed) throw new IOException("录像存储已关闭。");
            validateDirectory(next.resolvedDirectory(root));
            changing = true;
            // Drain a cleanup already in flight without holding its lock while a recorder finalizes.
            cleaning.lock(); cleaning.unlock();
            List<RecorderService> participants = List.copyOf(recorders);
            try {
                for (RecorderService recorder : participants) recorder.beginSharedSettingsChange();
                for (RecorderService recorder : participants) recorder.awaitSharedSettingsChange();
                next.save(root);
                settings = next;
            } finally {
                for (RecorderService recorder : participants) recorder.endSharedSettingsChange();
                changing = false;
            }
        }
        cleanupNow();
    }

    /** Safe to call after an index lock is released; coalesces background boundary-triggered cleanup. */
    public void requestCleanup() {
        if (closed || changing || !cleanupQueued.compareAndSet(false, true)) return;
        try { scheduler.execute(() -> { try { cleanupNow(); } finally { cleanupQueued.set(false); } }); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { cleanupQueued.set(false); }
    }
    @Override public void cleanupNow() {
        if (closed || changing) return;
        cleaning.lock();
        try {
            if (closed || changing) return;
            RecorderSettings policy = settings;
            List<RecorderService.RetentionCandidate> candidates = new ArrayList<>();
            for (RecorderService recorder : recorders) candidates.addAll(recorder.retentionCandidates());
            candidates.sort(Comparator.comparing(RecorderService.RetentionCandidate::start)
                    .thenComparing(candidate -> candidate.path().toString()));
            long total = usedBytes();
            Instant cutoff = Instant.now().minusSeconds(policy.keepDays() * 86400L);
            Path directory = policy.resolvedDirectory(root);
            long available = freeBytes(directory);
            for (RecorderService.RetentionCandidate candidate : candidates) {
                boolean expired = candidate.start().isBefore(cutoff);
                boolean exceeded = total > policy.maxBytes();
                boolean full = available < MIN_FREE && sameFileStore(candidate.path(), directory);
                if (!expired && !exceeded && available >= MIN_FREE) break;
                if (!expired && !exceeded && !full) continue;
                long removed = candidate.owner().deleteOwned(candidate);
                if (removed >= 0) {
                    total = Math.max(0, total - removed);
                    if (available < MIN_FREE) available = freeBytes(directory);
                }
            }
        } finally { cleaning.unlock(); }
    }

    private RecorderSettings normalized(RecorderSettings policy) {
        return new RecorderSettings(policy.resolvedDirectory(root).toString(), policy.maxBytes(),
                policy.keepDays(), policy.segmentMinutes(), policy.autoRecord());
    }
    private void validateDirectory(Path directory) throws IOException {
        Path existing = directory;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || Files.isSymbolicLink(existing)
                || !StorageDirectoryGuard.inspect(storageGuard, existing).real().equals(existing))
            throw new IOException("保存目录不能使用符号链接或目录联接。");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || (!directory.equals(existing)
                && !StorageDirectoryGuard.inspect(storageGuard, directory).real().equals(directory)))
            throw new IOException("保存目录不能使用符号链接或目录联接。");
        Path probe = Files.createTempFile(directory, ".homewatch-write-", ".tmp"); Files.delete(probe);
    }
    private long freeBytes(Path directory) {
        try { Path existing = directory; while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            return existing == null ? 0 : Files.getFileStore(existing).getUsableSpace();
        } catch (IOException | RuntimeException inaccessible) { return 0; }
    }
    private boolean sameFileStore(Path file, Path directory) {
        try { Path existing = directory; while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            return existing != null && Files.getFileStore(file).equals(Files.getFileStore(existing));
        } catch (IOException | RuntimeException inaccessible) { return false; }
    }
    private static long saturatingAdd(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
    private static void safely(Runnable work) { try { work.run(); } catch (RuntimeException ignored) {} }
    @Override public void close() {
        synchronized (changes) { if (closed) return; closed = true; }
        scheduler.shutdownNow();
        // The controller closes channel recorders; this coordinator never owns their stream lifecycle.
    }
}
