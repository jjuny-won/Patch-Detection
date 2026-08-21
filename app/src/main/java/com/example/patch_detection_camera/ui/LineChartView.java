package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LineChartView extends View {

    private float[] data;
    private long[]  timestamps;
    private String[] labels;
    private int selectedIndex = -1;
    private int lineColor = Color.BLUE;

    private final Paint linePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ttBgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ttValPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ttTimePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float PL = 12f, PR = 56f, PT = 14f, PB = 32f;
    private static final float DOT_R      = 5f;
    private static final float DOT_SEL_R  = 8f;
    private static final float TOUCH_R    = 52f;

    private final SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());

    public LineChartView(Context context) { super(context); init(); }
    public LineChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public LineChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1.5f);
        axisPaint.setColor(Color.parseColor("#BBBBBB"));

        axisTextPaint.setTextSize(28f);
        axisTextPaint.setColor(Color.parseColor("#999999"));

        dotFillPaint.setStyle(Paint.Style.FILL);
        dotEdgePaint.setStyle(Paint.Style.STROKE);
        dotEdgePaint.setStrokeWidth(2.5f);

        ttBgPaint.setStyle(Paint.Style.FILL);
        ttBgPaint.setColor(Color.WHITE);
        ttBgPaint.setShadowLayer(8f, 0f, 3f, 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        ttValPaint.setStyle(Paint.Style.FILL);
        ttValPaint.setTextSize(32f);
        ttValPaint.setFakeBoldText(true);
        ttValPaint.setTextAlign(Paint.Align.LEFT);

        ttTimePaint.setStyle(Paint.Style.FILL);
        ttTimePaint.setTextSize(25f);
        ttTimePaint.setColor(Color.parseColor("#888888"));
        ttTimePaint.setTextAlign(Paint.Align.LEFT);
    }

    public void setData(float[] data) {
        this.data = data;
        this.timestamps = null;
        this.labels = null;
        this.selectedIndex = -1;
        invalidate();
    }

    public void setDataWithMeta(float[] data, long[] timestamps, String[] labels) {
        this.data = data;
        this.timestamps = timestamps;
        this.labels = labels;
        this.selectedIndex = -1;
        invalidate();
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        linePaint.setColor(color);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) return false;
        if (data == null || data.length == 0) return false;

        float w = getWidth(), h = getHeight();
        float chartW = w - PL - PR;
        float chartH = h - PT - PB;
        float tx = event.getX(), ty = event.getY();
        int n = data.length;

        float minDist = Float.MAX_VALUE;
        int nearest = -1;
        for (int i = 0; i < n; i++) {
            float x = PL + (n == 1 ? 0f : (float) i / (n - 1)) * chartW;
            float y = h - PB - data[i] * chartH;
            float d = (float) Math.hypot(tx - x, ty - y);
            if (d < minDist) { minDist = d; nearest = i; }
        }

        // toggle: tap same point → dismiss
        if (nearest == selectedIndex) {
            selectedIndex = -1;
        } else {
            selectedIndex = (minDist <= TOUCH_R) ? nearest : -1;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data == null || data.length == 0) return;

        float w = getWidth(), h = getHeight();
        float chartW = w - PL - PR;
        float chartH = h - PT - PB;
        int n = data.length;

        // Axes
        canvas.drawLine(PL, PT, PL, h - PB, axisPaint);
        canvas.drawLine(PL, h - PB, w - PR, h - PB, axisPaint);

        axisTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("time", w - 4, h - 6, axisTextPaint);

        // Line (requires ≥2 points)
        if (n >= 2) {
            linePaint.setColor(lineColor);
            Path path = new Path();
            path.moveTo(PL, h - PB - data[0] * chartH);
            for (int i = 1; i < n; i++) {
                float x1 = PL + ((float)(i-1) / (n-1)) * chartW;
                float y1 = h - PB - data[i-1] * chartH;
                float x2 = PL + ((float)i     / (n-1)) * chartW;
                float y2 = h - PB - data[i]   * chartH;
                float cx = (x1 + x2) / 2f;
                path.cubicTo(cx, y1, cx, y2, x2, y2);
            }
            canvas.drawPath(path, linePaint);
        }

        // Dots
        for (int i = 0; i < n; i++) {
            float x = PL + (n == 1 ? 0f : (float) i / (n - 1)) * chartW;
            float y = h - PB - data[i] * chartH;
            drawDot(canvas, x, y, i == selectedIndex);
        }

        // Tooltip for selected point
        if (selectedIndex >= 0 && selectedIndex < n) {
            float sx = PL + (n == 1 ? 0f : (float) selectedIndex / (n - 1)) * chartW;
            float sy = h - PB - data[selectedIndex] * chartH;
            drawTooltip(canvas, selectedIndex, sx, sy, w, h);
        }
    }

    private void drawDot(Canvas canvas, float x, float y, boolean selected) {
        if (selected) {
            // Halo
            dotFillPaint.setColor(Color.argb(50,
                    Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)));
            canvas.drawCircle(x, y, DOT_SEL_R + 6f, dotFillPaint);
            // Filled circle
            dotFillPaint.setColor(lineColor);
            canvas.drawCircle(x, y, DOT_SEL_R, dotFillPaint);
            // White center
            dotFillPaint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, DOT_SEL_R * 0.38f, dotFillPaint);
        } else {
            // White fill + colored border
            dotFillPaint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, DOT_R, dotFillPaint);
            dotEdgePaint.setColor(lineColor);
            canvas.drawCircle(x, y, DOT_R, dotEdgePaint);
        }
    }

    private void drawTooltip(Canvas canvas, int idx, float dotX, float dotY, float w, float h) {
        String valText = (labels != null && idx < labels.length && labels[idx] != null)
                ? labels[idx]
                : String.format(Locale.US, "%.2f", data[idx]);

        String timeText = "";
        if (timestamps != null && idx < timestamps.length && timestamps[idx] > 0) {
            timeText = sdf.format(new Date(timestamps[idx]));
        }

        boolean hasTime = !timeText.isEmpty();

        float valW  = ttValPaint.measureText(valText);
        float timeW = hasTime ? ttTimePaint.measureText(timeText) : 0f;
        float ttW   = Math.max(valW, timeW) + 28f;
        float ttH   = ttValPaint.getTextSize()
                    + (hasTime ? ttTimePaint.getTextSize() + 6f : 0f)
                    + 20f;
        float arrowH = 10f;

        // Position: above dot if room, else below
        boolean above = (dotY - ttH - arrowH - 8f) >= PT;
        float ttY = above ? (dotY - ttH - arrowH - 8f) : (dotY + arrowH + 8f);
        float ttX = dotX - ttW / 2f;
        ttX = Math.max(4f, Math.min(ttX, w - ttW - 4f));

        // Background
        RectF bgRect = new RectF(ttX, ttY, ttX + ttW, ttY + ttH);
        canvas.drawRoundRect(bgRect, 12f, 12f, ttBgPaint);

        // Arrow pointer
        Path arrow = new Path();
        if (above) {
            arrow.moveTo(dotX - 9f, ttY + ttH);
            arrow.lineTo(dotX,      ttY + ttH + arrowH);
            arrow.lineTo(dotX + 9f, ttY + ttH);
        } else {
            arrow.moveTo(dotX - 9f, ttY);
            arrow.lineTo(dotX,      ttY - arrowH);
            arrow.lineTo(dotX + 9f, ttY);
        }
        arrow.close();
        canvas.drawPath(arrow, ttBgPaint);

        // Value text
        ttValPaint.setColor(lineColor);
        float textX = ttX + 14f;
        float valY  = ttY + ttValPaint.getTextSize() + 10f;
        canvas.drawText(valText, textX, valY, ttValPaint);

        // Time text
        if (hasTime) {
            canvas.drawText(timeText, textX, valY + ttTimePaint.getTextSize() + 6f, ttTimePaint);
        }
    }
}
