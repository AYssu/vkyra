package com.vkyra.code.project;

import com.vkyra.code.Log;
import com.vkyra.code.settings.AppPreferences;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecentProjectManager {
    
    public static final String AKODE_DIR = ".akode";
    public static final String CONFIG_FILE = "project.json";
    public static final String THIRD_CONFIG_FILE = "third.json";
    public static final String GLOBAL_PROJECTS_FILE = "global_projects.json";
    
    public static class ProjectInfo {
        public String name;
        public String path;
        public long createdTime;
        public long lastOpenTime;
        public String template;
        public String currentFile;           // 当前打开的文件（相对路径）
        public List<String> openedFiles;     // 历史打开文件列表（相对路径）
        public JSONObject thirdConfig;       // third.json配置
        
        public ProjectInfo(String name, String path) {
            this.name = name;
            this.path = path;
            this.createdTime = System.currentTimeMillis();
            this.lastOpenTime = System.currentTimeMillis();
            this.openedFiles = new ArrayList<>();
        }
        
        /**
         * 获取项目名称的缩写（用于图标显示）
         */
        public String getInitials() {
            if (name == null || name.isEmpty()) return "?";
            
            String[] parts = name.split("[\\s_\\-]+");
            StringBuilder initials = new StringBuilder();
            
            for (String part : parts) {
                if (!part.isEmpty() && initials.length() < 2) {
                    initials.append(Character.toUpperCase(part.charAt(0)));
                }
            }
            
            // 如果只有一个字母，尝试从驼峰命名中提取
            if (initials.length() == 1 && parts.length == 1) {
                String word = parts[0];
                for (int i = 1; i < word.length() && initials.length() < 2; i++) {
                    if (Character.isUpperCase(word.charAt(i))) {
                        initials.append(word.charAt(i));
                    }
                }
            }
            
            return initials.toString();
        }
        
        /**
         * 根据项目名称生成颜色
         */
        public int getColor() {
            int[] colors = {
                0xFF6366F1, // 紫色
                0xFF22C55E, // 绿色
                0xFFF59E0B, // 橙色
                0xFFEF4444, // 红色
                0xFF3B82F6, // 蓝色
                0xFFEC4899, // 粉色
                0xFF14B8A6, // 青色
                0xFF8B5CF6, // 紫罗兰
            };
            int hash = name.hashCode();
            return colors[Math.abs(hash) % colors.length];
        }
    }
    
    /**
     * 检查目录是否是 Vkyra 项目
     */
    public static boolean isAkodeProject(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File akodeDir = new File(dir, AKODE_DIR);
        File configFile = new File(akodeDir, CONFIG_FILE);
        return configFile.exists();
    }
    
    /**
     * 初始化项目（创建 .akode 目录和配置文件）
     */
    public static void initProject(String projectPath, String projectName, String template, String defaultFile) {
        try {
            File projectDir = new File(projectPath);
            File akodeDir = new File(projectDir, AKODE_DIR);
            if (!akodeDir.exists()) {
                akodeDir.mkdirs();
            }
            
            File configFile = new File(akodeDir, CONFIG_FILE);
            JSONObject config = new JSONObject();
            config.put("name", projectName);
            config.put("createdTime", System.currentTimeMillis());
            config.put("lastOpenTime", System.currentTimeMillis());
            config.put("template", template);
            
            // 设置默认打开文件
            if (defaultFile != null && !defaultFile.isEmpty()) {
                config.put("currentFile", defaultFile);
                JSONArray openedFiles = new JSONArray();
                openedFiles.put(defaultFile);
                config.put("openedFiles", openedFiles);
            }
            
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(config.toString(2));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 更新项目打开时间
     */
    public static void updateLastOpenTime(String projectPath) {
        try {
            File configFile = new File(projectPath, AKODE_DIR + "/" + CONFIG_FILE);
            if (!configFile.exists()) return;
            
            JSONObject config = readConfigJson(configFile);
            if (config == null) return;
            
            config.put("lastOpenTime", System.currentTimeMillis());
            writeConfigJson(configFile, config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 更新当前打开的文件
     */
    public static void updateCurrentFile(String projectPath, String filePath) {
        try {
            File projectDir = new File(projectPath);
            File configFile = new File(projectDir, AKODE_DIR + "/" + CONFIG_FILE);
            if (!configFile.exists()) return;
            
            // 转换为相对路径
            String relativePath = getRelativePath(projectPath, filePath);
            if (relativePath == null) return;
            
            JSONObject config = readConfigJson(configFile);
            if (config == null) return;
            
            config.put("currentFile", relativePath);
            
            // 添加到历史打开文件列表
            JSONArray openedFiles = config.optJSONArray("openedFiles");
            if (openedFiles == null) {
                openedFiles = new JSONArray();
            }
            
            // 移除已存在的，添加到开头
            List<String> fileList = new ArrayList<>();
            fileList.add(relativePath);
            for (int i = 0; i < openedFiles.length(); i++) {
                String f = openedFiles.getString(i);
                if (!f.equals(relativePath)) {
                    fileList.add(f);
                }
            }
            
            // 限制历史记录数量
            while (fileList.size() > 20) {
                fileList.remove(fileList.size() - 1);
            }
            
            config.put("openedFiles", new JSONArray(fileList));
            writeConfigJson(configFile, config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 获取相对路径
     */
    private static String getRelativePath(String projectPath, String filePath) {
        if (filePath == null || projectPath == null) return null;
        
        File projectDir = new File(projectPath);
        File file = new File(filePath);
        
        String projectAbsPath = projectDir.getAbsolutePath();
        String fileAbsPath = file.getAbsolutePath();
        
        if (fileAbsPath.startsWith(projectAbsPath)) {
            String relative = fileAbsPath.substring(projectAbsPath.length());
            if (relative.startsWith("/") || relative.startsWith("\\")) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return null;
    }
    
    /**
     * 读取项目配置
     */
    public static ProjectInfo readProjectConfig(File projectDir) {
        try {
            File configFile = new File(projectDir, AKODE_DIR + "/" + CONFIG_FILE);
            if (!configFile.exists()) return null;
            
            JSONObject config = readConfigJson(configFile);
            if (config == null) return null;
            
            ProjectInfo info = new ProjectInfo(
                config.optString("name", projectDir.getName()),
                projectDir.getAbsolutePath()
            );
            info.createdTime = config.optLong("createdTime", 0);
            info.lastOpenTime = config.optLong("lastOpenTime", 0);
            info.template = config.optString("template", "");
            info.currentFile = config.optString("currentFile", null);
            
            // 读取历史打开文件
            JSONArray openedFiles = config.optJSONArray("openedFiles");
            if (openedFiles != null) {
                for (int i = 0; i < openedFiles.length(); i++) {
                    info.openedFiles.add(openedFiles.getString(i));
                }
            }
            
            // 读取third.json配置
            info.thirdConfig = readThirdConfig(projectDir);
            
            return info;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 扫描项目根目录，获取所有 Vkyra 项目（按最后打开时间排序）
     */
    public static List<ProjectInfo> getRecentProjects(Context context) {
        List<ProjectInfo> projects = new ArrayList<>();
        String projectRoot = AppPreferences.getProjectRoot(context);
        File rootDir = new File(projectRoot);
        
        // 扫描项目根目录下的项目
        if (rootDir.exists() && rootDir.isDirectory()) {
            File[] dirs = rootDir.listFiles(new java.io.FileFilter() {
                @Override
                public boolean accept(File file) {
                    return file.isDirectory();
                }
            });
            if (dirs != null) {
                for (File dir : dirs) {
                    if (isAkodeProject(dir)) {
                        ProjectInfo info = readProjectConfig(dir);
                        if (info != null) {
                            projects.add(info);
                        }
                    }
                }
            }
        }
        
        // 添加全局项目列表中的项目
        try {
            File globalProjectsFile = new File(context.getFilesDir(), GLOBAL_PROJECTS_FILE);
            if (globalProjectsFile.exists()) {
                JSONObject config = readConfigJson(globalProjectsFile);
                if (config != null && config.has("projects")) {
                    JSONArray globalProjects = config.getJSONArray("projects");
                    for (int i = 0; i < globalProjects.length(); i++) {
                        String projectPath = globalProjects.getString(i);
                        File projectDir = new File(projectPath);
                        
                        if (projectDir.exists() && isAkodeProject(projectDir)) {
                            ProjectInfo info = readProjectConfig(projectDir);
                            if (info != null) {
                                // 检查是否已经存在（避免重复）
                                boolean exists = false;
                                for (ProjectInfo existing : projects) {
                                    if (existing.path.equals(info.path)) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    projects.add(info);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 按最后打开时间降序排序
        Collections.sort(projects, (a, b) -> Long.compare(b.lastOpenTime, a.lastOpenTime));
        
        return projects;
    }
    
    /**
     * 删除项目配置（不删除项目文件）
     */
    public static void removeProjectConfig(String projectPath) {
        try {
            File akodeDir = new File(projectPath, AKODE_DIR);
            if (akodeDir.exists()) {
                deleteRecursive(akodeDir);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 删除项目配置并从全局列表中移除
     */
    public static void removeProjectConfig(Context context, String projectPath) {
        removeProjectConfig(projectPath);
        removeFromGlobalProjects(context, projectPath);
    }
    
    /**
     * 删除整个项目文件夹（包括源代码）
     */
    public static void removeEntireProject(Context context, String projectPath) {
        try {
            File projectDir = new File(projectPath);
            android.util.Log.d("RecentProjectManager", "准备删除整个项目: " + projectDir.getAbsolutePath());
            
            if (projectDir.exists()) {
                android.util.Log.d("RecentProjectManager", "项目目录存在，开始删除: " + projectDir.getAbsolutePath());
                deleteRecursive(projectDir);
                android.util.Log.d("RecentProjectManager", "项目目录删除完成");
            } else {
                android.util.Log.d("RecentProjectManager", "项目目录不存在: " + projectDir.getAbsolutePath());
            }
            
            // 从全局列表中移除
            removeFromGlobalProjects(context, projectPath);
        } catch (Exception e) {
            android.util.Log.e("RecentProjectManager", "删除整个项目时出错", e);
            e.printStackTrace();
        }
    }
    
    private static JSONObject readConfigJson(File configFile) {
        try {
            StringBuilder content = new StringBuilder();
            try (FileReader reader = new FileReader(configFile)) {
                char[] buffer = new char[1024];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, len);
                }
            }
            return new JSONObject(content.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private static void writeConfigJson(File configFile, JSONObject config) {
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(config.toString(2));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
    
    /**
     * 读取third.json配置文件
     */
    public static JSONObject readThirdConfig(File projectDir) {
        try {
            File thirdConfigFile = new File(projectDir, THIRD_CONFIG_FILE);
            if (!thirdConfigFile.exists()) return null;
            
            return readConfigJson(thirdConfigFile);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 获取扩展目录列表
     */
    public static List<String> getExtensionDirs(File projectDir) {
        List<String> dirs = new ArrayList<>();
        JSONObject thirdConfig = readThirdConfig(projectDir);
        
        if (thirdConfig != null) {
            JSONArray extensionDirs = thirdConfig.optJSONArray("extensionDirs");
            if (extensionDirs != null) {
                for (int i = 0; i < extensionDirs.length(); i++) {
                    try {
                        dirs.add(extensionDirs.getString(i));
                    } catch (Exception e) {
                        // 忽略无效的目录项
                    }
                }
            }
        }
        
        // 如果没有配置，返回默认目录
        if (dirs.isEmpty()) {
            dirs.add("src");
            dirs.add("include");
            dirs.add("lib");
            dirs.add("libs");
        }
        
        return dirs;
    }
    
    /**
     * 获取包含的文件类型
     */
    public static List<String> getIncludeTypes(File projectDir) {
        List<String> types = new ArrayList<>();
        JSONObject thirdConfig = readThirdConfig(projectDir);
        
        if (thirdConfig != null) {
            JSONArray includeTypes = thirdConfig.optJSONArray("includeTypes");
            if (includeTypes != null) {
                for (int i = 0; i < includeTypes.length(); i++) {
                    try {
                        types.add(includeTypes.getString(i));
                    } catch (Exception e) {
                        // 忽略无效的类型项
                    }
                }
            }
        }
        
        // 如果没有配置，返回默认类型
        if (types.isEmpty()) {
            types.add("*.h");
            types.add("*.hpp");
            types.add("*.c");
            types.add("*.cpp");
            types.add("*.java");
        }
        
        return types;
    }
    
    /**
     * 获取排除的目录列表
     */
    public static List<String> getExcludeDirs(File projectDir) {
        List<String> dirs = new ArrayList<>();
        JSONObject thirdConfig = readThirdConfig(projectDir);
        
        if (thirdConfig != null) {
            JSONArray excludeDirs = thirdConfig.optJSONArray("excludeDirs");
            if (excludeDirs != null) {
                for (int i = 0; i < excludeDirs.length(); i++) {
                    try {
                        dirs.add(excludeDirs.getString(i));
                    } catch (Exception e) {
                        // 忽略无效的目录项
                    }
                }
            }
        }
        
        // 如果没有配置，返回默认排除目录
        if (dirs.isEmpty()) {
            dirs.add("build");
            dirs.add("bin");
            dirs.add("obj");
            dirs.add(".git");
            dirs.add("node_modules");
        }
        
        return dirs;
    }
    
    /**
     * 添加项目到全局项目列表
     */
    public static void addToGlobalProjects(Context context, String projectPath) {
        try {
            File globalProjectsFile = new File(context.getFilesDir(), GLOBAL_PROJECTS_FILE);
            JSONArray projects = new JSONArray();
            
            // 读取现有的全局项目列表
            if (globalProjectsFile.exists()) {
                JSONObject config = readConfigJson(globalProjectsFile);
                if (config != null && config.has("projects")) {
                    projects = config.getJSONArray("projects");
                }
            }
            
            // 检查项目是否已存在
            boolean exists = false;
            for (int i = 0; i < projects.length(); i++) {
                String existingPath = projects.getString(i);
                if (existingPath.equals(projectPath)) {
                    exists = true;
                    break;
                }
            }
            
            // 如果不存在，添加到列表
            if (!exists) {
                projects.put(projectPath);
                
                // 保存到文件
                JSONObject config = new JSONObject();
                config.put("projects", projects);
                writeConfigJson(globalProjectsFile, config);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 从全局项目列表中移除项目
     */
    public static void removeFromGlobalProjects(Context context, String projectPath) {
        try {
            File globalProjectsFile = new File(context.getFilesDir(), GLOBAL_PROJECTS_FILE);
            if (!globalProjectsFile.exists()) return;
            
            JSONObject config = readConfigJson(globalProjectsFile);
            if (config == null || !config.has("projects")) return;
            
            JSONArray projects = config.getJSONArray("projects");
            JSONArray newProjects = new JSONArray();
            
            // 复制除了要删除的项目之外的所有项目
            for (int i = 0; i < projects.length(); i++) {
                String existingPath = projects.getString(i);
                if (!existingPath.equals(projectPath)) {
                    newProjects.put(existingPath);
                }
            }
            
            // 保存更新后的列表
            config.put("projects", newProjects);
            writeConfigJson(globalProjectsFile, config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
