package com.vkyra.code.plugin;

import com.vkyra.code.R;
import com.vkyra.code.terminal.ManagedProcessRunner;
import com.vkyra.code.terminal.TerminalActivity;
import com.vkyra.code.toolchain.BuildToolsInstaller;
import com.vkyra.code.toolchain.ClangInstaller;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleEventObserver;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 插件安装脚本执行器
 * 支持 .install 文件中定义的安装流程
 */
public class PluginInstallScript {

    public interface InstallCallback {
        void onProgress(String message);
        void onComplete(boolean success, String message);
    }

    private final Activity activity;
    private final File pluginDir;
    private final Handler mainHandler;
    private InstallCallback callback;
    private volatile boolean cancelled;
    private boolean strictInstall;
    private final List<ManagedProcessRunner> processes = new CopyOnWriteArrayList<>();
    private final List<Dialog> dialogs = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> pendingProgress = new AtomicReference<>();
    private final AtomicBoolean progressScheduled = new AtomicBoolean();
    private LifecycleEventObserver lifecycleObserver;

    public synchronized void cancel() {
        cancelled = true;
        for (ManagedProcessRunner process : processes) process.cancel();
        mainHandler.post(() -> {
            for (Dialog dialog : dialogs) if (dialog.isShowing()) dialog.dismiss();
            dialogs.clear();
            releaseLifecycle();
        });
    }

    /** Cancellation barrier: callers must wait off-main before restoring plugin paths. */
    public void cancelAndAwait() throws InterruptedException {
        List<ManagedProcessRunner> owned;
        synchronized (this) { cancel(); owned = new ArrayList<>(processes); }
        for (ManagedProcessRunner runner : owned) runner.awaitStopped();
    }

    /** Releases page observation after a complete hook or installation; before/start phases keep ownership. */
    public void releaseLifecycle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::releaseLifecycle);
            return;
        }
        callback = null;
        if (activity instanceof LifecycleOwner && lifecycleObserver != null) {
            ((LifecycleOwner) activity).getLifecycle().removeObserver(lifecycleObserver);
            lifecycleObserver = null;
        }
    }

    private void trackDialog(Dialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
    }

    private boolean isActive() {
        return !cancelled && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void postActive(Runnable action) {
        mainHandler.post(() -> { if (isActive()) action.run(); });
    }

    private void fail(String message) {
        if (!isActive()) return;
        InstallCallback listener = callback;
        cancel();
        if (listener != null) mainHandler.post(() -> listener.onComplete(false, message));
    }

    private void publishProgress(String message) {
        pendingProgress.set(message);
        if (progressScheduled.compareAndSet(false, true)) mainHandler.postDelayed(() -> {
            progressScheduled.set(false);
            String latest = pendingProgress.getAndSet(null);
            if (isActive() && callback != null && latest != null) callback.onProgress(latest);
        }, 100);
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    private CommandResult runCommand(ProcessBuilder builder, JSONObject node, boolean streamProgress) throws Exception {
        ManagedProcessRunner runner = new ManagedProcessRunner(null);
        if (streamProgress) runner.setOutputListener(this::publishProgress);
        synchronized (this) {
            if (!isActive()) throw new InterruptedException("插件操作已取消");
            processes.add(runner);
        }
        try {
            long timeoutMs = Math.max(1000, Math.min(node.optLong("timeout_ms", 30 * 60 * 1000L), 24 * 60 * 60 * 1000L));
            ManagedProcessRunner.Result result = runner.run(builder, timeoutMs);
            if (result.cancelled) throw new InterruptedException("插件命令超时或已取消");
            return new CommandResult(result.exitCode, result.output.trim());
        } finally { runner.awaitStopped(); processes.remove(runner); }
    }

    // 变量映射表（内置 + 用户创建）
    private final Map<String, String> variableMap = new HashMap<>();

    public PluginInstallScript(Activity activity, File pluginDir) {
        this.activity = activity;
        this.pluginDir = pluginDir;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initBuiltinVariables();
        if (activity instanceof LifecycleOwner) {
            lifecycleObserver = (source, event) -> { if (event == Lifecycle.Event.ON_DESTROY) cancel(); };
            ((LifecycleOwner) activity).getLifecycle().addObserver(lifecycleObserver);
        }
    }
    
    /**
     * 初始化内置变量
     */
    private void initBuiltinVariables() {
        variableMap.put("plugin_path", pluginDir.getAbsolutePath());
        variableMap.put("files_dir", activity.getFilesDir().getAbsolutePath());
        variableMap.put("plugin_base", new File(activity.getFilesDir(), "plugin").getAbsolutePath());
        variableMap.put("cache_dir", activity.getCacheDir().getAbsolutePath());
        variableMap.put("plugin_name", pluginDir.getName());
        if (activity.getExternalFilesDir(null) != null) {
            variableMap.put("external_files", activity.getExternalFilesDir(null).getAbsolutePath());
        }
    }
    
    public void addVariable(String key, String value) {
        variableMap.put(key, value);
    }
    
    public String getVariable(String key) {
        return variableMap.get(key);
    }

    public void setCallback(InstallCallback callback) {
        this.callback = callback;
    }

    public boolean hasInstallScript() {
        return new File(pluginDir, ".install").exists();
    }

    public JSONObject readInstallScript() {
        File installFile = new File(pluginDir, ".install");
        if (!installFile.exists() || installFile.length() > 1024 * 1024) return null;
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(installFile));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return new JSONObject(content.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void executeInstallBefore(Runnable onComplete) {
        executePhase("install_before", onComplete);
    }

    public void executeInstallStart(Runnable onComplete) {
        executePhase("install_start", onComplete);
    }

    public void executeInstallAfter(Runnable onComplete) {
        executePhase("install_after", onComplete);
    }
    
    private void executePhase(String phase, Runnable onComplete) {
        if (!isActive()) return;
        strictInstall = true;
        JSONObject script = readInstallScript();
        if (script == null) {
            fail("无效的 .install 安装脚本");
            return;
        }
        Runnable completion = () -> { if ("install_after".equals(phase)) releaseLifecycle(); if (onComplete != null) onComplete.run(); };
        JSONArray nodes = script.optJSONArray(phase);
        if (nodes == null || nodes.length() == 0) {
            completion.run();
            return;
        }
        executeNodesInternal(nodes, 0, completion);
    }


    /**
     * 公开方法：执行节点数组（供 PluginHookManager 调用）
     */
    public void executeNodes(JSONArray nodes, Runnable onComplete) {
        if (!isActive()) return;
        if (nodes == null || nodes.length() == 0) {
            releaseLifecycle();
            if (onComplete != null) onComplete.run();
            return;
        }
        executeNodesInternal(nodes, 0, () -> { releaseLifecycle(); if (onComplete != null) onComplete.run(); });
    }

    /**
     * 递归执行节点
     */
    private void executeNodesInternal(JSONArray nodes, int index, Runnable onComplete) {
        if (!isActive()) return;
        if (index >= nodes.length()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        try {
            JSONObject node = nodes.getJSONObject(index);
            String nodeType = node.optString("node", "");

            switch (nodeType) {
                case "create_value":
                    executeCreateValue(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "alert":
                    executeAlert(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "edit":
                    executeEdit(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "select":
                    executeSelect(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "if":
                    executeIf(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "command":
                    executeCommand(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "command_result":
                    executeCommandResult(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "add_path":
                    executeAddPath(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "run_in_terminal":
                    executeRunInTerminal(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                case "grep":
                    executeGrep(node, () -> continueNodes(nodes, index + 1, onComplete));
                    break;
                default:
                    if (strictInstall) { fail("未知的安装脚本节点: " + nodeType); return; }
                    continueNodes(nodes, index + 1, onComplete);
                    break;
            }
        } catch (Exception e) {
            if (strictInstall) { fail("安装脚本执行失败: " + e.getMessage()); return; }
            continueNodes(nodes, index + 1, onComplete);
        }
    }

    private void continueNodes(JSONArray nodes, int index, Runnable onComplete) {
        postActive(() -> executeNodesInternal(nodes, index, onComplete));
    }

    /**
     * 安全执行回调（处理 null）
     */
    private void safeRun(Runnable runnable) {
        if (isActive() && runnable != null) runnable.run();
    }

    /**
     * create_value - 创建变量
     * {"node": "create_value", "name": "my_var", "type": "string", "value": "default"}
     */
    private void executeCreateValue(JSONObject node, Runnable onComplete) {
        String name = node.optString("name", "");
        String type = node.optString("type", "string");
        String value = node.optString("value", "");
        
        if (!name.isEmpty()) {
            // 根据类型设置默认值
            if (value.isEmpty()) {
                switch (type) {
                    case "bool": value = "false"; break;
                    case "int": value = "0"; break;
                    default: value = ""; break;
                }
            }
            variableMap.put(name, replaceVariables(value));
        }
        safeRun(onComplete);
    }

    /**
     * alert - 显示提示对话框
     * {"node": "alert", "title": "提示", "message": "内容"}
     */
    private void executeAlert(JSONObject node, Runnable onComplete) {
        String title = replaceVariables(node.optString("title", "提示"));
        String message = replaceVariables(node.optString("message", ""));

        postActive(() -> {
            // 创建可选中的 TextView
            TextView messageView = new TextView(activity);
            messageView.setText(message);
            messageView.setTextColor(0xFFE0E0E0);
            messageView.setTextSize(14);
            messageView.setTextIsSelectable(true);  // 允许长按选中
            messageView.setTypeface(android.graphics.Typeface.MONOSPACE);  // 使用等宽字体
            
            // 添加内边距
            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(48, 24, 48, 24);
            container.addView(messageView);
            
            trackDialog(new MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setView(container)
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> safeRun(onComplete))
                    .setNeutralButton("复制全部", (dialog, which) -> {
                        // 复制到剪贴板
                        android.content.ClipboardManager clipboard = 
                            (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("错误信息", message);
                        clipboard.setPrimaryClip(clip);
                        
                        android.widget.Toast.makeText(activity, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
                        
                        // 继续执行
                        safeRun(onComplete);
                    })
                    .show());
        });
    }

    /**
     * edit - 单行输入框
     * {"node": "edit", "title": "输入", "hint": "请输入", "target": "var_name", "default": ""}
     */
    private void executeEdit(JSONObject node, Runnable onComplete) {
        String title = replaceVariables(node.optString("title", "输入"));
        String hint = replaceVariables(node.optString("hint", ""));
        String target = node.optString("target", "");
        String defaultValue = replaceVariables(node.optString("default", ""));

        postActive(() -> {
            EditText input = new EditText(activity);
            input.setHint(hint);
            input.setText(defaultValue);
            input.setTextColor(0xFFE0E0E0);
            input.setHintTextColor(0xFF9E9E9E);
            
            LinearLayout container = new LinearLayout(activity);
            container.setPadding(48, 16, 48, 0);
            container.addView(input);

            trackDialog(new MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setView(container)
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String value = input.getText().toString();
                        if (!target.isEmpty()) {
                            variableMap.put(target, value);
                        }
                        safeRun(onComplete);
                    })
                    .setNegativeButton("取消", (dialog, which) -> safeRun(onComplete))
                    .show());
        });
    }


    /**
     * select - 多选/单选
     * {"node": "select", "title": "选择", "items": "a,b,c", "target": "var_name", "multi": false}
     * items 可以是逗号分隔的字符串，或者使用 items_var 从变量读取
     */
    private void executeSelect(JSONObject node, Runnable onComplete) {
        String title = replaceVariables(node.optString("title", "选择"));
        String target = node.optString("target", "");
        boolean multi = node.optBoolean("multi", false);
        
        // 获取列表项：支持 items（逗号分隔字符串）或 items_var（从变量读取）
        List<String> itemList = new ArrayList<>();
        
        String itemsStr = node.optString("items", "");
        if (!itemsStr.isEmpty()) {
            // 从 items 字符串解析（逗号分隔）
            itemsStr = replaceVariables(itemsStr);
            String[] parts = itemsStr.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    itemList.add(trimmed);
                }
            }
        } else {
            // 兼容旧的 JSONArray 格式
            JSONArray itemsArray = node.optJSONArray("items");
            if (itemsArray != null) {
                for (int i = 0; i < itemsArray.length(); i++) {
                    itemList.add(replaceVariables(itemsArray.optString(i, "")));
                }
            }
        }
        
        if (itemList.isEmpty()) {
            safeRun(onComplete);
            return;
        }

        String[] items = itemList.toArray(new String[0]);

        postActive(() -> {
            if (multi) {
                // 多选
                boolean[] checked = new boolean[items.length];
                trackDialog(new MaterialAlertDialogBuilder(activity)
                        .setTitle(title)
                        .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
                            checked[which] = isChecked;
                        })
                        .setCancelable(false)
                        .setPositiveButton("确定", (dialog, which) -> {
                            StringBuilder result = new StringBuilder();
                            for (int i = 0; i < items.length; i++) {
                                if (checked[i]) {
                                    if (result.length() > 0) result.append(",");
                                    result.append(items[i]);
                                }
                            }
                            if (!target.isEmpty()) {
                                variableMap.put(target, result.toString());
                            }
                            safeRun(onComplete);
                        })
                        .setNegativeButton("取消", (dialog, which) -> safeRun(onComplete))
                        .show());
            } else {
                // 单选
                final int[] selected = {0};
                trackDialog(new MaterialAlertDialogBuilder(activity)
                        .setTitle(title)
                        .setSingleChoiceItems(items, 0, (dialog, which) -> selected[0] = which)
                        .setCancelable(false)
                        .setPositiveButton("确定", (dialog, which) -> {
                            if (!target.isEmpty()) {
                                variableMap.put(target, items[selected[0]]);
                            }
                            safeRun(onComplete);
                        })
                        .setNegativeButton("取消", (dialog, which) -> safeRun(onComplete))
                        .show());
            }
        });
    }

    /**
     * if - 条件判断
     * {"node": "if", "var": "my_var", "type": "==", "value": "test", "success": [...], "error": [...]}
     * type: "==" | "!=" | "contains" | "empty" | "not_empty"
     */
    private void executeIf(JSONObject node, Runnable onComplete) {
        String varName = node.optString("var", "");
        String type = node.optString("type", "==");
        String compareValue = replaceVariables(node.optString("value", ""));
        
        String varValue = variableMap.getOrDefault(varName, "");
        boolean result = false;
        
        switch (type) {
            case "==":
                result = varValue.equals(compareValue);
                break;
            case "!=":
                result = !varValue.equals(compareValue);
                break;
            case "contains":
                result = varValue.contains(compareValue);
                break;
            case "empty":
                result = varValue.isEmpty();
                break;
            case "not_empty":
                result = !varValue.isEmpty();
                break;
        }
        
        JSONArray branch = result ? node.optJSONArray("success") : node.optJSONArray("error");
        if (branch != null && branch.length() > 0) {
            executeNodesInternal(branch, 0, onComplete);
        } else {
            safeRun(onComplete);
        }
    }

    /**
     * 构建命令执行的环境变量（包含系统环境变量和插件PATH）
     */
    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<>();
        
        // 复制系统环境变量
        env.putAll(System.getenv());
        
        // 获取应用目录
        String filesDir = activity.getFilesDir().getAbsolutePath();
        String tmpDir = filesDir + "/tmp";
        String clangRoot = ClangInstaller.getClangDir(activity);
        String clangBinPath = clangRoot != null ? clangRoot + "/bin" : "";
        String clangLib64Path = clangRoot != null ? clangRoot + "/lib" : "";
        
        // Build Tools 路径
        String buildToolsBinPath = BuildToolsInstaller.isInstalled(activity) ? 
                BuildToolsInstaller.getBuildToolsDir(activity) + "/bin" : "";
        
        // 构建 PATH
        StringBuilder pathBuilder = new StringBuilder(filesDir);
        if (!clangBinPath.isEmpty()) {
            pathBuilder.append(":").append(clangBinPath);
        }
        if (!buildToolsBinPath.isEmpty()) {
            pathBuilder.append(":").append(buildToolsBinPath);
        }
        pathBuilder.append(":/sbin:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin");
        String basePath = pathBuilder.toString();
        
        // 获取插件的 PATH
        String pluginPaths = PluginManager.getEnabledPluginPaths(activity);
        String customPath = pluginPaths.isEmpty() ? basePath : pluginPaths + ":" + basePath;
        
        // 追加系统 PATH
        String systemPath = System.getenv("PATH");
        if (systemPath != null && !systemPath.isEmpty()) {
            customPath = customPath + ":" + systemPath;
        }
        
        // 设置环境变量
        env.put("PATH", customPath);
        env.put("HOME", filesDir);
        env.put("TMPDIR", tmpDir);
        env.put("TEMP", tmpDir);
        env.put("LD_LIBRARY_PATH", clangLib64Path);
        
        // Clang 相关环境变量
        if (clangRoot != null) {
            env.put("CLANG_HOME", clangRoot);
            env.put("PREFIX", clangRoot + "/sysroot/usr");
        }
        
        return env;
    }

    /**
     * 创建 M3 风格的 loading 对话框
     */
    private Dialog createLoadingDialog(String message) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        
        // 创建圆角卡片布局
        CardView card = new CardView(activity);
        card.setCardBackgroundColor(0xFF2D2D2D);
        card.setRadius(28f);
        card.setCardElevation(16f);
        card.setUseCompatPadding(true);
        
        // 内部布局
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(48, 36, 56, 36);
        
        // 进度指示器
        CircularProgressIndicator progress = new CircularProgressIndicator(activity);
        progress.setIndeterminate(true);
        progress.setIndicatorSize(40);
        progress.setTrackThickness(4);
        progress.setIndicatorColor(0xFF4FC3F7);
        progress.setTrackColor(0x334FC3F7);
        
        // 文字
        TextView text = new TextView(activity);
        text.setText(message);
        text.setTextColor(0xFFE0E0E0);
        text.setTextSize(15);
        text.setTypeface(null, Typeface.NORMAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.leftMargin = 32;
        text.setLayoutParams(textParams);
        
        layout.addView(progress);
        layout.addView(text);
        card.addView(layout);
        
        FrameLayout container = new FrameLayout(activity);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        container.addView(card, cardParams);
        
        dialog.setContentView(container);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setDimAmount(0.5f);
        }
        
        return dialog;
    }

    /**
     * command - 执行 shell 命令
     * {"node": "command", "sh": "echo hello"}
     */
    private void executeCommand(JSONObject node, Runnable onComplete) {
        String sh = replaceVariables(node.optString("sh", ""));
        if (sh.isEmpty()) {
            safeRun(onComplete);
            return;
        }

        if (callback != null) {
            postActive(() -> callback.onProgress("执行: " + sh));
        }

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", sh);
                pb.directory(pluginDir);
                pb.redirectErrorStream(true);
                
                // 设置环境变量
                pb.environment().putAll(buildEnvironment());
                
                int exitCode = runCommand(pb, node, true).exitCode;
                postActive(() -> {
                    if (strictInstall && exitCode != 0) fail("安装命令失败，退出码 " + exitCode);
                    else safeRun(onComplete);
                });
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) {
                    postActive(() -> callback.onProgress("执行出错: " + e.getMessage()));
                }
                postActive(() -> {
                    if (strictInstall) fail("安装命令执行失败: " + e.getMessage());
                    else safeRun(onComplete);
                });
            }
        }, "Vkyra-plugin-command").start();
    }

    /**
     * command_result - 执行命令并根据结果执行不同分支
     * {"node": "command_result", "sh": "ndk-build", "dir": "${current_dir}", 
     *  "success": [...], "error": [...], "loading": "执行中..."}
     * 执行成功时设置 ${cmd_output}，失败时设置 ${cmd_error}
     */
    private void executeCommandResult(JSONObject node, Runnable onComplete) {
        String sh = replaceVariables(node.optString("sh", ""));
        String dir = replaceVariables(node.optString("dir", ""));
        String loadingMsg = replaceVariables(node.optString("loading", "执行中..."));
        
        if (sh.isEmpty()) {
            safeRun(onComplete);
            return;
        }

        // 显示 loading 对话框
        final Dialog[] loadingDialog = new Dialog[1];
        postActive(() -> {
            loadingDialog[0] = createLoadingDialog(loadingMsg);
            trackDialog(loadingDialog[0]);
            loadingDialog[0].show();
        });

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", sh);
                
                // 设置工作目录
                if (!dir.isEmpty()) {
                    File workDir = new File(dir);
                    if (workDir.exists() && workDir.isDirectory()) {
                        pb.directory(workDir);
                    }
                } else {
                    pb.directory(pluginDir);
                }
                
                pb.redirectErrorStream(true);
                
                // 设置环境变量
                pb.environment().putAll(buildEnvironment());
                
                CommandResult result = runCommand(pb, node, false);
                int exitCode = result.exitCode;
                final String outputStr = result.output;

                postActive(() -> {
                    // 关闭 loading 对话框
                    if (loadingDialog[0] != null && loadingDialog[0].isShowing()) {
                        loadingDialog[0].dismiss();
                    }
                    
                    if (exitCode == 0) {
                        // 成功
                        variableMap.put("cmd_output", outputStr);
                        variableMap.put("cmd_exit_code", "0");
                        JSONArray successBranch = node.optJSONArray("success");
                        if (successBranch != null && successBranch.length() > 0) {
                            executeNodesInternal(successBranch, 0, onComplete);
                        } else {
                            safeRun(onComplete);
                        }
                    } else {
                        // 失败
                        variableMap.put("cmd_error", outputStr);
                        variableMap.put("cmd_exit_code", String.valueOf(exitCode));
                        JSONArray errorBranch = node.optJSONArray("error");
                        if (errorBranch != null && errorBranch.length() > 0) {
                            executeNodesInternal(errorBranch, 0, onComplete);
                        } else {
                            if (strictInstall) fail("安装命令失败，退出码 " + exitCode);
                            else safeRun(onComplete);
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                postActive(() -> {
                    // 关闭 loading 对话框
                    if (loadingDialog[0] != null && loadingDialog[0].isShowing()) {
                        loadingDialog[0].dismiss();
                    }
                    
                    variableMap.put("cmd_error", e.getMessage());
                    variableMap.put("cmd_exit_code", "-1");
                    JSONArray errorBranch = node.optJSONArray("error");
                    if (errorBranch != null && errorBranch.length() > 0) {
                        executeNodesInternal(errorBranch, 0, onComplete);
                    } else {
                        if (strictInstall) fail("安装命令执行失败: " + e.getMessage());
                        else safeRun(onComplete);
                    }
                });
            }
        }, "Vkyra-plugin-command-result").start();
    }

    /**
     * add_path - 添加环境变量路径
     * {"node": "add_path", "export": "${plugin_path}/bin"}
     */
    private void executeAddPath(JSONObject node, Runnable onComplete) {
        String exportPath = replaceVariables(node.optString("export", ""));
        if (!exportPath.isEmpty()) {
            try {
                File pathFile = new File(pluginDir, ".path");
                java.io.FileWriter writer = new java.io.FileWriter(pathFile);
                writer.write(exportPath);
                writer.close();
                if (callback != null) {
                    postActive(() -> callback.onProgress("已添加路径: " + exportPath));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        safeRun(onComplete);
    }

    /**
     * grep - 正则表达式匹配
     * {"node": "grep", "input": "${cmd_output}", "pattern": "libs/.+\\.so", "target": "matched_files", "group": 0}
     * 匹配结果用逗号分隔保存到 target 变量
     */
    private void executeGrep(JSONObject node, Runnable onComplete) {
        String input = replaceVariables(node.optString("input", ""));
        String pattern = node.optString("pattern", "");
        String target = node.optString("target", "");
        int group = node.optInt("group", 0);
        
        if (pattern.isEmpty() || target.isEmpty()) {
            safeRun(onComplete);
            return;
        }
        
        try {
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(input);
            
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                String match = group > 0 && group <= matcher.groupCount() 
                    ? matcher.group(group) 
                    : matcher.group();
                if (match != null && !match.isEmpty()) {
                    matches.add(match);
                }
            }
            
            // 用逗号分隔保存结果
            variableMap.put(target, String.join(",", matches));
        } catch (Exception e) {
            e.printStackTrace();
            variableMap.put(target, "");
        }
        
        safeRun(onComplete);
    }

    /**
     * run_in_terminal - 在终端中运行可执行文件
     * {"node": "run_in_terminal", "file": "${selected_file}"}
     * 或 {"node": "run_in_terminal", "file": "/path/to/executable", "args": "-v"}
     */
    private void executeRunInTerminal(JSONObject node, Runnable onComplete) {
        String filePath = replaceVariables(node.optString("file", ""));
        String args = replaceVariables(node.optString("args", ""));
        
        if (filePath.isEmpty()) {
            safeRun(onComplete);
            return;
        }
        
        postActive(() -> {
            try {
                Intent intent = new Intent(activity, TerminalActivity.class);
                intent.putExtra("RUN_FILE", filePath);
                intent.putExtra(TerminalActivity.RUN_ARGS, args);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            safeRun(onComplete);
        });
    }

    /**
     * 替换变量 ${var_name}
     */
    private String replaceVariables(String str) {
        if (str == null) return "";
        String result = str;
        for (Map.Entry<String, String> entry : variableMap.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 获取插件 PATH
     */
    public static String getPluginPath(File pluginDir) {
        File pathFile = new File(pluginDir, ".path");
        if (!pathFile.exists()) return null;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(pathFile));
            String path = reader.readLine();
            reader.close();
            return path;
        } catch (Exception e) {
            return null;
        }
    }
}
