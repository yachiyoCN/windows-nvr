package cn.homewatch;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

/** Recording settings, separate from the camera credentials. */
public record RecorderSettings(String directory, long maxBytes, int keepDays,
                               int segmentMinutes, boolean autoRecord) {
    public RecorderSettings {
        directory = Objects.requireNonNullElse(directory, "").strip();
        if (directory.isBlank()) throw new IllegalArgumentException("请选择录像保存目录。");
        try { Path.of(directory); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("录像保存目录无效。"); }
        if (maxBytes < 256L * 1024 * 1024)
            throw new IllegalArgumentException("最大占用至少为 256 MB。");
        if (keepDays < 1 || keepDays > 36500)
            throw new IllegalArgumentException("保存天数必须在 1 至 36500 天之间。");
        if (segmentMinutes < 1 || segmentMinutes > 60)
            throw new IllegalArgumentException("每段时长必须在 1 至 60 分钟之间。");
    }

    public static RecorderSettings defaults(Path root) {
        return new RecorderSettings(root.toAbsolutePath().normalize()
                .resolve("data/recordings").toString(), 20L * 1024 * 1024 * 1024, 7, 5, false);
    }

    public Path resolvedDirectory(Path root) {
        Path path = Path.of(directory);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    public static RecorderSettings load(Path root) throws IOException {
        RecorderSettings fallback = defaults(root);
        Path file = root.resolve("config/recorder.properties");
        if (!Files.exists(file)) return fallback;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        try {
            return new RecorderSettings(values.getProperty("directory", fallback.directory()),
                    Long.parseLong(values.getProperty("maxBytes", Long.toString(fallback.maxBytes()))),
                    Integer.parseInt(values.getProperty("keepDays", "7")),
                    Integer.parseInt(values.getProperty("segmentMinutes", "5")),
                    Boolean.parseBoolean(values.getProperty("autoRecord", "false")));
        } catch (IllegalArgumentException failure) {
            throw new IOException("录像设置无效，请重新保存录像设置。", failure);
        }
    }

    public void save(Path root) throws IOException {
        Properties values = new Properties();
        values.setProperty("directory", resolvedDirectory(root).toString());
        values.setProperty("maxBytes", Long.toString(maxBytes));
        values.setProperty("keepDays", Integer.toString(keepDays));
        values.setProperty("segmentMinutes", Integer.toString(segmentMinutes));
        values.setProperty("autoRecord", Boolean.toString(autoRecord));
        Path file = root.resolve("config/recorder.properties");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "recorder-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "HomeWatch recording settings");
            }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
