package com.vkyra.code.plugin;

import com.vkyra.code.Log;
import com.vkyra.code.R;
import com.vkyra.code.editor.EditorActivity;
import com.vkyra.code.files.RootFileUtils;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;

public class PluginActivity extends AppCompatActivity {

    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Vkyra-plugin-install"));
    private volatile boolean destroyed;
    private volatile PluginInstallTransaction activeTransaction;
    private PluginInstallScript activeScript;
    private androidx.appcompat.app.AlertDialog activeDialog;
    private boolean preparingOrInstalling;
    private RecyclerView pluginList;
    private View emptyView;
    private PluginAdapter adapter;
    private File pluginDir;
    private File pendingInstallZipFile;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plugin);

        // 设置状态栏和导航栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
        }

        initFilePicker();
        initViews();
        initPluginDir();
        installExecutor.execute(() -> {
            try {
                PluginInstallTransaction.recoverInterruptedInstalls(pluginDir);
                postUi(this::loadPlugins);
            } catch (IOException failure) {
                postUi(() -> Toast.makeText(this, "插件恢复失败: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void initFilePicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            preInstallPlugin(uri);
                        }
                    }
                }
        );
    }

    private void initViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        pluginList = findViewById(R.id.plugin_list);
        emptyView = findViewById(R.id.empty_view);

        adapter = new PluginAdapter();
        adapter.setOnPluginActionListener(new PluginAdapter.OnPluginActionListener() {
            @Override
            public void onLongClick(PluginInfo plugin, int position) {
                showDeleteConfirmDialog(plugin, position);
            }

            @Override
            public void onAbiClick(PluginInfo plugin, int position) {
                showAbiSelectDialog(plugin, position);
            }

            @Override
            public void onClick(PluginInfo plugin, int position) {
                showPluginFilesDialog(plugin);
            }

            @Override
            public void onEnableChanged(PluginInfo plugin, int position, boolean enabled) {
                savePluginEnabled(plugin, enabled, position);
            }

            @Override
            public void onUninstallClick(PluginInfo plugin, int position) {
                showDeleteConfirmDialog(plugin, position);
            }

            @Override
            public void onLoadErrorClick(PluginInfo plugin, int position) {
                showHookLoadErrorDialog(plugin);
            }
        });

        pluginList.setLayoutManager(new LinearLayoutManager(this));
        pluginList.setAdapter(adapter);

        FloatingActionButton fabInstall = findViewById(R.id.fab_install);
        fabInstall.setOnClickListener(v -> openFilePicker());
    }

    private void openFilePicker() {
        // 方法1: 尝试使用 ACTION_OPEN_DOCUMENT（Android 4.4+）
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            filePickerLauncher.launch(intent);
            return;
        } catch (Exception e1) {
            android.util.Log.e("PluginActivity", "ACTION_OPEN_DOCUMENT failed: " + e1.getMessage());
        }
        
        // 方法2: 尝试使用 ACTION_GET_CONTENT
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            filePickerLauncher.launch(Intent.createChooser(intent, "选择插件压缩包"));
            return;
        } catch (Exception e2) {
            android.util.Log.e("PluginActivity", "ACTION_GET_CONTENT failed: " + e2.getMessage());
        }
        
        // 方法3: 尝试直接打开文件管理器
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"), "*/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "请手动选择ZIP文件", Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e3) {
            android.util.Log.e("PluginActivity", "ACTION_VIEW failed: " + e3.getMessage());
        }
        
        Toast.makeText(this, "无法打开文件选择器，请检查是否安装了文件管理器", Toast.LENGTH_LONG).show();
    }

    private void preInstallPlugin(Uri zipUri) {
        if (preparingOrInstalling) {
            Toast.makeText(this, "请等待当前插件安装完成", Toast.LENGTH_SHORT).show();
            return;
        }
        preparingOrInstalling = true;
        androidx.appcompat.app.AlertDialog preparingDialog = new MaterialAlertDialogBuilder(this)
            .setTitle("准备安装")
            .setMessage("正在复制插件包...")
            .setCancelable(false)
            .create();
        preparingDialog.show();
        activeDialog = preparingDialog;

        installExecutor.execute(() -> {
            try {
                clearPendingInstallZip();

                PluginZipExtractor.ProgressCallback progressCallback = new PluginZipExtractor.ProgressCallback() {
                    @Override
                    public void onStage(String stage) {
                        postUi(() -> preparingDialog.setMessage(stage));
                    }

                    @Override
                    public void onProgress(int percentDone, String detail) {
                        postUi(() -> preparingDialog.setMessage(detail));
                    }
                };

                File localZip = PluginZipExtractor.copyUriToCache(this, zipUri, progressCallback);
                pendingInstallZipFile = localZip;

                ensureInstallActive();
                postUi(() -> preparingDialog.setMessage("复制完成，正在分析压缩包..."));
                String configContent = PluginZipExtractor.readConfig(localZip, progressCallback);
                if (configContent == null) {
                    clearPendingInstallZip();
                    postUi(() -> {
                        preparingDialog.dismiss();
                        preparingOrInstalling = false;
                        Toast.makeText(this, "无效的插件包：缺少.config文件", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                PluginInfo pluginInfo = PluginInfo.fromJson(configContent);
                if (pluginInfo == null || pluginInfo.getPluginCode() == null || !PluginInstallTransaction.isValidPluginId(pluginInfo.getPluginCode())) {
                    clearPendingInstallZip();
                    postUi(() -> {
                        preparingDialog.dismiss();
                        preparingOrInstalling = false;
                        Toast.makeText(this, "无效的插件配置", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                postUi(() -> {
                    preparingDialog.dismiss();
                    showAbiSelectDialogForInstall(pluginInfo);
                });

            } catch (Exception e) {
                clearPendingInstallZip();
                postUi(() -> {
                    preparingDialog.dismiss();
                    preparingOrInstalling = false;
                    Toast.makeText(this, "读取插件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showAbiSelectDialogForInstall(PluginInfo pluginInfo) {
        List<String> abiList = pluginInfo.getSupportABI();
        if (abiList == null || abiList.isEmpty()) {
            // 没有指定 ABI，使用默认
            installPlugin(pluginInfo, Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a");
            return;
        }

        // 获取设备支持的 ABI 列表
        String[] deviceAbis = Build.SUPPORTED_ABIS;
        
        // 找到插件支持且设备兼容的 ABI
        List<String> compatibleAbis = new ArrayList<>();
        for (String pluginAbi : abiList) {
            for (String deviceAbi : deviceAbis) {
                if (pluginAbi.equals(deviceAbi)) {
                    compatibleAbis.add(pluginAbi);
                    break;
                }
            }
        }
        
        // 如果没有兼容的 ABI，提示不能安装
        if (compatibleAbis.isEmpty()) {
            clearPendingInstallZip();
            preparingOrInstalling = false;
            new MaterialAlertDialogBuilder(this)
                .setTitle("无法安装")
                .setMessage("该插件不支持您的设备架构\n\n插件支持: " + String.join(", ", abiList) + "\n设备支持: " + String.join(", ", deviceAbis))
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        
        // 如果只有一个兼容的 ABI，直接安装
        if (compatibleAbis.size() == 1) {
            installPlugin(pluginInfo, compatibleAbis.get(0));
            return;
        }

        // 多个兼容的 ABI，让用户选择，默认选中第一个
        String[] abiArray = compatibleAbis.toArray(new String[0]);
        final int[] selectedIndex = {0};

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择 ABI")
                .setSingleChoiceItems(abiArray, 0, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton("安装", (dialog, which) -> {
                    String selectedAbi = abiArray[selectedIndex[0]];
                    installPlugin(pluginInfo, selectedAbi);
                })
                .setOnCancelListener(dialog -> cancelPendingInstall())
                .setNegativeButton("取消", (dialog, which) -> cancelPendingInstall())
                .show();
    }

    private void installPlugin(PluginInfo pluginInfo, String selectedAbi) {
        if (pendingInstallZipFile == null || !pendingInstallZipFile.exists()) {
            preparingOrInstalling = false;
            Toast.makeText(this, "插件包已失效，请重新选择", Toast.LENGTH_SHORT).show();
            return;
        }
        // 创建 M3 风格的加载对话框（支持显示安装进度）
        android.widget.LinearLayout loadingLayout = new android.widget.LinearLayout(this);
        loadingLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        loadingLayout.setPadding(64, 48, 64, 48);
        
        android.widget.LinearLayout topRow = new android.widget.LinearLayout(this);
        topRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        com.google.android.material.progressindicator.CircularProgressIndicator progressIndicator = 
            new com.google.android.material.progressindicator.CircularProgressIndicator(this);
        progressIndicator.setIndeterminate(true);
        progressIndicator.setIndicatorSize(48);
        topRow.addView(progressIndicator);

        com.google.android.material.progressindicator.LinearProgressIndicator linearProgress =
            new com.google.android.material.progressindicator.LinearProgressIndicator(this);
        linearProgress.setIndeterminate(true);
        linearProgress.setVisibility(View.GONE);
        linearProgress.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        
        android.widget.TextView loadingText = new android.widget.TextView(this);
        loadingText.setText("正在安装插件...");
        loadingText.setTextColor(0xFFE0E0E0);
        loadingText.setTextSize(16);
        loadingText.setPadding(32, 0, 0, 0);
        topRow.addView(loadingText);
        loadingLayout.addView(topRow);
        loadingLayout.addView(linearProgress);
        
        // 进度详情文本
        android.widget.TextView progressDetail = new android.widget.TextView(this);
        progressDetail.setTextColor(0xFF9E9E9E);
        progressDetail.setTextSize(12);
        progressDetail.setPadding(0, 16, 0, 0);
        progressDetail.setMaxLines(3);
        progressDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        loadingLayout.addView(progressDetail);
        
        androidx.appcompat.app.AlertDialog loadingDialog = new MaterialAlertDialogBuilder(this)
            .setView(loadingLayout)
            .setCancelable(false)
            .create();
        loadingDialog.show();
        activeDialog = loadingDialog;

        final File localZip = pendingInstallZipFile;
        pendingInstallZipFile = null;

        installExecutor.execute(() -> {
            PluginInstallTransaction transaction = null;
            try {
                ensureInstallActive();
                transaction = PluginInstallTransaction.begin(pluginDir, pluginInfo.getPluginCode());
                activeTransaction = transaction;
                synchronized (transaction) {
                ensureInstallActive();
                File stagingDir = transaction.getStagingDirectory();
                PluginZipExtractor.extract(localZip, stagingDir, new PluginZipExtractor.ProgressCallback() {
                    @Override public void onStage(String stage) {
                        postUi(() -> { loadingText.setText(stage); progressDetail.setText(stage); });
                    }
                    @Override public void onProgress(int percentDone, String detail) {
                        postUi(() -> {
                            progressDetail.setText(detail);
                            linearProgress.setVisibility(View.VISIBLE);
                            linearProgress.setIndeterminate(percentDone < 0);
                            if (percentDone >= 0) linearProgress.setProgressCompat(percentDone, true);
                        });
                    }
                });
                PluginInstallTransaction.writeMetadata(new File(stagingDir, ".abi"),
                        selectedAbi.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                File config = new File(stagingDir, ".config");
                PluginInfo extracted = config.isFile() ? PluginInfo.fromJson(readFileContent(config)) : null;
                if (extracted == null || !pluginInfo.getPluginCode().equals(extracted.getPluginCode())) {
                    throw new IOException("插件根目录配置无效或与预览不一致");
                }
                java.nio.file.Files.deleteIfExists(new File(stagingDir, ".enabled").toPath());
                ensureInstallActive();
                transaction.activate();
                final PluginInstallTransaction ready = transaction;
                postUi(() -> {
                    activeScript = new PluginInstallScript(this, ready.getTargetDirectory());
                    activeScript.setCallback(new PluginInstallScript.InstallCallback() {
                        @Override public void onProgress(String message) {
                            postUi(() -> progressDetail.setText(message));
                        }
                        @Override public void onComplete(boolean success, String message) {
                            if (!success) failInstall(ready, loadingDialog, message);
                        }
                    });
                    if (activeScript.hasInstallScript()) {
                        activeScript.executeInstallBefore(() -> activeScript.executeInstallStart(
                                () -> activeScript.executeInstallAfter(() -> finishInstall(ready, pluginInfo, loadingDialog))));
                    } else {
                        finishInstall(ready, pluginInfo, loadingDialog);
                    }
                });
                }
            } catch (Exception failure) {
                final PluginInstallTransaction failed = transaction;
                rollbackQuietly(failed);
                postUi(() -> {
                    loadingDialog.dismiss();
                    preparingOrInstalling = false;
                    Toast.makeText(this, "安装失败: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                PluginZipExtractor.deleteQuietly(localZip);
            }
        });
    }

    private void ensureInstallActive() throws InterruptedException {
        if (destroyed || Thread.currentThread().isInterrupted()) throw new InterruptedException("插件安装已取消");
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> { if (!destroyed && !isFinishing() && !isDestroyed()) action.run(); });
    }

    private void cancelPendingInstall() {
        clearPendingInstallZip();
        preparingOrInstalling = false;
    }

    private void failInstall(PluginInstallTransaction transaction, androidx.appcompat.app.AlertDialog dialog, String message) {
        if (destroyed) return;
        installExecutor.execute(() -> {
            String result = message;
            try {
                try { if (activeScript != null) activeScript.cancelAndAwait(); }
                catch (Exception cleanup) { transaction.blockRecovery(); throw cleanup; }
                transaction.rollback();
            }
            catch (Exception failure) { result += "；旧插件将在下次打开时恢复: " + failure.getMessage(); }
            activeTransaction = null;
            final String detail = result;
            postUi(() -> {
                dialog.dismiss();
                preparingOrInstalling = false;
                Toast.makeText(this, "安装失败: " + detail, Toast.LENGTH_LONG).show();
                loadPlugins();
            });
        });
    }

    private void rollbackQuietly(PluginInstallTransaction transaction) {
        if (transaction != null) {
            try { transaction.rollback(); }
            catch (IOException failure) { android.util.Log.e("PluginActivity", "Plugin rollback deferred to recovery", failure); }
        }
        if (activeTransaction == transaction) activeTransaction = null;
    }

    private void clearPendingInstallZip() {
        if (pendingInstallZipFile != null) {
            PluginZipExtractor.deleteQuietly(pendingInstallZipFile);
            pendingInstallZipFile = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        PluginInstallScript script = activeScript;
        if (script != null) script.cancel();
        installExecutor.shutdownNow();
        clearPendingInstallZip();
        if (activeDialog != null) activeDialog.dismiss();
        PluginInstallTransaction transaction = activeTransaction;
        if (transaction != null) new Thread(() -> {
            try {
                try { if (script != null) script.cancelAndAwait(); }
                catch (Exception cleanup) { transaction.blockRecovery(); throw cleanup; }
                rollbackQuietly(transaction);
            } catch (Exception failure) {
                android.util.Log.e("PluginActivity", "Process cleanup failed; preserving transaction for recovery", failure);
            }
        }, "Vkyra-plugin-rollback").start();
        super.onDestroy();
    }

    private void finishInstall(PluginInstallTransaction transaction, PluginInfo pluginInfo,
            androidx.appcompat.app.AlertDialog loadingDialog) {
        if (destroyed) return;
        installExecutor.execute(() -> {
            try {
                synchronized (transaction) {
                    ensureInstallActive();
                    PluginInstallTransaction.writeMetadata(new File(transaction.getTargetDirectory(), ".enabled"), new byte[0]);
                    transaction.commit();
                }
                activeTransaction = null;
                postUi(() -> {
                    loadingDialog.dismiss();
                    preparingOrInstalling = false;
                    if (activeScript != null) activeScript.releaseLifecycle();
                    activeScript = null;
                    Toast.makeText(this, "插件 " + pluginInfo.getPluginName() + " 安装成功", Toast.LENGTH_SHORT).show();
                    PluginManager.reloadHooks(this);
                    loadPlugins();
                });
            } catch (Exception failure) {
                rollbackQuietly(transaction);
                postUi(() -> {
                    loadingDialog.dismiss();
                    preparingOrInstalling = false;
                    Toast.makeText(this, "安装失败: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                    loadPlugins();
                });
            }
        });
    }

    private void showAbiSelectDialog(PluginInfo plugin, int position) {
        List<String> abiList = plugin.getSupportABI();
        if (abiList == null || abiList.size() <= 1) {
            Toast.makeText(this, "该插件只支持一种ABI", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] abiArray = abiList.toArray(new String[0]);
        String currentAbi = plugin.getCurrentABI();
        int currentIndex = 0;
        for (int i = 0; i < abiArray.length; i++) {
            if (abiArray[i].equals(currentAbi)) {
                currentIndex = i;
                break;
            }
        }

        final int[] selectedIndex = {currentIndex};

        new MaterialAlertDialogBuilder(this)
                .setTitle("切换 ABI")
                .setSingleChoiceItems(abiArray, currentIndex, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton("确定", (dialog, which) -> {
                    String selectedAbi = abiArray[selectedIndex[0]];
                    saveAbi(plugin, selectedAbi, position);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveAbi(PluginInfo plugin, String abi, int position) {
        if (preparingOrInstalling) return;
        installExecutor.execute(() -> {
            try {
                if (abi == null || !abi.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IOException("无效的 ABI");
                File directory = PluginInstallTransaction.resolvePluginDirectory(pluginDir, new File(plugin.getPath()).getName());
                PluginInstallTransaction.writeMetadata(new File(directory, ".abi"), abi.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                postUi(() -> {
                    loadPlugins();
                    Toast.makeText(this, "已切换到 " + abi, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException failure) {
                postUi(() -> Toast.makeText(this, "保存失败: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void savePluginEnabled(PluginInfo plugin, boolean enabled, int position) {
        if (preparingOrInstalling) { loadPlugins(); return; }
        installExecutor.execute(() -> {
            try {
                File directory = PluginInstallTransaction.resolvePluginDirectory(pluginDir, new File(plugin.getPath()).getName());
                File enabledFile = new File(directory, ".enabled");
                if (enabled) PluginInstallTransaction.writeMetadata(enabledFile, new byte[0]);
                else java.nio.file.Files.deleteIfExists(enabledFile.toPath());
                postUi(() -> {
                    PluginManager.reloadHooks(this);
                    loadPlugins();
                    Toast.makeText(this, enabled ? "已启用" : "已禁用", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException failure) {
                postUi(() -> {
                    loadPlugins();
                    Toast.makeText(this, "操作失败: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void initPluginDir() {
        pluginDir = new File(getFilesDir(), "plugin");
        if (!pluginDir.exists()) {
            pluginDir.mkdirs();
        }
    }

    private void loadPlugins() {
        List<PluginInfo> plugins = new ArrayList<>();
        PluginHookManager hookManager = PluginManager.getHookManager(this);
        if (pluginDir.exists() && pluginDir.isDirectory()) {
            File[] files = pluginDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && !file.getName().startsWith(".")
                            && PluginInstallTransaction.isValidPluginId(file.getName())
                            && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                        File configFile = new File(file, ".config");
                        if (configFile.exists()) {
                            String configJson = readFileContent(configFile);
                            if (configJson != null) {
                                PluginInfo info = PluginInfo.fromJson(configJson);
                                if (info != null) {
                                    info.setPath(file.getAbsolutePath());
                                    File abiFile = new File(file, ".abi");
                                    if (abiFile.exists()) {
                                        String abi = readFileContent(abiFile);
                                        if (abi != null) {
                                            info.setCurrentABI(abi.trim());
                                        }
                                    }
                                    // 读取启用状态（.enabled文件存在则启用）
                                    File enabledFile = new File(file, ".enabled");
                                    info.setEnabled(enabledFile.exists());
                                    info.setLoadError(hookManager.getHookLoadError(file.getName()));
                                    plugins.add(info);
                                    continue;
                                }
                            }
                        }
                        PluginInfo info = new PluginInfo();
                        info.setPluginCode(file.getName());
                        info.setPluginName(file.getName());
                        info.setPluginDescribe("未知插件");
                        info.setPluginVersion("1.0.0");
                        info.setPath(file.getAbsolutePath());
                        info.setLoadError(hookManager.getHookLoadError(file.getName()));
                        plugins.add(info);
                    }
                }
            }
        }
        adapter.setPlugins(plugins);
        updateEmptyView(plugins.isEmpty());
    }

    private void showHookLoadErrorDialog(PluginInfo plugin) {
        String error = plugin.getLoadError();
        if (error == null || error.trim().isEmpty()) {
            Toast.makeText(this, "未找到错误详情", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(plugin.getPluginName() + " - 加载失败")
                .setMessage(error)
                .setPositiveButton("确定", null)
                .show();
    }

    private String readFileContent(File file) {
        if (file.length() > PluginZipExtractor.MAX_CONFIG_BYTES) return null;
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (content.length() + count > PluginZipExtractor.MAX_CONFIG_BYTES) return null;
                content.append(buffer, 0, count);
            }
            return content.toString();
        } catch (IOException failure) {
            return null;
        }
    }

    private void updateEmptyView(boolean isEmpty) {
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        pluginList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void showDeleteConfirmDialog(PluginInfo plugin, int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除插件")
                .setMessage("确定要删除插件 \"" + plugin.getPluginName() + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> deletePlugin(plugin, position))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPluginFilesDialog(PluginInfo plugin) {
        installExecutor.execute(() -> {
            File directory = new File(plugin.getPath());
            List<File> sourceFiles = new ArrayList<>();
            List<File> headerFiles = new ArrayList<>();
            findAndClassifyCppFiles(directory, sourceFiles, headerFiles);
            postUi(() -> showPluginFiles(plugin, directory, sourceFiles, headerFiles));
        });
    }

    private void showPluginFiles(PluginInfo plugin, File pluginDir, List<File> sourceFiles, List<File> headerFiles) {
        if (sourceFiles.isEmpty() && headerFiles.isEmpty()) {
            Toast.makeText(this, "该插件没有示例代码文件", Toast.LENGTH_SHORT).show();
            return;
        }

        // 构建分类列表
        List<String> displayItems = new ArrayList<>();
        List<File> allFiles = new ArrayList<>();
        
        // 先添加示例文件（源码）
        if (!sourceFiles.isEmpty()) {
            displayItems.add("── 示例文件 ──");
            allFiles.add(null); // 占位
            for (File file : sourceFiles) {
                String relativePath = file.getAbsolutePath()
                        .replace(pluginDir.getAbsolutePath() + File.separator, "");
                displayItems.add("    " + relativePath);
                allFiles.add(file);
            }
        }
        
        // 再添加头文件
        if (!headerFiles.isEmpty()) {
            displayItems.add("── 头文件 ──");
            allFiles.add(null); // 占位
            for (File file : headerFiles) {
                String relativePath = file.getAbsolutePath()
                        .replace(pluginDir.getAbsolutePath() + File.separator, "");
                displayItems.add("    " + relativePath);
                allFiles.add(file);
            }
        }

        String[] itemArray = displayItems.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle(plugin.getPluginName() + " - 示例代码")
                .setItems(itemArray, (dialog, which) -> {
                    File selectedFile = allFiles.get(which);
                    if (selectedFile != null) {
                        openFileInEditor(selectedFile);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void findAndClassifyCppFiles(File dir, List<File> sourceFiles, List<File> headerFiles) {
        if (dir == null || !dir.exists() || java.nio.file.Files.isSymbolicLink(dir.toPath())
                || Thread.currentThread().isInterrupted()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) return;
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) continue;
            if (file.isDirectory()) {
                findAndClassifyCppFiles(file, sourceFiles, headerFiles);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".c") || name.endsWith(".cpp") || 
                    name.endsWith(".cc") || name.endsWith(".cxx")) {
                    sourceFiles.add(file);
                } else if (name.endsWith(".h") || name.endsWith(".hpp") || name.endsWith(".hxx")) {
                    headerFiles.add(file);
                }
            }
        }
    }

    private void openFileInEditor(File file) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("打开方式")
            .setMessage("选择如何打开 " + file.getName())
            .setPositiveButton("另存一份", (d, w) -> {
                showSaveAsDialogForPlugin(file);
            })
            .setNegativeButton("直接编辑", (d, w) -> {
                Intent intent = new Intent(this, EditorActivity.class);
                intent.putExtra("file_path", file.getAbsolutePath());
                startActivity(intent);
            })
            .setNeutralButton("取消", null)
            .show();
    }
    
    private void showSaveAsDialogForPlugin(File sourceFile) {
        // 创建主布局
        android.widget.LinearLayout mainLayout = new android.widget.LinearLayout(this);
        mainLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        mainLayout.setPadding(48, 32, 48, 0);

        // 文件夹选择区域
        android.widget.LinearLayout folderLayout = new android.widget.LinearLayout(this);
        folderLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        folderLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        folderLayout.setPadding(0, 0, 0, 24);

        android.widget.TextView folderLabel = new android.widget.TextView(this);
        folderLabel.setText("保存到: ");
        folderLabel.setTextColor(0xFFAAAAAA);
        folderLabel.setTextSize(14);

        android.widget.TextView folderPath = new android.widget.TextView(this);
        File defaultDir = android.os.Environment.getExternalStorageDirectory();
        folderPath.setText(defaultDir.getAbsolutePath());
        folderPath.setTextColor(0xFFCCCCCC);
        folderPath.setTextSize(14);
        folderPath.setSingleLine(true);
        folderPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        folderPath.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        android.widget.ImageButton browseBtn = new android.widget.ImageButton(this);
        browseBtn.setImageResource(R.drawable.ic_folder);
        browseBtn.setBackgroundResource(android.R.color.transparent);
        browseBtn.setColorFilter(0xFF9E9E9E);
        browseBtn.setPadding(16, 0, 0, 0);

        folderLayout.addView(folderLabel);
        folderLayout.addView(folderPath);
        folderLayout.addView(browseBtn);
        mainLayout.addView(folderLayout);

        // 文件名输入框
        com.google.android.material.textfield.TextInputLayout inputLayout = new com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("文件名");
        inputLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);

        com.google.android.material.textfield.TextInputEditText input = new com.google.android.material.textfield.TextInputEditText(inputLayout.getContext());
        String defaultName = sourceFile.getName();
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
        final File[] targetDir = {defaultDir};

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
                    File targetFile = new File(targetDir[0], name);
                    try {
                        // 复制文件内容
                        String content = RootFileUtils.readFile(sourceFile.getAbsolutePath());
                        RootFileUtils.writeFile(targetFile.getAbsolutePath(), content);
                        Toast.makeText(this, "已保存: " + targetFile.getName(), Toast.LENGTH_SHORT).show();
                        
                        // 打开保存后的文件
                        Intent intent = new Intent(this, EditorActivity.class);
                        intent.putExtra("file_path", targetFile.getAbsolutePath());
                        startActivity(intent);
                        dialog.dismiss();
                    } catch (Exception e) {
                        Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });

        // 文件夹选择按钮点击
        browseBtn.setOnClickListener(v -> showFolderPickerDialog(targetDir, folderPath));
        folderPath.setOnClickListener(v -> showFolderPickerDialog(targetDir, folderPath));

        dialog.show();
    }
    
    private void showFolderPickerDialog(File[] targetDir, android.widget.TextView folderPathView) {
        final File[] browseDir = {targetDir[0]};

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);

        // 当前路径显示
        android.widget.TextView pathView = new android.widget.TextView(this);
        pathView.setText(browseDir[0].getAbsolutePath());
        pathView.setTextColor(0xFFCCCCCC);
        pathView.setTextSize(12);
        pathView.setPadding(48, 24, 48, 16);
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        layout.addView(pathView);

        // 文件夹列表
        androidx.recyclerview.widget.RecyclerView recyclerView = new androidx.recyclerview.widget.RecyclerView(this);
        recyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        android.widget.LinearLayout.LayoutParams rvParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 800);
        recyclerView.setLayoutParams(rvParams);
        layout.addView(recyclerView);

        // 简单的文件夹适配器
        List<File> folders = new java.util.ArrayList<>();
        updateFolderList(browseDir[0], folders);

        FolderAdapter adapter = new FolderAdapter(folders, folder -> {
            browseDir[0] = folder;
            pathView.setText(folder.getAbsolutePath());
            updateFolderList(folder, folders);
            recyclerView.getAdapter().notifyDataSetChanged();
        });
        recyclerView.setAdapter(adapter);

        new MaterialAlertDialogBuilder(this)
            .setTitle("选择文件夹")
            .setView(layout)
            .setPositiveButton("选择此目录", (d, w) -> {
                targetDir[0] = browseDir[0];
                folderPathView.setText(browseDir[0].getAbsolutePath());
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void updateFolderList(File dir, List<File> folders) {
        folders.clear();
        // 添加返回上级
        if (dir.getParentFile() != null) {
            folders.add(dir.getParentFile());
        }
        // 添加子文件夹
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    folders.add(f);
                }
            }
            // 排序
            java.util.Collections.sort(folders.subList(dir.getParentFile() != null ? 1 : 0, folders.size()), 
                (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
    }
    
    // 简单的文件夹适配器
    private static class FolderAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<FolderAdapter.ViewHolder> {
        private final List<File> folders;
        private final OnFolderClickListener listener;
        private File parentDir;
        
        interface OnFolderClickListener {
            void onClick(File folder);
        }
        
        FolderAdapter(List<File> folders, OnFolderClickListener listener) {
            this.folders = folders;
            this.listener = listener;
        }
        
        void setParentDir(File parent) {
            this.parentDir = parent;
        }
        
        @androidx.annotation.NonNull
        @Override
        public ViewHolder onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(parent.getContext());
            layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
            layout.setPadding(48, 24, 48, 24);
            layout.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            
            android.widget.ImageView icon = new android.widget.ImageView(parent.getContext());
            icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(48, 48));
            icon.setColorFilter(0xFF9E9E9E);
            layout.addView(icon);
            
            android.widget.TextView text = new android.widget.TextView(parent.getContext());
            text.setTextColor(0xFFE0E0E0);
            text.setTextSize(14);
            text.setPadding(24, 0, 0, 0);
            layout.addView(text);
            
            return new ViewHolder(layout, icon, text);
        }
        
        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull ViewHolder holder, int position) {
            File folder = folders.get(position);
            boolean isParent = position == 0 && folders.size() > 0 && 
                folder.getAbsolutePath().length() < (folders.size() > 1 ? folders.get(1).getParent().length() : Integer.MAX_VALUE);
            
            if (isParent) {
                holder.text.setText(".. (返回上级)");
                holder.icon.setImageResource(R.drawable.ic_arrow_back);
            } else {
                holder.text.setText(folder.getName());
                holder.icon.setImageResource(R.drawable.ic_folder);
            }
            
            holder.itemView.setOnClickListener(v -> listener.onClick(folder));
        }
        
        @Override
        public int getItemCount() {
            return folders.size();
        }
        
        static class ViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            android.widget.ImageView icon;
            android.widget.TextView text;
            
            ViewHolder(android.view.View itemView, android.widget.ImageView icon, android.widget.TextView text) {
                super(itemView);
                this.icon = icon;
                this.text = text;
            }
        }
    }

    private void deletePlugin(PluginInfo plugin, int position) {
        if (preparingOrInstalling) {
            Toast.makeText(this, "请等待当前插件安装完成", Toast.LENGTH_SHORT).show();
            return;
        }
        installExecutor.execute(() -> {
            boolean deleted = deleteRecursive(new File(plugin.getPath()));
            postUi(() -> {
                if (deleted) {
                    PluginManager.reloadHooks(this);
                    loadPlugins();
                }
                Toast.makeText(this, deleted ? "插件已删除" : "删除失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private boolean deleteRecursive(File file) {
        try {
            File approved = PluginInstallTransaction.resolvePluginDirectory(pluginDir, file.getName());
            if (!approved.equals(file.getCanonicalFile())) return false;
            PluginInstallTransaction.deleteTree(approved);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }
}
