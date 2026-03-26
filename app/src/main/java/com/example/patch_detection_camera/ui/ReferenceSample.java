package com.example.patch_detection_camera.ui;

import java.io.Serializable;
import java.util.Arrays;

public class ReferenceSample implements Serializable {
    public float[] rgb15;

    public ReferenceSample(float[] rgb15) {
        this.rgb15 = rgb15 != null ? Arrays.copyOf(rgb15, rgb15.length) : null;
    }
}