package com.vkyra.code.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件信息实体类
 */
public class PluginInfo {
    private String pluginCode;
    private String pluginName;
    private String pluginDescribe;
    private String pluginVersion;
    private String author;
    private List<String> supportABI;
    private String github;
    private String path;
    private String currentABI;  // 当前选择的ABI
    private boolean enabled = true;  // 是否启用
    private String loadError;   // hook 加载错误信息（为空表示正常）

    public PluginInfo() {
        supportABI = new ArrayList<>();
    }

    public static PluginInfo fromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            PluginInfo info = new PluginInfo();
            info.pluginCode = obj.optString("plugin_code", "");
            if (!PluginInstallTransaction.isValidPluginId(info.pluginCode)) return null;
            info.pluginName = obj.optString("plugin_name", "未知插件");
            info.pluginDescribe = obj.optString("plugin_describe", "");
            info.pluginVersion = obj.optString("plugin_version", "1.0.0");
            info.author = obj.optString("author", "");
            info.github = obj.optString("github", "");
            
            JSONArray abiArray = obj.optJSONArray("support_ABI");
            if (abiArray != null) {
                for (int i = 0; i < abiArray.length(); i++) {
                    String abi = abiArray.getString(i);
                    if (!abi.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) return null;
                    if (!info.supportABI.contains(abi)) info.supportABI.add(abi);
                }
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    public String getPluginCode() {
        return pluginCode;
    }

    public void setPluginCode(String pluginCode) {
        this.pluginCode = pluginCode;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getPluginDescribe() {
        return pluginDescribe;
    }

    public void setPluginDescribe(String pluginDescribe) {
        this.pluginDescribe = pluginDescribe;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public void setPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion;
    }

    public String getAuthor() {
        return (author == null || author.isEmpty()) ? "匿名" : author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public List<String> getSupportABI() {
        return supportABI;
    }

    public void setSupportABI(List<String> supportABI) {
        this.supportABI = supportABI;
    }

    public String getGithub() {
        return github;
    }

    public void setGithub(String github) {
        this.github = github;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean hasGithub() {
        return github != null && !github.isEmpty();
    }

    public String getCurrentABI() {
        return currentABI;
    }

    public void setCurrentABI(String currentABI) {
        this.currentABI = currentABI;
    }

    public boolean hasMultipleABI() {
        return supportABI != null && supportABI.size() > 1;
    }

    public String getDefaultABI() {
        if (supportABI != null && !supportABI.isEmpty()) {
            return supportABI.get(0);
        }
        return "arm64-v8a";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLoadError() {
        return loadError;
    }

    public void setLoadError(String loadError) {
        this.loadError = loadError;
    }

    public boolean hasLoadError() {
        return loadError != null && !loadError.trim().isEmpty();
    }
}
