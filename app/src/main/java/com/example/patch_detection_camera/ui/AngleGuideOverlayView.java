package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 촬영 각도 보조 가이드라인 오버레이
 *
 * 색상 마커(스트립) 양옆에 파란색 세로 기준선을 표시합니다.
 * 노란 ROI 원(반지름 = shortSide * 0.15) 안에 들어오도록 크기를 맞췄습니다.
 *
 * 밴드에 인쇄된 선이 화면의 파란 기준선과 일치하도록 맞추면
 * 일정한 촬영 각도를 유지할 수 있습니다.
 */
public class AngleGuideOverlayView extends View {

    // ── 위치/크기 비율 (화면 짧은 변 기준, RoiOverlayView radiusRatio=0.15 와 맞춤) ──
    /**
     * 세로선 반높이 비율 (화면 짧은 변 기준).
     * 원 반지름(0.15) × 0.82 ≈ 0.123 → 원 안에 여유 있게 들어옴
     */
    private static final float LINE_HALF_HEIGHT_RATIO = 0.123f;

    /**
     * 스트립 반폭 비율 (화면 짧은 변 기준).
     * 원 반지름(0.15) × 0.25 ≈ 0.037 → 스트립 좌우 가장자리 위치
     */
    private static final float STRIP_HALF_WIDTH_RATIO = 0.037f;

    /** 눈금 tick 반길이 (dp) */
    private static final float TICK_HALF_LEN_DP = 5f;

    // ── 색상 ────────────────────────────────────────────────────────────────
    private static final int COLOR_GUIDE = 0xFF1565C0;  // 파란색

    // ── Paint ───────────────────────────────────────────────────────────────
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ────────────────────────────────────────────────────────────────────────

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
        guidePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float cx        = w / 2f;
        float cy        = h / 2f;
        float shortSide = Math.min(w, h);

        // 가이드선 좌우 X (원 안쪽, 스트립 가장자리)
        float xOffset = shortSide * STRIP_HALF_WIDTH_RATIO;
        float leftX   = cx - xOffset;
        float rightX  = cx + xOffset;

        // 가이드선 상하 Y (노란 원 반지름보다 약간 짧게)
        float halfH  = shortSide * LINE_HALF_HEIGHT_RATIO;
        float top    = cy - halfH;
        float bottom = cy + halfH;

        // 왼쪽 세로 기준선
        drawGuideLine(canvas, leftX, top, bottom);
        // 오른쪽 세로 기준선
        drawGuideLine(canvas, rightX, top, bottom);
    }

    /** 세로선 + 상하 눈금 tick */
    private void drawGuideLine(Canvas canvas, float x, float top, float bottom) {
        float tick = dp(TICK_HALF_LEN_DP);

        // 메인 세로선
        canvas.drawLine(x, top, x, bottom, guidePaint);
        // 위 눈금
        canvas.drawLine(x - tick, top,    x + tick, top,    guidePaint);
        // 아래 눈금
        canvas.drawLine(x - tick, bottom, x + tick, bottom, guidePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
