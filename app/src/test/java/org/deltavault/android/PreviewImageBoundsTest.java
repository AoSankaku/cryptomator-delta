package org.deltavault.android;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class PreviewImageBoundsTest {
    @Test public void routesOnlySupportedImageExtensions() {
        for (String extension : List.of("png", "jpg", "jpeg", "webp", "avif")) {
            assertTrue(DocumentPreview.isImage("IMAGE." + extension.toUpperCase(java.util.Locale.ROOT)));
        }
        for (String name : List.of("page.pdf", "video.mp4", "image.svg", "image.avifs", "x.jpg.txt")) {
            assertFalse(name, DocumentPreview.isImage(name));
        }
        assertTrue(DocumentPreview.isPdf("page.PDF"));
        assertFalse(DocumentPreview.isPdf("page.pdf.txt"));
    }

    @Test public void sniffsSupportedImageContainersAndRejectsMalformedWebp() throws Exception {
        assertEquals("png", PreviewImageDecoder.sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0}));
        assertEquals("jpeg", PreviewImageDecoder.sniff(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        byte[] webp = new byte[20];
        ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(12)
                .put("WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        assertEquals("webp", PreviewImageDecoder.sniff(webp));
        webp[4] = 11;
        assertThrows(IOException.class, () -> PreviewImageDecoder.sniff(webp));
        assertThrows(IOException.class, () -> PreviewImageDecoder.sniff(new byte[32]));
    }

    @Test public void avifPreflightBoundsIsoBmffNestingBoxCountAndSourcePixels() throws Exception {
        List<AvifBounds.Size> sizes = AvifBounds.inspect(avif(256, 128));
        assertEquals(1, sizes.size());
        assertEquals(256, sizes.get(0).width);
        assertEquals(128, sizes.get(0).height);
        AvifBounds.validateDecodedDimensions(sizes, 256, 128);
        AvifBounds.validateDecodedDimensions(sizes, 128, 64); // A valid clap transform can crop the display size.
        assertEquals(1, AvifBounds.inspect(avifWithLaterCompatibleBrandAndIref()).size());
        ByteBuffer onlyMajorBrand = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        box(onlyMajorBrand, "ftyp", 16); put(onlyMajorBrand, "mif1"); onlyMajorBrand.putInt(0);
        assertThrows(IOException.class, () -> AvifBounds.inspect(onlyMajorBrand.array()));
        assertThrows(IOException.class, () -> AvifBounds.inspect(avif(16_384, 2049)));
        assertThrows(IOException.class, () -> AvifBounds.inspect(deepAvif(AvifBounds.MAX_DEPTH + 2)));
        assertThrows(IOException.class, () -> AvifBounds.inspect(manyBoxesAvif(AvifBounds.MAX_BOXES + 1)));
        byte[] malformed = avif(2, 2); ByteBuffer.wrap(malformed).putInt(20, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> AvifBounds.inspect(malformed));
    }

    @Test public void bitmapSizeAndSamplingUseOverflowSafeLimits() throws Exception {
        assertEquals(1, PreviewImageDecoder.sampleSize(2048, 2048));
        assertEquals(8, PreviewImageDecoder.sampleSize(16_384, 4096));
        assertThrows(IOException.class, () -> PreviewImageDecoder.validateSourceSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> PreviewImageDecoder.validateSourceSize(16_385, 1));
        PreviewImageDecoder.validateSourceSize(8000, 4000);
    }

    @Test public void displayedBitmapIsWipedWhenReplacedOrCleared() {
        try (org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
                     Robolectric.buildActivity(android.app.Activity.class).setup()) {
            ZoomImageView view = new ZoomImageView(controller.get());
            Bitmap first = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            first.eraseColor(Color.RED);
            Bitmap second = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            view.setImage(first);
            view.setImage(second);
            assertTrue(first.isRecycled());
            assertSame(second, view.currentImage());
            view.clearImage();
            assertTrue(second.isRecycled());
            assertNull(view.currentImage());
        }
    }

    private static byte[] avif(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(68).order(ByteOrder.BIG_ENDIAN);
        box(buffer, "ftyp", 20); put(buffer, "avif"); buffer.putInt(0); put(buffer, "mif1");
        box(buffer, "meta", 48); buffer.putInt(0);
        box(buffer, "iprp", 36); box(buffer, "ipco", 28);
        box(buffer, "ispe", 20); buffer.putInt(0).putInt(width).putInt(height);
        return buffer.array();
    }

    private static byte[] deepAvif(int depth) {
        byte[] current = imageSize(4, 4);
        for (int i = 0; i < depth; i++) {
            ByteBuffer box = ByteBuffer.allocate(current.length + 12).order(ByteOrder.BIG_ENDIAN);
            box.putInt(current.length + 12).put("meta".getBytes(StandardCharsets.US_ASCII)).putInt(0).put(current);
            current = box.array();
        }
        byte[] ftyp = ftyp(); byte[] result = new byte[ftyp.length + current.length];
        System.arraycopy(ftyp, 0, result, 0, ftyp.length); System.arraycopy(current, 0, result, ftyp.length, current.length);
        return result;
    }

    private static byte[] manyBoxesAvif(int count) {
        byte[] children = new byte[count * 8]; ByteBuffer child = ByteBuffer.wrap(children).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < count; i++) child.putInt(8).put("free".getBytes(StandardCharsets.US_ASCII));
        ByteBuffer ipco = ByteBuffer.allocate(8 + children.length).order(ByteOrder.BIG_ENDIAN);
        ipco.putInt(ipco.capacity()).put("ipco".getBytes(StandardCharsets.US_ASCII)).put(children);
        ByteBuffer iprp = ByteBuffer.allocate(8 + ipco.capacity()).order(ByteOrder.BIG_ENDIAN);
        iprp.putInt(iprp.capacity()).put("iprp".getBytes(StandardCharsets.US_ASCII)).put(ipco.array());
        ByteBuffer meta = ByteBuffer.allocate(12 + iprp.capacity()).order(ByteOrder.BIG_ENDIAN);
        meta.putInt(meta.capacity()).put("meta".getBytes(StandardCharsets.US_ASCII)).putInt(0).put(iprp.array());
        byte[] ftyp = ftyp(); byte[] result = new byte[ftyp.length + meta.capacity()];
        System.arraycopy(ftyp, 0, result, 0, ftyp.length); System.arraycopy(meta.array(), 0, result, ftyp.length, meta.capacity());
        return result;
    }

    private static byte[] imageSize(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        box(buffer, "ispe", 20); buffer.putInt(0).putInt(width).putInt(height);
        return buffer.array();
    }

    private static byte[] ftyp() {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        box(buffer, "ftyp", 20); put(buffer, "avif"); buffer.putInt(0); put(buffer, "mif1");
        return buffer.array();
    }

    private static byte[] avifWithLaterCompatibleBrandAndIref() {
        ByteBuffer buffer = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN);
        box(buffer, "ftyp", 24); put(buffer, "mif1"); buffer.putInt(0); put(buffer, "mif1"); put(buffer, "avif");
        box(buffer, "meta", 74); buffer.putInt(0);
        box(buffer, "iref", 26); buffer.putInt(0);
        box(buffer, "auxl", 14); buffer.putShort((short) 1).putShort((short) 1).putShort((short) 2);
        box(buffer, "iprp", 36); box(buffer, "ipco", 28);
        box(buffer, "ispe", 20); buffer.putInt(0).putInt(128).putInt(64);
        return buffer.array();
    }

    private static void box(ByteBuffer buffer, String type, int size) {
        buffer.putInt(size).put(type.getBytes(StandardCharsets.US_ASCII));
    }
    private static void put(ByteBuffer buffer, String value) { buffer.put(value.getBytes(StandardCharsets.US_ASCII)); }
}
