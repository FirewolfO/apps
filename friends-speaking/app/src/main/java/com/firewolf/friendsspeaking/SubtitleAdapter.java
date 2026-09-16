package com.firewolf.friendsspeaking;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class SubtitleAdapter extends RecyclerView.Adapter<SubtitleAdapter.Holder> {
    interface Listener { void onCue(SubtitleCue cue); }

    private final Listener listener;
    private List<SubtitleCue> cues = new ArrayList<>();
    private int active = -1;

    SubtitleAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<SubtitleCue> values) {
        cues = new ArrayList<>(values);
        active = -1;
        notifyDataSetChanged();
    }

    void setActive(int position) {
        if (position == active) return;
        int previous = active;
        active = position;
        if (previous >= 0 && previous < cues.size()) notifyItemChanged(previous);
        if (active >= 0 && active < cues.size()) notifyItemChanged(active);
    }

    SubtitleCue cue(int position) {
        return position >= 0 && position < cues.size() ? cues.get(position) : null;
    }

    @NonNull
    @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_subtitle_cue, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        SubtitleCue cue = cues.get(position);
        boolean selected = position == active;
        holder.text.setText(cue.text);
        holder.text.setTextColor(holder.itemView.getContext().getColor(selected ? R.color.brand : R.color.cream));
        holder.text.setTextSize(selected ? 19f : 16f);
        holder.itemView.setAlpha(selected ? 1f : 0.68f);
        holder.itemView.setBackgroundResource(selected ? R.drawable.bg_subtitle : android.R.color.transparent);
        holder.itemView.setOnClickListener(view -> listener.onCue(cue));
    }

    @Override public int getItemCount() { return cues.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView text;
        Holder(View view) {
            super(view);
            text = view.findViewById(R.id.subtitle_text);
        }
    }
}
