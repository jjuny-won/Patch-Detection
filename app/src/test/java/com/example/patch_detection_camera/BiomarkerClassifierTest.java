package com.example.patch_detection_camera;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import com.example.patch_detection_camera.ml.BiomarkerClassifier;

public class BiomarkerClassifierTest {

    @Test
    public void testGlucose_1mM() {
        double[] features = {159.0, 138.0, 84.0, 0.41732283464566927, 0.36220472440944884, 0.2204724409448819};
        String result = BiomarkerClassifier.predictGlucose(features);
        assertEquals("1mM", result);
    }

    @Test
    public void testGlucose_5mM() {
        double[] features = {87.0, 53.0, 16.0, 0.5576923076923077, 0.33974358974358976, 0.10256410256410256};
        String result = BiomarkerClassifier.predictGlucose(features);
        assertEquals("5mM", result);
    }

    @Test
    public void testGlucose_10mM() {
        double[] features = {67.0, 34.0, 2.0, 0.6504854368932039, 0.3300970873786408, 0.019417475728155338};
        String result = BiomarkerClassifier.predictGlucose(features);
        assertEquals("10mM", result);
    }

    @Test
    public void testGlucose_20mM() {
        double[] features = {21.0, 0.0, 0.0, 1.0, 0.0, 0.0};
        String result = BiomarkerClassifier.predictGlucose(features);
        assertEquals("20mM", result);
    }
}