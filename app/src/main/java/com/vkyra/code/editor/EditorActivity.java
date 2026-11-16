package com.vkyra.code.editor;

import com.vkyra.code.lsp.ClangdClient;

import com.vkyra.code.Log;
import com.vkyra.code.R;
import com.vkyra.code.files.FileAdapter;
import com.vkyra.code.files.RootFileUtils;
import com.vkyra.code.files.StorageAccessPolicy;
import com.vkyra.code.git.GitManager;
import com.vkyra.code.plugin.PluginHookManager;
import com.vkyra.code.plugin.PluginManager;
import com.vkyra.code.project.RecentProjectManager;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.settings.SettingsActivity;
import com.vkyra.code.terminal.TerminalActivity;
import com.vkyra.code.toolchain.BuildConfiguration;
import com.vkyra.code.toolchain.BuildToolsInstaller;
import com.vkyra.code.toolchain.ClangFormatter;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.CppCompiler;
import com.vkyra.code.toolchain.HeaderDirManager;
import com.vkyra.code.toolchain.ZshInstaller;
import com.vkyra.code.ui.SvgUtils;

import static java.lang.String.*;

import com.vkyra.code.editor.EditorDocument;
import com.vkyra.code.editor.EditorSessionStore;
import com.vkyra.code.editor.EditorSessionViewModel;
import androidx.lifecycle.ViewModelProvider;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.net.Uri;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.lang.diagnostic.Quickfix;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolInputView;

public class EditorActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private MaterialToolbar toolbar;
    private CodeEditor codeEditor;
    private RecyclerView fileList;
    private TextView tvCurrentPath;
    private FileAdapter fileAdapter;
    private TextView tvTitle;
    private ImageView ivDropdown;
    private View titleLayout;
    
    // 查找替换栏
    private View findReplaceBar;
    private View replaceRow;
    private EditText etFind;
    private EditText etReplace;
    private TextView tvFindCount;
    private List<int[]> findResults = new ArrayList<>();  // 查找结果位置列表
    private int currentFindIndex = -1;  // 当前高亮的结果索引
    
    private File currentDir;
    private File currentFile;
    private String currentProjectPath;  // 当前项目路径（用于更新 .akode 配置）
    private boolean isEditorMode = false;  // 编辑器模式标志，为 true 时不自动切换到项目模式
    private boolean isModified = false;
    
    // Git 管理器
    private GitManager gitManager;
    private MenuItem gitMenuItem;
    
    // 当前应用的主题（用于检测主题变化）
    private boolean currentThemeIsDark = true;
    
    // 用于变量提取的定时器
    private android.os.Handler extractHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable extractRunnable;
    
    // 自动保存
    private android.os.Handler autoSaveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable autoSaveRunnable;
    private MenuItem saveMenuItem;
    private boolean saveButtonHidden = false;  // 保存按钮是否被隐藏到菜单中
    private boolean restoreImeAfterOverflowMenu = false; // 关闭更多菜单后是否恢复键盘
    private String lastSyncedFilePath = null;  // 最近一次与磁盘同步的文件路径
    private long lastSyncedFileModified = -1L; // 最近一次与磁盘同步的时间戳
    private boolean externalChangeDialogShowing = false;
    
    // 诊断容器（用于错误标记）
    private DiagnosticsContainer diagnostics;
    
    // 全局运行参数（不保存，下次打开失效）
    private String runArgs = "";
    
    // 编译模式：0=单文件，1=多文件
    public int compileMode = 0;
    private static final String CONFIG_FILE_NAME = BuildConfiguration.CONFIG_FILE_NAME;
    
    // 多页签：已打开的文件列表
    private EditorSessionViewModel editorSession;
    private List<EditorDocument> openedFiles;
    private boolean initialSessionOpened;
    private View sessionLoadingOverlay;
    private boolean applyingDocument;
    private long openRequestVersion;
    private final android.os.Handler documentHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable persistDrafts = this::persistEditorSession;
    private static final int MAX_OPENED_FILES = EditorSessionStore.MAX_DOCUMENTS;
    
    // Clangd LSP 客户端
    private ClangdClient clangdClient;
    private boolean useClangd = false;  // 是否启用 clangd
    
    // 右侧工具栏

    private int documentVersion = 1;    // 文档版本号
    private android.graphics.Bitmap pendingApkIconBitmap;
    private ImageView pendingApkIconPreview;
    private TextView pendingApkIconHint;
    private androidx.activity.result.ActivityResultLauncher<String> apkIconPickerLauncher;

    /** clangd 诊断应用防抖，减少与文本变更竞态导致的 span 越界 */
    private final android.os.Handler diagnosticsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingDiagnosticsRunnable;
    private static final int DIAGNOSTICS_DEBOUNCE_MS = 48;
    private org.json.JSONArray pendingDiagnosticsArray;
    private String pendingDiagnosticsDocumentId;
    private final Object diagnosticsPendingLock = new Object();
    
    // 诊断消息存储（用于显示错误提示）
    private List<DiagnosticMessage> diagnosticMessages = new ArrayList<>();
    
    // 诊断缓存（按文件路径存储）- 供编辑器访问
    private static Map<String, List<DiagnosticMessage>> diagnosticsCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 获取指定文件的诊断信息（供编辑器使用）
     */
    public static List<DiagnosticMessage> getDiagnosticsForFile(String filePath) {
        return diagnosticsCache.get(filePath);
    }
    
    /**
     * 获取当前文件的诊断信息（供编辑器使用）
     */
    public List<DiagnosticMessage> getCurrentDiagnostics() {
        return new ArrayList<>(diagnosticMessages);
    }
    
    // 忽略的诊断消息列表（按消息内容匹配）
    private static Set<String> ignoredDiagnostics = new java.util.HashSet<>();
    
    /**
     * 诊断消息信息（供编辑器访问）
     */
    public static class DiagnosticMessage {
        public int startIndex;
        public int endIndex;
        public int startLine;
        public int startCol;
        public String message;
        public int severity;  // 1=Error, 2=Warning, 3=Info, 4=Hint
        
        public DiagnosticMessage(int startIndex, int endIndex, int startLine, int startCol, String message, int severity) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.startLine = startLine;
            this.startCol = startCol;
            this.message = message;
            this.severity = severity;
        }
        
        public String getSeverityText() {
            switch (severity) {
                case 1: return "错误";
                case 2: return "警告";
                case 3: return "信息";
                case 4: return "提示";
                default: return "诊断";
            }
        }
        
        public String getSeverityEmoji() {
            switch (severity) {
                case 1: return "❌";
                case 2: return "⚠️";
                case 3: return "ℹ️";
                case 4: return "💡";
                default: return "📋";
            }
        }
    }

    
    /**
     * 已打开文件的信息
     */


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 在 setContentView 之前应用主题
        applyActivityTheme();
        
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) runArgs = savedInstanceState.getString("editor_run_args", "");
        String sessionProject = getIntent().getStringExtra("project_path");
        String sessionKey = sessionProject == null ? "editor" : "project:" + new File(sessionProject).getAbsolutePath();
        editorSession = new ViewModelProvider(this).get(sessionKey, EditorSessionViewModel.class);
        openedFiles = editorSession.documents;
        setContentView(R.layout.activity_editor);
        initApkIconPickerLauncher();
        
        initViews();
        setupToolbar();
        setupEditor();
        setupFileExplorer();
        setupBottomToolbar();
        showSessionLoading();
        checkStoragePermission();
        
        editorSession.persistenceError().observe(this, error -> {
            if (error != null) Toast.makeText(this, "草稿持久化失败: " + error, Toast.LENGTH_LONG).show();
        });
        editorSession.ready().observe(this, ready -> {
            if (!Boolean.TRUE.equals(ready) || initialSessionOpened) return;
            initialSessionOpened = true;
            if (openedFiles.isEmpty()) {
                openInitialDocument();
            } else {
                currentProjectPath = getIntent().getStringExtra("project_path");
                isEditorMode = currentProjectPath == null;
                currentDir = currentProjectPath == null ? Environment.getExternalStorageDirectory() : new File(currentProjectPath);
                EditorDocument active = editorSession.activeDocument();
                displayDocument(active != null ? active : openedFiles.get(0));
                loadDirectory(currentDir);
                String requestedFile = getIntent().getStringExtra("file_path");
                if ((savedInstanceState == null || getIntent().getBooleanExtra("consume_navigation", false)) && requestedFile != null) {
                    doOpenFile(new File(requestedFile));
                    getIntent().removeExtra("consume_navigation");
                }
            }
            initClangd();
        });
        editorSession.load(sessionKey);
    }

    private void showSessionLoading() {
        codeEditor.setEditable(false);
        codeEditor.setFocusable(false);
        codeEditor.clearFocus();
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                codeEditor, com.google.android.material.R.attr.colorSurface));
        overlay.setClickable(true);
        overlay.setFocusableInTouchMode(true);
        TextView message = new TextView(this);
        message.setText("正在恢复编辑会话…");
        message.setGravity(android.view.Gravity.CENTER);
        overlay.addView(message, new android.widget.FrameLayout.LayoutParams(-1, -1));
        sessionLoadingOverlay = overlay;
        addContentView(overlay, new android.view.ViewGroup.LayoutParams(-1, -1));
        overlay.requestFocus();
    }

    private void finishSessionLoading() {
        if (sessionLoadingOverlay == null) return;
        ((android.view.ViewGroup) sessionLoadingOverlay.getParent()).removeView(sessionLoadingOverlay);
        sessionLoadingOverlay = null;
        codeEditor.setFocusableInTouchMode(true);
        codeEditor.setEditable(true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("editor_run_args", runArgs);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        persistEditorSession();
        intent.putExtra("consume_navigation", true);
        setIntent(intent);
        recreate();
    }

    private void openInitialDocument() {
        currentDir = Environment.getExternalStorageDirectory();
        
        // 检查是否从插件打开文件
        Intent intent = getIntent();
        String filePath = intent.getStringExtra("file_path");
        String projectPath = intent.getStringExtra("project_path");
        
        // 处理打开项目（项目模式）
        if (projectPath != null && RootFileUtils.exists(projectPath)) {
            File projectDir = new File(projectPath);
            if (projectDir.isDirectory()) {
                currentDir = projectDir;
                currentProjectPath = projectPath;
                loadDirectory(currentDir);
                // 更新项目打开时间
                RecentProjectManager.updateLastOpenTime(projectPath);
                
                // 恢复项目状态（打开之前的文件）
                RecentProjectManager.ProjectInfo projectInfo = RecentProjectManager.readProjectConfig(projectDir);
                if (projectInfo != null && projectInfo.currentFile != null) {
                    File lastFile = new File(projectDir, projectInfo.currentFile);
                    if (lastFile.exists() && lastFile.isFile()) {
                        doOpenFile(lastFile);
                        return;
                    }
                }
                
                // 如果没有上次打开的文件，尝试打开常见的项目文件
                File fileToOpen = findWelcomeFile(projectDir);
                if (fileToOpen != null) {
                    doOpenFile(fileToOpen);
                } else {
                    // 实在找不到，创建新文件
                    createNewFile();
                }
                return;
            }
        }
        
        // 编辑器模式：清除项目模式
        currentProjectPath = null;
        isEditorMode = true;  // 标记为编辑器模式，不自动切换到项目模式
        loadDirectory(currentDir);
        
        // 打开指定文件
        if (filePath != null && RootFileUtils.exists(filePath)) {
            File file = new File(filePath);
            doOpenFile(file);
            return;
        }
        
        // 检查是否需要恢复上次打开的文件
        if (AppPreferences.getRememberLastFile(this)) {
            String lastFilePath = AppPreferences.getLastFilePath(this);
            String lastDirPath = AppPreferences.getLastDirPath(this);
            
            if (lastDirPath != null && RootFileUtils.exists(lastDirPath)) {
                currentDir = new File(lastDirPath);
                loadDirectory(currentDir);
            }
            
            if (lastFilePath != null && RootFileUtils.exists(lastFilePath)) {
                File lastFile = new File(lastFilePath);
                doOpenFile(lastFile);
            } else {
                createNewFile();
            }
        } else {
            createNewFile();
        }
    }

    private void initApkIconPickerLauncher() {
        apkIconPickerLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) {
                    return;
                }
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
                    if (bitmap == null) {
                        Toast.makeText(this, "图标读取失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingApkIconBitmap = bitmap;
                    if (pendingApkIconPreview != null) {
                        pendingApkIconPreview.setPadding(0, 0, 0, 0);
                        pendingApkIconPreview.setImageBitmap(bitmap);
                    }
                    if (pendingApkIconHint != null) {
                        pendingApkIconHint.setText("已选择图标");
                    }
                    Toast.makeText(this, "图标已选择", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "选择图标失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        );
    }
    
    private void checkStoragePermission() {
        if (StorageAccessPolicy.hasSharedStorageAccess(this)) return;
        boolean runtimePermissions = StorageAccessPolicy.usesRuntimeStoragePermissions(this);
        new MaterialAlertDialogBuilder(this)
                    .setTitle("需要存储权限")
                    .setMessage(runtimePermissions
                            ? "访问共享存储中的项目需要读写存储权限。应用内部文件仍可继续编辑。"
                            : "为了访问所有文件，请授予所有文件访问权限")
                    .setPositiveButton(runtimePermissions ? "授予权限" : "去设置", (d, w) -> {
                        if (runtimePermissions) {
                            androidx.core.app.ActivityCompat.requestPermissions(this,
                                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
                            return;
                        }
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        toolbar = findViewById(R.id.toolbar);
        codeEditor = findViewById(R.id.code_editor);
        fileList = findViewById(R.id.file_list);
        tvCurrentPath = findViewById(R.id.tv_current_path);
        tvTitle = findViewById(R.id.tv_title);
        ivDropdown = findViewById(R.id.iv_dropdown);
        titleLayout = findViewById(R.id.title_layout);
        
        // 初始化查找替换栏
        initFindReplaceBar();
        
        // 初始化右侧工具栏

    }
    
    /**
     * 初始化查找替换栏
     */
    private void initFindReplaceBar() {
        findReplaceBar = findViewById(R.id.find_replace_bar);
        replaceRow = findViewById(R.id.replace_row);
        etFind = findViewById(R.id.et_find);
        etReplace = findViewById(R.id.et_replace);
        tvFindCount = findViewById(R.id.tv_find_count);
        
        // 关闭按钮
        findViewById(R.id.btn_close_find).setOnClickListener(v -> hideFindReplaceBar());
        
        // 上一个/下一个
        findViewById(R.id.btn_find_prev).setOnClickListener(v -> findPrevious());
        findViewById(R.id.btn_find_next).setOnClickListener(v -> findNext());
        
        // 切换替换行显示
        findViewById(R.id.btn_toggle_replace).setOnClickListener(v -> {
            if (replaceRow.getVisibility() == View.VISIBLE) {
                replaceRow.setVisibility(View.GONE);
            } else {
                replaceRow.setVisibility(View.VISIBLE);
                etReplace.requestFocus();
            }
        });
        
        // 替换一个
        findViewById(R.id.btn_replace_one).setOnClickListener(v -> replaceOne());
        
        // 替换全部
        findViewById(R.id.btn_replace_all).setOnClickListener(v -> replaceAll());
        
        // 查找输入框文本变化监听
        etFind.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(android.text.Editable s) {
                performFind(s.toString());
            }
        });
        
        // 回车键跳转到下一个
        etFind.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                findNext();
                return true;
            }
            return false;
        });
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        // 隐藏默认标题，使用自定义标题
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                // 打开抽屉前取消编辑器的文本选择
                if (codeEditor != null) {
                    codeEditor.setSelection(codeEditor.getCursor().getLeftLine(), codeEditor.getCursor().getLeftColumn());
                }
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        
        // 自定义 overflow menu 点击
        toolbar.setOnMenuItemClickListener(item -> {
            // 所有菜单项都通过 onOptionsItemSelected 处理
            return onOptionsItemSelected(item);
        });
        
        // 为保存按钮添加长按监听（另存为）
        toolbar.post(() -> {
            View saveButton = toolbar.findViewById(R.id.action_save);
            if (saveButton != null) {
                saveButton.setOnLongClickListener(v -> {
                    showSaveAsDialog();
                    return true;
                });
            }
            
            // 拦截 overflow 按钮点击，显示自定义菜单
            setupOverflowMenu();
        });
        
        // 点击标题显示已打开文件列表
        titleLayout.setOnClickListener(v -> showOpenedFilesMenu());
        
        // 长按标题显示完整路径
        titleLayout.setOnLongClickListener(v -> {
            showFilePathDialog();
            return true;
        });
        
        // 添加抽屉监听器，在抽屉打开时取消编辑器选择并刷新文件列表
        drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(android.view.View drawerView) {
                // 取消编辑器的文本选择
                if (codeEditor != null) {
                    codeEditor.setSelection(codeEditor.getCursor().getLeftLine(), codeEditor.getCursor().getLeftColumn());
                }
                // 刷新当前目录的文件列表
                if (currentDir != null) {
                    loadDirectory(currentDir);
                }
            }
        });
    }
    
    /**
     * 设置自定义 overflow 菜单
     */
    private void setupOverflowMenu() {
        // 找到 overflow 按钮（ActionMenuView 中的 OverflowMenuButton）
        try {
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                if (child instanceof androidx.appcompat.widget.ActionMenuView) {
                    androidx.appcompat.widget.ActionMenuView menuView = (androidx.appcompat.widget.ActionMenuView) child;
                    for (int j = 0; j < menuView.getChildCount(); j++) {
                        View menuChild = menuView.getChildAt(j);
                        // overflow 按钮的 class 名包含 "OverflowMenuButton"
                        if (menuChild.getClass().getSimpleName().contains("OverflowMenuButton")) {
                            menuChild.setOnClickListener(v -> showCustomOverflowMenu(v));
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    /**
     * 显示自定义 overflow 菜单
     */
    private void showCustomOverflowMenu(View anchor) {
        // 检查保存按钮是否被隐藏（小屏幕且有 Git 时）
        boolean showSaveInMenu = saveButtonHidden;
        
        // 检查是否有启用的编译命令
        java.util.List<AppPreferences.CompileCommand> enabledCommands = 
            AppPreferences.getEnabledCompileCommands(this);
        boolean hasEnabledCommands = !enabledCommands.isEmpty();
        
        List<String> titleList = new ArrayList<>();
        if (showSaveInMenu) {
            titleList.add("保存");
        }
        titleList.add("查找替换");
        titleList.add("跳转到行");
        titleList.add("格式化代码");
        titleList.add("编译模式");
        if (hasEnabledCommands) {
            titleList.add("编译命令");
        }
        titleList.add("打开终端");
        titleList.add("设置");
        
        String[] titles = titleList.toArray(new String[0]);
        
        androidx.appcompat.widget.ListPopupWindow popup = new androidx.appcompat.widget.ListPopupWindow(this);
        popup.setAnchorView(anchor);
        popup.setWidth((int) (140 * getResources().getDisplayMetrics().density));
        popup.setHeight(android.widget.ListPopupWindow.WRAP_CONTENT);
        popup.setModal(true);
        popup.setBackgroundDrawable(getDrawable(currentThemeIsDark ? R.drawable.popup_menu_bg : R.drawable.popup_menu_bg_light));
        popup.setVerticalOffset(8);
        popup.setHorizontalOffset(0);
        popup.setDropDownGravity(android.view.Gravity.END);
        
        // 自定义适配器以支持主题
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() { return titles.length; }
            @Override
            public Object getItem(int position) { return titles[position]; }
            @Override
            public long getItemId(int position) { return position; }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(EditorActivity.this)
                        .inflate(R.layout.item_menu_option, parent, false);
                }
                TextView tvTitle = convertView.findViewById(R.id.tv_title);
                tvTitle.setText(titles[position]);
                tvTitle.setTextColor(currentThemeIsDark ? 0xFFE0E0E0 : 0xFF1E1E1E);
                return convertView;
            }
        };
        
        popup.setAdapter(adapter);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            String selected = titles[position];
            switch (selected) {
                case "保存": saveFile(); break;
                case "查找替换": showFindReplaceBar(null); break;
                case "跳转到行": showGotoLineDialog(); break;
                case "格式化代码": formatCode(); break;
                case "编译模式": showCompileModeDialog(); break;
                case "编译命令": showCompileCommandMenu(); break;
                case "打开终端": openTerminalHere(); break;
                case "设置": startActivity(new Intent(this, SettingsActivity.class)); break;
            }
        });
        
        popup.show();
    }
    
    /**
     * 显示已打开文件的下拉菜单
     */
    private void showOpenedFilesMenu() {
        if (openedFiles.isEmpty()) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 箭头旋转动画（向上）
        ivDropdown.animate()
            .rotation(180f)
            .setDuration(200)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();
        
        // 构建文件列表
        List<String> itemList = new ArrayList<>();
        for (int i = 0; i < openedFiles.size(); i++) {
            EditorDocument info = openedFiles.get(i);
            String name = info.getFileName();
            if (info.isModified) {
                name = "● " + name;
            }
            // 标记当前文件
            if (currentFile != null && info.filePath != null && 
                info.filePath.equals(currentFile.getAbsolutePath())) {
                name = "→ " + name;
            } else if (currentFile == null && info.filePath == null) {
                name = "→ " + name;
            }
            itemList.add(name);
        }
        
        // 创建 ListView
        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_list_item_1, itemList);
        listView.setAdapter(adapter);
        
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("已打开的文件")
            .setView(listView)
            .setNeutralButton("关闭当前", (d, w) -> {
                closeCurrentFile();
            })
            .setNegativeButton("取消", null)
            .create();
        
        // 点击切换文件
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            switchToFile(position);
        });
        
        // 长按关闭文件
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            EditorDocument info = openedFiles.get(position);
            String fileName = info.getFileName();
            
            new MaterialAlertDialogBuilder(this)
                .setTitle("关闭文件")
                .setMessage("确定关闭 " + fileName + " 吗？")
                .setPositiveButton("关闭", (d, w) -> {
                    closeFileAt(position);
                    // 刷新列表
                    if (openedFiles.isEmpty()) {
                        dialog.dismiss();
                    } else {
                        itemList.clear();
                        for (int i = 0; i < openedFiles.size(); i++) {
                            EditorDocument inf = openedFiles.get(i);
                            String name = inf.getFileName();
                            if (inf.isModified) name = "● " + name;
                            if (currentFile != null && inf.filePath != null && 
                                inf.filePath.equals(currentFile.getAbsolutePath())) {
                                name = "→ " + name;
                            } else if (currentFile == null && inf.filePath == null) {
                                name = "→ " + name;
                            }
                            itemList.add(name);
                        }
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
            return true;
        });
        
        // 弹窗关闭时恢复箭头方向
        dialog.setOnDismissListener(d -> {
            ivDropdown.animate()
                .rotation(0f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        });
        
        dialog.show();
    }
    
    /**
     * 关闭指定索引的文件
     */
    private void closeFileAt(int index) {
        saveCurrentFileState();
        if (index < 0 || index >= openedFiles.size()) return;
        EditorDocument document = openedFiles.get(index);
        boolean active = document == editorSession.activeDocument();
        Runnable close = () -> doCloseFileAt(openedFiles.indexOf(document), active);
        if (!document.isModified) { close.run(); return; }
        new MaterialAlertDialogBuilder(this)
            .setTitle("保存更改")
            .setMessage("文件 " + document.getFileName() + " 已修改，是否保存？")
            .setPositiveButton("保存", (d, w) -> {
                if (active) saveFile(close);
                else if (document.filePath == null) {
                    switchToFile(openedFiles.indexOf(document));
                    saveFile(close);
                } else writeDocument(document, new File(document.filePath), document.content, close, null);
            })
            .setNegativeButton("不保存", (d, w) -> close.run())
            .setNeutralButton("取消", null)
            .show();
    }
    
    /**
     * 执行关闭指定索引的文件
     */
    private void doCloseFileAt(int index, boolean wasCurrent) {
        if (index < 0 || index >= openedFiles.size()) return;
        openRequestVersion++;
        EditorDocument removed = openedFiles.get(index);
        boolean active = removed == editorSession.activeDocument();
        if (clangdClient != null && removed.filePath != null) clangdClient.closeDocument(removed.filePath);
        openedFiles.remove(index);
        if (active) {
            editorSession.activeDocumentId = null;
            if (openedFiles.isEmpty()) createNewFile();
            else displayDocument(openedFiles.get(Math.min(index, openedFiles.size() - 1)));
        }
        updateTitle();
        editorSession.persist();
    }
    
    /**
     * 保存当前文件状态到 openedFiles
     */
    private void saveCurrentFileState() {
        EditorDocument document = editorSession.activeDocument();
        if (document == null || codeEditor == null) return;
        document.content = codeEditor.getText().toString();
        document.cursorLine = codeEditor.getCursor().getLeftLine();
        document.cursorColumn = codeEditor.getCursor().getLeftColumn();
        document.scrollY = Math.max(0, (int) codeEditor.getOffsetY());
        document.isModified = isModified;
    }

    private void persistEditorSession() {
        saveCurrentFileState();
        editorSession.persist();
    }

    private void displayDocument(EditorDocument document) {
        if (document == null) return;
        openRequestVersion++;
        if (clangdClient != null && currentFile != null) clangdClient.closeDocument(currentFile.getAbsolutePath());
        editorSession.activeDocumentId = document.id;
        currentFile = document.filePath == null ? null : new File(document.filePath);
        if (currentFile != null && currentFile.getParentFile() != null) currentDir = currentFile.getParentFile();
        applyingDocument = true;
        try {
            setEditorLanguage(document.getFileName());
            codeEditor.setText(document.content);
        } finally { applyingDocument = false; }
        isModified = document.isModified;
        finishSessionLoading();
        markCurrentFileSynced();
        updateTitle();
        clearDiagnostics();
        codeEditor.post(() -> {
            if (isDestroyed() || editorSession.activeDocument() != document) return;
            int line = Math.max(0, Math.min(document.cursorLine, codeEditor.getText().getLineCount() - 1));
            int column = Math.max(0, Math.min(document.cursorColumn, codeEditor.getText().getColumnCount(line)));
            codeEditor.setSelection(line, column);
            codeEditor.getScroller().startScroll(0, document.scrollY, 0, 0, 0);
            codeEditor.invalidate();
        });
        notifyClangdFileOpened();
    }
    
    /**
     * 切换到指定索引的文件
     */
    private void switchToFile(int index) {
        if (index < 0 || index >= openedFiles.size()) return;
        openRequestVersion++;
        EditorDocument target = openedFiles.get(index);
        if (target == editorSession.activeDocument()) return;
        saveCurrentFileState();
        displayDocument(target);
        editorSession.persist();
    }
    
    /**
     * 关闭当前文件
     */
    private void closeCurrentFile() {
        saveCurrentFileState();
        closeFileAt(openedFiles.indexOf(editorSession.activeDocument()));
    }
    
    /**
     * 执行关闭文件
     */
    private void doCloseFile(int index) {
        doCloseFileAt(index, true);
    }
    
    /**
     * 添加文件到已打开列表
     */
    private void addToOpenedFiles(File file, String content) {
        String path = file == null ? null : file.getAbsolutePath();
        if (path != null) {
            for (EditorDocument document : openedFiles) {
                if (path.equals(document.filePath)) { editorSession.activeDocumentId = document.id; return; }
            }
        }
        EditorDocument document = new EditorDocument(path);
        document.content = content;
        openedFiles.add(0, document);
        editorSession.activeDocumentId = document.id;
    }
    
    /**
     * 应用 Activity 主题（必须在 setContentView 之前调用）
     */
    private void applyActivityTheme() {
        currentThemeIsDark = shouldUseDarkTheme();
        if (currentThemeIsDark) {
            setTheme(R.style.Theme_FastScan_Editor_Dark);
        } else {
            setTheme(R.style.Theme_FastScan_Editor_Light);
        }
    }
    
    /**
     * 判断是否应该使用暗色主题
     */
    private boolean shouldUseDarkTheme() {
        String theme = AppPreferences.getEditorTheme(this);
        if ("system".equals(theme)) {
            // 跟随系统设置
            int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        } else if ("light".equals(theme)) {
            return false;
        } else {
            return true;  // 默认暗色
        }
    }
    
    /**
     * 根据设置应用编辑器主题
     */
    private void applyEditorTheme() {
        boolean useDarkTheme = shouldUseDarkTheme();
        
        com.vkyra.code.editor.language.EditorLanguages.initialize(getApplicationContext());
        codeEditor.setColorScheme(com.vkyra.code.editor.language.EditorLanguages.colorScheme(useDarkTheme));

        // 更新侧边栏主题

    }

    private void setupEditor() {
        codeEditor.setTypefaceText(Typeface.MONOSPACE);
        codeEditor.setTypefaceLineNumber(Typeface.MONOSPACE);
        codeEditor.setTextSize(14);
        codeEditor.setLineSpacing(2f, 1.2f);
        codeEditor.setTabWidth(4);
        codeEditor.setLineNumberEnabled(true);
        codeEditor.setHighlightCurrentLine(true);
        codeEditor.setCursorAnimationEnabled(true);
        codeEditor.setScrollBarEnabled(true);
        codeEditor.setLineNumberMarginLeft(12f);
        codeEditor.setDividerMargin(8f);
        codeEditor.setBlockLineEnabled(true);
        codeEditor.setHighlightBracketPair(true);
        codeEditor.setEditorLanguage(new CppLanguage(this));
        
        // 根据设置应用主题
        applyEditorTheme();
        
        // 初始化诊断容器
        diagnostics = new DiagnosticsContainer();
        codeEditor.registerDiagnosticProvider(container -> {
            if (diagnostics != null) container.addDiagnostics(diagnostics.getRegions());
        });
        codeEditor.invalidateDiagnostics();
        
        // 监听内容变化，标记为已修改，并触发变量提取
        codeEditor.subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent.class, (event) -> {
            if (applyingDocument) return;
            documentHandler.removeCallbacks(persistDrafts);
            documentHandler.postDelayed(persistDrafts, 200);
            if (!isModified) {
                isModified = true;
                updateTitle();
            }
            // 延迟提取变量（避免频繁触发）
            scheduleExtractIdentifiers();
            // 通知 clangd 内容变化（延迟，避免频繁触发）
            scheduleClangdUpdate();
            // 触发自动保存
            scheduleAutoSave();
            
            // 检测触发字符（. -> ::）并手动触发补全
            if (event.getAction() == io.github.rosemoe.sora.event.ContentChangeEvent.ACTION_INSERT) {
                CharSequence inserted = event.getChangedText();
                if (inserted != null && inserted.length() > 0) {
                    char lastChar = inserted.charAt(inserted.length() - 1);
                    // 检测 . 或 > (->的一部分) 或 : (::的一部分)
                    if (lastChar == '.' || lastChar == '>' || lastChar == ':') {
                        // 延迟触发，等待 clangd 处理完文件更新
                        codeEditor.postDelayed(() -> {
                            try {
                                // 检查是否是有效的触发字符序列
                                int line = codeEditor.getCursor().getLeftLine();
                                int col = codeEditor.getCursor().getLeftColumn();
                                if (col > 0) {
                                    String lineText = codeEditor.getText().getLine(line).toString();
                                    boolean shouldTrigger = false;
                                    if (lastChar == '.' && col >= 1) {
                                        // 检查前面不是数字（避免 1.5 这种情况触发）
                                        if (col >= 2) {
                                            char prevChar = lineText.charAt(col - 2);
                                            shouldTrigger = !Character.isDigit(prevChar);
                                        } else {
                                            shouldTrigger = true;
                                        }
                                    } else if (lastChar == '>' && col >= 2 && lineText.charAt(col - 2) == '-') {
                                        shouldTrigger = true;
                                    } else if (lastChar == ':' && col >= 2 && lineText.charAt(col - 2) == ':') {
                                        shouldTrigger = true;
                                    }
                                    
                                    if (shouldTrigger) {
                                        // 先确保 clangd 收到最新内容
                                        notifyClangdFileChanged();
                                        // 再延迟触发补全，给 clangd 足够的处理时间
                                        codeEditor.postDelayed(() -> {
                                            try {
                                                codeEditor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion.class)
                                                    .requireCompletion();
                                            } catch (Exception ignored) {}
                                        }, 200);
                                    }
                                }
                            } catch (Exception e) {
                                // 忽略
                            }
                        }, 50);
                    }
                }
            }
        });
        
        // 监听长按事件，实现函数跳转
        codeEditor.subscribeAlways(io.github.rosemoe.sora.event.LongPressEvent.class, (event) -> {
            handleLongPress(event);
        });
        
        // 监听点击事件，显示诊断提示
        codeEditor.subscribeAlways(io.github.rosemoe.sora.event.ClickEvent.class, (event) -> {
            showDiagnosticTooltip(event.getLine(), event.getColumn());
        });
    }
    
    // 诊断提示 PopupWindow
    private android.widget.PopupWindow diagnosticPopup;
    
    /**
     * 显示诊断提示（点击错误位置时）
     */
    private void showDiagnosticTooltip(int line, int column) {
        // 先关闭之前的提示
        dismissDiagnosticTooltip();
        
        if (diagnosticMessages.isEmpty()) return;
        
        try {
            int clickIndex = codeEditor.getText().getCharIndex(line, column);
            
            // 查找点击位置的诊断消息
            DiagnosticMessage foundMsg = null;
            for (DiagnosticMessage msg : diagnosticMessages) {
                if (clickIndex >= msg.startIndex && clickIndex <= msg.endIndex) {
                    foundMsg = msg;
                    break;
                }
            }
            
            if (foundMsg == null) return;
            
            final DiagnosticMessage diagMsg = foundMsg;
            
            // 创建提示视图（带忽略按钮）
            LinearLayout tipLayout = new LinearLayout(this);
            tipLayout.setOrientation(LinearLayout.VERTICAL);
            tipLayout.setPadding(24, 16, 24, 16);
            
            TextView tipView = new TextView(this);
            tipView.setText(foundMsg.message);
            tipView.setTextColor(0xFFFFFFFF);
            tipView.setTextSize(12);
            tipView.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.8));
            tipLayout.addView(tipView);
            
            // 添加忽略按钮
            TextView ignoreBtn = new TextView(this);
            ignoreBtn.setText("忽略此类诊断");
            ignoreBtn.setTextColor(0xFFBBDEFB);
            ignoreBtn.setTextSize(11);
            ignoreBtn.setPadding(0, 12, 0, 0);
            ignoreBtn.setOnClickListener(v -> {
                // 提取诊断类型关键词（去掉具体的标识符名称）
                String msgPattern = extractDiagnosticPattern(diagMsg.message);
                ignoredDiagnostics.add(msgPattern);
                dismissDiagnosticTooltip();
                Toast.makeText(this, "已忽略此类诊断", Toast.LENGTH_SHORT).show();
                // 重新应用诊断过滤
                refreshDiagnosticsDisplay();
            });
            tipLayout.addView(ignoreBtn);
            
            // 根据严重程度设置背景颜色
            int bgColor;
            switch (foundMsg.severity) {
                case 1: bgColor = 0xE6CF6679; break;  // 错误 - 红色
                case 2: bgColor = 0xE6FFB74D; break;  // 警告 - 橙色
                default: bgColor = 0xE6757575; break; // 其他 - 灰色
            }
            
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadius(8 * getResources().getDisplayMetrics().density);
            tipLayout.setBackground(bg);
            
            // 创建 PopupWindow
            diagnosticPopup = new android.widget.PopupWindow(tipLayout,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            diagnosticPopup.setOutsideTouchable(true);
            diagnosticPopup.setFocusable(false);  // 不获取焦点，避免影响键盘输入
            
            // 计算显示位置（在光标下方）
            float lineHeight = codeEditor.getRowHeight();
            float x = codeEditor.getCharOffsetX(line, column) - codeEditor.getOffsetX();
            float y = (line + 1) * lineHeight - codeEditor.getOffsetY() + lineHeight;
            
            // 获取编辑器在屏幕上的位置
            int[] location = new int[2];
            codeEditor.getLocationOnScreen(location);
            
            diagnosticPopup.showAtLocation(codeEditor, android.view.Gravity.NO_GRAVITY,
                (int) (location[0] + x), (int) (location[1] + y));
            
            // 5秒后自动关闭（延长时间以便点击忽略）
            extractHandler.postDelayed(this::dismissDiagnosticTooltip, 5000);
            
        } catch (Exception e) {
            // 忽略
        }
    }
    
    /**
     * 提取诊断消息的模式（用于匹配忽略）
     */
    private String extractDiagnosticPattern(String message) {
        // 移除具体的标识符名称，保留诊断类型
        // 例如: "no member named 'signbit'" -> "no member named"
        String pattern = message;
        // 移除引号中的内容
        pattern = pattern.replaceAll("'[^']*'", "");
        pattern = pattern.replaceAll("\"[^\"]*\"", "");
        // 移除多余空格
        pattern = pattern.replaceAll("\\s+", " ").trim();
        return pattern;
    }
    
    /**
     * 检查诊断消息是否应该被忽略
     */
    private boolean shouldIgnoreDiagnostic(String message) {
        String pattern = extractDiagnosticPattern(message);
        return ignoredDiagnostics.contains(pattern);
    }
    
    /**
     * 刷新诊断显示（过滤已忽略的）
     */
    private void refreshDiagnosticsDisplay() {
        if (diagnostics == null) return;
        
        // 移除已忽略的诊断
        diagnostics.reset();
        for (DiagnosticMessage msg : diagnosticMessages) {
            if (!shouldIgnoreDiagnostic(msg.message)) {
                try {
                    short severity;
                    switch (msg.severity) {
                        case 1: severity = DiagnosticRegion.SEVERITY_ERROR; break;
                        case 2: severity = DiagnosticRegion.SEVERITY_WARNING; break;
                        default: severity = DiagnosticRegion.SEVERITY_TYPO; break;
                    }
                    diagnostics.addDiagnostic(new DiagnosticRegion(msg.startIndex, msg.endIndex, severity));
                } catch (Exception e) {
                    // 忽略
                }
            }
        }
        codeEditor.invalidate();
    }
    
    /**
     * 关闭诊断提示
     */
    private void dismissDiagnosticTooltip() {
        if (diagnosticPopup != null && diagnosticPopup.isShowing()) {
            diagnosticPopup.dismiss();
        }
        diagnosticPopup = null;
    }
    
    // clangd 更新定时器
    private Runnable clangdUpdateRunnable;
    
    /**
     * 延迟通知 clangd 内容变化
     */
    private void scheduleClangdUpdate() {
        if (clangdUpdateRunnable != null) {
            extractHandler.removeCallbacks(clangdUpdateRunnable);
        }
        clangdUpdateRunnable = () -> {
            notifyClangdFileChanged();
        };
        // 立即触发
        extractHandler.post(clangdUpdateRunnable);
    }
    
    /**
     * 处理长按事件，实现函数跳转
     */
    private void handleLongPress(io.github.rosemoe.sora.event.LongPressEvent event) {
        io.github.rosemoe.sora.lang.Language lang = codeEditor.getEditorLanguage();
        if (!(lang instanceof CppLanguage)) return;
        
        CppLanguage cppLang = (CppLanguage) lang;
        String code = codeEditor.getText().toString();
        
        int line = event.getLine();
        int column = event.getColumn();
        
        // 获取长按位置的标识符
        String identifier = cppLang.getIdentifierAt(code, line, column);
        if (identifier == null || identifier.isEmpty()) return;
        
        // 检查是否是关键字或类型，跳过
        if (CppLanguage.isKeyword(identifier) || CppLanguage.isType(identifier) || CppLanguage.isStlType(identifier)) {
            return;
        }
        
        // 拦截事件，阻止默认的长按行为（选择文本）
        event.intercept();
        
        // 显示操作菜单
        showLongPressMenu(identifier, line, column, cppLang, code);
    }
    
    /**
     * 显示长按操作菜单
     */
    private void showLongPressMenu(String identifier, int line, int column, CppLanguage cppLang, String code) {
        // 检查 LSP 设置是否启用
        android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
        boolean enableLsp = prefs.getBoolean("enable_lsp", true);
        boolean hasClangd = enableLsp && useClangd && clangdClient != null && clangdClient.isInitialized() && currentFile != null;
        
        // 构建菜单项
        List<String> menuItems = new ArrayList<>();
        List<Runnable> menuActions = new ArrayList<>();
        
        // 1. 复制（默认第一个）
        menuItems.add("复制");
        menuActions.add(() -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("identifier", identifier);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制: " + identifier, Toast.LENGTH_SHORT).show();
        });
        
        // 2. 跳转到定义
        menuItems.add("跳转到定义");
        menuActions.add(() -> {
            if (hasClangd) {
                handleLongPressWithClangd(identifier, line, column);
            } else {
                handleLongPressWithRegex(cppLang, identifier, code, line, column);
            }
        });
        
        // 3. 查找引用（需要 clangd）
        if (hasClangd) {
            menuItems.add("查找所有引用");
            menuActions.add(() -> findReferencesWithClangd(identifier, line, column));
        }
        
        // 4. 重命名（clangd 或本地）
        menuItems.add("重命名");
        if (hasClangd) {
            menuActions.add(() -> showRenameDialogWithClangd(identifier, line, column));
        } else {
            menuActions.add(() -> showRenameDialogLocal(identifier));
        }
        
        // 5. 代码操作（需要 clangd）
        if (hasClangd) {
            menuItems.add("代码操作 / 快速修复");
            menuActions.add(() -> showCodeActionsWithClangd(line, column));
        }
        
        // 显示菜单对话框
        new MaterialAlertDialogBuilder(this)
            .setTitle(identifier)
            .setItems(menuItems.toArray(new String[0]), (dialog, which) -> {
                menuActions.get(which).run();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 使用 clangd 显示重命名对话框
     */
    private void showRenameDialogWithClangd(String identifier, int line, int column) {
        String filePath = currentFile.getAbsolutePath();
        
        // 先检查是否可以重命名
        clangdClient.prepareRename(filePath, line, column, new ClangdClient.ResponseCallback() {
            @Override
            public void onResponse(org.json.JSONObject result) {
                // 可以重命名，显示对话框
                runOnUiThread(() -> showRenameInputDialog(identifier, line, column, true));
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(EditorActivity.this, "无法重命名: " + error, Toast.LENGTH_SHORT).show();
                    // 回退到本地重命名
                    showRenameInputDialog(identifier, line, column, false);
                });
            }
        });
    }
    
    /**
     * 显示重命名输入对话框（M3 风格）
     */
    private void showRenameInputDialog(String identifier, int line, int column, boolean useClangd) {
        // 创建 M3 风格的输入框
        com.google.android.material.textfield.TextInputLayout inputLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("新名称");
        inputLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        com.google.android.material.textfield.TextInputEditText input = 
            new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
        input.setText(identifier);
        input.setSelectAllOnFocus(true);
        inputLayout.addView(input);
        
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(48, 16, 48, 0);
        container.addView(inputLayout);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(container)
            .setPositiveButton("重命名", (dialog, which) -> {
                String newName = input.getText().toString().trim();
                if (!newName.isEmpty() && !newName.equals(identifier)) {
                    if (useClangd && currentFile != null) {
                        performRenameWithClangd(currentFile.getAbsolutePath(), line, column, newName);
                    } else {
                        performRenameLocal(identifier, newName);
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 使用 clangd 执行重命名
     */
    private void performRenameWithClangd(String filePath, int line, int column, String newName) {
        clangdClient.rename(filePath, line, column, newName, new ClangdClient.ResponseCallback() {
            @Override public void onResponse(org.json.JSONObject result) {
                runOnUiThread(() -> applyWorkspaceEdit(result, applied -> {
                    if (applied) Toast.makeText(EditorActivity.this, "已应用重命名，请保存修改的文件", Toast.LENGTH_SHORT).show();
                }));
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(EditorActivity.this, "重命名失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Applies the entire workspace edit to recoverable buffers, including unopened files.
     * Reading and validation finish before any buffer changes. Disk commits remain explicit saves. */
    private void applyWorkspaceEdit(org.json.JSONObject edit, java.util.function.Consumer<Boolean> completion) {
        if (isDestroyed() || edit == null) { completion.accept(false); return; }
        saveCurrentFileState();
        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> changes = new java.util.LinkedHashMap<>();
        java.util.Map<String, EditorDocument> buffers = new java.util.HashMap<>();
        java.util.Map<String, String> originalContents = new java.util.HashMap<>();
        try {
            org.json.JSONObject simple = edit.optJSONObject("changes");
            if (simple != null) {
                java.util.Iterator<String> uris = simple.keys();
                while (uris.hasNext()) {
                    String uri = uris.next();
                    addWorkspaceEdits(changes, uri, simple.getJSONArray(uri));
                }
            }
            org.json.JSONArray documentChanges = edit.optJSONArray("documentChanges");
            if (documentChanges != null) {
                for (int i = 0; i < documentChanges.length(); i++) {
                    org.json.JSONObject change = documentChanges.getJSONObject(i);
                    if (change.has("kind")) throw new IllegalArgumentException("暂不支持服务器创建、删除或重命名文件");
                    org.json.JSONObject document = change.getJSONObject("textDocument");
                    String uri = document.getString("uri");
                    String path = new File(java.net.URI.create(uri)).getAbsolutePath();
                    if (!document.isNull("version")) {
                        Integer actual = clangdClient == null ? null : clangdClient.getDocumentVersion(path);
                        if (actual == null || actual != document.getInt("version")) {
                            throw new IllegalArgumentException("文档版本已改变，请重新请求操作");
                        }
                    }
                    addWorkspaceEdits(changes, uri, change.getJSONArray("edits"));
                }
            }
            int extra = 0;
            for (String path : changes.keySet()) {
                EditorDocument found = null;
                for (EditorDocument document : openedFiles) {
                    if (path.equals(document.filePath)) { found = document; break; }
                }
                if (found == null) extra++;
                else { buffers.put(path, found); originalContents.put(path, found.content); }
            }
            if (openedFiles.size() + extra > MAX_OPENED_FILES) {
                throw new IllegalArgumentException("操作涉及文件过多，请先关闭部分标签");
            }
        } catch (Exception failure) {
            Toast.makeText(this, "无法应用更改: " + failure.getMessage(), Toast.LENGTH_LONG).show();
            completion.accept(false);
            return;
        }
        editorSession.executeIo(() -> {
            java.util.Map<String, String> replacements = new java.util.LinkedHashMap<>();
            try {
                for (java.util.Map.Entry<String, java.util.List<org.eclipse.lsp4j.TextEdit>> entry : changes.entrySet()) {
                    String path = entry.getKey();
                    String original = originalContents.containsKey(path) ? originalContents.get(path) : RootFileUtils.readFile(path);
                    replacements.put(path, com.vkyra.code.lsp.LspTextEdits.apply(original, entry.getValue()));
                }
                documentHandler.post(() -> {
                    if (isDestroyed()) { completion.accept(false); return; }
                    saveCurrentFileState();
                    for (String path : replacements.keySet()) {
                        EditorDocument old = buffers.get(path);
                        if (old != null && (!openedFiles.contains(old) || !java.util.Objects.equals(old.filePath, path)
                                || !old.content.equals(originalContents.get(path)))) {
                            Toast.makeText(this, "文件已改变，请重新请求操作", Toast.LENGTH_LONG).show();
                            completion.accept(false); return;
                        }
                        if (old == null) {
                            for (EditorDocument document : openedFiles) {
                                if (path.equals(document.filePath)) { completion.accept(false); return; }
                            }
                        }
                    }
                    long added = replacements.keySet().stream().filter(path -> !buffers.containsKey(path)).count();
                    if (openedFiles.size() + added > MAX_OPENED_FILES) { completion.accept(false); return; }
                    for (java.util.Map.Entry<String, String> entry : replacements.entrySet()) {
                        EditorDocument document = buffers.get(entry.getKey());
                        if (document == null) { document = new EditorDocument(entry.getKey()); openedFiles.add(document); }
                        document.content = entry.getValue();
                        document.isModified = true;
                        if (document == editorSession.activeDocument()) {
                            codeEditor.getText().beginBatchEdit();
                            try { codeEditor.getText().replace(0, codeEditor.getText().length(), document.content); }
                            finally { codeEditor.getText().endBatchEdit(); }
                            isModified = true;
                            updateTitle();
                        }
                    }
                    editorSession.persist();
                    completion.accept(true);
                });
            } catch (Exception failure) {
                documentHandler.post(() -> {
                    if (!isDestroyed()) Toast.makeText(this, "无法应用更改: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                    completion.accept(false);
                });
            }
        });
    }

    private static void addWorkspaceEdits(java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> changes,
            String uri, org.json.JSONArray edits) throws Exception {
        String path = new File(java.net.URI.create(uri)).getAbsolutePath();
        java.util.List<org.eclipse.lsp4j.TextEdit> target = changes.computeIfAbsent(path, ignored -> new ArrayList<>());
        com.google.gson.Gson gson = new com.google.gson.Gson();
        for (int i = 0; i < edits.length(); i++) {
            target.add(gson.fromJson(edits.getJSONObject(i).toString(), org.eclipse.lsp4j.TextEdit.class));
        }
    }

    /**
     * 显示代码操作菜单（clangd）
     */
    private void showCodeActionsWithClangd(int line, int column) {
        String filePath = currentFile.getAbsolutePath();
        
        Toast.makeText(this, "正在获取代码操作...", Toast.LENGTH_SHORT).show();
        
        clangdClient.getCodeActions(filePath, line, column, line, column, new ClangdClient.ArrayResponseCallback() {
            @Override
            public void onResponse(org.json.JSONArray results) {
                runOnUiThread(() -> {
                    if (results == null || results.length() == 0) {
                        Toast.makeText(EditorActivity.this, "没有可用的代码操作", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    List<String> titles = new ArrayList<>();
                    List<org.json.JSONObject> actions = new ArrayList<>();
                    
                    for (int i = 0; i < results.length(); i++) {
                        try {
                            org.json.JSONObject action = results.getJSONObject(i);
                            String title = action.optString("title", "未知操作");
                            titles.add(title);
                            actions.add(action);
                        } catch (Exception e) {
                            // 忽略
                        }
                    }
                    
                    if (titles.isEmpty()) {
                        Toast.makeText(EditorActivity.this, "没有可用的代码操作", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    new MaterialAlertDialogBuilder(EditorActivity.this)
                        .setTitle("代码操作")
                        .setItems(titles.toArray(new String[0]), (dialog, which) -> {
                            executeCodeAction(actions.get(which));
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(EditorActivity.this, "获取代码操作失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }
    
    /**
     * 执行代码操作
     */
    private void executeCodeAction(org.json.JSONObject action) {
        if (action.has("disabled")) {
            Toast.makeText(this, "该代码操作当前不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        Runnable command = () -> {
            org.json.JSONObject nested = action.optJSONObject("command");
            String name = nested == null ? action.optString("command", "") : nested.optString("command", "");
            org.json.JSONArray arguments = (nested == null ? action : nested).optJSONArray("arguments");
            if (!name.isEmpty() && clangdClient != null) {
                clangdClient.executeCommand(name, arguments, new ClangdClient.ResponseCallback() {
                    @Override public void onResponse(org.json.JSONObject result) { }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(EditorActivity.this, error, Toast.LENGTH_LONG).show(); });
                    }
                });
            }
        };
        org.json.JSONObject edit = action.optJSONObject("edit");
        if (edit != null) applyWorkspaceEdit(edit, applied -> { if (applied) command.run(); });
        else command.run();
    }

    /**
     * 显示本地重命名对话框（不使用 clangd）
     */
    private void showRenameDialogLocal(String identifier) {
        // 创建 M3 风格的输入框
        com.google.android.material.textfield.TextInputLayout inputLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("新名称");
        inputLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setHelperText("仅替换当前文件");
        inputLayout.setHelperTextEnabled(true);
        
        com.google.android.material.textfield.TextInputEditText input = 
            new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
        input.setText(identifier);
        input.setSelectAllOnFocus(true);
        inputLayout.addView(input);
        
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(48, 16, 48, 0);
        container.addView(inputLayout);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(container)
            .setPositiveButton("重命名", (dialog, which) -> {
                String newName = input.getText().toString().trim();
                if (!newName.isEmpty() && !newName.equals(identifier)) {
                    performRenameLocal(identifier, newName);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 执行本地重命名操作
     */
    private void performRenameLocal(String oldName, String newName) {
        String code = codeEditor.getText().toString();
        
        // 使用正则匹配完整单词
        String pattern = "\\b" + Pattern.quote(oldName) + "\\b";
        String newCode = code.replaceAll(pattern, newName);
        
        if (!newCode.equals(code)) {
            int cursorLine = codeEditor.getCursor().getLeftLine();
            int cursorCol = codeEditor.getCursor().getLeftColumn();
            
            codeEditor.setText(newCode);
            
            try {
                codeEditor.setSelection(cursorLine, cursorCol);
            } catch (Exception e) {
                // 忽略
            }
            
            int count = 0;
            java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(code);
            while (matcher.find()) count++;
            
            Toast.makeText(this, "已替换 " + count + " 处", Toast.LENGTH_SHORT).show();
            isModified = true;
            updateTitle();
        } else {
            Toast.makeText(this, "未找到可替换的内容", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 显示查找替换栏
     */
    private void showFindReplaceDialog(String identifier) {
        showFindReplaceBar(identifier);
    }
    
    /**
     * 显示底部查找替换栏
     */
    private void showFindReplaceBar(String searchText) {
        findReplaceBar.setVisibility(View.VISIBLE);
        if (searchText != null && !searchText.isEmpty()) {
            etFind.setText(searchText);
            etFind.setSelection(searchText.length());
        }
        etFind.requestFocus();
        
        // 显示软键盘
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etFind, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
        
        // 执行查找
        performFind(etFind.getText().toString());
    }
    
    /**
     * 隐藏查找替换栏
     */
    private void hideFindReplaceBar() {
        findReplaceBar.setVisibility(View.GONE);
        replaceRow.setVisibility(View.GONE);
        findResults.clear();
        currentFindIndex = -1;
        
        // 清除搜索高亮
        codeEditor.getSearcher().stopSearch();
        
        // 隐藏软键盘
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etFind.getWindowToken(), 0);
        }
        
        // 让编辑器获取焦点
        codeEditor.requestFocus();
    }
    
    /**
     * 执行查找
     */
    private void performFind(String searchText) {
        findResults.clear();
        currentFindIndex = -1;
        
        if (searchText == null || searchText.isEmpty()) {
            tvFindCount.setText("0/0");
            codeEditor.getSearcher().stopSearch();
            return;
        }
        
        // 使用编辑器内置的搜索功能
        codeEditor.getSearcher().search(searchText, new io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(false, false));
        
        // 统计匹配数量
        String code = codeEditor.getText().toString();
        int index = 0;
        while ((index = code.indexOf(searchText, index)) != -1) {
            int line = codeEditor.getText().getIndexer().getCharLine(index);
            int col = codeEditor.getText().getIndexer().getCharColumn(index);
            findResults.add(new int[]{line, col, index, searchText.length()});
            index += searchText.length();
        }
        
        if (!findResults.isEmpty()) {
            currentFindIndex = 0;
            // 跳转到第一个结果
            int[] first = findResults.get(0);
            codeEditor.setSelection(first[0], first[1]);
            codeEditor.ensurePositionVisible(first[0], first[1]);
        }
        
        updateFindCount();
    }
    
    /**
     * 更新查找计数显示
     */
    private void updateFindCount() {
        if (findResults.isEmpty()) {
            tvFindCount.setText("0/0");
        } else {
            tvFindCount.setText((currentFindIndex + 1) + "/" + findResults.size());
        }
    }
    
    /**
     * 查找下一个
     */
    private void findNext() {
        if (findResults.isEmpty()) return;
        
        currentFindIndex = (currentFindIndex + 1) % findResults.size();
        int[] pos = findResults.get(currentFindIndex);
        codeEditor.setSelection(pos[0], pos[1]);
        codeEditor.ensurePositionVisible(pos[0], pos[1]);
        updateFindCount();
    }
    
    /**
     * 查找上一个
     */
    private void findPrevious() {
        if (findResults.isEmpty()) return;
        
        currentFindIndex = (currentFindIndex - 1 + findResults.size()) % findResults.size();
        int[] pos = findResults.get(currentFindIndex);
        codeEditor.setSelection(pos[0], pos[1]);
        codeEditor.ensurePositionVisible(pos[0], pos[1]);
        updateFindCount();
    }
    
    /**
     * 替换当前匹配
     */
    private void replaceOne() {
        if (findResults.isEmpty() || currentFindIndex < 0) {
            Toast.makeText(this, "没有可替换的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String replaceText = etReplace.getText().toString();
        int[] pos = findResults.get(currentFindIndex);
        
        // 替换当前位置
        io.github.rosemoe.sora.text.Content content = codeEditor.getText();
        int endCol = pos[1] + pos[3];
        int endLine = pos[0];
        
        // 检查是否跨行
        String lineText = content.getLine(pos[0]).toString();
        if (pos[1] + pos[3] > lineText.length()) {
            endCol = lineText.length();
        }
        
        content.replace(pos[0], pos[1], endLine, endCol, replaceText);
        
        isModified = true;
        updateTitle();
        
        // 重新查找
        performFind(etFind.getText().toString());
        
        Toast.makeText(this, "已替换 1 处", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 替换全部
     */
    private void replaceAll() {
        String findText = etFind.getText().toString();
        String replaceText = etReplace.getText().toString();
        
        if (findText.isEmpty()) {
            Toast.makeText(this, "请输入查找内容", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String code = codeEditor.getText().toString();
        String newCode = code.replace(findText, replaceText);
        
        if (!newCode.equals(code)) {
            int count = findResults.size();
            
            // 保存光标位置
            int cursorLine = codeEditor.getCursor().getLeftLine();
            int cursorCol = codeEditor.getCursor().getLeftColumn();
            
            codeEditor.setText(newCode);
            
            // 恢复光标
            try {
                codeEditor.setSelection(cursorLine, cursorCol);
            } catch (Exception e) {
                // 忽略
            }
            
            isModified = true;
            updateTitle();
            
            // 重新查找
            performFind(findText);
            
            Toast.makeText(this, "已替换 " + count + " 处", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "没有可替换的内容", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 使用 clangd 处理长按跳转
     */
    private void handleLongPressWithClangd(String identifier, int line, int column) {
        String filePath = currentFile.getAbsolutePath();
        
        // 显示加载提示
        Toast.makeText(this, "正在查找 " + identifier + " ...", Toast.LENGTH_SHORT).show();
        
        clangdClient.gotoDefinition(filePath, line, column, new ClangdClient.ResponseCallback() {
            @Override
            public void onResponse(org.json.JSONObject result) {
                try {
                    if (result == null) {
                        // 没有找到定义，可能当前位置就是定义，尝试查找引用
                        findReferencesWithClangd(identifier, line, column);
                        return;
                    }
                    
                    String uri = result.getString("uri");
                    org.json.JSONObject range = result.getJSONObject("range");
                    org.json.JSONObject start = range.getJSONObject("start");
                    int targetLine = start.getInt("line");
                    int targetCol = start.getInt("character");
                    
                    // 解析文件路径
                    String targetPath = new File(java.net.URI.create(uri)).getAbsolutePath();
                    File targetFile = new File(targetPath);
                    
                    // 检查是否跳转到当前位置（说明当前位置就是定义）
                    if (targetFile.getAbsolutePath().equals(currentFile.getAbsolutePath()) 
                        && Math.abs(targetLine - line) <= 1) {
                        // 当前位置就是定义，查找引用
                        findReferencesWithClangd(identifier, line, column);
                        return;
                    }
                    
                    if (targetFile.getAbsolutePath().equals(currentFile.getAbsolutePath())) {
                        // 同一文件内跳转，获取代码片段
                        String codeSnippet = "";
                        String[] lines = codeEditor.getText().toString().split("\n", -1);
                        if (targetLine < lines.length) {
                            codeSnippet = lines[targetLine].trim();
                            if (codeSnippet.length() > 60) {
                                codeSnippet = codeSnippet.substring(0, 60) + "...";
                            }
                        }
                        
                        new MaterialAlertDialogBuilder(EditorActivity.this)
                            .setTitle("跳转到定义")
                            .setMessage(identifier + " 定义在第 " + (targetLine + 1) + " 行\n\n" + codeSnippet)
                            .setPositiveButton("跳转", (d, w) -> {
                                codeEditor.setSelection(targetLine, targetCol);
                                codeEditor.ensurePositionVisible(targetLine, targetCol);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    } else {
                        // 跨文件跳转，获取代码片段
                        String codeSnippet = getLineFromFile(targetPath, targetLine);
                        showExternalJumpDialog(identifier, targetFile, targetLine, targetCol, codeSnippet);
                    }
                } catch (Exception e) {
                    android.util.Log.e("EditorActivity", "Failed to parse definition result", e);
                    // 解析失败，尝试查找引用
                    findReferencesWithClangd(identifier, line, column);
                }
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(EditorActivity.this, "查找失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 使用 clangd 查找引用
     */
    private void findReferencesWithClangd(String identifier, int line, int column) {
        String filePath = currentFile.getAbsolutePath();
        
        clangdClient.findAllReferences(filePath, line, column, new ClangdClient.ArrayResponseCallback() {
            @Override
            public void onResponse(org.json.JSONArray results) {
                if (results == null || results.length() == 0) {
                    Toast.makeText(EditorActivity.this, "未找到 " + identifier + " 的引用", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 构建引用列表
                List<String> items = new ArrayList<>();
                List<int[]> positions = new ArrayList<>();  // [line, col, isCurrentFile]
                List<String> filePaths = new ArrayList<>();
                
                // 当前文件内容（用于获取代码片段）
                String currentContent = codeEditor.getText().toString();
                String[] currentLines = currentContent.split("\n", -1);
                
                for (int i = 0; i < results.length(); i++) {
                    try {
                        org.json.JSONObject loc = results.getJSONObject(i);
                        String uri = loc.getString("uri");
                        org.json.JSONObject range = loc.getJSONObject("range");
                        org.json.JSONObject start = range.getJSONObject("start");
                        int targetLine = start.getInt("line");
                        int targetCol = start.getInt("character");
                        
                        String targetPath = new File(java.net.URI.create(uri)).getAbsolutePath();
                        
                        File targetFile = new File(targetPath);
                        boolean isCurrentFile = targetFile.getAbsolutePath().equals(currentFile.getAbsolutePath());
                        
                        // 获取代码片段
                        String codeSnippet = "";
                        if (isCurrentFile && targetLine < currentLines.length) {
                            codeSnippet = currentLines[targetLine].trim();
                            if (codeSnippet.length() > 50) {
                                codeSnippet = codeSnippet.substring(0, 50) + "...";
                            }
                        } else if (!isCurrentFile) {
                            // 读取其他文件的代码片段
                            codeSnippet = getLineFromFile(targetPath, targetLine);
                        }
                        
                        String displayName;
                        if (isCurrentFile) {
                            displayName = "第 " + (targetLine + 1) + " 行: " + codeSnippet;
                        } else {
                            displayName = targetFile.getName() + ":" + (targetLine + 1) + "\n" + codeSnippet;
                        }
                        
                        items.add(displayName);
                        positions.add(new int[]{targetLine, targetCol, isCurrentFile ? 1 : 0});
                        filePaths.add(targetPath);
                    } catch (Exception e) {
                        // 忽略解析错误
                    }
                }
                
                if (items.isEmpty()) {
                    Toast.makeText(EditorActivity.this, "未找到 " + identifier + " 的引用", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 显示引用列表对话框
                new MaterialAlertDialogBuilder(EditorActivity.this)
                    .setTitle(identifier + " 的引用 (" + items.size() + ")")
                    .setItems(items.toArray(new String[0]), (dialog, which) -> {
                        int[] pos = positions.get(which);
                        String path = filePaths.get(which);
                        
                        if (pos[2] == 1) {
                            // 当前文件内跳转
                            codeEditor.setSelection(pos[0], pos[1]);
                            codeEditor.ensurePositionVisible(pos[0], pos[1]);
                        } else {
                            // 跨文件跳转
                            File targetFile = new File(path);
                            openFileAndJump(targetFile, pos[0], pos[1]);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(EditorActivity.this, "查找引用失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 从文件中获取指定行的内容
     */
    private String getLineFromFile(String filePath, int lineNum) {
        try {
            String content = RootFileUtils.readFile(filePath);
            if (content == null) return "";
            String[] lines = content.split("\n", -1);
            if (lineNum < lines.length) {
                String line = lines[lineNum].trim();
                if (line.length() > 50) {
                    line = line.substring(0, 50) + "...";
                }
                return line;
            }
        } catch (Exception e) {
            // 忽略
        }
        return "";
    }
    
    /**
     * 显示跨文件跳转对话框
     */
    private void showExternalJumpDialog(String identifier, File targetFile, int line, int column) {
        showExternalJumpDialog(identifier, targetFile, line, column, "");
    }
    
    /**
     * 显示跨文件跳转对话框（带代码片段）
     */
    private void showExternalJumpDialog(String identifier, File targetFile, int line, int column, String codeSnippet) {
        String message = identifier + " 的定义在:\n\n文件: " + targetFile.getName() + "\n第 " + (line + 1) + " 行";
        if (codeSnippet != null && !codeSnippet.isEmpty()) {
            message += "\n\n" + codeSnippet;
        }
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("跳转到定义")
            .setMessage(message)
            .setPositiveButton("打开并跳转", (d, w) -> {
                openFileAndJump(targetFile, line, column);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 使用正则匹配处理长按跳转（回退方案）
     */
    private void handleLongPressWithRegex(CppLanguage cppLang, String identifier, String code, int line, int column) {
        // 检查是否是函数
        boolean isFunction = cppLang.isFunction(identifier);
        
        // 获取函数定义位置（当前文件）
        CppLanguage.FunctionLocation definition = cppLang.getFunctionDefinition(identifier);
        
        // 检查当前位置是否是函数定义
        String defAtPos = cppLang.getFunctionDefinitionAt(line, column);
        
        if (defAtPos != null && defAtPos.equals(identifier)) {
            // 当前位置是函数定义，显示所有调用位置
            List<CppLanguage.FunctionLocation> calls = cppLang.getFunctionCalls(identifier);
            if (calls.isEmpty()) {
                Toast.makeText(this, "未找到 " + identifier + " 的调用", Toast.LENGTH_SHORT).show();
            } else if (calls.size() == 1) {
                // 只有一个调用，显示确认对话框
                showJumpConfirmDialog(identifier, calls.get(0), "调用", null);
            } else {
                // 多个调用，显示列表
                showFunctionCallsDialog(identifier, calls);
            }
        } else {
            // 当前位置是函数调用，跳转到定义
            if (definition != null) {
                showJumpConfirmDialog(identifier, definition, "定义", null);
            } else {
                // 当前文件找不到定义，尝试在头文件中查找
                searchDefinitionInIncludedFiles(identifier, code);
            }
        }
    }
    
    /**
     * 在 #include 的头文件中搜索函数定义
     */
    private void searchDefinitionInIncludedFiles(String funcName, String code) {
        if (currentFile == null) {
            Toast.makeText(this, "未找到 " + funcName + " 的定义", Toast.LENGTH_SHORT).show();
            return;
        }
        
        File currentDir = currentFile.getParentFile();
        if (currentDir == null) {
            Toast.makeText(this, "未找到 " + funcName + " 的定义", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 解析 #include 语句
        List<String> includedFiles = parseIncludeStatements(code);
        if (includedFiles.isEmpty()) {
            Toast.makeText(this, "未找到 " + funcName + " 的定义", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 在后台线程中搜索
        new Thread(() -> {
            ExternalFunctionLocation found = null;
            
            for (String includePath : includedFiles) {
                // 尝试解析头文件路径
                File headerFile = resolveIncludePath(includePath, currentDir);
                if (headerFile == null || !headerFile.exists()) continue;
                
                try {
                    String headerContent = RootFileUtils.readFile(headerFile.getAbsolutePath());
                    CppLanguage.FunctionLocation loc = findFunctionDefinitionInCode(funcName, headerContent);
                    if (loc != null) {
                        found = new ExternalFunctionLocation(funcName, loc.line, loc.column, headerFile);
                        break;
                    }
                } catch (Exception e) {
                    // 忽略读取错误
                }
            }
            
            final ExternalFunctionLocation result = found;
            runOnUiThread(() -> {
                if (result != null) {
                    showExternalJumpConfirmDialog(funcName, result);
                } else {
                    Toast.makeText(this, "未找到 " + funcName + " 的定义", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
    
    /**
     * 解析代码中的 #include 语句
     */
    private List<String> parseIncludeStatements(String code) {
        List<String> includes = new ArrayList<>();
        Pattern includePattern = Pattern.compile("#include\\s*[\"<]([^\"'>]+)[\"'>]");
        Matcher matcher = includePattern.matcher(code);
        while (matcher.find()) {
            includes.add(matcher.group(1));
        }
        return includes;
    }
    
    /**
     * 解析头文件路径
     */
    private File resolveIncludePath(String includePath, File currentDir) {
        // 优先在当前目录查找
        File localFile = new File(currentDir, includePath);
        if (localFile.exists()) return localFile;
        
        // 尝试在父目录查找
        File parentFile = new File(currentDir.getParentFile(), includePath);
        if (parentFile.exists()) return parentFile;
        
        // 尝试常见的 include 目录
        String[] commonDirs = {"include", "inc", "src", "headers"};
        for (String dir : commonDirs) {
            File dirFile = new File(currentDir, dir + "/" + includePath);
            if (dirFile.exists()) return dirFile;
            
            File parentDirFile = new File(currentDir.getParentFile(), dir + "/" + includePath);
            if (parentDirFile.exists()) return parentDirFile;
        }
        
        return null;
    }
    
    /**
     * 在代码中查找函数定义
     */
    private CppLanguage.FunctionLocation findFunctionDefinitionInCode(String funcName, String code) {
        String[] lines = code.split("\n", -1);
        
        Pattern funcDeclPattern = Pattern.compile(
            "^\\s*(?:static\\s+|inline\\s+|virtual\\s+|extern\\s+)*" +
            "(?:const\\s+)?(?:unsigned\\s+|signed\\s+)?" +
            "(?:[a-zA-Z_][a-zA-Z0-9_]*(?:\\s*<[^>]*>)?(?:\\s*[*&])?\\s+)+" +
            Pattern.quote(funcName) + "\\s*\\([^)]*\\)\\s*(?:const)?\\s*$"
        );
        
        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String line = lines[lineNum];
            String trimmedLine = line.trim();
            
            // 风格1: 大括号在同一行
            if (trimmedLine.endsWith("{") && trimmedLine.contains(funcName + "(")) {
                int col = line.indexOf(funcName);
                if (col >= 0) {
                    return new CppLanguage.FunctionLocation(funcName, lineNum, col, true);
                }
            }
            
            // 风格2: 大括号在下一行
            if (trimmedLine.endsWith(")") && trimmedLine.contains(funcName + "(") && lineNum + 1 < lines.length) {
                String nextLine = lines[lineNum + 1].trim();
                if (nextLine.startsWith("{")) {
                    int col = line.indexOf(funcName);
                    if (col >= 0) {
                        return new CppLanguage.FunctionLocation(funcName, lineNum, col, true);
                    }
                }
            }
            
            // 函数声明（头文件中可能只有声明）
            if (trimmedLine.contains(funcName + "(") && trimmedLine.endsWith(";")) {
                int col = line.indexOf(funcName);
                if (col >= 0) {
                    return new CppLanguage.FunctionLocation(funcName, lineNum, col, true);
                }
            }
        }
        
        return null;
    }
    
    /**
     * 外部文件中的函数位置
     */
    private static class ExternalFunctionLocation {
        String name;
        int line;
        int column;
        File file;
        
        ExternalFunctionLocation(String name, int line, int column, File file) {
            this.name = name;
            this.line = line;
            this.column = column;
            this.file = file;
        }
    }
    
    /**
     * 显示跳转到外部文件的确认对话框
     */
    private void showExternalJumpConfirmDialog(String funcName, ExternalFunctionLocation location) {
        try {
            String headerContent = RootFileUtils.readFile(location.file.getAbsolutePath());
            String[] lines = headerContent.split("\n", -1);
            String linePreview = "";
            if (location.line >= 0 && location.line < lines.length) {
                linePreview = lines[location.line].trim();
                if (linePreview.length() > 60) {
                    linePreview = linePreview.substring(0, 60) + "...";
                }
            }
            
            new MaterialAlertDialogBuilder(this)
                .setTitle("跳转到定义")
                .setMessage(funcName + " 的定义在:\n\n" + 
                           "文件: " + location.file.getName() + "\n" +
                           "第 " + (location.line + 1) + " 行:\n" + linePreview)
                .setPositiveButton("打开并跳转", (dialog, which) -> {
                    // 打开文件并跳转
                    openFileAndJump(location.file, location.line, location.column);
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 打开文件并跳转到指定位置
     */
    private void openFileAndJump(File file, int line, int column) {
        // 保存当前文件
        if (isModified) {
            new MaterialAlertDialogBuilder(this)
                .setTitle("保存更改")
                .setMessage("当前文件已修改，是否保存？")
                .setPositiveButton("保存", (d, w) -> {
                    saveFile(() -> doOpenFileAndJump(file, line, column));
                })
                .setNegativeButton("不保存", (d, w) -> {
                    doOpenFileAndJump(file, line, column);
                })
                .setNeutralButton("取消", null)
                .show();
        } else {
            doOpenFileAndJump(file, line, column);
        }
    }
    
    /**
     * 执行打开文件并跳转
     */
    private Intent terminalIntent() {
        return new Intent(this, TerminalActivity.class).putExtra(TerminalActivity.RUN_ARGS, runArgs);
    }

    private void doOpenFileAndJump(File file, int line, int column) {
        doOpenFile(file, () -> {
            int targetLine = Math.max(0, Math.min(line, codeEditor.getText().getLineCount() - 1));
            int targetColumn = Math.max(0, Math.min(column, codeEditor.getText().getColumnCount(targetLine)));
            codeEditor.setSelection(targetLine, targetColumn);
            codeEditor.ensurePositionVisible(targetLine, targetColumn);
        });
    }
    
    /**
     * 初始化 clangd
     */
    private void initClangd() {
        if (!initialSessionOpened || clangdClient != null || isDestroyed()) return;
        // 检查设置是否启用 LSP
        android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
        boolean enableLsp = prefs.getBoolean("enable_lsp", true);
        if (!enableLsp) {
            useClangd = false;
            android.util.Log.d("EditorActivity", "LSP disabled in settings");
            return;
        }
        
        // 检查 clangd 是否存在于 clang 编译链中
        String clangDirName = ClangInstaller.getClangDirName(this);
        if (clangDirName == null) {
            useClangd = false;
            android.util.Log.w("EditorActivity", "Unsupported device architecture for clangd");
            return;
        }
        java.io.File clangdFile = new java.io.File(getFilesDir(), clangDirName + "/bin/clangd");
        if (!clangdFile.exists()) {
            useClangd = false;
            android.util.Log.w("EditorActivity", "clangd not found in clang toolchain: " + clangdFile.getAbsolutePath());
            return;
        }
        
        android.util.Log.d("EditorActivity", "Found clangd in clang toolchain, initializing...");
        
        try {
            clangdClient = new ClangdClient(this, new File(currentProjectPath != null ? currentProjectPath : getIntent().getStringExtra("file_path") != null ? new File(getIntent().getStringExtra("file_path")).getParent() : currentDir != null ? currentDir.getAbsolutePath() : getFilesDir().getAbsolutePath()));
            
            final ClangdClient initializingClient = clangdClient;
            // 先设置诊断回调，确保在初始化完成后能接收诊断
            clangdClient.setWorkspaceEditCallback((edit, completion) ->
                    runOnUiThread(() -> applyWorkspaceEdit(edit, completion)));

            clangdClient.setDiagnosticsCallback((uri, diagnosticsArray) -> {
                android.util.Log.d("EditorActivity", "Diagnostics callback triggered: uri=" + uri);
                handleClangdDiagnostics(uri, diagnosticsArray);
            });
            
            clangdClient.start(new ClangdClient.InitCallback() {
                @Override
                public void onInitialized() {
                    if (isDestroyed() || clangdClient != initializingClient) return;
                    // 再次检查设置，因为 start 是异步的，用户可能在此期间关闭了 LSP
                    android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
                    boolean enableLsp = prefs.getBoolean("enable_lsp", true);
                    
                    if (!enableLsp) {
                        // LSP 已被禁用，停止 clangd
                        android.util.Log.d("EditorActivity", "LSP disabled during init, stopping clangd");
                        if (clangdClient != null) {
                            clangdClient.stop();
                            clangdClient = null;
                        }
                        useClangd = false;
                        return;
                    }
                    
                    useClangd = true;
                    android.util.Log.d("EditorActivity", "Clangd initialized successfully");
                    Toast.makeText(EditorActivity.this, "Clangd 已启动", Toast.LENGTH_SHORT).show();
                    
                    // 如果当前有打开的文件，通知 clangd
                    if (currentFile != null) {
                        android.util.Log.d("EditorActivity", "Notifying clangd about current file: " + currentFile.getAbsolutePath());
                        notifyClangdFileOpened();
                    }
                }
                
                @Override
                public void onError(String error) {
                    if (isDestroyed() || clangdClient != initializingClient) return;
                    initializingClient.cleanup();
                    clangdClient = null;
                    useClangd = false;
                    android.util.Log.e("EditorActivity", "Clangd init failed: " + error);
                }
            });
            
        } catch (Exception e) {
            useClangd = false;
            android.util.Log.e("EditorActivity", "Failed to init clangd", e);
        }
    }
    
    /**
     * 处理 clangd 诊断信息
     */
    private void handleClangdDiagnostics(String uri, org.json.JSONArray diagnosticsArray) {
        android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
        boolean enableLsp = prefs.getBoolean("enable_lsp", true);
        if (!enableLsp) {
            if (diagnostics != null) {
                diagnostics.reset();
                codeEditor.invalidateDiagnostics();
            }
            return;
        }

        android.util.Log.d("EditorActivity", "handleClangdDiagnostics: uri=" + uri + ", count=" + diagnosticsArray.length());

        if (currentFile == null) {
            android.util.Log.d("EditorActivity", "currentFile is null");
            return;
        }

        try {
            if (!currentFile.getAbsoluteFile().equals(new File(java.net.URI.create(uri)).getAbsoluteFile())) return;
        } catch (IllegalArgumentException malformedUri) {
            return;
        }

        org.json.JSONArray snapshot;
        try {
            snapshot = new org.json.JSONArray(diagnosticsArray.toString());
        } catch (Exception e) {
            snapshot = diagnosticsArray;
        }

        synchronized (diagnosticsPendingLock) {
            pendingDiagnosticsArray = snapshot;
            pendingDiagnosticsDocumentId = editorSession.activeDocumentId;
        }

        if (pendingDiagnosticsRunnable != null) {
            diagnosticsHandler.removeCallbacks(pendingDiagnosticsRunnable);
        }
        pendingDiagnosticsRunnable = this::applyPendingClangdDiagnostics;
        diagnosticsHandler.postDelayed(pendingDiagnosticsRunnable, DIAGNOSTICS_DEBOUNCE_MS);
    }

    private void applyPendingClangdDiagnostics() {
        org.json.JSONArray diagnosticsArray;
        String documentId;
        synchronized (diagnosticsPendingLock) {
            diagnosticsArray = pendingDiagnosticsArray;
            documentId = pendingDiagnosticsDocumentId;
            pendingDiagnosticsArray = null;
            pendingDiagnosticsDocumentId = null;
        }
        if (diagnosticsArray == null || currentFile == null || codeEditor == null
                || !java.util.Objects.equals(documentId, editorSession.activeDocumentId)) {
            return;
        }

        android.util.Log.d("EditorActivity", "URI matched, applying " + diagnosticsArray.length() + " diagnostics (debounced)");

        try {
            if (diagnostics == null) {
                diagnostics = new DiagnosticsContainer();
            }
            diagnostics.reset();
            diagnosticMessages.clear();

            int lineCount = codeEditor.getText().getLineCount();
            if (lineCount <= 0) {
                codeEditor.invalidateDiagnostics();
                return;
            }

            for (int i = 0; i < diagnosticsArray.length(); i++) {
                org.json.JSONObject diag = diagnosticsArray.getJSONObject(i);
                org.json.JSONObject range = diag.getJSONObject("range");
                org.json.JSONObject start = range.getJSONObject("start");
                org.json.JSONObject end = range.getJSONObject("end");

                int startLine = start.getInt("line");
                int startCol = start.getInt("character");
                int endLine = end.getInt("line");
                int endCol = end.getInt("character");

                startLine = Math.max(0, Math.min(startLine, lineCount - 1));
                endLine = Math.max(0, Math.min(endLine, lineCount - 1));

                int severity = diag.optInt("severity", 1);
                String message = diag.optString("message", "");

                try {
                    int startIndex = codeEditor.getText().getCharIndex(startLine, startCol);
                    int endIndex = codeEditor.getText().getCharIndex(endLine, endCol);

                    if (endIndex <= startIndex) {
                        endIndex = startIndex + 1;
                    }

                    short severityLevel;
                    switch (severity) {
                        case 1:
                            severityLevel = DiagnosticRegion.SEVERITY_ERROR;
                            break;
                        case 2:
                            severityLevel = DiagnosticRegion.SEVERITY_WARNING;
                            break;
                        default:
                            severityLevel = DiagnosticRegion.SEVERITY_TYPO;
                            break;
                    }

                    diagnostics.addDiagnostic(new DiagnosticRegion(startIndex, endIndex, severityLevel));
                    diagnosticMessages.add(new DiagnosticMessage(startIndex, endIndex, startLine, startCol, message, severity));

                    if (shouldIgnoreDiagnostic(message)) {
                        diagnostics.reset();
                        for (DiagnosticMessage msg : diagnosticMessages) {
                            if (!shouldIgnoreDiagnostic(msg.message)) {
                                short sev;
                                switch (msg.severity) {
                                    case 1: sev = DiagnosticRegion.SEVERITY_ERROR; break;
                                    case 2: sev = DiagnosticRegion.SEVERITY_WARNING; break;
                                    default: sev = DiagnosticRegion.SEVERITY_TYPO; break;
                                }
                                diagnostics.addDiagnostic(new DiagnosticRegion(msg.startIndex, msg.endIndex, sev));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            codeEditor.invalidateDiagnostics();
            codeEditor.invalidate();
            android.util.Log.d("EditorActivity", "Diagnostics applied: " + diagnosticMessages.size() + " messages");

            if (currentFile != null) {
                diagnosticsCache.put(currentFile.getAbsolutePath(), new ArrayList<>(diagnosticMessages));
            }

        } catch (Exception e) {
            android.util.Log.e("EditorActivity", "Failed to handle diagnostics", e);
        }
    }
    
    /**
     * 通知 clangd 文件已打开
     */
    private void notifyClangdFileOpened() {
        // 检查 LSP 设置
        android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
        boolean enableLsp = prefs.getBoolean("enable_lsp", true);
        if (!enableLsp) return;
        
        if (!useClangd || clangdClient == null || currentFile == null) {
            android.util.Log.d("EditorActivity", "notifyClangdFileOpened: skipped (useClangd=" + useClangd + ", clangdClient=" + (clangdClient != null) + ", currentFile=" + (currentFile != null) + ")");
            return;
        }
        
        // 只对 C/C++ 文件启用 clangd
        String fileName = currentFile.getName().toLowerCase();
        if (!fileName.endsWith(".c") && !fileName.endsWith(".cpp") && 
            !fileName.endsWith(".cc") && !fileName.endsWith(".cxx") &&
            !fileName.endsWith(".h") && !fileName.endsWith(".hpp") &&
            !fileName.endsWith(".hxx")) {
            android.util.Log.d("EditorActivity", "notifyClangdFileOpened: skipped (not C/C++ file: " + fileName + ")");
            return;
        }
        
        // 确保有 compile_flags.txt 文件（clangd 需要它来正确分析代码）

        
        // 更新 CppLanguage 的文件路径（用于补全）
        io.github.rosemoe.sora.lang.Language lang = codeEditor.getEditorLanguage();
        if (lang instanceof CppLanguage) {
            ((CppLanguage) lang).setClangdClient(clangdClient);
            ((CppLanguage) lang).setCurrentFilePath(currentFile.getAbsolutePath());
        }
        
        String content = codeEditor.getText().toString();
        android.util.Log.d("EditorActivity", "notifyClangdFileOpened: " + currentFile.getAbsolutePath() + " (" + content.length() + " chars)");
        clangdClient.openDocument(currentFile.getAbsolutePath(), content);
        documentVersion = 1;
    }
    
    /**
     * 通知 clangd 文件内容已更新
     */
    private void notifyClangdFileChanged() {
        // 检查 LSP 设置
        android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
        boolean enableLsp = prefs.getBoolean("enable_lsp", true);
        if (!enableLsp) return;
        
        if (!useClangd || clangdClient == null || currentFile == null) return;
        
        // 只对 C/C++ 文件启用 clangd
        String fileName = currentFile.getName().toLowerCase();
        if (!fileName.endsWith(".c") && !fileName.endsWith(".cpp") && 
            !fileName.endsWith(".cc") && !fileName.endsWith(".cxx") &&
            !fileName.endsWith(".h") && !fileName.endsWith(".hpp") &&
            !fileName.endsWith(".hxx")) {
            return;
        }
        
        documentVersion++;
        String content = codeEditor.getText().toString();
        android.util.Log.d("EditorActivity", "notifyClangdFileChanged: version=" + documentVersion);
        clangdClient.updateDocument(currentFile.getAbsolutePath(), content, documentVersion);
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 修复 iPad 等大屏设备上点击唤起输入法后焦点丢失的问题
        if (hasFocus && codeEditor != null && codeEditor.hasWindowFocus()) {
            codeEditor.requestFocus();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // 检查主题是否变化，如果变化则重建 Activity
        boolean newThemeIsDark = shouldUseDarkTheme();
        if (newThemeIsDark != currentThemeIsDark) {
            recreate();
            return;
        }
        
        // 重新应用编辑器主题（用户可能在设置中更改了主题）
        applyEditorTheme();
        
        // 检查 LSP 设置，如果禁用则停止 clangd
        android.content.SharedPreferences prefs = getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
        boolean enableLsp = prefs.getBoolean("enable_lsp", true);
        
        android.util.Log.d("EditorActivity", "onResume: enableLsp=" + enableLsp + ", clangdClient=" + (clangdClient != null) + ", useClangd=" + useClangd);
        
        if (!enableLsp) {
            // LSP 被禁用，停止 clangd
            if (clangdClient != null) {
                android.util.Log.d("EditorActivity", "Stopping clangd because LSP is disabled");
                clangdClient.stop();
                clangdClient = null;
            }
            useClangd = false;
            
            // 清除诊断信息
            if (diagnostics != null) {
                diagnostics.reset();
                codeEditor.invalidateDiagnostics();
                codeEditor.invalidate();
            }
            diagnosticMessages.clear();
        } else if (enableLsp && clangdClient == null) {
            // LSP 被启用但 clangd 未运行，启动它
            android.util.Log.d("EditorActivity", "Starting clangd because LSP is enabled");
            initClangd();
        }
        
        // 每次回到编辑器时检测当前文件是否被外部修改
        checkCurrentFileExternalChanges();
    }

    @Override
    protected void onPause() {
        super.onPause();
        documentHandler.removeCallbacks(persistDrafts);
        persistEditorSession();
        // 当 Activity 暂停/切到后台时，确保未保存变更不会留在“旧磁盘”，
        // 否则返回后外部修改检测会把它当作磁盘发生了变化。
        if (AppPreferences.getAutoSave(this) && isModified && currentFile != null) {
            doSaveFileSilent();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingDiagnosticsRunnable != null) {
            diagnosticsHandler.removeCallbacks(pendingDiagnosticsRunnable);
        }
        if (clangdClient != null) {
            clangdClient.cleanup();
        }
        if (gitManager != null) gitManager.close();
        documentHandler.removeCallbacks(persistDrafts);
        extractHandler.removeCallbacksAndMessages(null);
        diagnosticsHandler.removeCallbacksAndMessages(null);
        stopCompileTimer();
        codeEditor.release();
        // 取消自动保存任务
        if (autoSaveRunnable != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
        }
        // 清理侧边栏

    }
    
    /**
     * 显示跳转确认对话框
     */
    private void showJumpConfirmDialog(String funcName, CppLanguage.FunctionLocation location, String type, File file) {
        String[] lines = codeEditor.getText().toString().split("\n", -1);
        String linePreview = "";
        if (location.line >= 0 && location.line < lines.length) {
            linePreview = lines[location.line].trim();
            if (linePreview.length() > 60) {
                linePreview = linePreview.substring(0, 60) + "...";
            }
        }
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("跳转到" + type)
            .setMessage(funcName + " 的" + type + "在第 " + (location.line + 1) + " 行:\n\n" + linePreview)
            .setPositiveButton("跳转", (dialog, which) -> {
                jumpToLocation(location);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 跳转到指定位置
     */
    private void jumpToLocation(CppLanguage.FunctionLocation location) {
        codeEditor.setSelection(location.line, location.column);
        // 确保跳转位置可见
        codeEditor.ensurePositionVisible(location.line, location.column);
        Toast.makeText(this, "跳转到第 " + (location.line + 1) + " 行", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 显示函数调用位置列表
     */
    private void showFunctionCallsDialog(String funcName, List<CppLanguage.FunctionLocation> calls) {
        String[] lines = codeEditor.getText().toString().split("\n", -1);
        
        List<String> items = new ArrayList<>();
        for (CppLanguage.FunctionLocation loc : calls) {
            String linePreview = "";
            if (loc.line >= 0 && loc.line < lines.length) {
                linePreview = lines[loc.line].trim();
                if (linePreview.length() > 50) {
                    linePreview = linePreview.substring(0, 50) + "...";
                }
            }
            items.add("第 " + (loc.line + 1) + " 行: " + linePreview);
        }
        
        new MaterialAlertDialogBuilder(this)
            .setTitle(funcName + " 的调用 (" + calls.size() + ")")
            .setItems(items.toArray(new String[0]), (dialog, which) -> {
                if (which >= 0 && which < calls.size()) {
                    jumpToLocation(calls.get(which));
                }
                })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 延迟提取变量和函数
     */
    private void scheduleExtractIdentifiers() {
        if (extractRunnable != null) {
            extractHandler.removeCallbacks(extractRunnable);
        }
        extractRunnable = () -> {
            try {
                String code = codeEditor.getText().toString();
                io.github.rosemoe.sora.lang.Language lang = codeEditor.getEditorLanguage();
                if (lang instanceof CppLanguage) {
                    ((CppLanguage) lang).extractIdentifiersAsync(code);
                }
            } catch (Exception ignored) {}
        };
        // 延迟500ms执行，避免频繁触发
        extractHandler.postDelayed(extractRunnable, 500);
    }
    
    /**
     * 调度自动保存
     */
    private void scheduleAutoSave() {
        // 检查是否启用自动保存
        if (!AppPreferences.getAutoSave(this)) return;
        
        // 取消之前的自动保存任务
        if (autoSaveRunnable != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
        }
        
        autoSaveRunnable = () -> {
            if (isModified && currentFile != null) {
                // 静默保存（不显示 Toast）
                doSaveFileSilent();
            }
        };
        
        // 获取自动保存间隔（秒）
        int intervalSeconds = AppPreferences.getAutoSaveInterval(this);
        autoSaveHandler.postDelayed(autoSaveRunnable, intervalSeconds * 1000L);
    }
    
    /**
     * 静默保存文件（不显示 Toast）
     */
    private void doSaveFileSilent() {
        saveCurrentFileState();
        EditorDocument document = editorSession.activeDocument();
        if (document != null && currentFile != null) writeDocument(document, currentFile, document.content, null, null);
    }
    
    /**
     * 清除诊断信息（错误标记）
     */
    private void clearDiagnostics() {
        if (pendingDiagnosticsRunnable != null) diagnosticsHandler.removeCallbacks(pendingDiagnosticsRunnable);
        synchronized (diagnosticsPendingLock) {
            pendingDiagnosticsArray = null;
            pendingDiagnosticsDocumentId = null;
        }
        diagnosticMessages.clear();
        if (diagnostics != null) {
            diagnostics.reset();
            codeEditor.invalidateDiagnostics();
        }
    }
    
    /**
     * 解析编译错误并标记到编辑器
     */
    private void markCompileErrors(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) return;
        if (diagnostics == null) {
            diagnostics = new DiagnosticsContainer();
        }
        diagnostics.reset();
        
        // 解析 GCC 错误格式: filename:line:column: error: message
        // 或者: filename:line: error: message
        Pattern errorPattern = Pattern.compile("(?:^|\\n)([^:\\n]+):(\\d+)(?::(\\d+))?:\\s*(error|warning):\\s*(.+?)(?=\\n|$)", Pattern.MULTILINE);
        Matcher matcher = errorPattern.matcher(errorMessage);
        
        String currentFileName = currentFile != null ? currentFile.getName() : "";
        
        while (matcher.find()) {
            try {
                String fileName = matcher.group(1);
                int line = Integer.parseInt(matcher.group(2)) - 1; // 转为0索引
                String colStr = matcher.group(3);
                int col = colStr != null ? Integer.parseInt(colStr) - 1 : 0;
                String severity = matcher.group(4);
                String message = matcher.group(5);
                
                // 只标记当前文件的错误
                if (fileName.endsWith(currentFileName) || fileName.contains(currentFileName)) {
                    // 确保行号有效
                    if (line >= 0 && line < codeEditor.getLineCount()) {
                        int lineLength = codeEditor.getText().getLine(line).length();
                        int startCol = Math.max(0, Math.min(col, lineLength));
                        int endCol = Math.min(lineLength, startCol + 20); // 标记20个字符宽度
                        
                        // 计算字符位置
                        int startIndex = codeEditor.getText().getCharIndex(line, startCol);
                        int endIndex = codeEditor.getText().getCharIndex(line, endCol);
                        
                        short severityLevel = severity.equals("error") 
                            ? DiagnosticRegion.SEVERITY_ERROR 
                            : DiagnosticRegion.SEVERITY_WARNING;
                        
                        // 使用简单构造函数，不带 DiagnosticDetail
                        diagnostics.addDiagnostic(new DiagnosticRegion(startIndex, endIndex, severityLevel));
                    }
                }
            } catch (Exception e) {
                // 忽略解析错误
            }
        }
        
        // 应用诊断
        codeEditor.invalidateDiagnostics();
    }

    private void setupFileExplorer() {
        fileAdapter = new FileAdapter();
        fileAdapter.setContext(this);  // 设置 context 用于头文件目录检查
        fileList.setLayoutManager(new LinearLayoutManager(this));
        fileList.setAdapter(fileAdapter);
        
        fileAdapter.setOnFileClickListener(new FileAdapter.OnFileClickListener() {
            @Override
            public void onFileClick(File file) {
                if (file.isDirectory()) {
                    loadDirectory(file);
                } else {
                    openFile(file);
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
            }

            @Override
            public void onFileLongClick(File file) {
                showFileOptionsDialog(file);
            }
        });
        
        ImageButton btnLocateFile = findViewById(R.id.btn_locate_file);
        btnLocateFile.setOnClickListener(v -> locateCurrentFile());
        
        ImageButton btnNewFile = findViewById(R.id.btn_new_file);
        btnNewFile.setOnClickListener(v -> showNewFileDialog());
        
        ImageButton btnNewFolder = findViewById(R.id.btn_new_folder);
        btnNewFolder.setOnClickListener(v -> showNewFolderDialog());
        
        // 路径栏点击编辑
        tvCurrentPath.setOnClickListener(v -> showPathEditDialog());
        
        // 路径栏长按复制
        tvCurrentPath.setOnLongClickListener(v -> {
            String path = tvCurrentPath.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("path", path));
            Toast.makeText(this, "已复制路径", Toast.LENGTH_SHORT).show();
            return true;
        });
    }
    
    private void showPathEditDialog() {
        // 创建主布局
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(48, 32, 48, 0);
        
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("路径");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setText(currentDir.getAbsolutePath());
        input.setSelection(input.getText().length());
        inputLayout.addView(input);
        mainLayout.addView(inputLayout);
        
        // 恢复默认按钮
        TextView resetBtn = new TextView(this);
        resetBtn.setText("恢复默认路径");
        resetBtn.setTextColor(0xFF9E9E9E);
        resetBtn.setTextSize(14);
        resetBtn.setPadding(0, 24, 0, 0);
        resetBtn.setOnClickListener(v -> {
            input.setText("/storage/emulated/0");
            input.setSelection(input.getText().length());
        });
        mainLayout.addView(resetBtn);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("跳转到路径")
            .setView(mainLayout)
            .setPositiveButton("跳转", (d, w) -> {
                String path = input.getText().toString().trim();
                if (!path.isEmpty()) {
                    File dir = new File(path);
                    if (RootFileUtils.exists(path) && RootFileUtils.isDirectory(path)) {
                        loadDirectory(dir);
                    } else {
                        Toast.makeText(this, "路径不存在或不是目录", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 显示跳转到行对话框（带预览）
     */
    private void showGotoLineDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);
        
        // 输入框
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("行号 (1-" + codeEditor.getLineCount() + ")");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        inputLayout.addView(input);
        layout.addView(inputLayout);
        
        // 预览区域
        TextView previewText = new TextView(this);
        previewText.setTextSize(12);
        previewText.setTextColor(0xFFAAAAAA);
        previewText.setTypeface(Typeface.MONOSPACE);
        previewText.setPadding(0, 24, 0, 16);
        previewText.setMaxLines(11);
        layout.addView(previewText);
        
        // 监听输入变化，实时预览
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String text = s.toString().trim();
                if (text.isEmpty()) {
                    previewText.setText("");
                    return;
                }
                try {
                    int lineNum = Integer.parseInt(text);
                    int targetLine = lineNum - 1; // 转为0索引
                    int totalLines = codeEditor.getLineCount();
                    
                    if (targetLine < 0 || targetLine >= totalLines) {
                        previewText.setText("行号超出范围");
                        return;
                    }
                    
                    // 获取上下5行预览
                    StringBuilder preview = new StringBuilder();
                    int startLine = Math.max(0, targetLine - 5);
                    int endLine = Math.min(totalLines - 1, targetLine + 5);
                    
                    for (int i = startLine; i <= endLine; i++) {
                        String lineContent = codeEditor.getText().getLine(i).toString();
                        // 截断过长的行
                        if (lineContent.length() > 40) {
                            lineContent = lineContent.substring(0, 40) + "...";
                        }
                        String lineNumStr = format("%4d", i + 1);
                        if (i == targetLine) {
                            preview.append("→ ").append(lineNumStr).append(" │ ").append(lineContent);
                        } else {
                            preview.append("  ").append(lineNumStr).append(" │ ").append(lineContent);
                        }
                        if (i < endLine) preview.append("\n");
                    }
                    previewText.setText(preview.toString());
                } catch (NumberFormatException e) {
                    previewText.setText("");
                }
            }
        });
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("跳转到行")
            .setView(layout)
            .setPositiveButton("跳转", (d, w) -> {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) {
                    try {
                        int lineNum = Integer.parseInt(text);
                        int targetLine = lineNum - 1;
                        if (targetLine >= 0 && targetLine < codeEditor.getLineCount()) {
                            // 先设置光标位置
                            codeEditor.setSelection(targetLine, 0);
                            
                            // 使用动画平滑滚动到目标行
                            codeEditor.post(() -> {
                                try {
                                    // 计算目标行的 Y 坐标
                                    float[] offset = codeEditor.getLayout().getCharLayoutOffset(targetLine, 0);
                                    int targetY = (int) (offset[0] - codeEditor.getHeight() / 2f);
                                    targetY = Math.max(0, targetY);
                                    
                                    // 使用 ValueAnimator 平滑滚动
                                    final int startY = (int) codeEditor.getOffsetY();
                                    final int endY = targetY;
                                    android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(startY, endY);
                                    animator.setDuration(300);
                                    animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    animator.addUpdateListener(animation -> {
                                        int value = (int) animation.getAnimatedValue();
                                        codeEditor.getScroller().startScroll(0, value, 0, 0, 0);
                                        codeEditor.invalidate();
                                    });
                                    animator.start();
                                } catch (Exception ignored) {}
                            });
                        } else {
                            Toast.makeText(this, "行号超出范围", Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的行号", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 显示运行参数设置对话框
     */
    private void showRunArgsDialog() {
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("运行参数");
        inputLayout.setHelperText("多个参数用空格分隔，如: arg1 arg2");
        inputLayout.setHelperTextEnabled(true);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(48, 32, 48, 0);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setText(runArgs);
        input.setSelection(input.getText().length());
        inputLayout.addView(input);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("运行参数")
            .setView(inputLayout)
            .setPositiveButton("确定", (d, w) -> {
                runArgs = input.getText().toString().trim();
                if (runArgs.isEmpty()) {
                    Toast.makeText(this, "已清除运行参数", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "运行参数: " + runArgs, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .setNeutralButton("清除", (d, w) -> {
                runArgs = "";
                Toast.makeText(this, "已清除运行参数", Toast.LENGTH_SHORT).show();
            })
            .show();
    }
    
    /**
     * 显示运行参数设置对话框（长按运行按钮，设置后直接运行）
     */
    private void showRunArgsAndRunDialog() {
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("运行参数");
        inputLayout.setHelperText("多个参数用空格分隔，如: arg1 arg2");
        inputLayout.setHelperTextEnabled(true);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(48, 32, 48, 0);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setText(runArgs);
        input.setSelection(input.getText().length());
        inputLayout.addView(input);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("运行参数")
            .setView(inputLayout)
            .setPositiveButton("运行", (d, w) -> {
                runArgs = input.getText().toString().trim();
                runCode();
            })
            .setNegativeButton("取消", null)
            .setNeutralButton("清除", (d, w) -> {
                runArgs = "";
                Toast.makeText(this, "已清除运行参数", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void setupBottomToolbar() {
        // 获取所有符号输入视图
        SymbolInputView symbolInput1 = findViewById(R.id.symbol_input);
        SymbolInputView symbolInput2 = findViewById(R.id.symbol_input_2);
        SymbolInputView symbolInput3 = findViewById(R.id.symbol_input_3);
        View symbolContainer = findViewById(R.id.symbol_container);
        
        // 绑定编辑器
        symbolInput1.bindEditor(codeEditor);
        symbolInput2.bindEditor(codeEditor);
        symbolInput3.bindEditor(codeEditor);
        
        // 先隐藏整个容器，避免加载时闪烁
        symbolContainer.setAlpha(0f);
        
        // 根据主题设置颜色
        applySymbolInputTheme(symbolInput1);
        applySymbolInputTheme(symbolInput2);
        applySymbolInputTheme(symbolInput3);
        
        // 从配置加载快捷键
        loadSymbolKeys();
    }
    
    /**
     * 根据主题设置 SymbolInputView 颜色
     */
    private void applySymbolInputTheme(SymbolInputView symbolInput) {
        if (shouldUseDarkTheme()) {
            symbolInput.setBackgroundColor(0xFF252526);  // 深色背景
            symbolInput.setTextColor(0xFFCCCCCC);        // 浅色文字
        } else {
            symbolInput.setBackgroundColor(0xFFF5F5F5);  // 浅灰背景
            symbolInput.setTextColor(0xFF000000);        // 纯黑文字（更明显）
        }
    }
    
    /**
     * 从配置加载快捷键（支持多行）
     */
    private void loadSymbolKeys() {
        SymbolInputView symbolInput1 = findViewById(R.id.symbol_input);
        SymbolInputView symbolInput2 = findViewById(R.id.symbol_input_2);
        SymbolInputView symbolInput3 = findViewById(R.id.symbol_input_3);
        View scroll1 = findViewById(R.id.symbol_scroll_1);
        View scroll2 = findViewById(R.id.symbol_scroll_2);
        View scroll3 = findViewById(R.id.symbol_scroll_3);
        View symbolContainer = findViewById(R.id.symbol_container);
        
        // 先隐藏整个容器，避免闪烁
        symbolContainer.setAlpha(0f);
        scroll2.setVisibility(View.GONE);
        scroll3.setVisibility(View.GONE);
        
        List<SymbolKeyConfig.KeyConfig> configs = SymbolKeyConfig.loadConfigs(this);
        int rows = AppPreferences.getSymbolRows(this);
        
        // 计算每行的按键数量
        int totalKeys = configs.size() + 1; // +1 是自定义按钮
        int keysPerRow = (int) Math.ceil((double) totalKeys / rows);
        
        // 分配按键到各行
        List<List<SymbolKeyConfig.KeyConfig>> rowConfigs = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            int start = r * keysPerRow;
            int end = Math.min(start + keysPerRow, configs.size());
            if (start >= configs.size()) {
                rowConfigs.add(new ArrayList<>());
            } else {
                rowConfigs.add(new ArrayList<>(configs.subList(start, end)));
            }
        }
        
        // 加载第一行（总是显示，包含自定义按钮）
        loadSymbolRowNew(symbolInput1, rowConfigs.size() > 0 ? rowConfigs.get(0) : new ArrayList<>(), true);
        
        // 加载第二行
        if (rows >= 2 && rowConfigs.size() > 1 && !rowConfigs.get(1).isEmpty()) {
            scroll2.setVisibility(View.VISIBLE);
            loadSymbolRowNew(symbolInput2, rowConfigs.get(1), false);
        }
        
        // 加载第三行
        boolean hasRow3 = rows >= 3 && rowConfigs.size() > 2 && !rowConfigs.get(2).isEmpty();
        if (hasRow3) {
            scroll3.setVisibility(View.VISIBLE);
            loadSymbolRowNew(symbolInput3, rowConfigs.get(2), false);
        }
        
        // 等待布局完成后淡入显示
        symbolContainer.post(() -> {
            symbolContainer.animate()
                .alpha(1f)
                .setDuration(150)
                .start();
        });
    }
    
    /**
     * 加载单行符号按键（新版本，支持多种事件类型）
     */
    private void loadSymbolRowNew(SymbolInputView symbolInput, List<SymbolKeyConfig.KeyConfig> configs, boolean includeCustomButton) {
        int extraCount = includeCustomButton ? 1 : 0;
        String[] displays = new String[configs.size() + extraCount];
        String[] inserts = new String[configs.size() + extraCount];
        
        for (int i = 0; i < configs.size(); i++) {
            SymbolKeyConfig.KeyConfig config = configs.get(i);
            displays[i] = config.displayName;
            // 对于非插入类型，设置空字符串，后面通过点击事件处理
            if (SymbolKeyConfig.ACTION_INSERT.equals(config.action)) {
                inserts[i] = config.param != null ? config.param : config.displayName;
            } else {
                inserts[i] = "";
            }
        }
        
        if (includeCustomButton) {
            displays[configs.size()] = "...";
            inserts[configs.size()] = "";
        }
        
        symbolInput.removeAllViews();
        symbolInput.addSymbols(displays, inserts);
        
        // 设置特殊按钮的点击事件和调整宽度
        symbolInput.post(() -> {
            try {
                int count = symbolInput.getChildCount();
                if (count == 0) return;
                
                float density = getResources().getDisplayMetrics().density;
                int minButtonWidth = (int) (48 * density);
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                
                int totalMinWidth = minButtonWidth * count;
                int buttonWidth = totalMinWidth <= screenWidth ? screenWidth / count : minButtonWidth;
                
                for (int i = 0; i < count; i++) {
                    View btn = symbolInput.getChildAt(i);
                    
                    ViewGroup.LayoutParams lp = btn.getLayoutParams();
                    if (lp != null) {
                        lp.width = buttonWidth;
                        btn.setLayoutParams(lp);
                    }
                    
                    if (i < configs.size()) {
                        SymbolKeyConfig.KeyConfig config = configs.get(i);
                        setupKeyButton(btn, config, buttonWidth);
                    } else if (includeCustomButton && i == count - 1) {
                        btn.setOnClickListener(v -> showSymbolCustomizeDialog());
                    }
                }
            } catch (Exception ignored) {}
        });
    }
    
    /**
     * 设置快捷键按钮的事件
     */
    private void setupKeyButton(View btn, SymbolKeyConfig.KeyConfig config, int buttonWidth) {
        switch (config.action) {
            case SymbolKeyConfig.ACTION_UNDO:
                replaceWithIconButton(btn, R.drawable.ic_undo_symbol, v -> codeEditor.undo(), buttonWidth);
                break;
            case SymbolKeyConfig.ACTION_REDO:
                replaceWithIconButton(btn, R.drawable.ic_redo_symbol, v -> codeEditor.redo(), buttonWidth);
                break;
            case SymbolKeyConfig.ACTION_COMMENT:
                btn.setOnClickListener(v -> toggleLineComment());
                break;
            case SymbolKeyConfig.ACTION_SHELL:
                btn.setOnClickListener(v -> executeShellCommand(config.param));
                break;
            case SymbolKeyConfig.ACTION_INSERT:
            default:
                // 插入字符由 SymbolInputView 默认处理
                break;
        }
    }
    
    /**
     * 执行 shell 命令
     */
    private void executeShellCommand(String command) {
        if (command == null || command.isEmpty()) {
            Toast.makeText(this, "命令为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isDestroyed() || isFinishing()) return;
        String expandedCommand = command;
        final java.io.File file = currentFile;
        final String projectPath = currentProjectPath;
        if (file != null) {
            expandedCommand = expandedCommand.replace("${FILE}", file.getAbsolutePath());
            expandedCommand = expandedCommand.replace("${DIR}", file.getParent());
        }
        if (projectPath != null) expandedCommand = expandedCommand.replace("${PROJECT}", projectPath);
        java.io.File directory = projectPath != null ? new java.io.File(projectPath)
                : file != null ? file.getParentFile() : null;
        String shell = ZshInstaller.isInstalled(this) ? ZshInstaller.getZshPath(this) : "sh";
        com.vkyra.code.toolchain.CustomCommandTask.Request request =
                new com.vkyra.code.toolchain.CustomCommandTask.Request(shell, expandedCommand, directory,
                        com.vkyra.code.toolchain.CustomCommandTask.SHELL_TIMEOUT_MILLIS,
                        file == null ? null : file.getAbsolutePath(), null, null);
        com.vkyra.code.toolchain.CustomCommandTasks.start(this, request, result -> {
            if (isDestroyed() || isFinishing()) return;
            String output = (result.output + result.errorOutput).trim();
            if (output.isEmpty() && !result.success) output = result.errorMessage;
            if (!output.isEmpty()) {
                Toast.makeText(this, output.length() > 100 ? output.substring(0, 100) + "..." : output,
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "命令执行完成", Toast.LENGTH_SHORT).show();
            }
        }, () -> { });
    }

    /**
     * 切换行注释（支持单行和多行选中）
     * 点击：注释/取消注释当前行
     * 选中多行时：批量注释/取消注释
     */
    private void toggleLineComment() {
        try {
            io.github.rosemoe.sora.text.Cursor cursor = codeEditor.getCursor();
            io.github.rosemoe.sora.text.Content content = codeEditor.getText();
            String lineCommentPrefix = getLineCommentPrefixForCurrentFile();
            String lineCommentWithSpace = lineCommentPrefix + " ";
            
            int startLine, endLine;
            if (cursor.isSelected()) {
                // 有选中文本，获取选中范围的行
                startLine = cursor.getLeftLine();
                endLine = cursor.getRightLine();
            } else {
                // 无选中，只处理当前行
                startLine = cursor.getLeftLine();
                endLine = startLine;
            }
            
            // 检查选中范围内是否所有行都已注释
            boolean allCommented = true;
            for (int line = startLine; line <= endLine; line++) {
                String lineText = content.getLineString(line);
                String trimmed = lineText.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith(lineCommentPrefix)) {
                    allCommented = false;
                    break;
                }
            }
            
            // 批量操作，使用 beginBatchEdit 保证可以一次撤销
            codeEditor.getText().beginBatchEdit();
            try {
                for (int line = endLine; line >= startLine; line--) {
                    String lineText = content.getLineString(line);
                    
                    // 跳过空行
                    if (lineText.trim().isEmpty()) continue;
                    
                    // 查找行首非空白字符位置
                    int firstNonSpace = 0;
                    while (firstNonSpace < lineText.length() && Character.isWhitespace(lineText.charAt(firstNonSpace))) {
                        firstNonSpace++;
                    }
                    
                    if (allCommented) {
                        // 移除注释
                        if (lineText.substring(firstNonSpace).startsWith(lineCommentPrefix)) {
                            int commentEnd = firstNonSpace + lineCommentPrefix.length();
                            if (commentEnd < lineText.length() && lineText.charAt(commentEnd) == ' ') {
                                commentEnd++;
                            }
                            content.delete(line, firstNonSpace, line, commentEnd);
                        }
                    } else {
                        // 添加注释
                        if (!lineText.substring(firstNonSpace).startsWith(lineCommentPrefix)) {
                            content.insert(line, firstNonSpace, lineCommentWithSpace);
                        }
                    }
                }
            } finally {
                codeEditor.getText().endBatchEdit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getLineCommentPrefixForCurrentFile() {
        if (currentFile != null) {
            String name = currentFile.getName().toLowerCase(java.util.Locale.ROOT);
            if ("cmakelists.txt".equals(name) || name.endsWith(".cmake")) {
                return "#";
            }
        }
        return "//";
    }
    
    /**
     * 将文字按钮替换为图标按钮
     */
    private void replaceWithIconButton(View btn, int iconRes, View.OnClickListener listener) {
        replaceWithIconButton(btn, iconRes, listener, -1);
    }
    
    /**
     * 将文字按钮替换为图标按钮（指定宽度）
     */
    private void replaceWithIconButton(View btn, int iconRes, View.OnClickListener listener, int width) {
        if (btn instanceof TextView) {
            TextView textBtn = (TextView) btn;
            ViewGroup parent = (ViewGroup) textBtn.getParent();
            int index = parent.indexOfChild(textBtn);
            
            // 获取原始宽度
            int originalWidth = width > 0 ? width : textBtn.getWidth();
            int originalHeight = textBtn.getHeight();
            
            // 创建 ImageView 替换 TextView
            ImageView imageBtn = new ImageView(this);
            imageBtn.setImageResource(iconRes);
            imageBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            imageBtn.setBackground(textBtn.getBackground());
            imageBtn.setClickable(true);
            imageBtn.setFocusable(true);
            imageBtn.setOnClickListener(listener);
            
            // 根据主题设置图标颜色
            int iconColor = shouldUseDarkTheme() ? 0xFFCCCCCC : 0xFF000000;
            imageBtn.setColorFilter(iconColor);
            
            // 设置固定宽高，保持和其他按钮一致
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(originalWidth, originalHeight);
            imageBtn.setLayoutParams(lp);
            imageBtn.setPadding(textBtn.getPaddingLeft(), textBtn.getPaddingTop(), 
                               textBtn.getPaddingRight(), textBtn.getPaddingBottom());
            
            // 替换视图
            parent.removeViewAt(index);
            parent.addView(imageBtn, index);
        }
    }
    
    /**
     * 显示快捷键自定义对话框
     */
    private void showSymbolCustomizeDialog() {
        // 获取当前快捷键配置
        List<SymbolKeyConfig.KeyConfig> configList = new ArrayList<>(SymbolKeyConfig.loadConfigs(this));
        
        // 获取当前行数设置
        final int[] currentRows = {AppPreferences.getSymbolRows(this)};
        
        // 创建主布局
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(0, 16, 0, 0);
        
        // 行数设置区域
        LinearLayout rowsLayout = new LinearLayout(this);
        rowsLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowsLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rowsLayout.setPadding(48, 8, 48, 16);
        
        TextView rowsLabel = new TextView(this);
        rowsLabel.setText("显示行数：");
        rowsLabel.setTextSize(14);
        rowsLayout.addView(rowsLabel);
        
        // M3 Segmented Buttons
        com.google.android.material.button.MaterialButtonToggleGroup toggleGroup = 
            new com.google.android.material.button.MaterialButtonToggleGroup(this);
        toggleGroup.setSingleSelection(true);
        toggleGroup.setSelectionRequired(true);
        
        String[] rowOptions = {"1行", "2行", "3行"};
        int[] buttonIds = new int[3];
        float density = getResources().getDisplayMetrics().density;
        int btnHeight = (int) (40 * density);
        int cornerRadius = (int) (20 * density);
        
        for (int i = 0; i < 3; i++) {
            buttonIds[i] = android.view.View.generateViewId();
            
            com.google.android.material.button.MaterialButton btn = 
                new com.google.android.material.button.MaterialButton(this, null, 
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setId(buttonIds[i]);
            btn.setText(rowOptions[i]);
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            
            com.google.android.material.shape.ShapeAppearanceModel.Builder shapeBuilder = 
                btn.getShapeAppearanceModel().toBuilder();
            if (i == 0) {
                shapeBuilder.setTopLeftCornerSize(cornerRadius)
                           .setBottomLeftCornerSize(cornerRadius)
                           .setTopRightCornerSize(0)
                           .setBottomRightCornerSize(0);
            } else if (i == 2) {
                shapeBuilder.setTopLeftCornerSize(0)
                           .setBottomLeftCornerSize(0)
                           .setTopRightCornerSize(cornerRadius)
                           .setBottomRightCornerSize(cornerRadius);
            } else {
                shapeBuilder.setAllCornerSizes(0);
            }
            btn.setShapeAppearanceModel(shapeBuilder.build());
            
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, btnHeight);
            if (i > 0) {
                btnParams.setMarginStart((int) (-1 * density));
            }
            btn.setLayoutParams(btnParams);
            
            toggleGroup.addView(btn);
        }
        
        toggleGroup.check(buttonIds[currentRows[0] - 1]);
        
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                for (int i = 0; i < buttonIds.length; i++) {
                    if (buttonIds[i] == checkedId) {
                        currentRows[0] = i + 1;
                        break;
                    }
                }
            }
        });
        
        rowsLayout.addView(toggleGroup);
        mainLayout.addView(rowsLayout);
        
        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerParams.setMargins(48, 0, 48, 16);
        divider.setLayoutParams(dividerParams);
        mainLayout.addView(divider);
        
        // 创建 RecyclerView
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setPadding(0, 0, 0, 16);
        
        // 创建适配器
        SymbolKeyAdapter adapter = new SymbolKeyAdapter(configList);
        recyclerView.setAdapter(adapter);
        
        // 设置拖动排序
        androidx.recyclerview.widget.ItemTouchHelper.Callback callback = new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP | androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
            
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, 
                                  @NonNull RecyclerView.ViewHolder viewHolder, 
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                java.util.Collections.swap(configList, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }
            
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
            
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }
        };
        
        androidx.recyclerview.widget.ItemTouchHelper touchHelper = new androidx.recyclerview.widget.ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);
        adapter.setTouchHelper(touchHelper);
        
        // 长按删除监听
        adapter.setOnDeleteListener(position -> {
            if (configList.size() > 1) {
                SymbolKeyConfig.KeyConfig config = configList.get(position);
                new MaterialAlertDialogBuilder(this)
                    .setTitle("删除快捷键")
                    .setMessage("确定删除 \"" + config.displayName + "\" 吗？")
                    .setPositiveButton("删除", (dd, ww) -> {
                        configList.remove(position);
                        adapter.notifyItemRemoved(position);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            } else {
                Toast.makeText(this, "至少保留一个快捷键", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 点击编辑监听
        adapter.setOnEditListener(position -> {
            showEditSymbolDialogNew(configList, adapter, position);
        });
        
        // 添加 RecyclerView 到主布局
        mainLayout.addView(recyclerView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        
        // 添加新快捷键按钮
        TextView addBtn = new TextView(this);
        addBtn.setText("+ 添加快捷键");
        addBtn.setTextColor(0xFFD0BCFF);
        addBtn.setTextSize(14);
        addBtn.setPadding(48, 24, 48, 24);
        addBtn.setOnClickListener(v -> showAddSymbolDialogNew(configList, adapter));
        mainLayout.addView(addBtn);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("自定义快捷键")
            .setView(mainLayout)
            .setPositiveButton("保存", (d, w) -> {
                SymbolKeyConfig.saveConfigs(this, configList);
                AppPreferences.setSymbolRows(this, currentRows[0]);
                loadSymbolKeys();
                Toast.makeText(this, "快捷键已保存", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认", (d, w) -> {
                SymbolKeyConfig.resetToDefault(this);
                AppPreferences.setSymbolRows(this, AppPreferences.DEFAULT_SYMBOL_ROWS);
                loadSymbolKeys();
                Toast.makeText(this, "已恢复默认快捷键", Toast.LENGTH_SHORT).show();
            })
            .show();
    }
    
    /**
     * 显示添加快捷键对话框（新版本）
     */
    private void showAddSymbolDialogNew(List<SymbolKeyConfig.KeyConfig> configList, SymbolKeyAdapter adapter) {
        showEditKeyConfigDialog(null, config -> {
            configList.add(config);
            adapter.notifyItemInserted(configList.size() - 1);
        });
    }
    
    /**
     * 显示编辑快捷键对话框（新版本）
     */
    private void showEditSymbolDialogNew(List<SymbolKeyConfig.KeyConfig> configList, SymbolKeyAdapter adapter, int position) {
        SymbolKeyConfig.KeyConfig currentConfig = configList.get(position);
        showEditKeyConfigDialog(currentConfig, config -> {
            configList.set(position, config);
            adapter.notifyItemChanged(position);
        });
    }
    
    /**
     * 显示编辑快捷键配置对话框
     */
    private void showEditKeyConfigDialog(SymbolKeyConfig.KeyConfig existingConfig, java.util.function.Consumer<SymbolKeyConfig.KeyConfig> onSave) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 0);
        
        // 显示名称输入
        TextInputLayout nameLayout = new TextInputLayout(this, null, 
            com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("显示名称");
        nameLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        TextInputEditText nameInput = new TextInputEditText(nameLayout.getContext());
        nameInput.setMaxLines(1);
        if (existingConfig != null) {
            nameInput.setText(existingConfig.displayName);
        }
        nameLayout.addView(nameInput);
        layout.addView(nameLayout);
        
        // 事件类型选择
        TextView actionLabel = new TextView(this);
        actionLabel.setText("触发事件");
        actionLabel.setTextSize(14);
        actionLabel.setPadding(0, 24, 0, 8);
        layout.addView(actionLabel);
        
        String[] actionTypes = SymbolKeyConfig.getActionTypes();
        String[] actionNames = SymbolKeyConfig.getActionDisplayNames();
        final int[] selectedAction = {0};
        
        if (existingConfig != null) {
            for (int i = 0; i < actionTypes.length; i++) {
                if (actionTypes[i].equals(existingConfig.action)) {
                    selectedAction[0] = i;
                    break;
                }
            }
        }
        
        com.google.android.material.chip.ChipGroup chipGroup = new com.google.android.material.chip.ChipGroup(this);
        chipGroup.setSingleSelection(true);
        chipGroup.setSelectionRequired(true);
        
        // 参数输入（根据事件类型显示/隐藏）
        TextInputLayout paramLayout = new TextInputLayout(this, null, 
            com.google.android.material.R.attr.textInputOutlinedStyle);
        paramLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        LinearLayout.LayoutParams paramParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        paramParams.topMargin = 16;
        paramLayout.setLayoutParams(paramParams);
        
        TextInputEditText paramInput = new TextInputEditText(paramLayout.getContext());
        paramInput.setMaxLines(1);
        paramLayout.addView(paramInput);
        
        // 更新参数输入框的显示
        Runnable updateParamVisibility = () -> {
            String action = actionTypes[selectedAction[0]];
            if (SymbolKeyConfig.ACTION_INSERT.equals(action)) {
                paramLayout.setVisibility(View.VISIBLE);
                paramLayout.setHint("插入字符");
            } else if (SymbolKeyConfig.ACTION_SHELL.equals(action)) {
                paramLayout.setVisibility(View.VISIBLE);
                paramLayout.setHint("Shell 命令 (支持 ${FILE}, ${DIR}, ${PROJECT})");
            } else {
                paramLayout.setVisibility(View.GONE);
            }
        };
        
        for (int i = 0; i < actionNames.length; i++) {
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
            chip.setText(actionNames[i]);
            chip.setCheckable(true);
            chip.setChecked(i == selectedAction[0]);
            final int index = i;
            chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedAction[0] = index;
                    updateParamVisibility.run();
                }
            });
            chipGroup.addView(chip);
        }
        
        layout.addView(chipGroup);
        layout.addView(paramLayout);
        
        // 初始化参数值
        if (existingConfig != null && existingConfig.param != null) {
            paramInput.setText(existingConfig.param);
        }
        updateParamVisibility.run();
        
        new MaterialAlertDialogBuilder(this)
            .setTitle(existingConfig == null ? "添加快捷键" : "编辑快捷键")
            .setView(layout)
            .setPositiveButton("保存", (d, w) -> {
                String displayName = nameInput.getText().toString().trim();
                if (displayName.isEmpty()) {
                    Toast.makeText(this, "请输入显示名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                String action = actionTypes[selectedAction[0]];
                String param = paramInput.getText().toString();
                
                // 对于插入字符，如果参数为空则使用显示名称
                if (SymbolKeyConfig.ACTION_INSERT.equals(action) && param.isEmpty()) {
                    param = displayName;
                }
                
                onSave.accept(new SymbolKeyConfig.KeyConfig(displayName, action, param));
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 快捷键列表适配器
     */
    private class SymbolKeyAdapter extends RecyclerView.Adapter<SymbolKeyAdapter.ViewHolder> {
        private List<SymbolKeyConfig.KeyConfig> configs;
        private androidx.recyclerview.widget.ItemTouchHelper touchHelper;
        private java.util.function.IntConsumer deleteListener;
        private java.util.function.IntConsumer editListener;
        
        SymbolKeyAdapter(List<SymbolKeyConfig.KeyConfig> configs) {
            this.configs = configs;
        }
        
        void setTouchHelper(androidx.recyclerview.widget.ItemTouchHelper helper) {
            this.touchHelper = helper;
        }
        
        void setOnDeleteListener(java.util.function.IntConsumer listener) {
            this.deleteListener = listener;
        }
        
        void setOnEditListener(java.util.function.IntConsumer listener) {
            this.editListener = listener;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_symbol_key, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SymbolKeyConfig.KeyConfig config = configs.get(position);
            holder.tvSymbol.setText(config.displayName);
            holder.tvDesc.setText(config.getActionDisplayName());
            
            // 拖动手柄
            holder.ivDragHandle.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN && touchHelper != null) {
                    touchHelper.startDrag(holder);
                }
                return false;
            });
            
            // 点击编辑
            holder.itemView.setOnClickListener(v -> {
                if (editListener != null) {
                    editListener.accept(holder.getAdapterPosition());
                }
            });
            
            // 长按删除
            holder.itemView.setOnLongClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.accept(holder.getAdapterPosition());
                }
                return true;
            });
        }
        
        @Override
        public int getItemCount() {
            return configs.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivDragHandle;
            TextView tvSymbol;
            TextView tvDesc;
            
            ViewHolder(View itemView) {
                super(itemView);
                ivDragHandle = itemView.findViewById(R.id.iv_drag_handle);
                tvSymbol = itemView.findViewById(R.id.tv_symbol);
                tvDesc = itemView.findViewById(R.id.tv_desc);
            }
        }
    }

    private void loadDirectory(File dir) {
        if (dir == null) return;
        
        String path = dir.getAbsolutePath();
        
        // 检查是否存在（支持 root）
        if (!RootFileUtils.exists(path)) return;
        
        // 获取规范化路径，解决 "../" 问题
        try {
            dir = dir.getCanonicalFile();
            path = dir.getAbsolutePath();
        } catch (IOException e) {
            dir = dir.getAbsoluteFile();
        }
        
        // 项目模式下，限制不能导航到项目根目录之外
        if (currentProjectPath != null) {
            if (!path.startsWith(currentProjectPath)) {
                // 尝试导航到项目外，阻止并提示
                Toast.makeText(this, "已到达项目根目录", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        currentDir = dir;
        
        // 更新项目根目录

        
        // 只有在非编辑器模式下才自动检测项目
        if (!isEditorMode) {
            // 检测是否进入了 Vkyra 项目目录
            if (RecentProjectManager.isAkodeProject(dir)) {
                // 自动切换到项目模式
                if (currentProjectPath == null || !currentProjectPath.equals(path)) {
                    currentProjectPath = path;
                    RecentProjectManager.updateLastOpenTime(path);
                }
            } else if (currentProjectPath == null) {
                // 只有在非项目模式下才检查是否进入了项目子目录
                File projectRoot = findProjectRoot(dir);
                if (projectRoot != null) {
                    currentProjectPath = projectRoot.getAbsolutePath();
                }
            }
        }
        
        // 显示路径（项目模式下显示相对路径）
        if (currentProjectPath != null && path.startsWith(currentProjectPath)) {
            String relativePath = path.substring(currentProjectPath.length());
            if (relativePath.isEmpty()) {
                relativePath = "/";
            }
            File projectDir = new File(currentProjectPath);
            tvCurrentPath.setText(projectDir.getName() + relativePath);
        } else {
            tvCurrentPath.setText(path);
        }
        
        // 使用 RootFileUtils 列出文件（自动判断是否需要 root）
        File[] files = RootFileUtils.listFiles(path);
        
        // 判断是否显示返回上级目录
        File parentDir = dir.getParentFile();
        boolean canGoUp = parentDir != null;
        
        // 项目模式下，如果已经在项目根目录，不显示返回上级
        if (currentProjectPath != null && path.equals(currentProjectPath)) {
            canGoUp = false;
            parentDir = null;
        }
        
        fileAdapter.setFiles(files, canGoUp, parentDir);
        
        // 更新 Git 菜单状态（会初始化 gitManager）
        updateGitMenu();
        
        // 更新文件列表的 Git 状态
        updateFileListGitStatus();
    }
    
    /**
     * 向上查找项目根目录（包含 .akode 的目录）
     */
    private File findProjectRoot(File dir) {
        File current = dir;
        int maxDepth = 10; // 最多向上查找10层
        
        while (current != null && maxDepth > 0) {
            if (RecentProjectManager.isAkodeProject(current)) {
                return current;
            }
            current = current.getParentFile();
            maxDepth--;
        }
        return null;
    }
    
    // ========== Git 相关方法 ==========
    
    /**
     * 更新 Git 菜单显示
     */
    private void updateGitMenu() {
        if (gitMenuItem == null) return;
        
        // 检查当前目录是否是 Git 仓库
        if (currentDir != null && GitManager.isGitRepository(currentDir) || 
            GitManager.findGitRoot(currentDir) != null) {
            
            // 初始化 Git 管理器
            if (gitManager == null) {
                gitManager = new GitManager(this);
            }
            
            if (gitManager.open(currentDir)) {
                gitMenuItem.setVisible(true);
                gitManager.getCurrentBranchAsync(gitUiCallback(branch -> {
                    if (branch != null) gitMenuItem.setTitle(branch);
                }));
                // 根据主题设置图标颜色
                android.graphics.drawable.Drawable icon = gitMenuItem.getIcon();
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setTint(currentThemeIsDark ? 0xFFE6E1E5 : 0xFF1E1E1E);
                    gitMenuItem.setIcon(icon);
                }
            } else {
                gitMenuItem.setVisible(false);
            }
        } else {
            gitMenuItem.setVisible(false);
            if (gitManager != null) {
                gitManager.close();
            }
        }
        
        // 更新工具栏布局（响应式）
        updateToolbarLayout();
    }
    
    /**
     * 更新文件列表的 Git 状态
     */
    private <T> GitManager.GitCallback<T> gitUiCallback(java.util.function.Consumer<T> success) {
        return new GitManager.GitCallback<T>() {
            @Override public void onSuccess(T result) { if (!isDestroyed()) success.accept(result); }
            @Override public void onError(String error) {
                if (!isDestroyed()) Toast.makeText(EditorActivity.this, "Git 操作失败: " + error, Toast.LENGTH_LONG).show();
            }
        };
    }

    private void updateFileListGitStatus() {
        if (gitManager != null && gitManager.getRepoDir() != null) {
            File repository = gitManager.getRepoDir();
            gitManager.getStatusAsync(gitUiCallback(status -> fileAdapter.setGitStatus(repository, status)));
        } else fileAdapter.clearGitStatus();
    }

    /**
     * 显示 Git 操作对话框
     */
    private void showGitDialog() {
        if (gitManager == null || gitManager.getRepoDir() == null) {
            Toast.makeText(this, "当前不在 Git 仓库中", Toast.LENGTH_SHORT).show();
            return;
        }
        
        gitManager.getCurrentBranchAsync(gitUiCallback(branch ->
                gitManager.getStatusAsync(gitUiCallback(status -> showGitDialog(branch, status)))));
    }

    private void showGitDialog(String branch, GitManager.GitStatus status) {
        String[] items = {
            "📊 查看状态 (" + status.getTotalChanges() + " 个变更)",
            "➕ 暂存全部",
            "📝 提交",
            "⬇️ 拉取 (Pull)",
            "⬆️ 推送 (Push)",
            "🌿 切换分支 (" + branch + ")",
            "↩️ 放弃当前文件修改"
        };
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Git 操作")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: showGitStatus(); break;
                    case 1: gitStageAll(); break;
                    case 2: showCommitDialog(); break;
                    case 3: gitPull(); break;
                    case 4: gitPush(); break;
                    case 5: showBranchDialog(); break;
                    case 6: gitDiscardCurrentFile(); break;
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }
    
    private void showGitStatus() {
        gitManager.getCurrentBranchAsync(gitUiCallback(branch ->
                gitManager.getStatusAsync(gitUiCallback(status -> showGitStatus(branch, status)))));
    }

    private void showGitStatus(String branch, GitManager.GitStatus status) {
        
        StringBuilder sb = new StringBuilder();
        
        if (!status.staged.isEmpty()) {
            sb.append("已暂存 (").append(status.staged.size()).append("):\n");
            for (String f : status.staged) {
                sb.append("  ✓ ").append(f).append("\n");
            }
            sb.append("\n");
        }
        
        if (!status.modified.isEmpty()) {
            sb.append("已修改 (").append(status.modified.size()).append("):\n");
            for (String f : status.modified) {
                sb.append("  M ").append(f).append("\n");
            }
            sb.append("\n");
        }
        
        if (!status.untracked.isEmpty()) {
            sb.append("未跟踪 (").append(status.untracked.size()).append("):\n");
            for (String f : status.untracked) {
                sb.append("  + ").append(f).append("\n");
            }
            sb.append("\n");
        }
        
        if (!status.conflicting.isEmpty()) {
            sb.append("冲突 (").append(status.conflicting.size()).append("):\n");
            for (String f : status.conflicting) {
                sb.append("  ! ").append(f).append("\n");
            }
        }
        
        if (sb.length() == 0) {
            sb.append("工作区干净，没有变更");
        }
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Git 状态 - " + branch)
            .setMessage(sb.toString().trim())
            .setPositiveButton("确定", null)
            .show();
    }
    
    private void gitStageAll() {
        gitManager.stageAll(new GitManager.GitCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(EditorActivity.this, "已暂存所有文件", Toast.LENGTH_SHORT).show();
                refreshFileList();
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(EditorActivity.this, "暂存失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void showCommitDialog() {
        com.google.android.material.textfield.TextInputLayout inputLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("提交信息");
        inputLayout.setPadding(48, 32, 48, 0);
        
        com.google.android.material.textfield.TextInputEditText input = 
            new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
        inputLayout.addView(input);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Git 提交")
            .setView(inputLayout)
            .setPositiveButton("提交", (d, w) -> {
                String message = input.getText().toString().trim();
                if (message.isEmpty()) {
                    Toast.makeText(this, "请输入提交信息", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                gitManager.commit(message, new GitManager.GitCallback<String>() {
                    @Override
                    public void onSuccess(String commitId) {
                        Toast.makeText(EditorActivity.this, "提交成功: " + commitId, Toast.LENGTH_SHORT).show();
                        updateGitMenu();
                        refreshFileList();
                    }
                    
                    @Override
                    public void onError(String error) {
                        Toast.makeText(EditorActivity.this, "提交失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void gitPull() {
        Toast.makeText(this, "正在拉取...", Toast.LENGTH_SHORT).show();
        
        gitManager.pull(new GitManager.GitCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Toast.makeText(EditorActivity.this, "拉取成功: " + result, Toast.LENGTH_SHORT).show();
                refreshFileList();
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(EditorActivity.this, "拉取失败: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private void gitPush() {
        gitManager.hasRemoteAsync(gitUiCallback(hasRemote -> {
            if (!hasRemote) showAddRemoteDialog();
            else doGitPush();
        }));
    }

    private void doGitPush() {
        Toast.makeText(this, "正在推送...", Toast.LENGTH_SHORT).show();
        
        gitManager.push(new GitManager.GitCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(EditorActivity.this, "推送成功", Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onError(String error) {
                Toast.makeText(EditorActivity.this, "推送失败: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }
    
    /**
     * 显示添加 Remote 对话框
     */
    private void showAddRemoteDialog() {
        com.google.android.material.textfield.TextInputLayout inputLayout = 
            new com.google.android.material.textfield.TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(48, 32, 48, 0);
        inputLayout.setHint("远程仓库 URL");
        inputLayout.setHelperText("例如: https://github.com/user/repo.git");
        inputLayout.setHelperTextEnabled(true);
        
        com.google.android.material.textfield.TextInputEditText input = 
            new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
        inputLayout.addView(input);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("添加远程仓库")
            .setMessage("当前仓库没有设置远程仓库，请添加一个远程仓库地址以便推送代码。")
            .setView(inputLayout)
            .setPositiveButton("添加", (d, w) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(this, "请输入远程仓库 URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                gitManager.addRemote("origin", url, new GitManager.GitCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Toast.makeText(EditorActivity.this, "远程仓库添加成功", Toast.LENGTH_SHORT).show();
                        // 添加成功后自动推送
                        gitPush();
                    }
                    
                    @Override
                    public void onError(String error) {
                        Toast.makeText(EditorActivity.this, "添加失败: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void showBranchDialog() {
        gitManager.getCurrentBranchAsync(gitUiCallback(current ->
                gitManager.getBranchesAsync(gitUiCallback(branches -> showBranchDialog(current, branches)))));
    }

    private void showBranchDialog(String currentBranch, java.util.List<String> branches) {
        
        String[] items = branches.toArray(new String[0]);
        int checkedIndex = branches.indexOf(currentBranch);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("切换分支")
            .setSingleChoiceItems(items, checkedIndex, (dialog, which) -> {
                String selectedBranch = branches.get(which);
                if (!selectedBranch.equals(currentBranch)) {
                    gitManager.checkout(selectedBranch, new GitManager.GitCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            Toast.makeText(EditorActivity.this, "已切换到 " + selectedBranch, Toast.LENGTH_SHORT).show();
                            updateGitMenu();
                            refreshFileList();
                        }
                        
                        @Override
                        public void onError(String error) {
                            Toast.makeText(EditorActivity.this, "切换失败: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void gitDiscardCurrentFile() {
        if (currentFile == null || gitManager == null) return;
        
        String relativePath = gitManager.getRelativePath(currentFile);
        if (relativePath == null) {
            Toast.makeText(this, "文件不在仓库中", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("放弃修改")
            .setMessage("确定要放弃对 " + currentFile.getName() + " 的所有修改吗？\n\n此操作不可撤销！")
            .setPositiveButton("放弃修改", (d, w) -> {
                gitManager.discardChanges(relativePath, new GitManager.GitCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Toast.makeText(EditorActivity.this, "已放弃修改", Toast.LENGTH_SHORT).show();
                        // 重新加载文件
                        doOpenFile(currentFile);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Toast.makeText(EditorActivity.this, "操作失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void refreshFileList() {
        if (currentDir != null) {
            loadDirectory(currentDir);
        }
    }

    private void openFile(File file) {
        if (isModified) {
            new MaterialAlertDialogBuilder(this)
                .setTitle("保存更改")
                .setMessage("当前文件已修改，是否保存？")
                .setPositiveButton("保存", (d, w) -> { saveFile(() -> doOpenFile(file)); })
                .setNegativeButton("不保存", (d, w) -> doOpenFile(file))
                .setNeutralButton("取消", null)
                .show();
        } else {
            doOpenFile(file);
        }
    }

    private void doOpenFile(File file) {
        doOpenFile(file, null);
    }

    private void doOpenFile(File file, Runnable afterOpen) {
        saveCurrentFileState();
        for (int i = 0; i < openedFiles.size(); i++) {
            if (file.getAbsolutePath().equals(openedFiles.get(i).filePath)) {
                displayDocument(openedFiles.get(i));
                if (afterOpen != null) afterOpen.run();
                return;
            }
        }
        if (openedFiles.size() >= MAX_OPENED_FILES) {
            Toast.makeText(this, "请先关闭一个标签，再打开其他文件", Toast.LENGTH_LONG).show();
            return;
        }
        long request = ++openRequestVersion;
        editorSession.executeIo(() -> {
            try {
                String content = RootFileUtils.readFile(file.getAbsolutePath());
                documentHandler.post(() -> {
                    if (isDestroyed() || request != openRequestVersion) return;
                    saveCurrentFileState();
                    addToOpenedFiles(file, content);
                    displayDocument(editorSession.activeDocument());
                    if (AppPreferences.getRememberLastFile(this)) {
                        AppPreferences.setLastFilePath(this, file.getAbsolutePath());
                        if (file.getParentFile() != null) AppPreferences.setLastDirPath(this, file.getParent());
                    }
                    if (currentProjectPath != null) {
                        editorSession.executeIo(() -> RecentProjectManager.updateCurrentFile(currentProjectPath, file.getAbsolutePath()));
                    }
                    editorSession.persist();
                    if (afterOpen != null) afterOpen.run();
                });
            } catch (IOException error) {
                documentHandler.post(() -> {
                    if (isDestroyed() || request != openRequestVersion) return;
                    Toast.makeText(this, "打开文件失败: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    if (editorSession.activeDocument() == null) createNewFile();
                });
            }
        });
    }
    
    /**
     * 根据文件名设置编辑器语言
     */
    private void setEditorLanguage(String fileName) {
        if (fileName == null) {
            CppLanguage cppLang = new CppLanguage(this);
            setupCppLanguageWithClangd(cppLang);
            codeEditor.setEditorLanguage(cppLang);
            return;
        }
        
        String lowerName = fileName.toLowerCase();
        
        if (lowerName.endsWith(".json")) {
            codeEditor.setEditorLanguage(new JsonLanguage());
        } else if (lowerName.endsWith(".mk") || lowerName.equals("makefile") || 
                   lowerName.equals("gnumakefile") || lowerName.endsWith(".make")) {
            codeEditor.setEditorLanguage(new MakefileLanguage());
        } else {
            // 默认使用 C++ 语言（支持 .c, .cpp, .h, .hpp 等）
            CppLanguage cppLang = new CppLanguage(this);
            setupCppLanguageWithClangd(cppLang);
            codeEditor.setEditorLanguage(cppLang);
        }
    }
    
    /**
     * 设置 CppLanguage 的 clangd 客户端
     */
    private void setupCppLanguageWithClangd(CppLanguage cppLang) {
        if (useClangd && clangdClient != null) {
            cppLang.setClangdClient(clangdClient);
            if (currentFile != null) {
                cppLang.setCurrentFilePath(currentFile.getAbsolutePath());
            }
        }
    }

    /** Every build consumes the saved workspace, including inactive files changed by LSP. */
    private boolean saveDirtyDocumentsBefore(Runnable continuation) {
        final String requestedDocumentId = editorSession.activeDocumentId;
        Runnable restoreRequest = () -> {
            if (isDestroyed()) return;
            for (int i = 0; i < openedFiles.size(); i++) {
                if (openedFiles.get(i).id.equals(requestedDocumentId)) {
                    switchToFile(i);
                    continuation.run();
                    return;
                }
            }
            Toast.makeText(this, "原文件已关闭，请重新选择构建文件", Toast.LENGTH_LONG).show();
        };
        return saveDirtyDocumentsSequentially(restoreRequest);
    }

    private boolean saveDirtyDocumentsSequentially(Runnable continuation) {
        saveCurrentFileState();
        for (EditorDocument document : openedFiles) {
            if (!document.isModified) continue;
            if (document.filePath == null) {
                switchToFile(openedFiles.indexOf(document));
                showSaveAsDialog(() -> { if (!saveDirtyDocumentsSequentially(continuation)) continuation.run(); });
            } else {
                writeDocument(document, new File(document.filePath), document.content,
                        () -> { if (!saveDirtyDocumentsSequentially(continuation)) continuation.run(); }, null);
            }
            return true;
        }
        return false;
    }

    private void saveFile() { saveFile(null); }

    private void saveFile(Runnable afterSaved) {
        saveCurrentFileState();
        if (currentFile == null || (currentFile.getName().equals("untitled.cpp")
                && currentFile.getParentFile().equals(getFilesDir()))) {
            showSaveAsDialog(afterSaved);
        } else {
            EditorDocument document = editorSession.activeDocument();
            if (document != null) writeDocument(document, currentFile, document.content, afterSaved, null);
        }
    }
    
    private void doSaveFile() { saveFile(); }

    private void writeDocument(EditorDocument document, File target, String content,
            Runnable afterSaved, java.util.function.Consumer<String> onFailure) {
        editorSession.executeIo(() -> {
            try {
                RootFileUtils.writeFile(target.getAbsolutePath(), content);
                documentHandler.post(() -> {
                    if (isDestroyed()) return;
                    saveCurrentFileState();
                    boolean unchanged = content.equals(document.content);
                    String previousPath = document.filePath;
                    boolean pathChanged = !target.getAbsolutePath().equals(previousPath);
                    if (pathChanged && clangdClient != null && previousPath != null) {
                        clangdClient.closeDocument(previousPath);
                    }
                    document.filePath = target.getAbsolutePath();
                    document.isModified = !unchanged;
                    if (editorSession.activeDocument() == document) {
                        currentFile = target;
                        currentDir = target.getParentFile();
                        isModified = !unchanged;
                        markCurrentFileSynced();
                        setEditorLanguage(target.getName());
                        updateTitle();
                        if (pathChanged) notifyClangdFileOpened();
                    }
                    editorSession.persist();
                    Toast.makeText(this, unchanged ? "已保存: " + target.getName() : "已保存快照，仍有新的更改", Toast.LENGTH_SHORT).show();
                    if (unchanged && afterSaved != null) afterSaved.run();
                    else if (!unchanged && onFailure != null) onFailure.accept("保存期间有新的更改，请再次保存");
                });
            } catch (IOException error) {
                documentHandler.post(() -> {
                    if (isDestroyed()) return;
                    Toast.makeText(this, "保存失败: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    if (onFailure != null) onFailure.accept(error.getMessage());
                });
            }
        });
    }

    /**
     * 查找项目中合适的欢迎文件
     * 优先级：README.md > CMakeLists.txt > Makefile > main.cpp/main.c > 其他源文件
     */
    private File findWelcomeFile(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            return null;
        }
        
        // 优先级列表
        String[] priorityFiles = {
            "README.md", "readme.md", "Readme.md", "README.MD",
            "README.txt", "readme.txt",
            "README", "readme",
            "CMakeLists.txt",
            "Makefile", "makefile",
            "main.cpp", "main.c", "main.cc", "main.cxx",
            "index.cpp", "index.c"
        };
        
        // 按优先级查找
        for (String fileName : priorityFiles) {
            File file = new File(projectDir, fileName);
            if (file.exists() && file.isFile()) {
                return file;
            }
        }
        
        // 如果没找到，尝试在 src 目录查找 main 文件
        File srcDir = new File(projectDir, "src");
        if (srcDir.exists() && srcDir.isDirectory()) {
            for (String fileName : new String[]{"main.cpp", "main.c", "main.cc"}) {
                File file = new File(srcDir, fileName);
                if (file.exists() && file.isFile()) {
                    return file;
                }
            }
        }
        
        // 最后尝试找任意 .cpp 或 .c 文件
        File[] files = projectDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".cpp") || name.endsWith(".c") || 
                        name.endsWith(".cc") || name.endsWith(".cxx") ||
                        name.endsWith(".h") || name.endsWith(".hpp")) {
                        return file;
                    }
                }
            }
        }
        
        return null;
    }

    private void createNewFile() {
        saveCurrentFileState();
        if (openedFiles.size() >= MAX_OPENED_FILES) {
            Toast.makeText(this, "请先关闭一个标签", Toast.LENGTH_LONG).show();
            return;
        }
        String content = "#include <stdio.h>\n\nint main() {\n    printf(\"Hello, Vkyra!\\n\");\n    return 0;\n}\n";
        addToOpenedFiles(null, content);
        EditorDocument document = editorSession.activeDocument();
        document.isModified = true;
        displayDocument(document);
        editorSession.persist();
    }
    
    /**
     * 创建带默认内容的 untitled.cpp
     */


    /**
     * 记录当前文件的磁盘同步状态
     */
    private void markCurrentFileSynced() {
        if (currentFile != null && currentFile.exists()) {
            lastSyncedFilePath = currentFile.getAbsolutePath();
            lastSyncedFileModified = currentFile.lastModified();
        } else {
            lastSyncedFilePath = null;
            lastSyncedFileModified = -1L;
        }
    }

    /**
     * 检测当前文件是否被外部修改（后台线程读取，主线程弹窗）
     */
    private void checkCurrentFileExternalChanges() {
        if (currentFile == null || !currentFile.exists() || externalChangeDialogShowing) {
            return;
        }
        // 当当前编辑器存在未保存变更时，不对“磁盘内容变化”做外部修改提示（避免误判）
        if (isModified) return;

        final File checkingFile = currentFile;
        final String checkingPath = checkingFile.getAbsolutePath();
        final long syncedModified = (checkingPath.equals(lastSyncedFilePath)) ? lastSyncedFileModified : -1L;
        final String editorContent = codeEditor.getText().toString();

        new Thread(() -> {
            try {
                long diskModified = checkingFile.lastModified();
                String diskContent = RootFileUtils.readFile(checkingPath);

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (currentFile == null || !checkingPath.equals(currentFile.getAbsolutePath())) return;

                    boolean contentDifferent = !editorContent.equals(diskContent);
                    if (!contentDifferent) {
                        // 内容一致，更新时间戳快照
                        lastSyncedFilePath = checkingPath;
                        lastSyncedFileModified = diskModified;
                        return;
                    }

                    // 有快照时，只有磁盘时间戳变化才判定为外部修改冲突
                    if (syncedModified > 0 && diskModified == syncedModified) {
                        return;
                    }

                    // 首次没有快照时，先建立快照，避免首次进入就误报
                    if (syncedModified <= 0) {
                        lastSyncedFilePath = checkingPath;
                        lastSyncedFileModified = diskModified;
                        return;
                    }

                    showExternalFileChangedDialog(diskContent, diskModified);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    /**
     * 外部变更冲突对话框：保留编辑器内容 / 使用磁盘内容 / 另存副本
     */
    private void showExternalFileChangedDialog(String diskContent, long diskModified) {
        if (externalChangeDialogShowing || currentFile == null) return;
        externalChangeDialogShowing = true;

        String fileName = currentFile.getName();
        new MaterialAlertDialogBuilder(this)
            .setTitle("检测到外部修改")
            .setMessage("文件 " + fileName + " 已被外部修改。\n\n请选择处理方式：\n- 保留当前编辑器内容\n- 使用外部修改后的内容\n- 将当前编辑器内容另存为副本")
            .setPositiveButton("使用磁盘变更", (d, w) -> {
                codeEditor.setText(diskContent);
                isModified = false;
                updateTitle();
                saveCurrentFileState();
                lastSyncedFilePath = currentFile.getAbsolutePath();
                lastSyncedFileModified = diskModified;
                externalChangeDialogShowing = false;
            })
            .setNegativeButton("保留编辑器内容", (d, w) -> {
                isModified = true;
                updateTitle();
                saveCurrentFileState();
                // 用户已确认保留，更新快照避免重复弹窗
                if (currentFile != null) {
                    lastSyncedFilePath = currentFile.getAbsolutePath();
                    lastSyncedFileModified = diskModified;
                }
                externalChangeDialogShowing = false;
            })
            .setNeutralButton("另存为副本", (d, w) -> {
                isModified = true;
                updateTitle();
                saveCurrentFileState();
                if (currentFile != null) {
                    lastSyncedFilePath = currentFile.getAbsolutePath();
                    lastSyncedFileModified = diskModified;
                }
                externalChangeDialogShowing = false;
                showSaveAsDialog();
            })
            .setOnDismissListener(dialog -> externalChangeDialogShowing = false)
            .show();
    }

    private void updateTitle() {
        String title = currentFile != null ? currentFile.getName() : "untitled.cpp";
        if (isModified) title = "* " + title;
        tvTitle.setText(title);
        
        // 如果有多个文件打开，显示下拉箭头
        if (openedFiles.size() > 1) {
            if (ivDropdown.getVisibility() != View.VISIBLE) {
                ivDropdown.setRotation(0f); // 新显示时重置为向下
                ivDropdown.setVisibility(View.VISIBLE);
            }
        } else {
            ivDropdown.setVisibility(View.GONE);
        }
    }

    private void showNewFileDialog() {
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("文件名");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(48, 32, 48, 0);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setText("main.cpp");
        input.setSelection(0, 4);
        inputLayout.addView(input);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("新建文件")
            .setView(inputLayout)
            .setPositiveButton("创建", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    File newFile = new File(currentDir, name);
                    try {
                        if (newFile.createNewFile()) {
                            loadDirectory(currentDir);
                            openFile(newFile);
                        }
                    } catch (IOException e) {
                        Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showNewFolderDialog() {
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("文件夹名");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(48, 32, 48, 0);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        inputLayout.addView(input);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("新建文件夹")
            .setView(inputLayout)
            .setPositiveButton("创建", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    File newDir = new File(currentDir, name);
                    if (newDir.mkdir()) {
                        loadDirectory(currentDir);
                    } else {
                        Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showSaveAsDialog() { showSaveAsDialog(null); }

    private void showSaveAsDialog(Runnable afterSaved) {
        saveCurrentFileState();
        final EditorDocument savingDocument = editorSession.activeDocument();
        if (savingDocument == null) return;
        // 创建主布局
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(48, 32, 48, 0);

        // 文件夹选择区域
        LinearLayout folderLayout = new LinearLayout(this);
        folderLayout.setOrientation(LinearLayout.HORIZONTAL);
        folderLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        folderLayout.setPadding(0, 0, 0, 24);

        TextView folderLabel = new TextView(this);
        folderLabel.setText("保存到: ");
        folderLabel.setTextColor(0xFFAAAAAA);
        folderLabel.setTextSize(14);

        TextView folderPath = new TextView(this);
        folderPath.setText(currentDir.getAbsolutePath());
        folderPath.setTextColor(0xFFCCCCCC);
        folderPath.setTextSize(14);
        folderPath.setSingleLine(true);
        folderPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        folderPath.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton browseBtn = new ImageButton(this);
        browseBtn.setImageResource(R.drawable.ic_folder);
        browseBtn.setBackgroundResource(android.R.color.transparent);
        browseBtn.setColorFilter(0xFF9E9E9E);
        browseBtn.setPadding(16, 0, 0, 0);

        folderLayout.addView(folderLabel);
        folderLayout.addView(folderPath);
        folderLayout.addView(browseBtn);
        mainLayout.addView(folderLayout);

        // 文件名输入框
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("文件名");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        String defaultName = currentFile != null ? currentFile.getName() : "main.cpp";
        input.setText(defaultName);
        int dotIndex = defaultName.lastIndexOf('.');
        if (dotIndex > 0) {
            input.setSelection(0, dotIndex);
        } else {
            input.setSelection(0, defaultName.length());
        }
        inputLayout.addView(input);
        mainLayout.addView(inputLayout);

        // 保存目标目录
        final File[] targetDir = {currentDir};

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("另存为")
            .setView(mainLayout)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
                        inputLayout.setError("请输入文件名，不能包含路径");
                        return;
                    }
                    File destination = new File(targetDir[0], name);
                    Runnable commit = () -> {
                        saveCurrentFileState();
                        String content = savingDocument.content;
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        writeDocument(savingDocument, destination, content, () -> {
                            dialog.dismiss();
                            loadDirectory(currentDir);
                            if (afterSaved != null) afterSaved.run();
                        }, error -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true));
                    };
                    if (destination.exists() && !destination.getAbsolutePath().equals(savingDocument.filePath)) {
                        new MaterialAlertDialogBuilder(this).setTitle("覆盖已有文件？")
                            .setMessage(destination.getName()).setPositiveButton("覆盖", (confirm, which) -> commit.run())
                            .setNegativeButton("取消", null).show();
                    } else commit.run();
                }
            });
        });

        // 文件夹选择按钮点击
        browseBtn.setOnClickListener(v -> showFolderPickerDialog(targetDir, folderPath));
        folderPath.setOnClickListener(v -> showFolderPickerDialog(targetDir, folderPath));

        dialog.show();
    }

    private void showFolderPickerDialog(File[] targetDir, TextView folderPathView) {
        final File[] browseDir = {targetDir[0]};

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // 当前路径显示
        TextView pathText = new TextView(this);
        pathText.setPadding(48, 24, 48, 16);
        pathText.setTextColor(0xFFCCCCCC);
        pathText.setTextSize(14);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathText.setText(browseDir[0].getAbsolutePath());
        layout.addView(pathText);

        // 文件夹列表
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 600));
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        layout.addView(recyclerView);

        // 简单的文件夹适配器
        List<File> folders = new ArrayList<>();
        Runnable refreshFolders = () -> {
            folders.clear();
            File parent = browseDir[0].getParentFile();
            if (parent != null) {
                folders.add(parent); // 用于返回上级
            }
            File[] files = browseDir[0].listFiles();
            if (files != null) {
                List<File> sortedFolders = new ArrayList<>();
                for (File f : files) {
                    if (f.isDirectory() && !f.getName().startsWith(".")) {
                        sortedFolders.add(f);
                    }
                }
                java.util.Collections.sort(sortedFolders, (a, b) -> 
                    a.getName().compareToIgnoreCase(b.getName()));
                folders.addAll(sortedFolders);
            }
            recyclerView.getAdapter().notifyDataSetChanged();
            pathText.setText(browseDir[0].getAbsolutePath());
        };

        recyclerView.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                TextView tv = new TextView(EditorActivity.this);
                tv.setPadding(48, 24, 48, 24);
                tv.setTextSize(15);
                tv.setTextColor(0xFFE0E0E0);
                tv.setCompoundDrawablePadding(24);
                return new RecyclerView.ViewHolder(tv) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                TextView tv = (TextView) holder.itemView;
                File folder = folders.get(position);
                boolean isParent = position == 0 && browseDir[0].getParentFile() != null 
                    && folder.equals(browseDir[0].getParentFile());
                
                tv.setText(isParent ? ".." : folder.getName());
                tv.setOnClickListener(v -> {
                    browseDir[0] = folder;
                    refreshFolders.run();
                });
            }

            @Override
            public int getItemCount() {
                return folders.size();
            }
        });

        refreshFolders.run();

        new MaterialAlertDialogBuilder(this)
            .setTitle("选择文件夹")
            .setView(layout)
            .setPositiveButton("选择此文件夹", (d, w) -> {
                targetDir[0] = browseDir[0];
                folderPathView.setText(browseDir[0].getAbsolutePath());
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showFileOptionsDialog(File file) {
        // 只有目录才显示头文件目录选项
        if (file.isDirectory()) {
            boolean isHeaderDir = HeaderDirManager.isHeaderDir(this, file.getAbsolutePath());
            String headerOption = isHeaderDir ? "移除头文件目录" : "设为头文件目录";
            
            new MaterialAlertDialogBuilder(this)
                .setTitle(file.getName())
                .setItems(new String[]{"删除", "重命名", headerOption}, (d, which) -> {
                    if (which == 0) {
                        confirmDelete(file);
                    } else if (which == 1) {
                        showRenameDialog(file);
                    } else if (which == 2) {
                        toggleHeaderDir(file, isHeaderDir);
                    }
                })
                .show();
        } else {
            new MaterialAlertDialogBuilder(this)
                .setTitle(file.getName())
                .setItems(new String[]{"删除", "重命名"}, (d, which) -> {
                    if (which == 0) confirmDelete(file);
                    else showRenameDialog(file);
                })
                .show();
        }
    }
    
    /**
     * 切换头文件目录状态
     */
    private void toggleHeaderDir(File dir, boolean isCurrentlyHeader) {
        if (isCurrentlyHeader) {
            HeaderDirManager.removeHeaderDir(this, dir.getAbsolutePath());
            Toast.makeText(this, "已移除头文件目录: " + dir.getName(), Toast.LENGTH_SHORT).show();
        } else {
            HeaderDirManager.addHeaderDir(this, dir.getAbsolutePath());
            Toast.makeText(this, "已设为头文件目录: " + dir.getName(), Toast.LENGTH_SHORT).show();
        }
        // 刷新文件列表显示
        fileAdapter.refreshHeaderDirs();
        // A new server session regenerates only our cache database in its background launcher.
        if (clangdClient != null) { clangdClient.cleanup(); clangdClient = null; }
        useClangd = false;
        clearDiagnostics();
        initClangd();

    }

    private void confirmDelete(File file) {
        if (!canModifyExplorerPath(file)) return;
        String message = "确定要删除 " + file.getName() + " 及其内容吗？";
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage(message)
            .setPositiveButton("删除", (d, w) -> {
                if (canModifyExplorerPath(file)) runExplorerFileOperation(file, null);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 递归删除文件夹及其内容
     */
    private static void deleteRecursive(File fileOrDir) throws IOException {
        com.vkyra.code.files.ProjectFileOperations.deleteRecursively(fileOrDir);
    }

    private void showRenameDialog(File file) {
        if (!canModifyExplorerPath(file)) return;
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("新名称");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(48, 32, 48, 0);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setText(file.getName());
        input.setSelection(input.getText().length());
        inputLayout.addView(input);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(inputLayout)
            .setPositiveButton("确定", (d, w) -> {
                String newName = input.getText().toString().trim();
                if (!com.vkyra.code.files.ProjectFileOperations.isBasename(newName)) {
                    Toast.makeText(this, "请输入单个文件名，不能包含路径或 . / ..", Toast.LENGTH_LONG).show();
                    return;
                }
                if (canModifyExplorerPath(file)) runExplorerFileOperation(file, newName);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private boolean explorerFileOperationRunning;

    private java.util.List<File> protectedExplorerPaths() {
        java.util.List<File> paths = new java.util.ArrayList<>();
        for (EditorDocument document : openedFiles) {
            if (document.filePath != null) paths.add(new File(document.filePath));
        }
        if (currentFile != null) paths.add(currentFile);
        if (currentDir != null) paths.add(currentDir);
        if (currentProjectPath != null) paths.add(new File(currentProjectPath));
        return paths;
    }

    private boolean canModifyExplorerPath(File selected) {
        if (explorerFileOperationRunning) {
            Toast.makeText(this, "正在处理文件，请稍候", Toast.LENGTH_SHORT).show();
            return false;
        }
        for (File protectedPath : protectedExplorerPaths()) {
            if (com.vkyra.code.files.ProjectFileOperations.contains(selected, protectedPath)) {
                new MaterialAlertDialogBuilder(this).setTitle("请先关闭文件或切出目录")
                        .setMessage("请先关闭相关标签并切出此项目或浏览目录，再重命名或删除。未保存的内容会保留。")
                        .setPositiveButton("确定", null).show();
                return false;
            }
        }
        return true;
    }

    /** Only unowned paths are mutated: a detached page never has to migrate live buffers. */
    private void runExplorerFileOperation(File selected, String renameTo) {
        final java.util.List<File> protectedPaths = protectedExplorerPaths();
        final java.lang.ref.WeakReference<EditorActivity> page = new java.lang.ref.WeakReference<>(this);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        androidx.appcompat.app.AlertDialog busy = new MaterialAlertDialogBuilder(this)
                .setTitle("正在处理文件").setMessage("请稍候…").setCancelable(false).create();
        final java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog> busyReference = new java.lang.ref.WeakReference<>(busy);
        androidx.lifecycle.DefaultLifecycleObserver observer = new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                androidx.appcompat.app.AlertDialog dialog = busyReference.get();
                if (dialog != null) dialog.dismiss();
                owner.getLifecycle().removeObserver(this);
            }
        };
        getLifecycle().addObserver(observer);
        busy.setOnDismissListener(dialog -> getLifecycle().removeObserver(observer));
        explorerFileOperationRunning = true;
        busy.show();
        editorSession.executeIo(() -> {
            String failure = null;
            try {
                com.vkyra.code.files.ExplorerFileOperations.execute(selected, renameTo, protectedPaths);
            } catch (Exception error) {
                failure = error instanceof java.nio.file.FileAlreadyExistsException
                        ? "目标名称已存在，原文件未修改" : "文件操作失败：" + error.getMessage();
            }
            final String result = failure;
            main.post(() -> {
                EditorActivity activity = page.get();
                if (activity == null || activity.isDestroyed()) return;
                activity.explorerFileOperationRunning = false;
                androidx.appcompat.app.AlertDialog dialog = busyReference.get();
                if (dialog != null) dialog.dismiss();
                if (result == null) activity.loadDirectory(activity.currentDir);
                else new MaterialAlertDialogBuilder(activity).setTitle("文件操作失败")
                        .setMessage(result).setPositiveButton("确定", null).show();
            });
        });
    }

    // 搜索相关
    private java.util.List<int[]> searchResults = new java.util.ArrayList<>();
    private int currentSearchIndex = -1;
    private String currentSearchQuery = "";
    
    private void showSearchDialog() {
        // 创建搜索布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);
        
        // 搜索结果统计
        TextView countText = new TextView(this);
        countText.setTextSize(13);
        countText.setTextColor(0xFFE57373); // 红色
        countText.setText("搜索结果: 0");
        countText.setPadding(4, 0, 0, 16);
        layout.addView(countText);
        
        // 查找输入框
        TextInputLayout searchLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        searchLayout.setHint("查找");
        searchLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchLayout.setPadding(0, 0, 0, 8);
        
        TextInputEditText searchInput = new TextInputEditText(searchLayout.getContext());
        searchInput.setText(currentSearchQuery);
        if (!currentSearchQuery.isEmpty()) {
            searchInput.setSelection(currentSearchQuery.length());
        }
        searchLayout.addView(searchInput);
        layout.addView(searchLayout);
        
        // 替换输入框（初始隐藏）
        TextInputLayout replaceLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        replaceLayout.setHint("替换");
        replaceLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        replaceLayout.setVisibility(View.GONE);
        
        TextInputEditText replaceInput = new TextInputEditText(replaceLayout.getContext());
        replaceLayout.addView(replaceInput);
        layout.addView(replaceLayout);
        
        // 按钮行
        android.widget.LinearLayout btnLayout = new android.widget.LinearLayout(this);
        btnLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnLayout.setGravity(android.view.Gravity.CENTER);
        btnLayout.setPadding(0, 24, 0, 8);
        
        // 上一个按钮
        com.google.android.material.button.MaterialButton btnPrev = new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnPrev.setText("上个");
        btnPrev.setTextSize(13);
        btnPrev.setEnabled(false);
        btnPrev.setMinWidth(0);
        btnPrev.setMinimumWidth(0);
        btnPrev.setPadding(32, 0, 32, 0);
        android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMargins(0, 0, 8, 0);
        btnPrev.setLayoutParams(btnParams);
        btnLayout.addView(btnPrev);
        
        // 下一个按钮
        com.google.android.material.button.MaterialButton btnNext = new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnNext.setText("下个");
        btnNext.setTextSize(13);
        btnNext.setEnabled(false);
        btnNext.setMinWidth(0);
        btnNext.setMinimumWidth(0);
        btnNext.setPadding(32, 0, 32, 0);
        android.widget.LinearLayout.LayoutParams btnParams2 = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams2.setMargins(8, 0, 8, 0);
        btnNext.setLayoutParams(btnParams2);
        btnLayout.addView(btnNext);
        
        // 替换按钮
        com.google.android.material.button.MaterialButton btnReplace = new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnReplace.setText("替换");
        btnReplace.setTextSize(13);
        btnReplace.setMinWidth(0);
        btnReplace.setMinimumWidth(0);
        btnReplace.setPadding(32, 0, 32, 0);
        android.widget.LinearLayout.LayoutParams btnParams3 = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams3.setMargins(8, 0, 8, 0);
        btnReplace.setLayoutParams(btnParams3);
        btnLayout.addView(btnReplace);
        
        // 全部替换按钮
        com.google.android.material.button.MaterialButton btnReplaceAll = new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnReplaceAll.setText("全部");
        btnReplaceAll.setTextSize(13);
        btnReplaceAll.setMinWidth(0);
        btnReplaceAll.setMinimumWidth(0);
        btnReplaceAll.setPadding(32, 0, 32, 0);
        btnReplaceAll.setVisibility(View.GONE);
        android.widget.LinearLayout.LayoutParams btnParams4 = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams4.setMargins(8, 0, 0, 0);
        btnReplaceAll.setLayoutParams(btnParams4);
        btnLayout.addView(btnReplaceAll);
        
        layout.addView(btnLayout);
        
        // 创建对话框
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setPositiveButton("关闭", null)
            .create();
        
        // 搜索逻辑
        Runnable doSearch = () -> {
            String query = searchInput.getText().toString();
            currentSearchQuery = query;
            searchResults.clear();
            currentSearchIndex = -1;
            
            if (query.isEmpty()) {
                codeEditor.getSearcher().stopSearch();
                countText.setText("搜索结果: 0");
                countText.setTextColor(0xFFAAAAAA);
                btnPrev.setEnabled(false);
                btnNext.setEnabled(false);
                return;
            }
            
            // 执行搜索
            codeEditor.getSearcher().search(query, new io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(false, false));
            
            // 统计匹配数量
            String text = codeEditor.getText().toString();
            int index = 0;
            while ((index = text.indexOf(query, index)) != -1) {
                int line = 0, col = 0;
                for (int i = 0; i < index; i++) {
                    if (text.charAt(i) == '\n') { line++; col = 0; } else { col++; }
                }
                searchResults.add(new int[]{line, col, index});
                index += query.length();
            }
            
            int count = searchResults.size();
            if (count == 0) {
                countText.setText("搜索结果: 0");
                countText.setTextColor(0xFFE57373);
                btnPrev.setEnabled(false);
                btnNext.setEnabled(false);
            } else {
                currentSearchIndex = 0;
                countText.setText("搜索结果: " + count + "  (1/" + count + ")");
                countText.setTextColor(0xFF4EC9B0);
                btnPrev.setEnabled(count > 1);
                btnNext.setEnabled(count > 1);
                gotoSearchResult(0, null);
            }
        };
        
        // 输入监听
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { doSearch.run(); }
        });
        
        // 上一个
        btnPrev.setOnClickListener(v -> {
            if (searchResults.isEmpty()) return;
            currentSearchIndex--;
            if (currentSearchIndex < 0) currentSearchIndex = searchResults.size() - 1;
            countText.setText("搜索结果: " + searchResults.size() + "  (" + (currentSearchIndex + 1) + "/" + searchResults.size() + ")");
            gotoSearchResult(currentSearchIndex, null);
        });
        
        // 下一个
        btnNext.setOnClickListener(v -> {
            if (searchResults.isEmpty()) return;
            currentSearchIndex++;
            if (currentSearchIndex >= searchResults.size()) currentSearchIndex = 0;
            countText.setText("搜索结果: " + searchResults.size() + "  (" + (currentSearchIndex + 1) + "/" + searchResults.size() + ")");
            gotoSearchResult(currentSearchIndex, null);
        });
        
        // 替换按钮 - 切换替换输入框显示/执行替换
        final boolean[] replaceMode = {false};
        btnReplace.setOnClickListener(v -> {
            if (!replaceMode[0]) {
                // 显示替换输入框
                replaceLayout.setVisibility(View.VISIBLE);
                btnReplaceAll.setVisibility(View.VISIBLE);
                replaceMode[0] = true;
            } else {
                // 执行替换当前
                if (currentSearchIndex >= 0 && currentSearchIndex < searchResults.size()) {
                    String replacement = replaceInput.getText().toString();
                    int[] result = searchResults.get(currentSearchIndex);
                    int line = result[0];
                    int col = result[1];
                    String query = searchInput.getText().toString();
                    
                    // 使用 Content 的 replace 方法，支持撤销
                    int endCol = col + query.length();
                    int endLine = line;
                    // 检查是否跨行
                    int lineLen = codeEditor.getText().getLine(line).length();
                    while (endCol > lineLen && endLine < codeEditor.getLineCount() - 1) {
                        endCol -= lineLen + 1;
                        endLine++;
                        lineLen = codeEditor.getText().getLine(endLine).length();
                    }
                    endCol = Math.min(endCol, lineLen);
                    
                    codeEditor.getText().replace(line, col, endLine, endCol, replacement);
                    isModified = true;
                    
                    // 重新搜索
                    doSearch.run();
                    Toast.makeText(this, "已替换 1 处", Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        // 全部替换
        btnReplaceAll.setOnClickListener(v -> {
            String query = searchInput.getText().toString();
            String replacement = replaceInput.getText().toString();
            if (query.isEmpty()) return;
            
            // 从后往前替换，避免索引偏移问题
            int count = searchResults.size();
            if (count == 0) return;
            
            // 开始批量编辑（合并为一次撤销操作）
            codeEditor.getText().beginBatchEdit();
            try {
                for (int i = count - 1; i >= 0; i--) {
                    int[] result = searchResults.get(i);
                    int line = result[0];
                    int col = result[1];
                    
                    int endCol = col + query.length();
                    int endLine = line;
                    int lineLen = codeEditor.getText().getLine(line).length();
                    while (endCol > lineLen && endLine < codeEditor.getLineCount() - 1) {
                        endCol -= lineLen + 1;
                        endLine++;
                        lineLen = codeEditor.getText().getLine(endLine).length();
                    }
                    endCol = Math.min(endCol, lineLen);
                    
                    codeEditor.getText().replace(line, col, endLine, endCol, replacement);
                }
            } finally {
                codeEditor.getText().endBatchEdit();
            }
            
            isModified = true;
            doSearch.run();
            Toast.makeText(this, "已替换 " + count + " 处", Toast.LENGTH_SHORT).show();
        });
        
        dialog.show();
        
        if (!currentSearchQuery.isEmpty()) {
            doSearch.run();
        }
    }
    
    private void gotoSearchResult(int index, TextView countText) {
        if (index < 0 || index >= searchResults.size()) return;
        int[] result = searchResults.get(index);
        int line = result[0];
        int col = result[1];
        
        // 设置光标位置
        codeEditor.setSelection(line, col);
        
        // 平滑滚动到目标位置
        codeEditor.post(() -> {
            try {
                float[] offset = codeEditor.getLayout().getCharLayoutOffset(line, col);
                int targetY = (int) (offset[0] - codeEditor.getHeight() / 2f);
                targetY = Math.max(0, targetY);
                
                final int startY = (int) codeEditor.getOffsetY();
                final int endY = targetY;
                android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(startY, endY);
                animator.setDuration(200);
                animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
                animator.addUpdateListener(animation -> {
                    int value = (int) animation.getAnimatedValue();
                    codeEditor.getScroller().startScroll(0, value, 0, 0, 0);
                    codeEditor.invalidate();
                });
                animator.start();
            } catch (Exception ignored) {}
        });
    }

    private void runCode() {
        if (currentFile == null) {
            Toast.makeText(this, "请先保存文件", Toast.LENGTH_SHORT).show();
            showSaveAsDialog();
            return;
        }
        
        // 检查 Clang 是否已安装
        if (!ClangInstaller.isInstalled(this)) {
            ClangInstaller.showInstallDialog(this, this::runCode);
            return;
        }
        
        if (saveDirtyDocumentsBefore(this::runCode)) return;
        
        // 检查是否有默认编译命令
        AppPreferences.CompileCommand defaultCmd = AppPreferences.getDefaultCompileCommand(this);
        if (defaultCmd != null) {
            // 有默认命令，直接执行
            executeCustomCompileCommand(defaultCmd);
            return;
        }
        
        // 检查是否有启用的自定义编译命令
        java.util.List<AppPreferences.CompileCommand> enabledCommands = 
            AppPreferences.getEnabledCompileCommands(this);
        
        if (enabledCommands.isEmpty()) {
            // 没有自定义命令，直接使用默认编译
            doDefaultCompile();
        } else {
            // 有自定义命令但没有默认，显示选择对话框
            showCompileCommandSelector(enabledCommands);
        }
    }
    
    /**
     * 显示编译命令选择对话框
     */
    private void showCompileCommandSelector(java.util.List<AppPreferences.CompileCommand> commands) {
        // 构建选项列表：默认编译 + 自定义命令
        String[] items = new String[commands.size() + 1];
        items[0] = "默认编译";
        for (int i = 0; i < commands.size(); i++) {
            items[i + 1] = commands.get(i).name;
        }
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("选择编译方式")
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    // 默认编译
                    doDefaultCompile();
                } else {
                    // 自定义命令
                    AppPreferences.CompileCommand cmd = commands.get(which - 1);
                    executeCustomCompileCommand(cmd);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 显示编译命令菜单（从更多菜单调用）
     */
    private void showCompileCommandMenu() {
        if (currentFile == null) {
            Toast.makeText(this, "请先打开一个文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 保存文件
        if (saveDirtyDocumentsBefore(this::showCompileCommandMenu)) return;
        
        // 获取所有启用的自定义编译命令
        java.util.List<AppPreferences.CompileCommand> enabledCommands = 
            AppPreferences.getEnabledCompileCommands(this);
        
        if (enabledCommands.isEmpty()) {
            // 没有自定义命令，提示用户去设置
            new MaterialAlertDialogBuilder(this)
                .setTitle("编译命令")
                .setMessage("您还没有配置自定义编译命令。\n\n请前往「设置 → 编译设置 → 编译命令管理」添加自定义编译命令。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    Intent intent = new Intent(this, SettingsActivity.class);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        
        // 显示编译命令选择对话框
        showCompileCommandSelector(enabledCommands);
    }
    
    /**
     * 执行自定义编译命令
     */
    private void executeCustomCompileCommand(AppPreferences.CompileCommand cmd) {
        String sourceFilePath = currentFile.getAbsolutePath();
        String outputFilePath = getFilesDir().getAbsolutePath() + "/tmp/a.out";
        
        // 替换占位符
        String compileCmd = cmd.compileCmd
            .replace("{source}", sourceFilePath)
            .replace("{output}", outputFilePath);
        String runCmd = cmd.runCmd
            .replace("{source}", sourceFilePath)
            .replace("{output}", outputFilePath);
        
        // 执行编译前 hook
        java.util.Map<String, String> hookVars = new java.util.HashMap<>();
        hookVars.put("source_file", sourceFilePath);
        hookVars.put("output_file", outputFilePath);
        hookVars.put("compile_cmd", compileCmd);
        
        final String finalRunCmd = runCmd;
        PluginManager.getHookManager(this).executeEvent(this, PluginHookManager.EVENT_COMPILE_BEFORE, hookVars, () -> {
            executeCustomCompileCommandInternal(sourceFilePath, outputFilePath, compileCmd, finalRunCmd);
        });
    }
    
    /**
     * 执行自定义编译命令（内部方法，hook 执行后调用）
     */
    private void executeCustomCompileCommandInternal(String sourceFilePath, String outputFilePath, String compileCmd, String runCmd) {
        if (isDestroyed() || isFinishing()) return;
        Dialog loadingDialog = createCompileLoadingDialog();
        loadingDialog.show();
        String shell = ZshInstaller.isInstalled(this) ? ZshInstaller.getZshPath(this) : "sh";
        com.vkyra.code.toolchain.CustomCommandTask.Request request =
                new com.vkyra.code.toolchain.CustomCommandTask.Request(shell, compileCmd, null,
                        com.vkyra.code.toolchain.CustomCommandTask.COMPILE_TIMEOUT_MILLIS,
                        sourceFilePath, outputFilePath, runCmd);
        java.util.concurrent.atomic.AtomicReference<com.vkyra.code.toolchain.CustomCommandTasks.OwnedTask>
                owner = new java.util.concurrent.atomic.AtomicReference<>();
        owner.set(com.vkyra.code.toolchain.CustomCommandTasks.start(this, request, result -> {
            if (isDestroyed() || isFinishing()) return;
            if (result.success) {
                long elapsed = result.elapsedMillis;
                String timeStr = elapsed < 1000 ? elapsed + "ms" : format("%.2fs", elapsed / 1000.0);
                java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                hookVars.put("source_file", result.request.sourceFile);
                hookVars.put("output_file", result.request.outputFile);
                hookVars.put("compile_time", timeStr);
                PluginManager.getHookManager(this).executeEvent(this,
                        PluginHookManager.EVENT_COMPILE_SUCCESS, hookVars, () -> runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || !owner.get().isActive()) return;
                    Toast.makeText(this, "执行成功，耗时 " + timeStr, Toast.LENGTH_SHORT).show();
                    if (result.request.runCommand != null && !result.request.runCommand.isEmpty()) {
                        Intent intent = terminalIntent();
                        intent.putExtra("RUN_COMMAND", result.request.runCommand);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);
                    }
                }));
            } else {
                java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                hookVars.put("source_file", result.request.sourceFile);
                hookVars.put("error_message", result.errorMessage);
                PluginManager.getHookManager(this).executeEvent(this,
                        PluginHookManager.EVENT_COMPILE_ERROR, hookVars, () -> runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || !owner.get().isActive()) return;
                    showCompileErrorDialog(result.errorMessage);
                }));
            }
        }, () -> {
            stopCompileTimer();
            if (loadingDialog.isShowing()) loadingDialog.dismiss();
        }));
        loadingDialog.setCancelable(true);
        loadingDialog.setOnCancelListener(dialog -> owner.get().cancel());
    }

    /**
     * 执行默认编译
     */
    private void doDefaultCompile() {
        String sourceFilePath = currentFile.getAbsolutePath();
        String outputFilePath = CppCompiler.newOutputPath(this);
        java.util.Map<String, String> hookVars = new java.util.HashMap<>();
        hookVars.put("source_file", sourceFilePath);
        hookVars.put("output_file", outputFilePath);
        PluginManager.getHookManager(this).executeEvent(this, PluginHookManager.EVENT_COMPILE_BEFORE, hookVars, () -> {
            doDefaultCompileInternal(sourceFilePath, outputFilePath);
        });
    }

    /** Compile every request against its current sources, flags, headers and plugin libraries. */
    private void doDefaultCompileInternal(String sourceFilePath, String outputFilePath) {
        // 显示编译中对话框
        Dialog loadingDialog = createCompileLoadingDialog();
        loadingDialog.show();
        
        // 记录编译开始时间
        long startTime = System.currentTimeMillis();
        
        // 后台编译
        CppCompiler.compileAsync(this, sourceFilePath, outputFilePath, new CppCompiler.CompileCallback() {
            @Override
            public void onSuccess(String outputPath) {
                stopCompileTimer();
                loadingDialog.dismiss();
                // 计算编译耗时
                long elapsed = System.currentTimeMillis() - startTime;
                String timeStr = elapsed < 1000 ? elapsed + "ms" : format("%.2fs", elapsed / 1000.0);
                Toast.makeText(EditorActivity.this, "编译成功，耗时 " + timeStr, Toast.LENGTH_SHORT).show();
                
                // 执行编译成功 hook
                java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                hookVars.put("source_file", sourceFilePath);
                hookVars.put("output_file", outputPath);
                hookVars.put("compile_time", timeStr);
                
                PluginManager.getHookManager(EditorActivity.this).executeEvent(EditorActivity.this, 
                    PluginHookManager.EVENT_COMPILE_SUCCESS, hookVars, () -> {
                    // 打开终端运行
                    Intent intent = terminalIntent();
                    intent.putExtra("RUN_FILE", outputPath);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                });
            }
            
            @Override
            public void onError(String errorMessage) {
                stopCompileTimer();
                loadingDialog.dismiss();
                
                // 执行编译失败 hook
                java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                hookVars.put("source_file", sourceFilePath);
                hookVars.put("error_message", errorMessage);
                
                PluginManager.getHookManager(EditorActivity.this).executeEvent(EditorActivity.this, 
                    PluginHookManager.EVENT_COMPILE_ERROR, hookVars, () -> {
                    // 显示错误对话框
                    showCompileErrorDialog(errorMessage);
                });
            }
        });
    }
    
    /**
     * 终端编译运行 - 在终端中执行编译和运行
     */
    private void terminalCompileAndRun() {
        if (currentFile == null) {
            Toast.makeText(this, "请先保存文件", Toast.LENGTH_SHORT).show();
            showSaveAsDialog();
            return;
        }
        
        // 检查 Clang 是否已安装
        if (!ClangInstaller.isInstalled(this)) {
            ClangInstaller.showInstallDialog(this, this::terminalCompileAndRun);
            return;
        }
        
        if (saveDirtyDocumentsBefore(this::terminalCompileAndRun)) return;
        
        // 传递 COMPILE_FILE 让 TerminalActivity 执行编译
        Intent intent = terminalIntent();
        intent.putExtra("COMPILE_FILE", currentFile.getAbsolutePath());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
    
    /**
     * CMake 编译 - 在终端中执行 cmake
     */
    private void runCMakeBuild() {
        if (currentDir == null) {
            Toast.makeText(this, "请先打开一个项目", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 检查 Clang 是否已安装（CMake 构建同样依赖 clang/clang++）
        if (!ClangInstaller.isInstalled(this)) {
            ClangInstaller.showInstallDialog(this, this::runCMakeBuild);
            return;
        }
        
        // 检查编译工具是否已安装
        if (!BuildToolsInstaller.isInstalled(this)) {
            BuildToolsInstaller.showInstallDialog(this, this::runCMakeBuild);
            return;
        }
        
        if (saveDirtyDocumentsBefore(this::runCMakeBuild)) return;
        
        // 先在当前目录查找 CMakeLists.txt，再向上查找到项目根目录
        File cmakeLists = new File(currentDir, "CMakeLists.txt");
        if (!cmakeLists.exists() && currentProjectPath != null) {
            // 如果当前目录没有，再检查项目根目录
            File projectDir = new File(currentProjectPath);
            cmakeLists = new File(projectDir, "CMakeLists.txt");
        }
        
        if (!cmakeLists.exists()) {
            Toast.makeText(this, "当前目录和项目根目录都没有找到 CMakeLists.txt", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示 CMake 参数对话框
        showCMakeArgsDialog();
    }
    
    /**
     * 显示 CMake 参数对话框（带编译/运行页签）
     */
    private void showCMakeArgsDialog() {
        // 先在当前目录查找 CMakeLists.txt，再向上查找到项目根目录
        File cmakeLists = new File(currentDir, "CMakeLists.txt");
        if (!cmakeLists.exists() && currentProjectPath != null) {
            File projectDir = new File(currentProjectPath);
            cmakeLists = new File(projectDir, "CMakeLists.txt");
        }
        String defaultExecName = parseCMakeExecutable(cmakeLists);
        
        // 创建ScrollView包装所有内容
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        
        android.widget.LinearLayout mainLayout = new android.widget.LinearLayout(this);
        mainLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        mainLayout.setPadding(48, 16, 48, 0);
        
        // Material 3 分段按钮
        com.google.android.material.button.MaterialButtonToggleGroup toggleGroup = 
            new com.google.android.material.button.MaterialButtonToggleGroup(this);
        toggleGroup.setSingleSelection(true);
        toggleGroup.setSelectionRequired(true);
        android.widget.LinearLayout.LayoutParams toggleParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleParams.bottomMargin = 24;
        toggleGroup.setLayoutParams(toggleParams);
        
        // 编译按钮
        final com.google.android.material.button.MaterialButton btnCompile = 
            new com.google.android.material.button.MaterialButton(this, null, 
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCompile.setId(View.generateViewId());
        btnCompile.setText("编译");
        btnCompile.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        // 运行按钮
        final com.google.android.material.button.MaterialButton btnRun = 
            new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnRun.setId(View.generateViewId());
        btnRun.setText("运行");
        btnRun.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        // 编译并运行按钮
        final com.google.android.material.button.MaterialButton btnCompileAndRun = 
            new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCompileAndRun.setId(View.generateViewId());
        btnCompileAndRun.setText("编译并运行");
        btnCompileAndRun.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        toggleGroup.addView(btnCompile);
        toggleGroup.addView(btnRun);
        toggleGroup.addView(btnCompileAndRun);
        toggleGroup.check(btnCompile.getId());
        
        mainLayout.addView(toggleGroup);
        
        // ========== 编译页面内容 ==========
        android.widget.LinearLayout compileLayout = new android.widget.LinearLayout(this);
        compileLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        
        // CMake 参数输入
        TextInputLayout argsLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        argsLayout.setHint("CMake 参数 (可选)");
        argsLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        TextInputEditText argsInput = new TextInputEditText(argsLayout.getContext());
        argsInput.setText(AppPreferences.getCMakeArgs(this));
        argsLayout.addView(argsInput);
        compileLayout.addView(argsLayout);
        
        // M3 紫色
        int m3PrimaryColor = 0xFFD0BCFF;
        android.content.res.ColorStateList checkboxTint = android.content.res.ColorStateList.valueOf(m3PrimaryColor);
        
        // 使用 Ninja 选项
        com.google.android.material.checkbox.MaterialCheckBox cbNinja = 
            new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbNinja.setText("使用 Ninja 构建 (-G Ninja)");
        cbNinja.setChecked(AppPreferences.getCMakeUseNinja(this));
        cbNinja.setButtonTintList(checkboxTint);
        android.widget.LinearLayout.LayoutParams cbParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cbParams.topMargin = 16;
        cbNinja.setLayoutParams(cbParams);
        compileLayout.addView(cbNinja);
        
        // 并行编译选项 - 使用 Slider
        android.widget.LinearLayout parallelLayout = new android.widget.LinearLayout(this);
        parallelLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams parallelParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        parallelParams.topMargin = 16;
        parallelLayout.setLayoutParams(parallelParams);
        
        // 复选框和标签的横向布局
        android.widget.LinearLayout parallelHeaderLayout = new android.widget.LinearLayout(this);
        parallelHeaderLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        parallelHeaderLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        com.google.android.material.checkbox.MaterialCheckBox cbParallel = 
            new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbParallel.setText("并行编译");
        cbParallel.setChecked(AppPreferences.getCMakeParallel(this));
        cbParallel.setButtonTintList(checkboxTint);
        parallelHeaderLayout.addView(cbParallel);
        
        // 线程数标签
        TextView threadsLabel = new TextView(this);
        int savedThreads = AppPreferences.getCMakeParallelThreads(this);
        threadsLabel.setText(" (-j" + savedThreads + ")");
        threadsLabel.setTextSize(14);
        threadsLabel.setTextColor(m3PrimaryColor);
        android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = 8;
        threadsLabel.setLayoutParams(labelParams);
        parallelHeaderLayout.addView(threadsLabel);
        
        parallelLayout.addView(parallelHeaderLayout);
        
        // Slider 滑块
        com.google.android.material.slider.Slider threadsSlider = new com.google.android.material.slider.Slider(this);
        threadsSlider.setValueFrom(1);
        threadsSlider.setValueTo(16);
        threadsSlider.setValue(savedThreads);
        threadsSlider.setStepSize(1);
        threadsSlider.setEnabled(cbParallel.isChecked());
        
        // 根据主题设置 Slider 颜色
        boolean isDarkMode = (getResources().getConfiguration().uiMode & 
                             android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                             android.content.res.Configuration.UI_MODE_NIGHT_YES;
        
        // 设置 Slider 颜色为 M3 紫色
        threadsSlider.setTrackActiveTintList(checkboxTint);
        // 非激活轨道：暗色模式用深灰，亮色模式用浅灰
        int inactiveTrackColor = isDarkMode ? 0xFF3A3A3A : 0xFFE0E0E0;
        threadsSlider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(inactiveTrackColor));
        threadsSlider.setThumbTintList(checkboxTint);
        threadsSlider.setHaloTintList(android.content.res.ColorStateList.valueOf(0x40D0BCFF));
        
        android.widget.LinearLayout.LayoutParams sliderParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderParams.topMargin = 0;
        sliderParams.leftMargin = 16;
        sliderParams.rightMargin = 16;
        threadsSlider.setLayoutParams(sliderParams);
        
        // Slider 值改变监听
        threadsSlider.addOnChangeListener((slider, value, fromUser) -> {
            threadsLabel.setText(" (-j" + (int)value + ")");
        });
        
        parallelLayout.addView(threadsSlider);
        
        // 复选框状态改变时启用/禁用 Slider
        cbParallel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            threadsSlider.setEnabled(isChecked);
            if (!isChecked) {
                threadsLabel.setTextColor(0xFF888888);
            } else {
                threadsLabel.setTextColor(m3PrimaryColor);
            }
        });
        
        compileLayout.addView(parallelLayout);
        
        mainLayout.addView(compileLayout);
        
        // ========== 运行页面内容 ==========
        android.widget.LinearLayout runLayout = new android.widget.LinearLayout(this);
        runLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        runLayout.setVisibility(View.GONE);
        
        // 可执行文件名输入
        TextInputLayout nameLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        nameLayout.setHint("可执行文件名");
        nameLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        TextInputEditText nameInput = new TextInputEditText(nameLayout.getContext());
        String savedName = AppPreferences.getCMakeOutputName(this);
        nameInput.setText(defaultExecName != null ? defaultExecName : (savedName.isEmpty() ? "" : savedName));
        nameLayout.addView(nameInput);
        runLayout.addView(nameLayout);
        
        // 运行参数输入
        TextInputLayout runArgsLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        runArgsLayout.setHint("运行参数 (可选)");
        runArgsLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        android.widget.LinearLayout.LayoutParams runArgsParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        runArgsParams.topMargin = 16;
        runArgsLayout.setLayoutParams(runArgsParams);
        
        TextInputEditText runArgsInput = new TextInputEditText(runArgsLayout.getContext());
        runArgsInput.setText(AppPreferences.getCMakeRunArgs(this));
        runArgsLayout.addView(runArgsInput);
        runLayout.addView(runArgsLayout);
        
        // 复制到 tmp 目录选项（默认勾选）
        com.google.android.material.checkbox.MaterialCheckBox cbCopyToTmp = 
            new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbCopyToTmp.setText("复制到 tmp 目录运行");
        boolean savedCopyToTmp = AppPreferences.getCMakeCopyToTmp(this);
        // 如果是第一次使用（没有保存过配置），默认勾选
        if (!getSharedPreferences("akode_settings", MODE_PRIVATE).contains("cmake_copy_to_tmp")) {
            cbCopyToTmp.setChecked(true);
        } else {
            cbCopyToTmp.setChecked(savedCopyToTmp);
        }
        cbCopyToTmp.setButtonTintList(checkboxTint);
        cbCopyToTmp.setLayoutParams(cbParams);
        runLayout.addView(cbCopyToTmp);
        
        // Root 运行选项
        com.google.android.material.checkbox.MaterialCheckBox cbRootRun = 
            new com.google.android.material.checkbox.MaterialCheckBox(this);
        cbRootRun.setText("使用 Root 权限运行");
        cbRootRun.setChecked(AppPreferences.getEnableRootRun(this));
        cbRootRun.setButtonTintList(checkboxTint);
        cbRootRun.setLayoutParams(cbParams);
        runLayout.addView(cbRootRun);
        
        // 提示文本
        TextView hintText = new TextView(this);
        String execName = nameInput.getText().toString().trim();
        hintText.setText("可执行文件路径: build/" + (execName.isEmpty() ? "<name>" : execName));
        hintText.setTextSize(12);
        hintText.setTextColor(0xFF888888);
        hintText.setPadding(0, 16, 0, 0);
        runLayout.addView(hintText);
        
        // 更新提示文本
        nameInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String name = s.toString().trim();
                hintText.setText("可执行文件路径: build/" + (name.isEmpty() ? "<name>" : name));
            }
        });
        
        mainLayout.addView(runLayout);
        
        // 当前选中的页签 (0=编译, 1=运行, 2=编译并运行)
        final int[] currentTab = {0};
        
        // 页签切换监听
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == btnCompile.getId()) {
                    currentTab[0] = 0;
                    compileLayout.setVisibility(View.VISIBLE);
                    runLayout.setVisibility(View.GONE);
                } else if (checkedId == btnRun.getId()) {
                    currentTab[0] = 1;
                    compileLayout.setVisibility(View.GONE);
                    runLayout.setVisibility(View.VISIBLE);
                } else if (checkedId == btnCompileAndRun.getId()) {
                    currentTab[0] = 2;
                    // 编译并运行显示两个页面的合并内容
                    compileLayout.setVisibility(View.VISIBLE);
                    runLayout.setVisibility(View.VISIBLE);
                }
            }
        });
        
        // 将mainLayout添加到ScrollView
        scrollView.addView(mainLayout);
        
        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("CMake")
            .setView(scrollView)
            .setPositiveButton("执行", (d, w) -> {
                if (currentTab[0] == 0) {
                    // 编译模式
                    String args = argsInput.getText().toString().trim();
                    boolean useNinja = cbNinja.isChecked();
                    boolean parallel = cbParallel.isChecked();
                    int threads = (int) threadsSlider.getValue();
                    
                    AppPreferences.setCMakeArgs(this, args);
                    AppPreferences.setCMakeUseNinja(this, useNinja);
                    AppPreferences.setCMakeParallel(this, parallel);
                    AppPreferences.setCMakeParallelThreads(this, threads);
                    executeCMakeBuild(args, useNinja, parallel, threads);
                } else if (currentTab[0] == 1) {
                    // 运行模式
                    String name = nameInput.getText().toString().trim();
                    String runArgs = runArgsInput.getText().toString().trim();
                    boolean copyToTmp = cbCopyToTmp.isChecked();
                    boolean rootRun = cbRootRun.isChecked();
                    
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入可执行文件名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    AppPreferences.setCMakeOutputName(this, name);
                    AppPreferences.setCMakeRunArgs(this, runArgs);
                    AppPreferences.setCMakeCopyToTmp(this, copyToTmp);
                    executeCMakeOutput(name, runArgs, copyToTmp, rootRun);
                } else if (currentTab[0] == 2) {
                    // 编译并运行模式
                    String args = argsInput.getText().toString().trim();
                    boolean useNinja = cbNinja.isChecked();
                    boolean parallel = cbParallel.isChecked();
                    int threads = (int) threadsSlider.getValue();
                    
                    String name = nameInput.getText().toString().trim();
                    String runArgs = runArgsInput.getText().toString().trim();
                    boolean copyToTmp = cbCopyToTmp.isChecked();
                    boolean rootRun = cbRootRun.isChecked();
                    
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入可执行文件名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 保存所有设置
                    AppPreferences.setCMakeArgs(this, args);
                    AppPreferences.setCMakeUseNinja(this, useNinja);
                    AppPreferences.setCMakeParallel(this, parallel);
                    AppPreferences.setCMakeParallelThreads(this, threads);
                    AppPreferences.setCMakeOutputName(this, name);
                    AppPreferences.setCMakeRunArgs(this, runArgs);
                    AppPreferences.setCMakeCopyToTmp(this, copyToTmp);
                    
                    // 执行编译并运行
                    executeCMakeBuildAndRun(args, useNinja, parallel, threads, name, runArgs, copyToTmp, rootRun);
                }
            })
            .setNegativeButton("取消", null);
        
        // 仅在编译页签时显示"仅编译"按钮
        if (currentTab[0] == 0) {
            dialogBuilder.setNeutralButton("仅编译", (d, w) -> {
                boolean useNinja = cbNinja.isChecked();
                boolean parallel = cbParallel.isChecked();
                int threads = (int) threadsSlider.getValue();
                
                AppPreferences.setCMakeUseNinja(this, useNinja);
                AppPreferences.setCMakeParallel(this, parallel);
                AppPreferences.setCMakeParallelThreads(this, threads);
                executeCMakeBuildOnly(useNinja, parallel, threads);
            });
        }
        
        // 页签切换时动态更新按钮
        final androidx.appcompat.app.AlertDialog[] dialog = {null};
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked && dialog[0] != null) {
                android.widget.Button neutralButton = dialog[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
                if (neutralButton != null) {
                    if (checkedId == btnCompile.getId()) {
                        neutralButton.setVisibility(View.VISIBLE);
                    } else {
                        neutralButton.setVisibility(View.GONE);
                    }
                }
            }
        });
        
        dialog[0] = dialogBuilder.show();
        
        // 设置按钮颜色为 M3 紫色
        android.widget.Button positiveButton = dialog[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        android.widget.Button negativeButton = dialog[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
        android.widget.Button neutralButton = dialog[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
        
        if (positiveButton != null) {
            positiveButton.setTextColor(m3PrimaryColor);
        }
        if (negativeButton != null) {
            negativeButton.setTextColor(m3PrimaryColor);
        }
        if (neutralButton != null) {
            neutralButton.setTextColor(m3PrimaryColor);
            // 初始化时隐藏"仅编译"按钮（因为默认是编译页签）
            if (currentTab[0] != 0) {
                neutralButton.setVisibility(View.GONE);
            }
        }
    }
    
    /**
     * 执行 CMake 配置和编译（完整构建，会清理 build 目录）
     */
    private void executeCMakeBuild(String args, boolean useNinja, boolean parallel, int threads) {
        // 先在当前目录查找 CMakeLists.txt，再向上查找到项目根目录
        File cmakeLists = new File(currentDir, "CMakeLists.txt");
        File cmakeDir = currentDir;
        
        if (!cmakeLists.exists() && currentProjectPath != null) {
            File projectDir = new File(currentProjectPath);
            cmakeLists = new File(projectDir, "CMakeLists.txt");
            if (cmakeLists.exists()) {
                cmakeDir = projectDir;
            }
        }
        
        if (!cmakeLists.exists()) {
            Toast.makeText(this, "找不到 CMakeLists.txt", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        
        // 先切换到 CMakeLists.txt 所在目录
        cmd.append("cd '").append(cmakeDir.getAbsolutePath()).append("' && ");
        
        // 如果 build 目录存在，先删除以确保干净构建
        cmd.append("rm -rf build && ");
        
        // 创建 build 目录并进入
        cmd.append("mkdir -p build && cd build && ");
        
        // CMake 配置
        cmd.append("cmake ..");
        if (useNinja) {
            cmd.append(" -G Ninja");
        }
        // 启用 OLLVM 时，自动为 CMake 配置注入 CXX flags（用户未手动传入时）
        String ollvmArgs = AppPreferences.getOllvmArgs(this);
        if (!ollvmArgs.isEmpty() && (args == null || !args.contains("CMAKE_CXX_FLAGS"))) {
            // 用单引号包裹，避免 shell 转义导致的引号错配（例如出现 -sobf" 之类残留引号被当作 cmake 参数）
            String escaped = ollvmArgs.replace("'", "'\\''");
            cmd.append(" -DCMAKE_CXX_FLAGS='").append(escaped).append("'");
        }
        if (args != null && !args.trim().isEmpty()) {
            cmd.append(" ").append(args.trim());
        }
        
        // 编译
        cmd.append(" && ");
        if (useNinja) {
            cmd.append("ninja");
        } else {
            cmd.append("make");
        }
        if (parallel) {
            cmd.append(" -j").append(threads);
        }
        
        Intent intent = terminalIntent();
        intent.putExtra("EXECUTE_COMMAND", cmd.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
    
    /**
     * 仅执行 CMake 编译（不重新配置）
     */
    private void executeCMakeBuildOnly(boolean useNinja, boolean parallel, int threads) {
        // 先在当前目录查找 CMakeLists.txt，再向上查找到项目根目录
        File cmakeLists = new File(currentDir, "CMakeLists.txt");
        File cmakeDir = currentDir;
        
        if (!cmakeLists.exists() && currentProjectPath != null) {
            File projectDir = new File(currentProjectPath);
            cmakeLists = new File(projectDir, "CMakeLists.txt");
            if (cmakeLists.exists()) {
                cmakeDir = projectDir;
            }
        }
        
        File buildDir = new File(cmakeDir, "build");
        if (!buildDir.exists()) {
            Toast.makeText(this, "build 目录不存在，请先配置", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        cmd.append("cd '").append(cmakeDir.getAbsolutePath()).append("' && ");
        cmd.append("cd build && ");
        if (useNinja) {
            cmd.append("ninja");
        } else {
            cmd.append("make");
        }
        if (parallel) {
            cmd.append(" -j").append(threads);
        }
        
        Intent intent = terminalIntent();
        intent.putExtra("EXECUTE_COMMAND", cmd.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
    
    /**
     * 解析 CMakeLists.txt 获取可执行文件名
     * 支持: add_executable(name ...) 和 project(name)
     */
    private String parseCMakeExecutable(File cmakeLists) {
        try {
            String content = RootFileUtils.readFile(cmakeLists.getAbsolutePath());
            if (content == null) return null;
            
            // 优先查找 add_executable
            // 匹配: add_executable(name source1.cpp source2.cpp ...)
            java.util.regex.Pattern execPattern = java.util.regex.Pattern.compile(
                "add_executable\\s*\\(\\s*([a-zA-Z0-9_-]+)", 
                java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher execMatcher = execPattern.matcher(content);
            if (execMatcher.find()) {
                return execMatcher.group(1);
            }
            
            // 如果没有 add_executable，尝试从 project 获取
            java.util.regex.Pattern projPattern = java.util.regex.Pattern.compile(
                "project\\s*\\(\\s*([a-zA-Z0-9_-]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher projMatcher = projPattern.matcher(content);
            if (projMatcher.find()) {
                return projMatcher.group(1);
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 执行 CMake 产物
     */
    private void executeCMakeOutput(String execName, String args, boolean copyToTmp, boolean rootRun) {
        // 优先使用项目根目录
        File projectDir = currentProjectPath != null ? new File(currentProjectPath) : currentDir;
        File buildDir = new File(projectDir, "build");
        File execFile = new File(buildDir, execName);
        
        if (!execFile.exists()) {
            Toast.makeText(this, "可执行文件不存在: build/" + execName + "\n请先编译项目", Toast.LENGTH_LONG).show();
            return;
        }
        
        String filesDir = getFilesDir().getAbsolutePath();
        String tmpDir = filesDir + "/tmp";
        
        StringBuilder cmd = new StringBuilder();
        
        // 先切换到项目目录
        cmd.append("cd '").append(projectDir.getAbsolutePath()).append("' && ");
        
        // 获取自定义 su 命令
        String customSu = AppPreferences.getCustomSu(this);
        
        if (copyToTmp) {
            // 复制到 tmp 目录运行
            cmd.append("cp 'build/").append(execName).append("' '").append(tmpDir).append("/' && ");
            cmd.append("cd '").append(tmpDir).append("' && ");
            cmd.append("chmod +x '").append(execName).append("' && ");
            
            if (rootRun) {
                cmd.append(customSu).append(" './").append(execName).append("'");
            } else {
                cmd.append("./").append(execName);
            }
            
            if (!args.isEmpty()) {
                cmd.append(" ").append(args);
            }
        } else {
            // 直接在 build 目录运行
            cmd.append("cd build && ");
            
            if (rootRun) {
                cmd.append(customSu).append(" './").append(execName).append("'");
            } else {
                cmd.append("./").append(execName);
            }
            
            if (!args.isEmpty()) {
                cmd.append(" ").append(args);
            }
        }
        
        Intent intent = terminalIntent();
        intent.putExtra("EXECUTE_COMMAND", cmd.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
    
    /**
     * 执行 CMake 编译并运行
     */
    private void executeCMakeBuildAndRun(String args, boolean useNinja, boolean parallel, int threads,
                                        String execName, String runArgs, boolean copyToTmp, boolean rootRun) {
        // 先在当前目录查找 CMakeLists.txt，再向上查找到项目根目录
        File cmakeLists = new File(currentDir, "CMakeLists.txt");
        File cmakeDir = currentDir;
        
        if (!cmakeLists.exists() && currentProjectPath != null) {
            File projectDir = new File(currentProjectPath);
            cmakeLists = new File(projectDir, "CMakeLists.txt");
            if (cmakeLists.exists()) {
                cmakeDir = projectDir;
            }
        }
        
        if (!cmakeLists.exists()) {
            Toast.makeText(this, "找不到 CMakeLists.txt", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        
        // 先切换到 CMakeLists.txt 所在目录
        cmd.append("cd '").append(cmakeDir.getAbsolutePath()).append("' && ");
        
        // 如果 build 目录存在，先删除以确保干净构建
        cmd.append("rm -rf build && ");
        
        // 创建 build 目录并进入
        cmd.append("mkdir -p build && cd build && ");
        
        // CMake 配置
        cmd.append("cmake ..");
        if (useNinja) {
            cmd.append(" -G Ninja");
        }
        // 启用 OLLVM 时，自动为 CMake 配置注入 CXX flags（用户未手动传入时）
        String ollvmArgs = AppPreferences.getOllvmArgs(this);
        if (!ollvmArgs.isEmpty() && (args == null || !args.contains("CMAKE_CXX_FLAGS"))) {
            // 用单引号包裹，避免 shell 转义导致的引号错配（例如出现 -sobf" 之类残留引号被当作 cmake 参数）
            String escaped = ollvmArgs.replace("'", "'\\''");
            cmd.append(" -DCMAKE_CXX_FLAGS='").append(escaped).append("'");
        }
        if (args != null && !args.trim().isEmpty()) {
            cmd.append(" ").append(args.trim());
        }
        
        // 编译
        cmd.append(" && ");
        if (useNinja) {
            cmd.append("ninja");
        } else {
            cmd.append("make");
        }
        if (parallel) {
            cmd.append(" -j").append(threads);
        }
        
        // 编译成功后运行
        cmd.append(" && echo '\\n=== 编译完成，开始运行 ===\\n' && ");
        
        String filesDir = getFilesDir().getAbsolutePath();
        String tmpDir = filesDir + "/tmp";
        String customSu = AppPreferences.getCustomSu(this);
        
        if (copyToTmp) {
            // 复制到 tmp 目录运行
            cmd.append("cp '").append(execName).append("' '").append(tmpDir).append("/' && ");
            cmd.append("cd '").append(tmpDir).append("' && ");
            cmd.append("chmod +x '").append(execName).append("' && ");
            
            if (rootRun) {
                cmd.append(customSu).append(" './").append(execName).append("'");
            } else {
                cmd.append("./").append(execName);
            }
            
            if (!runArgs.isEmpty()) {
                cmd.append(" ").append(runArgs);
            }
        } else {
            // 直接在 build 目录运行
            if (rootRun) {
                cmd.append(customSu).append(" './").append(execName).append("'");
            } else {
                cmd.append("./").append(execName);
            }
            
            if (!runArgs.isEmpty()) {
                cmd.append(" ").append(runArgs);
            }
        }
        
        Intent intent = terminalIntent();
        intent.putExtra("EXECUTE_COMMAND", cmd.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
    
    /**
     * 重新编译 - 无视缓存强制编译，不运行
     */
    private void recompile() {
        if (currentFile == null) {
            Toast.makeText(this, "请先保存文件", Toast.LENGTH_SHORT).show();
            showSaveAsDialog();
            return;
        }
        
        // 检查 Clang 是否已安装
        if (!ClangInstaller.isInstalled(this)) {
            ClangInstaller.showInstallDialog(this, this::recompile);
            return;
        }
        
        if (saveDirtyDocumentsBefore(this::recompile)) return;
        
        String sourceFilePath = currentFile.getAbsolutePath();
        String outputFilePath = CppCompiler.newOutputPath(this);

        // 执行编译前 hook
        java.util.Map<String, String> hookVars = new java.util.HashMap<>();
        hookVars.put("source_file", sourceFilePath);
        hookVars.put("output_file", outputFilePath);
        
        PluginManager.getHookManager(this).executeEvent(this, PluginHookManager.EVENT_COMPILE_BEFORE, hookVars, () -> {
            doRecompileInternal(sourceFilePath, outputFilePath);
        });
    }
    
    /**
     * 执行重新编译（内部方法，hook 执行后调用）
     */
    private void doRecompileInternal(String sourceFilePath, String outputFilePath) {
        // 显示编译中对话框
        Dialog loadingDialog = createCompileLoadingDialog();
        loadingDialog.show();
        
        // 记录编译开始时间
        long startTime = System.currentTimeMillis();
        
        // 强制编译，不检查缓存
        CppCompiler.compileAsync(this, sourceFilePath, outputFilePath, new CppCompiler.CompileCallback() {
            @Override
            public void onSuccess(String outputPath) {
                stopCompileTimer();
                loadingDialog.dismiss();
                // 计算编译耗时
                long elapsed = System.currentTimeMillis() - startTime;
                String timeStr = elapsed < 1000 ? elapsed + "ms" : format("%.2fs", elapsed / 1000.0);
                
                // 执行编译成功 hook
                java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                hookVars.put("source_file", sourceFilePath);
                hookVars.put("output_file", outputPath);
                hookVars.put("compile_time", timeStr);
                
                PluginManager.getHookManager(EditorActivity.this).executeEvent(EditorActivity.this, 
                    PluginHookManager.EVENT_COMPILE_SUCCESS, hookVars, () -> {
                    Toast.makeText(EditorActivity.this, "编译成功，耗时 " + timeStr, Toast.LENGTH_SHORT).show();
                });
            }
            
            @Override
            public void onError(String errorMessage) {
                stopCompileTimer();
                loadingDialog.dismiss();
                
                // 执行编译失败 hook
                java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                hookVars.put("source_file", sourceFilePath);
                hookVars.put("error_message", errorMessage);
                
                PluginManager.getHookManager(EditorActivity.this).executeEvent(EditorActivity.this, 
                    PluginHookManager.EVENT_COMPILE_ERROR, hookVars, () -> {
                    // 显示错误对话框
                    showCompileErrorDialog(errorMessage);
                });
            }
        });
    }
    
    private Dialog createLoadingDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        
        // 根据主题设置颜色
        String theme = AppPreferences.getEditorTheme(this);
        boolean isLightTheme = "light".equals(theme);
        int cardBgColor = isLightTheme ? 0xFFFAFAFA : 0xFF2D2D2D;
        int textColor = isLightTheme ? 0xFF333333 : 0xFFE0E0E0;
        int progressColor = isLightTheme ? 0xFF1976D2 : 0xFF4FC3F7;
        int trackColor = isLightTheme ? 0x331976D2 : 0x334FC3F7;
        
        // 创建圆角卡片布局
        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
        card.setCardBackgroundColor(cardBgColor);
        card.setRadius(28f);
        card.setCardElevation(16f);
        card.setUseCompatPadding(true);
        
        // 内部布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.setPadding(48, 36, 56, 36);
        
        // 进度指示器
        CircularProgressIndicator progress = new CircularProgressIndicator(this);
        progress.setIndeterminate(true);
        progress.setIndicatorSize(40);
        progress.setTrackThickness(4);
        progress.setIndicatorColor(progressColor);
        progress.setTrackColor(trackColor);
        
        // 文字
        TextView text = new TextView(this);
        text.setText("编译中...");
        text.setTextColor(textColor);
        text.setTextSize(15);
        text.setTypeface(null, Typeface.NORMAL);
        android.widget.LinearLayout.LayoutParams textParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.leftMargin = 32;
        text.setLayoutParams(textParams);
        
        layout.addView(progress);
        layout.addView(text);
        card.addView(layout);
        
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams cardParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        );
        container.addView(card, cardParams);
        
        dialog.setContentView(container);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setDimAmount(0.5f);
        }
        
        return dialog;
    }
    
    // 编译计时器
    private android.os.Handler compileTimerHandler;
    private Runnable compileTimerRunnable;
    private TextView compileTimeText;
    private long compileStartTime;
    
    /**
     * 创建带实时计时的编译加载弹窗
     */
    private Dialog createCompileLoadingDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        
        float density = getResources().getDisplayMetrics().density;
        
        // 根据主题设置颜色
        String theme = AppPreferences.getEditorTheme(this);
        boolean isLightTheme = "light".equals(theme);
        int cardBgColor = isLightTheme ? 0xFFFAFAFA : 0xFF1E1E1E;
        int textColor = isLightTheme ? 0xFF333333 : 0xFFCCCCCC;
        int subTextColor = isLightTheme ? 0xFF888888 : 0xFF666666;
        int progressColor = isLightTheme ? 0xFF1976D2 : 0xFF808080;
        int trackColor = isLightTheme ? 0x331976D2 : 0x33808080;
        
        // 创建圆角卡片布局
        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
        card.setCardBackgroundColor(cardBgColor);
        card.setRadius(16f);
        card.setCardElevation(20f);
        card.setUseCompatPadding(true);
        
        // 内部布局 - 左右结构
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.setPadding((int)(40 * density), (int)(32 * density), (int)(48 * density), (int)(32 * density));
        
        // 左侧：进度指示器
        CircularProgressIndicator progress = new CircularProgressIndicator(this);
        progress.setIndeterminate(true);
        progress.setIndicatorSize((int)(32 * density));
        progress.setTrackThickness((int)(3 * density));
        progress.setIndicatorColor(progressColor);
        progress.setTrackColor(trackColor);
        layout.addView(progress);
        
        // 右侧：文字区域（垂直排列）
        android.widget.LinearLayout textLayout = new android.widget.LinearLayout(this);
        textLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams textLayoutParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textLayoutParams.leftMargin = (int)(24 * density);
        textLayout.setLayoutParams(textLayoutParams);
        
        // 编译中文字
        TextView text = new TextView(this);
        text.setText("编译中...");
        text.setTextColor(textColor);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        textLayout.addView(text);
        
        // 计时文字
        compileTimeText = new TextView(this);
        compileTimeText.setText("编译耗时 0.0s");
        compileTimeText.setTextColor(subTextColor);
        compileTimeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        android.widget.LinearLayout.LayoutParams timeParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timeParams.topMargin = (int)(4 * density);
        compileTimeText.setLayoutParams(timeParams);
        textLayout.addView(compileTimeText);
        
        layout.addView(textLayout);
        card.addView(layout);
        
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams cardParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        );
        container.addView(card, cardParams);
        
        dialog.setContentView(container);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setDimAmount(0.6f);
        }
        
        // 启动计时器
        compileStartTime = System.currentTimeMillis();
        compileTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        compileTimerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - compileStartTime;
                compileTimeText.setText(format("编译耗时 %.1fs", elapsed / 1000.0));
                compileTimerHandler.postDelayed(this, 100);
            }
        };
        compileTimerHandler.post(compileTimerRunnable);
        
        return dialog;
    }
    
    /**
     * 停止编译计时器
     */
    private void stopCompileTimer() {
        if (compileTimerHandler != null && compileTimerRunnable != null) {
            compileTimerHandler.removeCallbacks(compileTimerRunnable);
        }
    }
    
    /**
     * 显示编译输出对话框
     */
    private void showCompileOutputDialog() {
        if (currentFile == null) {
            Toast.makeText(this, "请先保存文件", Toast.LENGTH_SHORT).show();
            showSaveAsDialog();
            return;
        }
        
        // 检查 Clang 是否已安装
        if (!ClangInstaller.isInstalled(this)) {
            ClangInstaller.showInstallDialog(this, this::showCompileOutputDialog);
            return;
        }
        
        if (saveDirtyDocumentsBefore(this::showCompileOutputDialog)) return;
        
        // 默认输出目录为当前文件所在目录
        String defaultDir = currentFile.getParent();
        // 默认输出文件名为源文件名去掉扩展名
        String sourceName = currentFile.getName();
        String defaultName = sourceName.contains(".") 
            ? sourceName.substring(0, sourceName.lastIndexOf('.'))
            : sourceName;
        
        // 创建主布局
        android.widget.LinearLayout mainLayout = new android.widget.LinearLayout(this);
        mainLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        mainLayout.setPadding(48, 16, 48, 0);
        
        // Material 3 分段按钮
        com.google.android.material.button.MaterialButtonToggleGroup toggleGroup = 
            new com.google.android.material.button.MaterialButtonToggleGroup(this);
        toggleGroup.setSingleSelection(true);
        toggleGroup.setSelectionRequired(true);
        android.widget.LinearLayout.LayoutParams toggleParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleParams.bottomMargin = 24;
        toggleGroup.setLayoutParams(toggleParams);
        
        // 编译按钮
        com.google.android.material.button.MaterialButton btnCompile = 
            new com.google.android.material.button.MaterialButton(this, null, 
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCompile.setId(View.generateViewId());
        btnCompile.setText("编译");
        btnCompile.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        // 运行按钮
        com.google.android.material.button.MaterialButton btnApk = 
            new com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnApk.setId(View.generateViewId());
        btnApk.setText("APK");
        btnApk.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        toggleGroup.addView(btnCompile);
        toggleGroup.addView(btnApk);
        toggleGroup.check(btnCompile.getId());
        
        mainLayout.addView(toggleGroup);
        
        // ========== 编译页面内容 ==========
        android.widget.LinearLayout compileLayout = new android.widget.LinearLayout(this);
        compileLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        
        // 输出目录输入框
        TextInputLayout dirInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        dirInputLayout.setHint("输出目录");
        dirInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText dirInput = new TextInputEditText(dirInputLayout.getContext());
        dirInput.setText(defaultDir);
        dirInput.setSelection(dirInput.getText().length());
        dirInputLayout.addView(dirInput);
        compileLayout.addView(dirInputLayout);
        
        // 间距
        android.view.View spacer1 = new android.view.View(this);
        spacer1.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 16));
        compileLayout.addView(spacer1);
        
        // 输出文件名输入框
        TextInputLayout nameInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        nameInputLayout.setHint("输出文件名");
        nameInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText nameInput = new TextInputEditText(nameInputLayout.getContext());
        nameInput.setText(defaultName);
        nameInput.setSelection(nameInput.getText().length());
        nameInputLayout.addView(nameInput);
        compileLayout.addView(nameInputLayout);
        
        mainLayout.addView(compileLayout);
        
        // ========== APK页面内容 ==========
        android.widget.LinearLayout apkLayout = new android.widget.LinearLayout(this);
        apkLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        apkLayout.setVisibility(View.GONE);
        
        // 软件名称输入框
        TextInputLayout appNameInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        appNameInputLayout.setHint("软件名称");
        appNameInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText appNameInput = new TextInputEditText(appNameInputLayout.getContext());
        appNameInput.setText(defaultName);
        appNameInputLayout.addView(appNameInput);
        apkLayout.addView(appNameInputLayout);
        
        // 间距
        android.view.View spacer2 = new android.view.View(this);
        spacer2.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 16));
        apkLayout.addView(spacer2);

        // 包名输入框（可选：用于区分不同安装包；不影响你源码里的 package 路径）
        TextInputLayout packageNameInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        packageNameInputLayout.setHint("包名（可选，用于区分安装）");
        packageNameInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText packageNameInput = new TextInputEditText(packageNameInputLayout.getContext());
        packageNameInput.setText("com.example.apk"); // 默认当前包名
        packageNameInputLayout.addView(packageNameInput);
        apkLayout.addView(packageNameInputLayout);

        // 间距
        android.view.View spacerPkg = new android.view.View(this);
        spacerPkg.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 16));
        apkLayout.addView(spacerPkg);
        
        // 软件版本Code输入框
        TextInputLayout versionCodeInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        versionCodeInputLayout.setHint("软件版本Code");
        versionCodeInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText versionCodeInput = new TextInputEditText(versionCodeInputLayout.getContext());
        versionCodeInput.setText("1");
        versionCodeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        versionCodeInputLayout.addView(versionCodeInput);
        apkLayout.addView(versionCodeInputLayout);
        
        // 间距
        android.view.View spacer3 = new android.view.View(this);
        spacer3.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 16));
        apkLayout.addView(spacer3);
        
        // 软件版本号输入框
        TextInputLayout versionNameInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        versionNameInputLayout.setHint("软件版本号");
        versionNameInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText versionNameInput = new TextInputEditText(versionNameInputLayout.getContext());
        versionNameInput.setText("1.0.0");
        versionNameInputLayout.addView(versionNameInput);
        apkLayout.addView(versionNameInputLayout);

        // 间距
        android.view.View spacerExec = new android.view.View(this);
        spacerExec.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 16));
        apkLayout.addView(spacerExec);

        // 自定义可执行文件路径（可选）
        TextInputLayout executablePathInputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        executablePathInputLayout.setHint("可执行文件路径（可选）");
        executablePathInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        executablePathInputLayout.setHelperText("留空则编译当前文件后打包");
        TextInputEditText executablePathInput = new TextInputEditText(executablePathInputLayout.getContext());
        executablePathInput.setSingleLine(true);
        executablePathInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        executablePathInputLayout.addView(executablePathInput);
        apkLayout.addView(executablePathInputLayout);
        
        // 图标选择（可选）
        pendingApkIconBitmap = null;
        TextView iconLabel = new TextView(this);
        iconLabel.setText("应用图标（可选）");
        iconLabel.setTextSize(12);
        iconLabel.setTextColor(0xFF938F99);
        iconLabel.setPadding(4, 12, 0, 8);
        apkLayout.addView(iconLabel);

        com.google.android.material.card.MaterialCardView iconCard =
            new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams iconCardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconCardParams.bottomMargin = 12;
        iconCard.setLayoutParams(iconCardParams);
        iconCard.setRadius(20f);
        iconCard.setStrokeWidth(1);
        iconCard.setStrokeColor(0x33FFFFFF);
        iconCard.setCardBackgroundColor(0x14FFFFFF);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int rowPadding = (int) (12 * getResources().getDisplayMetrics().density);
        iconRow.setPadding(rowPadding, rowPadding, rowPadding, rowPadding);

        com.google.android.material.card.MaterialCardView iconPreviewCard =
            new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
            (int) (56 * getResources().getDisplayMetrics().density),
            (int) (56 * getResources().getDisplayMetrics().density));
        previewParams.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
        iconPreviewCard.setLayoutParams(previewParams);
        iconPreviewCard.setRadius(12f * getResources().getDisplayMetrics().density);
        iconPreviewCard.setStrokeWidth((int) (1 * getResources().getDisplayMetrics().density));
        iconPreviewCard.setStrokeColor(0x334EC9B0);
        iconPreviewCard.setCardBackgroundColor(0x223E3E46);

        ImageView iconPreview = new ImageView(this);
        iconPreview.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconPreview.setImageResource(R.drawable.ic_image);
        iconPreview.setPadding(10, 10, 10, 10);
        iconPreviewCard.addView(iconPreview);

        LinearLayout iconTextWrap = new LinearLayout(this);
        iconTextWrap.setOrientation(LinearLayout.VERTICAL);
        iconTextWrap.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView iconHint = new TextView(this);
        iconHint.setText("未选择图标");
        iconHint.setTextSize(14);
        iconHint.setTextColor(0xFFE6E1E5);

        TextView iconSubHint = new TextView(this);
        iconSubHint.setText("建议 512x512 PNG/JPG");
        iconSubHint.setTextSize(12);
        iconSubHint.setTextColor(0xFF9A97A1);

        iconTextWrap.addView(iconHint);
        iconTextWrap.addView(iconSubHint);

        iconRow.addView(iconPreviewCard);
        iconRow.addView(iconTextWrap);
        iconCard.addView(iconRow);
        pendingApkIconHint = iconHint;
        apkLayout.addView(iconCard);

        com.google.android.material.button.MaterialButton pickIconButton =
            new com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        LinearLayout.LayoutParams pickBtnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pickBtnParams.gravity = android.view.Gravity.END;
        pickIconButton.setLayoutParams(pickBtnParams);
        pickIconButton.setInsetTop(0);
        pickIconButton.setInsetBottom(0);
        pickIconButton.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        pickIconButton.setStrokeWidth(1);
        pickIconButton.setText("选择图标");
        pickIconButton.setOnClickListener(v -> {
            pendingApkIconPreview = iconPreview;
            pendingApkIconHint = iconHint;
            apkIconPickerLauncher.launch("image/*");
        });
        apkLayout.addView(pickIconButton);

        mainLayout.addView(apkLayout);
        
        // 当前选中的页签 (0=编译, 1=APK)
        final int[] currentTab = {0};
        
        // 页签切换监听
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == btnCompile.getId()) {
                    currentTab[0] = 0;
                    compileLayout.setVisibility(View.VISIBLE);
                    apkLayout.setVisibility(View.GONE);
                } else if (checkedId == btnApk.getId()) {
                    currentTab[0] = 1;
                    compileLayout.setVisibility(View.GONE);
                    apkLayout.setVisibility(View.VISIBLE);
                }
            }
        });
        
        // M3 紫色
        int m3PrimaryColor = 0xFFD0BCFF;
        
        ScrollView formScrollView = new ScrollView(this);
        formScrollView.setFillViewport(true);
        formScrollView.addView(mainLayout);

        MaterialAlertDialogBuilder dialogBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("编译输出")
            .setView(formScrollView)
            .setPositiveButton("执行", (d, w) -> {
                if (currentTab[0] == 0) {
                    // 编译模式
                    String outputDir = dirInput.getText().toString().trim();
                    String outputName = nameInput.getText().toString().trim();
                    
                    if (outputDir.isEmpty() || outputName.isEmpty()) {
                        Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 检查目录是否存在
                    if (!RootFileUtils.exists(outputDir) || !RootFileUtils.isDirectory(outputDir)) {
                        Toast.makeText(this, "输出目录不存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    String outputPath = outputDir + "/" + outputName;
                    compileToOutput(currentFile.getAbsolutePath(), outputPath);
                } else {
                    // APK模式
                    String appName = appNameInput.getText().toString().trim();
                    String packageName = packageNameInput.getText().toString().trim();
                    String versionCode = versionCodeInput.getText().toString().trim();
                    String versionName = versionNameInput.getText().toString().trim();
                    String customExecutablePath = executablePathInput.getText().toString().trim();
                    
                    if (appName.isEmpty() || versionCode.isEmpty() || versionName.isEmpty()) {
                        Toast.makeText(this, "请填写完整的APK信息", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    try {
                        int versionCodeInt = Integer.parseInt(versionCode);
                        generateApk(appName, packageName, versionCodeInt, versionName, pendingApkIconBitmap, customExecutablePath);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "版本Code必须是数字", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null);
        
        androidx.appcompat.app.AlertDialog dialog = dialogBuilder.show();
        
        // 设置按钮颜色为 M3 紫色
        android.widget.Button positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        android.widget.Button negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
        
        if (positiveButton != null) {
            positiveButton.setTextColor(m3PrimaryColor);
        }
        if (negativeButton != null) {
            negativeButton.setTextColor(m3PrimaryColor);
        }
    }
    
    /**
     * 生成APK
     */
    private void generateApk(String appName, String packageName, int versionCode, String versionName, android.graphics.Bitmap selectedIcon, String customExecutablePath) {
        Runnable begin = () -> com.vkyra.code.signature.ApkExportUi.start(this, currentFile,
                appName, packageName, versionCode, versionName, selectedIcon, customExecutablePath);
        if (!saveDirtyDocumentsBefore(begin)) begin.run();
    }

    private void compileToOutput(String sourceFilePath, String outputPath) {
        // 显示编译中对话框
        Dialog loadingDialog = createCompileLoadingDialog();
        loadingDialog.show();
        
        // 记录编译开始时间
        long startTime = System.currentTimeMillis();
        
        // 编译输出模式使用混淆参数
        CppCompiler.compileToPathWithOllvmAsync(this, sourceFilePath, outputPath, new CppCompiler.CompileCallback() {
            @Override
            public void onSuccess(String outputFilePath) {
                stopCompileTimer();
                loadingDialog.dismiss();
                long elapsed = System.currentTimeMillis() - startTime;
                String timeStr = elapsed < 1000 ? elapsed + "ms" : format("%.2fs", elapsed / 1000.0);
                Toast.makeText(EditorActivity.this, "编译成功，输出: " + outputFilePath + "\n耗时 " + timeStr, Toast.LENGTH_LONG).show();
            }
            
            @Override
            public void onError(String errorMessage) {
                stopCompileTimer();
                loadingDialog.dismiss();
                showCompileErrorDialog(errorMessage);
            }
        });
    }
    
    /**
     * 格式化代码
     */
    // Formatting service boundary keeps document application separate from process execution.
    private java.util.function.BiConsumer<String, ClangFormatter.FormatCallback> formatRequest =
            (text, callback) -> ClangFormatter.formatAsync(this, text, callback);

    private void formatCode() {
        String code = codeEditor.getText().toString();
        if (code.isEmpty()) {
            Toast.makeText(this, "代码为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        final String formattingDocumentId = editorSession.activeDocumentId;
        // 保存光标位置（在格式化前）
        final int savedLine = codeEditor.getCursor().getLeftLine();
        final int savedCol = codeEditor.getCursor().getLeftColumn();
        
        // 显示格式化中提示
        Toast.makeText(this, "格式化中...", Toast.LENGTH_SHORT).show();
        
        formatRequest.accept(code, new ClangFormatter.FormatCallback() {
            @Override
            public void onSuccess(String formattedCode) {
                if (isDestroyed() || !java.util.Objects.equals(formattingDocumentId, editorSession.activeDocumentId)
                        || !code.equals(codeEditor.getText().toString())) return;
                // 使用 replace 替换全部内容，保留撤销历史
                io.github.rosemoe.sora.text.Content content = codeEditor.getText();
                int endLine = content.getLineCount() - 1;
                int endCol = content.getColumnCount(endLine);
                
                // 开始批量编辑（合并为一次撤销操作）
                content.beginBatchEdit();
                try {
                    // 删除所有内容并插入新内容
                    content.replace(0, 0, endLine, endCol, formattedCode);
                } finally {
                    content.endBatchEdit();
                }
                
                // 延迟恢复光标位置和滚动位置（等待编辑器完成渲染）
                codeEditor.post(() -> {
                    if (isDestroyed() || !java.util.Objects.equals(formattingDocumentId, editorSession.activeDocumentId)
                            || !formattedCode.equals(codeEditor.getText().toString())) return;
                    try {
                        int targetLine = Math.min(savedLine, codeEditor.getLineCount() - 1);
                        if (targetLine >= 0) {
                            int lineLen = codeEditor.getText().getLine(targetLine).length();
                            int targetCol = Math.min(savedCol, lineLen);
                            codeEditor.setSelection(targetLine, targetCol);
                            
                            // 计算目标行的 Y 坐标
                            float[] offset = codeEditor.getLayout().getCharLayoutOffset(targetLine, targetCol);
                            int targetY = (int) (offset[0] - codeEditor.getHeight() / 2f);
                            targetY = Math.max(0, targetY);
                            
                            // 使用 ValueAnimator 平滑滚动
                            final int startY = (int) codeEditor.getOffsetY();
                            final int endY = targetY;
                            android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(startY, endY);
                            animator.setDuration(250);
                            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
                            animator.addUpdateListener(animation -> {
                                if (isDestroyed() || !java.util.Objects.equals(formattingDocumentId, editorSession.activeDocumentId)
                                        || !formattedCode.equals(codeEditor.getText().toString())) {
                                    animation.cancel();
                                    return;
                                }
                                int value = (int) animation.getAnimatedValue();
                                codeEditor.getScroller().startScroll(0, value, 0, 0, 0);
                                codeEditor.invalidate();
                            });
                            animator.start();
                        }
                    } catch (Exception ignored) {}
                });
                
                Toast.makeText(EditorActivity.this, "格式化完成", Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onError(String errorMessage) {
                if (isDestroyed() || !java.util.Objects.equals(formattingDocumentId, editorSession.activeDocumentId)
                        || !code.equals(codeEditor.getText().toString())) return;
                new MaterialAlertDialogBuilder(EditorActivity.this)
                    .setTitle("格式化失败")
                    .setMessage(errorMessage)
                    .setPositiveButton("确定", null)
                    .show();
            }
        });
    }
    
    private void showCompileErrorDialog(String errorMessage) {
        // 标记错误行
        markCompileErrors(errorMessage);
        
        // 检测当前主题是否为浅色
        boolean isLightTheme = AppPreferences.getEditorTheme(this).equals("light");
        
        // 创建可滚动的 TextView
        TextView textView = new TextView(this);
        textView.setText(errorMessage);
        // 根据主题设置文字颜色
        textView.setTextColor(isLightTheme ? 0xFF212121 : 0xFFE0E0E0);
        textView.setTextSize(13);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setPadding(48, 24, 48, 24);
        textView.setTextIsSelectable(true);
        
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(textView);

        new MaterialAlertDialogBuilder(this)
            .setTitle("编译失败")
            .setView(scrollView)
            .setPositiveButton("确定", null)
            .setNeutralButton("复制全部", (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("error", errorMessage));
                Toast.makeText(this, "已复制错误信息", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("跳转错误", (d, w) -> {
                // 跳转到第一个错误行
                jumpToFirstError(errorMessage);
            })
            .show();
    }
    
    /**
     * 跳转到第一个错误行
     */
    private void jumpToFirstError(String errorMessage) {
        Pattern errorPattern = Pattern.compile("(?:^|\\n)([^:\\n]+):(\\d+)(?::(\\d+))?:\\s*error:", Pattern.MULTILINE);
        Matcher matcher = errorPattern.matcher(errorMessage);
        
        String currentFileName = currentFile != null ? currentFile.getName() : "";
        
        if (matcher.find()) {
            try {
                String fileName = matcher.group(1);
                int line = Integer.parseInt(matcher.group(2)) - 1;
                String colStr = matcher.group(3);
                int col = colStr != null ? Integer.parseInt(colStr) - 1 : 0;
                
                if (fileName.endsWith(currentFileName) || fileName.contains(currentFileName)) {
                    if (line >= 0 && line < codeEditor.getLineCount()) {
                        int lineLength = codeEditor.getText().getLine(line).length();
                        col = Math.max(0, Math.min(col, lineLength));
                        codeEditor.setSelection(line, col);
                        Toast.makeText(this, "已跳转到第 " + (line + 1) + " 行", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 先添加 position=start 的插件菜单项
        addPluginMenuItems(menu, true);
        
        // 再加载系统菜单
        getMenuInflater().inflate(R.menu.editor_menu, menu);
        
        // 根据主题设置图标颜色
        applyMenuIconTint(menu);
        
        // 最后添加 position=end 的插件菜单项
        addPluginMenuItems(menu, false);
        
        // 设置 Split Button 运行按钮
        setupRunSplitButton(menu);
        
        // 初始化保存菜单项
        saveMenuItem = menu.findItem(R.id.action_save);
        
        // 初始化 Git 菜单项
        gitMenuItem = menu.findItem(R.id.action_git);
        updateGitMenu();
        
        // 更新工具栏布局（响应式）
        updateToolbarLayout();
        
        return true;
    }
    
    /**
     * 更新工具栏布局（响应式）
     * 窄屏且显示 Git 时，隐藏保存按钮（移到更多菜单）
     */
    private void updateToolbarLayout() {
        if (saveMenuItem == null) return;
        
        // 获取屏幕宽度
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        int screenWidthDp = (int) (screenWidth / density);
        
        // 窄屏（<600dp）且 Git 可见时，隐藏保存按钮
        boolean isNarrowScreen = screenWidthDp < 600;
        boolean gitVisible = gitMenuItem != null && gitMenuItem.isVisible();
        
        if (isNarrowScreen && gitVisible) {
            // 窄屏且有 Git，保存按钮移到更多菜单
            saveMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            saveButtonHidden = true;
        } else {
            // 宽屏或无 Git，保存按钮显示在工具栏
            saveMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            saveButtonHidden = false;
        }
    }
    
    // 运行模式数据
    private static final String[] RUN_MODE_TITLES = {"运行", "带参数运行", "终端编译运行", "重新编译", "编译输出", "CMake"};
    private static final int[] RUN_MODE_ICONS = {R.drawable.ic_run, R.drawable.ic_run, R.drawable.ic_terminal, 
                                                  R.drawable.ic_refresh, R.drawable.ic_output, R.drawable.ic_cmake};
    
    // 保存 Split Button 的引用，用于更新
    private ImageView splitBtnIcon;
    private TextView splitBtnLabel;
    
    /**
     * 设置运行 Split Button
     */
    private void setupRunSplitButton(Menu menu) {
        MenuItem runItem = menu.findItem(R.id.action_run);
        if (runItem == null) return;
        
        View actionView = runItem.getActionView();
        if (actionView == null) return;
        
        // 根据主题设置颜色
        int runColor = currentThemeIsDark ? 0xFF4EC9B0 : 0xFF008060;  // 暗色用浅绿，亮色用深绿
        
        // 设置背景
        View btnRunMain = actionView.findViewById(R.id.btn_run_main);
        View btnDropdown = actionView.findViewById(R.id.btn_run_dropdown);
        if (!currentThemeIsDark) {
            if (btnRunMain != null) btnRunMain.setBackgroundResource(R.drawable.split_button_left_bg_light);
            if (btnDropdown != null) btnDropdown.setBackgroundResource(R.drawable.split_button_right_bg_light);
        }
        
        // 设置图标和文字颜色
        splitBtnIcon = actionView.findViewById(R.id.iv_run_icon);
        splitBtnLabel = actionView.findViewById(R.id.tv_run_label);
        ImageView ivRunDropdown = actionView.findViewById(R.id.iv_run_dropdown);
        
        if (splitBtnIcon != null) splitBtnIcon.setColorFilter(runColor);
        if (splitBtnLabel != null) splitBtnLabel.setTextColor(runColor);
        if (ivRunDropdown != null) ivRunDropdown.setColorFilter(runColor);
        
        // 根据保存的运行模式更新按钮
        updateRunButtonAppearance();
        
        // 主运行按钮 - 执行当前选中的运行模式
        if (btnRunMain != null) {
            btnRunMain.setOnClickListener(v -> executeCurrentRunMode());
            btnRunMain.setOnLongClickListener(v -> {
                showRunArgsAndRunDialog();
                return true;
            });
        }
        
        // 下拉按钮 - 显示运行选项菜单
        if (btnDropdown != null && ivRunDropdown != null) {
            btnDropdown.setOnClickListener(v -> showRunOptionsMenu(v, ivRunDropdown));
        }
    }
    
    /**
     * 运行菜单项数据（用于合并内置和插件菜单）
     */
    private static class RunMenuItem {
        boolean isBuiltin;
        int builtinIndex;  // 内置菜单的索引
        PluginHookManager.RunMenuItemConfig pluginConfig;  // 插件配置
        
        static RunMenuItem builtin(int index) {
            RunMenuItem item = new RunMenuItem();
            item.isBuiltin = true;
            item.builtinIndex = index;
            return item;
        }
        
        static RunMenuItem plugin(PluginHookManager.RunMenuItemConfig config) {
            RunMenuItem item = new RunMenuItem();
            item.isBuiltin = false;
            item.pluginConfig = config;
            return item;
        }
    }
    
    /**
     * 显示运行选项下拉菜单
     */
    private void showRunOptionsMenu(View anchor, ImageView dropdownIcon) {
        // 箭头旋转动画（向上）
        if (dropdownIcon != null) {
            dropdownIcon.animate()
                .rotation(180f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        }
        
        // 获取插件运行菜单项（按位置分组）
        PluginHookManager hookManager = PluginManager.getHookManager(this);
        List<PluginHookManager.RunMenuItemConfig> startItems = hookManager.getRunMenuItemsStart();
        List<PluginHookManager.RunMenuItemConfig> endItems = hookManager.getRunMenuItemsEnd();
        
        // 构建合并的菜单列表：start插件 -> 内置 -> end插件
        List<RunMenuItem> menuItems = new ArrayList<>();
        
        // 1. 添加 position=start 的插件菜单
        for (PluginHookManager.RunMenuItemConfig config : startItems) {
            menuItems.add(RunMenuItem.plugin(config));
        }
        
        // 2. 添加内置菜单
        for (int i = 0; i < RUN_MODE_TITLES.length; i++) {
            menuItems.add(RunMenuItem.builtin(i));
        }
        
        // 3. 添加 position=end 的插件菜单
        for (PluginHookManager.RunMenuItemConfig config : endItems) {
            menuItems.add(RunMenuItem.plugin(config));
        }
        
        // 运行图标颜色根据主题
        int runIconColor = currentThemeIsDark ? 0xFF4EC9B0 : 0xFF008060;
        int normalIconColor = currentThemeIsDark ? 0xFF9E9E9E : 0xFF666666;
        int normalTextColor = currentThemeIsDark ? 0xFFE0E0E0 : 0xFF1E1E1E;
        
        // 当前选中的运行模式
        int currentMode = AppPreferences.getRunMode(this);
        
        androidx.appcompat.widget.ListPopupWindow popup = new androidx.appcompat.widget.ListPopupWindow(this);
        popup.setAnchorView(anchor);
        popup.setWidth((int) (160 * getResources().getDisplayMetrics().density));
        popup.setHeight(android.widget.ListPopupWindow.WRAP_CONTENT);
        popup.setModal(true);
        popup.setBackgroundDrawable(getDrawable(currentThemeIsDark ? R.drawable.popup_menu_bg : R.drawable.popup_menu_bg_light));
        popup.setVerticalOffset(8);
        popup.setHorizontalOffset(-80);
        
        // 自定义适配器
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() { return menuItems.size(); }
            @Override
            public Object getItem(int position) { return menuItems.get(position); }
            @Override
            public long getItemId(int position) { return position; }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(EditorActivity.this)
                        .inflate(R.layout.item_run_option, parent, false);
                }
                ImageView ivIcon = convertView.findViewById(R.id.iv_icon);
                TextView tvTitle = convertView.findViewById(R.id.tv_title);
                
                RunMenuItem item = menuItems.get(position);
                boolean isSelected = (position == currentMode);
                
                if (item.isBuiltin) {
                    // 内置菜单项
                    ivIcon.setImageResource(RUN_MODE_ICONS[item.builtinIndex]);
                    tvTitle.setText(RUN_MODE_TITLES[item.builtinIndex]);
                } else {
                    // 插件菜单项
                    tvTitle.setText(item.pluginConfig.title);
                    
                    // 尝试加载 SVG 图标
                    android.graphics.drawable.Drawable svgDrawable = 
                        SvgUtils.base64SvgToDrawable(EditorActivity.this, item.pluginConfig.iconSvgBase64, 18);
                    if (svgDrawable != null) {
                        ivIcon.setImageDrawable(svgDrawable);
                    } else {
                        ivIcon.setImageResource(R.drawable.ic_run);
                    }
                }
                
                // 设置颜色
                ivIcon.setColorFilter(isSelected ? runIconColor : normalIconColor);
                tvTitle.setTextColor(isSelected ? runIconColor : normalTextColor);
                
                return convertView;
            }
        };
        
        popup.setAdapter(adapter);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            // 保存选中的运行模式（使用列表位置作为模式索引）
            AppPreferences.setRunMode(this, position);
            // 更新按钮外观
            updateRunButtonAppearance();
        });
        
        // 菜单关闭时恢复箭头方向
        popup.setOnDismissListener(() -> {
            if (dropdownIcon != null) {
                dropdownIcon.animate()
                    .rotation(0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            }
        });
        
        popup.show();
    }
    
    /**
     * 构建运行菜单项列表（用于更新按钮和执行操作）
     */
    private List<RunMenuItem> buildRunMenuItems() {
        PluginHookManager hookManager = PluginManager.getHookManager(this);
        List<PluginHookManager.RunMenuItemConfig> startItems = hookManager.getRunMenuItemsStart();
        List<PluginHookManager.RunMenuItemConfig> endItems = hookManager.getRunMenuItemsEnd();
        
        List<RunMenuItem> menuItems = new ArrayList<>();
        
        // 1. position=start 的插件菜单
        for (PluginHookManager.RunMenuItemConfig config : startItems) {
            menuItems.add(RunMenuItem.plugin(config));
        }
        
        // 2. 内置菜单
        for (int i = 0; i < RUN_MODE_TITLES.length; i++) {
            menuItems.add(RunMenuItem.builtin(i));
        }
        
        // 3. position=end 的插件菜单
        for (PluginHookManager.RunMenuItemConfig config : endItems) {
            menuItems.add(RunMenuItem.plugin(config));
        }
        
        return menuItems;
    }
    
    /**
     * 更新运行按钮外观为插件菜单项
     */
    private void updateRunButtonAppearanceForPlugin(PluginHookManager.RunMenuItemConfig config) {
        if (splitBtnLabel != null) {
            splitBtnLabel.setText(config.title);
        }
        if (splitBtnIcon != null) {
            android.graphics.drawable.Drawable svgDrawable = 
                SvgUtils.base64SvgToDrawable(this, config.iconSvgBase64, 18);
            if (svgDrawable != null) {
                splitBtnIcon.setImageDrawable(svgDrawable);
            } else {
                splitBtnIcon.setImageResource(R.drawable.ic_run);
            }
            // 应用主题颜色
            int runColor = currentThemeIsDark ? 0xFF4EC9B0 : 0xFF008060;
            splitBtnIcon.setColorFilter(runColor);
        }
    }
    
    /**
     * 更新运行按钮的外观（图标和文字）
     */
    private void updateRunButtonAppearance() {
        int mode = AppPreferences.getRunMode(this);
        List<RunMenuItem> menuItems = buildRunMenuItems();
        
        if (mode < 0 || mode >= menuItems.size()) {
            mode = 0;
            AppPreferences.setRunMode(this, 0);
        }
        
        RunMenuItem item = menuItems.get(mode);
        int runColor = currentThemeIsDark ? 0xFF4EC9B0 : 0xFF008060;
        
        if (item.isBuiltin) {
            if (splitBtnIcon != null) {
                splitBtnIcon.setImageResource(RUN_MODE_ICONS[item.builtinIndex]);
                splitBtnIcon.setColorFilter(runColor);
            }
            if (splitBtnLabel != null) {
                splitBtnLabel.setText(RUN_MODE_TITLES[item.builtinIndex]);
            }
        } else {
            updateRunButtonAppearanceForPlugin(item.pluginConfig);
        }
    }
    
    /**
     * 执行当前选中的运行模式
     */
    private void executeCurrentRunMode() {
        int mode = AppPreferences.getRunMode(this);
        List<RunMenuItem> menuItems = buildRunMenuItems();
        
        if (mode < 0 || mode >= menuItems.size()) {
            runCode();
            return;
        }
        
        RunMenuItem item = menuItems.get(mode);
        
        if (item.isBuiltin) {
            // 内置运行模式
            executeRunMode(item.builtinIndex);
        } else {
            // 插件运行模式
            java.util.Map<String, String> extraVars = new java.util.HashMap<>();
            extraVars.put("current_file", currentFile != null ? currentFile.getAbsolutePath() : "");
            extraVars.put("current_dir", currentDir != null ? currentDir.getAbsolutePath() : "");
            PluginManager.getHookManager(this).executeRunMenuClick(this, item.pluginConfig, extraVars, null);
        }
    }
    
    /**
     * 执行指定的运行模式
     */
    private void executeRunMode(int mode) {
        switch (mode) {
            case 0: runCode(); break;
            case 1: showRunArgsAndRunDialog(); break;
            case 2: terminalCompileAndRun(); break;
            case 3: recompile(); break;
            case 4: showCompileOutputDialog(); break;
            case 5: runCMakeBuild(); break;
            default: runCode(); break;
        }
    }
    
    /**
     * 根据主题设置菜单图标颜色
     */
    private void applyMenuIconTint(Menu menu) {
        int iconColor = currentThemeIsDark ? 0xFFCCCCCC : 0xFF666666;
        
        // 设置保存按钮图标颜色
        MenuItem saveItem = menu.findItem(R.id.action_save);
        if (saveItem != null && saveItem.getIcon() != null) {
            saveItem.getIcon().setTint(iconColor);
        }
    }
    
    /**
     * 添加插件菜单项
     * @param isStart true=只添加 position=start 的项，false=添加其他项
     */
    private void addPluginMenuItems(Menu menu, boolean isStart) {
        PluginHookManager hookManager = PluginManager.getHookManager(this);
        List<PluginHookManager.MenuItemConfig> menuItems = hookManager.getMenuItems();
        
        if (menuItems.isEmpty()) return;
        
        for (PluginHookManager.MenuItemConfig config : menuItems) {
            String position = config.position;
            
            // 根据 isStart 过滤
            boolean isStartPosition = position.equals("start");
            if (isStart != isStartPosition) continue;
            
            // 使用唯一 ID
            int itemId = Menu.FIRST + 1000 + menuItems.indexOf(config);
            MenuItem item = menu.add(Menu.NONE, itemId, Menu.NONE, config.title);
            
            // 保存配置引用
            final PluginHookManager.MenuItemConfig finalConfig = config;
            pluginMenuConfigs.put(itemId, finalConfig);
        }
    }
    
    // 存储插件菜单配置
    private final java.util.Map<Integer, PluginHookManager.MenuItemConfig> pluginMenuConfigs = new java.util.HashMap<>();
    
    /**
     * 打开终端，以当前项目或文件目录为工作目录
     */
    private void openTerminalHere() {
        String workingDir = null;
        
        // 优先使用当前目录（项目目录）
        if (currentDir != null && currentDir.exists()) {
            workingDir = currentDir.getAbsolutePath();
        }
        // 如果没有当前目录，使用当前文件所在目录
        else if (currentFile != null && currentFile.getParentFile() != null) {
            workingDir = currentFile.getParentFile().getAbsolutePath();
        }
        
        Intent intent = terminalIntent();
        if (workingDir != null) {
            intent.putExtra("WORKING_DIR", workingDir);
        }
        startActivity(intent);
    }
    
    /**
     * 显示编译模式选择对话框
     */
    private void showCompileModeDialog() {
        if (currentFile == null || currentDir == null) {
            Toast.makeText(this, "请先打开一个文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 检查当前目录是否有配置文件
        File configFile = new File(currentDir, CONFIG_FILE_NAME);
        boolean hasConfig = configFile.exists();
        
        // 读取当前模式
        int currentMode = compileMode;
        if (hasConfig) {
            currentMode = 1;
        }
        
        // 读取现有配置
        String existingConfig = "";
        if (configFile.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                existingConfig = sb.toString().trim();
            } catch (Exception e) {
                existingConfig = "";
            }
        } else if (currentFile != null) {
            existingConfig = currentFile.getName();
        }
        
        // 扫描当前目录的源文件
        File[] files = currentDir.listFiles((dir, name) -> 
            name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx"));
        
        StringBuilder hint = new StringBuilder("当前目录源文件: ");
        if (files != null && files.length > 0) {
            for (int i = 0; i < files.length; i++) {
                if (i > 0) hint.append(", ");
                hint.append(files[i].getName());
            }
        } else {
            hint.append("(无)");
        }
        
        // 创建布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 0);
        
        // 单选按钮组
        android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(this);
        radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        
        android.widget.RadioButton rbSingle = new android.widget.RadioButton(this);
        rbSingle.setText("单文件编译 (默认)");
        rbSingle.setTextColor(0xFFCCCCCC);
        rbSingle.setId(View.generateViewId());
        radioGroup.addView(rbSingle);
        
        android.widget.RadioButton rbMulti = new android.widget.RadioButton(this);
        rbMulti.setText("多文件编译");
        rbMulti.setTextColor(0xFFCCCCCC);
        rbMulti.setId(View.generateViewId());
        radioGroup.addView(rbMulti);
        
        layout.addView(radioGroup);
        
        // 多文件配置区域（初始隐藏）
        android.widget.LinearLayout multiConfigLayout = new android.widget.LinearLayout(this);
        multiConfigLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        multiConfigLayout.setPadding(0, 16, 0, 0);
        multiConfigLayout.setVisibility(currentMode == 1 ? View.VISIBLE : View.GONE);
        
        // 提示文本
        TextView hintText = new TextView(this);
        hintText.setText(hint.toString());
        hintText.setTextSize(11);
        hintText.setTextColor(0xFF888888);
        hintText.setPadding(0, 0, 0, 12);
        multiConfigLayout.addView(hintText);
        
        // 输入框
        TextInputLayout inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("源文件列表 (每行一个)");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setText(existingConfig);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP);
        inputLayout.addView(input);
        multiConfigLayout.addView(inputLayout);
        
        layout.addView(multiConfigLayout);
        
        // 设置初始选中状态
        if (currentMode == 1) {
            rbMulti.setChecked(true);
        } else {
            rbSingle.setChecked(true);
        }
        
        // 监听选择变化
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rbMulti.getId()) {
                multiConfigLayout.setVisibility(View.VISIBLE);
            } else {
                multiConfigLayout.setVisibility(View.GONE);
            }
        });
        
        final String finalExistingConfig = existingConfig;
        new MaterialAlertDialogBuilder(this)
            .setTitle("编译模式")
            .setView(layout)
            .setPositiveButton("确定", (dialog, which) -> {
                if (rbMulti.isChecked()) {
                    // 多文件模式
                    compileMode = 1;
                    String config = input.getText().toString().trim();
                    if (config.isEmpty()) {
                        Toast.makeText(this, "请输入至少一个源文件", Toast.LENGTH_SHORT).show();
                        compileMode = 0;
                        return;
                    }
                    
                    // 保存配置文件
                    try {
                        try (FileWriter writer = new FileWriter(configFile)) {
                            writer.write(config);
                        }
                        Toast.makeText(this, "多文件编译模式已启用", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "保存配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        compileMode = 0;
                    }
                } else {
                    // 单文件模式
                    compileMode = 0;
                    if (configFile.exists()) {
                        configFile.delete();
                    }
                    Toast.makeText(this, "单文件编译模式", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 获取多文件编译的源文件列表
     */
    /**
     * 显示文件完整路径对话框
     */
    private void showFilePathDialog() {
        String filePath;
        if (currentFile != null) {
            filePath = currentFile.getAbsolutePath();
        } else {
            filePath = "未保存的文件";
        }
        
        new MaterialAlertDialogBuilder(this)
            .setTitle("文件路径")
            .setMessage(filePath)
            .setPositiveButton("复制", (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("file_path", filePath));
                Toast.makeText(this, "已复制路径", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("关闭", null)
            .show();
    }
    
    /**
     * 定位到当前编辑文件所在的文件夹
     */
    private void locateCurrentFile() {
        if (currentFile == null) {
            Toast.makeText(this, "当前文件未保存", Toast.LENGTH_SHORT).show();
            return;
        }
        
        File parentDir = currentFile.getParentFile();
        if (parentDir != null && parentDir.exists()) {
            loadDirectory(parentDir);
            Toast.makeText(this, "已定位到: " + currentFile.getName(), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "文件夹不存在", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) { saveFile(); return true; }
        else if (id == R.id.action_search) { showFindReplaceBar(null); return true; }
        else if (id == R.id.action_goto_line) { showGotoLineDialog(); return true; }
        else if (id == R.id.action_format) { formatCode(); return true; }
        else if (id == R.id.action_compile_mode) { showCompileModeDialog(); return true; }
        else if (id == R.id.action_compile_command) { showCompileCommandMenu(); return true; }
        else if (id == R.id.action_git) { showGitDialog(); return true; }
        else if (id == R.id.action_open_terminal) { openTerminalHere(); return true; }
        else if (id == R.id.action_settings) { startActivity(new Intent(this, SettingsActivity.class)); return true; }


        // 检查是否是插件菜单项
        if (pluginMenuConfigs.containsKey(id)) {
            PluginHookManager.MenuItemConfig config = pluginMenuConfigs.get(id);
            if (config != null) {
                // 构建额外变量
                java.util.Map<String, String> extraVars = new java.util.HashMap<>();
                extraVars.put("current_file", currentFile != null ? currentFile.getAbsolutePath() : "");
                extraVars.put("current_dir", currentDir != null ? currentDir.getAbsolutePath() : "");
                
                // 执行菜单点击事件
                PluginManager.getHookManager(this).executeMenuClick(this, config, extraVars, null);
                return true;
            }
        }
        
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            restoreImeAfterOverflowMenu = isEditorImeVisible();
            if (restoreImeAfterOverflowMenu) {
                // 立即维持输入法状态，避免出现“菜单打开时先收起、关闭后再弹回”的闪动。
                restoreEditorIme();
            }
        }
        return super.onMenuOpened(featureId, menu);
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        super.onPanelClosed(featureId, menu);
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            restoreImeAfterOverflowMenu = false;
        }
    }

    private boolean isEditorImeVisible() {
        if (codeEditor == null) return false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        // 某些 ROM 在菜单展开时会让编辑器短暂失去焦点，这里宽松判断输入法活跃态。
        return imm.isActive(codeEditor) || imm.isAcceptingText();
    }

    private void restoreEditorIme() {
        if (codeEditor == null) return;
        codeEditor.post(() -> {
            if (isFinishing()) return;
            codeEditor.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(codeEditor, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (isModified) {
            new MaterialAlertDialogBuilder(this)
                .setTitle("保存更改")
                .setMessage("当前文件已修改，是否保存？")
                .setPositiveButton("保存", (d, w) -> { saveFile(this::finish); })
                .setNegativeButton("不保存", (d, w) -> finish())
                .setNeutralButton("取消", null)
                .show();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 为指定架构编译文件
     */
}
