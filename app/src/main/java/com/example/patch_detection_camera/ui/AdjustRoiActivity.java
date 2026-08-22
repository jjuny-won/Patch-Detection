package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.databinding.ActivityAdjustRoiBinding;
import com.example.patch_detection_camera.ml.BiomarkerClassifier;
import com.example.patch_detection_camera.processing.ImageNormalizer;

public class AdjustRoiActivity extends AppCompatActivity {

    private static final String TAG = "AdjustRoi";
    private ActivityAdjustRoiBinding vb;

    private Bitmap sourceNormalized;
    private ActivityResultLauncher<Intent> previewLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vb = ActivityAdjustRoiBinding.inflate(getLayoutInflater());
        setContentView(vb.getRoot());

        String uriStr = getIntent().getStringExtra("image_uri");
        if (uriStr == null) { finish(); return; }
        Uri uri = Uri.parse(uriStr);

        final String mode = getIntent().getStringExtra("mode");   // "REFERENCE" expected
        final String source = getIntent().getStringExtra("source"); // "gallery" 등

        previewLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        // PreviewActivity가 준 ok/rgb15 그대로 상위로 전달
                        setResult(RESULT_OK, result.getData());
                    } else {
                        setResult(RESULT_CANCELED);
                    }
                    finish();
                }
        );

        try {
            ImageNormalizer.Result r = ImageNormalizer.loadNormalized(this, uri, 2048);
            sourceNormalized = r.bitmap;
            vb.iv.setImageBitmap(sourceNormalized);

        } catch (Exception e) {
            Log.e(TAG, "image load/normalize failed", e);
            Toast.makeText(this, "이미지 로드 실패", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        vb.btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        vb.btnOk.setOnClickListener(v -> {
            NormalizedRoi nroi = computeNormalizedRoiFromView();
            if (nroi == null) {
                Toast.makeText(this, "ROI 계산 실패", Toast.LENGTH_SHORT).show();
                return;
            }


            // REFERENCE 모드에서는 도형 탐지가 필요 없으니 Color Marker
            // 샘플링 화면으로 바로 넘어간다.
            Class<?> nextActivity = "REFERENCE".equals(mode)
                    ? ColorMarkerDebugActivity.class
                    : PreviewActivity.class;
            Intent intent = new Intent(this, nextActivity);
            intent.putExtra("image_uri", uriStr);
            if (source != null) intent.putExtra("source", source);

            if (mode != null) intent.putExtra("mode", mode); // "REFERENCE"

            // ROI 전달
            intent.putExtra("roi_cx", nroi.cx);
            intent.putExtra("roi_cy", nroi.cy);
            intent.putExtra("roi_w",  nroi.w);
            intent.putExtra("roi_h",  nroi.h);

            previewLauncher.launch(intent);
        });
    }

    private NormalizedRoi computeNormalizedRoiFromView() {
        if (sourceNormalized == null) return null;

        RectF roiView = vb.overlay.getRoiRectInView();
        if (roiView == null || roiView.width() <= 1 || roiView.height() <= 1) return null;

        Matrix m = vb.iv.getImageMatrixCopy();
        Matrix inv = new Matrix();
        if (!m.invert(inv)) return null;

        // Map ROI circle center: view coords → bitmap coords (handles rotation correctly)
        float[] centerPt = { roiView.centerX(), roiView.centerY() };
        inv.mapPoints(centerPt);

        // Map a point on the right edge of the circle to compute radius in bitmap coords
        float[] edgePt = { roiView.centerX() + roiView.width() / 2f, roiView.centerY() };
        inv.mapPoints(edgePt);

        float bmpCx = centerPt[0];
        float bmpCy = centerPt[1];
        float bmpR  = (float) Math.sqrt(
                (edgePt[0] - bmpCx) * (edgePt[0] - bmpCx) +
                (edgePt[1] - bmpCy) * (edgePt[1] - bmpCy));

        float bw = sourceNormalized.getWidth();
        float bh = sourceNormalized.getHeight();

        float cx = clamp(bmpCx / bw, 0f, 1f);
        float cy = clamp(bmpCy / bh, 0f, 1f);
        float nw = clamp(2f * bmpR / bw, 0.001f, 1f);
        float nh = clamp(2f * bmpR / bh, 0.001f, 1f);

        return new NormalizedRoi(cx, cy, nw, nh);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static class NormalizedRoi {
        final float cx, cy, w, h;
        NormalizedRoi(float cx, float cy, float w, float h) {
            this.cx = cx; this.cy = cy; this.w = w; this.h = h;
        }
    }
}