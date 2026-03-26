package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class RoiOverlayView extends View {

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 원 반지름: 화면 짧은 변 * ratio
    private float radiusRatio = 0.15f;


    public RoiOverlayView(Context context) {
        super(context);
        init();
    }

    public RoiOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(5));
        strokePaint.setARGB(255, 255, 215, 0); // 노란색
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * radiusRatio;

        canvas.drawCircle(cx, cy, r, strokePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public RectF getRoiRectInView() {
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * radiusRatio;
        return new RectF(cx - r, cy - r, cx + r, cy + r);
    }
}
