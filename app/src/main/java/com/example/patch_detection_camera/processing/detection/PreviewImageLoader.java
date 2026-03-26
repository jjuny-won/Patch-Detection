package com.example.patch_detection_camera.processing.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

//PreviewActivity
//  ↓
//          PreviewImageLoader.loadInputMat(...)
//  ↓
//          1) uriStr → Uri.parse(uriStr)
//  ↓
//          2) loadBitmap()으로 Bitmap 로드
//  ↓
//          3) hasRoi == true 이면 cropByNormalizedRoi()로 잘라냄
//  ↓
//          4) Utils.bitmapToMat()으로 Mat 생성
//  ↓
//          5) Mat 반환
public final class PreviewImageLoader {

    private PreviewImageLoader() {
        // no instance
    }

    public static Bitmap loadBitmap(Context context, Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source src = ImageDecoder.createSource(context.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
            });
        } else {
            return android.provider.MediaStore.Images.Media.getBitmap(
                    context.getContentResolver(),
                    uri
            );
        }
    }

    public static Bitmap cropByNormalizedRoi(Bitmap src, float cx, float cy, float rw, float rh) {
        int W = src.getWidth();
        int H = src.getHeight();

        int cropW = Math.round(rw * W);
        int cropH = Math.round(rh * H);

        int x = Math.round((cx * W) - cropW / 2f);
        int y = Math.round((cy * H) - cropH / 2f);

        x = Math.max(0, Math.min(W - 1, x));
        y = Math.max(0, Math.min(H - 1, y));
        cropW = Math.max(1, Math.min(W - x, cropW));
        cropH = Math.max(1, Math.min(H - y, cropH));

        return Bitmap.createBitmap(src, x, y, cropW, cropH);
    }

    public static Bitmap loadInputBitmap(
            Context context,
            String uriStr,
            boolean hasRoi,
            float roiCx, float roiCy, float roiW, float roiH
    ) throws Exception {
        Bitmap bmp = loadBitmap(context, Uri.parse(uriStr));
        if (!hasRoi) return bmp;
        return cropByNormalizedRoi(bmp, roiCx, roiCy, roiW, roiH);
    }

    public static Mat loadInputMat(
            Context context,
            String uriStr,
            boolean hasRoi,
            float roiCx, float roiCy, float roiW, float roiH
    ) throws Exception {
        Bitmap bmp = loadInputBitmap(context, uriStr, hasRoi, roiCx, roiCy, roiW, roiH);
        Mat rgba = new Mat();
        Utils.bitmapToMat(bmp, rgba);
        return rgba;
    }
}