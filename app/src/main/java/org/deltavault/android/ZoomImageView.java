package org.deltavault.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** RAM-only, non-saveable image surface with pinch zoom, panning and double-tap reset. */
final class ZoomImageView extends View {
    private static final float MAX_SCALE = 8f;
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Bitmap image;
    private float fitScale = 1f;
    private float scale = 1f;
    private boolean pinching;

    ZoomImageView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setSaveEnabled(false);
        setFocusable(true);
        setClickable(true);
        setContentDescription("画像を拡大・縮小して表示します");
        setMinimumHeight(Math.round(320 * getResources().getDisplayMetrics().density));
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { pinching = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                applyScale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) { pinching = false; }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
                if (image != null && scale > fitScale * 1.001f) {
                    matrix.postTranslate(-distanceX, -distanceY); invalidate();
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (image == null) return true;
                if (scale > fitScale * 1.05f) fitImage();
                else applyScale(Math.min(2f, MAX_SCALE), event.getX(), event.getY());
                return true;
            }
        });
    }

    void setImage(Bitmap bitmap) {
        if (image != null && image != bitmap) wipe(image);
        image = bitmap;
        fitImage();
    }

    void clearImage() {
        Bitmap old = image;
        image = null;
        matrix.reset();
        invalidate();
        if (old != null) wipe(old);
    }

    Bitmap currentImage() { return image; }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { fitImage(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap current = image;
        if (current != null && !current.isRecycled()) canvas.drawBitmap(current, matrix, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean scaleHandled = scaleDetector.onTouchEvent(event);
        boolean gestureHandled = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!pinching) performClick();
            pinching = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pinching = false;
        }
        return scaleHandled || gestureHandled || pinching || super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void fitImage() {
        if (image == null || image.isRecycled() || getWidth() <= 0 || getHeight() <= 0) {
            matrix.reset(); scale = fitScale = 1f; invalidate(); return;
        }
        fitScale = Math.min((float) getWidth() / image.getWidth(), (float) getHeight() / image.getHeight());
        if (!Float.isFinite(fitScale) || fitScale <= 0f) fitScale = 1f;
        scale = fitScale;
        matrix.setScale(fitScale, fitScale);
        float dx = (getWidth() - image.getWidth() * fitScale) / 2f;
        float dy = (getHeight() - image.getHeight() * fitScale) / 2f;
        matrix.postTranslate(dx, dy);
        invalidate();
    }

    private void applyScale(float factor, float focusX, float focusY) {
        if (image == null || !Float.isFinite(factor) || factor <= 0f) return;
        float next = Math.max(fitScale, Math.min(fitScale * MAX_SCALE, scale * factor));
        float applied = next / scale;
        if (!Float.isFinite(applied) || applied <= 0f) return;
        matrix.postScale(applied, applied, focusX, focusY);
        scale = next;
        invalidate();
    }

    private static void wipe(Bitmap bitmap) {
        PreviewImageDecoder.wipe(bitmap);
    }
}
