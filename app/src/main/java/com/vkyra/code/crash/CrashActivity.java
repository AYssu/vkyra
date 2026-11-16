package com.vkyra.code.crash;

import com.vkyra.code.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import cat.ereza.customactivityoncrash.CustomActivityOnCrash;
import cat.ereza.customactivityoncrash.config.CaocConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 崩溃页面
 */
public class CrashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                // Keep crash details visible until the user chooses the explicit close/restart action.
            }
        });
        setContentView(R.layout.activity_crash);
        
        // 设置状态栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
        }
        
        // 获取崩溃配置
        CaocConfig config = CustomActivityOnCrash.getConfigFromIntent(getIntent());
        
        if (config == null) {
            finish();
            return;
        }
        
        // 获取错误信息
        String errorDetails = CustomActivityOnCrash.getAllErrorDetailsFromIntent(this, getIntent());
        String stackTrace = CustomActivityOnCrash.getStackTraceFromIntent(getIntent());
        
        // 显示错误信息（带语法高亮）
        TextView tvErrorDetails = findViewById(R.id.tv_error_details);
        tvErrorDetails.setText(highlightErrorText(errorDetails));
        tvErrorDetails.setTextIsSelectable(true);  // 允许选中
        tvErrorDetails.setTypeface(Typeface.MONOSPACE);  // 等宽字体
        
        // 重启按钮
        Button btnRestart = findViewById(R.id.btn_restart);
        btnRestart.setOnClickListener(v -> {
            CustomActivityOnCrash.restartApplication(this, config);
        });
        
        // 复制错误信息按钮
        Button btnCopy = findViewById(R.id.btn_copy);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("崩溃信息", errorDetails + "\n\n堆栈跟踪:\n" + stackTrace);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        
        // 关闭应用按钮
        Button btnClose = findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> {
            CustomActivityOnCrash.closeApplication(this, config);
        });
    }
    
    /**
     * 给错误信息添加语法高亮
     */
    private SpannableString highlightErrorText(String text) {
        SpannableString spannableString = new SpannableString(text);
        
        // 定义颜色
        int exceptionColor = 0xFFF44747;      // 红色 - 异常类名
        int atColor = 0xFF569CD6;             // 蓝色 - at 关键字
        int packageColor = 0xFF4EC9B0;        // 青色 - 包名
        int methodColor = 0xFFDCDCAA;         // 黄色 - 方法名
        int fileColor = 0xFF9E9E9E;           // 灰色 - 文件名和行号
        int labelColor = 0xFFC586C0;          // 紫色 - 标签（Build version, Device等）
        
        // 1. 高亮异常类名（如 java.lang.RuntimeException）
        Pattern exceptionPattern = Pattern.compile("([a-zA-Z][a-zA-Z0-9.]*Exception|[a-zA-Z][a-zA-Z0-9.]*Error)");
        Matcher exceptionMatcher = exceptionPattern.matcher(text);
        while (exceptionMatcher.find()) {
            spannableString.setSpan(
                new ForegroundColorSpan(exceptionColor),
                exceptionMatcher.start(),
                exceptionMatcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        
        // 2. 高亮 "at" 关键字
        Pattern atPattern = Pattern.compile("\\bat\\b");
        Matcher atMatcher = atPattern.matcher(text);
        while (atMatcher.find()) {
            spannableString.setSpan(
                new ForegroundColorSpan(atColor),
                atMatcher.start(),
                atMatcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        
        // 3. 高亮包名（com.vkyra.code）
        Pattern packagePattern = Pattern.compile("\\b(com\\.[a-zA-Z0-9.]+)\\.");
        Matcher packageMatcher = packagePattern.matcher(text);
        while (packageMatcher.find()) {
            spannableString.setSpan(
                new ForegroundColorSpan(packageColor),
                packageMatcher.start(1),
                packageMatcher.end(1),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        
        // 4. 高亮方法名（括号前的部分）
        Pattern methodPattern = Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)\\(");
        Matcher methodMatcher = methodPattern.matcher(text);
        while (methodMatcher.find()) {
            spannableString.setSpan(
                new ForegroundColorSpan(methodColor),
                methodMatcher.start(1),
                methodMatcher.end(1),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        
        // 5. 高亮文件名和行号（.java:123）
        Pattern filePattern = Pattern.compile("\\(([^:)]+:\\d+)\\)");
        Matcher fileMatcher = filePattern.matcher(text);
        while (fileMatcher.find()) {
            spannableString.setSpan(
                new ForegroundColorSpan(fileColor),
                fileMatcher.start(1),
                fileMatcher.end(1),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        
        // 6. 高亮标签（Build version:, Device:, OS version: 等）
        Pattern labelPattern = Pattern.compile("^([A-Za-z][A-Za-z ]+:)", Pattern.MULTILINE);
        Matcher labelMatcher = labelPattern.matcher(text);
        while (labelMatcher.find()) {
            spannableString.setSpan(
                new ForegroundColorSpan(labelColor),
                labelMatcher.start(1),
                labelMatcher.end(1),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        
        return spannableString;
    }
    
}
