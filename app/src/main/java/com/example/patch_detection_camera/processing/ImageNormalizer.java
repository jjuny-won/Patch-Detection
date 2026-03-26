package com.example.patch_detection_camera.processing;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;


import java.io.IOException;
import java.io.InputStream;

/**
 * Camera/Gallery 공통:
 * Uri -> Bitmap 디코드 -> EXIF 회전 보정 -> (옵션) 최대 크기 제한
 *
 * 사용:
 *   ImageNormalizer.Result r = ImageNormalizer.loadNormalized(ctx, uri, 2048);
 *   Bitmap sourceNormalized = r.bitmap;
 */
public final class ImageNormalizer {

    private ImageNormalizer() {}

    public static class Result {
        public final Bitmap bitmap;
        public final int rotationDegrees; // EXIF 기반 회전 각도 (0/90/180/270)

        public Result(Bitmap bitmap, int rotationDegrees) {
            this.bitmap = bitmap;
            this.rotationDegrees = rotationDegrees;
        }
    }

    /**
     * @param maxDim 긴 변 기준 최대 픽셀. 너무 큰 이미지면 다운스케일.
     *               0 또는 음수면 다운스케일 하지 않음.
     */
    public static Result loadNormalized(@NonNull Context context,
                                        @NonNull Uri uri,
                                        int maxDim) throws IOException {

        ContentResolver cr = context.getContentResolver();

        // 1) 먼저 bounds로 크기만 읽어 inSampleSize 계산
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) throw new IOException("openInputStream returned null: " + uri);
            BitmapFactory.decodeStream(is, null, bounds);
        }

        int srcW = bounds.outWidth;
        int srcH = bounds.outHeight;
        if (srcW <= 0 || srcH <= 0) {
            // 일부 URI에서 bounds 실패할 수 있음 -> 그냥 원본 decode 시도
            srcW = 0;
            srcH = 0;
        }

        // 2) EXIF 회전 각도 읽기
        int rotation = readExifRotationDegrees(cr, uri);

        // 3) inSampleSize 결정 (긴 변 maxDim 맞추기)
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        if (maxDim > 0 && srcW > 0 && srcH > 0) {
            int longSide = Math.max(srcW, srcH);
            opts.inSampleSize = computeInSampleSize(longSide, maxDim);
        } else {
            opts.inSampleSize = 1;
        }

        // 4) decode bitmap
        Bitmap decoded;
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) throw new IOException("openInputStream returned null: " + uri);
            decoded = BitmapFactory.decodeStream(is, null, opts);
        }
        if (decoded == null) throw new IOException("decodeStream failed: " + uri);

        // 5) EXIF 회전 보정 (픽셀 자체 회전)
        Bitmap rotated = applyRotationIfNeeded(decoded, rotation);
        if (rotated != decoded) {
            decoded.recycle();
        }

        // 6) maxDim을 inSampleSize로만 맞추면 약간 클 수 있어 -> 마지막으로 정확히 스케일(옵션)
        if (maxDim > 0) {
            rotated = scaleDownIfNeeded(rotated, maxDim);
        }

        return new Result(rotated, rotation);
    }

    private static int computeInSampleSize(int longSide, int maxDim) {
        int inSample = 1;
        while (longSide / inSample > maxDim) {
            inSample *= 2;
        }
        return Math.max(1, inSample);
    }

    private static Bitmap scaleDownIfNeeded(Bitmap bm, int maxDim) {
        int w = bm.getWidth();
        int h = bm.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= maxDim) return bm;

        float scale = (float) maxDim / (float) longSide;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(bm, nw, nh, true);
        if (scaled != bm) bm.recycle();
        return scaled;
    }

    private static Bitmap applyRotationIfNeeded(Bitmap bm, int degrees) {
        if (degrees == 0) return bm;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
    }

    private static int readExifRotationDegrees(ContentResolver cr, Uri uri) {
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) return 0;

            ExifInterface exif;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                exif = new ExifInterface(is);
            } else {
                // 구버전은 파일 경로가 필요할 수 있음. 대부분 N+에서 OK.
                exif = new ExifInterface(is);
            }

            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
            return 0;
        } catch (Throwable t) {
            return 0; // 실패해도 그냥 0으로 처리
        }
    }
}
