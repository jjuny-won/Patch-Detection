package com.example.patch_detection_camera.processing.detection;
public class PreviewAnalysisResult {
    public final String shapesJson;
    public final float[] rgb15;

    public PreviewAnalysisResult(String shapesJson, float[] rgb15) {
        this.shapesJson = shapesJson;
        this.rgb15 = (rgb15 != null) ? rgb15 : new float[0];
    }
}