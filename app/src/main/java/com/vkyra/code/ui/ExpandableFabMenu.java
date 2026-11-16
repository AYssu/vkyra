package com.vkyra.code.ui;

import com.vkyra.code.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.cardview.widget.CardView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * M3风格的可扩展FAB菜单
 * 点击FAB后从下往上依次弹出菜单项，带动画效果
 */
public class ExpandableFabMenu extends FrameLayout {
    
    private FloatingActionButton mainFab;
    private View scrimView;
    private LinearLayout menuContainer;
    private List<MenuItem> menuItems = new ArrayList<>();
    private List<View> menuItemViews = new ArrayList<>();
    private boolean isExpanded = false;
    private OnMenuItemClickListener listener;
    
    // 颜色配置
    private static final int COLOR_FAB_BG = 0xFF49454F;
    private static final int COLOR_FAB_ICON = 0xFFE6E1E5;
    private static final int COLOR_MENU_BG = 0xFFD0BCFF;
    private static final int COLOR_MENU_TEXT = 0xFF1E1E1E;
    private static final int COLOR_SCRIM = 0x80000000;
    
    public ExpandableFabMenu(Context context) {
        super(context);
        init(context);
    }
    
    public ExpandableFabMenu(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        // 遮罩层
        scrimView = new View(context);
        scrimView.setBackgroundColor(COLOR_SCRIM);
        scrimView.setAlpha(0f);
        scrimView.setVisibility(GONE);
        scrimView.setOnClickListener(v -> collapse());
        addView(scrimView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        // 菜单容器
        menuContainer = new LinearLayout(context);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setGravity(Gravity.END);
        LayoutParams menuParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        menuParams.gravity = Gravity.BOTTOM | Gravity.END;
        menuParams.bottomMargin = dpToPx(80);
        menuParams.rightMargin = dpToPx(16);
        addView(menuContainer, menuParams);
        
        // 主FAB
        mainFab = new FloatingActionButton(context);
        mainFab.setImageResource(android.R.drawable.ic_input_add);
        mainFab.setImageTintList(android.content.res.ColorStateList.valueOf(COLOR_FAB_ICON));
        mainFab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_FAB_BG));
        mainFab.setSize(FloatingActionButton.SIZE_NORMAL);
        mainFab.setOnClickListener(v -> toggle());
        LayoutParams fabParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        fabParams.gravity = Gravity.BOTTOM | Gravity.END;
        fabParams.bottomMargin = dpToPx(16);
        fabParams.rightMargin = dpToPx(16);
        addView(mainFab, fabParams);
    }
    
    /**
     * 设置主FAB图标
     */
    public void setMainFabIcon(@DrawableRes int iconRes) {
        mainFab.setImageResource(iconRes);
    }
    
    /**
     * 添加菜单项
     */
    public void addMenuItem(@DrawableRes int iconRes, String label) {
        menuItems.add(new MenuItem(iconRes, label));
    }
    
    /**
     * 构建菜单项视图
     */
    public void buildMenu() {
        menuContainer.removeAllViews();
        menuItemViews.clear();
        
        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            View itemView = createMenuItemView(item, i);
            itemView.setAlpha(0f);
            itemView.setTranslationY(50);
            itemView.setVisibility(GONE);
            menuContainer.addView(itemView);
            menuItemViews.add(itemView);
        }
    }
    
    private View createMenuItemView(MenuItem item, int index) {
        Context context = getContext();
        
        CardView card = new CardView(context);
        card.setCardBackgroundColor(COLOR_MENU_BG);
        card.setRadius(dpToPx(28));
        card.setCardElevation(dpToPx(4));
        card.setUseCompatPadding(true);
        
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dpToPx(16), dpToPx(12), dpToPx(20), dpToPx(12));
        
        ImageView icon = new ImageView(context);
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(COLOR_MENU_TEXT);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        icon.setLayoutParams(iconParams);
        content.addView(icon);
        
        TextView label = new TextView(context);
        label.setText(item.label);
        label.setTextColor(COLOR_MENU_TEXT);
        label.setTextSize(14);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dpToPx(12);
        label.setLayoutParams(labelParams);
        content.addView(label);
        
        card.addView(content);
        
        card.setOnClickListener(v -> {
            if (listener != null) {
                listener.onMenuItemClick(index, item.label);
            }
            collapse();
        });
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dpToPx(8);
        card.setLayoutParams(cardParams);
        
        return card;
    }
    
    /**
     * 切换展开/收起状态
     */
    public void toggle() {
        if (isExpanded) {
            collapse();
        } else {
            expand();
        }
    }
    
    /**
     * 展开菜单
     */
    public void expand() {
        if (isExpanded) return;
        isExpanded = true;
        
        // 显示遮罩
        scrimView.setVisibility(VISIBLE);
        scrimView.animate().alpha(1f).setDuration(200).start();
        
        // 旋转FAB图标
        mainFab.animate().rotation(45f).setDuration(200).start();
        
        // 依次显示菜单项（从下往上）
        for (int i = menuItemViews.size() - 1; i >= 0; i--) {
            View itemView = menuItemViews.get(i);
            itemView.setVisibility(VISIBLE);
            
            int delay = (menuItemViews.size() - 1 - i) * 50;
            itemView.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(delay)
                    .setDuration(250)
                    .setInterpolator(new OvershootInterpolator(0.8f))
                    .start();
        }
    }
    
    /**
     * 收起菜单
     */
    public void collapse() {
        if (!isExpanded) return;
        isExpanded = false;
        
        // 隐藏遮罩
        scrimView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            scrimView.setVisibility(GONE);
        }).start();
        
        // 旋转FAB图标回原位
        mainFab.animate().rotation(0f).setDuration(200).start();
        
        // 依次隐藏菜单项（从上往下）
        for (int i = 0; i < menuItemViews.size(); i++) {
            View itemView = menuItemViews.get(i);
            int delay = i * 50;
            int index = i;
            itemView.animate()
                    .alpha(0f)
                    .translationY(50)
                    .setStartDelay(delay)
                    .setDuration(200)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        itemView.setVisibility(GONE);
                    })
                    .start();
        }
    }
    
    public boolean isExpanded() {
        return isExpanded;
    }
    
    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.listener = listener;
    }
    
    private int dpToPx(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density);
    }
    
    private static class MenuItem {
        int iconRes;
        String label;
        
        MenuItem(int iconRes, String label) {
            this.iconRes = iconRes;
            this.label = label;
        }
    }
    
    public interface OnMenuItemClickListener {
        void onMenuItemClick(int index, String label);
    }
}
