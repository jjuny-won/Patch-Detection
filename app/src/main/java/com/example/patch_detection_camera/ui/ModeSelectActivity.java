package com.example.patch_detection_camera.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.patch_detection_camera.R;
import com.example.patch_detection_camera.processing.ReferenceStore;


public class ModeSelectActivity extends AppCompatActivity {

    private Button btnCamera;
    private Button btnGallery;
    private Button btnReference;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;

                Intent intent = new Intent(this, AdjustRoiActivity.class);
                intent.putExtra("image_uri", uri.toString());
                intent.putExtra("source", "gallery");
                startActivity(intent);
            });

    @Override
    protected void onResume() {
        super.onResume();
        updateButtons();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_select);

        btnCamera = findViewById(R.id.btnCamera);
        btnGallery = findViewById(R.id.btnGallery);
        btnReference = findViewById(R.id.btnReference);

        btnCamera.setOnClickListener(v ->
                startActivity(new Intent(this, CameraActivity.class)));

        btnGallery.setOnClickListener(v ->
                pickImageLauncher.launch("image/*"));

        btnReference.setOnClickListener(v ->
                startActivity(new Intent(this, ReferenceCaptureActivity.class)));

        updateButtons();
    }

    private void updateButtons() {
        boolean hasRef = ReferenceStore.hasReference(this);
        btnCamera.setEnabled(hasRef);
        btnGallery.setEnabled(hasRef);

        // 시각적으로도 흐리게
        float alpha = hasRef ? 1.0f : 0.4f;
        btnCamera.setAlpha(alpha);
        btnGallery.setAlpha(alpha);
    }
}