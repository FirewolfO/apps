package top.lxvb.yuque;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import top.lxvb.yuque.databinding.ItemConversationBinding;

import java.util.ArrayList;
import java.util.List;

final class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.Holder> {
    interface Listener { void onConversation(Models.Conversation conversation); }
    private final List<Models.Conversation> items = new ArrayList<>();
    private final Listener listener;

    ConversationAdapter(Listener listener) { this.listener = listener; }

    void submit(List<Models.Conversation> conversations) {
        items.clear();
        items.addAll(conversations);
        notifyDataSetChanged();
    }

    List<Models.Conversation> items() { return items; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.Conversation item = items.get(position);
        holder.binding.avatar.setText(Ui.initials(item.peer.displayName));
        holder.binding.name.setText(item.peer.displayName);
        holder.binding.preview.setText(preview(item));
        holder.binding.time.setText(Ui.shortTime(item.lastTime));
        holder.binding.unread.setVisibility(item.unreadCount > 0 ? View.VISIBLE : View.GONE);
        holder.binding.unread.setText(item.unreadCount > 99 ? "99+" : String.valueOf(item.unreadCount));
        holder.itemView.setOnClickListener(view -> listener.onConversation(item));
    }

    private String preview(Models.Conversation item) {
        switch (item.lastType) {
            case "image": return "[图片]";
            case "video": return "[视频]";
            case "emoji": return item.lastContent;
            case "text": return item.lastContent;
            default: return "新会话";
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemConversationBinding binding;
        Holder(ItemConversationBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
