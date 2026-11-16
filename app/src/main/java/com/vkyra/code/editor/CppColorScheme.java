package com.vkyra.code.editor;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * VSCode Dark+ 风格配色方案
 */
public class CppColorScheme extends EditorColorScheme {

    public CppColorScheme() {
        super();
        applyVSCodeDarkTheme();
    }

    private void applyVSCodeDarkTheme() {
        // ========== 编辑器背景 ==========
        setColor(WHOLE_BACKGROUND, 0xFF1E1E1E);          // 主背景
        setColor(LINE_NUMBER_BACKGROUND, 0xFF1E1E1E);    // 行号背景
        setColor(LINE_DIVIDER, 0xFF1E1E1E);              // 分隔线
        
        // ========== 行号 ==========
        setColor(LINE_NUMBER, 0xFF6E7681);               // 行号颜色（稍暗）
        setColor(LINE_NUMBER_CURRENT, 0xFFC6C6C6);       // 当前行号（高亮）
        setColor(LINE_NUMBER_PANEL, 0xFF1E1E1E);         // 行号面板背景
        setColor(LINE_NUMBER_PANEL_TEXT, 0xFF6E7681);    // 行号面板文字
        
        // ========== 当前行高亮 ==========
        setColor(CURRENT_LINE, 0xFF2A2D2E);              // 当前行背景（微亮）
        
        // ========== 文本颜色 ==========
        setColor(TEXT_NORMAL, 0xFFD4D4D4);               // 普通文本（浅灰）
        
        // ========== 选择和光标 ==========
        setColor(SELECTED_TEXT_BACKGROUND, 0xFF264F78);  // 选中背景（蓝色）
        setColor(SELECTION_INSERT, 0xFFAEAFAD);          // 光标颜色
        setColor(SELECTION_HANDLE, 0xFF569CD6);          // 选择手柄
        
        // ========== 语法高亮 ==========
        // 关键字 - 蓝色 (if, else, for, while, return, class, etc.)
        setColor(KEYWORD, 0xFF569CD6);
        
        // 字符串 - 橙色 ("hello", 'c')
        setColor(LITERAL, 0xFFCE9178);
        
        // 注释 - 绿色 (// comment, /* comment */)
        setColor(COMMENT, 0xFF6A9955);
        
        // 函数名 - 淡黄色 (main, printf, etc.)
        setColor(FUNCTION_NAME, 0xFFDCDCAA);
        
        // 预处理指令 - 紫色 (#include, #define)
        setColor(ATTRIBUTE_VALUE, 0xFFC586C0);
        
        // 类型/类名 - 青色 (vector, string, int, etc.)
        setColor(ATTRIBUTE_NAME, 0xFF4EC9B0);
        
        // 操作符 - 浅灰 (+, -, *, /, =, etc.)
        setColor(OPERATOR, 0xFFD4D4D4);
        
        // 数字 - 浅绿色
        setColor(LITERAL, 0xFFB5CEA8);
        
        // ========== 括号匹配 ==========
        setColor(MATCHED_TEXT_BACKGROUND, 0xFF4D4D4D);   // 匹配括号背景（更明显）
        setColor(UNDERLINE, 0xFFFFD700);                 // 下划线颜色（金色）
        
        // ========== 自动补全 ==========
        setColor(COMPLETION_WND_BACKGROUND, 0xFF252526); // 补全窗口背景
        setColor(COMPLETION_WND_CORNER, 0xFF454545);     // 补全窗口边角
        setColor(COMPLETION_WND_TEXT_PRIMARY, 0xFFD4D4D4);   // 补全主文本
        setColor(COMPLETION_WND_TEXT_SECONDARY, 0xFF808080); // 补全次要文本
        setColor(COMPLETION_WND_ITEM_CURRENT, 0xFF094771);   // 当前选中项
        
        // ========== 滚动条 ==========
        setColor(SCROLL_BAR_THUMB, 0xFF424242);          // 滚动条滑块
        setColor(SCROLL_BAR_THUMB_PRESSED, 0xFF555555);  // 滚动条按下
        setColor(SCROLL_BAR_TRACK, 0xFF1E1E1E);          // 滚动条轨道
        
        // ========== 代码块指示线 ==========
        setColor(BLOCK_LINE, 0xFF404040);                // 代码块线
        setColor(BLOCK_LINE_CURRENT, 0xFF606060);        // 当前代码块线
        
        // ========== 搜索高亮 ==========
        setColor(HIGHLIGHTED_DELIMITERS_FOREGROUND, 0xFFFFD700); // 高亮分隔符
        setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, 0x33FFFF00); // 高亮背景
        setColor(HIGHLIGHTED_DELIMITERS_UNDERLINE, 0xFFFFD700);  // 高亮下划线
        
        // ========== 其他 ==========
        setColor(TEXT_SELECTED, 0xFFFFFFFF);             // 选中文本颜色
        setColor(NON_PRINTABLE_CHAR, 0xFF505050);        // 不可打印字符
        setColor(SIDE_BLOCK_LINE, 0xFF404040);           // 侧边块线
        
        // ========== 文本操作窗口（选中文本后的弹出菜单）==========
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, 0xFF252526);  // 操作窗口背景（深灰）
    }
}
