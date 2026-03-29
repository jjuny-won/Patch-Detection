package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 가속도계(TYPE_ROTATION_VECTOR) 기반 수평 가이드라인 오버레이
 *
 * - 기기 기울기(roll)를 반영해 수평 기준선을 그림
 * - ±2° 이내 → 초록색 (수평)
 * - 그 외     → 빨간색 + 기울기 각도 텍스트
 * - 선이 기울기 반대 방향으로 회전 → 실제 "수평선"처럼 보임
 */
public class LevelIndicatorView extends View {

    // 수평으로 간주하는 허용 각도 (degree)
    private static final float LEVEL_THRESHOLD = 2.0f;

    private float tiltDegrees = 0f;

    private final Paint linePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 색상
    private static final int COLOR_LEVEL   = 0xFF4CAF50;  // 수평: 초록
    private static final int COLOR_TILTED  = 0xFFFF5252;  // 기울어짐: 빨간

    public LevelIndicatorView(Context context) {
        super(context);
        init();
    }

    public LevelIndicatorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 수평 가이드선
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        // 중앙 원형 점
        centerPaint.setStyle(Paint.Style.FILL);

        // 각도 텍스트
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(dp(13f));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * CameraActivity 에서 SensorEvent 수신 시 호출
     * @param degrees 기기 roll 각도 (양수=오른쪽 기울어짐, 음수=왼쪽 기울어짐)
     */
    public void setTilt(float degrees) {
        this.tiltDegrees = degrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        boolean isLevel = Math.abs(tiltDegrees) <= LEVEL_THRESHOLD;
        int color = isLevel ? COLOR_LEVEL : COLOR_TILTED;

        linePaint.setColor(color);
        centerPaint.setColor(color);
        textPaint.setColor(color);

        float cx = w / 2f;
        float cy = h / 2f;

        // ── 가이드선: 중앙에서 좌우로 뻗는 선을 기울기만큼 회전
        float lineHalfLen = w * 0.38f;  // 화면 너비의 38%

        canvas.save();
        canvas.rotate(-tiltDegrees, cx, cy);   // 기울기 반대로 회전 → 수평선처럼 보임

        // 좌측 선
        canvas.drawLine(cx - lineHalfLen, cy, cx - dp(24f), cy, linePaint);
        // 우측 선
        canvas.drawLine(cx + dp(24f), cy, cx + lineHalfLen, cy, linePaint);

        // 끝 눈금 tick
        canvas.drawLine(cx - lineHalfLen, cy - dp(6f), cx - lineHalfLen, cy + dp(6f), linePaint);
        canvas.drawLine(cx + lineHalfLen, cy - dp(6f), cx + lineHalfLen, cy + dp(6f), linePaint);

        canvas.restore();

        // ── 중앙 원형 점 (회전 없이 항상 중앙 고정)
        float dotR = dp(isLevel ? 5f : 4f);
        canvas.drawCircle(cx, cy, dotR, centerPaint);

        // ── 각도 텍스트 (수평이 아닐 때만 표시)
        if (!isLevel) {
            String angleText = String.format("%.1f°", Math.abs(tiltDegrees));
            // 텍스트 배경 (반투명 검정)
            Paint bgPaint = new Paint();
            bgPaint.setColor(0x88000000);
            bgPaint.setStyle(Paint.Style.FILL);
            float textY = cy - dp(24f);
            float textW = textPaint.measureText(angleText);
            canvas.drawRoundRect(
                    cx - textW / 2f - dp(8f), textY - dp(14f),
                    cx + textW / 2f + dp(8f), textY + dp(4f),
                    dp(6f), dp(6f), bgPaint
            );
            canvas.drawText(angleText, cx, textY, textPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
