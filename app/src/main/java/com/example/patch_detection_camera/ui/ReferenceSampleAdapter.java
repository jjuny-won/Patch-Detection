package com.example.patch_detection_camera.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.patch_detection_camera.R;

import java.util.List;
import java.util.Locale;

public class ReferenceSampleAdapter extends RecyclerView.Adapter<ReferenceSampleAdapter.VH> {

    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    private final List<ReferenceSample> items;
    private final OnDeleteClickListener deleteListener;

    public ReferenceSampleAdapter(List<ReferenceSample> items,
                                  OnDeleteClickListener deleteListener) {
        this.items = items;
        this.deleteListener = deleteListener;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvSample;
        Button btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            tvSample = itemView.findViewById(R.id.tvSample);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reference_sample, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ReferenceSample item = items.get(position);
        holder.tvSample.setText(formatRgb15(item.rgb15));

        holder.btnDelete.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onDelete(holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatRgb15(float[] rgb15) {
        if (rgb15 == null || rgb15.length < 15) return "(empty)";

        String[] labels = {"Gray", "Red", "Green", "Blue", "White"};
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 5; i++) {
            int r = Math.round(rgb15[i * 3]);
            int g = Math.round(rgb15[i * 3 + 1]);
            int b = Math.round(rgb15[i * 3 + 2]);

            sb.append(String.format(Locale.US, "%s - %d,%d,%d", labels[i], r, g, b));
            if (i != 4) sb.append("\n");
        }

        return sb.toString();
    }
}