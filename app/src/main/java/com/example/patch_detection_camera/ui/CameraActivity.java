package com.example.patch_detection_camera.ui;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Toast;

import android.hardware.camera2.CaptureRequest;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.patch_detection_camera.databinding.ActivityCameraBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CameraActivity (완성본)
 * - PreviewView 위에 RoiOverlayView(원 가이드)가 올라가 있다고 가정
 * - Pinch Zoom + Tap Focus 지원
 * - 촬영 직전 중앙 AF/AE 재시도 + 400ms 대기 후 촬영
 * - 촬영 결과는 "AdjustRoiActivity"로 넘겨서 사용자가 ROI에 맞게 이미지 이동/확대 후 crop 하도록 유도
 *
 * 필요:
 * 1) AdjustRoiActivity 추가(촬영/갤러리 공통)
 * 2) activity_camera.xml 에 RoiOverlayView overlay 유지
 */
public class CameraActivity extends AppCompatActivity {
    private boolean isReferenceMode = false;
    private String mode = null;

    private ActivityResultLauncher<Intent> previewLauncher;

    private static final String TAG = "CameraXApp";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    private static final float DEFAULT_ZOOM_RATIO = 2.5f;

    private ActivityCameraBinding viewBinding;

    private androidx.camera.core.Camera camera;
    private ImageCapture imageCapture;

    private ExecutorService cameraExecutor;

    // Pinch zoom
    private ScaleGestureDetector scaleGestureDetector;
    private float currentZoomRatio = DEFAULT_ZOOM_RATIO;

    // 수평 가이드라인 (가속도계)
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private final SensorEventListener levelListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            // rotation vector → 회전 행렬 → orientation(방위각, 피치, 롤)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);

        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        viewBinding = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();
        mode = getIntent().getStringExtra("mode");
        isReferenceMode = "REFERENCE".equals(mode);

        // 수평 센서 초기화
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        previewLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (data == null) data = new Intent();

                    setResult(result.getResultCode(), data);
                    finish();
                }
        );
        // Tap focus + pinch zoom 같이 처리
        viewBinding.viewFinder.setClickable(true);
        viewBinding.viewFinder.setFocusable(true);
        viewBinding.viewFinder.setOnTouchListener((v, event) -> {

            if (event.getPointerCount() == 1 && event.getAction() == MotionEvent.ACTION_UP) {
                tapToFocus(event.getX(), event.getY());
                return true;
            }
            return true;
        });

        viewBinding.imageCaptureButton.setOnClickListener(v -> takePhoto());

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        // 회전 안정성(ROI 흔들림 감소)
                        .setTargetRotation(viewBinding.viewFinder.getDisplay().getRotation())
                        .build();

                provider.unbindAll();

                camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                );

                // view 레이아웃 잡힌 뒤 1회만 세팅
                viewBinding.viewFinder.post(() -> {
                    applyCameraDefaults("after bind/post");
                });

            } catch (Exception e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void applyCameraDefaults(String where) {
        viewBinding.viewFinder.post(() -> {
            setZoomRatioSafe(DEFAULT_ZOOM_RATIO);

            // 프리뷰 중에는 AWB를 다시 auto/unlock 상태로 되돌려 씬에 맞게 수렴하게 둔다.
            setAwbLock(false);
            lockAfAeToCenterOnce();
            setExposureCompensation(0);

            logZoomState(where);
        });
    }
    private void takePhoto() {
        if (imageCapture == null) return;
        applyCameraDefaults("before capture");
        // 400ms 동안 AF/AE/AWB가 중앙 기준으로 수렴하도록 대기 후,
        // 셔터 직전 AWB를 고정(lock)해서 촬영 순간의 화이트밸런스가
        // 촬영 도중 다시 흔들리지 않도록 한다.
        viewBinding.viewFinder.postDelayed(() -> {
            setAwbLock(true);
            viewBinding.viewFinder.postDelayed(this::reallyTakePhoto, 150);
        }, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (camera != null) {
            applyCameraDefaults("onResume");
        }

        // 센서 등록 (배터리 절약: GAME 속도 사용 - UI보다 빠르고 FASTEST보다 절약)
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(
                    levelListener,
                    rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 백그라운드 시 센서 해제 (배터리 절약)
        sensorManager.unregisterListener(levelListener);
    }

    private void reallyTakePhoto() {
        if (imageCapture == null) return;

        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());

        ContentValues contentValues = new ContentValues();
        contentValues.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/patch_detection");
        }

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(
                        getContentResolver(),
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                ).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        setAwbLock(false);

                        Uri savedUri = outputFileResults.getSavedUri();
                        if (savedUri == null) {
                            Toast.makeText(CameraActivity.this, "Saved URI is null", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // ✅ ROI 값을 먼저 계산해서 intent에 넣고, startActivity는 딱 1번만
                        viewBinding.viewFinder.post(() -> {

                            RoiOverlayView overlay = viewBinding.roiOverlay;
                            RectF roiRect = overlay.getRoiRectInView();

                            float viewW = viewBinding.viewFinder.getWidth();
                            float viewH = viewBinding.viewFinder.getHeight();

                            // 정규화 (0~1)
                            float cx = roiRect.centerX() / viewW;
                            float cy = roiRect.centerY() / viewH;
                            float rw = roiRect.width() / viewW;
                            float rh = roiRect.height() / viewH;

                            // REFERENCE 모드에서는 도형 탐지가 필요 없으니 Color Marker
                            // 샘플링 화면으로 바로 넘어간다.
                            Class<?> nextActivity = isReferenceMode
                                    ? ColorMarkerDebugActivity.class
                                    : PreviewActivity.class;
                            Intent previewIntent = new Intent(CameraActivity.this, nextActivity);
                            previewIntent.putExtra("image_uri", savedUri.toString());
                            previewIntent.putExtra("source", "camera");

                            previewIntent.putExtra("roi_cx", cx);
                            previewIntent.putExtra("roi_cy", cy);
                            previewIntent.putExtra("roi_w", rw);
                            previewIntent.putExtra("roi_h", rh);

                            previewIntent.putExtra("zoom_ratio", currentZoomRatio);
                            if (mode != null) previewIntent.putExtra("mode", mode);

                            if (isReferenceMode) {
                                previewLauncher.launch(previewIntent);
                            } else {
                                startActivity(previewIntent);            // 기존 플로우
                            }
                        });
                    }


                    @Override
                    public void onError(@NonNull androidx.camera.core.ImageCaptureException exception) {
                        setAwbLock(false);
                        Log.e(TAG, "Photo capture failed", exception);
                        Toast.makeText(CameraActivity.this, "Capture failed", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    // -------------------- Focus / Zoom / Exposure --------------------

    private void tapToFocus(float x, float y) {
        if (camera == null) return;

        CameraControl control = camera.getCameraControl();
        MeteringPointFactory factory = viewBinding.viewFinder.getMeteringPointFactory();

        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
        )
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build();

        Log.d(TAG, "tapToFocus request: x=" + x + ", y=" + y);

        ListenableFuture<FocusMeteringResult> future = control.startFocusAndMetering(action);
        future.addListener(() -> {
            try {
                FocusMeteringResult r = future.get();
                Log.d(TAG, "tapToFocus DONE. success=" + r.isFocusSuccessful());
            } catch (Exception e) {
                Log.w(TAG, "tapToFocus FAILED", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void lockAfAeToCenterOnce() {
        if (camera == null) return;

        CameraControl control = camera.getCameraControl();
        MeteringPointFactory factory = viewBinding.viewFinder.getMeteringPointFactory();

        float cx = viewBinding.viewFinder.getWidth() / 2f;
        float cy = viewBinding.viewFinder.getHeight() / 2f;

        MeteringPoint center;
        if (cx <= 0 || cy <= 0) {
            center = factory.createPoint(0.5f, 0.5f);
            Log.d(TAG, "FocusMetering fallback (0.5,0.5)");
        } else {
            center = factory.createPoint(cx, cy);
            Log.d(TAG, "FocusMetering center px=(" + cx + "," + cy + ")");
        }

        FocusMeteringAction action = new FocusMeteringAction.Builder(
                center,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
        )
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build();

        control.startFocusAndMetering(action);
    }

    /**
     * AWB(자동 화이트밸런스)를 Camera2 CaptureRequest 레벨에서 직접 잠근다.
     * FocusMeteringAction의 FLAG_AWB는 측광 영역만 지정할 뿐 실제로 값을 고정하지 않기 때문에,
     * 촬영 순간 색온도가 계속 흔들리는 것을 막으려면 CONTROL_AWB_LOCK을 명시적으로 켜야 한다.
     */
    @ExperimentalCamera2Interop
    private void setAwbLock(boolean locked) {
        if (camera == null) return;
        try {
            Camera2CameraControl camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
                    .build();
            camera2Control.addCaptureRequestOptions(options);
            Log.d(TAG, "AWB lock=" + locked);
        } catch (Exception e) {
            Log.w(TAG, "setAwbLock failed", e);
        }
    }

    private void setZoomRatioSafe(float zoomRatio) {
        if (camera == null) return;

        CameraControl control = camera.getCameraControl();
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();

        float clamped = zoomRatio;
        if (zs != null) {
            clamped = Math.max(zs.getMinZoomRatio(), Math.min(zs.getMaxZoomRatio(), zoomRatio));
        }

        control.setZoomRatio(clamped);
        currentZoomRatio = clamped; // pinch zoom 기준값 갱신

        if (zs != null) {
            Log.d(TAG, "Zoom set: " + clamped + " (min=" + zs.getMinZoomRatio() + ", max=" + zs.getMaxZoomRatio() + ")");
        } else {
            Log.d(TAG, "Zoom set: " + clamped + " (zoomState=null)");
        }
    }

    private void setExposureCompensation(int index) {
        if (camera == null) return;

        CameraInfo info = camera.getCameraInfo();
        if (!info.getExposureState().isExposureCompensationSupported()) return;

        int min = info.getExposureState().getExposureCompensationRange().getLower();
        int max = info.getExposureState().getExposureCompensationRange().getUpper();

        int clamped = Math.max(min, Math.min(max, index));
        camera.getCameraControl().setExposureCompensationIndex(clamped);
        Log.d(TAG, "ExposureCompensation=" + clamped + " (range " + min + ".." + max + ")");
    }

    private void logZoomState(String where) {
        if (camera == null) return;
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        if (zs == null) {
            Log.d(TAG, where + " zoomState=null");
            return;
        }
        Log.d(TAG, where + " zoom=" + zs.getZoomRatio()
                + " min=" + zs.getMinZoomRatio()
                + " max=" + zs.getMaxZoomRatio());
    }

    // -------------------- Permissions / lifecycle --------------------

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(getBaseContext(), permission)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    private static final String[] REQUIRED_PERMISSIONS = buildRequiredPermissions();

    private static String[] buildRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }
}
