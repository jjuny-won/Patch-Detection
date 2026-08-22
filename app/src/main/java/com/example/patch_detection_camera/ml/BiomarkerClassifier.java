package com.example.patch_detection_camera.ml;

public class BiomarkerClassifier {
    // sklearn classes_ 순서와 정확히 일치해야 함 (임의로 바꾸지 말 것)
    private static final String[] GLUCOSE_CLASSES = {"10mM", "1mM", "20mM", "5mM"};
    private static final String[] PH_CLASSES = {"pH5", "pH6", "pH7", "pH8", "pH9"};


    public static String predictGlucose(double[] features) {
        double[] scores = GlucoseClassifier.score(features);
        int idx = argmax(scores);
        return GLUCOSE_CLASSES[idx];
    }

    public static String predictPh(double[] features) {
        double[] scores = PhClassifier.score(features);
        int idx = argmax(scores);
        return PH_CLASSES[idx];
    }


    private static int argmax(double[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[idx]) idx = i;
        }
        return idx;
    }
}
