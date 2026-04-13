package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
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

        // Reference Value (RGB15: Gray=0-2, Red=3-5, Green=6-8, Blue=9-11, White=12-14)
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

        // Updated time
        TextView tvTime = findViewById(R.id.tvUpdatedTime);
        String formatted = DateFormat.format("MMM dd, yyyy hh:mm a", new Date()).toString();
        tvTime.setText("Updated " + formatted);

        // Patch 칩 클릭 → Debug로 back
        findViewById(R.id.tvChip).setOnClickListener(v -> finish());

        // 하단 버튼
        findViewById(R.id.btnMonitoring).setOnClickListener(v ->
                Toast.makeText(this, "준비 중", Toast.LENGTH_SHORT).show());

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
