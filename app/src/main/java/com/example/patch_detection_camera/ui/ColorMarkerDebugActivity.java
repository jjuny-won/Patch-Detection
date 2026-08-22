package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.PreviewPipeline;
import com.example.patch_detection_camera.processing.detection.PreviewImageLoader;
import com.example.patch_detection_camera.processing.detection.strip.ColorMarkerExtractor;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

public class ColorMarkerDebugActivity extends AppCompatActivity {

    private static final String TAG = "ColorMarkerDebugActivity";

    private boolean isReferenceMode = false;
    private float[] lastRgb15 = new float[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_color_marker_debug);

        ImageView ivRegions = findViewById(R.id.ivRegions);
        TextView tvEmpty = findViewById(R.id.tvEmpty);
        View btnBack = findViewById(R.id.btnBack);
        View btnSave = findViewById(R.id.btnSave);

        String mode = getIntent().getStringExtra("mode");
        isReferenceMode = "REFERENCE".equals(mode);

        if (isReferenceMode) {
            ((android.widget.Button) btnBack).setText("다시 촬영");
            btnBack.setOnClickListener(v -> {
                setResult(RESULT_CANCELED);
                finish();
            });
            btnSave.setVisibility(View.VISIBLE);
            btnSave.setOnClickListener(v -> returnReferenceRgb15());
        } else {
            btnBack.setOnClickListener(v -> finish());
        }

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String uriStr = getIntent().getStringExtra("image_uri");
        if (uriStr == null) {
            tvEmpty.setText("촬영 원본 이미지를 찾을 수 없습니다.");
            return;
        }

        float roiCx = getIntent().getFloatExtra("roi_cx", -1f);
        float roiCy = getIntent().getFloatExtra("roi_cy", -1f);
        float roiW  = getIntent().getFloatExtra("roi_w", -1f);
        float roiH  = getIntent().getFloatExtra("roi_h", -1f);
        boolean hasRoi = (roiCx > 0 && roiCy > 0 && roiW > 0 && roiH > 0);

        Mat rgba = null;
        Mat labeled = null;
        try {
            rgba = PreviewImageLoader.loadInputMat(this, uriStr, hasRoi, roiCx, roiCy, roiW, roiH);

            ColorMarkerExtractor.Result mr = ColorMarkerExtractor.extract(rgba, null, false);
            lastRgb15 = (mr != null && mr.rgb15 != null) ? mr.rgb15 : new float[0];

            labeled = PreviewPipeline.renderColorMarkerRegions(rgba);

            if (labeled == null || labeled.empty()) {
                tvEmpty.setText("Color Marker를 찾지 못했습니다.");
                return;
            }

            Bitmap bmp = Bitmap.createBitmap(labeled.cols(), labeled.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(labeled, bmp);
            ivRegions.setImageBitmap(bmp);

        } catch (Exception e) {
            Log.e(TAG, "renderColorMarkerRegions error", e);
            tvEmpty.setText("영역을 불러오는 중 오류가 발생했습니다.");
        } finally {
            if (rgba != null) rgba.release();
            if (labeled != null) labeled.release();
        }
    }

    private void returnReferenceRgb15() {
        if (lastRgb15 == null || lastRgb15.length < 15) {
            Toast.makeText(this, "Marker 검출 실패 - 다시 촬영하세요", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent out = new Intent();
        out.putExtra("ok", true);
        out.putExtra("rgb15", lastRgb15);
        setResult(RESULT_OK, out);
        finish();
    }
}
