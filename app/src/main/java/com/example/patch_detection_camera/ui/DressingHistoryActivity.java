package com.example.patch_detection_camera.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import com.example.patch_detection_camera.R;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class DressingHistoryActivity extends AppCompatActivity {

    private LinearLayout recordsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dressing_history);

        recordsContainer = findViewById(R.id.recordsContainer);
        loadAndDisplayRecords();
        requestNotificationPermission();

        findViewById(R.id.btnDressingChange).setOnClickListener(v -> showDressingDialog(null));

        findViewById(R.id.btnHome).setOnClickListener(v -> {
            Intent it = new Intent(this, ModeSelectActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAndDisplayRecords();
    }

    private void loadAndDisplayRecords() {
        recordsContainer.removeAllViews();
        FirebaseRepository.getInstance().loadDressingRecords(new FirebaseRepository.DressingCallback() {
            @Override public void onSuccess(List<DressingRecord> records) { displayRecords(records); }
            @Override public void onFailure(Exception e) {
                Toast.makeText(DressingHistoryActivity.this, "서버 연결 실패. 로컬 데이터를 표시합니다.", Toast.LENGTH_SHORT).show();
                displayRecords(DressingStore.loadRecords(DressingHistoryActivity.this));
            }
        });
    }

    private void displayRecords(List<DressingRecord> records) {
        recordsContainer.removeAllViews();

        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("기록이 없습니다.");
            empty.setTextSize(15f);
            empty.setPadding(48, 48, 48, 48);
            empty.setTextColor(0xFF888888);
            recordsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < records.size(); i++) {
            DressingRecord record = records.get(i);
            View itemView = LayoutInflater.from(this)
                    .inflate(R.layout.item_dressing_record, recordsContainer, false);

            ((TextView) itemView.findViewById(R.id.tvDate)).setText(record.getDateStr());
            ((TextView) itemView.findViewById(R.id.tvTime)).setText(record.getTimeStr());

            if (i == records.size() - 1) {
                itemView.findViewById(R.id.divider).setVisibility(View.GONE);
            }

            itemView.setOnLongClickListener(v -> {
                showRecordOptions(record);
                return true;
            });

            recordsContainer.addView(itemView);
        }
    }

    // 수정/삭제 선택 다이얼로그
    private void showRecordOptions(DressingRecord record) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"수정", "삭제"}, (dialog, which) -> {
                    if (which == 0) showDressingDialog(record);
                    else           confirmDelete(record);
                })
                .show();
    }

    private void confirmDelete(DressingRecord record) {
        new AlertDialog.Builder(this)
                .setMessage("삭제하시겠습니까?")
                .setPositiveButton("삭제", (d, w) ->
                        FirebaseRepository.getInstance().deleteDressingRecord(record.getTimestamp(),
                                new FirebaseRepository.SimpleCallback() {
                                    @Override public void onSuccess() {
                                        DressingStore.deleteRecord(DressingHistoryActivity.this, record.getTimestamp());
                                        loadAndDisplayRecords();
                                    }
                                    @Override public void onFailure(Exception e) {
                                        Toast.makeText(DressingHistoryActivity.this, "삭제 실패", Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton("취소", null)
                .show();
    }

    // record == null 이면 신규 등록, non-null 이면 수정
    private void showDressingDialog(DressingRecord existing) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_dressing_change, null);

        EditText etYear   = dialogView.findViewById(R.id.etYear);
        EditText etMonth  = dialogView.findViewById(R.id.etMonth);
        EditText etDay    = dialogView.findViewById(R.id.etDay);
        EditText etHour   = dialogView.findViewById(R.id.etHour);
        EditText etMinute = dialogView.findViewById(R.id.etMinute);
        CheckBox cbCurrent = dialogView.findViewById(R.id.cbCurrentDate);

        // 수정 모드: 기존 값 파싱해서 미리 채움
        if (existing != null) {
            try {
                // dateStr 형식: "2026.05. 26"
                String[] dateParts = existing.getDateStr().split("\\.");
                etYear.setText(dateParts[0].trim());
                etMonth.setText(dateParts[1].trim());
                etDay.setText(dateParts[2].trim());
            } catch (Exception ignored) {}
            try {
                // timeStr 형식: "03 : 55"
                String[] timeParts = existing.getTimeStr().split(" : ");
                etHour.setText(timeParts[0].trim());
                etMinute.setText(timeParts[1].trim());
            } catch (Exception ignored) {}
        }

        cbCurrent.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                Calendar cal = Calendar.getInstance();
                etYear.setText(String.valueOf(cal.get(Calendar.YEAR)));
                etMonth.setText(String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1));
                etDay.setText(String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH)));
                etHour.setText(String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY)));
                etMinute.setText(String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE)));
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            String yearStr   = etYear.getText().toString().trim();
            String monthStr  = etMonth.getText().toString().trim();
            String dayStr    = etDay.getText().toString().trim();
            String hourStr   = etHour.getText().toString().trim();
            String minuteStr = etMinute.getText().toString().trim();

            if (yearStr.isEmpty() || monthStr.isEmpty() || dayStr.isEmpty()
                    || hourStr.isEmpty() || minuteStr.isEmpty()) {
                Toast.makeText(this, "모든 항목을 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }

            String dateStr = String.format(Locale.US, "%s.%s. %s", yearStr, monthStr, dayStr);
            String timeStr = String.format(Locale.US, "%s : %s", hourStr, minuteStr);

            // 수정이면 원래 timestamp 유지, 신규면 현재 시각
            long ts = (existing != null) ? existing.getTimestamp() : System.currentTimeMillis();
            DressingRecord record = new DressingRecord(ts, dateStr, timeStr);

            DressingStore.updateRecord(this, record); // 로컬 저장 (신규도 upsert 동작)
            DressingStore.setActiveDressingId(this, ts); // 현재 활성 dressingId 갱신

            // 드레싱 스케줄 저장 + 6시간 알림 예약
            DressingSchedule schedule = new DressingSchedule(System.currentTimeMillis());
            FirebaseRepository.getInstance().saveDressingSchedule(schedule, null);
            scheduleDressingReminder();

            FirebaseRepository.getInstance().addDressingRecord(record, new FirebaseRepository.SimpleCallback() {
                @Override public void onSuccess() {
                    dialog.dismiss();
                    loadAndDisplayRecords();
                }
                @Override public void onFailure(Exception e) {
                    Toast.makeText(DressingHistoryActivity.this,
                            "서버 저장 실패 (로컬에만 저장됨)", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    loadAndDisplayRecords();
                }
            });
        });

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void scheduleDressingReminder() {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DressingReminderWorker.class)
                .setInitialDelay(DressingSchedule.CYCLE_HOURS, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(this).enqueueUniqueWork(
                "dressing_reminder",
                ExistingWorkPolicy.REPLACE,
                request);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }
}
