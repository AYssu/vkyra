package com.vkyra.code.editor;

import com.vkyra.code.settings.AppPreferences;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 快捷键配置管理
 */
public class SymbolKeyConfig {
    
    // 触发事件类型
    public static final String ACTION_INSERT = "insert";       // 插入字符
    public static final String ACTION_UNDO = "undo";           // 撤销
    public static final String ACTION_REDO = "redo";           // 重做
    public static final String ACTION_COMMENT = "comment";     // 注释选中
    public static final String ACTION_SHELL = "shell";         // 执行sh命令
    
    private static final String PREFS_NAME = "akode_settings";
    private static final String KEY_SYMBOL_CONFIGS = "symbol_key_configs";
    
    /**
     * 快捷键配置项
     */
    public static class KeyConfig {
        public String displayName;  // 显示名称
        public String action;       // 触发事件类型
        public String param;        // 事件参数（插入字符或sh命令）
        
        public KeyConfig(String displayName, String action, String param) {
            this.displayName = displayName;
            this.action = action;
            this.param = param;
        }
        
        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("displayName", displayName);
                obj.put("action", action);
                obj.put("param", param != null ? param : "");
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        
        public static KeyConfig fromJson(JSONObject obj) {
            try {
                return new KeyConfig(
                    obj.optString("displayName", ""),
                    obj.optString("action", ACTION_INSERT),
                    obj.optString("param", "")
                );
            } catch (Exception e) {
                return null;
            }
        }
        
        /**
         * 获取事件类型的显示名称
         */
        public String getActionDisplayName() {
            switch (action) {
                case ACTION_INSERT: return "插入字符";
                case ACTION_UNDO: return "撤销";
                case ACTION_REDO: return "重做";
                case ACTION_COMMENT: return "注释选中";
                case ACTION_SHELL: return "执行命令";
                default: return "插入字符";
            }
        }
    }
    
    /**
     * 获取所有事件类型选项
     */
    public static String[] getActionTypes() {
        return new String[]{ACTION_INSERT, ACTION_UNDO, ACTION_REDO, ACTION_COMMENT, ACTION_SHELL};
    }
    
    /**
     * 获取事件类型显示名称
     */
    public static String[] getActionDisplayNames() {
        return new String[]{"插入字符", "撤销", "重做", "注释选中", "执行命令"};
    }
    
    /**
     * 获取默认快捷键配置
     */
    public static List<KeyConfig> getDefaultConfigs() {
        List<KeyConfig> configs = new ArrayList<>();
        configs.add(new KeyConfig("TAB", ACTION_INSERT, "\t"));
        configs.add(new KeyConfig("↩", ACTION_UNDO, ""));
        configs.add(new KeyConfig("↪", ACTION_REDO, ""));
        configs.add(new KeyConfig("//", ACTION_COMMENT, ""));
        configs.add(new KeyConfig("{", ACTION_INSERT, "{"));
        configs.add(new KeyConfig("}", ACTION_INSERT, "}"));
        configs.add(new KeyConfig("(", ACTION_INSERT, "("));
        configs.add(new KeyConfig(")", ACTION_INSERT, ")"));
        configs.add(new KeyConfig("[", ACTION_INSERT, "["));
        configs.add(new KeyConfig("]", ACTION_INSERT, "]"));
        configs.add(new KeyConfig("<", ACTION_INSERT, "<"));
        configs.add(new KeyConfig(">", ACTION_INSERT, ">"));
        configs.add(new KeyConfig(";", ACTION_INSERT, ";"));
        configs.add(new KeyConfig(":", ACTION_INSERT, ":"));
        configs.add(new KeyConfig("\"", ACTION_INSERT, "\""));
        configs.add(new KeyConfig("'", ACTION_INSERT, "'"));
        configs.add(new KeyConfig("=", ACTION_INSERT, "="));
        configs.add(new KeyConfig("+", ACTION_INSERT, "+"));
        configs.add(new KeyConfig("-", ACTION_INSERT, "-"));
        configs.add(new KeyConfig("*", ACTION_INSERT, "*"));
        configs.add(new KeyConfig("/", ACTION_INSERT, "/"));
        configs.add(new KeyConfig("&", ACTION_INSERT, "&"));
        configs.add(new KeyConfig("|", ACTION_INSERT, "|"));
        configs.add(new KeyConfig("!", ACTION_INSERT, "!"));
        configs.add(new KeyConfig("?", ACTION_INSERT, "?"));
        configs.add(new KeyConfig("#", ACTION_INSERT, "#"));
        configs.add(new KeyConfig("_", ACTION_INSERT, "_"));
        configs.add(new KeyConfig("\\", ACTION_INSERT, "\\"));
        configs.add(new KeyConfig("~", ACTION_INSERT, "~"));
        configs.add(new KeyConfig("^", ACTION_INSERT, "^"));
        return configs;
    }

    
    /**
     * 保存快捷键配置
     */
    public static void saveConfigs(Context context, List<KeyConfig> configs) {
        try {
            JSONArray array = new JSONArray();
            for (KeyConfig config : configs) {
                array.put(config.toJson());
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_SYMBOL_CONFIGS, array.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 加载快捷键配置
     */
    public static List<KeyConfig> loadConfigs(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_SYMBOL_CONFIGS, null);
            
            if (json == null || json.isEmpty()) {
                // 尝试从旧配置迁移
                return migrateFromOldConfig(context);
            }
            
            JSONArray array = new JSONArray(json);
            List<KeyConfig> configs = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                KeyConfig config = KeyConfig.fromJson(array.getJSONObject(i));
                if (config != null) {
                    configs.add(config);
                }
            }
            return configs.isEmpty() ? getDefaultConfigs() : configs;
        } catch (Exception e) {
            e.printStackTrace();
            return getDefaultConfigs();
        }
    }
    
    /**
     * 从旧配置迁移
     */
    private static List<KeyConfig> migrateFromOldConfig(Context context) {
        try {
            String oldKeys = AppPreferences.getSymbolKeys(context);
            if (oldKeys == null || oldKeys.isEmpty()) {
                return getDefaultConfigs();
            }
            
            String[] keys = oldKeys.split(",");
            List<KeyConfig> configs = new ArrayList<>();
            
            for (String key : keys) {
                key = key.trim();
                if (key.isEmpty()) continue;
                
                if (key.equals("TAB")) {
                    configs.add(new KeyConfig("TAB", ACTION_INSERT, "\t"));
                } else if (key.equals("↩")) {
                    configs.add(new KeyConfig("↩", ACTION_UNDO, ""));
                } else if (key.equals("↪")) {
                    configs.add(new KeyConfig("↪", ACTION_REDO, ""));
                } else if (key.equals("//")) {
                    configs.add(new KeyConfig("//", ACTION_COMMENT, ""));
                } else {
                    configs.add(new KeyConfig(key, ACTION_INSERT, key));
                }
            }
            
            // 保存迁移后的配置
            if (!configs.isEmpty()) {
                saveConfigs(context, configs);
            }
            
            return configs.isEmpty() ? getDefaultConfigs() : configs;
        } catch (Exception e) {
            return getDefaultConfigs();
        }
    }
    
    /**
     * 重置为默认配置
     */
    public static void resetToDefault(Context context) {
        saveConfigs(context, getDefaultConfigs());
    }
}
