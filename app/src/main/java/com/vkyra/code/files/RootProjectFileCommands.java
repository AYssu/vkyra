package com.vkyra.code.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Quoted POSIX commands for explorer entries that already came from the root browser. */
final class RootProjectFileCommands {
    static final int COLLISION = 73;
    static final int PROTECTED = 74;

    private RootProjectFileCommands() {}

    static String operation(File selected, String newName, List<File> protectedPaths) throws IOException {
        Path source = selected.toPath().toAbsolutePath().normalize();
        if (source.getParent() == null) throw new IOException("不能修改文件系统根目录");
        if (newName != null && !ProjectFileOperations.isBasename(newName))
            throw new IOException("名称必须是单个文件名");
        String original = RootFileCommands.quote(source.toString());
        StringBuilder command = new StringBuilder();
        // readlink is the platform resolver. The sentinel retains trailing newlines in pathnames.
        // Resolve the parent for a symlink entry, since rm/mv operate on the link, not its target.
        command.append("if [ -L ").append(original).append(" ]; then ")
                .append(resolve("vkyra_parent", source.getParent().toString()))
                .append("vkyra_selected=\"${vkyra_parent%/}/\"")
                .append(RootFileCommands.quote(source.getFileName().toString())).append("; else ")
                .append(resolve("vkyra_selected", source.toString())).append("fi; ")
                .append("[ \"$vkyra_selected\" != / ] || exit ").append(PROTECTED).append("; ");
        for (File protectedPath : protectedPaths) {
            if (ProjectFileOperations.contains(source.toFile(), protectedPath))
                throw new IOException("请先关闭相关标签并切出此目录，再执行操作");
            command.append(resolve("vkyra_guard", protectedPath.toPath().toAbsolutePath().normalize().toString()))
                    .append("case \"$vkyra_guard\" in \"$vkyra_selected\"|\"$vkyra_selected\"/*) exit ")
                    .append(PROTECTED).append(";; esac; ");
        }
        if (newName == null) {
            // Never append '/': rm must unlink selected directory symlinks, never traverse them.
            command.append("rm -rf -- ").append(original);
        } else {
            String destination = RootFileCommands.quote(source.getParent().resolve(newName).toString());
            command.append("[ ! -e ").append(destination).append(" ] && [ ! -L ")
                    .append(destination).append(" ] || exit ").append(COLLISION).append("; ")
                    .append("mv -n -- ").append(original).append(' ').append(destination).append(" || exit $?; ")
                    // mv -n may return success when another entry wins the destination race.
                    .append("[ ! -e ").append(original).append(" ] && [ ! -L ").append(original)
                    .append(" ] && { [ -e ").append(destination).append(" ] || [ -L ")
                    .append(destination).append(" ]; }");
        }
        // A failed guard must terminate this job, never libsu's shared interactive shell.
        return "( " + command + " )";
    }

    private static String resolve(String variable, String path) {
        return variable + "=$(readlink -fn -- " + RootFileCommands.quote(path)
                + " && printf .) || exit 1; " + variable + "=${" + variable + "%.}; "
                + "[ -n \"$" + variable + "\" ] || exit 1; ";
    }
}
