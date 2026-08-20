package com.example.patch_detection_camera.ui;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExportUtils {

    public static void exportMeasurementsCsv(Context ctx, List<Measurement> measurements) {
        String tag      = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        String filename = "patch_measurements_" + tag + ".csv";
        byte[] bytes    = buildCsv(measurements).getBytes(StandardCharsets.UTF_8);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert failed");
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("OutputStream null");
                    os.write(bytes);
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
            } else {
                File dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                }
            }
            Toast.makeText(ctx, "저장 완료: Downloads/" + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String buildCsv(List<Measurement> list) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();

        // UTF-8 BOM (Excel 한글 깨짐 방지)
        sb.append('\uFEFF');

        // Header
        sb.append("capturedAt,dateTime,dressingId,calibrationApplied,calibrationStatus,")
          .append("medianDeltaE,maxDeltaE,")
          .append("pH,pH_status,")
          .append("temperature_C,temp_status,")
          .append("uricAcid_umolL,uric_status,")
          .append("glucose_mmolL,glucose_status,")
          .append("imageUrl\n");

        for (Measurement m : list) {
            SensorValue ph   = m.getSensor("pH");
            SensorValue temp = m.getSensor("Temperature");
            SensorValue uric = m.getSensor("UricAcid");
            SensorValue gluc = m.getSensor("Glucose");

            sb.append(m.capturedAt).append(',');
            sb.append(sdf.format(new Date(m.capturedAt))).append(',');
            sb.append(m.dressingId).append(',');
            sb.append(m.calibrationApplied).append(',');
            sb.append(m.calibrationStatus).append(',');
            sb.append(fmt2(m.medianDeltaE)).append(',');
            sb.append(fmt2(m.maxDeltaE)).append(',');

            appendSensor(sb, ph,   "%.2f");
            appendSensor(sb, temp, "%.2f");
            appendSensor(sb, uric, "%.1f");
            appendSensor(sb, gluc, "%.2f");

            sb.append('"').append(m.imageUrl != null ? m.imageUrl : "").append('"');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendSensor(StringBuilder sb, SensorValue sv, String fmt) {
        if (sv != null) {
            sb.append(String.format(Locale.US, fmt, sv.numericValue)).append(',');
            sb.append(sv.statusCode).append(',');
        } else {
            sb.append("N/A,N/A,");
        }
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
