package com.vkyra.code.editor;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * VSCode Light+ 风格亮色配色方案
 */
public class CppLightColorScheme extends EditorColorScheme {

    public CppLightColorScheme() {
        super();
        applyVSCodeLightTheme();
    }

    private void applyVSCodeLightTheme() {
        // ========== 编辑器背景 ==========
        setColor(WHOLE_BACKGROUND, 0xFFFFFFFF);          // 主背景（白色）
        setColor(LINE_NUMBER_BACKGROUND, 0xFFFFFFFF);    // 行号背景
        setColor(LINE_DIVIDER, 0xFFE8E8E8);              // 分隔线
        
        // ========== 行号 ==========
        setColor(LINE_NUMBER, 0xFF999999);               // 行号颜色（灰色）
        setColor(LINE_NUMBER_CURRENT, 0xFF0B216F);       // 当前行号（深蓝）
        setColor(LINE_NUMBER_PANEL, 0xFFFFFFFF);         // 行号面板背景
        setColor(LINE_NUMBER_PANEL_TEXT, 0xFF999999);    // 行号面板文字
        
        // ========== 当前行高亮 ==========
        setColor(CURRENT_LINE, 0xFFFFFBE6);              // 当前行背景（淡黄）
        
        // ========== 文本颜色 ==========
        setColor(TEXT_NORMAL, 0xFF000000);               // 普通文本（黑色）
        
        // ========== 选择和光标 ==========
        setColor(SELECTED_TEXT_BACKGROUND, 0xFFADD6FF);  // 选中背景（淡蓝）
        setColor(SELECTION_INSERT, 0xFF000000);          // 光标颜色（黑色）
        setColor(SELECTION_HANDLE, 0xFF0066CC);          // 选择手柄
        
        // ========== 语法高亮 ==========
        // 关键字 - 蓝色 (if, else, for, while, return, class, etc.)
        setColor(KEYWORD, 0xFF0000FF);
        
        // 字符串 - 红棕色 ("hello", 'c')
        setColor(LITERAL, 0xFFA31515);
        
        // 注释 - 绿色 (// comment, /* comment */)
        setColor(COMMENT, 0xFF008000);
        
        // 函数名 - 深黄色 (main, printf, etc.)
        setColor(FUNCTION_NAME, 0xFF795E26);
        
        // 预处理指令 - 紫色 (#include, #define)
        setColor(ATTRIBUTE_VALUE, 0xFFAF00DB);
        
        // 类型/类名 - 青色 (vector, string, int, etc.)
        setColor(ATTRIBUTE_NAME, 0xFF267F99);
        
        // 操作符 - 黑色 (+, -, *, /, =, etc.)
        setColor(OPERATOR, 0xFF000000);
        
        // ========== 括号匹配 ==========
        setColor(MATCHED_TEXT_BACKGROUND, 0xFFE0E0E0);   // 匹配括号背景
        setColor(UNDERLINE, 0xFF0066CC);                 // 下划线颜色
        
        // ========== 自动补全 ==========
        setColor(COMPLETION_WND_BACKGROUND, 0xFFF3F3F3); // 补全窗口背景
        setColor(COMPLETION_WND_CORNER, 0xFFCCCCCC);     // 补全窗口边角
        setColor(COMPLETION_WND_TEXT_PRIMARY, 0xFF000000);   // 补全主文本
        setColor(COMPLETION_WND_TEXT_SECONDARY, 0xFF666666); // 补全次要文本
        setColor(COMPLETION_WND_ITEM_CURRENT, 0xFFD6EBFF);   // 当前选中项
        
        // ========== 滚动条 ==========
        setColor(SCROLL_BAR_THUMB, 0xFFCCCCCC);          // 滚动条滑块
        setColor(SCROLL_BAR_THUMB_PRESSED, 0xFFAAAAAA);  // 滚动条按下
        setColor(SCROLL_BAR_TRACK, 0xFFFFFFFF);          // 滚动条轨道
        
        // ========== 代码块指示线 ==========
        setColor(BLOCK_LINE, 0xFFCCCCCC);                // 代码块线（加深，更明显）
        setColor(BLOCK_LINE_CURRENT, 0xFF999999);        // 当前代码块线（更深）
        
        // ========== 搜索高亮 ==========
        setColor(HIGHLIGHTED_DELIMITERS_FOREGROUND, 0xFF0066CC); // 高亮分隔符
        setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, 0x33FFFF00); // 高亮背景
        setColor(HIGHLIGHTED_DELIMITERS_UNDERLINE, 0xFF0066CC);  // 高亮下划线
        
        // ========== 其他 ==========
        setColor(TEXT_SELECTED, 0xFF000000);             // 选中文本颜色
        setColor(NON_PRINTABLE_CHAR, 0xFFCCCCCC);        // 不可打印字符
        setColor(SIDE_BLOCK_LINE, 0xFFE0E0E0);           // 侧边块线
        
        // ========== 文本操作窗口（选中文本后的弹出菜单）==========
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, 0xFFF3F3F3);  // 操作窗口背景（浅灰）
    }
}
