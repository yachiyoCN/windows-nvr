package cn.homewatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Resolves known directory metadata without enumerating unrelated ancestor folders. */
final class StorageDirectoryGuard {
    record Proof(Path real, String identity) {}

    static Proof inspect(Path helper, Path requested) throws IOException {
        Path directory = requested.toAbsolutePath().normalize();
        try {
            Path real = directory.toRealPath();
            if (!real.equals(directory)) throw new IOException("保存目录不能使用目录联接或符号链接。");
            BasicFileAttributes noFollow = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!noFollow.isDirectory() || noFollow.isSymbolicLink()) throw new IOException("保存目录无效。");
            if (noFollow.fileKey() != null) {
                for (Path parent = directory; parent != null; parent = parent.getParent()) {
                    BasicFileAttributes attributes = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || !attributes.isDirectory())
                        throw new IOException("保存目录不能使用符号链接。");
                    Object followed = Files.readAttributes(parent, BasicFileAttributes.class).fileKey();
                    if (attributes.fileKey() == null || !attributes.fileKey().equals(followed))
                        throw new IOException("无法验证保存目录的真实归属。");
                }
                return new Proof(real, "java:" + noFollow.fileKey());
            }
            // Windows does not expose a BasicFileAttributes fileKey. Use its directory handle ID.
        } catch (AccessDeniedException canonicalizationRestricted) {
            // Attribute reads are allowed for this exact directory; JDK canonicalization enumerates
            // ancestors. The helper instead reads the canonical name from an already-open handle.
        }
        if (!Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(helper))
            throw new IOException("缺少目录安全校验组件 StorageGuard.exe。");
        Process process = new ProcessBuilder(helper.toString(), directory.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) throw new IOException("目录安全校验超时。");
            String result = new String(process.getInputStream().readNBytes(8192), StandardCharsets.US_ASCII);
            String[] lines = result.strip().split("\\R");
            if (process.exitValue() != 0 || lines.length != 3 || !"OK".equals(lines[0])
                    || !lines[1].matches("[0-9A-F]{8}:[0-9A-F]{16}"))
                throw new IOException("保存目录无法验证，或包含符号链接／目录联接。");
            Path real;
            try {
                real = Path.of(new String(Base64.getDecoder().decode(lines[2]), StandardCharsets.UTF_8))
                        .toAbsolutePath().normalize();
            } catch (RuntimeException invalid) { throw new IOException("目录安全校验结果无效。"); }
            if (!real.equals(directory)) throw new IOException("保存目录的实际位置不一致。");
            return new Proof(real, "win:" + lines[1]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("目录安全校验已中断。", interrupted);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }
}
