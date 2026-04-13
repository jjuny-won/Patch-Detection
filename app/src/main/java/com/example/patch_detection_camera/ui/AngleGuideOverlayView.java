package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class AngleGuideOverlayView extends View {

    /** 노란 ROI 원 반지름 비율 */
    private static final float ROI_RADIUS_RATIO = 0.15f;

    /** 스트립 가이드 크기: ROI 기준 */
    private static final float STRIP_HALF_WIDTH_IN_ROI_RATIO = 0.12f;
    private static final float STRIP_HALF_HEIGHT_IN_ROI_RATIO = 0.50f;

    /** 미세 위치 보정 */
    private static final float GUIDE_OFFSET_X_DP = 0f;
    private static final float GUIDE_OFFSET_Y_DP = 2f;

    /** 꼭짓점 선 길이 */
    private static final float CORNER_LEN_DP = 10f;

    private static final int COLOR_GUIDE = 0xFFE53935;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AngleGuideOverlayView(Context context) {
        super(context);
        init();
    }

    public AngleGuideOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setColor(COLOR_GUIDE);
        guidePaint.setStrokeWidth(dp(3f));
        guidePaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float cx = w / 2f + dp(GUIDE_OFFSET_X_DP);
        float cy = h / 2f + dp(GUIDE_OFFSET_Y_DP);

        float shortSide = Math.min(w, h);
        float roiRadius = shortSide * ROI_RADIUS_RATIO;

        float halfW = roiRadius * STRIP_HALF_WIDTH_IN_ROI_RATIO;
        float halfH = roiRadius * STRIP_HALF_HEIGHT_IN_ROI_RATIO;

        float left = cx - halfW;
        float right = cx + halfW;
        float top = cy - halfH;
        float bottom = cy + halfH;

        float cl = dp(CORNER_LEN_DP);

        // 좌상
        canvas.drawLine(left, top, left + cl, top, guidePaint);
        canvas.drawLine(left, top, left, top + cl, guidePaint);

        // 우상
        canvas.drawLine(right, top, right - cl, top, guidePaint);
        canvas.drawLine(right, top, right, top + cl, guidePaint);

        // 좌하
        canvas.drawLine(left, bottom, left + cl, bottom, guidePaint);
        canvas.drawLine(left, bottom, left, bottom - cl, guidePaint);

        // 우하
        canvas.drawLine(right, bottom, right - cl, bottom, guidePaint);
        canvas.drawLine(right, bottom, right, bottom - cl, guidePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}