package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.ReferenceStore;

import java.util.ArrayList;
import java.util.List;

public class ReferenceListActivity extends AppCompatActivity {

    private ArrayList<ReferenceSample> samples;
    private ReferenceSampleAdapter adapter;

    private RecyclerView rvSamples;
    private Button btnSave, btnDeleteAll, btnGalleryAdd, btnCameraAdd;

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<Intent> captureLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reference_list);

        captureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                    float[] rgb15 = result.getData().getFloatArrayExtra("rgb15");
                    if (rgb15 != null && rgb15.length >= 15) {
                        samples.add(0, new ReferenceSample(rgb15));
                        adapter.notifyItemInserted(0);
                        rvSamples.scrollToPosition(0);
                    }
                });

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;

                    Intent it = new Intent(this, AdjustRoiActivity.class);
                    it.putExtra("image_uri", uri.toString());
                    it.putExtra("source", "gallery");
                    it.putExtra("mode", "REFERENCE");
                    captureLauncher.launch(it);
                });

        rvSamples = findViewById(R.id.rvSamples);
        btnSave = findViewById(R.id.btnSave);
        btnDeleteAll = findViewById(R.id.btnDeleteAll);
        btnGalleryAdd = findViewById(R.id.btnGalleryAdd);
        btnCameraAdd = findViewById(R.id.btnCameraAdd);

        @SuppressWarnings("unchecked")
        ArrayList<ReferenceSample> received =
                (ArrayList<ReferenceSample>) getIntent().getSerializableExtra("samples");

        samples = (received != null) ? received : new ArrayList<>();

        setupRecyclerView();
        setupButtons();
    }

    private void setupRecyclerView() {
        rvSamples.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ReferenceSampleAdapter(samples, position -> {
            if (position >= 0 && position < samples.size()) {
                samples.remove(position);
                adapter.notifyItemRemoved(position);
                adapter.notifyItemRangeChanged(position, samples.size());
            }
        });

        rvSamples.setAdapter(adapter);
    }

    private void setupButtons() {
        if (btnGalleryAdd != null) {
            btnGalleryAdd.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        }

        if (btnCameraAdd != null) {
            btnCameraAdd.setOnClickListener(v -> {
                Intent it = new Intent(this, CameraActivity.class);
                it.putExtra("mode", "REFERENCE");
                captureLauncher.launch(it);
            });
        }

        btnDeleteAll.setOnClickListener(v -> {
            samples.clear(); // detected_data만 삭제
            adapter.notifyDataSetChanged();
        });

        btnSave.setOnClickListener(v -> {
            if (samples.isEmpty()) {
                Toast.makeText(this, "저장할 샘플이 없어요.", Toast.LENGTH_SHORT).show();
                return;
            }

            List<float[]> detectedList = new ArrayList<>();
            for (ReferenceSample sample : samples) {
                if (sample != null && sample.rgb15 != null && sample.rgb15.length >= 15) {
                    detectedList.add(sample.rgb15);
                }
            }

            if (detectedList.isEmpty()) {
                Toast.makeText(this, "유효한 샘플이 없어요.", Toast.LENGTH_SHORT).show();
                return;
            }

            List<float[]> merged = new ArrayList<>();

            List<float[]> savedSamples = ReferenceStore.loadSavedRgbSamples(this);
            if (savedSamples != null && !savedSamples.isEmpty()) {
                merged.addAll(savedSamples);
            }

            merged.addAll(detectedList);

            float[] median = ReferenceStore.computeMedianRgb15(merged);

            ReferenceStore.saveSavedReference(
                    this,
                    median,
                    merged.size(),
                    merged
            );

            Toast.makeText(this, "Reference 저장 완료! (n=" + merged.size() + ")", Toast.LENGTH_SHORT).show();

            samples.clear(); // 저장 후 detected_data 비움
            finishWithResult();
        });
    }

    private void finishWithResult() {
        Intent result = new Intent();
        result.putExtra("samples", samples);
        setResult(RESULT_OK, result);
        finish();
    }
}