package com.vkyra.code.terminal;

import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.toolchain.BuildConfiguration;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.CompilerArguments;
import com.vkyra.code.toolchain.CppCompiler;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Builds quoted shell commands; user commands remain explicit RUN_COMMAND/EXECUTE_COMMAND input. */
public final class TerminalCommands {
    private TerminalCommands() { }
    public static String runFile(String file, String arguments, boolean root, String customSu) {
        List<String> words = new ArrayList<>();
        words.add(file); words.addAll(CompilerArguments.parse(arguments));
        String command = CompilerArguments.shellCommand(words);
        return root ? customSu + " " + CompilerArguments.shellQuote(command) : command;
    }

    public static String compileAndRun(Context context, String filePath, String sessionHandle, String runArgs) throws Exception {
        File source = new File(filePath);
        String clang = ClangInstaller.getClangDir(context);
        if (clang == null) throw new IllegalStateException("不支持的设备架构");
        File temporary = new File(context.getFilesDir(), "tmp/terminal-" + sessionHandle);
        Files.createDirectories(temporary.toPath());
        List<String> files = new ArrayList<>();
        for (String entry : BuildConfiguration.readMultiFileList(source.getParentFile())) {
            File file = new File(entry);
            files.add(file.isAbsolute() ? file.getPath() : new File(source.getParentFile(), entry).getPath());
        }
        if (files.isEmpty()) files.add(filePath);
        String output = new File(temporary, "a.out").getPath();
        String compile = CompilerArguments.shellCommand(CppCompiler.createCommand(context, clang, files, output, false));
        String run = runFile(output, runArgs, AppPreferences.getEnableRootRun(context), AppPreferences.getCustomSu(context));
        return "clear; printf '%s\\n' " + CompilerArguments.shellQuote("正在编译: " + source.getName())
                + " " + CompilerArguments.shellQuote("$ " + compile) + "; cd " + CompilerArguments.shellQuote(temporary.getPath())
                + " && " + compile + " && printf '%s\\n' '编译成功，开始运行...' && " + run + "; exit\n";
    }
}
