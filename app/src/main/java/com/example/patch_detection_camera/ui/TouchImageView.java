package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.*;
import androidx.appcompat.widget.AppCompatImageView;

public class TouchImageView extends AppCompatImageView implements View.OnTouchListener {

    private final Matrix matrix = new Matrix();
    private final Matrix saved  = new Matrix();
    private final PointF start  = new PointF();
    private float oldDist  = 1f;
    private float oldAngle = 0f;   // angle between two fingers at POINTER_DOWN

    private static final int NONE=0, DRAG=1, ZOOM=2;
    private int mode = NONE;

    public TouchImageView(Context c) { super(c); init(); }
    public TouchImageView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setScaleType(ScaleType.MATRIX);
        setOnTouchListener(this);
    }

    public Matrix getImageMatrixCopy() {
        Matrix m = new Matrix();
        m.set(matrix);
        return m;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
                saved.set(matrix);
                start.set(event.getX(), event.getY());
                mode = DRAG;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                oldDist  = spacing(event);
                oldAngle = fingerAngle(event);
                if (oldDist > 10f) {
                    saved.set(matrix);
                    mode = ZOOM;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG) {
                    matrix.set(saved);
                    float dx = event.getX() - start.x;
                    float dy = event.getY() - start.y;
                    matrix.postTranslate(dx, dy);
                    setImageMatrix(matrix);

                } else if (mode == ZOOM && event.getPointerCount() >= 2) {
                    float newDist  = spacing(event);
                    float newAngle = fingerAngle(event);
                    if (newDist > 10f) {
                        float scale    = newDist / oldDist;
                        float rotation = newAngle - oldAngle;   // degrees delta
                        float cx = (event.getX(0) + event.getX(1)) / 2f;
                        float cy = (event.getY(0) + event.getY(1)) / 2f;
                        matrix.set(saved);
                        matrix.postScale(scale, scale, cx, cy);
                        matrix.postRotate(rotation, cx, cy);    // rotate around midpoint
                        setImageMatrix(matrix);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
                break;
        }
        return true;
    }

    /** Distance between two touch points. */
    private float spacing(MotionEvent e) {
        float x = e.getX(0) - e.getX(1);
        float y = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    /** Angle (degrees) of the line connecting two touch points. */
    private float fingerAngle(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0);
        float dy = e.getY(1) - e.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }
}
