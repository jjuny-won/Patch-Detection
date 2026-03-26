package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.ReferenceStore;

import org.opencv.android.OpenCVLoader;

import java.util.ArrayList;
import java.util.List;

public class ReferenceCaptureActivity extends AppCompatActivity {

    private TextView tvGray, tvRed, tvGreen, tvBlue, tvWhite;
    private Button btnStart, btnReset, btnList, btnSelectGallery;

    private final ArrayList<ReferenceSample> samples = new ArrayList<>();

    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<Intent> flowLauncher;
    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<Intent> listLauncher;

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reference_capture);

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        bindViews();
        setupLaunchers();
        setupButtons();
        updateUi();
    }

    private void bindViews() {
        tvGray = findViewById(R.id.tvGray);
        tvRed = findViewById(R.id.tvRed);
        tvGreen = findViewById(R.id.tvGreen);
        tvBlue = findViewById(R.id.tvBlue);
        tvWhite = findViewById(R.id.tvWhite);

        btnStart = findViewById(R.id.btnStart);
        btnReset = findViewById(R.id.btnReset);
        btnList = findViewById(R.id.btnList);
        btnSelectGallery = findViewById(R.id.btnSelectGallery);

        if (tvGray == null || tvRed == null || tvGreen == null || tvBlue == null || tvWhite == null) {
            throw new IllegalStateException("RGB TextViews not found in activity_reference_capture.xml");
        }
        if (btnStart == null || btnReset == null || btnList == null || btnSelectGallery == null) {
            throw new IllegalStateException("Required buttons not found in activity_reference_capture.xml");
        }
    }

    private void setupLaunchers() {
        flowLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                    boolean ok = result.getData().getBooleanExtra("ok", false);
                    float[] rgb15 = result.getData().getFloatArrayExtra("rgb15");

                    if (ok && rgb15 != null && rgb15.length >= 15) {
                        // 새 샘플을 맨 위에 추가
                        samples.add(0, new ReferenceSample(rgb15));
                        updateUi();
                    } else {
                        Toast.makeText(this, "샘플 저장 안함", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                    boolean ok = result.getData().getBooleanExtra("ok", false);

                    String uriStr = result.getData().getStringExtra("image_uri");
                    if (uriStr == null) uriStr = result.getData().getStringExtra("imageUri");

                    if (!ok || uriStr == null) {
                        Toast.makeText(this, "촬영 결과가 없어요", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Intent it = new Intent(this, PreviewActivity.class);
                    it.putExtra("image_uri", uriStr);
                    it.putExtra("mode", "REFERENCE");
                    flowLauncher.launch(it);
                }
        );

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;

                    Intent it = new Intent(this, AdjustRoiActivity.class);
                    it.putExtra("image_uri", uri.toString());
                    it.putExtra("source", "gallery");
                    it.putExtra("mode", "REFERENCE");
                    flowLauncher.launch(it);
                }
        );

        listLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getData() != null) {
                        @SuppressWarnings("unchecked")
                        ArrayList<ReferenceSample> updated =
                                (ArrayList<ReferenceSample>) result.getData()
                                        .getSerializableExtra("samples");

                        if (updated != null) {
                            samples.clear();
                            samples.addAll(updated);
                        }
                    }
                    updateUi();
                }
        );
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> launchCapture());

        btnSelectGallery.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        btnReset.setOnClickListener(v -> {
            samples.clear();
            updateUi();
        });

        btnList.setOnClickListener(v -> {
            Intent it = new Intent(this, ReferenceListActivity.class);
            it.putExtra("samples", samples);
            listLauncher.launch(it);
        });
    }

    private void launchCapture() {
        Intent it = new Intent(this, CameraActivity.class);
        it.putExtra("mode", "REFERENCE");
        cameraLauncher.launch(it);
    }

    private List<float[]> toRgbList() {
        List<float[]> out = new ArrayList<>();
        for (ReferenceSample s : samples) {
            if (s != null && s.rgb15 != null && s.rgb15.length >= 15) {
                out.add(s.rgb15);
            }
        }
        return out;
    }

    private void saveReference() {
        List<float[]> rgbList = toRgbList();
        int n = rgbList.size();

        if (n <= 0) {
            Toast.makeText(this, "샘플이 없어요. 먼저 추가해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        ReferenceStore.saveRgbReference(
                this,
                ReferenceStore.computeMedianRgb15(rgbList),
                n,
                rgbList
        );

        Toast.makeText(this, "Reference 저장 완료! (n=" + n + ")", Toast.LENGTH_SHORT).show();
        updateUi();
    }

    private void updateUi() {
        btnList.setEnabled(true);
        updateSavedRgbViews();
    }

    private void updateSavedRgbViews() {
        float[] saved = null;

        if (ReferenceStore.hasReference(this)) {
            saved = ReferenceStore.loadRgbReference(this);
        }

        if (saved == null || saved.length < 15) {
            setRgbViews(
                    0, 0, 0,
                    0, 0, 0,
                    0, 0, 0,
                    0, 0, 0,
                    0, 0, 0
            );
            return;
        }

        setRgbViews(
                Math.round(saved[0]),  Math.round(saved[1]),  Math.round(saved[2]),
                Math.round(saved[3]),  Math.round(saved[4]),  Math.round(saved[5]),
                Math.round(saved[6]),  Math.round(saved[7]),  Math.round(saved[8]),
                Math.round(saved[9]),  Math.round(saved[10]), Math.round(saved[11]),
                Math.round(saved[12]), Math.round(saved[13]), Math.round(saved[14])
        );
    }

    private void setRgbViews(
            int grayR, int grayG, int grayB,
            int redR, int redG, int redB,
            int greenR, int greenG, int greenB,
            int blueR, int blueG, int blueB,
            int whiteR, int whiteG, int whiteB
    ) {
        tvGray.setText(grayR + "," + grayG + "," + grayB);
        tvRed.setText(redR + "," + redG + "," + redB);
        tvGreen.setText(greenR + "," + greenG + "," + greenB);
        tvBlue.setText(blueR + "," + blueG + "," + blueB);
        tvWhite.setText(whiteR + "," + whiteG + "," + whiteB);
    }
}