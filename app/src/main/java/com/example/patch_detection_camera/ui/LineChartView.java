package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class LineChartView extends View {

    private float[] data;
    private int lineColor = Color.BLUE;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LineChartView(Context context) {
        super(context); init();
    }
    public LineChartView(Context context, AttributeSet attrs) {
        super(context, attrs); init();
    }
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

        textPaint.setTextSize(28f);
        textPaint.setColor(Color.parseColor("#999999"));
    }

    public void setData(float[] data) {
        this.data = data;
        invalidate();
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        linePaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data == null || data.length < 2) return;

        float w  = getWidth();
        float h  = getHeight();
        float pl = 12f;
        float pr = 56f;
        float pt = 10f;
        float pb = 32f;
        float chartW = w - pl - pr;
        float chartH = h - pt - pb;

        // Axes
        canvas.drawLine(pl, pt, pl, h - pb, axisPaint);
        canvas.drawLine(pl, h - pb, w - pr, h - pb, axisPaint);

        // "time" label
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("time", w - 4, h - 6, textPaint);

        // Smooth line (cubic bezier)
        linePaint.setColor(lineColor);
        Path path = new Path();
        path.moveTo(pl, h - pb - data[0] * chartH);

        for (int i = 1; i < data.length; i++) {
            float x1 = pl + ((float)(i - 1) / (data.length - 1)) * chartW;
            float y1 = h - pb - data[i - 1] * chartH;
            float x2 = pl + ((float)i / (data.length - 1)) * chartW;
            float y2 = h - pb - data[i] * chartH;
            float cx = (x1 + x2) / 2f;
            path.cubicTo(cx, y1, cx, y2, x2, y2);
        }
        canvas.drawPath(path, linePaint);
    }
}
