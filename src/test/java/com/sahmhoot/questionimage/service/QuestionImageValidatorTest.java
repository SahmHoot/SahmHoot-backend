package com.sahmhoot.questionimage.service;

import static com.sahmhoot.questionimage.storage.ImageStorage.MAX_BYTES;
import static org.assertj.core.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionImageValidatorTest {
  private final QuestionImageValidator validator = new QuestionImageValidator();

  @Test
  void validatesAndReencodesJpegAndPng() throws Exception {
    for (String format : new String[] {"jpeg", "png"}) {
      byte[] input = image(format, 12, 8);
      var result = validator.validate(new ByteArrayInputStream(input), "image/" + format);
      assertThat(result.mimeType()).isEqualTo("image/" + format);
      assertThat(result.width()).isEqualTo(12);
      assertThat(result.height()).isEqualTo(8);
      assertThat(ImageIO.read(new ByteArrayInputStream(result.content())).getWidth()).isEqualTo(12);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesCompactPngColorsAndTransparency(boolean indexed) throws Exception {
    BufferedImage original;
    if (indexed) {
      int[] colors = new Random(121).ints(16).map(rgb -> rgb | 0xff000000).toArray();
      colors[0] = 0;
      var palette = new IndexColorModel(4, 16, colors, 0, true, -1, DataBuffer.TYPE_BYTE);
      var raster = Raster.createPackedRaster(DataBuffer.TYPE_BYTE, 3000, 3000, 1, 4, null);
      original = new BufferedImage(palette, raster, false, null);
    } else {
      original = new BufferedImage(2000, 2000, BufferedImage.TYPE_BYTE_GRAY);
    }
    new Random(130).nextBytes(((DataBufferByte) original.getRaster().getDataBuffer()).getData());
    for (int y = 0; y < 12; y++) {
      for (int x = 0; x < 20; x++) {
        original.getRaster().setSample(x, y, 0, x < 6 ? 0 : 15);
      }
    }
    byte[] input = encode(original, "png");
    assertThat(input.length).isBetween(3 * 1024 * 1024, MAX_BYTES);

    var result = validator.validate(new ByteArrayInputStream(input), "image/png");
    var saved = ImageIO.read(new ByteArrayInputStream(result.content()));
    assertThat(result.width()).isEqualTo(1920);
    assertThat(result.height()).isEqualTo(1920);
    assertThat(saved.getWidth()).isEqualTo(result.width());
    assertThat(saved.getHeight()).isEqualTo(result.height());
    assertThat(result.content().length).isLessThanOrEqualTo(MAX_BYTES);
    assertThat(saved.getColorModel().hasAlpha()).isEqualTo(indexed);
    assertThat(saved.getRaster().getNumBands()).isEqualTo(1);
    assertThat(saved.getRGB(0, 0)).isEqualTo(original.getRGB(0, 0));
    assertThat(saved.getRGB(7, 2)).isEqualTo(original.getRGB(8, 2));
  }

  @Test
  void downscalesTransparentLandscapePngWithoutLosingAlpha() throws Exception {
    var original = new BufferedImage(2400, 1200, BufferedImage.TYPE_INT_ARGB);
    var graphics = original.createGraphics();
    graphics.setColor(new Color(11, 22, 33, 128));
    graphics.fillRect(100, 100, 100, 100);
    graphics.dispose();
    var result = validator.validate(new ByteArrayInputStream(encode(original, "png")), "image/png");
    var saved = ImageIO.read(new ByteArrayInputStream(result.content()));
    assertThat(result.width()).isEqualTo(1920);
    assertThat(result.height()).isEqualTo(960);
    assertThat(saved.getWidth()).isEqualTo(result.width());
    assertThat(saved.getHeight()).isEqualTo(result.height());
    assertThat(saved.getRGB(10, 10)).isZero();
    assertThat(saved.getRGB(100, 100) >>> 24).isEqualTo(128);
  }

  @Test
  void normalizesReferencePortraitAndDiscardsBytesAfterJpegEnd() throws Exception {
    byte[] original;
    // Portrait_6.jpg by John Salvino, from recurser/exif-orientation-examples (see fixture LICENSE).
    try (var sample = getClass().getResourceAsStream("/question-images/portrait-exif-6.jpg")) {
      original = sample.readAllBytes();
    }
    byte[] trailer = "trailing-camera-data".getBytes(StandardCharsets.US_ASCII);
    byte[] input = ByteBuffer.allocate(original.length + trailer.length).put(original).put(trailer).array();

    var result = validator.validate(new ByteArrayInputStream(input), "image/jpeg");
    var saved = ImageIO.read(new ByteArrayInputStream(result.content()));
    assertThat(result.mimeType()).isEqualTo("image/jpeg");
    assertThat(result.width()).isEqualTo(1200);
    assertThat(result.height()).isEqualTo(1800);
    assertThat(saved.getWidth()).isEqualTo(result.width());
    assertThat(saved.getHeight()).isEqualTo(result.height());
    assertThat(new String(result.content(), StandardCharsets.ISO_8859_1))
        .doesNotContain("Exif\u0000\u0000", "trailing-camera-data");
    assertThat(result.content()).endsWith((byte) 0xff, (byte) 0xd9);
    assertRejected(ByteBuffer.allocate(original.length - 2 + trailer.length)
        .put(original, 0, original.length - 2).put(trailer).array(), "image/jpeg");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  void appliesExifOrientationToPixels(int orientation) throws Exception {
    int inputWidth = orientation == 6 ? 2400 : 96;
    int inputHeight = orientation == 6 ? 1600 : 64;
    var original = new BufferedImage(inputWidth, inputHeight, BufferedImage.TYPE_INT_RGB);
    int halfWidth = inputWidth / 2;
    int halfHeight = inputHeight / 2;
    Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
    var g = original.createGraphics();
    for (int i = 0; i < colors.length; i++) {
      g.setColor(colors[i]);
      g.fillRect(i % 2 * halfWidth, i / 2 * halfHeight, halfWidth, halfHeight);
    }
    g.dispose();
    var order = orientation % 2 == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    byte[] input = withExif(encode(original, "jpeg"), orientation, order, 8);
    var result = validator.validate(new ByteArrayInputStream(input), "image/jpeg");
    var saved = ImageIO.read(new ByteArrayInputStream(result.content()));
    int[] corners = switch (orientation) {
      case 2 -> new int[] {1, 0, 3, 2};
      case 3 -> new int[] {3, 2, 1, 0};
      case 4 -> new int[] {2, 3, 0, 1};
      case 5 -> new int[] {0, 2, 1, 3};
      case 6 -> new int[] {2, 0, 3, 1};
      case 7 -> new int[] {3, 1, 2, 0};
      case 8 -> new int[] {1, 3, 0, 2};
      default -> new int[] {0, 1, 2, 3};
    };
    int width = orientation == 6 ? 1280 : orientation >= 5 ? 64 : 96;
    int height = orientation == 6 ? 1920 : orientation >= 5 ? 96 : 64;
    assertThat(result.width()).isEqualTo(width);
    assertThat(result.height()).isEqualTo(height);
    assertThat(saved.getWidth()).isEqualTo(width);
    assertThat(saved.getHeight()).isEqualTo(height);
    for (int i = 0; i < corners.length; i++) {
      var pixel = new Color(saved.getRGB((1 + 2 * (i % 2)) * width / 4, (1 + 2 * (i / 2)) * height / 4));
      Color expected = colors[corners[i]];
      assertThat(pixel.getRed()).isCloseTo(expected.getRed(), within(8));
      assertThat(pixel.getGreen()).isCloseTo(expected.getGreen(), within(8));
      assertThat(pixel.getBlue()).isCloseTo(expected.getBlue(), within(8));
    }
  }

  @Test
  void ignoresExifOffsetsOutsideTheAppSegmentAndUnknownOrientationValues() throws Exception {
    byte[] jpeg = image("jpeg", 12, 8);
    for (byte[] input : new byte[][] {
        withExif(jpeg, 6, ByteOrder.LITTLE_ENDIAN, -1),
        withExif(jpeg, 6, ByteOrder.BIG_ENDIAN, 24),
        withExif(jpeg, 9, ByteOrder.BIG_ENDIAN, 8)
    }) {
      var result = validator.validate(new ByteArrayInputStream(input), "image/jpeg");
      assertThat(result.width()).isEqualTo(12);
      assertThat(result.height()).isEqualTo(8);
    }
  }

  @Test
  void acceptsExactlyFiveMiBAndDropsAncillaryData() throws Exception {
    byte[] original = image("png", 10, 10);
    int limit = MAX_BYTES;
    byte[] input = new byte[limit];
    int endOffset = original.length - 12;
    int length = limit - original.length - 12;
    var bytes = ByteBuffer.wrap(input);
    bytes.put(original, 0, endOffset).putInt(length).putInt(0x7361486f);
    var crc = new CRC32();
    crc.update(input, endOffset + 4, length + 4);
    bytes.position(endOffset + 8 + length);
    bytes.putInt((int) crc.getValue()).put(original, endOffset, 12);
    var result = validator.validate(new ByteArrayInputStream(input), "image/png");
    assertThat(result.content().length).isLessThan(limit);
    assertRejected(Arrays.copyOf(input, limit + 1), "image/png");
  }

  @Test
  void rejectsEmptyForgedUnsupportedAndDamagedImages() throws Exception {
    byte[] png = image("png", 10, 10);
    assertRejected(new byte[0], "image/png");
    assertRejected(png, "image/jpeg");
    assertRejected(png, "image/gif");
    assertRejected(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, "image/png");
    assertRejected(Arrays.copyOf(png, png.length - 1), "image/png");
    assertRejected(Arrays.copyOf(png, png.length + 1), "image/png");
    png[30] ^= 1;
    assertRejected(png, "image/png");
    byte[] jpeg = image("jpeg", 10, 10);
    assertRejected(Arrays.copyOf(jpeg, jpeg.length - 2), "image/jpeg");
  }

  @Test
  void acceptsDimensionAndPixelLimits() throws Exception {
    var edge = validator.validate(new ByteArrayInputStream(image("png", 4096, 1)), "image/png");
    assertThat(edge.width()).isEqualTo(1920);
    assertThat(edge.height()).isEqualTo(1);
    var area = validator.validate(new ByteArrayInputStream(image("png", 4000, 4000)), "image/png");
    assertThat(area.width()).isEqualTo(1920);
    assertThat(area.height()).isEqualTo(1920);
    var storedEdge = validator.validate(new ByteArrayInputStream(image("png", 960, 1920)), "image/png");
    assertThat(storedEdge.width()).isEqualTo(960);
    assertThat(storedEdge.height()).isEqualTo(1920);
  }

  @Test
  void rejectsExcessiveDimensionsAndTotalPixels() throws Exception {
    assertRejected(image("png", 4097, 1), "image/png");
    assertRejected(image("png", 1, 4097), "image/png");
    assertRejected(image("png", 4096, 3907), "image/png");
  }

  private void assertRejected(byte[] content, String mimeType) {
    assertThatThrownBy(() -> validator.validate(new ByteArrayInputStream(content), mimeType))
        .isInstanceOf(InvalidImageException.class);
  }

  private byte[] image(String format, int width, int height) throws Exception {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), format);
  }

  private byte[] encode(BufferedImage image, String format) throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, format, output);
    return output.toByteArray();
  }

  private byte[] withExif(byte[] jpeg, int orientation, ByteOrder order, int ifdOffset) {
    var tiff = ByteBuffer.allocate(26).order(order);
    tiff.putShort((short) (order == ByteOrder.BIG_ENDIAN ? 0x4d4d : 0x4949));
    tiff.putShort((short) 42).putInt(ifdOffset).putShort((short) 1);
    tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
    tiff.putInt(0);
    return ByteBuffer.allocate(jpeg.length + 4 + 6 + tiff.capacity())
        .put(jpeg, 0, 2).putShort((short) 0xffe1).putShort((short) (2 + 6 + tiff.capacity()))
        .put(new byte[] {'E', 'x', 'i', 'f', 0, 0}).put(tiff.array()).put(jpeg, 2, jpeg.length - 2).array();
  }
}
