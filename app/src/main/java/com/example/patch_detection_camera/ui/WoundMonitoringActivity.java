package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;

import java.util.List;
import java.util.Locale;

public class WoundMonitoringActivity extends AppCompatActivity {

    // Firestore에 데이터가 없을 때 사용하는 샘플 데이터
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

        loadChartsFromFirebase();

        findViewById(R.id.btnDressingHistory).setOnClickListener(v ->
                startActivity(new Intent(this, DressingHistoryActivity.class)));

        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());

        findViewById(R.id.btnHome).setOnClickListener(v -> {
            Intent it = new Intent(this, ModeSelectActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });
    }

    private void exportCsv() {
        Toast.makeText(this, "데이터 불러오는 중...", Toast.LENGTH_SHORT).show();
        FirebaseRepository.getInstance().loadMeasurements(new FirebaseRepository.MeasurementV2Callback() {
            @Override
            public void onSuccess(List<Measurement> measurements) {
                if (measurements.isEmpty()) {
                    Toast.makeText(WoundMonitoringActivity.this, "저장된 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
                    return;
                }
                ExportUtils.exportMeasurementsCsv(WoundMonitoringActivity.this, measurements);
            }
            @Override
            public void onFailure(Exception e) {
                Toast.makeText(WoundMonitoringActivity.this, "데이터 로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadChartsFromFirebase() {
        FirebaseRepository.getInstance().loadMeasurements(new FirebaseRepository.MeasurementV2Callback() {
            @Override
            public void onSuccess(List<Measurement> measurements) {
                if (measurements.isEmpty()) {
                    setupSampleCharts();
                    return;
                }

                int size = measurements.size();
                float[]  ph   = new float[size];
                float[]  temp = new float[size];
                float[]  uric = new float[size];
                float[]  gluc = new float[size];
                long[]   ts   = new long[size];
                String[] phL  = new String[size];
                String[] tpL  = new String[size];
                String[] urL  = new String[size];
                String[] glL  = new String[size];

                for (int i = 0; i < size; i++) {
                    Measurement m   = measurements.get(i);
                    SensorValue phV = m.getSensor("pH");
                    SensorValue tpV = m.getSensor("Temperature");
                    SensorValue urV = m.getSensor("UricAcid");
                    SensorValue glV = m.getSensor("Glucose");

                    ph[i]   = phV != null ? clamp01((phV.numericValue - 4.0f)  / 6.0f)  : 0f;
                    temp[i] = tpV != null ? clamp01((tpV.numericValue - 30.0f) / 12.0f) : 0f;
                    uric[i] = normalizeUric(urV);
                    gluc[i] = normalizeGluc(glV);
                    ts[i]   = m.capturedAt;

                    phL[i] = phV != null ? String.format(Locale.US, "pH %.1f", phV.numericValue) : "N/A";
                    tpL[i] = tpV != null ? String.format(Locale.US, "%.1f °C", tpV.numericValue) : "N/A";
                    urL[i] = sensorLabel(urV, "%.0f");
                    glL[i] = sensorLabel(glV, "%.2f");
                }

                setupChartsWithData(ph, temp, uric, gluc, ts, phL, tpL, urL, glL);
            }

            @Override
            public void onFailure(Exception e) {
                Toast.makeText(WoundMonitoringActivity.this,
                        "데이터 로드 실패. 샘플 데이터를 표시합니다.", Toast.LENGTH_SHORT).show();
                setupSampleCharts();
            }
        });
    }

    // ── 정규화 헬퍼 ──────────────────────────────────────────────────────────

    private static float normalizeUric(SensorValue sv) {
        if (sv == null) return 0f;
        // 구 데이터: mg/dL (0~100 범위) / 새 데이터: μmol/L (0~600 범위)
        float divisor = "mg/dL".equals(sv.unit) ? 100.0f : 600.0f;
        return clamp01(sv.numericValue / divisor);
    }

    private static float normalizeGluc(SensorValue sv) {
        if (sv == null) return 0f;
        // 구 데이터: mg/dL (0~100 범위) / 새 데이터: mmol/L (0~20 범위)
        float divisor = "mg/dL".equals(sv.unit) ? 100.0f : 20.0f;
        return clamp01(sv.numericValue / divisor);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static String sensorLabel(SensorValue sv, String fmt) {
        if (sv == null) return "N/A";
        return String.format(Locale.US, fmt + " %s", sv.numericValue, sv.unit);
    }

    // ── 차트 세팅 ────────────────────────────────────────────────────────────

    private void setupSampleCharts() {
        setupChartsWithData(
                PH_DATA, TEMP_DATA, URIC_DATA, GLUCOSE_DATA, null,
                makeNormLabels(PH_DATA,      "pH %.1f",      4.0f,  6.0f),
                makeNormLabels(TEMP_DATA,    "%.1f °C",      30.0f, 12.0f),
                makeNormLabels(URIC_DATA,    "%.0f μmol/L",  0f,    600.0f),
                makeNormLabels(GLUCOSE_DATA, "%.1f mmol/L",  0f,    20.0f));
    }

    private void setupChartsWithData(float[] ph, float[] temp, float[] uric, float[] gluc,
                                     long[] ts,
                                     String[] phL, String[] tpL, String[] urL, String[] glL) {
        LineChartView chartPh = findViewById(R.id.chartPh);
        chartPh.setLineColor(Color.parseColor("#7765C5"));
        chartPh.setDataWithMeta(ph, ts, phL);

        LineChartView chartTemp = findViewById(R.id.chartTemp);
        chartTemp.setLineColor(Color.parseColor("#E07050"));
        chartTemp.setDataWithMeta(temp, ts, tpL);

        LineChartView chartUric = findViewById(R.id.chartUricAcid);
        chartUric.setLineColor(Color.parseColor("#3A5580"));
        chartUric.setDataWithMeta(uric, ts, urL);

        LineChartView chartGlucose = findViewById(R.id.chartGlucose);
        chartGlucose.setLineColor(Color.parseColor("#2D5E3C"));
        chartGlucose.setDataWithMeta(gluc, ts, glL);
    }

    /** 샘플 데이터용: norm 값에서 실제 값으로 역산해 라벨 생성 */
    private String[] makeNormLabels(float[] norm, String fmt, float base, float range) {
        String[] out = new String[norm.length];
        for (int i = 0; i < norm.length; i++)
            out[i] = String.format(Locale.US, fmt, base + norm[i] * range);
        return out;
    }
}
