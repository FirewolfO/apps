package com.firewolf.friendsspeaking;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
    interface Listener { void onEpisode(Episode episode); }

    private final LearningStore store;
    private final Listener listener;
    private List<Episode> episodes = new ArrayList<>();

    EpisodeAdapter(LearningStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    void submit(List<Episode> values) {
        episodes = new ArrayList<>(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Episode episode = episodes.get(position);
        holder.number.setText(String.format(Locale.CHINA, "%02d", episode.number));
        holder.title.setText(episode.displayTitle() + " · " + episode.key);
        boolean audio = store.audio(episode) != null;
        boolean subtitle = store.subtitle(episode) != null;
        holder.status.setText(audio && subtitle
                ? (store.isRemoteAudio(episode) || store.isRemoteSubtitle(episode)
                    ? "内网音频 + 双语台词稿" : "本地音频 + 字幕已就绪")
                : audio ? "已有音频 · 本集台词稿缺失"
                : subtitle ? "已有字幕 · 本集音频缺失" : "服务器无本集资源 · 可导入本地文件");
        long progress = store.progress(episode);
        long duration = store.duration(episode);
        if (progress > 0) {
            int percent = duration > 0 ? (int) Math.min(100, progress * 100 / duration) : 0;
            holder.progress.setText("继续 " + time(progress) + (percent > 0 ? " · " + percent + "%" : ""));
            holder.progress.setVisibility(View.VISIBLE);
        } else {
            holder.progress.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(view -> listener.onEpisode(episode));
    }

    @Override public int getItemCount() { return episodes.size(); }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView number;
        final TextView title;
        final TextView status;
        final TextView progress;

        Holder(View view) {
            super(view);
            number = view.findViewById(R.id.episode_number);
            title = view.findViewById(R.id.episode_title);
            status = view.findViewById(R.id.episode_status);
            progress = view.findViewById(R.id.episode_progress);
        }
    }
}
