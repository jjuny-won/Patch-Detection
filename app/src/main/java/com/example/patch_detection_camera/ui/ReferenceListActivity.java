package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

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
    private Button btnSave, btnDeleteAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reference_list);

        rvSamples = findViewById(R.id.rvSamples);
        btnSave = findViewById(R.id.btnSave);
        btnDeleteAll = findViewById(R.id.btnDeleteAll);

        samples = (ArrayList<ReferenceSample>) getIntent().getSerializableExtra("samples");
        if (samples == null) {
            samples = new ArrayList<>();
        }

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
        btnDeleteAll.setOnClickListener(v -> {
            samples.clear();
            adapter.notifyDataSetChanged();
        });

        btnSave.setOnClickListener(v -> {
            if (samples.isEmpty()) {
                Toast.makeText(this, "저장할 샘플이 없어요.", Toast.LENGTH_SHORT).show();
                return;
            }

            List<float[]> rgbList = new ArrayList<>();
            for (ReferenceSample sample : samples) {
                if (sample != null && sample.rgb15 != null && sample.rgb15.length >= 15) {
                    rgbList.add(sample.rgb15);
                }
            }

            if (rgbList.isEmpty()) {
                Toast.makeText(this, "유효한 샘플이 없어요.", Toast.LENGTH_SHORT).show();
                return;
            }

            float[] median = ReferenceStore.computeMedianRgb15(rgbList);
            ReferenceStore.saveRgbReference(
                    this,
                    median,
                    rgbList.size(),
                    rgbList
            );

            Toast.makeText(this, "Reference 저장 완료! (n=" + rgbList.size() + ")", Toast.LENGTH_SHORT).show();
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