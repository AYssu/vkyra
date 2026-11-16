package com.vkyra.code.settings;

import com.vkyra.code.R;
import com.vkyra.code.toolchain.BuildToolsInstaller;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.update.UpdateUi;

import static com.vkyra.code.settings.AppPreferences.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileWriter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        
        toolbar.setNavigationOnClickListener(v -> finish());
        
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // 使用自定义的 SharedPreferences
            getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
            setPreferencesFromResource(R.xml.preferences, rootKey);
            
            setDefaultValues();
            updateVersionInfo();
            
            Preference enableLogsPref = findPreference(KEY_ENABLE_LOGS);
            if (enableLogsPref != null) {
                enableLogsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enableLogs = (Boolean) newValue;
                    setEnableLogs(getContext(), enableLogs);
                    return true;
                });
            }
            
            Preference keepScreenOnPref = findPreference(KEY_KEEP_SCREEN_ON);
            if (keepScreenOnPref != null) {
                keepScreenOnPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean keepScreenOn = (Boolean) newValue;
                    setKeepScreenOn(getContext(), keepScreenOn);
                    return true;
                });
            }
            
            Preference showBottomToolbarPref = findPreference(KEY_SHOW_BOTTOM_TOOLBAR);
            if (showBottomToolbarPref != null) {
                showBottomToolbarPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean showBottomToolbar = (Boolean) newValue;
                    setShowBottomToolbar(getContext(), showBottomToolbar);
                    sendBottomToolbarSettingsBroadcast(getContext());
                    return true;
                });
            }
            
            Preference rememberLastFilePref = findPreference(KEY_REMEMBER_LAST_FILE);
            if (rememberLastFilePref != null) {
                rememberLastFilePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean remember = (Boolean) newValue;
                    setRememberLastFile(getContext(), remember);
                    return true;
                });
            }
            
            Preference enableRootRunPref = findPreference(KEY_ENABLE_ROOT_RUN);
            if (enableRootRunPref != null) {
                enableRootRunPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enableRootRun = (Boolean) newValue;
                    setEnableRootRun(getContext(), enableRootRun);
                    return true;
                });
            }
            
            Preference aboutPref = findPreference(KEY_ABOUT);
            if (aboutPref != null) {
                aboutPref.setOnPreferenceClickListener(preference -> {
                    openGitHubRepository();
                    return true;
                });
            }
            
            Preference clearCachePref = findPreference(KEY_CLEAR_CACHE);
            if (clearCachePref != null) {
                clearCachePref.setOnPreferenceClickListener(preference -> {
                    clearCache();
                    return true;
                });
            }
            
            Preference exportLogsPref = findPreference(KEY_EXPORT_LOGS);
            if (exportLogsPref != null) {
                exportLogsPref.setOnPreferenceClickListener(preference -> {
                    exportLogs();
                    return true;
                });
            }
            
            // 项目根目录设置
            Preference projectRootPref = findPreference(KEY_PROJECT_ROOT);
            if (projectRootPref != null) {
                projectRootPref.setSummary(getProjectRoot(getContext()));
                projectRootPref.setOnPreferenceClickListener(preference -> {
                    showProjectRootDialog();
                    return true;
                });
            }
            
            Preference acknowledgementsPref = findPreference(KEY_ACKNOWLEDGEMENTS);
            if (acknowledgementsPref != null) {
                acknowledgementsPref.setOnPreferenceClickListener(preference -> {
                    showAcknowledgementsDialog();
                    return true;
                });
            }
            
            // 版本信息 - 点击检查更新
            Preference versionPref = findPreference("version");
            if (versionPref != null) {
                versionPref.setOnPreferenceClickListener(preference -> {
                    checkVersionUpdate();
                    return true;
                });
            }
            
            // 官方群聊
            Preference qqGroupPref = findPreference("qq_group");
            if (qqGroupPref != null) {
                qqGroupPref.setOnPreferenceClickListener(preference -> {
                    showQQGroupDialog();
                    return true;
                });
            }
            
            // 安装编译工具
            Preference installBuildToolsPref = findPreference("install_build_tools");
            if (installBuildToolsPref != null) {
                updateBuildToolsStatus(installBuildToolsPref);
                installBuildToolsPref.setOnPreferenceClickListener(preference -> {
                    if (BuildToolsInstaller.isInstalled(getContext())) {
                        // 已安装，显示选项
                        showBuildToolsOptionsDialog(installBuildToolsPref);
                    } else {
                        // 未安装，显示安装对话框
                        BuildToolsInstaller.showInstallDialog(getContext(), () -> {
                            updateBuildToolsStatus(installBuildToolsPref);
                        });
                    }
                    return true;
                });
            }
            
            // 编译命令管理
            Preference compileCommandsPref = findPreference(KEY_COMPILE_COMMANDS);
            if (compileCommandsPref != null) {
                compileCommandsPref.setOnPreferenceClickListener(preference -> {
                    showCompileCommandsDialog();
                    return true;
                });
            }
            
            // 自定义 Preference 使用 M3 风格对话框
            setupM3EditPreference(KEY_CLANG_ARGS, "Clang 编译参数", getClangArgs(getContext()));
            setupM3EditPreference(KEY_CUSTOM_SU, "自定义 SU 命令", getCustomSu(getContext()));
            // 自定义 ListPreference 使用 M3 风格对话框
            setupM3ListPreference(KEY_FONT_SIZE, "终端字体大小",
                getResources().getStringArray(R.array.font_size_entries),
                getResources().getStringArray(R.array.font_size_values),
                String.valueOf(getFontSize(getContext())));
            
            setupM3ListPreference(KEY_TERMINAL_FONT, "终端字体",
                getResources().getStringArray(R.array.font_entries),
                getResources().getStringArray(R.array.font_values),
                getTerminalFont(getContext()));
            
            setupM3ListPreference(KEY_EDITOR_FONT_SIZE, "编辑器字体大小",
                getResources().getStringArray(R.array.editor_font_size_entries),
                getResources().getStringArray(R.array.editor_font_size_values),
                String.valueOf(getEditorFontSize(getContext())));
            
            setupM3ListPreference(KEY_EDITOR_THEME, "编辑器主题",
                getResources().getStringArray(R.array.editor_theme_entries),
                getResources().getStringArray(R.array.editor_theme_values),
                getEditorTheme(getContext()));
            
            // Android API Level 设置
            setupM3ListPreference(KEY_ANDROID_API_LEVEL, "Android API Level",
                getResources().getStringArray(R.array.android_api_level_entries),
                getResources().getStringArray(R.array.android_api_level_values),
                String.valueOf(getAndroidApiLevel(getContext())));
            
            // 更新 API Level 的 summary
            Preference apiLevelPref = findPreference(KEY_ANDROID_API_LEVEL);
            if (apiLevelPref != null) {
                int apiLevel = getAndroidApiLevel(getContext());
                apiLevelPref.setSummary("API " + apiLevel);
            }
            
            // 自动保存设置
            Preference autoSavePref = findPreference(KEY_AUTO_SAVE);
            if (autoSavePref != null) {
                autoSavePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean autoSave = (Boolean) newValue;
                    setAutoSave(getContext(), autoSave);
                    return true;
                });
            }
            
            // 自动保存间隔
            Preference autoSaveIntervalPref = findPreference(KEY_AUTO_SAVE_INTERVAL);
            if (autoSaveIntervalPref != null) {
                int interval = getAutoSaveInterval(getContext());
                autoSaveIntervalPref.setSummary(interval + " 秒");
                autoSaveIntervalPref.setOnPreferenceClickListener(preference -> {
                    showAutoSaveIntervalDialog();
                    return true;
                });
            }
            

        }
        
        private void setupM3ListPreference(String key, String title, String[] entries, String[] values, String defaultValue) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceClickListener(preference -> {
                    // 动态获取当前值
                    String currentValue = defaultValue;
                    if (KEY_FONT_SIZE.equals(key)) {
                        currentValue = String.valueOf(getFontSize(getContext()));
                    } else if (KEY_TERMINAL_FONT.equals(key)) {
                        currentValue = getTerminalFont(getContext());
                    } else if (KEY_EDITOR_FONT_SIZE.equals(key)) {
                        currentValue = String.valueOf(getEditorFontSize(getContext()));
                    } else if (KEY_EDITOR_THEME.equals(key)) {
                        currentValue = getEditorTheme(getContext());
                    } else if (KEY_ANDROID_API_LEVEL.equals(key)) {
                        currentValue = String.valueOf(getAndroidApiLevel(getContext()));
                    }
                    showM3ListDialog(key, title, entries, values, currentValue);
                    return true;
                });
            }
        }
        
        private void showM3ListDialog(String key, String title, String[] entries, String[] values, String currentValue) {
            int checkedIndex = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(currentValue)) {
                    checkedIndex = i;
                    break;
                }
            }
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                    String newValue = values[which];
                    
                    // 保存设置
                    if (KEY_FONT_SIZE.equals(key)) {
                        setFontSize(getContext(), Integer.parseInt(newValue));
                        sendFontSettingsBroadcast(getContext());
                    } else if (KEY_TERMINAL_FONT.equals(key)) {
                        setTerminalFont(getContext(), newValue);
                        sendFontSettingsBroadcast(getContext());
                    } else if (KEY_EDITOR_FONT_SIZE.equals(key)) {
                        setEditorFontSize(getContext(), Integer.parseInt(newValue));
                    } else if (KEY_EDITOR_THEME.equals(key)) {
                        setEditorTheme(getContext(), newValue);
                    } else if (KEY_ANDROID_API_LEVEL.equals(key)) {
                        setAndroidApiLevel(getContext(), Integer.parseInt(newValue));
                        // 更新 summary
                        Preference pref = findPreference(KEY_ANDROID_API_LEVEL);
                        if (pref != null) {
                            pref.setSummary("API " + newValue);
                        }
                        // 更新 clang 配置文件
                        ClangInstaller.updateClangConfig(getContext());
                    }
                    
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        }
        
        private void setupM3EditPreference(String key, String title, String defaultValue) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceClickListener(preference -> {
                    String currentValue = defaultValue;
                    if (KEY_CLANG_ARGS.equals(key)) {
                        currentValue = getClangArgs(getContext());
                    } else if (KEY_CUSTOM_SU.equals(key)) {
                        currentValue = getCustomSu(getContext());
                    }
                    showM3EditDialog(key, title, currentValue);
                    return true;
                });
            }
        }
        
        private void showM3EditDialog(String key, String title, String currentValue) {
            TextInputLayout inputLayout = new TextInputLayout(getContext(), null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
            inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            inputLayout.setPadding(48, 32, 48, 0);
            
            TextInputEditText input = new TextInputEditText(inputLayout.getContext());
            input.setText(currentValue);
            input.setSelection(input.getText().length());
            inputLayout.addView(input);
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setView(inputLayout)
                .setPositiveButton("确定", (d, w) -> {
                    String newValue = input.getText().toString();
                    if (KEY_CLANG_ARGS.equals(key)) {
                        setClangArgs(getContext(), newValue);
                    } else if (KEY_CUSTOM_SU.equals(key)) {
                        setCustomSu(getContext(), newValue);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        }
        
        private void updateVersionInfo() {
            try {
                PackageInfo packageInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                String versionName = packageInfo.versionName;
                int versionCode = packageInfo.versionCode;
                
                Preference aboutPreference = findPreference(KEY_ABOUT);
                if (aboutPreference != null) {
                    aboutPreference.setSummary("C++ IDE v" + versionName);
                }
                
                Preference versionPreference = findPreference("version");
                if (versionPreference != null) {
                    versionPreference.setSummary("构建版本: " + versionName + " (" + versionCode + ")");
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(LOG_TAG, "Error getting package info", e);
            }
        }
        
        private void openGitHubRepository() {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AYssu/vkyra"));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(getContext(), "无法打开页面", Toast.LENGTH_SHORT).show();
            }
        }
        
        private void showQQGroupDialog() {
            // 创建对话框布局
            android.widget.LinearLayout layout = new android.widget.LinearLayout(getContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            layout.setPadding(48, 32, 48, 32);
            
            // 群聊二维码图片
            ImageView qrCodeImage = new ImageView(getContext());
            String qqGroupUrl = "https://qm.qq.com/q/y6swAr56Rc";
            android.graphics.Bitmap qrBitmap = generateQRCodeWithLogo(qqGroupUrl, 512);
            if (qrBitmap != null) {
                qrCodeImage.setImageBitmap(qrBitmap);
            }
            qrCodeImage.setAdjustViewBounds(true);
            android.widget.LinearLayout.LayoutParams imageParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            imageParams.bottomMargin = 32;
            qrCodeImage.setLayoutParams(imageParams);
            layout.addView(qrCodeImage);
            
            // 提示文字
            TextView hint = new TextView(getContext());
            hint.setText("扫一扫二维码，加入群聊");
            hint.setTextColor(0xFF9E9E9E);
            hint.setTextSize(14);
            hint.setGravity(android.view.Gravity.CENTER);
            layout.addView(hint);
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("Vkyra C/C++ IDE 官方群")
                .setView(layout)
                .setPositiveButton("打开链接", (d, w) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(qqGroupUrl));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "无法打开链接", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
        }
        
        /**
         * 生成带 Logo 的二维码（透明背景、白色二维码）
         */
        private android.graphics.Bitmap generateQRCodeWithLogo(String content, int size) {
            try {
                com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
                java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
                hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
                hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
                hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);
                
                com.google.zxing.common.BitMatrix bitMatrix = writer.encode(content, 
                    com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
                
                int width = bitMatrix.getWidth();
                int height = bitMatrix.getHeight();
                int[] pixels = new int[width * height];
                
                // 透明背景，白色二维码
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        pixels[y * width + x] = bitMatrix.get(x, y) ? 0xFFFFFFFF : 0x00000000;
                    }
                }
                
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, 
                    android.graphics.Bitmap.Config.ARGB_8888);
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
                
                // 在中间添加应用图标
                return addLogoToQRCode(bitmap);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        
        /**
         * 在二维码中间添加应用图标
         */
        private android.graphics.Bitmap addLogoToQRCode(android.graphics.Bitmap qrBitmap) {
            try {
                int qrWidth = qrBitmap.getWidth();
                int qrHeight = qrBitmap.getHeight();
                
                // 获取应用图标
                android.graphics.drawable.Drawable drawable = getContext().getPackageManager()
                    .getApplicationIcon(getContext().getPackageName());
                android.graphics.Bitmap logoBitmap = android.graphics.Bitmap.createBitmap(
                    drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas logoCanvas = new android.graphics.Canvas(logoBitmap);
                drawable.setBounds(0, 0, logoCanvas.getWidth(), logoCanvas.getHeight());
                drawable.draw(logoCanvas);
                
                // Logo 大小为二维码的 1/5
                int logoSize = qrWidth / 5;
                android.graphics.Bitmap scaledLogo = android.graphics.Bitmap.createScaledBitmap(
                    logoBitmap, logoSize, logoSize, true);
                
                // 创建结果 Bitmap
                android.graphics.Bitmap result = android.graphics.Bitmap.createBitmap(
                    qrWidth, qrHeight, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(result);
                
                // 绘制二维码
                canvas.drawBitmap(qrBitmap, 0, 0, null);
                
                // 计算 Logo 位置（居中）
                int left = (qrWidth - logoSize) / 2;
                int top = (qrHeight - logoSize) / 2;
                
                // 绘制白色圆角背景
                android.graphics.Paint bgPaint = new android.graphics.Paint();
                bgPaint.setColor(0xFF1E1E1E);
                bgPaint.setAntiAlias(true);
                int padding = 8;
                android.graphics.RectF bgRect = new android.graphics.RectF(
                    left - padding, top - padding, 
                    left + logoSize + padding, top + logoSize + padding);
                canvas.drawRoundRect(bgRect, 12, 12, bgPaint);
                
                // 绘制 Logo
                canvas.drawBitmap(scaledLogo, left, top, null);
                
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                return qrBitmap;
            }
        }
        
        private void clearCache() {
            try {
                File cacheDir = getContext().getCacheDir();
                if (cacheDir.exists()) {
                    deleteRecursive(cacheDir);
                }
                
                File externalCacheDir = getContext().getExternalCacheDir();
                if (externalCacheDir != null && externalCacheDir.exists()) {
                    deleteRecursive(externalCacheDir);
                }
                
                Toast.makeText(getContext(), "缓存清除完成", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(getContext(), "清除缓存失败", Toast.LENGTH_SHORT).show();
            }
        }
        
        private void showProjectRootDialog() {
            TextInputLayout inputLayout = new TextInputLayout(getContext(), null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
            inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            inputLayout.setPadding(48, 32, 48, 0);
            inputLayout.setHint("项目根目录路径");
            
            TextInputEditText input = new TextInputEditText(inputLayout.getContext());
            input.setText(getProjectRoot(getContext()));
            input.setSelection(input.getText().length());
            inputLayout.addView(input);
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("项目根目录")
                .setView(inputLayout)
                .setPositiveButton("确定", (d, w) -> {
                    String newPath = input.getText().toString().trim();
                    if (!newPath.isEmpty()) {
                        setProjectRoot(getContext(), newPath);
                        Preference pref = findPreference(KEY_PROJECT_ROOT);
                        if (pref != null) {
                            pref.setSummary(newPath);
                        }
                        // 确保目录存在
                        File dir = new File(newPath);
                        if (!dir.exists()) {
                            dir.mkdirs();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        }
        
        private void showAutoSaveIntervalDialog() {
            String[] entries = {"5 秒", "10 秒", "15 秒", "30 秒", "60 秒"};
            int[] values = {5, 10, 15, 30, 60};
            int currentInterval = getAutoSaveInterval(getContext());
            
            int checkedIndex = 1; // 默认10秒
            for (int i = 0; i < values.length; i++) {
                if (values[i] == currentInterval) {
                    checkedIndex = i;
                    break;
                }
            }
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("自动保存间隔")
                .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                    setAutoSaveInterval(getContext(), values[which]);
                    Preference pref = findPreference(KEY_AUTO_SAVE_INTERVAL);
                    if (pref != null) {
                        pref.setSummary(entries[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        }
        
        private void exportLogs() {
            try {
                File shareDir = new File(getContext().getCacheDir(), "shared");
                java.nio.file.Files.createDirectories(shareDir.toPath());
                File logFile = File.createTempFile("vkyra-logs-", ".txt", shareDir);
                FileWriter writer = new FileWriter(logFile);
                
                PackageInfo packageInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                writer.write("C++ IDE Logs\n");
                writer.write("Version: " + packageInfo.versionName + "\n");
                writer.write("Export Time: " + new java.util.Date().toString() + "\n");
                writer.write("========================================\n\n");
                writer.close();
                
                shareLogFile(logFile);
                Toast.makeText(getContext(), "日志已导出", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(getContext(), "导出日志失败", Toast.LENGTH_SHORT).show();
            }
        }
        
        private void shareLogFile(File logFile) {
            try {
                Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    logFile
                );
                
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "导出日志"));
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error sharing log file", e);
            }
        }
        
        private void deleteRecursive(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            fileOrDirectory.delete();
        }
        
        private void showAcknowledgementsDialog() {
            // 创建主布局
            android.widget.LinearLayout mainLayout = new android.widget.LinearLayout(getContext());
            mainLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            
            // ========== 赞助列表 ==========
            TextView sponsorTitle = new TextView(getContext());
            sponsorTitle.setText("赞助者");
            sponsorTitle.setTextColor(0xFF4EC9B0);
            sponsorTitle.setTextSize(12);
            sponsorTitle.setPadding(48, 24, 48, 8);
            mainLayout.addView(sponsorTitle);
            
            List<SponsorItem> sponsors = new ArrayList<>();
            // 按金额从高到低排序添加赞助者
            sponsors.add(new SponsorItem(1, "Lin", 50.00, "", "", "3530500498"));

            // 排序（金额从高到低）
            sponsors.sort((a, b) -> Double.compare(b.amount, a.amount));
            
            RecyclerView sponsorRecyclerView = new RecyclerView(getContext());
            sponsorRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            sponsorRecyclerView.setNestedScrollingEnabled(false);
            sponsorRecyclerView.setAdapter(new SponsorAdapter(sponsors, new SponsorAdapter.OnSponsorClickListener() {
                @Override
                public void onGithubClick(String github) {
                    openUrl(github);
                }
                @Override
                public void onBilibiliClick(String bilibili) {
                    openUrl(bilibili);
                }
                @Override
                public void onQQClick(String qq) {
                    Toast.makeText(getContext(), "QQ: " + qq, Toast.LENGTH_SHORT).show();
                }
            }));
            mainLayout.addView(sponsorRecyclerView);
            
            // 分隔线
            View divider = new View(getContext());
            divider.setBackgroundColor(0xFF2D2D2D);
            android.widget.LinearLayout.LayoutParams dividerParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
            dividerParams.setMargins(48, 16, 48, 0);
            divider.setLayoutParams(dividerParams);
            mainLayout.addView(divider);
            
            // ========== 致谢列表 ==========
            TextView ackTitle = new TextView(getContext());
            ackTitle.setText("开源致谢");
            ackTitle.setTextColor(0xFF4EC9B0);
            ackTitle.setTextSize(12);
            ackTitle.setPadding(48, 16, 48, 8);
            mainLayout.addView(ackTitle);

            TextView ackNotice = new TextView(getContext());
            ackNotice.setText("（以下项目排名不分先后）");
            ackNotice.setTextColor(0xFF9E9E9E);
            ackNotice.setTextSize(11);
            ackNotice.setPadding(48, 0, 48, 8);
            mainLayout.addView(ackNotice);
            
            List<AckItem> items = new ArrayList<>();
            
            // 开发者放最前面
            items.add(new AckItem("阿夜", "开发者", "https://github.com/AYssu", true));
            
            items.add(new AckItem("LSP4J", "Language Server Protocol 客户端", "https://github.com/eclipse-lsp4j/lsp4j", false));
            // 开源库列表
            items.add(new AckItem("Sora Editor", "代码编辑器组件", "https://github.com/Rosemoe/sora-editor", false));
            items.add(new AckItem("C4droid", "Android平台C/C++语言编程工具", "https://baike.baidu.com/item/c4droid/8215130", false));
            items.add(new AckItem("simpleC", "Android平台C/C++语言编程工具", "https://github.com/luoyesiqiu/simpleC", false));
            items.add(new AckItem("libsu", "Root权限访问库", "https://github.com/topjohnwu/libsu", false));
            items.add(new AckItem("Termux", "终端模拟器", "https://github.com/termux/termux-app", false));
            items.add(new AckItem("Material Components", "Material Design 组件库", "https://github.com/material-components/material-components-android", false));
            items.add(new AckItem("AndroidX", "Android 基础库", "https://github.com/androidx/androidx", false));
            items.add(new AckItem("ZXing", "二维码生成库", "https://github.com/zxing/zxing", false));
            items.add(new AckItem("Markwon", "Markdown 渲染库", "https://github.com/noties/Markwon", false));
            items.add(new AckItem("JGit", "Git Java 实现库", "https://github.com/eclipse-jgit/jgit", false));
            items.add(new AckItem("apk-parser", "APK 元数据解析库", "https://github.com/hsiafan/apk-parser", false));
            items.add(new AckItem("Zip4j", "ZIP 压缩与解压库", "https://github.com/srikanth-lingala/zip4j", false));
            items.add(new AckItem("Bouncy Castle", "加密与证书库", "https://github.com/bcgit/bc-java", false));
            items.add(new AckItem("Android apksig", "APK 签名工具库", "https://android.googlesource.com/platform/tools/apksig/", false));
            items.add(new AckItem("ARSCLib", "Android 资源表与二进制XML处理库", "https://github.com/REAndroid/ARSCLib", false));
            items.add(new AckItem("AndroidSVG", "SVG 渲染库", "https://github.com/BigBadaboom/androidsvg", false));
            items.add(new AckItem("Apache Commons Compress", "压缩格式处理库", "https://github.com/apache/commons-compress", false));
            items.add(new AckItem("CustomActivityOnCrash", "崩溃捕获与恢复库", "https://github.com/Ereza/CustomActivityOnCrash", false));
            items.add(new AckItem("Clang / LLVM (AYssu Fork)", "C/C++ 编译器工具链与 OLLVM 支持", "https://github.com/ayssu/llvm-project", false));
            items.add(new AckItem("Zsh", "终端 Shell 环境", "https://www.zsh.org/", false));
            items.add(new AckItem("CMake", "跨平台构建系统", "https://cmake.org/", false));
            items.add(new AckItem("GNU Make", "构建自动化工具", "https://www.gnu.org/software/make/", false));
            items.add(new AckItem("Ninja", "高速构建系统", "https://ninja-build.org/", false));
            
            RecyclerView ackRecyclerView = new RecyclerView(getContext());
            ackRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            ackRecyclerView.setNestedScrollingEnabled(false);
            ackRecyclerView.setAdapter(new AckAdapter(items, this::openUrl));
            mainLayout.addView(ackRecyclerView);
            
            // 包装在 ScrollView 中
            androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(getContext());
            scrollView.addView(mainLayout);
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("致谢")
                .setView(scrollView)
                .setPositiveButton("确定", null)
                .show();
        }
        
        private void openUrl(String url) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(getContext(), "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        }
        
        /**
         * 检查版本更新
         */
        private void checkVersionUpdate() {
            UpdateUi.check(requireActivity(), getViewLifecycleOwner());
        }

        // 赞助者数据类
        private static class SponsorItem {
            int id;
            String name;
            double amount;
            String github;
            String bilibili;
            String qq;
            
            SponsorItem(int id, String name, double amount, String github, String bilibili, String qq) {
                this.id = id;
                this.name = name;
                this.amount = amount;
                this.github = github;
                this.bilibili = bilibili;
                this.qq = qq;
            }
        }
        
        // 赞助者适配器
        private static class SponsorAdapter extends RecyclerView.Adapter<SponsorAdapter.ViewHolder> {
            private final List<SponsorItem> items;
            private final OnSponsorClickListener listener;
            
            interface OnSponsorClickListener {
                void onGithubClick(String github);
                void onBilibiliClick(String bilibili);
                void onQQClick(String qq);
            }
            
            SponsorAdapter(List<SponsorItem> items, OnSponsorClickListener listener) {
                this.items = items;
                this.listener = listener;
            }
            
            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_sponsor, parent, false);
                return new ViewHolder(view);
            }
            
            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                SponsorItem item = items.get(position);
                holder.tvName.setText(item.name);
                holder.tvAmount.setText("¥" + String.format("%.2f", item.amount));
                bindSponsorAvatar(holder, item.id);
                
                // GitHub 图标
                if (item.github != null && !item.github.isEmpty()) {
                    holder.ivGithub.setVisibility(View.VISIBLE);
                    holder.ivGithub.setOnClickListener(v -> listener.onGithubClick(item.github));
                } else {
                    holder.ivGithub.setVisibility(View.GONE);
                }
                
                // Bilibili 图标
                if (item.bilibili != null && !item.bilibili.isEmpty()) {
                    holder.ivBilibili.setVisibility(View.VISIBLE);
                    holder.ivBilibili.setOnClickListener(v -> listener.onBilibiliClick(item.bilibili));
                } else {
                    holder.ivBilibili.setVisibility(View.GONE);
                }
                
                // QQ 图标
                if (item.qq != null && !item.qq.isEmpty()) {
                    holder.ivQQ.setVisibility(View.VISIBLE);
                    holder.ivQQ.setOnClickListener(v -> listener.onQQClick(item.qq));
                } else {
                    holder.ivQQ.setVisibility(View.GONE);
                }
            }
            
            @Override
            public int getItemCount() {
                return items.size();
            }
            
            static class ViewHolder extends RecyclerView.ViewHolder {
                TextView tvName, tvAmount;
                ImageView ivAvatar;
                ImageView ivGithub, ivBilibili, ivQQ;
                
                ViewHolder(View itemView) {
                    super(itemView);
                    ivAvatar = itemView.findViewById(R.id.iv_sponsor_avatar);
                    tvName = itemView.findViewById(R.id.tv_sponsor_name);
                    tvAmount = itemView.findViewById(R.id.tv_sponsor_amount);
                    ivGithub = itemView.findViewById(R.id.iv_github);
                    ivBilibili = itemView.findViewById(R.id.iv_bilibili);
                    ivQQ = itemView.findViewById(R.id.iv_qq);
                }
            }

            private void bindSponsorAvatar(@NonNull ViewHolder holder, int sponsorId) {
                String avatarFile = "zanzhu" + sponsorId + ".jpg";
                android.graphics.Bitmap bmp = decodeAssetAvatar(holder.itemView.getContext(), avatarFile);
                if (bmp != null) {
                    holder.ivAvatar.setImageBitmap(bmp);
                } else {
                    holder.ivAvatar.setImageResource(R.drawable.ic_developer);
                }
            }

            private android.graphics.Bitmap decodeAssetAvatar(Context context, String avatarFile) {
                String[] candidates = new String[] {
                    avatarFile,
                    "icons/" + avatarFile
                };
                for (String path : candidates) {
                    try (java.io.InputStream is = context.getAssets().open(path)) {
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            return bmp;
                        }
                    } catch (Exception ignored) {
                    }
                }
                return null;
            }
        }
        
        /**
         * 更新编译工具安装状态
         */
        private void updateBuildToolsStatus(Preference pref) {
            if (BuildToolsInstaller.isInstalled(getContext())) {
                pref.setSummary("已安装 (CMake, Make, Ninja)");
            } else {
                pref.setSummary("未安装 - 点击安装 CMake、Make、Ninja");
            }
        }
        
        /**
         * 显示编译工具选项对话框
         */
        private void showBuildToolsOptionsDialog(Preference pref) {
            String[] options = {"查看安装路径", "重新安装", "卸载"};
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("编译工具")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // 查看安装路径
                            String path = BuildToolsInstaller.getBuildToolsDir(getContext());
                            new MaterialAlertDialogBuilder(getContext())
                                .setTitle("安装路径")
                                .setMessage("编译工具安装在:\n" + path + "\n\n" +
                                    "包含:\n• cmake\n• make\n• ninja\n\n" +
                                    "终端启动时会自动添加到 PATH，可直接使用。")
                                .setPositiveButton("确定", null)
                                .show();
                            break;
                        case 1: // 重新安装
                            BuildToolsInstaller.reinstallWithProgress(getContext(), () -> {
                                updateBuildToolsStatus(pref);
                            });
                            break;
                        case 2: // 卸载
                            new MaterialAlertDialogBuilder(getContext())
                                .setTitle("确认卸载")
                                .setMessage("确定要卸载编译工具吗？")
                                .setPositiveButton("卸载", (d, w) -> {
                                    BuildToolsInstaller.uninstall(getContext());
                                    updateBuildToolsStatus(pref);
                                    android.widget.Toast.makeText(getContext(), "编译工具已卸载", android.widget.Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        }
        
        /**
         * 显示编译命令管理对话框
         */
        private void showCompileCommandsDialog() {
            List<CompileCommand> commands = getCompileCommands(getContext());
            
            // 创建 RecyclerView
            RecyclerView recyclerView = new RecyclerView(getContext());
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setPadding(0, 16, 0, 16);
            
            CompileCommandAdapter adapter = new CompileCommandAdapter(commands, new CompileCommandAdapter.OnCommandActionListener() {
                @Override
                public void onEnabledChanged(int position, boolean enabled) {
                    commands.get(position).enabled = enabled;
                    saveCompileCommands(getContext(), commands);
                }
                
                @Override
                public void onItemClick(int position) {
                    showEditCompileCommandDialog(commands.get(position), position, commands, 
                        () -> recyclerView.getAdapter().notifyDataSetChanged());
                }
                
                @Override
                public void onItemLongClick(int position) {
                    new MaterialAlertDialogBuilder(getContext())
                        .setTitle("删除命令")
                        .setMessage("确定删除 \"" + commands.get(position).name + "\" 吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            commands.remove(position);
                            saveCompileCommands(getContext(), commands);
                            recyclerView.getAdapter().notifyItemRemoved(position);
                        })
                        .setNegativeButton("取消", null)
                        .show();
                }
            });
            recyclerView.setAdapter(adapter);
            
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("编译命令管理")
                .setView(recyclerView)
                .setPositiveButton("添加", (d, w) -> {
                    showAddCompileCommandDialog(commands, () -> showCompileCommandsDialog());
                })
                .setNegativeButton("关闭", null)
                .show();
        }
        
        /**
         * 显示添加编译命令对话框
         */
        private void showAddCompileCommandDialog(List<CompileCommand> commands, Runnable onSaved) {
            showEditCompileCommandDialog(null, -1, commands, onSaved);
        }
        
        /**
         * 显示编辑编译命令对话框
         */
        private void showEditCompileCommandDialog(CompileCommand command, int position, List<CompileCommand> commands, Runnable onSaved) {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(getContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(48, 32, 48, 0);
            
            // 名称输入
            TextInputLayout nameLayout = new TextInputLayout(getContext(), null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
            nameLayout.setHint("命令名称");
            nameLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            TextInputEditText nameInput = new TextInputEditText(nameLayout.getContext());
            if (command != null) nameInput.setText(command.name);
            nameLayout.addView(nameInput);
            layout.addView(nameLayout);
            
            // 编译命令输入
            TextInputLayout compileLayout = new TextInputLayout(getContext(), null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
            compileLayout.setHint("编译命令");
            compileLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            android.widget.LinearLayout.LayoutParams compileParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            compileParams.topMargin = 16;
            compileLayout.setLayoutParams(compileParams);
            TextInputEditText compileInput = new TextInputEditText(compileLayout.getContext());
            if (command != null) compileInput.setText(command.compileCmd);
            compileLayout.addView(compileInput);
            layout.addView(compileLayout);
            
            // 运行命令输入
            TextInputLayout runLayout = new TextInputLayout(getContext(), null, 
                com.google.android.material.R.attr.textInputOutlinedStyle);
            runLayout.setHint("运行命令");
            runLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            android.widget.LinearLayout.LayoutParams runParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            runParams.topMargin = 16;
            runLayout.setLayoutParams(runParams);
            TextInputEditText runInput = new TextInputEditText(runLayout.getContext());
            if (command != null) runInput.setText(command.runCmd);
            runLayout.addView(runInput);
            layout.addView(runLayout);
            
            // 设为默认选项
            android.widget.CheckBox defaultCheckBox = new android.widget.CheckBox(getContext());
            defaultCheckBox.setText("设为默认");
            defaultCheckBox.setTextColor(0xFFE6E1E5);
            defaultCheckBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFD0BCFF));
            if (command != null) defaultCheckBox.setChecked(command.isDefault);
            android.widget.LinearLayout.LayoutParams cbParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            cbParams.topMargin = 16;
            defaultCheckBox.setLayoutParams(cbParams);
            layout.addView(defaultCheckBox);
            
            // 提示文本
            TextView hint = new TextView(getContext());
            hint.setText("提示: 使用 {source} 表示源文件, {output} 表示输出文件");
            hint.setTextColor(0xFF9E9E9E);
            hint.setTextSize(12);
            android.widget.LinearLayout.LayoutParams hintParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = 16;
            hint.setLayoutParams(hintParams);
            layout.addView(hint);
            
            String title = command == null ? "添加编译命令" : "编辑编译命令";
            new MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("保存", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    String compileCmd = compileInput.getText().toString().trim();
                    String runCmd = runInput.getText().toString().trim();
                    boolean isDefault = defaultCheckBox.isChecked();
                    
                    if (name.isEmpty()) {
                        Toast.makeText(getContext(), "请输入命令名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 如果设为默认，清除其他命令的默认状态
                    if (isDefault) {
                        for (CompileCommand cmd : commands) {
                            cmd.isDefault = false;
                        }
                    }
                    
                    if (command == null) {
                        // 添加新命令
                        CompileCommand newCmd = new CompileCommand(name, compileCmd, runCmd, true, isDefault);
                        commands.add(newCmd);
                    } else {
                        // 编辑现有命令
                        command.name = name;
                        command.compileCmd = compileCmd;
                        command.runCmd = runCmd;
                        command.isDefault = isDefault;
                    }
                    saveCompileCommands(getContext(), commands);
                    Toast.makeText(getContext(), "已保存", Toast.LENGTH_SHORT).show();
                    
                    // 回调刷新列表
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        }
        
        // 致谢项数据类
        private static class AckItem {
            String title;
            String description;
            String url;
            boolean isDeveloper;
            
            AckItem(String title, String description, String url, boolean isDeveloper) {
                this.title = title;
                this.description = description;
                this.url = url;
                this.isDeveloper = isDeveloper;
            }
        }
        
        // 点击回调接口
        private interface OnItemClickListener {
            void onClick(String url);
        }
        
        // RecyclerView 适配器
        private static class AckAdapter extends RecyclerView.Adapter<AckAdapter.ViewHolder> {
            private final List<AckItem> items;
            private final OnItemClickListener listener;
            
            AckAdapter(List<AckItem> items, OnItemClickListener listener) {
                this.items = items;
                this.listener = listener;
            }
            
            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_acknowledgement, parent, false);
                return new ViewHolder(view);
            }
            
            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                AckItem item = items.get(position);
                holder.title.setText(item.title);
                holder.description.setText(item.description);
                
                if (item.isDeveloper) {
                    holder.icon.setVisibility(View.GONE);
                    holder.iconText.setVisibility(View.VISIBLE);
                    holder.iconText.setText("阿");
                } else {
                    holder.icon.setVisibility(View.VISIBLE);
                    holder.iconText.setVisibility(View.GONE);
                }
                
                holder.itemView.setOnClickListener(v -> listener.onClick(item.url));
            }
            
            @Override
            public int getItemCount() {
                return items.size();
            }
            
            static class ViewHolder extends RecyclerView.ViewHolder {
                ImageView icon;
                TextView iconText;
                TextView title;
                TextView description;
                
                ViewHolder(View itemView) {
                    super(itemView);
                    icon = itemView.findViewById(R.id.icon);
                    iconText = itemView.findViewById(R.id.icon_text);
                    title = itemView.findViewById(R.id.title);
                    description = itemView.findViewById(R.id.description);
                }
            }
        }
        
        private void setDefaultValues() {
            Context context = getContext();
            if (context == null) return;
            ensureDefaultSettings(context);
        }
    }

    public static class CompileCommandAdapter extends RecyclerView.Adapter<CompileCommandAdapter.ViewHolder> {
        private final List<CompileCommand> commands;
        private final OnCommandActionListener listener;
        
        public interface OnCommandActionListener {
            void onEnabledChanged(int position, boolean enabled);
            void onItemClick(int position);
            void onItemLongClick(int position);
        }
        
        public CompileCommandAdapter(List<CompileCommand> commands, OnCommandActionListener listener) {
            this.commands = commands;
            this.listener = listener;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_compile_command, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CompileCommand cmd = commands.get(position);
            holder.tvName.setText(cmd.name);
            holder.tvCompileCmd.setText("编译: " + (cmd.compileCmd.isEmpty() ? "无" : cmd.compileCmd));
            holder.tvRunCmd.setText("运行: " + (cmd.runCmd.isEmpty() ? "无" : cmd.runCmd));
            holder.switchEnabled.setChecked(cmd.enabled);
            
            // 显示/隐藏默认标签
            holder.tvDefaultTag.setVisibility(cmd.isDefault ? View.VISIBLE : View.GONE);
            
            holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null) {
                    listener.onEnabledChanged(holder.getAdapterPosition(), isChecked);
                }
            });
            
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(holder.getAdapterPosition());
                }
            });
            
            holder.itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onItemLongClick(holder.getAdapterPosition());
                }
                return true;
            });
            
            // 禁用时降低透明度
            holder.itemView.setAlpha(cmd.enabled ? 1.0f : 0.5f);
        }
        
        @Override
        public int getItemCount() {
            return commands.size();
        }
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvDefaultTag;
            TextView tvCompileCmd;
            TextView tvRunCmd;
            com.google.android.material.materialswitch.MaterialSwitch switchEnabled;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_name);
                tvDefaultTag = itemView.findViewById(R.id.tv_default_tag);
                tvCompileCmd = itemView.findViewById(R.id.tv_compile_cmd);
                tvRunCmd = itemView.findViewById(R.id.tv_run_cmd);
                switchEnabled = itemView.findViewById(R.id.switch_enabled);
            }
        }
    }
    
}
