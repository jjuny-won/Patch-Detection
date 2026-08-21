package com.example.patch_detection_camera.ui;
import com.example.patch_detection_camera.processing.ReferenceStore;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.PreviewPipeline;
import com.example.patch_detection_camera.processing.ShapeJsonUtils;
import com.example.patch_detection_camera.processing.calibration.ColorCalibrator;
import com.example.patch_detection_camera.processing.detection.PreviewAnalysisResult;
import com.example.patch_detection_camera.processing.detection.PreviewImageLoader;



import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.RotatedRect;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class PreviewActivity extends AppCompatActivity {

    private static final String TAG = "PreviewActivity";

    private android.widget.TextView tvDebug;
    private Spinner spDebugView;
    private Button btnDebugToggle;
    private Button btnBandPrev, btnBandNext;
    private ImageView ivResult;
    private Button btnRetry;
    private Button btnResult;

    private boolean debugTextVisible = true;
    private boolean isReferenceMode = false;

    private String uriStr;
    private float roiCx, roiCy, roiW, roiH;
    private boolean hasRoi;

    private float[] lastRgb15 = new float[0];

    // PreviewActivity.java
    enum DebugView {
        OVERLAY,
        VALID,
        SHAPE_MASK,
        STRIP_CANDIDATE,
        STRIP_EDGES,
        STRIP_WARP,
        STRIP_MASK
    }

    private DebugView debugView = DebugView.OVERLAY;
    private int stripMaskBandIdx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        exportAssetsFolderToGallery("test/test_images", "PatchDetection");

        spDebugView = findViewById(R.id.spDebugView);
        btnDebugToggle = findViewById(R.id.btnDebugToggle);
        btnBandPrev = findViewById(R.id.btnBandPrev);
        btnBandNext = findViewById(R.id.btnBandNext);
        ivResult = findViewById(R.id.ivResult);
        btnRetry = findViewById(R.id.btnRetry);
        btnResult = findViewById(R.id.btnResult);

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        uriStr = getIntent().getStringExtra("image_uri");
        if (uriStr == null) {
            Toast.makeText(this, "No image_uri", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        roiCx = getIntent().getFloatExtra("roi_cx", -1f);
        roiCy = getIntent().getFloatExtra("roi_cy", -1f);
        roiW = getIntent().getFloatExtra("roi_w", -1f);
        roiH = getIntent().getFloatExtra("roi_h", -1f);
        hasRoi = (roiCx > 0 && roiCy > 0 && roiW > 0 && roiH > 0);

        String mode = getIntent().getStringExtra("mode");
        isReferenceMode = "REFERENCE".equals(mode);
        Log.d(TAG, "mode=" + mode + " isReferenceMode=" + isReferenceMode);

        if (isReferenceMode) {
            btnResult.setText("이 값 저장");
            btnRetry.setText("다시 촬영");
        } else {
            btnResult.setText("결과 보기");
            btnRetry.setText("다시 찍기");
        }

        btnResult.setOnClickListener(v -> {
            Log.d(TAG, "btnResult click, isReferenceMode=" + isReferenceMode);
            if (isReferenceMode) {
                returnReferenceRgb15();
            } else {
                goToResult();
            }
        });

        btnRetry.setOnClickListener(v -> {
            Log.d(TAG, "btnRetry click, isReferenceMode=" + isReferenceMode);
            if (isReferenceMode) {
                setResult(RESULT_CANCELED);
            }
            finish();
        });

        setupDebugSpinner();

        btnDebugToggle.setOnClickListener(v -> {
            debugTextVisible = !debugTextVisible;
            tvDebug.setVisibility(debugTextVisible ? View.VISIBLE : View.GONE);
            btnDebugToggle.setText(debugTextVisible ? "DEBUG ON" : "DEBUG OFF");
        });

        btnBandPrev.setOnClickListener(v -> {
            stripMaskBandIdx = (stripMaskBandIdx + 4) % 5;
            renderPreviewOverlay();
        });

        btnBandNext.setOnClickListener(v -> {
            stripMaskBandIdx = (stripMaskBandIdx + 1) % 5;
            renderPreviewOverlay();
        });

        syncBandButtonsVisibility();
        renderPreviewOverlay();
    }

    private void returnReferenceRgb15() {
        if (lastRgb15 == null || lastRgb15.length < 15) {
            Log.d(TAG, "Marker detection failed. Rgb15 is empty or null.");
            Toast.makeText(this, "Marker 검출 실패 - 다시 촬영하세요", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Returning RGB15 values: " + Arrays.toString(lastRgb15));
        Intent out = new Intent();
        out.putExtra("ok", true);
        out.putExtra("rgb15", lastRgb15);
        setResult(RESULT_OK, out);
        finish();
    }

    private void setupDebugSpinner() {
        String[] items = new String[DebugView.values().length];
        for (int i = 0; i < DebugView.values().length; i++) {
            items[i] = DebugView.values()[i].name();
        }

        ArrayAdapter<String> ad = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                items
        );
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDebugView.setAdapter(ad);
        spDebugView.setSelection(debugView.ordinal(), false);

        spDebugView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                debugView = DebugView.values()[pos];
                syncBandButtonsVisibility();
                renderPreviewOverlay();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void syncBandButtonsVisibility() {
        boolean show = (debugView == DebugView.STRIP_MASK);
        btnBandPrev.setVisibility(show ? View.VISIBLE : View.GONE);
        btnBandNext.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void renderPreviewOverlay() {
        Mat rgba = null;
        try {
            rgba = PreviewImageLoader.loadInputMat(
                    this,
                    uriStr,
                    hasRoi,
                    roiCx, roiCy, roiW, roiH
            );

            PreviewPipeline.Options opt = new PreviewPipeline.Options();
            opt.enableWhiteDisc = true;
            PreviewPipeline.DebugView v = PreviewPipeline.DebugView.valueOf(debugView.name());

            try (PreviewPipeline.Out out =
                         PreviewPipeline.run(rgba, v, stripMaskBandIdx, opt)) {

                if (out.displayRgba != null && !out.displayRgba.empty()) {
                    showMatAsBitmap(out.displayRgba);
                } else {
                    showMatAsBitmap(rgba);
                }

                lastRgb15 = (out.rgb15 != null) ? out.rgb15 : new float[0];


            }

        } catch (Exception e) {
            Log.e(TAG, "renderPreviewOverlay error", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (rgba != null) {
                rgba.release();
            }
        }
    }

    private void goToResult() {
        Mat rgba = null;
        try {
            rgba = PreviewImageLoader.loadInputMat(
                    this, uriStr, hasRoi, roiCx, roiCy, roiW, roiH
            );

            PreviewPipeline.Options opt = new PreviewPipeline.Options();
            opt.enableWhiteDisc = true;

            try (PreviewPipeline.Out out =
                         PreviewPipeline.run(rgba, PreviewPipeline.DebugView.OVERLAY, 0, opt)) {

                float[] rawRgb15 = out.rgb15;
                float[] correctedRgb15 = null;

                ColorCalibrator.Model model = null;
                ColorCalibrator.EvalResult eval = null;

                float[] refRgb15 = ReferenceStore.loadActiveReferenceRgb(
                        this);

                if (rawRgb15 == null || rawRgb15.length < 12) {
                    Toast.makeText(this, "RGB 검출 실패", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (refRgb15 != null && refRgb15.length == rawRgb15.length) {
                    model = ColorCalibrator.fitGammaWbCcmFromMarker(rawRgb15, refRgb15);

                    if (model != null && model.ok) {
                        correctedRgb15 = buildCorrectedRgb15(rawRgb15, model);
                        eval = ColorCalibrator.evaluateWithModel(rawRgb15, refRgb15, model);
                    } else {
                        Log.d(TAG, "Calibration model fit failed");
                    }
                } else {
                    Log.d(TAG, "Reference missing or length mismatch");
                }

                // 여기서 JSON 생성
                String shapesJson = out.shapesJson;

                String correctedShapesJson;
                if (out.shapeCandidates != null) {
                    correctedShapesJson = ShapeJsonUtils.toCorrectedJson(out.shapeCandidates, model);
                } else {
                    correctedShapesJson = "[]";
                    Log.w(TAG, "out.shapeCandidates is null");
                }

                Log.d(TAG, "shapes_json=" + shapesJson);
                Log.d(TAG, "corrected_shapes_json=" + correctedShapesJson);

                Intent intent = new Intent(this, ResultActivity.class);
                intent.putExtra("image_uri", uriStr);
                intent.putExtra("rgb15", rawRgb15);
                intent.putExtra("correctedRgb15", correctedRgb15);

                intent.putExtra("shapes_json", shapesJson);
                intent.putExtra("corrected_shapes_json", correctedShapesJson);

                if (model != null && model.ok) {
                    intent.putExtra("grade", model.grade);
                    intent.putExtra("reason", model.reason);
                    intent.putExtra("beforeRmse", (float) model.beforeRmse);
                    intent.putExtra("afterRmse", (float) model.afterRmse);
                    intent.putExtra("improvementPct", (float) model.improvementPct);
                    intent.putExtra("exposureGain", (float) model.exposureGain);
                    intent.putExtra("wbR", (float) model.wb[0]);
                    intent.putExtra("wbG", (float) model.wb[1]);
                    intent.putExtra("wbB", (float) model.wb[2]);
                }

                if (eval != null) {
                    intent.putExtra("beforeDeltaEMedian", (float) eval.beforeDeltaEMedian);
                    intent.putExtra("afterDeltaEMedian", (float) eval.afterDeltaEMedian);
                    intent.putExtra("beforeDeltaEMax", (float) eval.beforeDeltaEMax);
                    intent.putExtra("afterDeltaEMax", (float) eval.afterDeltaEMax);
                }

                startActivity(intent);
            }

        } catch (Exception e) {
            Log.e(TAG, "goToResult error", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (rgba != null) rgba.release();
        }
    }


    private void showMatAsBitmap(Mat rgba) {
        Bitmap outBmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, outBmp);
        ivResult.setImageBitmap(outBmp);
    }

    private void exportAssetsFolderToGallery(String assetsDir, String albumName) {
        try {
            String[] files = getAssets().list(assetsDir);
            if (files == null || files.length == 0) {
                Toast.makeText(this, "assets에 이미지가 없어요: " + assetsDir, Toast.LENGTH_SHORT).show();
                return;
            }

            int copied = 0;
            for (String name : files) {
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                    continue;
                }

                String assetPath = assetsDir + "/" + name;

                if (existsInMediaStore(this, albumName, name)) {
                    continue;
                }

                Uri outUri = saveAssetImageToMediaStore(this, assetPath, albumName, name);
                if (outUri != null) {
                    copied++;
                }
            }

            Toast.makeText(this, "갤러리에 " + copied + "장 추가됨", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "갤러리 추가 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "exportAssetsFolderToGallery error", e);
        }
    }

    private boolean existsInMediaStore(Context ctx, String albumName, String fileName) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] proj = {MediaStore.Images.Media._ID};
        String sel = MediaStore.Images.Media.RELATIVE_PATH + "=? AND " +
                MediaStore.Images.Media.DISPLAY_NAME + "=?";
        String relPath = "Pictures/" + albumName + "/";
        String[] args = {relPath, fileName};

        try (Cursor c = ctx.getContentResolver().query(collection, proj, sel, args, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception ignore) {
            return false;
        }
    }

    private Uri saveAssetImageToMediaStore(Context ctx, String assetPath, String albumName, String fileName) {
        try (InputStream is = ctx.getAssets().open(assetPath)) {
            String mime = fileName.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + albumName + "/");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri item = ctx.getContentResolver().insert(collection, values);
            if (item == null) return null;

            try (OutputStream os = ctx.getContentResolver().openOutputStream(item)) {
                if (os == null) return null;

                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(item, done, null, null);
            }

            return item;

        } catch (Exception e) {
            Log.e(TAG, "saveAssetImageToMediaStore error: " + assetPath, e);
            return null;
        }
    }

    private float[] buildCorrectedRgb15(float[] rgb15, ColorCalibrator.Model model) {
        if (rgb15 == null || rgb15.length < 12 || model == null || !model.ok) {
            return null;
        }

        float[] corrected = new float[rgb15.length];

        for (int i = 0; i + 2 < rgb15.length; i += 3) {
            int r = Math.round(rgb15[i]);
            int g = Math.round(rgb15[i + 1]);
            int b = Math.round(rgb15[i + 2]);

            int[] corr = ColorCalibrator.applyModelToRgb(r, g, b, model);

            corrected[i] = corr[0];
            corrected[i + 1] = corr[1];
            corrected[i + 2] = corr[2];
        }

        return corrected;
    }
}