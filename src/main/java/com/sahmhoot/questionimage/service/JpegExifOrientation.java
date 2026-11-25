package com.sahmhoot.questionimage.service;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class JpegExifOrientation {
  private static final int APP1 = 0xe1;
  private static final int START_OF_SCAN = 0xda;
  private static final int END_OF_IMAGE = 0xd9;
  private static final int ORIENTATION_TAG = 0x0112;

  private JpegExifOrientation() {}

  static BufferedImage apply(BufferedImage image, byte[] jpeg) {
    int orientation = readOrientation(jpeg);
    int width = image.getWidth();
    int height = image.getHeight();
    var transform = switch (orientation) {
      case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
      case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
      case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
      case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
      case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
      case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
      default -> new AffineTransform();
    };
    boolean swapAxes = orientation >= 5;
    var normalized = new BufferedImage(swapAxes ? height : width, swapAxes ? width : height,
        BufferedImage.TYPE_INT_RGB);
    var graphics = normalized.createGraphics();
    try {
      graphics.drawImage(image, transform, null);
    } finally {
      graphics.dispose();
    }
    return normalized;
  }

  private static int readOrientation(byte[] jpeg) {
    var bytes = ByteBuffer.wrap(jpeg);
    bytes.position(2); // Skip the validated SOI marker.
    while (bytes.remaining() >= 2 && Byte.toUnsignedInt(bytes.get()) == 0xff) {
      int marker;
      do {
        marker = Byte.toUnsignedInt(bytes.get());
      } while (marker == 0xff && bytes.hasRemaining());
      if (marker == START_OF_SCAN || marker == END_OF_IMAGE || bytes.remaining() < 2) {
        break;
      }
      int length = Short.toUnsignedInt(bytes.getShort()) - 2;
      if (length < 0 || length > bytes.remaining()) {
        break;
      }
      if (marker == APP1) {
        int orientation = readExif(bytes.slice(bytes.position(), length));
        if (orientation != 0) {
          return orientation;
        }
      }
      bytes.position(bytes.position() + length);
    }
    return 1;
  }

  private static int readExif(ByteBuffer app1) {
    // The Exif identifier takes 6 bytes. Bound all TIFF offsets to this APP1 payload.
    if (app1.remaining() < 14 || app1.getInt() != 0x45786966 || app1.getShort() != 0) {
      return 0;
    }
    var tiff = app1.slice();
    switch (tiff.getShort()) {
      case 0x4949 -> tiff.order(ByteOrder.LITTLE_ENDIAN);
      case 0x4d4d -> tiff.order(ByteOrder.BIG_ENDIAN);
      default -> { return 0; }
    }
    if (tiff.getShort() != 42) {
      return 0;
    }
    long ifdOffset = Integer.toUnsignedLong(tiff.getInt());
    if (ifdOffset < 8 || ifdOffset > tiff.limit() - Short.BYTES) {
      return 0;
    }
    tiff.position((int) ifdOffset);
    int entryCount = Short.toUnsignedInt(tiff.getShort());
    final int entrySize = 12;
    if (entryCount > tiff.remaining() / entrySize) {
      return 0;
    }
    for (int i = 0; i < entryCount; i++) {
      int entry = tiff.position() + i * entrySize;
      if (tiff.getShort(entry) == ORIENTATION_TAG && tiff.getShort(entry + 2) == 3 && tiff.getInt(entry + 4) == 1) {
        int orientation = Short.toUnsignedInt(tiff.getShort(entry + 8));
        return orientation >= 1 && orientation <= 8 ? orientation : 0;
      }
    }
    return 0;
  }
}
