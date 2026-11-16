package com.vkyra.code.plugin;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件 Hook 管理器
 * 负责读取和执行插件的 .hook 文件
 */
public class PluginHookManager {
    private static final String TAG = "PluginHookManager";

    // Hook 事件类型
    public static final String EVENT_COMPILE_BEFORE = "on_compile_before";
    public static final String EVENT_COMPILE_SUCCESS = "on_compile_success";
    public static final String EVENT_COMPILE_ERROR = "on_compile_error";
    public static final String EVENT_RUN_BEFORE = "on_run_before";
    public static final String EVENT_APP_INIT = "on_app_init";
    public static final String EVENT_EDITOR_COMPLETION_REQUEST = "on_editor_completion_request";

    private final Context context;
    private final File pluginBaseDir;
    
    // 缓存已加载的 hook 配置
    private List<PluginHookConfig> hookConfigs = new ArrayList<>();
    // 缓存插件加载错误（key: 插件目录名）
    private final Map<String, String> hookLoadErrors = new HashMap<>();

    public PluginHookManager(Context context) {
        this.context = context;
        this.pluginBaseDir = new File(context.getFilesDir(), "plugin");
        loadAllHooks();
    }

    /**
     * 加载所有已启用插件的 hook 配置
     */
    public void loadAllHooks() {
        hookConfigs.clear();
        hookLoadErrors.clear();
        Log.d(TAG, "loadAllHooks: scanning plugin dir = " + pluginBaseDir.getAbsolutePath());
        if (!pluginBaseDir.exists()) {
            Log.w(TAG, "loadAllHooks: plugin base dir not found");
            return;
        }

        File[] plugins = pluginBaseDir.listFiles();
        if (plugins == null) {
            Log.w(TAG, "loadAllHooks: plugin dir listFiles returned null");
            return;
        }

        int scanned = 0;
        int enabled = 0;

        for (File pluginDir : plugins) {
            if (!pluginDir.isDirectory() || pluginDir.getName().startsWith(".")
                    || !PluginInstallTransaction.isValidPluginId(pluginDir.getName())
                    || java.nio.file.Files.isSymbolicLink(pluginDir.toPath())) continue;
            scanned++;
            
            // 检查是否启用
            File enabledFile = new File(pluginDir, ".enabled");
            if (!enabledFile.exists()) {
                Log.d(TAG, "loadAllHooks: skip disabled plugin = " + pluginDir.getName());
                continue;
            }
            enabled++;
            
            // 读取 .hook 文件
            File hookFile = new File(pluginDir, ".hook");
            if (!hookFile.exists()) {
                Log.w(TAG, "loadAllHooks: enabled plugin has no .hook = " + pluginDir.getName());
                continue;
            }
            
            try {
                String content = readFileContent(hookFile);
                if (content != null) {
                    JSONObject hookJson = new JSONObject(content);
                    PluginHookConfig config = new PluginHookConfig();
                    config.pluginDir = pluginDir;
                    config.hookJson = hookJson;
                    hookConfigs.add(config);
                    int completionCount = 0;
                    JSONArray completion = hookJson.optJSONArray("completion");
                    if (completion != null) {
                        completionCount = completion.length();
                    }
                    Log.i(TAG, "loadAllHooks: loaded plugin = " + pluginDir.getName()
                        + ", completion=" + completionCount);
                } else {
                    Log.w(TAG, "loadAllHooks: failed to read .hook content = " + hookFile.getAbsolutePath());
                    hookLoadErrors.put(pluginDir.getName(), "读取 .hook 文件失败，请检查文件权限或编码。");
                }
            } catch (Exception e) {
                Log.e(TAG, "loadAllHooks: parse hook failed for plugin = " + pluginDir.getName(), e);
                hookLoadErrors.put(pluginDir.getName(), formatLoadErrorMessage(e));
            }
        }

        Log.i(TAG, "loadAllHooks: done. scanned=" + scanned
            + ", enabled=" + enabled + ", loaded=" + hookConfigs.size());
    }

    public String getHookLoadError(String pluginDirName) {
        if (pluginDirName == null) return null;
        return hookLoadErrors.get(pluginDirName);
    }

    private String formatLoadErrorMessage(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 执行指定事件的所有 hook
     */
    public void executeEvent(Activity activity, String eventName, Runnable onComplete) {
        executeEvent(activity, eventName, new HashMap<>(), onComplete);
    }

    /**
     * 执行指定事件的所有 hook（带额外变量）
     */
    public void executeEvent(Activity activity, String eventName, Map<String, String> extraVars, Runnable onComplete) {
        List<HookEventItem> allEvents = new ArrayList<>();
        
        for (PluginHookConfig config : hookConfigs) {
            JSONObject events = config.hookJson.optJSONObject("events");
            if (events == null) continue;
            
            JSONArray eventNodes = events.optJSONArray(eventName);
            if (eventNodes != null && eventNodes.length() > 0) {
                HookEventItem item = new HookEventItem();
                item.pluginDir = config.pluginDir;
                item.nodes = eventNodes;
                allEvents.add(item);
            }
        }
        
        if (allEvents.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        
        // 依次执行每个插件的 hook
        executeEventList(activity, allEvents, 0, extraVars, onComplete);
    }


    private void executeEventList(Activity activity, List<HookEventItem> events, int index, 
                                   Map<String, String> extraVars, Runnable onComplete) {
        if (index >= events.size()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        
        HookEventItem item = events.get(index);
        executeNodes(activity, item.pluginDir, item.nodes, extraVars, () -> {
            executeEventList(activity, events, index + 1, extraVars, onComplete);
        });
    }

    /**
     * 执行节点数组
     */
    private void executeNodes(Activity activity, File pluginDir, JSONArray nodes, 
                              Map<String, String> extraVars, Runnable onComplete) {
        PluginInstallScript script = new PluginInstallScript(activity, pluginDir);
        for (Map.Entry<String, String> entry : extraVars.entrySet()) {
            script.addVariable(entry.getKey(), entry.getValue());
        }
        
        // 调用 PluginInstallScript 的执行方法
        script.executeNodes(nodes, onComplete);
    }

    /**
     * 获取所有工具栏按钮配置
     */
    public List<ToolbarButtonConfig> getToolbarButtons() {
        List<ToolbarButtonConfig> buttons = new ArrayList<>();
        
        for (PluginHookConfig config : hookConfigs) {
            JSONArray toolbar = config.hookJson.optJSONArray("toolbar");
            if (toolbar == null) continue;
            
            for (int i = 0; i < toolbar.length(); i++) {
                try {
                    JSONObject btn = toolbar.getJSONObject(i);
                    ToolbarButtonConfig btnConfig = new ToolbarButtonConfig();
                    btnConfig.pluginDir = config.pluginDir;
                    btnConfig.icon = btn.optString("icon", "");
                    btnConfig.label = btn.optString("label", "");
                    btnConfig.position = btn.optString("position", "right:0");
                    btnConfig.onClick = btn.optJSONArray("onClick");
                    btnConfig.onLongClick = btn.optJSONArray("onLongClick");
                    buttons.add(btnConfig);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return buttons;
    }


    /**
     * 获取所有菜单项配置
     */
    public List<MenuItemConfig> getMenuItems() {
        List<MenuItemConfig> items = new ArrayList<>();
        
        for (PluginHookConfig config : hookConfigs) {
            JSONArray menu = config.hookJson.optJSONArray("menu");
            if (menu == null) continue;
            
            for (int i = 0; i < menu.length(); i++) {
                try {
                    JSONObject item = menu.getJSONObject(i);
                    MenuItemConfig menuConfig = new MenuItemConfig();
                    menuConfig.pluginDir = config.pluginDir;
                    menuConfig.title = item.optString("title", "");
                    menuConfig.position = item.optString("position", "end");
                    menuConfig.onClick = item.optJSONArray("onClick");
                    items.add(menuConfig);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return items;
    }

    private String readFileContent(File file) {
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return content.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // 配置类
    private static class PluginHookConfig {
        File pluginDir;
        JSONObject hookJson;
    }

    private static class HookEventItem {
        File pluginDir;
        JSONArray nodes;
    }

    public static class ToolbarButtonConfig {
        public File pluginDir;
        public String icon;
        public String label;
        public String position;  // "left:N" 或 "right:N"
        public JSONArray onClick;
        public JSONArray onLongClick;
    }

    public static class MenuItemConfig {
        public File pluginDir;
        public String title;
        public String position;  // "start", "end", "after:菜单名"
        public JSONArray onClick;
    }
    
    /**
     * 运行菜单项配置（带图标）
     */
    public static class RunMenuItemConfig {
        public File pluginDir;
        public String title;
        public String iconSvgBase64;  // Base64 编码的 SVG 图标
        public String position;       // "start" 或 "end"，默认 "end"
        public JSONArray onClick;
        
        /**
         * 获取解码后的 SVG 字符串
         */
        public String getDecodedSvg() {
            if (iconSvgBase64 == null || iconSvgBase64.isEmpty()) return null;
            try {
                byte[] decoded = android.util.Base64.decode(iconSvgBase64, android.util.Base64.DEFAULT);
                return new String(decoded, "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
        
        public boolean isStartPosition() {
            return "start".equals(position);
        }
    }

    /**
     * 编辑器补全模板配置
     */
    public static class CompletionConfig {
        public File pluginDir;
        public String language;   // cpp / c / java ...
        public String trigger;    // 用户输入触发词
        public String label;      // 补全显示名称
        public String desc;       // 描述
        public String insert;     // 实际插入文本
    }
    
    /**
     * 获取所有运行菜单项配置
     */
    public List<RunMenuItemConfig> getRunMenuItems() {
        List<RunMenuItemConfig> items = new ArrayList<>();
        
        for (PluginHookConfig config : hookConfigs) {
            JSONArray runMenu = config.hookJson.optJSONArray("run_menu");
            if (runMenu == null) continue;
            
            for (int i = 0; i < runMenu.length(); i++) {
                try {
                    JSONObject item = runMenu.getJSONObject(i);
                    RunMenuItemConfig menuConfig = new RunMenuItemConfig();
                    menuConfig.pluginDir = config.pluginDir;
                    menuConfig.title = item.optString("title", "");
                    menuConfig.iconSvgBase64 = item.optString("icon", "");
                    menuConfig.position = item.optString("position", "end");
                    menuConfig.onClick = item.optJSONArray("onClick");
                    items.add(menuConfig);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return items;
    }

    /**
     * 获取编辑器补全模板配置（来自插件 .hook 的 completion 字段）
     */
    public List<CompletionConfig> getCompletionItems(String language, String prefix) {
        List<CompletionConfig> items = new ArrayList<>();
        String lowerLang = language == null ? "" : language.trim().toLowerCase();
        String lowerPrefix = prefix == null ? "" : prefix.trim().toLowerCase();
        int checkedTemplates = 0;

        for (PluginHookConfig config : hookConfigs) {
            JSONArray completion = config.hookJson.optJSONArray("completion");
            if (completion == null) continue;

            for (int i = 0; i < completion.length(); i++) {
                try {
                    checkedTemplates++;
                    JSONObject item = completion.getJSONObject(i);
                    CompletionConfig c = new CompletionConfig();
                    c.pluginDir = config.pluginDir;
                    c.language = item.optString("language", "cpp").trim().toLowerCase();
                    c.trigger = item.optString("trigger", "");
                    c.label = item.optString("label", c.trigger);
                    c.desc = item.optString("desc", "插件模板");
                    c.insert = item.optString("insert", "");

                    if (c.insert.isEmpty() || c.trigger.isEmpty()) continue;
                    if (!lowerLang.equals(c.language)) continue;
                    if (!lowerPrefix.isEmpty() && !c.trigger.toLowerCase().startsWith(lowerPrefix)) continue;
                    items.add(c);
                } catch (Exception e) {
                    Log.e(TAG, "getCompletionItems: parse completion failed in plugin = "
                        + config.pluginDir.getName(), e);
                }
            }
        }

        Log.d(TAG, "getCompletionItems: lang=" + lowerLang
            + ", prefix='" + lowerPrefix + "', matched=" + items.size()
            + ", checked=" + checkedTemplates + ", loadedPlugins=" + hookConfigs.size());
        return items;
    }
    
    /**
     * 获取 position=start 的运行菜单项
     */
    public List<RunMenuItemConfig> getRunMenuItemsStart() {
        List<RunMenuItemConfig> result = new ArrayList<>();
        for (RunMenuItemConfig item : getRunMenuItems()) {
            if (item.isStartPosition()) {
                result.add(item);
            }
        }
        return result;
    }
    
    /**
     * 获取 position=end 的运行菜单项
     */
    public List<RunMenuItemConfig> getRunMenuItemsEnd() {
        List<RunMenuItemConfig> result = new ArrayList<>();
        for (RunMenuItemConfig item : getRunMenuItems()) {
            if (!item.isStartPosition()) {
                result.add(item);
            }
        }
        return result;
    }
    
    /**
     * 执行运行菜单项点击事件
     */
    public void executeRunMenuClick(Activity activity, RunMenuItemConfig config, Map<String, String> extraVars, Runnable onComplete) {
        if (config.onClick == null || config.onClick.length() == 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        executeNodes(activity, config.pluginDir, config.onClick, extraVars != null ? extraVars : new HashMap<>(), onComplete);
    }
    
    /**
     * 执行工具栏按钮点击事件
     */
    public void executeToolbarClick(Activity activity, ToolbarButtonConfig config, Map<String, String> extraVars, Runnable onComplete) {
        if (config.onClick == null || config.onClick.length() == 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        executeNodes(activity, config.pluginDir, config.onClick, extraVars != null ? extraVars : new HashMap<>(), onComplete);
    }
    
    /**
     * 执行工具栏按钮长按事件
     */
    public void executeToolbarLongClick(Activity activity, ToolbarButtonConfig config, Map<String, String> extraVars, Runnable onComplete) {
        if (config.onLongClick == null || config.onLongClick.length() == 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        executeNodes(activity, config.pluginDir, config.onLongClick, extraVars != null ? extraVars : new HashMap<>(), onComplete);
    }
    
    /**
     * 执行菜单项点击事件
     */
    public void executeMenuClick(Activity activity, MenuItemConfig config, Map<String, String> extraVars, Runnable onComplete) {
        if (config.onClick == null || config.onClick.length() == 0) {
            if (onComplete != null) onComplete.run();
            return;
        }
        executeNodes(activity, config.pluginDir, config.onClick, extraVars != null ? extraVars : new HashMap<>(), onComplete);
    }
}
