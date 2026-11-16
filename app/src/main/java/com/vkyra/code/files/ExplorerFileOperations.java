package com.vkyra.code.files;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.List;

/** Retains the browser's selected access backend; normal IO errors never trigger root escalation. */
public final class ExplorerFileOperations {
    private ExplorerFileOperations() {}

    public static void execute(File selected, String newName, List<File> protectedPaths) throws IOException {
        if (selected instanceof SuFile) {
            Shell shell = ((SuFile) selected).getShell();
            if (!shell.isRoot()) throw new IOException("Root 文件访问未获授权");
            String command = RootProjectFileCommands.operation(selected, newName, protectedPaths);
            Shell.Result result = shell.newJob().add(command).exec();
            if (result.getCode() == RootProjectFileCommands.COLLISION)
                throw new FileAlreadyExistsException(newName);
            if (result.getCode() == RootProjectFileCommands.PROTECTED)
                throw new IOException("请先关闭相关标签并切出此目录，再执行操作");
            if (!result.isSuccess()) throw new IOException("Root 文件操作失败，退出码 " + result.getCode());
            return;
        }
        ProjectFileOperations.requireUnprotected(selected, protectedPaths);
        if (newName == null) ProjectFileOperations.deleteRecursively(selected);
        else ProjectFileOperations.rename(selected, newName);
    }
}
