package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;

public class WoundMonitoringActivity extends AppCompatActivity {

    private static final float[] PH_DATA = {
            0.40f, 0.42f, 0.38f, 0.44f, 0.40f, 0.45f, 0.50f, 0.48f,
            0.52f, 0.55f, 0.58f, 0.60f, 0.58f, 0.62f, 0.65f, 0.60f,
            0.63f, 0.67f, 0.65f, 0.68f
    };
    private static final float[] TEMP_DATA = {
            0.30f, 0.33f, 0.36f, 0.38f, 0.40f, 0.43f, 0.46f, 0.49f,
            0.52f, 0.54f, 0.56f, 0.58f, 0.61f, 0.63f, 0.65f, 0.63f,
            0.67f, 0.70f, 0.72f, 0.75f
    };
    private static final float[] URIC_DATA = {
            0.30f, 0.35f, 0.28f, 0.38f, 0.32f, 0.40f, 0.35f, 0.42f,
            0.38f, 0.44f, 0.40f, 0.46f, 0.42f, 0.48f, 0.44f, 0.50f,
            0.46f, 0.48f, 0.52f, 0.50f
    };
    private static final float[] GLUCOSE_DATA = {
            0.75f, 0.70f, 0.65f, 0.60f, 0.55f, 0.50f, 0.48f, 0.46f,
            0.44f, 0.43f, 0.42f, 0.41f, 0.40f, 0.42f, 0.41f, 0.43f,
            0.42f, 0.44f, 0.43f, 0.45f
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wound_monitoring);

        setupCharts();

        findViewById(R.id.btnDressingHistory).setOnClickListener(v ->
                startActivity(new Intent(this, DressingHistoryActivity.class)));

        findViewById(R.id.btnHome).setOnClickListener(v -> {
            Intent it = new Intent(this, ModeSelectActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });
    }

    private void setupCharts() {
        LineChartView chartPh = findViewById(R.id.chartPh);
        chartPh.setLineColor(Color.parseColor("#7765C5"));
        chartPh.setData(PH_DATA);

        LineChartView chartTemp = findViewById(R.id.chartTemp);
        chartTemp.setLineColor(Color.parseColor("#E07050"));
        chartTemp.setData(TEMP_DATA);

        LineChartView chartUric = findViewById(R.id.chartUricAcid);
        chartUric.setLineColor(Color.parseColor("#3A5580"));
        chartUric.setData(URIC_DATA);

        LineChartView chartGlucose = findViewById(R.id.chartGlucose);
        chartGlucose.setLineColor(Color.parseColor("#2D5E3C"));
        chartGlucose.setData(GLUCOSE_DATA);
    }
}
