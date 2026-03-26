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
import com.example.patch_detection_camera.processing.ImageNormalizer;

public class AdjustRoiActivity extends AppCompatActivity {

    private static final String TAG = "AdjustRoi";
    private ActivityAdjustRoiBinding vb;

    private Bitmap sourceNormalized;

    // ✅ PreviewActivity 결과 받기
    private ActivityResultLauncher<Intent> previewLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vb = ActivityAdjustRoiBinding.inflate(getLayoutInflater());
        setContentView(vb.getRoot());

        String uriStr = getIntent().getStringExtra("image_uri");
        if (uriStr == null) { finish(); return; }
        Uri uri = Uri.parse(uriStr);

        // ✅ mode를 그대로 전달 (REFERENCE/일반)
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

            // ✅ PreviewActivity를 forResult로 띄움
            Intent intent = new Intent(this, PreviewActivity.class);
            intent.putExtra("image_uri", uriStr);
            if (source != null) intent.putExtra("source", source);

            // ✅ 중요: PreviewActivity가 reference 저장 로직을 타도록 mode 전달
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

        RectF roiBmp = new RectF();
        inv.mapRect(roiBmp, roiView);

        float bw = sourceNormalized.getWidth();
        float bh = sourceNormalized.getHeight();

        float left   = clamp(roiBmp.left,   0f, bw);
        float top    = clamp(roiBmp.top,    0f, bh);
        float right  = clamp(roiBmp.right,  0f, bw);
        float bottom = clamp(roiBmp.bottom, 0f, bh);

        float w = Math.max(1f, right - left);
        float h = Math.max(1f, bottom - top);

        float cx = (left + right) * 0.5f / bw;
        float cy = (top + bottom) * 0.5f / bh;
        float nw = w / bw;
        float nh = h / bh;

        cx = clamp(cx, 0f, 1f);
        cy = clamp(cy, 0f, 1f);
        nw = clamp(nw, 0.001f, 1f);
        nh = clamp(nh, 0.001f, 1f);

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