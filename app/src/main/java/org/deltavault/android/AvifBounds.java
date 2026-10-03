package org.deltavault.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded ISO-BMFF preflight for AVIF image dimensions before native decoding. */
final class AvifBounds {
    static final int MAX_DEPTH = 16;
    static final int MAX_BOXES = 4096;
    static final int MAX_DIMENSION = 16_384;
    static final long MAX_PIXELS = 32_000_000L;

    static final class Size {
        final int width;
        final int height;
        Size(int width, int height) { this.width = width; this.height = height; }
    }

    private AvifBounds() { }

    static List<Size> inspect(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 16) throw new IOException("Truncated AVIF container");
        Parser parser = new Parser(bytes);
        parser.boxes(0, bytes.length, 0, true);
        if (!parser.avifBrand || parser.sizes.isEmpty()) throw new IOException("Missing AVIF image dimensions");
        return List.copyOf(parser.sizes);
    }

    static void validateDecodedDimensions(List<Size> sizes, int width, int height) throws IOException {
        if (sizes == null || sizes.isEmpty() || width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IOException("AVIF decoded dimensions exceed supported bounds");
        }
        // Valid AVIF clap/crop transforms can make the display dimensions differ from every ispe.
        // The encoded ispe dimensions were all bounded by inspect(); bound the post-transform size here.
    }

    private static final class Parser {
        final byte[] bytes;
        final ByteBuffer data;
        final List<Size> sizes = new ArrayList<>();
        int boxes;
        boolean avifBrand;

        Parser(byte[] bytes) {
            this.bytes = bytes;
            this.data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        }

        void boxes(int start, int end, int depth, boolean topLevel) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("AVIF container nesting exceeds the limit");
            int cursor = start;
            while (cursor < end) {
                if (++boxes > MAX_BOXES) throw new IOException("AVIF container has too many boxes");
                if (end - cursor < 8) throw new IOException("Truncated AVIF box header");
                long size = u32(cursor);
                String type = fourcc(cursor + 4);
                int header = 8;
                if (size == 1) {
                    if (end - cursor < 16) throw new IOException("Truncated extended AVIF box header");
                    size = data.getLong(cursor + 8);
                    if (size < 0) throw new IOException("AVIF box size exceeds supported bounds");
                    header = 16;
                } else if (size == 0) {
                    size = end - (long) cursor;
                }
                if (size < header || size > end - (long) cursor) throw new IOException("Malformed AVIF box size");
                long boxEndLong = cursor + size;
                if (boxEndLong > Integer.MAX_VALUE) throw new IOException("AVIF box is too large");
                int boxEnd = (int) boxEndLong;
                int payload = cursor + header;

                if (topLevel && "ftyp".equals(type)) checkBrand(payload, boxEnd);
                if ("ispe".equals(type)) readImageSize(payload, boxEnd);

                int childStart = containerStart(type, payload, boxEnd);
                if (childStart >= 0 && childStart < boxEnd) boxes(childStart, boxEnd, depth + 1, false);
                cursor = boxEnd;
            }
            if (cursor != end) throw new IOException("Malformed AVIF box boundary");
        }

        private int containerStart(String type, int payload, int boxEnd) throws IOException {
            if ("meta".equals(type)) {
                if (boxEnd - payload < 4) throw new IOException("Truncated AVIF meta box");
                return payload + 4; // FullBox version and flags.
            }
            if (isContainer(type)) return payload;
            return -1;
        }

        private static boolean isContainer(String type) {
            return switch (type) {
                case "iprp", "ipco", "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf",
                        "mvex", "moof", "traf", "mfra", "udta", "ilst", "sinf", "schi", "wave" -> true;
                default -> false;
            };
        }

        private void checkBrand(int payload, int boxEnd) throws IOException {
            if (boxEnd - payload < 8 || ((boxEnd - payload - 8) & 3) != 0) {
                throw new IOException("Malformed AVIF file type box");
            }
            if (isAvifBrand(fourcc(payload))) avifBrand = true;
            for (int offset = payload + 8; offset + 4 <= boxEnd; offset += 4) {
                if (isAvifBrand(fourcc(offset))) avifBrand = true;
            }
        }

        private void readImageSize(int payload, int boxEnd) throws IOException {
            if (boxEnd - payload < 12) throw new IOException("Truncated AVIF ispe box");
            long width = u32(payload + 4), height = u32(payload + 8);
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                    || width * height > MAX_PIXELS) {
                throw new IOException("AVIF source dimensions exceed supported bounds");
            }
            sizes.add(new Size((int) width, (int) height));
        }

        private long u32(int offset) { return Integer.toUnsignedLong(data.getInt(offset)); }
        private String fourcc(int offset) {
            return new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
        }
        private static boolean isAvifBrand(String brand) { return "avif".equals(brand) || "avis".equals(brand); }
    }
}
