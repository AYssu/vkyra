package com.vkyra.code.update;

import com.vkyra.code.R;
import com.vkyra.code.ui.MarkdownUtils;

import android.content.Context;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;


/**
 * 版本更新对话框工具类
 * 统一主页和设置页面的更新弹窗样式
 */
public class UpdateDialogHelper {

    /**
     * 显示更新对话框
     * @param context 上下文
     * @param releaseInfo 版本信息
     * @param currentVersion 当前版本
     * @param isNewVersion 是否是新版本
     * @param onUpdate 点击更新的回调
     */
    public static void showUpdateDialog(Context context, VersionChecker.ReleaseInfo releaseInfo, 
            String currentVersion, boolean isNewVersion, Runnable onUpdate) {
        String title;
        if (isNewVersion) {
            title = "发现新版本 v" + releaseInfo.version;
        } else {
            title = "当前版本 v" + currentVersion + " (最新发布: v" + releaseInfo.version + ")";
        }
        
        // 创建ScrollView包装TextView以支持长内容滚动
        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12));
        
        TextView contentView = new TextView(context);
        contentView.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                contentView, com.google.android.material.R.attr.colorOnSurface));
        contentView.setTextIsSelectable(true);
        contentView.setTextSize(14);
        contentView.setLineSpacing(0, 1.3f);
        // 使用Markwon渲染Markdown
        MarkdownUtils.setMarkdown(contentView, releaseInfo.body);
        scrollView.addView(contentView);
        
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(scrollView);
        
        if (isNewVersion && releaseInfo.downloadUrl != null) {
            builder.setPositiveButton("立即更新", (dialog, which) -> {
                if (onUpdate != null) {
                    onUpdate.run();
                }
            });
            builder.setNegativeButton("取消", null);
        } else {
            builder.setPositiveButton("关闭", null);
        }
        
        builder.show();
    }
    
    /**
     * 简化版：仅用于主页显示新版本（从 SharedPreferences 读取的数据）
     */
    public static void showUpdateDialog(Context context, String newVersion, String releaseBody, 
            String downloadUrl, Runnable onUpdate) {
        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12));
        
        TextView contentView = new TextView(context);
        contentView.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                contentView, com.google.android.material.R.attr.colorOnSurface));
        contentView.setTextIsSelectable(true);
        contentView.setTextSize(14);
        contentView.setLineSpacing(0, 1.3f);
        MarkdownUtils.setMarkdown(contentView, releaseBody);
        scrollView.addView(contentView);
        
        new MaterialAlertDialogBuilder(context)
                .setTitle("发现新版本 v" + newVersion)
                .setView(scrollView)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    if (onUpdate != null) {
                        onUpdate.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
