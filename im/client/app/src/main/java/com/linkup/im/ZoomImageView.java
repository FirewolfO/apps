package com.linkup.im;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public final class ZoomImageView extends AppCompatImageView {
    private final Matrix drawMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float baseScale = 1f;
    private float currentScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;

    public ZoomImageView(@NonNull Context context) { this(context, null); }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float target = Math.max(baseScale, Math.min(baseScale * 4f, currentScale * detector.getScaleFactor()));
                float factor = target / currentScale;
                drawMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                currentScale = target;
                constrain();
                setImageMatrix(drawMatrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(@NonNull MotionEvent event) {
                if (currentScale > baseScale * 1.2f) resetImage();
                else zoomTo(baseScale * 2.5f, event.getX(), event.getY());
                return true;
            }
        });
    }

    @Override public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetImage);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::resetImage);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && currentScale > baseScale) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    drawMatrix.postTranslate(dx, dy);
                    constrain();
                    setImageMatrix(drawMatrix);
                }
                lastX = event.getX();
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                performClick();
                break;
            default:
                break;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void resetImage() {
        Drawable drawable = getDrawable();
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (drawable == null || width <= 0 || height <= 0
                || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return;
        float sx = width / (float) drawable.getIntrinsicWidth();
        float sy = height / (float) drawable.getIntrinsicHeight();
        baseScale = Math.min(sx, sy);
        currentScale = baseScale;
        float renderedWidth = drawable.getIntrinsicWidth() * baseScale;
        float renderedHeight = drawable.getIntrinsicHeight() * baseScale;
        drawMatrix.reset();
        drawMatrix.postScale(baseScale, baseScale);
        drawMatrix.postTranslate((getWidth() - renderedWidth) / 2f, (getHeight() - renderedHeight) / 2f);
        setImageMatrix(drawMatrix);
    }

    private void zoomTo(float target, float focusX, float focusY) {
        float factor = target / currentScale;
        drawMatrix.postScale(factor, factor, focusX, focusY);
        currentScale = target;
        constrain();
        setImageMatrix(drawMatrix);
    }

    private void constrain() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        drawMatrix.mapRect(bounds);
        float dx = 0;
        float dy = 0;
        if (bounds.width() <= getWidth()) dx = getWidth() / 2f - bounds.centerX();
        else if (bounds.left > 0) dx = -bounds.left;
        else if (bounds.right < getWidth()) dx = getWidth() - bounds.right;
        if (bounds.height() <= getHeight()) dy = getHeight() / 2f - bounds.centerY();
        else if (bounds.top > 0) dy = -bounds.top;
        else if (bounds.bottom < getHeight()) dy = getHeight() - bounds.bottom;
        drawMatrix.postTranslate(dx, dy);
    }
}
