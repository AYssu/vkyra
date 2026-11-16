package com.vkyra.code.terminal;

import com.vkyra.code.Log;
import com.vkyra.code.R;
import com.vkyra.code.plugin.PluginHookManager;
import com.vkyra.code.plugin.PluginManager;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.settings.SettingsActivity;
import com.vkyra.code.toolchain.BuildToolsInstaller;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.CompilerArguments;
import com.vkyra.code.toolchain.ZshInstaller;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
// Use local Log wrapper that also writes to file
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class TerminalActivity extends AppCompatActivity implements TerminalViewClient, TerminalSessionClient {

    public static final String RUN_ARGS = "RUN_ARGS";
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private TerminalSessionViewModel sessionOwner;
    private String launchRunArgs = "";
    private boolean destroyed;
    private static final String LOG_TAG = "TerminalActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private TerminalView mTerminalView;
    private TerminalSession mTerminalSession;
    private ClipboardManager mClipboardManager;
    private InputMethodManager mInputMethodManager;
    private Vibrator mVibrator;

    private LinearLayout terminalControlBar;
    private BroadcastReceiver mBottomToolbarSettingsReceiver;
    
    // 编译相关
    private String compileFilePath = null;
    private String runFilePath = null;
    private String runCommand = null;
    private String executeCommand = null;  // 直接执行的命令（Make/CMake等）
    
    // 重置标志
    private boolean isResetting = false;
    
    // 会话结束标志，等待用户按回车退出
    private boolean isSessionFinished = false;
    
    // 终端刷新节流控制
    private volatile boolean mPendingInvalidate = false;
    private final Object mInvalidateLock = new Object();
    // 方向键长按连发配置
    private static final long ARROW_REPEAT_INITIAL_DELAY_MS = 220;
    private static final long ARROW_REPEAT_INTERVAL_MS = 45;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        sessionOwner = new ViewModelProvider(this).get(TerminalSessionViewModel.class);
        sessionOwner.attach(this);
        launchRunArgs = getIntent().getStringExtra(RUN_ARGS);
        if (launchRunArgs == null) launchRunArgs = "";

        // 设置沉浸式状态栏
        setupImmersiveStatusBar();

        // 初始化文件日志
        Log.init(getApplicationContext());

        mClipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        mInputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        
        // 初始化震动器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            mVibrator = vibratorManager.getDefaultVibrator();
        } else {
            mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        
        // 检查是否有编译任务或运行任务
        compileFilePath = getIntent().getStringExtra("COMPILE_FILE");
        runFilePath = getIntent().getStringExtra("RUN_FILE");
        runCommand = getIntent().getStringExtra("RUN_COMMAND");
        executeCommand = getIntent().getStringExtra("EXECUTE_COMMAND");
        
        initViews();
        setupTerminal();
        
        // 加载设置
        AppPreferences.loadFromConfigFile(this);
        
        // 确保有默认设置
        AppPreferences.ensureDefaultSettings(this);
        
        // 应用字体设置
        applyFontSettings();
        
        // 设置软键盘可见性监听器
        setupKeyboardVisibilityListener();
    }
    
    /**
     * 设置软键盘可见性监听器
     */
    private void setupKeyboardVisibilityListener() {
        final View rootView = findViewById(android.R.id.content);
        keyboardVisibilityListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (rootView == null) return;

                Rect visibleFrame = new Rect();
                rootView.getWindowVisibleDisplayFrame(visibleFrame);
                int screenHeight = rootView.getRootView().getHeight();
                int keyboardHeight = screenHeight - visibleFrame.bottom;
                boolean keyboardCurrentlyVisible = keyboardHeight > screenHeight * 0.15;

                if (!keyboardCurrentlyVisible) {
                    keyboardCurrentlyVisible = KeyboardUtils.isSoftKeyboardVisible(TerminalActivity.this);
                }

                if (keyboardCurrentlyVisible != isKeyboardVisible) {
                    isKeyboardVisible = keyboardCurrentlyVisible;
                    updateKeyboardButtonText();
                    Log.d(LOG_TAG, "Keyboard visibility changed to: " + isKeyboardVisible
                        + " (keyboard height: " + keyboardHeight + ")");
                }
            }
        };
        
        if (rootView != null) {
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardVisibilityListener);
            Log.d(LOG_TAG, "Keyboard visibility listener setup completed");
        }
    }

    // 手动显示软键盘
    public void showKeyboard() {
        Log.d(LOG_TAG, "showKeyboard() called");
        if (mInputMethodManager != null && mTerminalView != null) {
            isKeyboardVisible = true;
            updateKeyboardButtonText();
            // 确保TerminalView可以获得焦点
            mTerminalView.setFocusable(true);
            mTerminalView.setFocusableInTouchMode(true);
            mTerminalView.requestFocus();
            
            // 显示软键盘
            boolean result = mInputMethodManager.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
            Log.d(LOG_TAG, "showKeyboard result: " + result);
        } else {
            Log.e(LOG_TAG, "showKeyboard failed - InputMethodManager or TerminalView is null");
        }
    }

    // 手动隐藏软键盘
    public void hideKeyboard() {
        if (mInputMethodManager != null) {
            // 立即尝试隐藏软键盘
            mInputMethodManager.hideSoftInputFromWindow(mTerminalView.getWindowToken(), 0);
            
            // 清除焦点
            mTerminalView.clearFocus();
            
            // 延迟再次尝试，确保软键盘完全隐藏
            lifecycleHandler.postDelayed(() -> {
                mInputMethodManager.hideSoftInputFromWindow(mTerminalView.getWindowToken(), 0);
                mTerminalView.clearFocus();
                Log.d(LOG_TAG, "Keyboard hide attempted with delay");
            }, 300);
            
            // 再次延迟尝试
            lifecycleHandler.postDelayed(() -> {
                mInputMethodManager.hideSoftInputFromWindow(mTerminalView.getWindowToken(), 0);
                Log.d(LOG_TAG, "Final keyboard hide attempt");
            }, 600);
        }
    }

    // 键盘状态跟踪
    private boolean isKeyboardVisible = false;
    
    // 软键盘可见性监听器
    private ViewTreeObserver.OnGlobalLayoutListener keyboardVisibilityListener;

    // 切换软键盘显示状�
    private void toggleKeyboard() {
        if (mInputMethodManager != null) {
            // 检查实际的软键盘状�
            boolean actuallyVisible = mInputMethodManager.isActive(mTerminalView);
            Log.d(LOG_TAG, "Toggle keyboard - isKeyboardVisible: " + isKeyboardVisible + ", actuallyVisible: " + actuallyVisible);
            
            if (actuallyVisible || isKeyboardVisible) {
                // 软键盘当前显示，隐藏它
                hideKeyboardCompletely();
                Log.d(LOG_TAG, "Keyboard hidden");
            } else {
                // 软键盘当前隐藏，显示它
                showKeyboardCompletely();
                Log.d(LOG_TAG, "Keyboard shown");
            }
        }
    }

    // 完全隐藏软键盘
    private void hideKeyboardCompletely() {
        Log.d(LOG_TAG, "hideKeyboardCompletely called");
        if (mInputMethodManager != null) {
            // 立即更新状态
            isKeyboardVisible = false;
            updateKeyboardButtonText();
            
            // 清除焦点
            if (mTerminalView != null) {
                mTerminalView.clearFocus();
            }
            
            // 隐藏软键盘
            mInputMethodManager.hideSoftInputFromWindow(mTerminalView.getWindowToken(), 0);
            
            // 延迟再次尝试，确保软键盘完全隐藏
            if (mTerminalView != null) {
                lifecycleHandler.postDelayed(() -> {
                    mInputMethodManager.hideSoftInputFromWindow(mTerminalView.getWindowToken(), 0);
                    mTerminalView.clearFocus();
                    Log.d(LOG_TAG, "Keyboard hide completed - state: " + isKeyboardVisible);
                }, 100);
            }
        }
    }

    // 完全显示软键盘
    private void showKeyboardCompletely() {
        Log.d(LOG_TAG, "showKeyboardCompletely called");
        Log.d(LOG_TAG, "mInputMethodManager: " + (mInputMethodManager != null ? "not null" : "null"));
        Log.d(LOG_TAG, "mTerminalView: " + (mTerminalView != null ? "not null" : "null"));
        
        if (mInputMethodManager != null && mTerminalView != null) {
            isKeyboardVisible = true;
            updateKeyboardButtonText();
            
            // 确保TerminalView可以获得焦点
            mTerminalView.setFocusable(true);
            mTerminalView.setFocusableInTouchMode(true);
            
            // 请求焦点
            boolean focusResult = mTerminalView.requestFocus();
            Log.d(LOG_TAG, "Focus request result: " + focusResult);
            
            // 延迟显示软键盘，确保焦点设置完成
            lifecycleHandler.postDelayed(() -> {
                try {
                    // 显示软键盘
                    boolean showResult = mInputMethodManager.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
                    Log.d(LOG_TAG, "Keyboard show result: " + showResult);
                    
                    // 如果第一次尝试失败，再试一次
                    if (!showResult) {
                        lifecycleHandler.postDelayed(() -> {
                            boolean retryResult = mInputMethodManager.showSoftInput(mTerminalView, InputMethodManager.SHOW_FORCED);
                            Log.d(LOG_TAG, "Keyboard show retry result: " + retryResult);
                        }, 200);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error showing keyboard", e);
                }
            }, 100);
            
            Log.d(LOG_TAG, "Keyboard show initiated");
        } else {
            Log.e(LOG_TAG, "Cannot show keyboard - InputMethodManager or TerminalView is null");
        }
    }

    // 更新键盘按钮文本（已移除按钮，保留方法避免调用错误）
    private void updateKeyboardButtonText() {
        // 按钮已移除
    }


    private void setupImmersiveStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用新的 WindowInsetsController API
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Android 4.4+ 使用传统方法
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
        
        // 设置状态栏为透明
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        }
    }

    private void setStatusBarPadding() {
        if (mTerminalView != null) {
            // 现在不需要了
            // 调整为一个透明的部分即可
//            terminalControlBar.setMinimumHeight(getStatusBarHeight());
        }
    }



    private void initViews() {
        try {
            mTerminalView = findViewById(R.id.terminal_view);
            terminalControlBar = findViewById(R.id.terminal_control_bar);
            if (mTerminalView == null) {
                Log.e(LOG_TAG, "TerminalView not found!");
                return;
            }
            
            mTerminalView.setTerminalViewClient(this);
            
            // 设置终端字体大小为4号(24sp)
            mTerminalView.setTextSize(24);
            
            // 动态设置顶部间距为状态栏高度 + 5dp
            try {
                setStatusBarPadding();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error setting status bar padding", e);
            }
            
            // 设置TerminalView的焦点和点击处理
            mTerminalView.setFocusable(true);
            mTerminalView.setFocusableInTouchMode(true);
            // 禁止获焦时自动弹出软键盘：AndroidManifest 已配置 stateAlwaysHidden，
            // 由 onSingleTapUp / showKeyboard 显式调起。

            mTerminalView.setOnFocusChangeListener((v, hasFocus) -> {
                Log.d(LOG_TAG, "Focus changed: " + hasFocus + ", isKeyboardVisible: " + isKeyboardVisible);
            });

            // 触摸事件交给 TerminalView 内部手势识别处理（点击逻辑由 onSingleTapUp 负责）
            // 避免在 ACTION_DOWN（包括滑动起点）时误触发软键盘弹出。
            mTerminalView.setOnTouchListener((v, event) -> false);
            
            // 添加TerminalView按键监听器，拦截Ctrl组合键
            mTerminalView.setOnKeyListener((v, keyCode, event) -> {
                // 只处理按键按下事件
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
                    Log.d(LOG_TAG, "TerminalView KeyListener: Ctrl key pressed with keyCode=" + keyCode);
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_V:
                            // Ctrl+V 粘贴
                            Log.d(LOG_TAG, "TerminalView KeyListener: Ctrl+V detected - pasting from clipboard");
                            pasteFromClipboard();
                            return true;
                            
                        case KeyEvent.KEYCODE_C:
                            // Ctrl+C 如果有选中文本则复制
                            if (mTerminalView != null && mTerminalView.isSelectingText()) {
                                Log.d(LOG_TAG, "TerminalView KeyListener: Ctrl+C detected - copying selected text");
                                copySelectedText();
                                return true;
                            }
                            // 没有选中文本时，让终端处理（发送SIGINT）
                            Log.d(LOG_TAG, "TerminalView KeyListener: Ctrl+C detected - passing to terminal");
                            break;
                            
                        case KeyEvent.KEYCODE_X:
                            // Ctrl+X 剪切
                            if (mTerminalView != null && mTerminalView.isSelectingText()) {
                                Log.d(LOG_TAG, "TerminalView KeyListener: Ctrl+X detected - cutting selected text");
                                copySelectedText();
                                return true;
                            }
                            break;
                    }
                }
                return false;
            });
            
            // 不自动请求焦点，避免自动弹出软键�
            
            Log.d(LOG_TAG, "initViews completed successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in initViews", e);
            Toast.makeText(this, "Error initializing views: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // 控制栏按�
        try {
            setupControlButtons();
            Log.d(LOG_TAG, "Button initialization completed successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error initializing buttons", e);
            Toast.makeText(this, "Error initializing buttons: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private androidx.appcompat.app.AlertDialog shellPreparationDialog;

    private void setupTerminal() {
        if (sessionOwner.getSession() != null) {
            mTerminalSession = sessionOwner.getSession();
            mTerminalView.attachSession(mTerminalSession);
            isSessionFinished = sessionOwner.isSessionFinished();
            if (!sessionOwner.isLaunchDispatched()) dispatchLaunchCommands();
            return;
        }
        shellPreparationDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("准备终端").setMessage("正在准备 shell 环境...").setCancelable(false).create();
        shellPreparationDialog.show();
        sessionOwner.shellPreparation().observe(this, state -> {
            if (destroyed || isFinishing()) return;
            if (state.ready) {
                shellPreparationDialog.dismiss();
                if (state.warning != null) Toast.makeText(this, state.warning, Toast.LENGTH_LONG).show();
                createNewSession();
            } else shellPreparationDialog.setMessage(state.detail);
        });
        sessionOwner.prepareShell(getApplicationContext());
    }

    private void createNewSession() {
        if (destroyed || isFinishing()) return;
        if (sessionOwner.getSession() != null) {
            mTerminalSession = sessionOwner.getSession();
            mTerminalView.attachSession(mTerminalSession);
            return;
        }
        try {
            Log.d(LOG_TAG, "Starting to create terminal session...");

            // 检查是否有 zsh 可用
            String shellPath;
            boolean useZsh = false;
            
            if (ZshInstaller.isInstalled(this)) {
                // 使用 zsh
                shellPath = ZshInstaller.getZshPath(this);
                useZsh = true;
                Log.d(LOG_TAG, "Using zsh: " + shellPath);
            } else {
                // 回退到系统 shell
                shellPath = "/system/bin/sh";
                File shellFile = new File(shellPath);
                if (!shellFile.exists()) {
                    shellPath = "/bin/sh";
                    shellFile = new File(shellPath);
                    if (!shellFile.exists()) {
                        shellPath = "sh";
                    }
                }
                Log.d(LOG_TAG, "Using system shell: " + shellPath);
            }

            // 确保路径不为null
            if (shellPath == null || shellPath.isEmpty()) {
                shellPath = "sh";
            }
            
            Log.d(LOG_TAG, "Using shell path: " + shellPath);

            // 创建终端会话 - 提供非null的工作目录和环境变量
            // 优先使用 Intent 传入的工作目录
            String workingDirectory = getIntent().getStringExtra("WORKING_DIR");
            if (workingDirectory == null || workingDirectory.isEmpty() || !new File(workingDirectory).exists()) {
                workingDirectory = getFilesDir().getAbsolutePath();
            }
            
            Log.d(LOG_TAG, "Working directory: " + workingDirectory);
            
            // 获取系统环境变量并构建终端环境变量
            java.util.Map<String, String> systemEnvVars = System.getenv();
            java.util.List<String> envVarsList = new java.util.ArrayList<>();
            
            // 添加系统环境变量（排除HOME 和TMPDIR）
            for (java.util.Map.Entry<String, String> entry : systemEnvVars.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                
            // 跳过我们要自定义的环境变量
            if (!"HOME".equals(key) && !"TMPDIR".equals(key) && !"TEMP".equals(key) && !"PATH".equals(key)) {
                    envVarsList.add(key + "=" + value);
                }
            }
            
            // 添加/覆盖自定义环境变量
            String filesDir = getFilesDir().getAbsolutePath();
            String tmpDir = filesDir + "/tmp";
            String clangRoot = ClangInstaller.getClangDir(this);
            String clangBinPath = clangRoot != null ? clangRoot + "/bin" : "";
            String clangLib64Path = clangRoot != null ? clangRoot + "/lib" : "";
            
            // Build Tools 路径
            String buildToolsBinPath = BuildToolsInstaller.isInstalled(this) ? 
                    BuildToolsInstaller.getBuildToolsDir(this) + "/bin" : "";
            
            // Zsh 路径
            String zshBinPath = ZshInstaller.isInstalled(this) ? 
                    ZshInstaller.getZshDir(this) + "/bin" : "";
            
            // PATH 添加 Zsh、Clang、Build Tools 和系统路径
            StringBuilder pathBuilder = new StringBuilder(filesDir);
            if (!zshBinPath.isEmpty()) {
                pathBuilder.append(":").append(zshBinPath);
            }
            if (!clangBinPath.isEmpty()) {
                pathBuilder.append(":").append(clangBinPath);
            }
            if (!buildToolsBinPath.isEmpty()) {
                pathBuilder.append(":").append(buildToolsBinPath);
            }
            pathBuilder.append(":/sbin:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin");
            String basePath = pathBuilder.toString();
            
            // 获取插件的 PATH
            String pluginPaths = PluginManager.getEnabledPluginPaths(this);
            String customPath = pluginPaths.isEmpty() ? basePath : pluginPaths + ":" + basePath;
            
            String systemPath = systemEnvVars.get("PATH");
            if (systemPath != null && !systemPath.isEmpty()) {
                customPath = customPath + ":" + systemPath;
            }
            
            // 获取 Android API Level
            int apiLevel = AppPreferences.getAndroidApiLevel(this);
            
            envVarsList.add("PATH=" + customPath);
            envVarsList.add("HOME=" + filesDir);
            envVarsList.add("TMPDIR=" + tmpDir);
            envVarsList.add("TEMP=" + tmpDir);
            envVarsList.add("TERM=xterm-256color");
            
            // 根据 shell 类型设置不同的环境变量
            if (useZsh) {
                // Zsh 配置
                envVarsList.add("ZDOTDIR=" + ZshInstaller.getZdotdir(this));
                envVarsList.add("TERMINFO=" + ZshInstaller.getTerminfoDir(this));
                // zsh 的库路径和模块路径
                String zshLibPath = ZshInstaller.getZshDir(this) + "/lib";
                String zshModulePath = ZshInstaller.getZshDir(this) + "/lib/zsh/5.9";
                String zshFpath = ZshInstaller.getZshDir(this) + "/share/zsh/5.9/functions";
                envVarsList.add("MODULE_PATH=" + zshModulePath);
                envVarsList.add("FPATH=" + zshFpath);
                // 设置 PREFIX 让 zsh 知道实际的安装路径
                envVarsList.add("PREFIX=" + ZshInstaller.getZshDir(this));
                if (clangLib64Path != null && !clangLib64Path.isEmpty()) {
                    envVarsList.add("LD_LIBRARY_PATH=" + zshLibPath + ":" + clangLib64Path);
                } else {
                    envVarsList.add("LD_LIBRARY_PATH=" + zshLibPath);
                }
            } else {
                // 设置 ENV 变量，让 sh 启动时加载 .bashrc
                envVarsList.add("ENV=" + filesDir + "/.bashrc");
                if (clangLib64Path != null && !clangLib64Path.isEmpty()) {
                    envVarsList.add("LD_LIBRARY_PATH=" + clangLib64Path);
                }
            }
            
            // Clang 相关环境变量
            if (clangRoot != null) {
                envVarsList.add("CLANG_HOME=" + clangRoot);
                
                // PREFIX 用于 CMake 查找 api-level.h
                envVarsList.add("PREFIX=" + clangRoot + "/sysroot/usr");
                
                // 根据设备架构设置 target
                String arch = ClangInstaller.getDeviceArch();
                String targetTriple;
                String libSubdir;
                if ("x86_64".equals(arch)) {
                    targetTriple = "x86_64-linux-android";
                    libSubdir = "x86_64-linux-android";
                } else {
                    targetTriple = "aarch64-linux-android";
                    libSubdir = "aarch64-linux-android";
                }
                
                // 设置默认编译参数，让 clang++ file.cpp 直接可用
                String target = "--target=" + targetTriple + apiLevel;
                String sysroot = "--sysroot=" + clangRoot + "/sysroot";
                String clangResourceMajor = ClangInstaller.getClangResourceMajor(this);
                String resourceDir = "-resource-dir=" + clangRoot + "/lib/clang/" + clangResourceMajor;
                String libPaths = "-L" + clangRoot + "/lib -L" + clangRoot + "/" + libSubdir + "/lib";
                
                // CFLAGS 和 CXXFLAGS - 只包含编译阶段的参数
                String commonFlags = target + " " + sysroot + " " + resourceDir + " -O2";
                String cxxFlags = commonFlags + " -stdlib=libc++";
                
                // LDFLAGS - 链接阶段的参数
                String ldFlags = target + " " + sysroot + " " + libPaths + " -lc++abi -s -pie";
                
                envVarsList.add("CFLAGS=" + commonFlags);
                envVarsList.add("CXXFLAGS=" + cxxFlags);
                envVarsList.add("LDFLAGS=" + ldFlags);
            }
            
            // 转换为数组
            String[] envVars = envVarsList.toArray(new String[0]);
            
            Log.d(LOG_TAG, "Total environment variables: " + envVars.length);
            
            Log.d(LOG_TAG, "Creating TerminalSession...");
            // 使用 -l (login shell) 而不是-i，确保加载完整环境
            String[] shellArgs = new String[]{"-l"};
            mTerminalSession = new TerminalSession(shellPath, workingDirectory, shellArgs, envVars, 2000, sessionOwner);
            
            Log.d(LOG_TAG, "Initializing emulator...");
            // 先初始化终端大小，再附加会话
            mTerminalSession.initializeEmulator(80, 24);
            sessionOwner.own(mTerminalSession, false, AppPreferences.getCustomSu(this));
            
            Log.d(LOG_TAG, "Attaching session to view...");
            mTerminalView.attachSession(mTerminalSession);

            // 记录会话状态
            try {
                Log.i(LOG_TAG, "Terminal session created: pid=" + mTerminalSession.getPid() + 
                    ", running=" + mTerminalSession.isRunning() + 
                    ", cwd=" + mTerminalSession.getCwd());
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error querying session state", e);
            }

            if (!sessionOwner.isLaunchDispatched()) dispatchLaunchCommands();

        } catch (Exception e) {
            Log.e(LOG_TAG, "Terminal session created error", e);
            Toast.makeText(this, "Failed to create terminal session: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void dispatchLaunchCommands() {
            // 发送初始化命令 - 需要延迟等待 shell 启动完成
            final String pendingExecuteCommand = executeCommand;
            final String pendingRunCommand = runCommand;
            final String pendingRunFilePath = runFilePath;
            final String pendingCompileFilePath = compileFilePath;
            
            // 清空，避免重复执行
            executeCommand = null;
            runCommand = null;
            runFilePath = null;
            compileFilePath = null;
            
            // 延迟发送命令，等待 shell 启动
            lifecycleHandler.postDelayed(() -> {
                if (destroyed || isFinishing() || sessionOwner.isLaunchDispatched()) return;
                if (mTerminalSession == null || !mTerminalSession.isRunning()) {
                    Log.w(LOG_TAG, "Terminal session not ready, skipping command");
                    return;
                }
                
                try {
                    sessionOwner.markLaunchDispatched();
                    // 如果有直接执行的命令（Make/CMake等）
                    if (pendingExecuteCommand != null && !pendingExecuteCommand.isEmpty()) {
                        String execCmd = "clear; " + pendingExecuteCommand + "\n";
                        Log.i(LOG_TAG, "Executing requested terminal command");
                        mTerminalSession.write(execCmd);
                    }
                    // 如果有直接运行的命令（自定义编译命令的运行命令）
                    else if (pendingRunCommand != null && !pendingRunCommand.isEmpty()) {
                        // 检查是否启用root运行
                        boolean enableRootRun = AppPreferences.getEnableRootRun(TerminalActivity.this);
                        String customSu = AppPreferences.getCustomSu(TerminalActivity.this);
                        String runCmd;
                        if (enableRootRun) {
                            sessionOwner.allowRootCleanup(customSu);
                            runCmd = "clear; " + customSu + " " + CompilerArguments.shellQuote(pendingRunCommand) + "; exit\n";
                        } else {
                            runCmd = "clear; " + pendingRunCommand + "; exit\n";
                        }
                        Log.i(LOG_TAG, "Running requested terminal command");
                        mTerminalSession.write(runCmd);
                    }
                    // 如果有直接运行的文件（已编译好的）
                    else if (pendingRunFilePath != null && !pendingRunFilePath.isEmpty()) {
                        sessionOwner.markLaunchPending();
                        // 执行运行前 hook
                        java.util.Map<String, String> hookVars = new java.util.HashMap<>();
                        hookVars.put("run_file", pendingRunFilePath);
                        hookVars.put("run_args", launchRunArgs);
                        
                        PluginManager.getHookManager(TerminalActivity.this).executeEvent(
                            TerminalActivity.this, PluginHookManager.EVENT_RUN_BEFORE, hookVars, () -> {
                                if (!destroyed && !isFinishing()) {
                                    sessionOwner.markLaunchDispatched();
                                    executeRunFile(pendingRunFilePath);
                                }
                            });
                    }
                    // 如果有编译任务
                    else if (pendingCompileFilePath != null && !pendingCompileFilePath.isEmpty()) {
                        executeCompileAndRun(pendingCompileFilePath);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error executing pending command", e);
                }
            }, 500); // 延迟 500ms 等待 shell 启动

    }

    /**
     * 执行运行文件（hook 执行后调用）
     */
    private void executeRunFile(String filePath) {
        if (mTerminalSession == null) return;
        try {
            boolean root = AppPreferences.getEnableRootRun(this);
            String su = AppPreferences.getCustomSu(this);
            String command = TerminalCommands.runFile(filePath, launchRunArgs, root, su);
            if (root) sessionOwner.allowRootCleanup(su);
            mTerminalSession.write("clear; " + command + "; exit\n");
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void executeCompileAndRun(String filePath) {
        if (filePath != null && mTerminalSession != null) sessionOwner.compileAndRun(getApplicationContext(), filePath, launchRunArgs);
    }

    /**
     * 获取设备架构
     */
    private String getDeviceArchitecture() {
        String arch = Build.SUPPORTED_ABIS[0];
        Log.d(LOG_TAG, "Supported ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS));

        // 映射到常见的架构名称
        switch (arch) {
            case "arm64-v8a":
                return "arm64-v8a";
            case "armeabi-v7a":
                return "armeabi-v7a";
            case "x86_64":
                return "x86_64";
            case "x86":
                return "x86";
            default:
                Log.w(LOG_TAG, "Unknown architecture: " + arch + ", using as-is");
                return arch;
        }
    }


    private void copySelectedText() {
        if (mTerminalView != null && mTerminalView.mEmulator != null) {
            // 获取整个屏幕的文�
            String selectedText = mTerminalView.mEmulator.getSelectedText(0, 0, 
                mTerminalView.mEmulator.mColumns, mTerminalView.mEmulator.mRows);
            if (selectedText != null && !selectedText.trim().isEmpty()) {
                ClipData clip = ClipData.newPlainText("Terminal Text", selectedText);
                mClipboardManager.setPrimaryClip(clip);
                Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No text to copy", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Terminal not ready", Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteFromClipboard() {
        if (mClipboardManager.hasPrimaryClip()) {
            ClipData clip = mClipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String text = clip.getItemAt(0).getText().toString();
                if (mTerminalSession != null) {
                    mTerminalSession.write(text);
                    // 立即刷新显示
                    if (mTerminalView != null) {
                        mTerminalView.post(() -> mTerminalView.invalidate());
                    }
                }
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearTerminal() {
        if (mTerminalSession != null) {
            // 发送clear 命令并按回车
            mTerminalSession.write("clear\n");
            // 立即刷新显示
            if (mTerminalView != null) {
                mTerminalView.post(() -> mTerminalView.invalidate());
            }
        }
    }



    public static String readFileToString(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }
    private void vibrateFeedback() {
        if (mVibrator != null && mVibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mVibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                mVibrator.vibrate(50);
            }
        }
    }

    private void applyFontSettings() {
        try {
            // 获取字体设置
            String fontName = AppPreferences.getTerminalFont(this);
            int fontSize = AppPreferences.getFontSize(this);
            
            // 加载字体
            Typeface typeface = Typeface.createFromAsset(getAssets(), "fonts/" + fontName);
            
            // 应用字体和字体大小到TerminalView
            if (mTerminalView != null) {
                mTerminalView.setTypeface(typeface);
                mTerminalView.setTextSize(fontSize);
                Log.d(LOG_TAG, "Applied font: " + fontName + ", size: " + fontSize);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error applying font settings: " + e.getMessage());
        }
    }

    private void setupControlButtons() {
        // 第一行控制按钮
        findViewById(R.id.btn_esc).setOnClickListener(v -> {
            vibrateFeedback();
            if (mTerminalSession != null) {
                mTerminalSession.write("\u001b"); // Escape key
            }
        });

        findViewById(R.id.btn_tab).setOnClickListener(v -> {
            vibrateFeedback();
            if (mTerminalSession != null) {
                mTerminalSession.write("\t"); // Tab key
            }
        });

        findViewById(R.id.btn_home).setOnClickListener(v -> {
            vibrateFeedback();
            if (mTerminalSession != null) {
                mTerminalSession.write("\u001b[H"); // Home key
            }
        });

        findViewById(R.id.btn_up).setOnClickListener(v -> {
            vibrateFeedback();
            if (mTerminalSession != null) {
                mTerminalSession.write("\u001b[A"); // Up arrow
            }
        });

        findViewById(R.id.btn_end).setOnClickListener(v -> {
            vibrateFeedback();
            if (mTerminalSession != null) {
                mTerminalSession.write("\u001b[F"); // End key
            }
        });

        // 第二行控制按钮
        findViewById(R.id.btn_ctrl).setOnClickListener(v -> {
            vibrateFeedback();
            // Ctrl键需要特殊处理，这里暂时发送Ctrl+C
            if (mTerminalSession != null) {
                mTerminalSession.write("\u0003"); // Ctrl+C
            }
        });

        findViewById(R.id.btn_alt).setOnClickListener(v -> {
            vibrateFeedback();
            // Alt键需要特殊处理，这里暂时发送Alt+Tab
            if (mTerminalSession != null) {
                mTerminalSession.write("\u001b\t"); // Alt+Tab
            }
        });

        setupRepeatingArrowButton(R.id.btn_left, "\u001b[D");

        findViewById(R.id.btn_bottom).setOnClickListener(v -> {
            vibrateFeedback();
            if (mTerminalSession != null) {
                mTerminalSession.write("\u001b[B"); // Down arrow
            }
        });

        setupRepeatingArrowButton(R.id.btn_right, "\u001b[C");

        // 更多按钮 - 弹出菜单
        findViewById(R.id.btn_menu).setOnClickListener(v -> {
            vibrateFeedback();
            showTerminalMenu(v);
        });

        // 切换按钮暂时不添加点击事件，按您的要求保�
    }

    /**
     * 设置支持长按连发的方向键按钮。
     * ACTION_DOWN 立即发送一次，随后按固定间隔持续发送，抬手后停止。
     */
    private void setupRepeatingArrowButton(int buttonId, String keySequence) {
        View button = findViewById(buttonId);
        if (button == null) return;

        final Handler repeatHandler = lifecycleHandler;
        final Runnable repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!destroyed && mTerminalSession != null) {
                    mTerminalSession.write(keySequence);
                    repeatHandler.postDelayed(this, ARROW_REPEAT_INTERVAL_MS);
                }
            }
        };

        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    vibrateFeedback();
                    if (mTerminalSession != null) {
                        mTerminalSession.write(keySequence);
                    }
                    repeatHandler.removeCallbacks(repeatRunnable);
                    repeatHandler.postDelayed(repeatRunnable, ARROW_REPEAT_INITIAL_DELAY_MS);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    repeatHandler.removeCallbacks(repeatRunnable);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }
    
    /**
     * 显示终端菜单
     */
    private void showTerminalMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "结束");
        popup.getMenu().add(0, 2, 1, "重置");
        popup.getMenu().add(0, 3, 2, "设置");
        popup.getMenu().add(0, 4, 3, "粘贴");
        
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: // 结束
                    finish();
                    return true;
                case 2: // 重置
                    resetTerminal();
                    return true;
                case 3: // 设置
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                case 4: // 粘贴
                    pasteFromClipboard();
                    return true;
            }
            return false;
        });
        
        popup.show();
    }
    
    /**
     * 重置终端
     */
    private void resetTerminal() {
        isResetting = true;
        sessionOwner.closeSession();
        mTerminalSession = null;
        sessionOwner.markLaunchDispatched();
        isSessionFinished = false;
        // 重新创建会话
        createNewSession();
        Toast.makeText(this, "终端已重置", Toast.LENGTH_SHORT).show();
        isResetting = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限被拒绝", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public float onScale(float scale) {
        return 1.0f; // 禁用缩放
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        Log.d(LOG_TAG, "onSingleTapUp - isKeyboardVisible: " + isKeyboardVisible);
        if (!isKeyboardVisible) {
            showKeyboard();
            Log.d(LOG_TAG, "onSingleTapUp - showing keyboard");
        } else if (mTerminalView != null && !mTerminalView.hasFocus()) {
            mTerminalView.requestFocus();
            if (mInputMethodManager != null) {
                mInputMethodManager.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
            }
            Log.d(LOG_TAG, "onSingleTapUp - restoring focus for IME");
        }
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        Log.d(LOG_TAG, "onBackPressed called - isKeyboardVisible: " + isKeyboardVisible);
        if (isKeyboardVisible) {
            // 如果软键盘显示，先关闭软键盘
            hideKeyboardCompletely();
            Log.d(LOG_TAG, "onBackPressed - hiding keyboard");
        } else {
            // 软键盘已关闭，正常退出
            Log.d(LOG_TAG, "onBackPressed - finishing activity");
            super.onBackPressed();
        }
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
        // 复制模式改变处理
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        // 如果会话已结束，按回车键退出
        if (isSessionFinished && keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        
        // 处理返回键 - 不拦截，让系统处理
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d(LOG_TAG, "TerminalViewClient.onKeyDown: BACK key detected, not intercepting");
            return false;
        }
        return false;
    }

    /**
     * 拦截所有按键事件
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // 兜底：方向键/回车/删除在焦点异常时可能到达 Activity 但未进入 TerminalView
        if (isSpecialTerminalKey(keyCode)) {
            boolean handled = dispatchSpecialKeyToTerminal("dispatchKeyEvent", event);
            if (handled) return true;
        }
        
        // 如果会话已结束，按回车键退出
        if (isSessionFinished && keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        
        // 返回键特殊处理 - 直接处理退出
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Log.d(LOG_TAG, "dispatchKeyEvent: BACK key UP - handling exit");
                if (isKeyboardVisible) {
                    hideKeyboardCompletely();
                } else {
                    finish();
                }
            }
            return true; // 消费掉返回键事件
        }
        
        // 只处理按键按下事件的 Ctrl 组合键
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            
            switch (keyCode) {
                case KeyEvent.KEYCODE_V:
                    // Ctrl+V 粘贴
                    Log.d(LOG_TAG, "dispatchKeyEvent: Ctrl+V detected - pasting from clipboard");
                    pasteFromClipboard();
                    return true;
                    
                case KeyEvent.KEYCODE_C:
                    // Ctrl+C 如果有选中文本则复制，否则让终端处理
                    if (mTerminalView != null && mTerminalView.isSelectingText()) {
                        Log.d(LOG_TAG, "dispatchKeyEvent: Ctrl+C detected - copying selected text");
                        copySelectedText();
                        return true;
                    }
                    // 没有选中文本时，让终端处理（发送SIGINT）
                    break;
                    
                case KeyEvent.KEYCODE_X:
                    // Ctrl+X 剪切
                    if (mTerminalView != null && mTerminalView.isSelectingText()) {
                        Log.d(LOG_TAG, "dispatchKeyEvent: Ctrl+X detected - cutting selected text");
                        copySelectedText();
                        return true;
                    }
                    break;
            }
        }
        
        // 其他按键交给系统处理
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            logDeleteKeyEvent("onKeyDown", event);
        }

        // 处理Ctrl组合键（作为备用，但dispatchKeyEvent应该已经处理了）
        if (event.isCtrlPressed()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_V:
                    // Ctrl+V 粘贴
                    Log.d(LOG_TAG, "Ctrl+V detected - pasting from clipboard");
                    pasteFromClipboard();
                    return true;
                    
                case KeyEvent.KEYCODE_C:
                    // Ctrl+C 如果有选中文本则复制，否则发送中断信号
                    if (mTerminalView != null && mTerminalView.isSelectingText()) {
                        Log.d(LOG_TAG, "Ctrl+C detected - copying selected text");
                        copySelectedText();
                        return true;
                    }
                    // 如果没有选中文本，让终端处理（发送SIGINT）
                    break;
                    
                case KeyEvent.KEYCODE_A:
                    // Ctrl+A 全选（可选功能）
                    Log.d(LOG_TAG, "Ctrl+A detected - select all");
                    if (mTerminalSession != null) {
                        mTerminalSession.write("\u0001"); // 发送Ctrl+A到终端
                    }
                    return true;
                    
                case KeyEvent.KEYCODE_X:
                    // Ctrl+X 剪切（复制并删除选中文本）
                    if (mTerminalView != null && mTerminalView.isSelectingText()) {
                        Log.d(LOG_TAG, "Ctrl+X detected - cutting selected text");
                        copySelectedText();
                        return true;
                    }
                    break;
            }
        }
        
        // 返回键由 onBackPressed 处理，这里不拦截
        
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            logDeleteKeyEvent("onKeyUp", e);
        }
        return false;
    }

    private static boolean isSpecialTerminalKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    private int getKeyModFromEvent(KeyEvent event) {
        int keyMod = 0;
        if (event.isCtrlPressed()) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed()) keyMod |= KeyHandler.KEYMOD_ALT;
        if (event.isShiftPressed()) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        return keyMod;
    }

    /**
     * 将特殊按键直接转发到 TerminalView，避免焦点丢失导致 IME 按键失效。
     */
    private boolean dispatchSpecialKeyToTerminal(String stage, KeyEvent event) {
        int keyCode = event.getKeyCode();
        logSpecialKeyEvent(stage, event);

        if (event.getAction() != KeyEvent.ACTION_DOWN
            || mTerminalView == null
            || mTerminalSession == null) {
            return false;
        }

        if (!mTerminalView.hasFocus()) {
            mTerminalView.requestFocus();
        }

        boolean handled = mTerminalView.handleKeyCode(keyCode, getKeyModFromEvent(event));
        Log.d(LOG_TAG, "SpecialKey direct-handle result=" + handled + ", keyCode=" + keyCode + ", stage=" + stage);
        return handled;
    }

    private void logSpecialKeyEvent(String stage, KeyEvent event) {
        if (event == null) return;
        Log.d(LOG_TAG, "SpecialKey " + stage
            + " action=" + keyEventActionName(event.getAction())
            + " keyCode=" + event.getKeyCode()
            + " repeat=" + event.getRepeatCount()
            + " deviceId=" + event.getDeviceId()
            + " source=" + event.getSource()
            + " hasFocus=" + (mTerminalView != null && mTerminalView.hasFocus()));
    }

    /**
     * 删除键相关日志（退格 KEYCODE_DEL / 前删 KEYCODE_FORWARD_DEL）。
     */
    private void logDeleteKeyEvent(String stage, KeyEvent event) {
        logSpecialKeyEvent(stage, event);
    }

    private static String keyEventActionName(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:
                return "DOWN";
            case KeyEvent.ACTION_UP:
                return "UP";
            case KeyEvent.ACTION_MULTIPLE:
                return "MULTIPLE";
            default:
                return String.valueOf(action);
        }
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        // 模拟器设置完成，不自动显示软键盘
        // 让用户手动控制软键盘的显�
        Log.d(LOG_TAG, "Emulator set - keyboard will be controlled manually");
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {

    }

    // TerminalSessionClient methods
    @Override
    public void onTextChanged(TerminalSession changedSession) {
        // 使用节流机制避免频繁刷新导致的抖动
        if (mTerminalView != null) {
            synchronized (mInvalidateLock) {
                if (!mPendingInvalidate) {
                    mPendingInvalidate = true;
                    mTerminalView.post(() -> {
                        synchronized (mInvalidateLock) {
                            mPendingInvalidate = false;
                        }
                        mTerminalView.invalidate();
                    });
                }
            }
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        // 标题改变时的处理
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (destroyed || finishedSession != mTerminalSession) return;
        // 会话结束时的处理
        runOnUiThread(() -> {
            // 如果是重置操作，不关闭Activity
            if (isResetting) {
                return;
            }
            // 延迟设置会话结束标志，避免用户输入的回车被误判为退出操作
            // 这样可以确保用户看到程序输出后，需要再次按回车才能退出
            lifecycleHandler.postDelayed(() -> {
                if (destroyed || finishedSession != mTerminalSession) return;
                isSessionFinished = true;
                Toast.makeText(this, "按回车键退出终端", Toast.LENGTH_SHORT).show();
            }, 300); // 延迟 300ms
        });
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        // 复制文本到剪贴板
        ClipData clip = ClipData.newPlainText("Terminal Text", text);
        mClipboardManager.setPrimaryClip(clip);
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        // 从剪贴板粘贴文本
        if (mClipboardManager.hasPrimaryClip()) {
            ClipData clip = mClipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String text = clip.getItemAt(0).getText().toString();
                session.write(text);
            }
        }
    }

    @Override
    public void onBell(TerminalSession session) {
        // 响铃处理
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        // 颜色改变时的处理，立即刷新显示
        if (mTerminalView != null) {
            mTerminalView.post(() -> mTerminalView.invalidate());
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        // 光标状态改变时的处理，立即刷新显示
        if (mTerminalView != null) {
            mTerminalView.post(() -> mTerminalView.invalidate());
        }
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null; // 使用默认样式
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launchRunArgs = intent.getStringExtra(RUN_ARGS);
        if (launchRunArgs == null) launchRunArgs = "";
        
        // 处理新的命令
        String newExecuteCommand = intent.getStringExtra("EXECUTE_COMMAND");
        String newWorkingDir = intent.getStringExtra("WORKING_DIR");
        
        if (newExecuteCommand != null && !newExecuteCommand.isEmpty() && mTerminalSession != null) {
            // 如果有工作目录，先切换
            StringBuilder cmd = new StringBuilder();
            if (newWorkingDir != null && !newWorkingDir.isEmpty()) {
                cmd.append("cd ").append(CompilerArguments.shellQuote(newWorkingDir)).append(" && ");
            }
            cmd.append(newExecuteCommand);
            
            String execCmd = "clear; " + cmd.toString() + "\n";
            Log.i(LOG_TAG, "Executing terminal command from new intent");
            mTerminalSession.write(execCmd);
        }
        
        // 处理编译任务
        String newCompileFile = intent.getStringExtra("COMPILE_FILE");
        if (newCompileFile != null && !newCompileFile.isEmpty() && mTerminalSession != null) {
            executeCompileAndRun(newCompileFile);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 屏幕旋转时，让终端视图重新计算大小
        if (mTerminalView != null && mTerminalSession != null) {
            mTerminalView.post(() -> {
                // 触发终端大小更新
                mTerminalView.requestLayout();
            });
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        lifecycleHandler.removeCallbacksAndMessages(null);
        if (shellPreparationDialog != null) shellPreparationDialog.dismiss();
        sessionOwner.detach(this);
        // ViewModel survives configuration changes. Its onCleared closes only this owned PTY.
        if (keyboardVisibilityListener != null) {
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) rootView.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardVisibilityListener);
        }
        unregisterBottomToolbarSettingsReceiver();
        super.onDestroy();
    }

    /**
     * 注销底部工具栏设置广播接收器
     */
    private void unregisterBottomToolbarSettingsReceiver() {
        if (mBottomToolbarSettingsReceiver != null) {
            try {
                unregisterReceiver(mBottomToolbarSettingsReceiver);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error unregistering receiver", e);
            }
            mBottomToolbarSettingsReceiver = null;
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 当窗口获得焦点时，重新设置沉浸式状态栏
            setupImmersiveStatusBar();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 在恢复时重新设置沉浸式状态栏
        applyFontSettings();

        setupImmersiveStatusBar();
    }
}
