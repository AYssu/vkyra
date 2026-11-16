package com.vkyra.code.plugin;

import com.vkyra.code.R;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class PluginAdapter extends RecyclerView.Adapter<PluginAdapter.ViewHolder> {

    private List<PluginInfo> plugins = new ArrayList<>();
    private OnPluginActionListener listener;

    public interface OnPluginActionListener {
        void onLongClick(PluginInfo plugin, int position);
        void onAbiClick(PluginInfo plugin, int position);
        void onClick(PluginInfo plugin, int position);
        void onEnableChanged(PluginInfo plugin, int position, boolean enabled);
        void onUninstallClick(PluginInfo plugin, int position);
        void onLoadErrorClick(PluginInfo plugin, int position);
    }

    public void setOnPluginActionListener(OnPluginActionListener listener) {
        this.listener = listener;
    }

    public void setPlugins(List<PluginInfo> plugins) {
        this.plugins = plugins;
        notifyDataSetChanged();
    }

    public void removePlugin(int position) {
        if (position >= 0 && position < plugins.size()) {
            plugins.remove(position);
            notifyItemRemoved(position);
        }
    }

    public void updatePlugin(int position) {
        if (position >= 0 && position < plugins.size()) {
            notifyItemChanged(position);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_plugin, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PluginInfo plugin = plugins.get(position);
        holder.bind(plugin, position);
    }

    @Override
    public int getItemCount() {
        return plugins.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView nameText;
        private TextView versionAuthorText;
        private TextView abiText;
        private TextView descriptionText;
        private TextView statusText;
        private ImageView githubIcon;
        private MaterialSwitch enableSwitch;
        private LinearLayout uninstallBtn;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.plugin_name);
            versionAuthorText = itemView.findViewById(R.id.plugin_version_author);
            abiText = itemView.findViewById(R.id.plugin_abi);
            descriptionText = itemView.findViewById(R.id.plugin_description);
            statusText = itemView.findViewById(R.id.plugin_status);
            githubIcon = itemView.findViewById(R.id.btn_github);
            enableSwitch = itemView.findViewById(R.id.switch_enabled);
            uninstallBtn = itemView.findViewById(R.id.btn_uninstall);
        }

        void bind(PluginInfo plugin, int position) {
            nameText.setText(plugin.getPluginName());
            
            // 版本 + 作者
            String versionAuthor = "v" + plugin.getPluginVersion() + ", 作者 " + plugin.getAuthor();
            versionAuthorText.setText(versionAuthor);
            
            // 描述
            String desc = plugin.getPluginDescribe();
            if (desc != null && !desc.isEmpty()) {
                descriptionText.setVisibility(View.VISIBLE);
                descriptionText.setText(desc);
            } else {
                descriptionText.setVisibility(View.GONE);
            }

            if (plugin.hasLoadError()) {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("加载失败，点击查看原因");
                statusText.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onLoadErrorClick(plugin, position);
                    }
                });
            } else {
                statusText.setVisibility(View.GONE);
                statusText.setOnClickListener(null);
            }

            // ABI标签
            if (plugin.hasMultipleABI()) {
                abiText.setVisibility(View.VISIBLE);
                String currentAbi = plugin.getCurrentABI();
                if (currentAbi == null || currentAbi.isEmpty()) {
                    currentAbi = plugin.getDefaultABI();
                }
                abiText.setText(currentAbi);
                abiText.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onAbiClick(plugin, position);
                    }
                });
            } else {
                abiText.setVisibility(View.GONE);
            }

            // 启用开关
            enableSwitch.setOnCheckedChangeListener(null);
            enableSwitch.setChecked(plugin.isEnabled());
            enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null) {
                    listener.onEnableChanged(plugin, position, isChecked);
                }
            });

            // 根据启用状态调整透明度
            float alpha = plugin.isEnabled() ? 1.0f : 0.5f;
            nameText.setAlpha(alpha);
            versionAuthorText.setAlpha(alpha);
            descriptionText.setAlpha(alpha);

            // GitHub图标
            if (plugin.hasGithub()) {
                githubIcon.setVisibility(View.VISIBLE);
                githubIcon.setAlpha(plugin.isEnabled() ? 1.0f : 0.5f);
                githubIcon.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(plugin.getGithub()));
                    itemView.getContext().startActivity(intent);
                });
            } else {
                githubIcon.setVisibility(View.GONE);
            }

            // 卸载按钮
            uninstallBtn.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onUninstallClick(plugin, position);
                }
            });

            // 长按
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onLongClick(plugin, position);
                }
                return true;
            });

            // 点击查看文件
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClick(plugin, position);
                }
            });
        }
    }
}
