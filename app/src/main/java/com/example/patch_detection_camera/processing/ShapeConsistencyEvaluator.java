package com.example.patch_detection_camera.processing;

import android.util.Log;

import com.example.patch_detection_camera.processing.calibration.ColorCalibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class ShapeConsistencyEvaluator {
    private static final String TAG = "ShapeConsistencyEval";

    public static class Result {
        public int nLab;
        public double sigmaL;
        public double sigmaA;
        public double sigmaB;
        public double pairwiseDeltaEMean;
        public double pairwiseDeltaEMax;
    }

    /**
     * runs.json(배열)에서 마지막 K개 run의 shapesCorrected를 읽어서
     * Lab 분산(σL,σa,σb) + pairwise ΔE(mean/max) 계산
     */
    public static Result evaluate(File runFile, int lastK) {
        Result r = new Result();

        if (runFile == null || !runFile.exists() || lastK <= 0) {
            return r;
        }

        try {
            // 1) read file
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(runFile))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONArray runs = new JSONArray(sb.toString());
            int start = Math.max(0, runs.length() - lastK);

            // 2) collect Lab samples
            List<double[]> labs = new ArrayList<>();

            for (int idx = start; idx < runs.length(); idx++) {
                JSONObject run = runs.optJSONObject(idx);
                if (run == null) continue;

                JSONArray shapes = run.optJSONArray("shapesCorrected");
                if (shapes == null) continue;

                for (int j = 0; j < shapes.length(); j++) {
                    JSONObject s = shapes.optJSONObject(j);
                    if (s == null) continue;

                    // 저장 포맷에 맞춰 corr_r/g/b를 읽는다
                    int rr = s.optInt("corr_r", Integer.MIN_VALUE);
                    int gg = s.optInt("corr_g", Integer.MIN_VALUE);
                    int bb = s.optInt("corr_b", Integer.MIN_VALUE);
                    if (rr == Integer.MIN_VALUE || gg == Integer.MIN_VALUE || bb == Integer.MIN_VALUE) continue;

                    double[] lab = ColorCalibrator.rgbToLab(rr, gg, bb);
                    if (lab != null && lab.length == 3) labs.add(lab);
                }
            }

            r.nLab = labs.size();
            if (labs.isEmpty()) return r;

            // 3) mean
            double meanL = 0, meanA = 0, meanB = 0;
            for (double[] lab : labs) {
                meanL += lab[0];
                meanA += lab[1];
                meanB += lab[2];
            }
            meanL /= labs.size();
            meanA /= labs.size();
            meanB /= labs.size();

            // 4) sigma
            double varL = 0, varA = 0, varB = 0;
            for (double[] lab : labs) {
                double dL = lab[0] - meanL;
                double dA = lab[1] - meanA;
                double dB = lab[2] - meanB;
                varL += dL * dL;
                varA += dA * dA;
                varB += dB * dB;
            }
            r.sigmaL = Math.sqrt(varL / labs.size());
            r.sigmaA = Math.sqrt(varA / labs.size());
            r.sigmaB = Math.sqrt(varB / labs.size());

            // 5) pairwise ΔE
            double sum = 0;
            double max = 0;
            int count = 0;

            for (int i = 0; i < labs.size(); i++) {
                for (int j = i + 1; j < labs.size(); j++) {
                    double d = ColorCalibrator.deltaE76Public(labs.get(i), labs.get(j)); // ✅ public wrapper
                    sum += d;
                    if (d > max) max = d;
                    count++;
                }
            }

            if (count > 0) {
                r.pairwiseDeltaEMean = sum / count;
                r.pairwiseDeltaEMax = max;
            }

            return r;

        } catch (Exception e) {
            Log.e(TAG, "evaluate failed: " + e.getMessage(), e);
            return r;
        }
    }
}