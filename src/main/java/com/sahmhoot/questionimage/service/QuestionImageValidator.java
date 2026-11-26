package com.sahmhoot.questionimage.service;

import static com.sahmhoot.questionimage.storage.ImageStorage.MAX_BYTES;

import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class QuestionImageValidator {
  private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
  private static final int PNG_END = 0x49454e44;
  private static final int MAX_STORED_EDGE = 1920;

  public record ValidatedImage(UUID key, String mimeType, int width, int height, byte[] content) {}

  // Bound peak decoding memory across concurrent calls on the application bean.
  public synchronized ValidatedImage validate(InputStream source, String declaredMimeType) throws IOException {
    byte[] input = source.readNBytes(MAX_BYTES + 1);
    if (input.length > MAX_BYTES) {
      throw new InvalidImageException(InvalidImageException.Reason.TOO_LARGE);
    }
    if (input.length < 8) {
      throw new InvalidImageException();
    }
    boolean pngMime = "image/png".equalsIgnoreCase(declaredMimeType);
    boolean jpegMime = "image/jpeg".equalsIgnoreCase(declaredMimeType);
    if (!pngMime && !jpegMime) {
      throw new InvalidImageException(InvalidImageException.Reason.UNSUPPORTED);
    }
    boolean png = Arrays.equals(input, 0, 8, PNG_SIGNATURE, 0, 8);
    boolean jpeg = (input[0] & 255) == 255 && (input[1] & 255) == 216;
    if (png != pngMime || jpeg != jpegMime) {
      throw new InvalidImageException(InvalidImageException.Reason.UNSUPPORTED);
    }
    byte[] sanitized = png ? sanitizePng(input) : input;
    String format = png ? "png" : "jpeg";
    var reader = ImageIO.getImageReadersByFormatName(format).next();
    try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(sanitized))) {
      reader.setInput(stream, true, true);
      // The JPEG reader detects a missing EOI. Valid JPEGs can have data after that marker.
      reader.addIIOReadWarningListener((ignored, warning) -> {
        throw new InvalidImageException();
      });
      int width = reader.getWidth(0);
      int height = reader.getHeight(0);
      if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || (long) width * height > 16_000_000) {
        throw new InvalidImageException(InvalidImageException.Reason.DIMENSIONS);
      }
      BufferedImage decoded = reader.read(0);
      BufferedImage oriented = png ? decoded : JpegExifOrientation.apply(decoded, input);
      if (oriented != decoded) {
        decoded.flush();
      }
      BufferedImage clean = downscale(oriented);
      if (clean != oriented) {
        oriented.flush();
      }
      var encoded = new BoundedImageOutput();
      try (var output = new MemoryCacheImageOutputStream(encoded)) {
        ImageIO.write(clean, format, output);
      } finally {
        clean.flush();
      }
      if (encoded.bytes.size() == 0) {
        throw new InvalidImageException();
      }
      return new ValidatedImage(UUID.randomUUID(), "image/" + format, clean.getWidth(), clean.getHeight(),
          encoded.bytes.toByteArray());
    } catch (IOException e) {
      throw new InvalidImageException();
    } finally {
      reader.dispose();
    }
  }

  private BufferedImage downscale(BufferedImage source) {
    int longestEdge = Math.max(source.getWidth(), source.getHeight());
    if (longestEdge <= MAX_STORED_EDGE) {
      return source;
    }
    double scale = (double) MAX_STORED_EDGE / longestEdge;
    int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
    // Retain the PNG color model so grayscale and indexed images stay compact.
    var model = source.getColorModel();
    var resized = new BufferedImage(model, model.createCompatibleWritableRaster(width, height),
        model.isAlphaPremultiplied(), null);
    var graphics = resized.createGraphics();
    try {
      graphics.setComposite(AlphaComposite.Src);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, model instanceof IndexColorModel
          ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return resized;
  }

  private byte[] sanitizePng(byte[] input) {
    var bytes = ByteBuffer.wrap(input);
    bytes.position(PNG_SIGNATURE.length);
    var crc = new CRC32();
    var sanitized = new ByteArrayOutputStream();
    sanitized.writeBytes(PNG_SIGNATURE);
    while (bytes.remaining() >= 12) {
      int chunkOffset = bytes.position();
      int length = bytes.getInt();
      int typeOffset = bytes.position();
      int type = bytes.getInt();
      if (length < 0 || length > bytes.remaining() - 4) {
        break;
      }
      crc.reset();
      crc.update(input, typeOffset, length + 4);
      bytes.position(bytes.position() + length);
      if ((int) crc.getValue() != bytes.getInt()) {
        break;
      }
      // Preserve rendering chunks without passing compressed metadata to the decoder.
      if ((type & 0x20000000) == 0 || type == 0x74524e53) {
        sanitized.write(input, chunkOffset, length + 12);
      }
      if (type == PNG_END) {
        if (length == 0 && !bytes.hasRemaining()) {
          return sanitized.toByteArray();
        }
        break;
      }
    }
    throw new InvalidImageException();
  }

  private static final class BoundedImageOutput extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    public void write(int value) throws IOException {
      checkCapacity(1);
      bytes.write(value);
    }

    @Override
    public void write(byte[] content, int offset, int length) throws IOException {
      checkCapacity(length);
      bytes.write(content, offset, length);
    }

    private void checkCapacity(int length) throws IOException {
      if (length > MAX_BYTES - bytes.size()) {
        throw new InvalidImageException(InvalidImageException.Reason.TOO_LARGE);
      }
    }
  }
}
