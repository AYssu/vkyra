package com.vkyra.code.home;

import com.vkyra.code.BuildConfig;
import com.vkyra.code.R;
import com.vkyra.code.editor.EditorActivity;
import com.vkyra.code.files.StorageAccessPolicy;
import com.vkyra.code.git.GitCloneManager;
import com.vkyra.code.git.GitCredentialManager;
import com.vkyra.code.plugin.PluginActivity;
import com.vkyra.code.project.NewProjectActivity;
import com.vkyra.code.project.RecentProjectManager;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.settings.SettingsActivity;
import com.vkyra.code.terminal.TerminalActivity;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.ui.ExpandableFabMenu;
import com.vkyra.code.update.UpdateDialogHelper;
import com.vkyra.code.update.UpdateUi;
import com.vkyra.code.update.VersionChecker;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.io.OutputStream;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainActivity extends AppCompatActivity {

    private static final int STORAGE_PERMISSION_REQUEST_CODE = 1002;
    private static final int MANAGE_STORAGE_PERMISSION_REQUEST_CODE = 1003;
    private static final int REQUEST_CODE_IMPORT_PROJECT = 1004;
    private static final Logger log = LoggerFactory.getLogger(MainActivity.class);

    // 初始化状态标志
    private boolean isClangInstalled = false;
    
    // UI元素引用
    private MaterialCardView editorModeCard;
    private MaterialCardView terminalModeCard;
    private MaterialCardView pluginCard;
    private ExpandableFabMenu fabMenu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置状态栏颜色为深色背景（必须在 setContentView 之前或之后立即设置）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
        }

        // 检查并请求存储权限
        if (!checkStoragePermission()) {
            setContentView(R.layout.activity_main);
            requestStoragePermission();
            return;
        }

        // 显示主页
        setContentView(R.layout.activity_main);

        // 初始化应用
        initializeApp();
    }

    private void initializeApp() {
        // 将assets目录下的文件夹拷贝到软件根目录
        copyAssetsToRoot();
        
        // 更新版本信息
        updateVersionInfo();
        
        // 检查并安装 Clang 工具链
        checkAndInstallClang();
        
        // 检查版本更新
        checkVersionUpdate();

        // 设置工具栏
        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        toolbar.setOverflowIcon(getDrawable(R.drawable.ic_settings));
        // 设置菜单图标颜色为浅色
        toolbar.post(() -> {
            for (int i = 0; i < toolbar.getMenu().size(); i++) {
                MenuItem item = toolbar.getMenu().getItem(i);
                if (item.getIcon() != null) {
                    item.getIcon().setTint(0xFFCCCCCC);
                }
            }
        });
        setSupportActionBar(toolbar);

        // 获取UI元素引用
        editorModeCard = findViewById(R.id.app_mode_card);
        terminalModeCard = findViewById(R.id.floating_mode_card);
        pluginCard = findViewById(R.id.plugin_card);
        fabMenu = findViewById(R.id.fab_menu);
        
        // 初始化时禁用所有操作按钮
        setUIEnabled(false);

        // C++编辑器卡片
        editorModeCard.setOnClickListener(v -> {
            if (!isClangInstalled) {
                Toast.makeText(this, "请等待 Clang 工具链安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, EditorActivity.class);
            startActivity(intent);
        });

        // 终端模式卡片
        terminalModeCard.setOnClickListener(v -> {
            if (!isClangInstalled) {
                Toast.makeText(this, "请等待 Clang 工具链安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, TerminalActivity.class);
            startActivity(intent);
        });

        // 插件卡片
        pluginCard.setOnClickListener(v -> {
            if (!isClangInstalled) {
                Toast.makeText(this, "请等待 Clang 工具链安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, PluginActivity.class);
            startActivity(intent);
        });

        // 初始化最近项目快捷入口
        setupRecentProjectShortcut();

        // 初始化FAB菜单
        setupFabMenu();

        // 启动进入动画
        startEnterAnimation();
    }
    
    private void setupFabMenu() {
        fabMenu.setMainFabIcon(android.R.drawable.ic_input_add);
        
        // 添加菜单项
        fabMenu.addMenuItem(R.drawable.ic_new_project, "新建项目");
        fabMenu.addMenuItem(R.drawable.ic_recent, "最近项目");
        fabMenu.addMenuItem(R.drawable.ic_git_pull, "拉取项目");
        fabMenu.addMenuItem(R.drawable.ic_folder_open, "导入项目");
        
        fabMenu.buildMenu();
        
        fabMenu.setOnMenuItemClickListener((index, label) -> {
            if (!isClangInstalled) {
                Toast.makeText(this, "请等待 Clang 工具链安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            
            switch (index) {
                case 0: // 新建项目
                    showNewProjectDialog();
                    break;
                case 1: // 最近项目
                    showRecentProjectsDialog();
                    break;
                case 2: // 拉取项目
                    showCloneProjectDialog();
                    break;
                case 3: // 导入项目
                    showImportProjectDialog();
                    break;
            }
        });
    }
    
    private void setupRecentProjectShortcut() {
        MaterialCardView shortcutCard = findViewById(R.id.recent_project_shortcut_card);
        TextView nameText = findViewById(R.id.recent_project_name);
        TextView initialsText = findViewById(R.id.recent_project_initials);
        com.google.android.material.card.MaterialCardView iconCard = findViewById(R.id.recent_project_icon_card);
        
        if (shortcutCard == null) return;
        
        // 获取最近项目列表
        List<RecentProjectManager.ProjectInfo> projects = RecentProjectManager.getRecentProjects(this);
        
        if (projects.isEmpty()) {
            shortcutCard.setVisibility(View.GONE);
            return;
        }
        
        // 显示最近打开的项目
        RecentProjectManager.ProjectInfo recentProject = projects.get(0);
        shortcutCard.setVisibility(View.VISIBLE);
        nameText.setText(recentProject.name);
        initialsText.setText(recentProject.getInitials());
        iconCard.setCardBackgroundColor(recentProject.getColor());
        
        shortcutCard.setOnClickListener(v -> {
            if (!isClangInstalled) {
                Toast.makeText(this, "请等待 Clang 工具链安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            // 更新打开时间
            RecentProjectManager.updateLastOpenTime(recentProject.path);
            // 打开项目
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra("project_path", recentProject.path);
            startActivity(intent);
        });
        
        // 添加长按删除功能
        shortcutCard.setOnLongClickListener(v -> {
            showDeleteProjectDialog(recentProject, () -> {
                // 删除成功后刷新快捷方式
                setupRecentProjectShortcut();
            });
            return true; // 消费长按事件
        });
    }
    
    private void showNewProjectDialog() {
        Intent intent = new Intent(this, NewProjectActivity.class);
        startActivity(intent);
    }
    
    private void showRecentProjectsDialog() {
        List<RecentProjectManager.ProjectInfo> projects = RecentProjectManager.getRecentProjects(this);
        
        if (projects.isEmpty()) {
            new MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle("最近项目")
                .setMessage("暂无 Vkyra 项目\n\n在项目根目录下创建的项目会显示在这里")
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        
        // 创建列表视图
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        // 先创建对话框引用
        final androidx.appcompat.app.AlertDialog[] dialogHolder = new androidx.appcompat.app.AlertDialog[1];
        
        // 创建适配器 - 先声明为final数组以便在内部类中使用
        final RecentProjectAdapter[] adapterHolder = new RecentProjectAdapter[1];
        
        // 创建适配器
        RecentProjectAdapter adapter = new RecentProjectAdapter(projects, new RecentProjectAdapter.OnProjectClickListener() {
            @Override
            public void onClick(RecentProjectManager.ProjectInfo project) {
                // 关闭对话框
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                // 更新打开时间
                RecentProjectManager.updateLastOpenTime(project.path);
                // 打开项目
                Intent intent = new Intent(MainActivity.this, EditorActivity.class);
                intent.putExtra("project_path", project.path);
                startActivity(intent);
            }
            
            @Override
            public void onLongClick(RecentProjectManager.ProjectInfo project) {
                // 显示删除确认对话框
                showDeleteProjectDialog(project, () -> {
                    // 删除成功后刷新列表
                    projects.remove(project);
                    if (adapterHolder[0] != null) {
                        adapterHolder[0].notifyDataSetChanged();
                    }
                    
                    // 如果列表为空，关闭对话框
                    if (projects.isEmpty()) {
                        if (dialogHolder[0] != null) {
                            dialogHolder[0].dismiss();
                        }
                    }
                });
            }
        });
        
        // 保存适配器引用
        adapterHolder[0] = adapter;
        recyclerView.setAdapter(adapter);
        
        dialogHolder[0] = new MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle("最近项目")
            .setView(recyclerView)
            .setNegativeButton("关闭", null)
            .create();
        dialogHolder[0].show();
    }
    
    private void showDeleteProjectDialog(RecentProjectManager.ProjectInfo project, Runnable onDeleteSuccess) {
        if (project == null) {
            Toast.makeText(this, "项目信息为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(24, 16, 24, 8);
            
            // 项目信息
            TextView projectInfo = new TextView(this);
            String projectName = project.name != null ? project.name : "未知项目";
            String projectPath = project.path != null ? project.path : "未知路径";
            projectInfo.setText(projectName + "\n" + projectPath);
            projectInfo.setTextColor(0xFFE6E0E9);
            projectInfo.setTextSize(14);
            projectInfo.setPadding(0, 0, 0, 16);
            layout.addView(projectInfo);
            
            // 删除选项
            android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(this);
            radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
            
            android.widget.RadioButton configOnly = new android.widget.RadioButton(this);
            configOnly.setText("仅删除 Vkyra 配置");
            configOnly.setTextColor(0xFFE6E0E9);
            configOnly.setChecked(true);
            configOnly.setId(View.generateViewId());
            
            android.widget.RadioButton deleteAll = new android.widget.RadioButton(this);
            deleteAll.setText("删除整个项目文件夹");
            deleteAll.setTextColor(0xFFE6E0E9);
            deleteAll.setId(View.generateViewId());
            
            radioGroup.addView(configOnly);
            radioGroup.addView(deleteAll);
            layout.addView(radioGroup);
            
            // 确认输入
            TextView confirmLabel = new TextView(this);
            confirmLabel.setText("输入项目名称确认:");
            confirmLabel.setTextColor(0xFFE6E0E9);
            confirmLabel.setPadding(0, 16, 0, 8);
            layout.addView(confirmLabel);
            
            com.google.android.material.textfield.TextInputLayout inputLayout = 
                new com.google.android.material.textfield.TextInputLayout(this, null,
                    com.google.android.material.R.attr.textInputOutlinedStyle);
            inputLayout.setHint(projectName);
            
            com.google.android.material.textfield.TextInputEditText inputEdit = 
                new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
            
            // 禁用粘贴
            inputEdit.setLongClickable(false);
            inputEdit.setTextIsSelectable(false);
            inputEdit.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                    return false;
                }
                @Override
                public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                    return false;
                }
                @Override
                public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                    return false;
                }
                @Override
                public void onDestroyActionMode(android.view.ActionMode mode) {
                }
            });
            
            inputLayout.addView(inputEdit);
            layout.addView(inputLayout);
            
            // 创建对话框
            com.google.android.material.dialog.MaterialAlertDialogBuilder dialogBuilder = 
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("删除项目")
                .setView(layout)
                .setPositiveButton("删除", null)
                .setNegativeButton("取消", null);
            
            androidx.appcompat.app.AlertDialog dialog = dialogBuilder.create();
            dialog.show();
            
            // 删除按钮点击事件
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String inputName = inputEdit.getText().toString().trim();
                    
                    if (inputName.equals(projectName)) {
                        int selectedId = radioGroup.getCheckedRadioButtonId();
                        if (selectedId == deleteAll.getId()) {
                            // 删除整个项目
                            RecentProjectManager.removeEntireProject(this, projectPath);
                            Toast.makeText(this, "项目已删除", Toast.LENGTH_SHORT).show();
                        } else {
                            // 只删除配置
                            RecentProjectManager.removeProjectConfig(this, projectPath);
                            Toast.makeText(this, "配置已删除", Toast.LENGTH_SHORT).show();
                        }
                        
                        dialog.dismiss();
                        if (onDeleteSuccess != null) {
                            onDeleteSuccess.run();
                        }
                    } else if (inputName.isEmpty()) {
                        inputLayout.setError("请输入项目名称");
                    } else {
                        inputLayout.setError("名称不匹配");
                        inputEdit.setText("");
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    android.util.Log.e("MainActivity", "删除项目时出错", e);
                }
            });
            
            // 清除错误
            inputEdit.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (inputLayout != null) {
                        inputLayout.setError(null);
                    }
                }
                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
            
        } catch (Exception e) {
            Toast.makeText(this, "创建删除对话框失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            android.util.Log.e("MainActivity", "创建删除对话框时出错", e);
        }
    }
    
    // 最近项目适配器
    private static class RecentProjectAdapter extends RecyclerView.Adapter<RecentProjectAdapter.ViewHolder> {
        private final List<RecentProjectManager.ProjectInfo> projects;
        private final OnProjectClickListener listener;
        
        interface OnProjectClickListener {
            void onClick(RecentProjectManager.ProjectInfo project);
            void onLongClick(RecentProjectManager.ProjectInfo project);
        }
        
        RecentProjectAdapter(List<RecentProjectManager.ProjectInfo> projects, OnProjectClickListener listener) {
            this.projects = projects;
            this.listener = listener;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_project, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RecentProjectManager.ProjectInfo project = projects.get(position);
            
            holder.tvName.setText(project.name);
            holder.tvPath.setText(project.path);
            holder.tvInitials.setText(project.getInitials());
            holder.iconCard.setCardBackgroundColor(project.getColor());
            
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClick(project);
                }
            });
            
            holder.itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onLongClick(project);
                }
                return true; // 消费长按事件
            });
        }
        
        @Override
        public int getItemCount() {
            return projects.size();
        }
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvPath, tvInitials;
            com.google.android.material.card.MaterialCardView iconCard;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_project_name);
                tvPath = itemView.findViewById(R.id.tv_project_path);
                tvInitials = itemView.findViewById(R.id.tv_initials);
                iconCard = itemView.findViewById(R.id.icon_card);
            }
        }
    }
    
    private void showCloneProjectDialog() {
        // 创建对话框布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);
        
        // 定义颜色
        int hintColor = 0xFFB0B0B0;  // 更亮的 hint 颜色
        int textColor = 0xFFE6E1E5;  // 输入文字颜色
        int boxStrokeColor = 0xFF6B6B6B;  // 边框颜色
        int focusedColor = 0xFFD0BCFF;  // 聚焦时的颜色（紫色）
        
        // 仓库 URL 输入
        com.google.android.material.textfield.TextInputLayout urlLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        urlLayout.setHint("仓库 URL");
        urlLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        urlLayout.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        urlLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(focusedColor));
        urlLayout.setBoxStrokeColor(focusedColor);
        urlLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        
        com.google.android.material.textfield.TextInputEditText urlInput = 
            new com.google.android.material.textfield.TextInputEditText(urlLayout.getContext());
        urlInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextColor(textColor);
        urlLayout.addView(urlInput);
        layout.addView(urlLayout);
        
        // 项目名称输入
        com.google.android.material.textfield.TextInputLayout nameLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("项目名称（可选，默认从 URL 提取）");
        nameLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameLayout.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        nameLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(focusedColor));
        nameLayout.setBoxStrokeColor(focusedColor);
        nameLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = 24;
        nameLayout.setLayoutParams(nameParams);
        
        com.google.android.material.textfield.TextInputEditText nameInput = 
            new com.google.android.material.textfield.TextInputEditText(nameLayout.getContext());
        nameInput.setTextColor(textColor);
        nameLayout.addView(nameInput);
        layout.addView(nameLayout);
        
        // 凭据选择
        List<GitCredentialManager.Credential> savedCredentials = GitCredentialManager.getCredentials(this);
        
        // 用户名输入
        com.google.android.material.textfield.TextInputLayout userLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        userLayout.setHint("用户名（私有仓库需要）");
        userLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        userLayout.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        userLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(focusedColor));
        userLayout.setBoxStrokeColor(focusedColor);
        userLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        LinearLayout.LayoutParams userParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        userParams.topMargin = 24;
        userLayout.setLayoutParams(userParams);
        
        com.google.android.material.textfield.TextInputEditText userInput = 
            new com.google.android.material.textfield.TextInputEditText(userLayout.getContext());
        userInput.setTextColor(textColor);
        userLayout.addView(userInput);
        layout.addView(userLayout);
        
        // 密码/Token 输入
        com.google.android.material.textfield.TextInputLayout passLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        passLayout.setHint("密码/Token（私有仓库需要）");
        passLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        passLayout.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        passLayout.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        passLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(focusedColor));
        passLayout.setBoxStrokeColor(focusedColor);
        passLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        passLayout.setEndIconTintList(android.content.res.ColorStateList.valueOf(hintColor));
        LinearLayout.LayoutParams passParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        passParams.topMargin = 24;
        passLayout.setLayoutParams(passParams);
        
        com.google.android.material.textfield.TextInputEditText passInput = 
            new com.google.android.material.textfield.TextInputEditText(passLayout.getContext());
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setTextColor(textColor);
        passLayout.addView(passInput);
        layout.addView(passLayout);
        
        // 保存凭据复选框
        com.google.android.material.checkbox.MaterialCheckBox saveCredentialCheck = 
            new com.google.android.material.checkbox.MaterialCheckBox(this);
        saveCredentialCheck.setText("保存凭据（加密存储）");
        saveCredentialCheck.setTextColor(0xFFE6E1E5);
        saveCredentialCheck.setChecked(true);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        checkParams.topMargin = 16;
        saveCredentialCheck.setLayoutParams(checkParams);
        layout.addView(saveCredentialCheck);
        
        // URL 输入变化时自动填充已保存的凭据
        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String url = s.toString().trim();
                if (!url.isEmpty()) {
                    GitCredentialManager.Credential credential = 
                        GitCredentialManager.findCredentialForUrl(MainActivity.this, url);
                    if (credential != null) {
                        userInput.setText(credential.username);
                        passInput.setText(credential.password);
                    } else {
                        userInput.setText("");
                        passInput.setText("");
                    }
                } else {
                    userInput.setText("");
                    passInput.setText("");
                }
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle("拉取项目")
            .setView(layout)
            .setPositiveButton("克隆", (dialog, which) -> {
                String url = urlInput.getText().toString().trim();
                String name = nameInput.getText().toString().trim();
                String username = userInput.getText().toString().trim();
                String password = passInput.getText().toString();
                
                if (url.isEmpty()) {
                    Toast.makeText(this, "请输入仓库 URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 如果没有输入项目名，从 URL 提取
                if (name.isEmpty()) {
                    name = GitCloneManager.extractRepoName(url);
                }
                
                if (name.isEmpty()) {
                    Toast.makeText(this, "无法从 URL 提取项目名称，请手动输入", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 保存凭据
                if (saveCredentialCheck.isChecked() && !username.isEmpty() && !password.isEmpty()) {
                    String host = GitCredentialManager.extractOrigin(url);
                    GitCredentialManager.saveCredential(this, 
                        new GitCredentialManager.Credential(host + " - " + username, host, username, password));
                }
                
                startClone(url, name, username, password);
            })
            .setNegativeButton("取消", null);
        
        // 如果有保存的凭据，添加管理按钮
        if (!savedCredentials.isEmpty()) {
            dialogBuilder.setNeutralButton("管理凭据", (dialog, which) -> {
                showCredentialManagerDialog();
            });
        }
        
        dialogBuilder.show();
    }
    
    private void showCredentialManagerDialog() {
        List<GitCredentialManager.Credential> credentials = GitCredentialManager.getCredentials(this);
        
        if (credentials.isEmpty()) {
            Toast.makeText(this, "暂无保存的凭据", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] items = new String[credentials.size()];
        for (int i = 0; i < credentials.size(); i++) {
            GitCredentialManager.Credential c = credentials.get(i);
            items[i] = c.name + "\n" + c.username;
        }
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("管理 Git 凭据")
            .setItems(items, (dialog, which) -> {
                GitCredentialManager.Credential selected = credentials.get(which);
                new MaterialAlertDialogBuilder(this)
                    .setTitle("凭据操作")
                    .setMessage("主机: " + selected.host + "\n用户名: " + selected.username)
                    .setPositiveButton("删除", (d, w) -> {
                        GitCredentialManager.deleteCredential(this, selected.host, selected.username);
                        Toast.makeText(this, "凭据已删除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            })
            .setNeutralButton("清空全部", (dialog, which) -> {
                new MaterialAlertDialogBuilder(this)
                    .setTitle("确认清空")
                    .setMessage("确定要删除所有保存的 Git 凭据吗？")
                    .setPositiveButton("确定", (d, w) -> {
                        GitCredentialManager.clearAll(this);
                        Toast.makeText(this, "所有凭据已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            })
            .setNegativeButton("关闭", null)
            .show();
    }
    
    private void showImportProjectDialog() {
        // 直接显示手动输入对话框，避免URI复杂性
        showManualImportDialog();
    }
    
    private void showManualImportDialog() {
        // 创建对话框布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);
        
        // 定义颜色
        int hintColor = 0xFFB0B0B0;
        int textColor = 0xFFE6E1E5;
        int boxStrokeColor = 0xFF6B6B6B;
        int focusedColor = 0xFFD0BCFF;
        
        // 添加标题说明
        TextView titleText = new TextView(this);
        titleText.setText("选择要导入的项目文件夹");
        titleText.setTextColor(0xFFE6E1E5);
        titleText.setTextSize(16);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = 24;
        titleText.setLayoutParams(titleParams);
        layout.addView(titleText);
        
        // 项目路径输入
        com.google.android.material.textfield.TextInputLayout pathLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        pathLayout.setHint("项目文件夹路径");
        pathLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        pathLayout.setBoxStrokeColor(boxStrokeColor);
        pathLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        pathLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        pathLayout.setHelperText("输入完整的文件夹路径，如：/storage/emulated/0/MyProject");
        pathLayout.setHelperTextColor(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
        
        com.google.android.material.textfield.TextInputEditText pathEdit = 
            new com.google.android.material.textfield.TextInputEditText(pathLayout.getContext());
        pathEdit.setTextColor(textColor);
        pathEdit.setText("/storage/emulated/0/");
        pathEdit.setSelection(pathEdit.getText().length()); // 光标移到末尾
        pathLayout.addView(pathEdit);
        layout.addView(pathLayout);
        
        // 浏览按钮容器
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.topMargin = 16;
        containerParams.bottomMargin = 8;
        buttonContainer.setLayoutParams(containerParams);
        
        // 添加浏览按钮
        com.google.android.material.button.MaterialButton browseButton = 
            new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        browseButton.setText("📁 浏览文件夹");
        browseButton.setTextColor(focusedColor);
        browseButton.setStrokeColor(android.content.res.ColorStateList.valueOf(focusedColor));
        browseButton.setStrokeWidth(2);
        browseButton.setCornerRadius(12);
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        browseButton.setLayoutParams(browseParams);
        
        // 创建对话框引用，以便在按钮点击时关闭
        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
        
        browseButton.setOnClickListener(v -> {
            // 关闭当前对话框
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            
            // 启动文件选择器
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            
            try {
                startActivityForResult(intent, REQUEST_CODE_IMPORT_PROJECT);
            } catch (Exception e) {
                Toast.makeText(this, "系统不支持文件选择器", Toast.LENGTH_SHORT).show();
                // 如果文件选择器启动失败，重新显示对话框
                showManualImportDialog();
            }
        });
        buttonContainer.addView(browseButton);
        layout.addView(buttonContainer);
        
        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(0xFF424242);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = 16;
        dividerParams.bottomMargin = 16;
        divider.setLayoutParams(dividerParams);
        layout.addView(divider);
        
        // 项目名称输入
        com.google.android.material.textfield.TextInputLayout nameLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("项目名称（可选）");
        nameLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameLayout.setBoxStrokeColor(boxStrokeColor);
        nameLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        nameLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        nameLayout.setHelperText("留空将使用文件夹名称作为项目名");
        nameLayout.setHelperTextColor(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
        
        com.google.android.material.textfield.TextInputEditText nameEdit = 
            new com.google.android.material.textfield.TextInputEditText(nameLayout.getContext());
        nameEdit.setTextColor(textColor);
        nameLayout.addView(nameEdit);
        layout.addView(nameLayout);
        
        // 创建对话框
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("导入项目")
            .setView(layout)
            .setPositiveButton("导入", (dialog, which) -> {
                String projectPath = pathEdit.getText().toString().trim();
                String projectName = nameEdit.getText().toString().trim();
                
                if (projectPath.isEmpty()) {
                    Toast.makeText(this, "请输入项目路径", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                importProject(projectPath, projectName);
            })
            .setNegativeButton("取消", null);
        
        androidx.appcompat.app.AlertDialog dialog = dialogBuilder.create();
        dialogRef[0] = dialog; // 保存对话框引用
        dialog.show();
    }
    
    private void showManualImportDialogWithPath(String suggestedPath) {
        // 创建对话框布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);
        
        // 定义颜色
        int hintColor = 0xFFB0B0B0;
        int textColor = 0xFFE6E1E5;
        int boxStrokeColor = 0xFF6B6B6B;
        int focusedColor = 0xFFD0BCFF;
        
        // 添加标题说明
        TextView titleText = new TextView(this);
        titleText.setText("确认项目路径");
        titleText.setTextColor(0xFFE6E1E5);
        titleText.setTextSize(16);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = 16;
        titleText.setLayoutParams(titleParams);
        layout.addView(titleText);
        
        // 添加说明文字
        TextView infoText = new TextView(this);
        infoText.setText("系统已解析出项目路径，请确认或修改：");
        infoText.setTextColor(0xFF9E9E9E);
        infoText.setTextSize(14);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.bottomMargin = 24;
        infoText.setLayoutParams(infoParams);
        layout.addView(infoText);
        
        // 项目路径输入
        com.google.android.material.textfield.TextInputLayout pathLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        pathLayout.setHint("项目文件夹路径");
        pathLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        pathLayout.setBoxStrokeColor(boxStrokeColor);
        pathLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        pathLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        pathLayout.setHelperText("可以修改路径或重新选择文件夹");
        pathLayout.setHelperTextColor(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
        
        com.google.android.material.textfield.TextInputEditText pathEdit = 
            new com.google.android.material.textfield.TextInputEditText(pathLayout.getContext());
        pathEdit.setTextColor(textColor);
        // 使用建议的路径，如果没有则使用默认路径
        String pathToShow = suggestedPath != null ? suggestedPath : "/storage/emulated/0/";
        pathEdit.setText(pathToShow);
        pathEdit.setSelection(pathEdit.getText().length()); // 光标移到末尾
        pathLayout.addView(pathEdit);
        layout.addView(pathLayout);
        
        // 浏览按钮容器
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.topMargin = 16;
        containerParams.bottomMargin = 8;
        buttonContainer.setLayoutParams(containerParams);
        
        // 添加重新浏览按钮
        com.google.android.material.button.MaterialButton browseButton = 
            new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        browseButton.setText("🔄 重新选择");
        browseButton.setTextColor(focusedColor);
        browseButton.setStrokeColor(android.content.res.ColorStateList.valueOf(focusedColor));
        browseButton.setStrokeWidth(2);
        browseButton.setCornerRadius(12);
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        browseButton.setLayoutParams(browseParams);
        
        // 创建对话框引用，以便在按钮点击时关闭
        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
        
        browseButton.setOnClickListener(v -> {
            // 关闭当前对话框
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            
            // 启动文件选择器
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            
            try {
                startActivityForResult(intent, REQUEST_CODE_IMPORT_PROJECT);
            } catch (Exception e) {
                Toast.makeText(this, "系统不支持文件选择器", Toast.LENGTH_SHORT).show();
                // 如果文件选择器启动失败，重新显示对话框
                showManualImportDialogWithPath(suggestedPath);
            }
        });
        buttonContainer.addView(browseButton);
        layout.addView(buttonContainer);
        
        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(0xFF424242);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = 16;
        dividerParams.bottomMargin = 16;
        divider.setLayoutParams(dividerParams);
        layout.addView(divider);
        
        // 项目名称输入
        com.google.android.material.textfield.TextInputLayout nameLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("项目名称（可选）");
        nameLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameLayout.setBoxStrokeColor(boxStrokeColor);
        nameLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        nameLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));
        nameLayout.setHelperText("留空将使用文件夹名称作为项目名");
        nameLayout.setHelperTextColor(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
        
        com.google.android.material.textfield.TextInputEditText nameEdit = 
            new com.google.android.material.textfield.TextInputEditText(nameLayout.getContext());
        nameEdit.setTextColor(textColor);
        nameLayout.addView(nameEdit);
        layout.addView(nameLayout);
        
        // 创建对话框
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("导入项目")
            .setView(layout)
            .setPositiveButton("导入", (dialog, which) -> {
                String projectPath = pathEdit.getText().toString().trim();
                String projectName = nameEdit.getText().toString().trim();
                
                if (projectPath.isEmpty()) {
                    Toast.makeText(this, "请输入项目路径", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                importProject(projectPath, projectName);
            })
            .setNegativeButton("取消", null);
        
        androidx.appcompat.app.AlertDialog dialog = dialogBuilder.create();
        dialogRef[0] = dialog; // 保存对话框引用
        dialog.show();
    }
    
    private void importProject(String projectPath, String customName) {
        File projectDir = new File(projectPath);
        
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            Toast.makeText(this, "项目路径不存在或不是文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 确定项目名称
        String projectName = customName.isEmpty() ? projectDir.getName() : customName;
        
        try {
            // 检查是否已经是Akode项目
            if (RecentProjectManager.isAkodeProject(projectDir)) {
                // 已经是Akode项目，直接添加到最近项目
                RecentProjectManager.updateLastOpenTime(projectPath);
                // 添加到全局项目列表
                RecentProjectManager.addToGlobalProjects(this, projectPath);
                Toast.makeText(this, "项目导入成功", Toast.LENGTH_SHORT).show();
            } else {
                // 初始化为Akode项目
                RecentProjectManager.initProject(projectPath, projectName, "imported", null);
                
                // 创建third.json配置文件
                createThirdJsonConfig(projectDir);
                
                // 添加到全局项目列表
                RecentProjectManager.addToGlobalProjects(this, projectPath);
                
                Toast.makeText(this, "项目导入并初始化成功", Toast.LENGTH_SHORT).show();
            }
            
            // 打开项目
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra("project_path", projectPath);
            startActivity(intent);
            
        } catch (Exception e) {
            Toast.makeText(this, "导入项目失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void createThirdJsonConfig(File projectDir) {
        try {
            File thirdJsonFile = new File(projectDir, "third.json");
            if (!thirdJsonFile.exists()) {
                // 创建默认的third.json配置
                org.json.JSONObject config = new org.json.JSONObject();
                
                // 扩展目录配置
                org.json.JSONArray extensions = new org.json.JSONArray();
                extensions.put("src");
                extensions.put("include");
                extensions.put("lib");
                extensions.put("libs");
                extensions.put("external");
                extensions.put("third_party");
                extensions.put("vendor");
                config.put("extensionDirs", extensions);
                
                // 包含文件类型
                org.json.JSONArray includeTypes = new org.json.JSONArray();
                includeTypes.put("*.h");
                includeTypes.put("*.hpp");
                includeTypes.put("*.hxx");
                includeTypes.put("*.c");
                includeTypes.put("*.cpp");
                includeTypes.put("*.cxx");
                includeTypes.put("*.cc");
                includeTypes.put("*.java");
                includeTypes.put("*.kt");
                includeTypes.put("*.js");
                includeTypes.put("*.ts");
                includeTypes.put("*.py");
                config.put("includeTypes", includeTypes);
                
                // 排除目录
                org.json.JSONArray excludeDirs = new org.json.JSONArray();
                excludeDirs.put("build");
                excludeDirs.put("bin");
                excludeDirs.put("obj");
                excludeDirs.put("out");
                excludeDirs.put(".git");
                excludeDirs.put(".svn");
                excludeDirs.put("node_modules");
                excludeDirs.put("__pycache__");
                config.put("excludeDirs", excludeDirs);
                
                // 项目配置
                org.json.JSONObject projectConfig = new org.json.JSONObject();
                projectConfig.put("name", projectDir.getName());
                projectConfig.put("type", "imported");
                projectConfig.put("version", "1.0.0");
                projectConfig.put("description", "Imported project");
                config.put("project", projectConfig);
                
                // 写入文件
                java.io.FileWriter writer = new java.io.FileWriter(thirdJsonFile);
                writer.write(config.toString(2));
                writer.close();
            }
        } catch (Exception e) {
            // 忽略创建third.json的错误，不影响项目导入
        }
    }
    
    private GitCloneManager.CloneTask activeClone;

    @Override protected void onDestroy() {
        if (activeClone != null) activeClone.cancel();
        super.onDestroy();
    }

    private void startClone(String url, String projectName, String username, String password) {
        if (activeClone != null) activeClone.cancel();
        // 创建进度对话框
        LinearLayout progressLayout = new LinearLayout(this);
        progressLayout.setOrientation(LinearLayout.VERTICAL);
        progressLayout.setPadding(48, 32, 48, 32);
        
        com.google.android.material.progressindicator.LinearProgressIndicator progressBar = 
            new com.google.android.material.progressindicator.LinearProgressIndicator(this);
        progressBar.setIndeterminate(true);
        progressBar.setIndicatorColor(0xFFD0BCFF);
        progressBar.setTrackColor(0xFF49454F);
        progressLayout.addView(progressBar);
        
        TextView progressText = new TextView(this);
        progressText.setText("正在克隆...");
        progressText.setTextColor(0xFFE6E1E5);
        progressText.setTextSize(14);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = 16;
        progressText.setLayoutParams(textParams);
        progressLayout.addView(progressText);
        
        androidx.appcompat.app.AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
            .setTitle("拉取项目")
            .setNegativeButton("取消", (d, w) -> { if (activeClone != null) activeClone.cancel(); })
            .setView(progressLayout)
            .setCancelable(false)
            .create();
        progressDialog.show();
        
        activeClone = GitCloneManager.cloneAsync(this, url, projectName, username, password, 
            new GitCloneManager.CloneCallback() {
                @Override
                public void onProgress(String task, int completed, int total) {
                    runOnUiThread(() -> {
                        if (total > 0) {
                            progressBar.setIndeterminate(false);
                            progressBar.setMax(total);
                            progressBar.setProgress(completed);
                            progressText.setText(task + " (" + completed + "/" + total + ")");
                        } else {
                            progressBar.setIndeterminate(true);
                            progressText.setText(task);
                        }
                    });
                }
                
                @Override
                public void onSuccess(String projectPath) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle("克隆成功")
                            .setMessage("项目已克隆到:\n" + projectPath)
                            .setPositiveButton("打开项目", (d, w) -> {
                                Intent intent = new Intent(MainActivity.this, EditorActivity.class);
                                intent.putExtra("project_path", projectPath);
                                startActivity(intent);
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                    });
                }
                
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle("克隆失败")
                            .setMessage(error)
                            .setPositiveButton("确定", null)
                            .show();
                    });
                }
            });
    }
    
    private void checkVersionUpdate() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        if (!VersionChecker.isConfigured() || !BuildConfig.UPDATE_REPOSITORY.equals(prefs.getString("update_repository", ""))) {
            prefs.edit().clear().apply();
            return;
        }
        boolean hasNewVersion = prefs.getBoolean("has_new_version", false);
        if (hasNewVersion) {
            String newVersion = prefs.getString("new_version", "");
            String releaseBody = prefs.getString("release_body", "");
            String downloadUrl = prefs.getString("download_url", "");
            
            showUpdateDialog(newVersion, releaseBody, downloadUrl);
            
            // 清除标志，避免重复显示
            prefs.edit().putBoolean("has_new_version", false).apply();
        }
    }
    
    private void showUpdateDialog(String newVersion, String releaseBody, String downloadUrl) {
        UpdateDialogHelper.showUpdateDialog(this, newVersion, releaseBody, downloadUrl, 
            () -> downloadAndInstallApk(downloadUrl));
    }
    
    private void downloadAndInstallApk(String downloadUrl) {
        UpdateUi.download(this, this, downloadUrl);
    }

    private void updateVersionInfo() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionName = packageInfo.versionName;
            TextView tvVersion = findViewById(R.id.tv_version);
            if (tvVersion != null) {
                tvVersion.setText(getString(R.string.app_name) + " v" + versionName + " · " + getString(R.string.app_tagline));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("MainActivity", "Error getting version info", e);
        }
    }
    
    private void checkAndInstallClang() {
        View gccStatusIndicator = findViewById(R.id.gcc_status_indicator);
        TextView gccStatusText = findViewById(R.id.gcc_status_text);
        
        if (!ClangInstaller.isInstalled(this)) {
            // 更新状态为待安装
            isClangInstalled = false;
            setUIEnabled(false);
            if (gccStatusIndicator != null) {
                gccStatusIndicator.setBackgroundResource(R.drawable.status_dot_yellow);
            }
            if (gccStatusText != null) {
                gccStatusText.setText("待安装");
                gccStatusText.setTextColor(0xFFDCDCAA);
            }

            // 复用统一安装弹窗流程（确认 -> 进度 -> 完成）
            ClangInstaller.showInstallDialog(this, () -> {
                runOnUiThread(() -> checkClangVersion(gccStatusIndicator, gccStatusText));
            });
        } else {
            // 已安装，检查版本是否匹配
            checkClangVersion(gccStatusIndicator, gccStatusText);
        }
    }
    
    private void checkClangVersion(View gccStatusIndicator, TextView gccStatusText) {
        // 先显示检查中状态
        if (gccStatusIndicator != null) {
            gccStatusIndicator.setBackgroundResource(R.drawable.status_dot_yellow);
        }
        if (gccStatusText != null) {
            gccStatusText.setText("检查版本...");
            gccStatusText.setTextColor(0xFFDCDCAA);
        }
        
        // 在后台线程检查版本
        new Thread(() -> {
            String expectedVersion = ClangInstaller.getExpectedVersion(this);
            String installedVersion = ClangInstaller.getInstalledVersion(this);
            
            Log.d("MainActivity", "Expected Clang version: " + expectedVersion);
            Log.d("MainActivity", "Installed Clang version: " + installedVersion);
            
            runOnUiThread(() -> {
                if (expectedVersion != null && installedVersion != null && expectedVersion.equals(installedVersion)) {
                    // 版本匹配
                    isClangInstalled = true;
                    setUIEnabled(true);
                    
                    if (gccStatusIndicator != null) {
                        gccStatusIndicator.setBackgroundResource(R.drawable.status_dot_green);
                    }
                    if (gccStatusText != null) {
                        gccStatusText.setText("已就绪");
                        gccStatusText.setTextColor(0xFF4EC9B0);
                    }
                } else {
                    // 版本不匹配，提示重新安装
                    isClangInstalled = false;
                    setUIEnabled(false);
                    
                    if (gccStatusIndicator != null) {
                        gccStatusIndicator.setBackgroundResource(R.drawable.status_dot_yellow);
                    }
                    if (gccStatusText != null) {
                        gccStatusText.setText("版本不匹配");
                        gccStatusText.setTextColor(0xFFDCDCAA);
                    }
                    
                    showClangVersionMismatchDialog(expectedVersion, installedVersion);
                }
            });
        }).start();
    }
    
    private void showClangVersionMismatchDialog(String expectedVersion, String installedVersion) {
        String message = "检测到 Clang 编译器版本不匹配，需要重新安装。\n\n" +
                        "期望版本:\n" + (expectedVersion != null ? expectedVersion : "未知") + "\n\n" +
                        "已安装版本:\n" + (installedVersion != null ? installedVersion : "未知");
        
        new MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle("Clang 版本不匹配")
            .setMessage(message)
            .setPositiveButton("重新安装", (dialog, which) -> {
                View gccStatusIndicator = findViewById(R.id.gcc_status_indicator);
                TextView gccStatusText = findViewById(R.id.gcc_status_text);
                
                if (gccStatusIndicator != null) {
                    gccStatusIndicator.setBackgroundResource(R.drawable.status_dot_yellow);
                }
                if (gccStatusText != null) {
                    gccStatusText.setText("重新安装中...");
                    gccStatusText.setTextColor(0xFFDCDCAA);
                }
                
                ClangInstaller.reinstallWithProgress(this, () -> {
                    isClangInstalled = true;
                    setUIEnabled(true);
                    
                    if (gccStatusIndicator != null) {
                        gccStatusIndicator.setBackgroundResource(R.drawable.status_dot_green);
                    }
                    if (gccStatusText != null) {
                        gccStatusText.setText("已就绪");
                        gccStatusText.setTextColor(0xFF4EC9B0);
                    }
                });
            })
            .setNegativeButton("稍后", null)
            .setCancelable(false)
            .show();
    }
    
    /**
     * 设置UI元素的启用/禁用状态
     * @param enabled true表示启用，false表示禁用
     */
    private void setUIEnabled(boolean enabled) {
        runOnUiThread(() -> {
            // 设置卡片的启用状态和透明度
            if (editorModeCard != null) {
                editorModeCard.setEnabled(enabled);
                editorModeCard.setAlpha(enabled ? 1.0f : 0.5f);
            }
            if (terminalModeCard != null) {
                terminalModeCard.setEnabled(enabled);
                terminalModeCard.setAlpha(enabled ? 1.0f : 0.5f);
            }
            if (pluginCard != null) {
                pluginCard.setEnabled(enabled);
                pluginCard.setAlpha(enabled ? 1.0f : 0.5f);
            }
            
            // 设置FAB菜单的启用状态
            if (fabMenu != null) {
                fabMenu.setEnabled(enabled);
                fabMenu.setAlpha(enabled ? 1.0f : 0.5f);
            }
            
            // 设置最近项目快捷入口的启用状态
            MaterialCardView shortcutCard = findViewById(R.id.recent_project_shortcut_card);
            if (shortcutCard != null && shortcutCard.getVisibility() == View.VISIBLE) {
                shortcutCard.setEnabled(enabled);
                shortcutCard.setAlpha(enabled ? 1.0f : 0.5f);
            }
        });
    }

    private void startEnterAnimation() {
        View welcomeSection = findViewById(R.id.welcome_section);
        MaterialCardView appModeCard = findViewById(R.id.app_mode_card);
        MaterialCardView floatingModeCard = findViewById(R.id.floating_mode_card);
        MaterialCardView pluginCard = findViewById(R.id.plugin_card);
        LinearLayout bottomSection = findViewById(R.id.bottom_section);
        
        if (welcomeSection != null && appModeCard != null && floatingModeCard != null && pluginCard != null && bottomSection != null) {
            welcomeSection.setAlpha(0f);
            welcomeSection.setTranslationY(30f);
            appModeCard.setAlpha(0f);
            appModeCard.setTranslationY(20f);
            floatingModeCard.setAlpha(0f);
            floatingModeCard.setTranslationY(20f);
            pluginCard.setAlpha(0f);
            pluginCard.setTranslationY(20f);
            bottomSection.setAlpha(0f);
            bottomSection.setTranslationY(20f);

            AnimatorSet animatorSet = new AnimatorSet();

            ObjectAnimator welcomeFadeIn = ObjectAnimator.ofFloat(welcomeSection, "alpha", 0f, 1f);
            ObjectAnimator welcomeSlideUp = ObjectAnimator.ofFloat(welcomeSection, "translationY", 30f, 0f);
            AnimatorSet welcomeAnim = new AnimatorSet();
            welcomeAnim.playTogether(welcomeFadeIn, welcomeSlideUp);
            welcomeAnim.setDuration(250);
            welcomeAnim.setInterpolator(new AccelerateDecelerateInterpolator());

            ObjectAnimator appFadeIn = ObjectAnimator.ofFloat(appModeCard, "alpha", 0f, 1f);
            ObjectAnimator appSlideUp = ObjectAnimator.ofFloat(appModeCard, "translationY", 20f, 0f);
            AnimatorSet appAnim = new AnimatorSet();
            appAnim.playTogether(appFadeIn, appSlideUp);
            appAnim.setDuration(200);
            appAnim.setInterpolator(new AccelerateDecelerateInterpolator());

            ObjectAnimator floatingFadeIn = ObjectAnimator.ofFloat(floatingModeCard, "alpha", 0f, 1f);
            ObjectAnimator floatingSlideUp = ObjectAnimator.ofFloat(floatingModeCard, "translationY", 20f, 0f);
            AnimatorSet floatingAnim = new AnimatorSet();
            floatingAnim.playTogether(floatingFadeIn, floatingSlideUp);
            floatingAnim.setDuration(200);
            floatingAnim.setInterpolator(new AccelerateDecelerateInterpolator());

            ObjectAnimator pluginFadeIn = ObjectAnimator.ofFloat(pluginCard, "alpha", 0f, 1f);
            ObjectAnimator pluginSlideUp = ObjectAnimator.ofFloat(pluginCard, "translationY", 20f, 0f);
            AnimatorSet pluginAnim = new AnimatorSet();
            pluginAnim.playTogether(pluginFadeIn, pluginSlideUp);
            pluginAnim.setDuration(200);
            pluginAnim.setInterpolator(new AccelerateDecelerateInterpolator());

            ObjectAnimator bottomFadeIn = ObjectAnimator.ofFloat(bottomSection, "alpha", 0f, 1f);
            ObjectAnimator bottomSlideUp = ObjectAnimator.ofFloat(bottomSection, "translationY", 20f, 0f);
            AnimatorSet bottomAnim = new AnimatorSet();
            bottomAnim.playTogether(bottomFadeIn, bottomSlideUp);
            bottomAnim.setDuration(200);
            bottomAnim.setInterpolator(new AccelerateDecelerateInterpolator());

            animatorSet.playSequentially(welcomeAnim, appAnim, floatingAnim, pluginAnim, bottomAnim);
            animatorSet.start();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivity(settingsIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新最近项目快捷入口
        if (checkStoragePermission()) {
            setupRecentProjectShortcut();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 使用统一的权限检查方法
                if (checkStoragePermission()) {
                    Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                    initializeApp();
                } else {
                    Toast.makeText(this, "需要存储权限才能正常使用应用", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        } else if (requestCode == REQUEST_CODE_IMPORT_PROJECT) {
            if (resultCode == RESULT_OK && data != null) {
                com.vkyra.code.Log.d("MainAc", "url: " + data.getDataString());
                Uri uri = data.getData();
                if (uri != null) {
                    // 添加更多调试信息
                    com.vkyra.code.Log.d("MainAc", "URI: " + uri.toString());
                    com.vkyra.code.Log.d("MainAc", "Path: " + uri.getPath());
                    com.vkyra.code.Log.d("MainAc", "LastPathSegment: " + uri.getLastPathSegment());
                    
                    // 尝试获取真实路径并自动填入对话框
                    String realPath = getRealPathFromUri(uri);
                    com.vkyra.code.Log.d("MainAc", "RealPath: " + realPath);
                    
                    if (realPath != null && new File(realPath).exists()) {
                        // 自动导入项目
                        File projectDir = new File(realPath);
                        String projectName = projectDir.getName();
                        Toast.makeText(this, "找到路径: " + realPath, Toast.LENGTH_SHORT).show();
                        importProject(realPath, projectName);
                    } else {
                        // 显示解析出的路径信息，帮助用户手动输入
                        String message = "无法访问选择的文件夹";
                        if (realPath != null) {
                            message += "\n解析路径: " + realPath;
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        showManualImportDialogWithPath(realPath);
                    }
                }
            }
        }
    }
    
    private String getRealPathFromUri(Uri uri) {
        try {
            String uriString = uri.toString();
            // 处理外部存储文档URI
            if (uriString.contains("com.android.externalstorage.documents/tree/")) {
                // 提取文档ID
                String documentId = uri.getLastPathSegment();
                if (documentId != null) {
                    // 解码URL编码的字符
                    documentId = java.net.URLDecoder.decode(documentId, "UTF-8");
                    
                    // 处理primary存储
                    if (documentId.startsWith("primary:")) {
                        String path = documentId.substring(8); // 移除"primary:"
                        return "/storage/emulated/0/" + path;
                    }
                    // 处理其他存储卷
                    else if (documentId.contains(":")) {
                        String[] parts = documentId.split(":", 2);
                        if (parts.length == 2) {
                            return "/storage/" + parts[0] + "/" + parts[1];
                        }
                    }
                }
            }
            
            // 备用方法：直接从路径解析
            String path = uri.getPath();
            if (path != null && path.startsWith("/tree/")) {
                String[] parts = path.substring(6).split(":", 2); // 移除"/tree/"
                if (parts.length >= 2) {
                    String realPath = parts[1];
                    if (parts[0].equals("primary")) {
                        return "/storage/emulated/0/" + realPath;
                    } else {
                        return "/storage/" + parts[0] + "/" + realPath;
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    

    private void showProjectNameDialog(String projectPath) {
        File projectDir = new File(projectPath);
        String defaultName = projectDir.getName();

        // 创建对话框布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);

        // 定义颜色
        int hintColor = 0xFFB0B0B0;
        int textColor = 0xFFE6E1E5;
        int boxStrokeColor = 0xFF6B6B6B;
        int focusedColor = 0xFFD0BCFF;

        // 项目名称输入
        com.google.android.material.textfield.TextInputLayout nameLayout =
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("项目名称");
        nameLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameLayout.setBoxStrokeColor(boxStrokeColor);
        nameLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        nameLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));

        com.google.android.material.textfield.TextInputEditText nameEdit =
            new com.google.android.material.textfield.TextInputEditText(nameLayout.getContext());
        nameEdit.setTextColor(textColor);
        nameEdit.setText(defaultName);
        nameLayout.addView(nameEdit);
        layout.addView(nameLayout);

        // 显示项目路径
        TextView pathText = new TextView(this);
        pathText.setText("路径: " + projectPath);
        pathText.setTextColor(0xFFB0B0B0);
        pathText.setTextSize(12);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pathParams.topMargin = 16;
        pathText.setLayoutParams(pathParams);
        layout.addView(pathText);

        // 创建对话框
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("导入项目")
            .setView(layout)
            .setPositiveButton("导入", (dialog, which) -> {
                String projectName = nameEdit.getText().toString().trim();
                if (projectName.isEmpty()) {
                    projectName = defaultName;
                }
                importProject(projectPath, projectName);
            })
            .setNegativeButton("取消", null);

        dialogBuilder.show();
    }

    private void showProjectNameDialogForUri(Uri uri, DocumentFile documentFile) {
        String defaultName = documentFile.getName();
        if (defaultName == null) defaultName = "未知项目";

        // 创建对话框布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);

        // 定义颜色
        int hintColor = 0xFFB0B0B0;
        int textColor = 0xFFE6E1E5;
        int boxStrokeColor = 0xFF6B6B6B;
        int focusedColor = 0xFFD0BCFF;

        // 项目名称输入
        com.google.android.material.textfield.TextInputLayout nameLayout =
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("项目名称");
        nameLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameLayout.setBoxStrokeColor(boxStrokeColor);
        nameLayout.setBoxStrokeColorStateList(createBoxStrokeColorStateList(boxStrokeColor, focusedColor));
        nameLayout.setHintTextColor(android.content.res.ColorStateList.valueOf(hintColor));

        com.google.android.material.textfield.TextInputEditText nameEdit =
            new com.google.android.material.textfield.TextInputEditText(nameLayout.getContext());
        nameEdit.setTextColor(textColor);
        nameEdit.setText(defaultName);
        nameLayout.addView(nameEdit);
        layout.addView(nameLayout);

        // 显示URI信息
        TextView pathText = new TextView(this);
        pathText.setText("URI: " + uri.toString());
        pathText.setTextColor(0xFFB0B0B0);
        pathText.setTextSize(12);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pathParams.topMargin = 16;
        pathText.setLayoutParams(pathParams);
        layout.addView(pathText);

        // 提示信息
        TextView hintText = new TextView(this);
        hintText.setText("注意：此项目将以URI方式访问，某些功能可能受限");
        hintText.setTextColor(0xFFFF9800);
        hintText.setTextSize(11);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = 8;
        hintText.setLayoutParams(hintParams);
        layout.addView(hintText);

        // 创建对话框
        String finalDefaultName = defaultName;
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("导入项目")
            .setView(layout)
            .setPositiveButton("导入", (dialog, which) -> {
                String projectName = nameEdit.getText().toString().trim();
                if (projectName.isEmpty()) {
                    projectName = finalDefaultName;
                }
                importProjectFromUri(uri, projectName, documentFile);
            })
            .setNegativeButton("取消", null);

        dialogBuilder.show();
    }

    private void importProjectFromUri(Uri uri, String projectName, DocumentFile documentFile) {
        try {
            // 对于URI方式，我们需要在应用的私有目录创建一个符号链接或配置文件
            // 这里我们创建一个特殊的项目配置来记录URI信息

            // 在项目根目录创建一个以项目名命名的文件夹
            String projectRoot = AppPreferences.getProjectRoot(this);
            File projectDir = new File(projectRoot, projectName);

            // 如果目录已存在，添加数字后缀
            int counter = 1;
            while (projectDir.exists()) {
                projectDir = new File(projectRoot, projectName + "_" + counter);
                counter++;
            }

            if (!projectDir.mkdirs()) {
                Toast.makeText(this, "无法创建项目目录", Toast.LENGTH_SHORT).show();
                return;
            }

            // 初始化为Akode项目
            RecentProjectManager.initProject(projectDir.getAbsolutePath(), projectName, "imported_uri", null);

            // 创建URI配置文件
            createUriConfig(projectDir, uri);

            // 创建third.json配置文件
            createThirdJsonConfig(projectDir);

            Toast.makeText(this, "项目导入成功", Toast.LENGTH_SHORT).show();

            // 打开项目
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra("project_path", projectDir.getAbsolutePath());
            intent.putExtra("source_uri", uri.toString());
            startActivity(intent);

        } catch (Exception e) {
            Toast.makeText(this, "导入项目失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void createUriConfig(File projectDir, Uri uri) {
        try {
            File uriConfigFile = new File(projectDir, ".akode/uri_config.json");
            org.json.JSONObject config = new org.json.JSONObject();
            config.put("sourceUri", uri.toString());
            config.put("accessType", "document_tree");
            config.put("createdTime", System.currentTimeMillis());

            java.io.FileWriter writer = new java.io.FileWriter(uriConfigFile);
            writer.write(config.toString(2));
            writer.close();
        } catch (Exception e) {
            // 忽略URI配置创建错误
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            boolean allGranted = grantResults.length > 0 && grantResults.length == permissions.length;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted && checkStoragePermission()) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                initializeApp();
            } else {
                Toast.makeText(this, "需要存储权限才能正常使用应用", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private boolean checkStoragePermission() {
        return StorageAccessPolicy.hasSharedStorageAccess(this);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !StorageAccessPolicy.usesRuntimeStoragePermissions(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, MANAGE_STORAGE_PERMISSION_REQUEST_CODE);
                Toast.makeText(this, "请授予所有文件访问权限", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, MANAGE_STORAGE_PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    STORAGE_PERMISSION_REQUEST_CODE);
            Toast.makeText(this, "请授予存储权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAssetsToRoot() {
        try {
            File dataDir = MainActivity.this.getFilesDir();
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            String[] assetFolders = {"arm64-v8a", "x86_64"};

            for (String folderName : assetFolders) {
                try {
                    String[] files = getAssets().list(folderName);
                    if (files != null && files.length > 0) {
                        File targetFolder = new File(dataDir, folderName);
                        if (!targetFolder.exists()) {
                            targetFolder.mkdirs();
                        }
                        copyAssetFolder(folderName, targetFolder);
                    }
                } catch (IOException e) {
                    Log.w("MainActivity", "Assets folder not found: " + folderName, e);
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error copying assets", e);
        }
    }

    private void copyAssetFolder(String assetPath, File targetDir) throws IOException {
        String[] files = getAssets().list(assetPath);
        if (files != null) {
            for (String file : files) {
                String assetFilePath = assetPath + "/" + file;
                File targetFile = new File(targetDir, file);

                String[] subFiles = getAssets().list(assetFilePath);
                if (subFiles != null && subFiles.length > 0) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs();
                    }
                    copyAssetFolder(assetFilePath, targetFile);
                } else {
                    copyAssetFile(assetFilePath, targetFile);
                }
            }
        }
    }

    private void copyAssetFile(String assetPath, File targetFile) throws IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;

        try {
            inputStream = getAssets().open(assetPath);
            outputStream = new FileOutputStream(targetFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            targetFile.setReadable(true, true);
            targetFile.setWritable(true, true);
            targetFile.setExecutable(true, true);
        } finally {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
        }
    }

    /**
     * 创建 TextInputLayout 边框颜色状态列表
     */
    private android.content.res.ColorStateList createBoxStrokeColorStateList(int defaultColor, int focusedColor) {
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_focused },
            new int[] { android.R.attr.state_hovered },
            new int[] { }  // 默认状态
        };
        int[] colors = new int[] {
            focusedColor,
            focusedColor,
            defaultColor
        };
        return new android.content.res.ColorStateList(states, colors);
    }
}
