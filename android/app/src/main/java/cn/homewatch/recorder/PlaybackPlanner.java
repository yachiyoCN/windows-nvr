package cn.homewatch.recorder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects continuous playback windows in absolute time, constrained to the requested query range. */
final class PlaybackPlanner {
    static final class Window {
        final RecorderModels.Recording recording;
        final Instant start, end;
        final double offsetSeconds, durationSeconds;
        Window(RecorderModels.Recording r, Instant start, Instant end) {
            this.recording = r; this.start = start; this.end = end;
            this.offsetSeconds = seconds(r.start, start); this.durationSeconds = seconds(start, end);
        }
    }
    private List<RecorderModels.Recording> recordings = new ArrayList<>();
    private Instant from = Instant.EPOCH, to = Instant.EPOCH.plusSeconds(1);

    void update(List<RecorderModels.Recording> list, Instant from, Instant to) {
        if (!to.isAfter(from)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
        this.recordings = new ArrayList<>(list); this.recordings.sort(Comparator.comparing(r -> r.start));
        this.from = from; this.to = to;
    }
    List<RecorderModels.Recording> recordings() { return java.util.Collections.unmodifiableList(recordings); }
    Instant from() { return from; }
    Instant to() { return to; }
    Instant clamp(Instant value) {
        if (value.isBefore(from)) return from;
        if (!value.isBefore(to)) return to.minusMillis(1);
        return value;
    }
    Window atOrAfter(Instant time) {
        Instant wanted = time.isBefore(from) ? from : time;
        if (!wanted.isBefore(to)) return null;
        for (RecorderModels.Recording r : recordings) {
            if (r.duration <= 0 || !r.end.isAfter(wanted) || !r.start.isBefore(to)) continue;
            Instant start = wanted.isAfter(r.start) ? wanted : r.start;
            if (start.isBefore(from)) start = from;
            Instant end = minimum(r.end, minimum(to, start.plusSeconds(300)));
            if (end.isAfter(start)) return new Window(r, start, end);
        }
        return null;
    }
    List<Instant> personStarts() {
        List<Instant> result = new ArrayList<>();
        for (RecorderModels.Recording r : recordings) for (RecorderModels.Person p : r.persons) {
            Instant start = r.start.plusMillis(Math.round(p.fromSeconds * 1000));
            Instant end = r.start.plusMillis(Math.round(p.toSeconds * 1000));
            if (!end.isAfter(start) || !end.isAfter(from) || !start.isBefore(to) || !start.isBefore(r.end)) continue;
            start = maximum(start, maximum(from, r.start));
            if (!result.contains(start)) result.add(start);
        }
        result.sort(Comparator.naturalOrder()); return result;
    }
    Instant firstPersonAfter(Instant time) { for (Instant start : personStarts()) if (start.isAfter(time.plusMillis(100))) return start; return null; }
    Instant personBefore(Instant time) { List<Instant> values = personStarts(); for (int i = values.size() - 1; i >= 0; i--) if (values.get(i).isBefore(time.minusMillis(100))) return values.get(i); return null; }
    Instant recordingStart(Instant time, boolean next) {
        if (next) { for (RecorderModels.Recording r : recordings) { Instant start = maximum(r.start, from); if (r.duration > 0 && start.isAfter(time.plusMillis(100)) && start.isBefore(to)) return start; } }
        else { for (int i = recordings.size() - 1; i >= 0; i--) { RecorderModels.Recording r = recordings.get(i); Instant start = maximum(r.start, from); if (r.duration > 0 && start.isBefore(time.minusMillis(100)) && r.end.isAfter(from) && start.isBefore(to)) return start; } }
        return null;
    }
    Instant atOrBefore(Instant time) {
        Instant wanted = clamp(time), found = null;
        for (RecorderModels.Recording r : recordings) {
            Instant start = maximum(r.start, from), end = minimum(r.end, to);
            if (r.duration <= 0 || !end.isAfter(start) || start.isAfter(wanted)) continue;
            Instant candidate = wanted.isBefore(end) ? wanted : maximum(start, end.minusSeconds(1));
            if (found == null || candidate.isAfter(found)) found = candidate;
        }
        return found;
    }
    private static Instant maximum(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    static double seconds(Instant start, Instant end) { return Duration.between(start, end).toMillis() / 1000.0; }
    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
