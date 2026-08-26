package top.lxvb.yuque;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import top.lxvb.yuque.databinding.ItemContactBinding;

import java.util.ArrayList;
import java.util.List;

final class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.Holder> {
    interface Listener { void onContact(Models.User user); }
    private final List<Models.User> items = new ArrayList<>();
    private final Listener listener;

    ContactAdapter(Listener listener) { this.listener = listener; }

    void submit(List<Models.User> users) {
        items.clear();
        items.addAll(users);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.User user = items.get(position);
        holder.binding.avatar.setText(Ui.initials(user.displayName));
        holder.binding.name.setText(user.displayName);
        holder.binding.username.setText("@" + user.username);
        holder.binding.messageButton.setOnClickListener(view -> listener.onContact(user));
        holder.itemView.setOnClickListener(view -> listener.onContact(user));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemContactBinding binding;
        Holder(ItemContactBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
