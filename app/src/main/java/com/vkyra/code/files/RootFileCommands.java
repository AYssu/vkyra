package com.vkyra.code.files;

/** POSIX shell arguments for the root-only commit path; never interpolate a raw file path. */
final class RootFileCommands {
    private RootFileCommands() {}

    static String quote(String path) {
        if (path.indexOf('\0') >= 0) throw new IllegalArgumentException("路径包含 NUL 字符");
        return "'" + path.replace("'", "'\\''") + "'";
    }

    static String commit(String staging, String target, boolean preserveMetadata) {
        String temporary = quote(staging);
        String destination = quote(target);
        String metadata = preserveMetadata ? "chown \"$(stat -c %u:%g " + destination + ")\" "
                + temporary + " && chmod \"$(stat -c %a " + destination + ")\" " + temporary
                + " && vkyra_context=$(ls -Zd -- " + destination + ")"
                + " && vkyra_context=${vkyra_context%% *}"
                + " && case \"$vkyra_context\" in *:*:*) chcon \"$vkyra_context\" " + temporary
                + " || exit 1 ;; esac && " : "";
        return metadata + "sync && mv -f -- " + temporary + " " + destination;
    }
}
