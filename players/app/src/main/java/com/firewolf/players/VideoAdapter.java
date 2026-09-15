package com.firewolf.players;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class VideoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onVideoSelected(VideoItem item);
    }

    static final int TYPE_HERO = 1;
    static final int TYPE_SECTION = 2;
    static final int TYPE_VIDEO = 3;
    static final int TYPE_EMPTY = 4;

    private final ImageLoader imageLoader;
    private final Listener listener;
    private final List<Object> rows = new ArrayList<>();

    public VideoAdapter(ImageLoader imageLoader, Listener listener) {
        this.imageLoader = imageLoader;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<VideoItem> items, String sectionTitle) {
        rows.clear();
        if (items.isEmpty()) {
            rows.add(new EmptyRow());
        } else {
            rows.add(new HeroRow(items.get(0)));
            rows.add(new SectionRow(sectionTitle + " · " + items.size()));
            rows.addAll(items);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        Object row = rows.get(position);
        if (row instanceof HeroRow) return ("hero:" + ((HeroRow) row).item.id).hashCode();
        if (row instanceof VideoItem) return ((VideoItem) row).id.hashCode();
        return Long.MIN_VALUE + position;
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row instanceof HeroRow) return TYPE_HERO;
        if (row instanceof SectionRow) return TYPE_SECTION;
        if (row instanceof VideoItem) return TYPE_VIDEO;
        return TYPE_EMPTY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HERO) return new HeroHolder(inflater.inflate(R.layout.item_hero, parent, false));
        if (viewType == TYPE_SECTION) return new SectionHolder(inflater.inflate(R.layout.item_section, parent, false));
        if (viewType == TYPE_VIDEO) return new VideoHolder(inflater.inflate(R.layout.item_video, parent, false));
        return new EmptyHolder(inflater.inflate(R.layout.item_empty, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeroHolder) ((HeroHolder) holder).bind(((HeroRow) row).item);
        else if (holder instanceof SectionHolder) ((SectionHolder) holder).title.setText(((SectionRow) row).title);
        else if (holder instanceof VideoHolder) ((VideoHolder) holder).bind((VideoItem) row);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private final class HeroHolder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final TextView title;
        final TextView summary;
        final TextView badge;

        HeroHolder(View view) {
            super(view);
            poster = view.findViewById(R.id.hero_poster);
            title = view.findViewById(R.id.hero_title);
            summary = view.findViewById(R.id.hero_summary);
            badge = view.findViewById(R.id.hero_badge);
        }

        void bind(VideoItem item) {
            title.setText(item.title);
            summary.setText(item.summary);
            badge.setText("今日推荐 · " + item.badge);
            imageLoader.load(item.posterUrl, poster);
            itemView.setOnClickListener(view -> listener.onVideoSelected(item));
        }
    }

    private final class VideoHolder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final TextView title;
        final TextView meta;
        final TextView badge;

        VideoHolder(View view) {
            super(view);
            poster = view.findViewById(R.id.poster);
            title = view.findViewById(R.id.title);
            meta = view.findViewById(R.id.meta);
            badge = view.findViewById(R.id.badge);
        }

        void bind(VideoItem item) {
            title.setText(item.title);
            String prefix = item.year.isEmpty() ? item.source : item.year + " · " + item.source;
            meta.setText(prefix);
            badge.setText(item.badge);
            imageLoader.load(item.posterUrl, poster);
            itemView.setOnClickListener(view -> listener.onVideoSelected(item));
        }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        SectionHolder(View view) {
            super(view);
            title = view.findViewById(R.id.section_title);
        }
    }

    private static final class EmptyHolder extends RecyclerView.ViewHolder {
        EmptyHolder(View view) { super(view); }
    }

    private static final class HeroRow {
        final VideoItem item;
        HeroRow(VideoItem item) { this.item = item; }
    }

    private static final class SectionRow {
        final String title;
        SectionRow(String title) { this.title = title; }
    }

    private static final class EmptyRow {}
}
