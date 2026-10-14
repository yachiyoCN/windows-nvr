import cn.homewatch.AppIdentity;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Compile beside AppIdentity; no external graphics library or download is required. */
public final class GenerateAppIcon {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected assets and resources directories");
        Path assets = Path.of(arguments[0]);
        Path resources = Path.of(arguments[1]).resolve("cn/homewatch/icons");
        Files.createDirectories(assets); Files.createDirectories(resources);
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(AppIdentity.drawIcon(size), "png", png);
            byte[] bytes = png.toByteArray(); images.add(bytes);
            Files.write(resources.resolve("recorder-" + size + ".png"), bytes);
            if (size == 256) Files.write(assets.resolve("recorder.png"), bytes);
        }
        int length = 6 + sizes.length * 16 + images.stream().mapToInt(png -> png.length).sum();
        ByteBuffer ico = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + sizes.length * 16;
        for (int index = 0; index < sizes.length; index++) {
            int dimension = sizes[index] == 256 ? 0 : sizes[index];
            ico.put((byte) dimension).put((byte) dimension).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32).putInt(images.get(index).length).putInt(offset);
            offset += images.get(index).length;
        }
        for (byte[] png : images) ico.put(png);
        Files.write(assets.resolve("recorder.ico"), ico.array());
    }
}
