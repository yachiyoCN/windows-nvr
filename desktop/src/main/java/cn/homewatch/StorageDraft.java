package cn.homewatch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** One EDT-owned storage form draft shared by every camera's settings panel. */
final class StorageDraft {
    private final List<NvrSettingsPanel> views = new ArrayList<>();
    final AtomicBoolean busy = new AtomicBoolean();
    String directory, capacity, days, segment;
    boolean automatic, dirty;
    String notice = " ";
    boolean error;
    StorageDraft(Path root, RecorderSettings settings) { reset(root, settings); }
    void reset(Path root, RecorderSettings settings) {
        directory = settings.resolvedDirectory(root).toString();
        capacity = String.format(java.util.Locale.ROOT, "%.2f", settings.maxBytes() / (1024.0 * 1024 * 1024));
        days = Integer.toString(settings.keepDays());
        segment = Integer.toString(settings.segmentMinutes());
        automatic = settings.autoRecord(); dirty = false;
    }
    void add(NvrSettingsPanel view) { views.add(view); }
    void remove(NvrSettingsPanel view) { views.remove(view); }
    void publish(NvrSettingsPanel source) {
        for (NvrSettingsPanel view : List.copyOf(views)) if (view != source) view.applyStorageDraft();
    }
}
