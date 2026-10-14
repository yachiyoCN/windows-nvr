package cn.homewatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Recording-only wall-clock watermark. Camera previews and existing files are unaffected. */
final class RecordingTimestamp {
    private RecordingTimestamp() {}

    static String filter() throws IOException {
        List<Path> fontDirectories = new ArrayList<>();
        for (String variable : List.of("WINDIR", "SystemRoot")) {
            String directory = System.getenv(variable);
            if (directory != null && !directory.isBlank()) fontDirectories.add(Path.of(directory).resolve("Fonts"));
        }
        for (Path directory : fontDirectories) {
            for (String name : List.of("consolab.ttf", "consola.ttf", "arial.ttf")) {
                Path font = directory.resolve(name).toAbsolutePath().normalize();
                if (Files.isRegularFile(font, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(font)) return filter(font);
            }
        }
        throw new UnavailableException("录像时间水印字体不可用，请检查 Windows 字体文件或关闭该摄像头的录像时间水印。");
    }

    static String filter(Path font) throws IOException {
        if (!Files.isRegularFile(font, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(font))
            throw new UnavailableException("录像时间水印字体不可用，请检查字体文件或关闭该摄像头的录像时间水印。");
        // drawtext localtime's documented default is exactly yyyy-MM-dd HH:mm:ss. Using
        // that default avoids injecting a strftime string through two colon-sensitive parsers.
        return "drawtext=fontfile=" + optionValue(font.toAbsolutePath().normalize().toString().replace('\\', '/'))
                + ":text='%{localtime}':expansion=normal:fontcolor=white:fontsize='max(16,h/30)'"
                + ":x=14:y=14:box=1:boxcolor=black@0.55:boxborderw=6";
    }

    private static String optionValue(String value) {
        // First escape an AVOption value; then escape the filter-graph grammar. No shell is used.
        StringBuilder option = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character == '\\' || character == '\'' || character == ':') option.append('\\');
            option.append(character);
        }
        StringBuilder graph = new StringBuilder();
        for (char character : option.toString().toCharArray()) {
            if (character == '\\' || character == '\'' || character == ',' || character == ';'
                    || character == '[' || character == ']') graph.append('\\');
            graph.append(character);
        }
        return graph.toString();
    }
    static final class UnavailableException extends IOException {
        UnavailableException(String message) { super(message); }
    }
}
