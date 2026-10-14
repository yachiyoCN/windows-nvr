package cn.homewatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Confirms the same model-predicted person over time before colouring the recording timeline. */
final class PersonPresenceTracker {
    private static final double MATCH_GAP = 0.76;
    private final double sampleInterval;
    private final List<Track> live = new ArrayList<>();
    private final List<PersonInterval> complete = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private double lastObservation = Double.NEGATIVE_INFINITY;
    record Candidate(double seconds, PersonDetector.PersonBox box, boolean monochrome) {}

    PersonPresenceTracker(double sampleInterval) {
        if (!Double.isFinite(sampleInterval) || sampleInterval <= 0) throw new IllegalArgumentException("sample interval");
        this.sampleInterval = sampleInterval;
    }

    boolean needsConfirmation() {
        return live.stream().anyMatch(track -> !track.confirmed && track.evidence.size() < 3);
    }

    void observe(double seconds, PersonDetector.DetectionResult result, boolean inferred) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds <= lastObservation + .001 || result == null) return;
        lastObservation = seconds;
        for (int i = live.size() - 1; i >= 0; i--) {
            Track old = live.get(i);
            if (seconds - old.last > MATCH_GAP) { finish(old); live.remove(i); }
        }
        boolean[] matched = new boolean[live.size()];
        for (PersonDetector.PersonBox box : result.boxes()) {
            if (!valid(box)) continue;
            int best = -1;
            double similarity = 0;
            for (int i = 0; i < matched.length; i++) {
                if (matched[i]) continue;
                double value = match(live.get(i).box, box);
                if (value > similarity) { similarity = value; best = i; }
            }
            Track track;
            if (best < 0) { track = new Track(seconds, box, result.monochrome()); live.add(track); }
            else { matched[best] = true; track = live.get(best); }
            double priorLast = track.last;
            track.box = box;
            track.last = seconds;
            track.monochrome |= result.monochrome();
            if (inferred && seconds > track.lastInference + 0.035) {
                track.lastInference = seconds;
                track.evidence.add(new Evidence(seconds, box.score(), box.strong()));
                if (track.evidence.size() > 3) track.evidence.remove(0);
            }
            // Repetition of a low-confidence false candidate alone never becomes a person event.
            double mean = track.evidence.stream().mapToDouble(Evidence::score).average().orElse(0);
            double maxScore = track.evidence.stream().mapToDouble(Evidence::score).max().orElse(0);
            double span = track.evidence.size() < 2 ? 0
                    : track.evidence.getLast().time - track.evidence.getFirst().time;
            double pairMean = track.monochrome ? 0.65 : 0.58;
            double tripleMean = track.monochrome ? 0.58 : 0.52;
            double peak = track.monochrome ? 0.74 : 0.66;
            boolean supported = span >= .06 && (track.evidence.size() >= 2 && mean >= pairMean && maxScore >= peak
                    || track.evidence.size() >= 3 && mean >= tripleMean && maxScore >= (track.monochrome ? 0.66 : 0.58));
            // Once real independent frames confirm a person, tolerate a modest confidence dip
            // without splitting the event. This does not promote a weak new object track.
            if (track.confirmed && track.evidence.size() >= 2 && span >= .06
                    && mean >= (track.monochrome ? .52 : .46) && maxScore >= (track.monochrome ? .58 : .52)) supported = true;
            if (supported) {
                if (!track.confirmed) track.first = track.evidence.get(0).time;
                track.confirmed = true;
            } else if (track.confirmed && inferred) {
                track.last = priorLast;
                finish(track);
                track.last = seconds;
                track.first = seconds;
                track.confirmed = false;
            }
        }
    }

    List<Candidate> isolatedCandidates() {
        live.forEach(this::finish);
        live.clear();
        return List.copyOf(candidates);
    }

    void addVerified(List<PersonInterval> verified) { complete.addAll(verified); }

    List<PersonInterval> finish(double duration) {
        live.forEach(this::finish);
        live.clear();
        complete.sort(Comparator.comparingDouble(PersonInterval::startSeconds));
        List<PersonInterval> merged = new ArrayList<>();
        for (PersonInterval interval : complete) {
            double start = Math.min(duration, interval.startSeconds());
            double end = Math.min(duration, interval.endSeconds());
            if (end <= start) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1).endSeconds() >= start) {
                PersonInterval previous = merged.remove(merged.size() - 1);
                start = previous.startSeconds(); end = Math.max(end, previous.endSeconds());
            }
            merged.add(new PersonInterval(start, end));
        }
        return List.copyOf(merged);
    }

    private void finish(Track track) {
        if (!track.confirmed) {
            if (track.evidence.size() == 1 && candidates.size() < 64) {
                Evidence evidence = track.evidence.get(0);
                if (evidence.score >= 0.35f)
                    candidates.add(new Candidate(evidence.time, track.box, track.monochrome));
            }
            return;
        }
        complete.add(new PersonInterval(Math.max(0, track.first - sampleInterval * 0.5),
                track.last + sampleInterval));
    }

    private static boolean valid(PersonDetector.PersonBox box) {
        return Float.isFinite(box.score()) && box.score() >= 0.35f && box.score() <= 1
                && Double.isFinite(box.x()) && Double.isFinite(box.y()) && Double.isFinite(box.width()) && Double.isFinite(box.height())
                && box.x() >= 0 && box.y() >= 0 && box.width() > 0 && box.height() > 0
                && box.x() + box.width() <= 1.000001 && box.y() + box.height() <= 1.000001;
    }

    private static double match(PersonDetector.PersonBox a, PersonDetector.PersonBox b) {
        double overlapWidth = Math.max(0, Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x()));
        double overlapHeight = Math.max(0, Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y()));
        double intersection = overlapWidth * overlapHeight;
        double union = a.width() * a.height() + b.width() * b.height() - intersection;
        double iou = union > 0 ? intersection / union : 0;
        double ratio = Math.max(a.width() * a.height(), b.width() * b.height())
                / Math.max(1e-6, Math.min(a.width() * a.height(), b.width() * b.height()));
        if (ratio > 3 || Math.max(a.height(), b.height()) / Math.min(a.height(), b.height()) > 2) return 0;
        if (iou >= 0.12) return iou + 1;
        double dx = a.x() + a.width() * 0.5 - b.x() - b.width() * 0.5;
        double dy = a.y() + a.height() * 0.5 - b.y() - b.height() * 0.5;
        double tolerance = Math.max(0.045, Math.max(a.height(), b.height()) * 0.50);
        double distance = Math.hypot(dx, dy);
        return distance < tolerance ? 1 - distance / tolerance : 0;
    }

    private static final class Track {
        double first;
        double last, lastInference = Double.NEGATIVE_INFINITY;
        final List<Evidence> evidence = new ArrayList<>(3);
        boolean confirmed, monochrome;
        PersonDetector.PersonBox box;
        Track(double time, PersonDetector.PersonBox box, boolean monochrome) {
            first = last = time; this.box = box; this.monochrome = monochrome;
        }
    }
    private record Evidence(double time, float score, boolean strong) {}
}
