package com.example.patch_detection_camera.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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

        findViewById(R.id.btnDressingChange).setOnClickListener(v -> showDressingChangeDialog());

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
        List<DressingRecord> records = DressingStore.loadRecords(this);

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

            final long timestamp = record.getTimestamp();
            itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("삭제하시겠습니까?")
                        .setPositiveButton("삭제", (dialog, which) -> {
                            DressingStore.deleteRecord(this, timestamp);
                            loadAndDisplayRecords();
                        })
                        .setNegativeButton("취소", null)
                        .show();
                return true;
            });

            recordsContainer.addView(itemView);
        }
    }

    private void showDressingChangeDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_dressing_change, null);

        EditText etYear    = dialogView.findViewById(R.id.etYear);
        EditText etMonth   = dialogView.findViewById(R.id.etMonth);
        EditText etDay     = dialogView.findViewById(R.id.etDay);
        EditText etHour    = dialogView.findViewById(R.id.etHour);
        EditText etMinute  = dialogView.findViewById(R.id.etMinute);
        CheckBox cbCurrent = dialogView.findViewById(R.id.cbCurrentDate);

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

            DressingStore.addRecord(this, new DressingRecord(
                    System.currentTimeMillis(), dateStr, timeStr));

            dialog.dismiss();
            loadAndDisplayRecords();
        });

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }
}
