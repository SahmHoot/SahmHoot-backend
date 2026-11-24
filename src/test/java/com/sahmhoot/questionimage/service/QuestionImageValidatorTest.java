package com.sahmhoot.questionimage.service;

import static com.sahmhoot.questionimage.storage.ImageStorage.MAX_BYTES;
import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

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
    png[30] ^= 1;
    assertRejected(png, "image/png");
    byte[] jpeg = image("jpeg", 10, 10);
    assertRejected(Arrays.copyOf(jpeg, jpeg.length - 2), "image/jpeg");
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
    var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, format, output);
    return output.toByteArray();
  }
}
