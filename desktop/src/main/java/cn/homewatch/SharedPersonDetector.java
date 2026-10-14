package cn.homewatch;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Channels share one inference session. PersonDetector serializes its reusable input buffer. */
final class SharedPersonDetector {
    private static final Map<Path, Entry> sessions = new HashMap<>();
    private static final class Entry {
        final PersonDetector detector;
        int users = 1;
        Entry(PersonDetector detector) { this.detector = detector; }
    }
    static synchronized PersonDetector acquire(Path assetsRoot) throws Exception {
        Path key = assetsRoot.toAbsolutePath().normalize();
        Entry entry = sessions.get(key);
        if (entry == null) {
            entry = new Entry(new PersonDetector(key, 0.35f));
            sessions.put(key, entry);
        } else entry.users++;
        return entry.detector;
    }
    static synchronized void release(Path assetsRoot, PersonDetector detector) {
        Path key = assetsRoot.toAbsolutePath().normalize();
        Entry entry = sessions.get(key);
        if (entry != null && entry.detector == detector && --entry.users == 0) {
            sessions.remove(key);
            detector.close();
        }
    }
}
