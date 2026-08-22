package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.ReferenceStore;

import java.util.Date;
import java.util.Locale;

public class ColorMarkerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_color_marker);

        float[] refMarker = ReferenceStore.loadActiveReferenceRgb(this);
        float[] curMarker = getIntent().getFloatArrayExtra("rgb15");

        // Reference Value
        bindRgb(R.id.tvRefGray,  refMarker, 0);
        bindRgb(R.id.tvRefRed,   refMarker, 3);
        bindRgb(R.id.tvRefGreen, refMarker, 6);
        bindRgb(R.id.tvRefBlue,  refMarker, 9);
        bindRgb(R.id.tvRefWhite, refMarker, 12);

        // Detected Value
        bindRgb(R.id.tvDetGray,  curMarker, 0);
        bindRgb(R.id.tvDetRed,   curMarker, 3);
        bindRgb(R.id.tvDetGreen, curMarker, 6);
        bindRgb(R.id.tvDetBlue,  curMarker, 9);
        bindRgb(R.id.tvDetWhite, curMarker, 12);


        // 보정 결과 카드
        updateCalibCard();

        // Patch 칩 클릭 → Debug로 back
        findViewById(R.id.tvChip).setOnClickListener(v -> finish());

        // 샘플링 영역 보기 → ColorMarkerDebugActivity
        findViewById(R.id.btnViewRegions).setOnClickListener(v -> {
            Intent it = new Intent(this, ColorMarkerDebugActivity.class);
            it.putExtra("image_uri", getIntent().getStringExtra("image_uri"));
            it.putExtra("roi_cx", getIntent().getFloatExtra("roi_cx", -1f));
            it.putExtra("roi_cy", getIntent().getFloatExtra("roi_cy", -1f));
            it.putExtra("roi_w",  getIntent().getFloatExtra("roi_w", -1f));
            it.putExtra("roi_h",  getIntent().getFloatExtra("roi_h", -1f));
            startActivity(it);
        });

        // 하단 버튼
        findViewById(R.id.btnMonitoring).setOnClickListener(v ->
                startActivity(new Intent(this, WoundMonitoringActivity.class)));

        findViewById(R.id.btnResult).setOnClickListener(v -> {
            Intent it = new Intent(this, ResultActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });

        findViewById(R.id.btnHome).setOnClickListener(v -> {
            Intent it = new Intent(this, ModeSelectActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });
    }

    private void updateCalibCard() {
        String grade = getIntent().getStringExtra("grade");
        if (grade == null) return; // 보정 데이터 없으면 카드 숨김

        float improvementPct    = getIntent().getFloatExtra("improvementPct",    Float.NaN);
        float exposureGain      = getIntent().getFloatExtra("exposureGain",      Float.NaN);
        float beforeDeltaEMed   = getIntent().getFloatExtra("beforeDeltaEMedian", Float.NaN);
        float afterDeltaEMed    = getIntent().getFloatExtra("afterDeltaEMedian",  Float.NaN);
        float beforeDeltaEMax   = getIntent().getFloatExtra("beforeDeltaEMax",    Float.NaN);
        float afterDeltaEMax    = getIntent().getFloatExtra("afterDeltaEMax",     Float.NaN);

        LinearLayout card = findViewById(R.id.cardCalib);
        card.setVisibility(View.VISIBLE);

        // 등급 배지
        TextView tvGrade = findViewById(R.id.tvCalibGrade);
        tvGrade.setText(grade);
        int badgeColor;
        switch (grade) {
            case "PASS": badgeColor = 0xFF44BB66; break;
            case "WARN": badgeColor = 0xFFFF9922; break;
            default:     badgeColor = 0xFFEE4444; break;
        }
        tvGrade.setBackgroundColor(badgeColor);
        tvGrade.setTextColor(0xFFFFFFFF);

        // ΔE 보정 전: "중앙값 X.X  최대 X.X"
        TextView tvBefore = findViewById(R.id.tvDeltaEBefore);
        if (Float.isFinite(beforeDeltaEMed)) {
            tvBefore.setText(String.format(Locale.US, "중앙값 %.1f  최대 %.1f",
                    beforeDeltaEMed, beforeDeltaEMax));
        } else {
            tvBefore.setText("N/A");
        }

        // ΔE 보정 후: "중앙값 X.X  최대 X.X"
        TextView tvAfter = findViewById(R.id.tvDeltaEAfter);
        if (Float.isFinite(afterDeltaEMed)) {
            tvAfter.setText(String.format(Locale.US, "중앙값 %.1f  최대 %.1f",
                    afterDeltaEMed, afterDeltaEMax));
            // 기준선: PASS=초록, WARN=주황, FAIL=빨강
            tvAfter.setTextColor(badgeColor);
        } else {
            tvAfter.setText("N/A");
        }

        // 개선율
        TextView tvImp = findViewById(R.id.tvImprovement);
        if (Float.isFinite(improvementPct)) {
            tvImp.setText(String.format(Locale.US, "↑ %.1f%%", improvementPct));
            tvImp.setTextColor(improvementPct >= 0 ? 0xFF44BB66 : 0xFFEE4444);
        } else {
            tvImp.setText("N/A");
        }

        // 노출 보정
        TextView tvExp = findViewById(R.id.tvExposureGain);
        if (Float.isFinite(exposureGain)) {
            tvExp.setText(String.format(Locale.US, "× %.2f", exposureGain));
        } else {
            tvExp.setText("N/A");
        }
    }

    private void bindRgb(int viewId, float[] arr, int offset) {
        TextView tv = findViewById(viewId);
        if (arr == null || arr.length < offset + 3) {
            tv.setText("N/A");
            return;
        }
        tv.setText(String.format(Locale.US, "%.0f, %.0f, %.0f",
                arr[offset], arr[offset + 1], arr[offset + 2]));
    }
}
