package com.example.patch_detection_camera.processing.calibration;

import android.util.Log;

import java.util.Arrays;

public class ColorCalibrator {
    private static final String TAG = "ColorCalibrator";

    // ============================
    // Thresholds for Grade (ΔE76)
    // ============================
    private static final double DE_PASS_MEDIAN = 3.0;
    private static final double DE_PASS_MAX    = 6.0;

    private static final double DE_WARN_MEDIAN = 6.0;
    private static final double DE_WARN_MAX    = 12.0;

    // ============================
    // Exposure normalization clamps
    // ============================
    // 너무 과한 노출 보정(특히 매우 어두운 환경에서 ref/cur가 크게 튀는 경우) 방지
    private static final double EXP_GAIN_MIN = 0.5;
    private static final double EXP_GAIN_MAX = 2.0;

    // ============================
    // Result structs
    // ============================

    /** 기존: 3x3 CCM만 */
    public static class FitResult {
        public boolean ok;
        public double[][] A;   // 3x3 (linear domain)
        public double rmse;    // marker fit error (sRGB domain 평가)
        public String error;
    }

    /** Gamma + Exposure + WB + CCM 모델 */
    public static class Model {
        public boolean ok;

        public double exposureGain = 1.0;

        public double[] wb = new double[]{1,1,1}; // R,G,B gain (linear domain)
        public double[][] A;                      // 3x3 CCM (linear domain)

        public double beforeRmse;                 // sRGB domain
        public double afterRmse;                  // sRGB domain
        public double improvementPct;

        public String grade;                      // PASS/WARN/FAIL  (ΔE 기준)
        public String reason;
        public String error;
    }

    // ============================
    // Public API (Gamma+Exposure+WB+CCM)
    // ============================

    /**
     *  Gamma + Exposure + WB + CCM Fit
     * - cur/ref marker are sRGB 0..255 float arrays (len=15 or 45)
     * - Fit happens in linear domain
     */
    public static Model fitGammaWbCcmFromMarker(float[] curRgb, float[] refRgb) {
        Model m = new Model();
        m.ok = false;

        double[][] Xs = markerToNx3(curRgb);
        double[][] Ys = markerToNx3(refRgb);

        if (Xs == null || Ys == null) {
            m.error = "marker_parse_failed";
            return m;
        }
        if (Xs.length != Ys.length || Xs.length < 3) {
            m.error = "marker_len_mismatch_or_too_few";
            return m;
        }

        // BEFORE RMSE (sRGB)
        m.beforeRmse = rmseNx3(Xs, Ys);

        // 1) sRGB -> Linear (0..1)
        double[][] Xl = srgb255Nx3_to_linear01Nx3(Xs);
        double[][] Yl = srgb255Nx3_to_linear01Nx3(Ys);

        // ✅ 1.5) Exposure normalization (linear)
        // marker 평균 luminance를 ref에 맞추는 gain
        m.exposureGain = estimateExposureGainLinear(Xl, Yl);
        double[][] Xexp = applyExposureGainLinear(Xl, m.exposureGain);

        // 2) WB gains in linear domain (refMean / curMean)  ※ exposure 적용된 Xexp 기준으로 WB
        m.wb = estimateWbGainsLinear(Xexp, Yl);

        // 3) Apply WB to Xexp
        double[][] Xwb = applyWbLinear(Xexp, m.wb);

        // 4) Fit CCM (linear): minimize ||Xwb*A - Yl||^2 + λ||A||^2
        FitResult fr = fit3x3Linear(Xwb, Yl);
        if (fr == null || !fr.ok || fr.A == null) {
            m.error = (fr != null ? fr.error : "fit_null");
            return m;
        }
        m.A = fr.A;

        // 5) Evaluate AFTER RMSE in sRGB domain (참고용)
        float[] correctedMarker = applyModelToMarker(curRgb, m);
        m.afterRmse = (correctedMarker != null) ? rmseMarker(correctedMarker, refRgb) : Double.NaN;
        m.improvementPct =
                (Double.isFinite(m.beforeRmse) && m.beforeRmse > 1e-6 && Double.isFinite(m.afterRmse))
                        ? (m.beforeRmse - m.afterRmse) / m.beforeRmse * 100.0
                        : Double.NaN;

        // ✅ Grade는 ΔE 기준으로 산정
        EvalResult ev = evaluateWithModel(curRgb, refRgb, m);
        m.grade = ev.grade;
        m.reason = ev.reason;

        m.ok = true;

        Log.d(TAG, "fitGammaWbCcm ok N=" + Xs.length
                + " expGain=" + m.exposureGain
                + " wb=" + Arrays.toString(m.wb)
                + " beforeRMSE=" + m.beforeRmse + " afterRMSE=" + m.afterRmse
                + " grade=" + m.grade);

        return m;
    }

    /** Apply Gamma+Exposure+WB+CCM model to ONE RGB */
    public static int[] applyModelToRgb(int r, int g, int b, Model m) {
        if (m == null || !m.ok || m.A == null || m.wb == null) return new int[]{r,g,b};

        // sRGB 0..255 -> linear 0..1
        double lr = srgb255_to_linear01(r);
        double lg = srgb255_to_linear01(g);
        double lb = srgb255_to_linear01(b);

        // ✅ Exposure normalization (linear)
        lr *= m.exposureGain;
        lg *= m.exposureGain;
        lb *= m.exposureGain;

        // WB (linear)
        lr *= m.wb[0];
        lg *= m.wb[1];
        lb *= m.wb[2];

        // CCM (rowvec * A)
        double[] y = applyRowVec(new double[]{lr, lg, lb}, m.A);

        // linear -> sRGB 0..255
        int rr = clamp255((int)Math.round(linear01_to_srgb255(y[0])));
        int gg = clamp255((int)Math.round(linear01_to_srgb255(y[1])));
        int bb = clamp255((int)Math.round(linear01_to_srgb255(y[2])));

        return new int[]{rr, gg, bb};
    }

    public static float[] applyModelToMarker(float[] curSrgb255, Model m) {
        if (curSrgb255 == null || m == null || !m.ok || m.A == null || m.wb == null) return null;
        if (curSrgb255.length % 3 != 0) return null;

        int N = curSrgb255.length / 3;
        float[] out = new float[curSrgb255.length];

        for (int i = 0; i < N; i++) {
            int r = Math.round(curSrgb255[i*3]);
            int g = Math.round(curSrgb255[i*3 + 1]);
            int b = Math.round(curSrgb255[i*3 + 2]);

            int[] corr = applyModelToRgb(r, g, b, m);
            out[i*3]     = corr[0];
            out[i*3 + 1] = corr[1];
            out[i*3 + 2] = corr[2];
        }
        return out;
    }

    // ============================
    // Backward compatible (OLD CCM-only)
    // ============================

    public static FitResult fitFromMarker(float[] curRgb, float[] refRgb) {
        FitResult fr = new FitResult();
        fr.ok = false;

        double[][] X = markerToNx3(curRgb);
        double[][] Y = markerToNx3(refRgb);

        if (X == null || Y == null) {
            fr.error = "marker_parse_failed";
            return fr;
        }
        if (X.length != Y.length || X.length < 3) {
            fr.error = "marker_len_mismatch_or_too_few";
            return fr;
        }

        double lambda = 1e-3;
        double[][] Xt = transpose(X);
        double[][] XtX = mul(Xt, X);
        addDiagInPlace(XtX, lambda);
        double[][] XtY = mul(Xt, Y);

        double[][] inv = inv3x3(XtX);
        if (inv == null) {
            fr.error = "inv_failed";
            return fr;
        }
        double[][] A = mul(inv, XtY);

        double err2 = 0.0;
        int cnt = 0;
        for (int i = 0; i < X.length; i++) {
            double[] yp = applyRowVec(X[i], A);
            double dr = yp[0] - Y[i][0];
            double dg = yp[1] - Y[i][1];
            double db = yp[2] - Y[i][2];
            err2 += dr*dr + dg*dg + db*db;
            cnt += 3;
        }
        fr.rmse = Math.sqrt(err2 / Math.max(1, cnt));
        fr.A = A;
        fr.ok = true;

        Log.d(TAG, "fit (old) ok N=" + X.length + " rmse=" + fr.rmse);
        return fr;
    }

    public static int[] applyToRgb(int r, int g, int b, double[][] A) {
        if (A == null) return new int[]{r,g,b};
        double[] x = new double[]{r, g, b};
        double[] y = applyRowVec(x, A);
        int rr = clamp255((int) Math.round(y[0]));
        int gg = clamp255((int) Math.round(y[1]));
        int bb = clamp255((int) Math.round(y[2]));
        return new int[]{rr, gg, bb};
    }

    // ============================
    // Evaluation (RMSE + ΔE76 + per-patch ΔE)
    // ============================

    public static class EvalResult {
        public double beforeRmse;
        public double afterRmse;
        public double improvementPct;

        public double beforeDeltaEMean;
        public double afterDeltaEMean;
        public double beforeDeltaEMedian;
        public double afterDeltaEMedian;
        public double beforeDeltaEMax;
        public double afterDeltaEMax;

        public double[] beforeDeltaEPerPatch;
        public double[] afterDeltaEPerPatch;

        public String grade;
        public String reason;
    }

    /** RMSE between two markers (Nx3) in sRGB domain */
    public static double rmseMarker(float[] a, float[] b) {
        double[][] X = markerToNx3(a);
        double[][] Y = markerToNx3(b);
        if (X == null || Y == null || X.length != Y.length) return Double.NaN;
        return rmseNx3(X, Y);
    }

    /** Apply matrix to a marker and return corrected marker as float[] (sRGB domain) */
    public static float[] applyToMarker(float[] cur, double[][] A) {
        double[][] X = markerToNx3(cur);
        if (X == null) return null;
        float[] out = new float[cur.length];
        for (int i = 0; i < X.length; i++) {
            double[] y = applyRowVec(X[i], A);
            out[i*3]     = (float) clamp255d(y[0]);
            out[i*3 + 1] = (float) clamp255d(y[1]);
            out[i*3 + 2] = (float) clamp255d(y[2]);
        }
        return out;
    }

    private static double clamp255d(double v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    /** ✅ CCM-only 평가 (A를 준 경우) */
    public static EvalResult evaluate(float[] curMarker, float[] refMarker, double[][] A) {
        EvalResult er = new EvalResult();

        er.beforeRmse = rmseMarker(curMarker, refMarker);
        float[] corrected = (A != null) ? applyToMarker(curMarker, A) : null;
        er.afterRmse = (corrected != null) ? rmseMarker(corrected, refMarker) : Double.NaN;

        if (Double.isFinite(er.beforeRmse) && Double.isFinite(er.afterRmse) && er.beforeRmse > 1e-6) {
            er.improvementPct = (er.beforeRmse - er.afterRmse) / er.beforeRmse * 100.0;
        } else {
            er.improvementPct = Double.NaN;
        }

        DeltaEStats deBefore = deltaEForMarker(curMarker, refMarker);
        er.beforeDeltaEMean = deBefore.mean;
        er.beforeDeltaEMedian = deBefore.median;
        er.beforeDeltaEMax = deBefore.max;
        er.beforeDeltaEPerPatch = deBefore.perPatch;

        DeltaEStats deAfter = (corrected != null) ? deltaEForMarker(corrected, refMarker) : new DeltaEStats();
        er.afterDeltaEMean = deAfter.mean;
        er.afterDeltaEMedian = deAfter.median;
        er.afterDeltaEMax = deAfter.max;
        er.afterDeltaEPerPatch = deAfter.perPatch;

        gradeByDeltaEInPlace(er);
        return er;
    }

    /** ✅ Model(Gamma+Exposure+WB+CCM) 평가 */
    public static EvalResult evaluateWithModel(float[] curMarker, float[] refMarker, Model m) {
        EvalResult er = new EvalResult();

        er.beforeRmse = rmseMarker(curMarker, refMarker);

        float[] corrected = (m != null && m.ok) ? applyModelToMarker(curMarker, m) : null;

        er.afterRmse = (corrected != null) ? rmseMarker(corrected, refMarker) : Double.NaN;

        if (Double.isFinite(er.beforeRmse) && Double.isFinite(er.afterRmse) && er.beforeRmse > 1e-6) {
            er.improvementPct = (er.beforeRmse - er.afterRmse) / er.beforeRmse * 100.0;
        } else {
            er.improvementPct = Double.NaN;
        }

        DeltaEStats deBefore = deltaEForMarker(curMarker, refMarker);
        er.beforeDeltaEMean = deBefore.mean;
        er.beforeDeltaEMedian = deBefore.median;
        er.beforeDeltaEMax = deBefore.max;
        er.beforeDeltaEPerPatch = deBefore.perPatch;

        DeltaEStats deAfter = (corrected != null) ? deltaEForMarker(corrected, refMarker) : new DeltaEStats();
        er.afterDeltaEMean = deAfter.mean;
        er.afterDeltaEMedian = deAfter.median;
        er.afterDeltaEMax = deAfter.max;
        er.afterDeltaEPerPatch = deAfter.perPatch;

        gradeByDeltaEInPlace(er);
        return er;
    }

    /** ✅ Grade = ΔE AFTER 기준 */
    private static void gradeByDeltaEInPlace(EvalResult er) {
        if (!Double.isFinite(er.afterDeltaEMedian) || !Double.isFinite(er.afterDeltaEMax)) {
            er.grade = "FAIL";
            er.reason = "ΔE invalid";
            return;
        }

        if (er.afterDeltaEMedian <= DE_PASS_MEDIAN && er.afterDeltaEMax <= DE_PASS_MAX) {
            er.grade = "PASS";
            er.reason = "";
            return;
        }

        if (er.afterDeltaEMedian <= DE_WARN_MEDIAN && er.afterDeltaEMax <= DE_WARN_MAX) {
            er.grade = "WARN";
            er.reason = "ΔE borderline";
            return;
        }

        er.grade = "FAIL";
        er.reason = "ΔE high";
    }

    // ============================
    // ΔE76 utilities (Lab D65)
    // ============================

    public static class DeltaEStats {
        public double mean;
        public double median;
        public double max;
        public int n;
        public double[] perPatch;
    }

    private static double srgb01_to_linear01_forLab(double s) {
        s = clamp01(s);
        if (s <= 0.04045) return s / 12.92;
        return Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static double fLab(double t) {
        double delta = 6.0 / 29.0;
        double delta3 = delta * delta * delta;
        if (t > delta3) return Math.cbrt(t);
        return (t / (3 * delta * delta)) + (4.0 / 29.0);
    }

    private static double[] rgbToLabD65(int r, int g, int b) {
        double R = srgb01_to_linear01_forLab(clamp01(r / 255.0));
        double G = srgb01_to_linear01_forLab(clamp01(g / 255.0));
        double B = srgb01_to_linear01_forLab(clamp01(b / 255.0));

        double X = 0.4124564 * R + 0.3575761 * G + 0.1804375 * B;
        double Y = 0.2126729 * R + 0.7151522 * G + 0.0721750 * B;
        double Z = 0.0193339 * R + 0.1191920 * G + 0.9503041 * B;

        double Xn = 0.95047;
        double Yn = 1.00000;
        double Zn = 1.08883;

        double fx = fLab(X / Xn);
        double fy = fLab(Y / Yn);
        double fz = fLab(Z / Zn);

        double L = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double bb = 200.0 * (fy - fz);

        return new double[]{L, a, bb};
    }

    private static double deltaE76(double[] lab1, double[] lab2) {
        double dL = lab1[0] - lab2[0];
        double da = lab1[1] - lab2[1];
        double db = lab1[2] - lab2[2];
        return Math.sqrt(dL*dL + da*da + db*db);
    }

    private static double medianOfDoublesCopy(double[] arr) {
        if (arr == null || arr.length == 0) return 0.0;
        double[] tmp = Arrays.copyOf(arr, arr.length);
        Arrays.sort(tmp);
        int n = tmp.length;
        if (n % 2 == 1) return tmp[n/2];
        return (tmp[n/2 - 1] + tmp[n/2]) * 0.5;
    }

    public static DeltaEStats deltaEForMarker(float[] markerRgb, float[] refRgb) {
        DeltaEStats st = new DeltaEStats();
        st.mean = 0.0;
        st.median = 0.0;
        st.max = 0.0;
        st.n = 0;
        st.perPatch = null;

        if (markerRgb == null || refRgb == null) return st;
        int len = Math.min(markerRgb.length, refRgb.length);
        if (len < 15 || len % 3 != 0) return st;

        int N = len / 3;
        double[] des = new double[N];

        double sum = 0.0;
        double mx = 0.0;

        for (int i = 0; i < N; i++) {
            int mr = Math.round(markerRgb[i*3]);
            int mg = Math.round(markerRgb[i*3 + 1]);
            int mb = Math.round(markerRgb[i*3 + 2]);

            int rr = Math.round(refRgb[i*3]);
            int rg = Math.round(refRgb[i*3 + 1]);
            int rb = Math.round(refRgb[i*3 + 2]);

            double[] labM = rgbToLabD65(mr, mg, mb);
            double[] labR = rgbToLabD65(rr, rg, rb);

            double de = deltaE76(labM, labR);
            des[i] = de;
            sum += de;
            if (de > mx) mx = de;
        }

        st.n = N;
        st.mean = (N > 0) ? (sum / N) : 0.0;
        st.max = mx;
        st.median = medianOfDoublesCopy(des);
        st.perPatch = des;
        return st;
    }

    public static double[] deltaEPerPatch(float[] markerRgb, float[] refRgb) {
        if (markerRgb == null || refRgb == null) return null;
        int len = Math.min(markerRgb.length, refRgb.length);
        if (len < 15 || len % 3 != 0) return null;

        int N = len / 3;
        double[] out = new double[N];

        for (int i = 0; i < N; i++) {
            int mr = Math.round(markerRgb[i*3]);
            int mg = Math.round(markerRgb[i*3 + 1]);
            int mb = Math.round(markerRgb[i*3 + 2]);

            int rr = Math.round(refRgb[i*3]);
            int rg = Math.round(refRgb[i*3 + 1]);
            int rb = Math.round(refRgb[i*3 + 2]);

            double[] labM = rgbToLabD65(mr, mg, mb);
            double[] labR = rgbToLabD65(rr, rg, rb);

            out[i] = deltaE76(labM, labR);
        }
        return out;
    }

    // ============================
    // Internals: NEW fit in linear domain
    // ============================

    private static FitResult fit3x3Linear(double[][] X, double[][] Y) {
        FitResult fr = new FitResult();
        fr.ok = false;

        if (X == null || Y == null || X.length != Y.length || X.length < 3) {
            fr.error = "bad_XY";
            return fr;
        }

        double lambda = 1e-3;
        double[][] Xt = transpose(X);
        double[][] XtX = mul(Xt, X);
        addDiagInPlace(XtX, lambda);
        double[][] XtY = mul(Xt, Y);

        double[][] inv = inv3x3(XtX);
        if (inv == null) {
            fr.error = "inv_failed";
            return fr;
        }
        fr.A = mul(inv, XtY);
        fr.ok = true;
        fr.rmse = Double.NaN;
        return fr;
    }

    // exposure gain from marker mean luminance
    // relative luminance (linear RGB): Y = 0.2126 R + 0.7152 G + 0.0722 B
    private static double estimateExposureGainLinear(double[][] curLin, double[][] refLin) {
        double curL = meanLuminanceLinear(curLin);
        double refL = meanLuminanceLinear(refLin);

        double eps = 1e-9;
        double g = (curL > eps) ? (refL / curL) : 1.0;

        // 안정성 clamp
        g = clamp(g, EXP_GAIN_MIN, EXP_GAIN_MAX);
        return g;
    }

    private static double meanLuminanceLinear(double[][] lin) {
        if (lin == null || lin.length == 0) return 0.0;
        double sum = 0.0;
        for (double[] v : lin) {
            double Y = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
            sum += Y;
        }
        return sum / Math.max(1, lin.length);
    }

    private static double[][] applyExposureGainLinear(double[][] X, double exposureGain) {
        double[][] out = new double[X.length][3];
        for (int i = 0; i < X.length; i++) {
            out[i][0] = X[i][0] * exposureGain;
            out[i][1] = X[i][1] * exposureGain;
            out[i][2] = X[i][2] * exposureGain;
        }
        return out;
    }

    private static double[] estimateWbGainsLinear(double[][] curLin, double[][] refLin) {
        double[] c = meanRgb(curLin);
        double[] r = meanRgb(refLin);

        double eps = 1e-9;
        double gr = (c[0] > eps) ? (r[0] / c[0]) : 1.0;
        double gg = (c[1] > eps) ? (r[1] / c[1]) : 1.0;
        double gb = (c[2] > eps) ? (r[2] / c[2]) : 1.0;

        // 안정성 목적 clamp
        gr = clamp(gr, 0.7, 1.5);
        gg = clamp(gg, 0.7, 1.5);
        gb = clamp(gb, 0.7, 1.5);

        return new double[]{gr, gg, gb};
    }

    private static double[][] applyWbLinear(double[][] X, double[] wb) {
        double[][] out = new double[X.length][3];
        for (int i = 0; i < X.length; i++) {
            out[i][0] = X[i][0] * wb[0];
            out[i][1] = X[i][1] * wb[1];
            out[i][2] = X[i][2] * wb[2];
        }
        return out;
    }

    private static double[] meanRgb(double[][] X) {
        double sr=0, sg=0, sb=0;
        for (double[] v : X) { sr += v[0]; sg += v[1]; sb += v[2]; }
        int n = Math.max(1, X.length);
        return new double[]{sr/n, sg/n, sb/n};
    }

    // ============================
    // Gamma (sRGB <-> Linear)
    // ============================

    private static double srgb01_to_linear01(double s) {
        s = clamp01(s);
        if (s <= 0.04045) return s / 12.92;
        return Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static double linear01_to_srgb01(double l) {
        l = clamp01(l);
        if (l <= 0.0031308) return 12.92 * l;
        return 1.055 * Math.pow(l, 1.0/2.4) - 0.055;
    }

    private static double srgb255_to_linear01(int v255) {
        double s = clamp01(v255 / 255.0);
        return srgb01_to_linear01(s);
    }

    private static double linear01_to_srgb255(double l01) {
        double s = linear01_to_srgb01(l01);
        return clamp01(s) * 255.0;
    }

    private static double[][] srgb255Nx3_to_linear01Nx3(double[][] srgb255Nx3) {
        double[][] out = new double[srgb255Nx3.length][3];
        for (int i = 0; i < srgb255Nx3.length; i++) {
            out[i][0] = srgb01_to_linear01(clamp01(srgb255Nx3[i][0] / 255.0));
            out[i][1] = srgb01_to_linear01(clamp01(srgb255Nx3[i][1] / 255.0));
            out[i][2] = srgb01_to_linear01(clamp01(srgb255Nx3[i][2] / 255.0));
        }
        return out;
    }

    // ============================
    // utils
    // ============================

    private static double[][] markerToNx3(float[] a) {
        if (a == null) return null;
        if (a.length % 3 != 0) return null;
        int N = a.length / 3;
        if (N < 3) return null;
        double[][] out = new double[N][3];
        for (int i = 0; i < N; i++) {
            out[i][0] = a[i*3];
            out[i][1] = a[i*3 + 1];
            out[i][2] = a[i*3 + 2];
        }
        return out;
    }

    private static double rmseNx3(double[][] X, double[][] Y) {
        if (X == null || Y == null || X.length != Y.length) return Double.NaN;

        double err2 = 0.0;
        int totalCount = X.length * 3;

        for (int i = 0; i < X.length; i++) {
            double dr = X[i][0] - Y[i][0];
            double dg = X[i][1] - Y[i][1];
            double db = X[i][2] - Y[i][2];
            err2 += dr * dr + dg * dg + db * db;
        }
        return Math.sqrt(err2 / totalCount);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static double clamp01(double v) {
        return (v < 0) ? 0 : (v > 1) ? 1 : v;
    }

    private static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    // y = x * A  (row vector)
    private static double[] applyRowVec(double[] x, double[][] A) {
        return new double[]{
                x[0]*A[0][0] + x[1]*A[1][0] + x[2]*A[2][0],
                x[0]*A[0][1] + x[1]*A[1][1] + x[2]*A[2][1],
                x[0]*A[0][2] + x[1]*A[1][2] + x[2]*A[2][2]
        };
    }

    private static double[][] transpose(double[][] M) {
        int r = M.length, c = M[0].length;
        double[][] T = new double[c][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) T[j][i] = M[i][j];
        return T;
    }

    private static double[][] mul(double[][] A, double[][] B) {
        int r = A.length, k = A[0].length, c = B[0].length;
        double[][] C = new double[r][c];
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                double s = 0.0;
                for (int t = 0; t < k; t++) s += A[i][t] * B[t][j];
                C[i][j] = s;
            }
        }
        return C;
    }

    private static void addDiagInPlace(double[][] M, double v) {
        for (int i = 0; i < 3; i++) M[i][i] += v;
    }

    private static double[][] inv3x3(double[][] m) {
        double a=m[0][0], b=m[0][1], c=m[0][2];
        double d=m[1][0], e=m[1][1], f=m[1][2];
        double g=m[2][0], h=m[2][1], i=m[2][2];

        double A =   e*i - f*h;
        double B = -(d*i - f*g);
        double C =   d*h - e*g;
        double D = -(b*i - c*h);
        double E =   a*i - c*g;
        double F = -(a*h - b*g);
        double G =   b*f - c*e;
        double H = -(a*f - c*d);
        double I =   a*e - b*d;

        double det = a*A + b*B + c*C;
        if (Math.abs(det) < 1e-12) return null;

        double invDet = 1.0 / det;
        return new double[][]{
                {A*invDet, D*invDet, G*invDet},
                {B*invDet, E*invDet, H*invDet},
                {C*invDet, F*invDet, I*invDet}
        };
    }

    // PUBLIC wrappers for external evaluators
    public static double[] rgbToLab(int r, int g, int b) {
        return rgbToLabD65(r, g, b);
    }

    public static double deltaE76Public(double[] lab1, double[] lab2) {
        return deltaE76(lab1, lab2);
    }
}