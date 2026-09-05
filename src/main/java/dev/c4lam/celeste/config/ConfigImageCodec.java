package dev.c4lam.celeste.config;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Packs a Celeste config payload into a PNG. The later VPS cloud flow hosts
 * these images; locally they live in the configured cloud folder.
 */
public final class ConfigImageCodec {
    private static final byte[] MAGIC = "CELESTE1".getBytes(StandardCharsets.US_ASCII);
    private static final int MIN_SIZE = 64;

    private ConfigImageCodec() {
    }

    public static void write(Path png, byte[] payload) throws IOException {
        if (payload == null) {
            throw new IOException("Config payload is empty");
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        int total = MAGIC.length + 4 + payload.length + 4;
        int pixels = Math.max(MIN_SIZE * MIN_SIZE, (total + 2) / 3);
        int size = Math.max(MIN_SIZE, (int) Math.ceil(Math.sqrt(pixels)));
        byte[] packed = new byte[size * size * 3];
        ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.putInt((int) crc.getValue());
        byte[] header = buffer.array();
        System.arraycopy(header, 0, packed, 0, header.length);
        for (int fill = header.length; fill + 2 < packed.length; fill += 3) {
            int pixel = fill / 3;
            int x = pixel % size;
            int y = pixel / size;
            packed[fill] = (byte) 255;
            packed[fill + 1] = (byte) (43 + (x * 3 + y) % 80);
            packed[fill + 2] = (byte) (143 + (x + y * 5) % 70);
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int index = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int red = packed[index++] & 0xFF;
                int green = packed[index++] & 0xFF;
                int blue = packed[index++] & 0xFF;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ImageIO.write(image, "png", png.toFile());
    }

    public static byte[] read(Path png) throws IOException {
        BufferedImage image = ImageIO.read(png.toFile());
        if (image == null) {
            throw new IOException("Not a readable PNG: " + png);
        }
        return read(image);
    }

    public static byte[] read(byte[] png) throws IOException {
        if (png == null || png.length == 0) {
            throw new IOException("Cloud config image is empty");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("Cloud config is not a readable PNG");
        }
        return read(image);
    }

    private static byte[] read(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] packed = new byte[width * height * 3];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                packed[index++] = (byte) ((rgb >> 16) & 0xFF);
                packed[index++] = (byte) ((rgb >> 8) & 0xFF);
                packed[index++] = (byte) (rgb & 0xFF);
            }
        }
        if (packed.length < MAGIC.length + 8) {
            throw new IOException("PNG is too small to hold a Celeste config");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (packed[i] != MAGIC[i]) {
                throw new IOException("PNG is not a Celeste cloud config");
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN);
        buffer.position(MAGIC.length);
        int length = buffer.getInt();
        if (length < 0 || MAGIC.length + 8 + length > packed.length) {
            throw new IOException("Cloud config payload is truncated");
        }
        byte[] payload = new byte[length];
        buffer.get(payload);
        int expected = buffer.getInt();
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (expected != (int) crc.getValue()) {
            throw new IOException("Cloud config checksum failed");
        }
        return payload;
    }
}
