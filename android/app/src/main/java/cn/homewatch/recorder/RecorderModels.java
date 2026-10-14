package cn.homewatch.recorder;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Wire data carries opaque IDs only. Camera passwords and filesystem paths never enter the App. */
final class RecorderModels {
    static final class Session {
        final String baseUrl, token, refreshToken, username, fingerprint;
        final Instant expiresAt;
        Session(String baseUrl, String token, String refreshToken, String username, Instant expiresAt, String fingerprint) {
            this.baseUrl = baseUrl; this.token = token; this.refreshToken = refreshToken; this.username = username; this.expiresAt = expiresAt; this.fingerprint = fingerprint;
        }
    }
    static final class Server {
        final Instant time;
        final ZoneId zone;
        final long usedBytes, maxBytes;
        final int keepDays;
        Server(Instant time, ZoneId zone, long usedBytes, long maxBytes, int keepDays) {
            this.time = time; this.zone = zone; this.usedBytes = usedBytes; this.maxBytes = maxBytes; this.keepDays = keepDays;
        }
    }
    static final class Camera {
        final String id, name;
        final int number;
        final boolean online, recording;
        Camera(String id, String name, int number, boolean online, boolean recording) {
            this.id = id; this.name = name; this.number = number; this.online = online; this.recording = recording;
        }
        String label() { return String.format(java.util.Locale.ROOT, "CH%02d  %s", number, name); }
        @Override public String toString() { return label(); }
    }
    static final class Person {
        final double fromSeconds, toSeconds;
        Person(double fromSeconds, double toSeconds) { this.fromSeconds = fromSeconds; this.toSeconds = toSeconds; }
    }
    static final class Recording {
        final String id, channelId, personStatus;
        final Instant start, end;
        final double duration;
        final boolean active;
        final List<Person> persons;
        Recording(String id, String channelId, Instant start, Instant end, double duration, boolean active,
                  List<Person> persons, String personStatus) {
            this.id = id; this.channelId = channelId; this.start = start; this.end = end;
            this.duration = duration; this.active = active;
            this.persons = Collections.unmodifiableList(new ArrayList<>(persons)); this.personStatus = personStatus;
        }
    }
    private RecorderModels() { }
}
