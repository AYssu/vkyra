package com.vkyra.code.terminal;

import com.vkyra.code.Log;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;

public final class ShellConfiguration {
    private ShellConfiguration() { }
    public static synchronized void initialize(android.content.Context context) throws java.io.IOException {
        try {
            String homeDir = context.getFilesDir().getAbsolutePath();
            
            // 版本标记文件
            File versionFile = new File(homeDir, ".bashrc_version");
            String currentVersion = "5"; // 更新版本号来强制更新配置
            
            boolean needUpdate = true;
            if (versionFile.exists()) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(versionFile));
                    String savedVersion = reader.readLine();
                    reader.close();
                    if (currentVersion.equals(savedVersion)) {
                        needUpdate = false;
                    }
                } catch (Exception e) {
                    // 读取失败，需要更新
                }
            }
            
            if (needUpdate)     {
                // 创建 .bashrc (mksh 兼容格式)
                File bashrcFile = new File(homeDir, ".bashrc");
                // 使用函数来处理路径显示
                String bashrcContent = 
                    "# Vkyra Shell Config v" + currentVersion + "\n" +
                    "\n" +
                    "# 路径处理函数: HOME显示~, 最多3级目录, 超过显示.../x/x/x\n" +
                    "_akode_pwd() {\n" +
                    "    local p=\"$PWD\"\n" +
                    "    # 替换 HOME 为 ~\n" +
                    "    if [ \"$p\" = \"$HOME\" ]; then\n" +
                    "        echo '~'\n" +
                    "        return\n" +
                    "    fi\n" +
                    "    case \"$p\" in\n" +
                    "        \"$HOME\"/*) p=\"~${p#$HOME}\" ;;\n" +
                    "    esac\n" +
                    "    # 计算目录层级\n" +
                    "    local count=$(echo \"$p\" | tr -cd '/' | wc -c)\n" +
                    "    if [ \"$count\" -gt 3 ]; then\n" +
                    "        # 取最后3级\n" +
                    "        local d1=$(basename \"$p\")\n" +
                    "        local d2=$(basename \"$(dirname \"$p\")\")\n" +
                    "        local d3=$(basename \"$(dirname \"$(dirname \"$p\")\")\")\n" +
                    "        echo \".../$d3/$d2/$d1\"\n" +
                    "    else\n" +
                    "        echo \"$p\"\n" +
                    "    fi\n" +
                    "}\n" +
                    "\n" +
                    "# 检测是否为 root 用户\n" +
                    "if [ \"$(id -u)\" = \"0\" ]; then\n" +
                    "    # root 用户: 红色\n" +
                    "    PS1=$'\\033[1;31mroot@vkyra\\033[0m in \\033[1;34m'\"\\$(_akode_pwd)\"$'\\033[0m# '\n" +
                    "else\n" +
                    "    # 普通用户: 绿色\n" +
                    "    PS1=$'\\033[1;32muser@vkyra\\033[0m in \\033[1;34m'\"\\$(_akode_pwd)\"$'\\033[0m\\$ '\n" +
                    "fi\n" +
                    "\n" +
                    "# ls 颜色支持\n" +
                    "alias ls='ls --color=auto'\n" +
                    "alias ll='ls -la'\n" +
                    "alias la='ls -A'\n" +
                    "alias l='ls -CF'\n" +
                    "\n" +
                    "# 文件类型颜色配置\n" +
                    "export LS_COLORS='di=1;34:ln=1;35:so=1;32:pi=1;33:ex=1;31:bd=1;34;46:cd=1;34;43:su=30;41:sg=30;46:tw=30;42:ow=30;43'\n" +
                    "\n" +
                    "# grep 颜色\n" +
                    "alias grep='grep --color=auto'\n" +
                    "\n" +
                    "# 历史记录配置\n" +
                    "export HISTFILE=\"$HOME/.history\"\n" +
                    "export HISTSIZE=1000\n" +
                    "\n" +
                    "# 常用别名\n" +
                    "alias ..='cd ..'\n" +
                    "alias ...='cd ../..'\n" +
                    "alias cls='clear'\n";
                
                writeIfMissing(bashrcFile, bashrcContent);
                
                // 同时创建 .mkshrc (mksh 的配置文件)
                File mkshrcFile = new File(homeDir, ".mkshrc");
                String mkshrcContent = 
                    "# mksh 配置 - 加载 .bashrc\n" +
                    "if [ -f \"$HOME/.bashrc\" ]; then\n" +
                    "    . \"$HOME/.bashrc\"\n" +
                    "fi\n";
                
                writeIfMissing(mkshrcFile, mkshrcContent);
                
                // 创建 .profile
                File profileFile = new File(homeDir, ".profile");
                String profileContent = 
                    "# ~/.profile\n" +
                    "if [ -f \"$HOME/.bashrc\" ]; then\n" +
                    "    . \"$HOME/.bashrc\"\n" +
                    "fi\n";
                
                writeIfMissing(profileFile, profileContent);
                
                // 保存版本号
                java.nio.file.Files.write(versionFile.toPath(), currentVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                
                Log.d("ShellConfiguration", "Updated shell config to version " + currentVersion);
            }
            
            // 创建 tmp 目录
            File tmpDir = new File(homeDir, "tmp");
            if (!tmpDir.exists()) {
                tmpDir.mkdirs();
            }
            
        } catch (Exception e) {
            throw new java.io.IOException("无法初始化 shell 配置", e);
        }
    }

    private static void writeIfMissing(File file, String content) throws java.io.IOException {
        if (!file.exists()) java.nio.file.Files.write(file.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
