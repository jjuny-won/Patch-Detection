package com.example.patch_detection_camera.processing.detection.strip;

import java.util.*;

/**
 * 5개 밴드(위->아래 순서로 들어옴) <-> 5개 라벨(White/Gray/Red/Green/Blue)을
 * 브루트포스 순열 탐색으로 전역 최적 배정하는 클래스.
 *
 * 기존 순차적(greedy) 라벨링(White 먼저 확정 -> Gray -> Hue 매칭)을 대체.
 * 색상 비용 + 위치 비용(정순/역순 중 유리한 쪽)을 합산해 총 비용이 최소인 배정을 선택.
 */
public class BandLabelMatcher {

    public static final String[] LABELS = {"White", "Gray", "Red", "Green", "Blue"};

    // 라벨의 "기준 위치 인덱스" (물리적 카드에서 위->아래 배치 순서).
    // 실측 확인 완료: 위->아래 = Gray, Red, Green, Blue, White
    private static final int[] LABEL_CANONICAL_ORDER = {4, 0, 1, 2, 3};
    // 위 배열의 의미: LABELS[i]가 정순에서 몇 번째 밴드 위치에 오는지
    // White(idx0)->4번째(맨 아래), Gray(idx1)->0번째(맨 위), Red(idx2)->1번째, Green(idx3)->2번째, Blue(idx4)->3번째

    // ============================
    // 튜닝 파라미터 (아래 "정해야 할 것" 섹션 참고)
    // ============================
    private static double POSITION_WEIGHT = 8.0;   // 색상 비용 대비 위치 비용의 가중치
    static double SATURATION_V_THRESHOLD = 250.0; // V가 이 이상이면 "과다노출 후보" (ColorStripSampler의 clippedRatio 계산과 공유)
    private static double SATURATION_PIXEL_RATIO = 0.30;  // 밴드 내 과다노출 픽셀 비율 임계값

    public static class BandStat {
        public double meanH, meanS, meanV; // OpenCV 기준 H:0-180, S/V:0-255
        public double clippedRatio;        // R,G,B 모두 250 초과인 픽셀 비율
    }

    public static class MatchResult {
        public int[] bandToLabelIndex;   // bandToLabelIndex[bandIdx] = labelIdx
        public double totalCost;
        public boolean usedReversedOrder;
        public boolean whiteGrayAmbiguous;
    }

    /**
     * @param bands 위->아래 순서로 들어온 5개 밴드의 HSV/클리핑 통계
     */
    public static MatchResult match(BandStat[] bands) {
        if (bands.length != 5) throw new IllegalArgumentException("bands must have length 5");

        int[] perm = {0, 1, 2, 3, 4};
        int[] bestPerm = null;
        double bestCost = Double.MAX_VALUE;
        boolean bestReversed = false;

        List<int[]> allPerms = new ArrayList<>();
        permute(perm, 0, allPerms);

        for (int[] p : allPerms) {
            // p[bandIdx] = labelIdx
            for (boolean reversed : new boolean[]{false, true}) {
                double cost = 0.0;
                for (int bandIdx = 0; bandIdx < 5; bandIdx++) {
                    int labelIdx = p[bandIdx];
                    cost += colorCost(bands[bandIdx], labelIdx);
                    cost += positionCost(bandIdx, labelIdx, reversed) * POSITION_WEIGHT;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestPerm = p.clone();
                    bestReversed = reversed;
                }
            }
        }

        MatchResult result = new MatchResult();
        result.bandToLabelIndex = bestPerm;
        result.totalCost = bestCost;
        result.usedReversedOrder = bestReversed;

        // White/Gray 혼동 감지: 서로 위치가 맞바뀌어도 비용 차이가 거의 없으면 ambiguous
        result.whiteGrayAmbiguous = checkWhiteGrayAmbiguous(bands, bestPerm);

        return result;
    }

    private static void permute(int[] arr, int k, List<int[]> out) {
        if (k == arr.length) {
            out.add(arr.clone());
            return;
        }
        for (int i = k; i < arr.length; i++) {
            swap(arr, k, i);
            permute(arr, k + 1, out);
            swap(arr, k, i);
        }
    }

    private static void swap(int[] arr, int i, int j) {
        int t = arr[i]; arr[i] = arr[j]; arr[j] = t;
    }

    // ============================
    // 비용 함수
    // ============================

    /** 라벨별 색상 불일치 비용. 값이 작을수록 그 라벨에 가깝다는 뜻. */
    private static double colorCost(BandStat b, int labelIdx) {
        switch (LABELS[labelIdx]) {
            case "White":
                // V가 높고 S가 낮을수록 White에 가까움
                return (255.0 - b.meanV) + b.meanS * 0.5;
            case "Gray":
                // S가 낮되, White보다는 V가 중간 정도
                double vMidDistance = Math.abs(b.meanV - 170.0); // 170: 예상 중간 밝기, 튜닝 필요
                return b.meanS + vMidDistance * 0.3;
            case "Red":
                return hueDistanceCost(b, 0.0) ;
            case "Green":
                return hueDistanceCost(b, 60.0);
            case "Blue":
                return hueDistanceCost(b, 120.0);
            default:
                return Double.MAX_VALUE;
        }
    }

    /** OpenCV Hue(0-180) 기준 원형 거리 계산. targetHueDeg는 0-180 스케일로 이미 맞춰서 전달. */
    private static double hueDistanceCost(BandStat b, double targetHueDeg) {
        double diff = Math.abs(b.meanH - targetHueDeg);
        diff = Math.min(diff, 180.0 - diff); // 원형 거리
        // 채도가 너무 낮으면 Hue 자체가 불안정하므로 페널티 추가
        double lowSaturationPenalty = Math.max(0, 30.0 - b.meanS); // S<30이면 페널티 커짐
        return diff + lowSaturationPenalty * 2.0;
    }

    /** 밴드 인덱스와 라벨의 기대 위치 차이. reversed=true면 순서를 뒤집어서 계산. */
    private static double positionCost(int bandIdx, int labelIdx, boolean reversed) {
        int expectedBandIdx = LABEL_CANONICAL_ORDER[labelIdx];
        if (reversed) expectedBandIdx = 4 - expectedBandIdx;
        return Math.abs(bandIdx - expectedBandIdx);
    }

    /**
     * White/Gray로 배정된 두 밴드의 V값 차이가 작고, 둘 중 하나라도 과다노출이면
     * "확신할 수 없다"고 표시. (재촬영 유도는 이 플래그를 보고 상위 레이어에서 처리)
     */
    private static boolean checkWhiteGrayAmbiguous(BandStat[] bands, int[] bandToLabel) {
        int whiteBandIdx = -1, grayBandIdx = -1;
        for (int i = 0; i < 5; i++) {
            if (LABELS[bandToLabel[i]].equals("White")) whiteBandIdx = i;
            if (LABELS[bandToLabel[i]].equals("Gray")) grayBandIdx = i;
        }
        if (whiteBandIdx < 0 || grayBandIdx < 0) return true; // 배정 자체가 안 됐으면 문제

        double vWhite = bands[whiteBandIdx].meanV;
        double vGray = bands[grayBandIdx].meanV;
        boolean vTooClose = Math.abs(vWhite - vGray) < 15.0; // 튜닝 필요

        boolean eitherSaturated =
                isSaturated(bands[whiteBandIdx]) || isSaturated(bands[grayBandIdx]);

        return vTooClose && eitherSaturated;
    }

    private static boolean isSaturated(BandStat b) {
        return b.clippedRatio > SATURATION_PIXEL_RATIO;
    }
}
