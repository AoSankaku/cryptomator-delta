package org.deltavault.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import org.aomedia.avif.android.AvifDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Bounded still-image decoder. It accepts one frame and never writes plaintext to storage. */
final class PreviewImageDecoder {
    static final int OUTPUT_LIMIT = 2048;
    static final int SOURCE_DIMENSION_LIMIT = AvifBounds.MAX_DIMENSION;
    static final long SOURCE_PIXEL_LIMIT = AvifBounds.MAX_PIXELS;

    private PreviewImageDecoder() { }

    static String expectedKind(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpeg";
        if (lower.endsWith(".webp")) return "webp";
        if (lower.endsWith(".avif")) return "avif";
        return null;
    }

    static boolean isKind(String kind) {
        return "png".equals(kind) || "jpeg".equals(kind) || "webp".equals(kind) || "avif".equals(kind);
    }

    static String sniff(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 12) throw new IOException("Unsupported image data");
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) return "png";
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "jpeg";
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            long riffSize = Integer.toUnsignedLong((bytes[4] & 0xff) | ((bytes[5] & 0xff) << 8)
                    | ((bytes[6] & 0xff) << 16) | ((bytes[7] & 0xff) << 24));
            if (riffSize + 8L != bytes.length) throw new IOException("Malformed WebP container length");
            return "webp";
        }
        try { AvifBounds.inspect(bytes); return "avif"; }
        catch (IOException ignored) { throw new IOException("Unsupported image data"); }
    }

    static Bitmap decode(ParcelFileDescriptor source, String expectedKind) throws IOException {
        if (source == null || expectedKind == null) throw new IOException("Missing image input");
        StructStat stat;
        try { stat = Os.fstat(source.getFileDescriptor()); }
        catch (ErrnoException e) { throw new IOException("Unable to read image descriptor", e); }
        long size = stat.st_size;
        if (size <= 0 || size > PreviewSource.MAX_IMAGE_BYTES) throw new IOException("Image exceeds the preview size limit");
        byte[] bytes = new byte[(int) size];
        try {
            readFully(source, bytes);
            String detected = sniff(bytes);
            if (!detected.equals(expectedKind)) throw new IOException("Image content does not match its filename extension");
            return switch (detected) {
                case "avif" -> decodeAvif(bytes);
                case "png", "jpeg", "webp" -> decodeBitmapFactory(bytes);
                default -> throw new IOException("Unsupported image data");
            };
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void readFully(ParcelFileDescriptor source, byte[] destination) throws IOException {
        int offset = 0;
        try {
            while (offset < destination.length) {
                int count = Os.pread(source.getFileDescriptor(), destination, offset, destination.length - offset, offset);
                if (count <= 0) throw new IOException("Unexpected end of image input");
                offset += count;
            }
        } catch (ErrnoException e) { throw new IOException("Unable to read image input", e); }
    }

    private static Bitmap decodeBitmapFactory(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        validateSourceSize(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;
        options.inScaled = false;
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return validateOutput(decoded);
    }

    private static Bitmap decodeAvif(byte[] bytes) throws IOException {
        List<AvifBounds.Size> preflight = AvifBounds.inspect(bytes);
        ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
        Bitmap result = null;
        try {
            direct.put(bytes).position(0);
            if (!AvifDecoder.isAvifImage(direct)) throw new IOException("Unsupported AVIF container");
            AvifDecoder.Info info = new AvifDecoder.Info();
            if (!AvifDecoder.getInfo(direct, bytes.length, info)) throw new IOException("Unable to read AVIF image dimensions");
            AvifBounds.validateDecodedDimensions(preflight, info.width, info.height);
            int divisor = sampleSize(info.width, info.height);
            int width = Math.max(1, (info.width + divisor - 1) / divisor);
            int height = Math.max(1, (info.height + divisor - 1) / divisor);
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (!AvifDecoder.decode(direct, bytes.length, result, 1)) throw new IOException("Unable to decode AVIF image");
            return validateOutput(result);
        } catch (IOException | RuntimeException | LinkageError e) {
            wipe(result);
            if (e instanceof IOException io) throw io;
            throw new IOException("Unable to decode AVIF image", e);
        } catch (OutOfMemoryError e) {
            wipe(result);
            throw e;
        } finally {
            for (int i = 0; i < direct.capacity(); i++) direct.put(i, (byte) 0);
            direct.clear();
        }
    }

    static void validateSourceSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > SOURCE_DIMENSION_LIMIT || height > SOURCE_DIMENSION_LIMIT
                || (long) width * height > SOURCE_PIXEL_LIMIT) throw new IOException("Image dimensions exceed supported bounds");
    }

    static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0) return 1;
        int sample = 1;
        while ((width + (long) sample - 1) / sample > OUTPUT_LIMIT
                || (height + (long) sample - 1) / sample > OUTPUT_LIMIT) {
            if (sample > (1 << 28)) break;
            sample <<= 1;
        }
        return sample;
    }

    private static Bitmap validateOutput(Bitmap bitmap) throws IOException {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable()
                || bitmap.getConfig() != Bitmap.Config.ARGB_8888
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0
                || bitmap.getWidth() > OUTPUT_LIMIT || bitmap.getHeight() > OUTPUT_LIMIT
                || (long) bitmap.getWidth() * bitmap.getHeight() > (long) OUTPUT_LIMIT * OUTPUT_LIMIT) {
            wipe(bitmap);
            throw new IOException("Decoded image exceeds display bounds");
        }
        return bitmap;
    }

    static void wipe(Bitmap bitmap) {
        if (bitmap == null) return;
        try { if (!bitmap.isRecycled() && bitmap.isMutable()) bitmap.eraseColor(android.graphics.Color.TRANSPARENT); }
        catch (RuntimeException ignored) { }
        try { if (!bitmap.isRecycled()) bitmap.recycle(); }
        catch (RuntimeException ignored) { }
    }
}
