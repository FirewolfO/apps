package top.lxvb.yuque;

import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import top.lxvb.yuque.databinding.ItemMessageBinding;

import java.util.ArrayList;
import java.util.List;

final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {
    interface RetryListener { void onRetry(Models.Message message); }

    private final List<Models.Message> items = new ArrayList<>();
    private final String currentUserId;
    private final ApiClient api;
    private final RetryListener retryListener;

    MessageAdapter(String currentUserId, ApiClient api, RetryListener retryListener) {
        this.currentUserId = currentUserId;
        this.api = api;
        this.retryListener = retryListener;
    }

    void submit(List<Models.Message> messages) {
        List<Models.Message> local = new ArrayList<>();
        for (Models.Message item : items) {
            if (!Models.Message.SENT.equals(item.deliveryState)) local.add(item);
        }
        items.clear();
        items.addAll(messages);
        for (Models.Message item : local) if (indexOf(item) < 0) items.add(item);
        notifyDataSetChanged();
    }

    void append(Models.Message message) {
        int existing = indexOf(message);
        if (existing >= 0) {
            Models.Message current = items.get(existing);
            if (!current.id.equals(message.id) || current.readByPeer != message.readByPeer
                    || !current.deliveryState.equals(message.deliveryState)) {
                items.set(existing, message);
                notifyItemChanged(existing);
            }
            return;
        }
        items.add(message);
        notifyItemInserted(items.size() - 1);
    }

    int merge(List<Models.Message> messages) {
        int firstInserted = items.size();
        int added = 0;
        for (Models.Message message : messages) {
            int existing = indexOf(message);
            if (existing < 0) {
                items.add(message);
                added++;
            } else {
                Models.Message current = items.get(existing);
                if (!current.id.equals(message.id) || current.readByPeer != message.readByPeer
                        || !Models.Message.SENT.equals(current.deliveryState)) {
                    items.set(existing, message);
                    notifyItemChanged(existing);
                }
            }
        }
        if (added > 0) notifyItemRangeInserted(firstInserted, added);
        return added;
    }

    void confirm(String clientId, Models.Message message) {
        int index = indexOfClientId(clientId);
        if (index < 0) append(message);
        else {
            items.set(index, message);
            notifyItemChanged(index);
        }
    }

    void markSending(String clientId) { updateDelivery(clientId, Models.Message.SENDING); }
    void markFailed(String clientId) { updateDelivery(clientId, Models.Message.FAILED); }
    void markReceiveFailed(String clientId) { updateDelivery(clientId, Models.Message.FAILED); }

    void updateProgress(String clientId, int progress) {
        int index = indexOfClientId(clientId);
        if (index < 0) return;
        items.set(index, items.get(index).withProgress(progress));
        notifyItemChanged(index);
    }

    Models.Message readyToSend(String clientId, String mediaUrl, String mimeType, String type) {
        int index = indexOfClientId(clientId);
        if (index < 0) return null;
        Models.Message ready = items.get(index).readyToSend(mediaUrl, mimeType, type);
        items.set(index, ready);
        notifyItemChanged(index);
        return ready;
    }

    void markRead(long readAt) {
        for (int i = 0; i < items.size(); i++) {
            Models.Message message = items.get(i);
            if (currentUserId.equals(message.senderId) && message.createdAt <= readAt
                    && Models.Message.SENT.equals(message.deliveryState) && !message.readByPeer) {
                items.set(i, message.asRead());
                notifyItemChanged(i);
            }
        }
    }

    private void updateDelivery(String clientId, String state) {
        int index = indexOfClientId(clientId);
        if (index < 0) return;
        items.set(index, items.get(index).withDeliveryState(state));
        notifyItemChanged(index);
    }

    private int indexOf(Models.Message message) {
        for (int i = 0; i < items.size(); i++) {
            Models.Message existing = items.get(i);
            if (existing.id.equals(message.id)
                    || (!message.clientId.isEmpty() && message.clientId.equals(existing.clientId))) return i;
        }
        return -1;
    }

    private int indexOfClientId(String clientId) {
        for (int i = 0; i < items.size(); i++) {
            if (clientId.equals(items.get(i).clientId)) return i;
        }
        return -1;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.Message message = items.get(position);
        boolean mine = currentUserId.equals(message.senderId);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.binding.bubble.getLayoutParams();
        params.gravity = mine ? Gravity.END : Gravity.START;
        int avatarSpace = dp(holder.itemView, 40);
        params.leftMargin = mine ? 0 : avatarSpace;
        params.rightMargin = mine ? avatarSpace : 0;
        holder.binding.bubble.setLayoutParams(params);
        FrameLayout.LayoutParams avatarParams = (FrameLayout.LayoutParams) holder.binding.avatar.getLayoutParams();
        avatarParams.gravity = (mine ? Gravity.END : Gravity.START) | Gravity.BOTTOM;
        holder.binding.avatar.setLayoutParams(avatarParams);
        holder.binding.avatar.setText(Ui.initials(message.senderName));
        holder.binding.avatar.setBackgroundResource(mine ? R.drawable.bg_avatar_dark : R.drawable.bg_avatar);
        holder.binding.avatar.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), mine ? R.color.white : R.color.teal_dark));
        holder.binding.bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_sent : R.drawable.bg_bubble_received);
        holder.binding.content.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), mine ? R.color.white : R.color.ink));
        holder.binding.time.setTextColor(mine ? Color.argb(190, 255, 255, 255) : ContextCompat.getColor(holder.itemView.getContext(), R.color.muted));
        holder.binding.time.setText(Ui.shortTime(message.createdAt));
        holder.binding.status.setVisibility(mine ? View.VISIBLE : View.GONE);
        holder.binding.status.setOnClickListener(null);
        holder.binding.transferProgress.setVisibility(View.GONE);
        holder.binding.transferProgress.setIndeterminate(false);
        holder.binding.mediaStatus.setVisibility(View.GONE);
        if (mine) {
            if (Models.Message.UPLOADING.equals(message.deliveryState)) {
                holder.binding.status.setText("发送中 " + message.progress + "%");
                holder.binding.status.setTextColor(Color.argb(210, 255, 255, 255));
                holder.binding.transferProgress.setVisibility(View.VISIBLE);
                holder.binding.transferProgress.setProgress(message.progress);
            } else if (Models.Message.SENDING.equals(message.deliveryState)) {
                holder.binding.status.setText("发送中…");
                holder.binding.status.setTextColor(Color.argb(190, 255, 255, 255));
            } else if (Models.Message.FAILED.equals(message.deliveryState)) {
                holder.binding.status.setText("发送失败 · 点击重试");
                holder.binding.status.setTextColor(Color.rgb(255, 205, 196));
                holder.binding.status.setOnClickListener(view -> retryListener.onRetry(message));
            } else {
                holder.binding.status.setText(message.readByPeer ? "已读" : "未读");
                holder.binding.status.setTextColor(Color.argb(210, 255, 255, 255));
            }
        }
        holder.binding.senderName.setVisibility(View.GONE);
        holder.binding.mediaFrame.setVisibility(View.GONE);
        holder.binding.content.setVisibility(View.GONE);
        holder.binding.playBadge.setVisibility(View.GONE);
        Glide.with(holder.binding.mediaImage).clear(holder.binding.mediaImage);

        if ("image".equals(message.type) || "video".equals(message.type)) {
            String url = api.absoluteMediaUrl(message.mediaUrl);
            holder.binding.mediaFrame.setVisibility(View.VISIBLE);
            holder.binding.playBadge.setVisibility("video".equals(message.type) ? View.VISIBLE : View.GONE);
            boolean receiving = Models.Message.RECEIVING.equals(message.deliveryState);
            if (url.isEmpty() || receiving) {
                holder.binding.mediaImage.setImageDrawable(null);
                holder.binding.mediaImage.setBackgroundColor(Color.rgb(33, 54, 52));
                holder.binding.mediaStatus.setVisibility(View.VISIBLE);
                holder.binding.mediaStatus.setText("video".equals(message.type) ? "视频接收中…" : "图片接收中…");
                holder.binding.playBadge.setVisibility(View.GONE);
                holder.binding.transferProgress.setVisibility(View.VISIBLE);
                holder.binding.transferProgress.setIndeterminate(true);
                holder.binding.mediaFrame.setOnClickListener(null);
            } else {
                holder.binding.mediaImage.setBackgroundColor(Color.TRANSPARENT);
                Glide.with(holder.binding.mediaImage).load(url).centerCrop().into(holder.binding.mediaImage);
                holder.binding.mediaFrame.setOnClickListener(view ->
                        MediaViewerActivity.open(view.getContext(), url, message.type, message.mimeType));
            }
            if (!mine && Models.Message.FAILED.equals(message.deliveryState)) {
                holder.binding.mediaStatus.setVisibility(View.VISIBLE);
                holder.binding.mediaStatus.setText("接收失败");
                holder.binding.transferProgress.setVisibility(View.GONE);
            }
        } else {
            holder.binding.content.setVisibility(View.VISIBLE);
            holder.binding.content.setText(message.content);
            holder.binding.content.setTextSize("emoji".equals(message.type) ? 30 : 15);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemMessageBinding binding;
        Holder(ItemMessageBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
