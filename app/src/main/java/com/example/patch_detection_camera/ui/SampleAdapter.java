package com.example.patch_detection_camera.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.patch_detection_camera.R;

import java.util.List;

public class SampleAdapter extends RecyclerView.Adapter<SampleAdapter.SampleViewHolder> {

    private final List<float[]> sampleList;
    private final Context context;

    public SampleAdapter(List<float[]> sampleList, Context context) {
        this.sampleList = sampleList;
        this.context = context;
    }

    @NonNull
    @Override
    public SampleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(context).inflate(R.layout.item_sample, parent, false);
        return new SampleViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull SampleViewHolder holder, int position) {
        float[] sample = sampleList.get(position);

        holder.tvSample.setText(
                "Sample " + (position + 1) + ": " + formatRgbBlocks(sample)
        );

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            sampleList.remove(pos);
            notifyItemRemoved(pos);
            notifyItemRangeChanged(pos, sampleList.size());
        });
    }

    @Override
    public int getItemCount() {
        return sampleList.size();
    }

    /** float[] -> "[(12, 34, 56), (78, 90, 12), ...]" */
    private static String formatRgbBlocks(float[] arr) {
        if (arr == null || arr.length < 3) return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append("[");

        int N = arr.length / 3;
        for (int i = 0; i < N; i++) {
            int r = Math.round(arr[i * 3]);
            int g = Math.round(arr[i * 3 + 1]);
            int b = Math.round(arr[i * 3 + 2]);

            sb.append("(")
                    .append(r).append(", ")
                    .append(g).append(", ")
                    .append(b).append(")");

            if (i != N - 1) sb.append(", ");
        }

        sb.append("]");
        return sb.toString();
    }

    public static class SampleViewHolder extends RecyclerView.ViewHolder {
        TextView tvSample;
        Button btnDelete;

        public SampleViewHolder(View itemView) {
            super(itemView);
            tvSample = itemView.findViewById(R.id.tvSample);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}