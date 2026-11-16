package com.vkyra.code.files;

import com.vkyra.code.R;
import com.vkyra.code.git.GitManager;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.toolchain.HeaderDirManager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.topjohnwu.superuser.io.SuFile;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private List<File> files = new ArrayList<>();
    private OnFileClickListener listener;
    private boolean hasParentEntry = false;
    private String currentPath = "";
    private Context context;
    private Set<String> headerDirs;
    private boolean isDarkTheme = true;
    
    // Git 状态
    private File gitRepoRoot;
    private GitManager.GitStatus gitStatus;
    private Map<String, Character> gitFileStatusMap = new HashMap<>();  // 文件路径 -> 状态字符

    // 图标颜色
    private static final int COLOR_FOLDER_DARK = 0xFF9E9E9E;      // 文件夹 - 灰色（暗色）
    private static final int COLOR_FOLDER_LIGHT = 0xFF757575;     // 文件夹 - 灰色（亮色）
    private static final int COLOR_FILE_DARK = 0xFF9E9E9E;        // 文件 - 灰色（暗色）
    private static final int COLOR_FILE_LIGHT = 0xFF757575;       // 文件 - 灰色（亮色）
    private static final int COLOR_HEADER_DIR = 0xFFFFD54F;       // 头文件目录 - 黄色
    private static final int COLOR_TEXT_DARK = 0xFFE0E0E0;        // 文字 - 浅色（暗色主题）
    private static final int COLOR_TEXT_LIGHT = 0xFF1E1E1E;       // 文字 - 深色（亮色主题）
    
    // Git 状态颜色
    private static final int COLOR_GIT_MODIFIED = 0xFFE2B93D;     // 已修改 - 黄色 M
    private static final int COLOR_GIT_ADDED = 0xFF73C991;        // 已暂存/新增 - 绿色 A
    private static final int COLOR_GIT_UNTRACKED = 0xFF73C991;    // 未跟踪 - 绿色 U
    private static final int COLOR_GIT_DELETED = 0xFFF14C4C;      // 已删除 - 红色 D
    private static final int COLOR_GIT_CONFLICT = 0xFFF14C4C;     // 冲突 - 红色 C

    public interface OnFileClickListener {
        void onFileClick(File file);
        void onFileLongClick(File file);
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.listener = listener;
    }
    
    public void setContext(Context context) {
        this.context = context;
        refreshHeaderDirs();
        updateTheme();
    }
    
    /**
     * 更新主题设置
     */
    public void updateTheme() {
        if (context != null) {
            String theme = AppPreferences.getEditorTheme(context);
            if ("system".equals(theme)) {
                int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                isDarkTheme = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
            } else if ("light".equals(theme)) {
                isDarkTheme = false;
            } else {
                isDarkTheme = true;
            }
        }
    }
    
    /**
     * 刷新头文件目录列表
     */
    public void refreshHeaderDirs() {
        if (context != null) {
            headerDirs = HeaderDirManager.getHeaderDirs(context);
            notifyDataSetChanged();
        }
    }
    
    /**
     * 设置 Git 状态信息
     */
    public void setGitStatus(File repoRoot, GitManager.GitStatus status) {
        this.gitRepoRoot = repoRoot;
        this.gitStatus = status;
        
        // 构建文件状态映射
        gitFileStatusMap.clear();
        if (status != null && repoRoot != null) {
            // 已暂存的文件
            for (String path : status.staged) {
                gitFileStatusMap.put(path, 'A');
            }
            // 已修改的文件（覆盖暂存状态，显示 M）
            for (String path : status.modified) {
                gitFileStatusMap.put(path, 'M');
            }
            // 未跟踪的文件
            for (String path : status.untracked) {
                gitFileStatusMap.put(path, 'U');
            }
            // 冲突的文件
            for (String path : status.conflicting) {
                gitFileStatusMap.put(path, 'C');
            }
        }
        notifyDataSetChanged();
    }
    
    /**
     * 清除 Git 状态
     */
    public void clearGitStatus() {
        this.gitRepoRoot = null;
        this.gitStatus = null;
        this.gitFileStatusMap.clear();
        notifyDataSetChanged();
    }
    
    /**
     * 获取文件的 Git 状态字符
     */
    private Character getGitStatusChar(File file) {
        if (gitRepoRoot == null || gitStatus == null) return null;
        
        String filePath = file.getAbsolutePath();
        String repoPath = gitRepoRoot.getAbsolutePath();
        
        if (!filePath.startsWith(repoPath)) return null;
        
        // 计算相对路径
        String relativePath = filePath.substring(repoPath.length());
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            relativePath = relativePath.substring(1);
        }
        
        // 直接匹配文件
        if (gitFileStatusMap.containsKey(relativePath)) {
            return gitFileStatusMap.get(relativePath);
        }
        
        // 如果是目录，检查是否有子文件有变更
        if (file.isDirectory()) {
            String dirPrefix = relativePath.isEmpty() ? "" : relativePath + "/";
            for (String path : gitFileStatusMap.keySet()) {
                if (path.startsWith(dirPrefix)) {
                    // 目录包含变更文件，返回 M 表示有修改
                    return 'M';
                }
            }
        }
        
        return null;
    }
    
    /**
     * 获取 Git 状态对应的颜色
     */
    private int getGitStatusColor(char status) {
        switch (status) {
            case 'M': return COLOR_GIT_MODIFIED;
            case 'A': return COLOR_GIT_ADDED;
            case 'U': return COLOR_GIT_UNTRACKED;
            case 'D': return COLOR_GIT_DELETED;
            case 'C': return COLOR_GIT_CONFLICT;
            default: return COLOR_GIT_MODIFIED;
        }
    }

    public void setFiles(File[] fileArray, boolean hasParent, File parentDir) {
        files.clear();
        hasParentEntry = hasParent;
        
        // 记录当前路径
        if (fileArray != null && fileArray.length > 0) {
            currentPath = fileArray[0].getParent();
        } else if (parentDir != null) {
            currentPath = parentDir.getAbsolutePath();
        }
        
        if (hasParent && parentDir != null) {
            files.add(parentDir);
        }
        
        if (fileArray != null) {
            List<File> fileList = new ArrayList<>(Arrays.asList(fileArray));
            Collections.sort(fileList, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });
            files.addAll(fileList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        File file = files.get(position);
        boolean isParentEntry = hasParentEntry && position == 0;
        holder.bind(file, isParentEntry);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    class FileViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivIcon;
        private final TextView tvName;
        private final TextView tvInfo;
        private final TextView tvGitStatus;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.file_icon);
            tvName = itemView.findViewById(R.id.file_name);
            tvInfo = itemView.findViewById(R.id.file_info);
            tvGitStatus = itemView.findViewById(R.id.git_status);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onFileClick(files.get(pos));
                }
            });

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    if (hasParentEntry && pos == 0) return false;
                    listener.onFileLongClick(files.get(pos));
                    return true;
                }
                return false;
            });
        }

        void bind(File file, boolean isParentEntry) {
            int textColor = isDarkTheme ? COLOR_TEXT_DARK : COLOR_TEXT_LIGHT;
            int folderColor = isDarkTheme ? COLOR_FOLDER_DARK : COLOR_FOLDER_LIGHT;
            int fileColor = isDarkTheme ? COLOR_FILE_DARK : COLOR_FILE_LIGHT;
            
            if (isParentEntry) {
                tvName.setText("..");
                tvInfo.setVisibility(View.GONE);
                ivIcon.setImageResource(R.drawable.ic_folder);
                ivIcon.setImageTintList(ColorStateList.valueOf(folderColor));
                tvName.setTextColor(textColor);
                tvGitStatus.setVisibility(View.GONE);
                return;
            }
            
            tvName.setText(file.getName());
            tvInfo.setVisibility(View.VISIBLE);
            
            // /storage/emulated/0/ 下不显示权限
            boolean showPermission = currentPath == null || !currentPath.startsWith("/storage/emulated/0");
            
            // 检查是否是头文件目录
            boolean isHeaderDir = file.isDirectory() && headerDirs != null && 
                                  headerDirs.contains(file.getAbsolutePath());
            
            // 获取 Git 状态
            Character gitStatusChar = getGitStatusChar(file);
            
            if (file.isDirectory()) {
                // 文件夹显示修改日期
                Drawable folderIcon = FileIconProvider.getFileIconDrawable(context, file, 22, headerDirs, true);
                if (folderIcon != null) {
                    ivIcon.setImageDrawable(folderIcon);
                    ivIcon.setImageTintList(null); // SVG 图标不着色
                } else {
                    ivIcon.setImageResource(R.drawable.ic_folder);
                    ivIcon.setImageTintList(ColorStateList.valueOf(folderColor));
                }
                
                if (isHeaderDir) {
                    // 头文件目录显示黄色
                    tvName.setTextColor(COLOR_HEADER_DIR);
                } else if (gitStatusChar != null) {
                    // 有 Git 变更的目录
                    int gitColor = getGitStatusColor(gitStatusChar);
                    tvName.setTextColor(gitColor);
                } else {
                    tvName.setTextColor(textColor);
                }
                tvInfo.setText(formatDate(file.lastModified()));
            } else {
                // 文件显示日期和大小
                Drawable fileIcon = FileIconProvider.getFileIconDrawable(context, file, 22, null, false);
                if (fileIcon != null) {
                    ivIcon.setImageDrawable(fileIcon);
                    ivIcon.setImageTintList(null); // SVG 图标不着色
                } else {
                    ivIcon.setImageResource(R.drawable.ic_file);
                    ivIcon.setImageTintList(ColorStateList.valueOf(fileColor));
                }
                
                if (gitStatusChar != null) {
                    // 有 Git 变更的文件
                    int gitColor = getGitStatusColor(gitStatusChar);
                    tvName.setTextColor(gitColor);
                } else {
                    tvName.setTextColor(textColor);
                }
                
                if (showPermission) {
                    tvInfo.setText(getPermissionString(file) + "  " + formatFileSize(file.length()));
                } else {
                    tvInfo.setText(formatDate(file.lastModified()) + "  " + formatFileSize(file.length()));
                }
            }
            
            // 显示 Git 状态标识
            if (gitStatusChar != null) {
                tvGitStatus.setVisibility(View.VISIBLE);
                tvGitStatus.setText(String.valueOf(gitStatusChar));
                tvGitStatus.setTextColor(getGitStatusColor(gitStatusChar));
            } else {
                tvGitStatus.setVisibility(View.GONE);
            }
        }
        
        private String formatDate(long timestamp) {
            SimpleDateFormat sdf = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }
        
        private String formatFileSize(long size) {
            if (size < 1024) {
                return size + "B";
            } else if (size < 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.2fK", size / 1024.0);
            } else if (size < 1024 * 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.2fM", size / (1024.0 * 1024));
            } else {
                return String.format(Locale.getDefault(), "%.2fG", size / (1024.0 * 1024 * 1024));
            }
        }
        
        private String getPermissionString(File file) {
            StringBuilder sb = new StringBuilder();
            sb.append(file.isDirectory() ? "d" : "-");
            sb.append(file.canRead() ? "r" : "-");
            sb.append(file.canWrite() ? "w" : "-");
            sb.append(file.canExecute() ? "x" : "-");
            sb.append("------"); // 简化显示
            return sb.toString();
        }
    }
}
