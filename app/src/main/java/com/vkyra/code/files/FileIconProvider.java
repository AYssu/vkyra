package com.vkyra.code.files;

import com.vkyra.code.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import com.caverock.androidsvg.SVG;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 文件图标提供器
 * 基于 VSCode Material Icon Theme 风格
 * 图标来源: https://github.com/PKief/vscode-material-icon-theme (MIT License)
 */
public class FileIconProvider {
    
    // 文件扩展名 -> SVG 文件名
    private static final Map<String, String> EXT_ICON_MAP = new HashMap<>();
    
    // 特殊文件名 -> SVG 文件名
    private static final Map<String, String> NAME_ICON_MAP = new HashMap<>();
    
    // 文件夹名 -> SVG 文件名
    private static final Map<String, String> FOLDER_ICON_MAP = new HashMap<>();
    
    // 图标缓存
    private static LruCache<String, Drawable> iconCache;
    private static final int CACHE_SIZE = 100;
    
    static {
        iconCache = new LruCache<>(CACHE_SIZE);
        initExtIconMap1();
        initExtIconMap2();
        initExtIconMap3();
        initNameIconMap();
        initFolderIconMap1();
        initFolderIconMap2();
        initFolderIconMap3();
    }
    
    // ==================== 扩展名映射初始化 - 第1部分 ====================
    private static void initExtIconMap1() {
        // C/C++ 相关
        EXT_ICON_MAP.put("c", "c");
        EXT_ICON_MAP.put("h", "h");
        EXT_ICON_MAP.put("cpp", "cpp");
        EXT_ICON_MAP.put("cc", "cpp");
        EXT_ICON_MAP.put("cxx", "cpp");
        EXT_ICON_MAP.put("c++", "cpp");
        EXT_ICON_MAP.put("hpp", "hpp");
        EXT_ICON_MAP.put("hh", "hpp");
        EXT_ICON_MAP.put("hxx", "hpp");
        EXT_ICON_MAP.put("inl", "hpp");
        EXT_ICON_MAP.put("m", "objective-c");
        EXT_ICON_MAP.put("mm", "objective-cpp");
        EXT_ICON_MAP.put("cu", "cuda");
        EXT_ICON_MAP.put("cuh", "cuda");
        
        // Java/Kotlin/Android
        EXT_ICON_MAP.put("java", "java");
        EXT_ICON_MAP.put("kt", "kotlin");
        EXT_ICON_MAP.put("kts", "kotlin");
        EXT_ICON_MAP.put("gradle", "gradle");
        EXT_ICON_MAP.put("groovy", "groovy");
        EXT_ICON_MAP.put("scala", "scala");
        
        // C#/.NET
        EXT_ICON_MAP.put("cs", "csharp");
        EXT_ICON_MAP.put("csx", "csharp");
        EXT_ICON_MAP.put("fs", "fsharp");
        EXT_ICON_MAP.put("fsx", "fsharp");
        
        // Web 相关
        EXT_ICON_MAP.put("html", "html");
        EXT_ICON_MAP.put("htm", "html");
        EXT_ICON_MAP.put("css", "css");
        EXT_ICON_MAP.put("scss", "sass");
        EXT_ICON_MAP.put("sass", "sass");
        EXT_ICON_MAP.put("less", "less");
        EXT_ICON_MAP.put("styl", "stylus");
        EXT_ICON_MAP.put("js", "javascript");
        EXT_ICON_MAP.put("mjs", "javascript");
        EXT_ICON_MAP.put("cjs", "javascript");
        EXT_ICON_MAP.put("ts", "typescript");
        EXT_ICON_MAP.put("mts", "typescript");
        EXT_ICON_MAP.put("cts", "typescript");
        EXT_ICON_MAP.put("d.ts", "typescript-def");
        EXT_ICON_MAP.put("jsx", "react");
        EXT_ICON_MAP.put("tsx", "react_ts");
        EXT_ICON_MAP.put("vue", "vue");
        EXT_ICON_MAP.put("svelte", "svelte");
        EXT_ICON_MAP.put("astro", "astro");
        EXT_ICON_MAP.put("mdx", "mdx");
        EXT_ICON_MAP.put("graphql", "graphql");
        EXT_ICON_MAP.put("gql", "graphql");
    }
    
    // ==================== 扩展名映射初始化 - 第2部分 ====================
    private static void initExtIconMap2() {
        // 数据/配置文件
        EXT_ICON_MAP.put("json", "json");
        EXT_ICON_MAP.put("json5", "json");
        EXT_ICON_MAP.put("jsonc", "json");
        EXT_ICON_MAP.put("xml", "xml");
        EXT_ICON_MAP.put("yaml", "yaml");
        EXT_ICON_MAP.put("yml", "yaml");
        EXT_ICON_MAP.put("toml", "toml");
        EXT_ICON_MAP.put("ini", "settings");
        EXT_ICON_MAP.put("conf", "settings");
        EXT_ICON_MAP.put("cfg", "settings");
        EXT_ICON_MAP.put("properties", "settings");
        EXT_ICON_MAP.put("env", "settings");
        EXT_ICON_MAP.put("proto", "proto");
        EXT_ICON_MAP.put("prisma", "prisma");
        
        // 脚本
        EXT_ICON_MAP.put("sh", "console");
        EXT_ICON_MAP.put("bash", "console");
        EXT_ICON_MAP.put("zsh", "console");
        EXT_ICON_MAP.put("fish", "console");
        EXT_ICON_MAP.put("bat", "console");
        EXT_ICON_MAP.put("cmd", "console");
        EXT_ICON_MAP.put("ps1", "powershell");
        EXT_ICON_MAP.put("psm1", "powershell");
        EXT_ICON_MAP.put("py", "python");
        EXT_ICON_MAP.put("pyw", "python");
        EXT_ICON_MAP.put("pyx", "python");
        EXT_ICON_MAP.put("rb", "ruby");
        EXT_ICON_MAP.put("rake", "ruby");
        EXT_ICON_MAP.put("go", "go");
        EXT_ICON_MAP.put("rs", "rust");
        EXT_ICON_MAP.put("swift", "swift");
        EXT_ICON_MAP.put("php", "php");
        EXT_ICON_MAP.put("lua", "lua");
        EXT_ICON_MAP.put("luau", "luau");
        EXT_ICON_MAP.put("pl", "perl");
        EXT_ICON_MAP.put("pm", "perl");
        EXT_ICON_MAP.put("r", "r");
        EXT_ICON_MAP.put("R", "r");
        EXT_ICON_MAP.put("dart", "dart");
        EXT_ICON_MAP.put("ex", "elixir");
        EXT_ICON_MAP.put("exs", "elixir");
        EXT_ICON_MAP.put("erl", "erlang");
        EXT_ICON_MAP.put("hrl", "erlang");
        EXT_ICON_MAP.put("hs", "haskell");
        EXT_ICON_MAP.put("lhs", "haskell");
        EXT_ICON_MAP.put("clj", "clojure");
        EXT_ICON_MAP.put("cljs", "clojure");
        EXT_ICON_MAP.put("nim", "nim");
        EXT_ICON_MAP.put("zig", "zig");
        EXT_ICON_MAP.put("v", "vlang");
        EXT_ICON_MAP.put("jl", "julia");
        EXT_ICON_MAP.put("ml", "ocaml");
        EXT_ICON_MAP.put("mli", "ocaml");
        EXT_ICON_MAP.put("coffee", "coffee");
        EXT_ICON_MAP.put("tcl", "tcl");
    }
    
    // ==================== 扩展名映射初始化 - 第3部分 ====================
    private static void initExtIconMap3() {
        // 文档
        EXT_ICON_MAP.put("md", "markdown");
        EXT_ICON_MAP.put("markdown", "markdown");
        EXT_ICON_MAP.put("txt", "document");
        EXT_ICON_MAP.put("log", "log");
        EXT_ICON_MAP.put("pdf", "pdf");
        EXT_ICON_MAP.put("doc", "word");
        EXT_ICON_MAP.put("docx", "word");
        EXT_ICON_MAP.put("ppt", "powerpoint");
        EXT_ICON_MAP.put("pptx", "powerpoint");
        EXT_ICON_MAP.put("xls", "table");
        EXT_ICON_MAP.put("xlsx", "table");
        EXT_ICON_MAP.put("csv", "table");
        EXT_ICON_MAP.put("tex", "tex");
        EXT_ICON_MAP.put("latex", "tex");
        EXT_ICON_MAP.put("rst", "document");
        EXT_ICON_MAP.put("adoc", "asciidoc");
        EXT_ICON_MAP.put("asciidoc", "asciidoc");
        
        // 图片
        EXT_ICON_MAP.put("png", "image");
        EXT_ICON_MAP.put("jpg", "image");
        EXT_ICON_MAP.put("jpeg", "image");
        EXT_ICON_MAP.put("gif", "image");
        EXT_ICON_MAP.put("bmp", "image");
        EXT_ICON_MAP.put("webp", "image");
        EXT_ICON_MAP.put("svg", "svg");
        EXT_ICON_MAP.put("ico", "image");
        
        // 压缩包
        EXT_ICON_MAP.put("zip", "zip");
        EXT_ICON_MAP.put("rar", "zip");
        EXT_ICON_MAP.put("7z", "zip");
        EXT_ICON_MAP.put("tar", "zip");
        EXT_ICON_MAP.put("gz", "zip");
        EXT_ICON_MAP.put("xz", "zip");
        
        // 二进制/可执行
        EXT_ICON_MAP.put("apk", "android");
        EXT_ICON_MAP.put("exe", "exe");
        EXT_ICON_MAP.put("dll", "dll");
        EXT_ICON_MAP.put("so", "lib");
        EXT_ICON_MAP.put("a", "lib");
        EXT_ICON_MAP.put("o", "lib");
        EXT_ICON_MAP.put("class", "javaclass");
        EXT_ICON_MAP.put("jar", "jar");
        EXT_ICON_MAP.put("aar", "jar");
        
        // 数据库
        EXT_ICON_MAP.put("sql", "database");
        EXT_ICON_MAP.put("db", "database");
        EXT_ICON_MAP.put("sqlite", "database");
        
        // 音视频
        EXT_ICON_MAP.put("mp3", "audio");
        EXT_ICON_MAP.put("wav", "audio");
        EXT_ICON_MAP.put("flac", "audio");
        EXT_ICON_MAP.put("mp4", "video");
        EXT_ICON_MAP.put("avi", "video");
        EXT_ICON_MAP.put("mkv", "video");
        
        // 字体
        EXT_ICON_MAP.put("ttf", "font");
        EXT_ICON_MAP.put("otf", "font");
        EXT_ICON_MAP.put("woff", "font");
        
        // 证书/密钥
        EXT_ICON_MAP.put("pem", "key");
        EXT_ICON_MAP.put("key", "key");
        EXT_ICON_MAP.put("crt", "certificate");
        EXT_ICON_MAP.put("cer", "certificate");
        EXT_ICON_MAP.put("jks", "key");
        EXT_ICON_MAP.put("p12", "key");
        EXT_ICON_MAP.put("pfx", "key");
        
        // 构建工具
        EXT_ICON_MAP.put("cmake", "cmake");
        EXT_ICON_MAP.put("ninja", "settings");
        EXT_ICON_MAP.put("bazel", "bazel");
        EXT_ICON_MAP.put("bzl", "bazel");
        EXT_ICON_MAP.put("meson", "meson");
        
        // 其他语言
        EXT_ICON_MAP.put("sol", "solidity");
        EXT_ICON_MAP.put("wasm", "webassembly");
        EXT_ICON_MAP.put("wat", "webassembly");
        EXT_ICON_MAP.put("asm", "assembly");
        EXT_ICON_MAP.put("s", "assembly");
        EXT_ICON_MAP.put("S", "assembly");
        EXT_ICON_MAP.put("pas", "pascal");
        EXT_ICON_MAP.put("f90", "fortran");
        EXT_ICON_MAP.put("f95", "fortran");
        EXT_ICON_MAP.put("f03", "fortran");
        EXT_ICON_MAP.put("cob", "cobol");
        EXT_ICON_MAP.put("cbl", "cobol");
        EXT_ICON_MAP.put("lisp", "lisp");
        EXT_ICON_MAP.put("scm", "scheme");
        EXT_ICON_MAP.put("rkt", "racket");
        EXT_ICON_MAP.put("pro", "prolog");
        EXT_ICON_MAP.put("vhd", "verilog");
        EXT_ICON_MAP.put("vhdl", "verilog");
        EXT_ICON_MAP.put("sv", "verilog");
        EXT_ICON_MAP.put("mat", "matlab");
        EXT_ICON_MAP.put("nb", "mathematica");
        EXT_ICON_MAP.put("ipynb", "jupyter");
        
        // 游戏开发
        EXT_ICON_MAP.put("gd", "godot");
        EXT_ICON_MAP.put("tscn", "godot");
        EXT_ICON_MAP.put("tres", "godot");
        EXT_ICON_MAP.put("unity", "unity");
        EXT_ICON_MAP.put("prefab", "unity");
        EXT_ICON_MAP.put("blend", "blender");
    }

    // ==================== 特殊文件名映射初始化 ====================
    private static void initNameIconMap() {
        NAME_ICON_MAP.put("makefile", "makefile");
        NAME_ICON_MAP.put("gnumakefile", "makefile");
        NAME_ICON_MAP.put("cmakelists.txt", "cmake");
        NAME_ICON_MAP.put("dockerfile", "docker");
        NAME_ICON_MAP.put("docker-compose.yml", "docker");
        NAME_ICON_MAP.put("docker-compose.yaml", "docker");
        NAME_ICON_MAP.put("compose.yml", "docker");
        NAME_ICON_MAP.put("compose.yaml", "docker");
        NAME_ICON_MAP.put(".dockerignore", "docker");
        NAME_ICON_MAP.put(".gitignore", "git");
        NAME_ICON_MAP.put(".gitmodules", "git");
        NAME_ICON_MAP.put(".gitattributes", "git");
        NAME_ICON_MAP.put(".gitconfig", "git");
        NAME_ICON_MAP.put("readme.md", "readme");
        NAME_ICON_MAP.put("readme.txt", "readme");
        NAME_ICON_MAP.put("readme", "readme");
        NAME_ICON_MAP.put("license", "license");
        NAME_ICON_MAP.put("license.md", "license");
        NAME_ICON_MAP.put("license.txt", "license");
        NAME_ICON_MAP.put("licence", "license");
        NAME_ICON_MAP.put("copying", "license");
        NAME_ICON_MAP.put("changelog.md", "changelog");
        NAME_ICON_MAP.put("changelog", "changelog");
        NAME_ICON_MAP.put("history.md", "changelog");
        NAME_ICON_MAP.put("package.json", "nodejs");
        NAME_ICON_MAP.put("package-lock.json", "npm");
        NAME_ICON_MAP.put("yarn.lock", "yarn");
        NAME_ICON_MAP.put("pnpm-lock.yaml", "pnpm");
        NAME_ICON_MAP.put(".npmrc", "npm");
        NAME_ICON_MAP.put(".yarnrc", "yarn");
        NAME_ICON_MAP.put("build.gradle", "gradle");
        NAME_ICON_MAP.put("build.gradle.kts", "gradle");
        NAME_ICON_MAP.put("settings.gradle", "gradle");
        NAME_ICON_MAP.put("settings.gradle.kts", "gradle");
        NAME_ICON_MAP.put("gradle.properties", "gradle");
        NAME_ICON_MAP.put("gradlew", "gradle");
        NAME_ICON_MAP.put("gradlew.bat", "gradle");
        NAME_ICON_MAP.put(".editorconfig", "editorconfig");
        NAME_ICON_MAP.put(".clang-format", "clangd");
        NAME_ICON_MAP.put(".clang-tidy", "clangd");
        NAME_ICON_MAP.put(".clangd", "clangd");
        NAME_ICON_MAP.put("compile_commands.json", "json");
        NAME_ICON_MAP.put("tsconfig.json", "tsconfig");
        NAME_ICON_MAP.put("jsconfig.json", "jsconfig");
        NAME_ICON_MAP.put(".eslintrc", "eslint");
        NAME_ICON_MAP.put(".eslintrc.js", "eslint");
        NAME_ICON_MAP.put(".eslintrc.json", "eslint");
        NAME_ICON_MAP.put("eslint.config.js", "eslint");
        NAME_ICON_MAP.put(".prettierrc", "prettier");
        NAME_ICON_MAP.put(".prettierrc.js", "prettier");
        NAME_ICON_MAP.put(".prettierrc.json", "prettier");
        NAME_ICON_MAP.put("prettier.config.js", "prettier");
        NAME_ICON_MAP.put("vite.config.js", "vite");
        NAME_ICON_MAP.put("vite.config.ts", "vite");
        NAME_ICON_MAP.put("webpack.config.js", "webpack");
        NAME_ICON_MAP.put("rollup.config.js", "rollup");
        NAME_ICON_MAP.put("babel.config.js", "babel");
        NAME_ICON_MAP.put(".babelrc", "babel");
        NAME_ICON_MAP.put("jest.config.js", "jest");
        NAME_ICON_MAP.put("vitest.config.ts", "vitest");
        NAME_ICON_MAP.put("cargo.toml", "rust");
        NAME_ICON_MAP.put("cargo.lock", "rust");
        NAME_ICON_MAP.put("go.mod", "go-mod");
        NAME_ICON_MAP.put("go.sum", "go-mod");
        NAME_ICON_MAP.put("requirements.txt", "python");
        NAME_ICON_MAP.put("setup.py", "python");
        NAME_ICON_MAP.put("pyproject.toml", "python");
        NAME_ICON_MAP.put("pipfile", "python");
        NAME_ICON_MAP.put("gemfile", "gemfile");
        NAME_ICON_MAP.put("gemfile.lock", "gemfile");
        NAME_ICON_MAP.put("rakefile", "ruby");
        NAME_ICON_MAP.put("podfile", "ruby");
        NAME_ICON_MAP.put("pubspec.yaml", "dart");
        NAME_ICON_MAP.put("pubspec.lock", "dart");
        NAME_ICON_MAP.put("pom.xml", "maven");
        NAME_ICON_MAP.put("build.xml", "xml");
        NAME_ICON_MAP.put("androidmanifest.xml", "android");
        NAME_ICON_MAP.put("info.plist", "settings");
        NAME_ICON_MAP.put(".htaccess", "settings");
        NAME_ICON_MAP.put("nginx.conf", "nginx");
        NAME_ICON_MAP.put("robots.txt", "robots");
        NAME_ICON_MAP.put("favicon.ico", "favicon");
        NAME_ICON_MAP.put("contributing.md", "contributing");
        NAME_ICON_MAP.put("authors", "authors");
        NAME_ICON_MAP.put("authors.md", "authors");
        NAME_ICON_MAP.put("todo", "todo");
        NAME_ICON_MAP.put("todo.md", "todo");
        NAME_ICON_MAP.put("todo.txt", "todo");
        NAME_ICON_MAP.put(".env", "settings");
        NAME_ICON_MAP.put(".env.local", "settings");
        NAME_ICON_MAP.put(".env.development", "settings");
        NAME_ICON_MAP.put(".env.production", "settings");
        NAME_ICON_MAP.put("toolchain.cmake", "cmake");
    }
    
    // ==================== 文件夹名映射初始化 - 第1部分 ====================
    private static void initFolderIconMap1() {
        FOLDER_ICON_MAP.put("src", "folder-src");
        FOLDER_ICON_MAP.put("source", "folder-src");
        FOLDER_ICON_MAP.put("sources", "folder-src");
        FOLDER_ICON_MAP.put("include", "folder-include");
        FOLDER_ICON_MAP.put("includes", "folder-include");
        FOLDER_ICON_MAP.put("inc", "folder-include");
        FOLDER_ICON_MAP.put("lib", "folder-lib");
        FOLDER_ICON_MAP.put("libs", "folder-lib");
        FOLDER_ICON_MAP.put("library", "folder-lib");
        FOLDER_ICON_MAP.put("vendor", "folder-lib");
        FOLDER_ICON_MAP.put("third_party", "folder-lib");
        FOLDER_ICON_MAP.put("external", "folder-lib");
        FOLDER_ICON_MAP.put("build", "folder-dist");
        FOLDER_ICON_MAP.put("out", "folder-dist");
        FOLDER_ICON_MAP.put("output", "folder-dist");
        FOLDER_ICON_MAP.put("bin", "folder-dist");
        FOLDER_ICON_MAP.put("dist", "folder-dist");
        FOLDER_ICON_MAP.put("release", "folder-dist");
        FOLDER_ICON_MAP.put("target", "folder-target");
        FOLDER_ICON_MAP.put("test", "folder-test");
        FOLDER_ICON_MAP.put("tests", "folder-test");
        FOLDER_ICON_MAP.put("__tests__", "folder-test");
        FOLDER_ICON_MAP.put("spec", "folder-test");
        FOLDER_ICON_MAP.put("specs", "folder-test");
        FOLDER_ICON_MAP.put("res", "folder-resource");
        FOLDER_ICON_MAP.put("resources", "folder-resource");
        FOLDER_ICON_MAP.put("resource", "folder-resource");
        FOLDER_ICON_MAP.put("assets", "folder-resource");
        FOLDER_ICON_MAP.put("asset", "folder-resource");
        FOLDER_ICON_MAP.put("static", "folder-public");
        FOLDER_ICON_MAP.put("doc", "folder-docs");
        FOLDER_ICON_MAP.put("docs", "folder-docs");
        FOLDER_ICON_MAP.put("documentation", "folder-docs");
        FOLDER_ICON_MAP.put(".git", "folder-git");
        FOLDER_ICON_MAP.put(".github", "folder-github");
        FOLDER_ICON_MAP.put(".gitlab", "folder-gitlab");
        FOLDER_ICON_MAP.put(".vscode", "folder-vscode");
        FOLDER_ICON_MAP.put(".idea", "folder-intellij");
        FOLDER_ICON_MAP.put("node_modules", "folder-node");
        FOLDER_ICON_MAP.put("gradle", "folder-gradle");
        FOLDER_ICON_MAP.put(".gradle", "folder-gradle");
        FOLDER_ICON_MAP.put("config", "folder-config");
        FOLDER_ICON_MAP.put("configs", "folder-config");
        FOLDER_ICON_MAP.put("configuration", "folder-config");
        FOLDER_ICON_MAP.put("settings", "folder-config");
        FOLDER_ICON_MAP.put("scripts", "folder-scripts");
        FOLDER_ICON_MAP.put("script", "folder-scripts");
        FOLDER_ICON_MAP.put("tools", "folder-tools");
        FOLDER_ICON_MAP.put("tool", "folder-tools");
        FOLDER_ICON_MAP.put("images", "folder-images");
        FOLDER_ICON_MAP.put("image", "folder-images");
        FOLDER_ICON_MAP.put("img", "folder-images");
        FOLDER_ICON_MAP.put("icons", "folder-images");
        FOLDER_ICON_MAP.put("icon", "folder-images");
        FOLDER_ICON_MAP.put("drawable", "folder-images");
        FOLDER_ICON_MAP.put("drawable-hdpi", "folder-images");
        FOLDER_ICON_MAP.put("drawable-mdpi", "folder-images");
        FOLDER_ICON_MAP.put("drawable-xhdpi", "folder-images");
        FOLDER_ICON_MAP.put("drawable-xxhdpi", "folder-images");
        FOLDER_ICON_MAP.put("drawable-xxxhdpi", "folder-images");
        FOLDER_ICON_MAP.put("mipmap-hdpi", "folder-images");
        FOLDER_ICON_MAP.put("mipmap-mdpi", "folder-images");
        FOLDER_ICON_MAP.put("mipmap-xhdpi", "folder-images");
        FOLDER_ICON_MAP.put("mipmap-xxhdpi", "folder-images");
        FOLDER_ICON_MAP.put("mipmap-xxxhdpi", "folder-images");
        FOLDER_ICON_MAP.put("layout", "folder-layout");
        FOLDER_ICON_MAP.put("layouts", "folder-layout");
        FOLDER_ICON_MAP.put("values", "folder-resource");
        FOLDER_ICON_MAP.put("values-zh", "folder-i18n");
        FOLDER_ICON_MAP.put("values-en", "folder-i18n");
        FOLDER_ICON_MAP.put("java", "folder-java");
        FOLDER_ICON_MAP.put("kotlin", "folder-src");
        FOLDER_ICON_MAP.put("cpp", "folder-src");
        FOLDER_ICON_MAP.put("android", "folder-android");
        FOLDER_ICON_MAP.put("ios", "folder-ios");
        FOLDER_ICON_MAP.put("macos", "folder-macos");
        FOLDER_ICON_MAP.put("linux", "folder-linux");
        FOLDER_ICON_MAP.put("windows", "folder-windows");
        FOLDER_ICON_MAP.put("app", "folder-app");
        FOLDER_ICON_MAP.put("apps", "folder-app");
        FOLDER_ICON_MAP.put("application", "folder-app");
        FOLDER_ICON_MAP.put("api", "folder-api");
        FOLDER_ICON_MAP.put("apis", "folder-api");
        FOLDER_ICON_MAP.put("components", "folder-components");
        FOLDER_ICON_MAP.put("component", "folder-components");
        FOLDER_ICON_MAP.put("widgets", "folder-components");
        FOLDER_ICON_MAP.put("utils", "folder-utils");
        FOLDER_ICON_MAP.put("util", "folder-utils");
        FOLDER_ICON_MAP.put("utilities", "folder-utils");
        FOLDER_ICON_MAP.put("helper", "folder-helper");
        FOLDER_ICON_MAP.put("helpers", "folder-helper");
        FOLDER_ICON_MAP.put("hooks", "folder-hook");
        FOLDER_ICON_MAP.put("hook", "folder-hook");
        FOLDER_ICON_MAP.put("public", "folder-public");
        FOLDER_ICON_MAP.put("private", "folder-private");
        FOLDER_ICON_MAP.put("temp", "folder-temp");
        FOLDER_ICON_MAP.put("tmp", "folder-temp");
        FOLDER_ICON_MAP.put("cache", "folder-temp");
        FOLDER_ICON_MAP.put(".cache", "folder-temp");
    }

    // ==================== 文件夹名映射初始化 - 第2部分 ====================
    private static void initFolderIconMap2() {
        FOLDER_ICON_MAP.put("packages", "folder-packages");
        FOLDER_ICON_MAP.put("package", "folder-packages");
        FOLDER_ICON_MAP.put("models", "folder-database");
        FOLDER_ICON_MAP.put("model", "folder-database");
        FOLDER_ICON_MAP.put("database", "folder-database");
        FOLDER_ICON_MAP.put("db", "folder-database");
        FOLDER_ICON_MAP.put("data", "folder-database");
        FOLDER_ICON_MAP.put("views", "folder-views");
        FOLDER_ICON_MAP.put("view", "folder-views");
        FOLDER_ICON_MAP.put("pages", "folder-views");
        FOLDER_ICON_MAP.put("page", "folder-views");
        FOLDER_ICON_MAP.put("screens", "folder-views");
        FOLDER_ICON_MAP.put("templates", "folder-template");
        FOLDER_ICON_MAP.put("template", "folder-template");
        FOLDER_ICON_MAP.put("fonts", "folder-font");
        FOLDER_ICON_MAP.put("font", "folder-font");
        FOLDER_ICON_MAP.put("audio", "folder-audio");
        FOLDER_ICON_MAP.put("sounds", "folder-audio");
        FOLDER_ICON_MAP.put("music", "folder-audio");
        FOLDER_ICON_MAP.put("video", "folder-video");
        FOLDER_ICON_MAP.put("videos", "folder-video");
        FOLDER_ICON_MAP.put("media", "folder-video");
        FOLDER_ICON_MAP.put("download", "folder-download");
        FOLDER_ICON_MAP.put("downloads", "folder-download");
        FOLDER_ICON_MAP.put("upload", "folder-upload");
        FOLDER_ICON_MAP.put("uploads", "folder-upload");
        FOLDER_ICON_MAP.put("plugin", "folder-plugin");
        FOLDER_ICON_MAP.put("plugins", "folder-plugin");
        FOLDER_ICON_MAP.put("extension", "folder-plugin");
        FOLDER_ICON_MAP.put("extensions", "folder-plugin");
        FOLDER_ICON_MAP.put("addon", "folder-plugin");
        FOLDER_ICON_MAP.put("addons", "folder-plugin");
        FOLDER_ICON_MAP.put("modules", "folder-plugin");
        FOLDER_ICON_MAP.put("module", "folder-plugin");
        FOLDER_ICON_MAP.put("core", "folder-core");
        FOLDER_ICON_MAP.put("common", "folder-shared");
        FOLDER_ICON_MAP.put("shared", "folder-shared");
        FOLDER_ICON_MAP.put("server", "folder-server");
        FOLDER_ICON_MAP.put("client", "folder-client");
        FOLDER_ICON_MAP.put("frontend", "folder-client");
        FOLDER_ICON_MAP.put("backend", "folder-server");
        FOLDER_ICON_MAP.put("services", "folder-server");
        FOLDER_ICON_MAP.put("service", "folder-server");
        FOLDER_ICON_MAP.put("controllers", "folder-controller");
        FOLDER_ICON_MAP.put("controller", "folder-controller");
        FOLDER_ICON_MAP.put("routes", "folder-routes");
        FOLDER_ICON_MAP.put("route", "folder-routes");
        FOLDER_ICON_MAP.put("router", "folder-routes");
        FOLDER_ICON_MAP.put("middleware", "folder-middleware");
        FOLDER_ICON_MAP.put("middlewares", "folder-middleware");
        FOLDER_ICON_MAP.put("store", "folder-store");
        FOLDER_ICON_MAP.put("stores", "folder-store");
        FOLDER_ICON_MAP.put("state", "folder-store");
        FOLDER_ICON_MAP.put("redux", "folder-redux-reducer");
        FOLDER_ICON_MAP.put("context", "folder-context");
        FOLDER_ICON_MAP.put("contexts", "folder-context");
        FOLDER_ICON_MAP.put("providers", "folder-context");
        FOLDER_ICON_MAP.put("types", "folder-typescript");
        FOLDER_ICON_MAP.put("typings", "folder-typescript");
        FOLDER_ICON_MAP.put("interfaces", "folder-interface");
        FOLDER_ICON_MAP.put("interface", "folder-interface");
        FOLDER_ICON_MAP.put("constants", "folder-constant");
        FOLDER_ICON_MAP.put("constant", "folder-constant");
        FOLDER_ICON_MAP.put("consts", "folder-constant");
        FOLDER_ICON_MAP.put("enums", "folder-enum");
        FOLDER_ICON_MAP.put("enum", "folder-enum");
        FOLDER_ICON_MAP.put("styles", "folder-css");
        FOLDER_ICON_MAP.put("style", "folder-css");
        FOLDER_ICON_MAP.put("css", "folder-css");
        FOLDER_ICON_MAP.put("scss", "folder-sass");
        FOLDER_ICON_MAP.put("sass", "folder-sass");
        FOLDER_ICON_MAP.put("less", "folder-less");
        FOLDER_ICON_MAP.put("i18n", "folder-i18n");
        FOLDER_ICON_MAP.put("locale", "folder-i18n");
        FOLDER_ICON_MAP.put("locales", "folder-i18n");
        FOLDER_ICON_MAP.put("lang", "folder-i18n");
        FOLDER_ICON_MAP.put("languages", "folder-i18n");
        FOLDER_ICON_MAP.put("translations", "folder-i18n");
        FOLDER_ICON_MAP.put("logs", "folder-log");
        FOLDER_ICON_MAP.put("log", "folder-log");
        FOLDER_ICON_MAP.put("debug", "folder-debug");
        FOLDER_ICON_MAP.put("backup", "folder-backup");
        FOLDER_ICON_MAP.put("backups", "folder-backup");
        FOLDER_ICON_MAP.put("archive", "folder-archive");
        FOLDER_ICON_MAP.put("archives", "folder-archive");
        FOLDER_ICON_MAP.put("examples", "folder-examples");
        FOLDER_ICON_MAP.put("example", "folder-examples");
        FOLDER_ICON_MAP.put("samples", "folder-examples");
        FOLDER_ICON_MAP.put("sample", "folder-examples");
        FOLDER_ICON_MAP.put("demo", "folder-examples");
        FOLDER_ICON_MAP.put("demos", "folder-examples");
        FOLDER_ICON_MAP.put("mock", "folder-mock");
        FOLDER_ICON_MAP.put("mocks", "folder-mock");
        FOLDER_ICON_MAP.put("__mocks__", "folder-mock");
        FOLDER_ICON_MAP.put("fixtures", "folder-mock");
    }
    
    // ==================== 文件夹名映射初始化 - 第3部分 ====================
    private static void initFolderIconMap3() {
        FOLDER_ICON_MAP.put("docker", "folder-docker");
        FOLDER_ICON_MAP.put(".docker", "folder-docker");
        FOLDER_ICON_MAP.put("kubernetes", "folder-kubernetes");
        FOLDER_ICON_MAP.put("k8s", "folder-kubernetes");
        FOLDER_ICON_MAP.put("helm", "folder-helm");
        FOLDER_ICON_MAP.put("terraform", "folder-terraform");
        FOLDER_ICON_MAP.put("ansible", "folder-ansible");
        FOLDER_ICON_MAP.put("ci", "folder-ci");
        FOLDER_ICON_MAP.put(".ci", "folder-ci");
        FOLDER_ICON_MAP.put(".circleci", "folder-circleci");
        FOLDER_ICON_MAP.put("workflows", "folder-gh-workflows");
        FOLDER_ICON_MAP.put(".husky", "folder-husky");
        FOLDER_ICON_MAP.put("husky", "folder-husky");
        FOLDER_ICON_MAP.put("functions", "folder-functions");
        FOLDER_ICON_MAP.put("function", "folder-functions");
        FOLDER_ICON_MAP.put("lambda", "folder-functions");
        FOLDER_ICON_MAP.put("aws", "folder-aws");
        FOLDER_ICON_MAP.put(".aws", "folder-aws");
        FOLDER_ICON_MAP.put("azure", "folder-azure-pipelines");
        FOLDER_ICON_MAP.put("gcp", "folder-cloud-functions");
        FOLDER_ICON_MAP.put("firebase", "folder-firebase");
        FOLDER_ICON_MAP.put("supabase", "folder-supabase");
        FOLDER_ICON_MAP.put("prisma", "folder-prisma");
        FOLDER_ICON_MAP.put("graphql", "folder-graphql");
        FOLDER_ICON_MAP.put("proto", "folder-proto");
        FOLDER_ICON_MAP.put("protos", "folder-proto");
        FOLDER_ICON_MAP.put("protobuf", "folder-proto");
        FOLDER_ICON_MAP.put("python", "folder-python");
        FOLDER_ICON_MAP.put("rust", "folder-rust");
        FOLDER_ICON_MAP.put("go", "folder-src");
        FOLDER_ICON_MAP.put("dart", "folder-dart");
        FOLDER_ICON_MAP.put("flutter", "folder-flutter");
        FOLDER_ICON_MAP.put("react", "folder-react-components");
        FOLDER_ICON_MAP.put("vue", "folder-vue");
        FOLDER_ICON_MAP.put("angular", "folder-angular");
        FOLDER_ICON_MAP.put("svelte", "folder-svelte");
        FOLDER_ICON_MAP.put("next", "folder-next");
        FOLDER_ICON_MAP.put(".next", "folder-next");
        FOLDER_ICON_MAP.put("nuxt", "folder-nuxt");
        FOLDER_ICON_MAP.put(".nuxt", "folder-nuxt");
        FOLDER_ICON_MAP.put("storybook", "folder-storybook");
        FOLDER_ICON_MAP.put(".storybook", "folder-storybook");
        FOLDER_ICON_MAP.put("cypress", "folder-cypress");
        FOLDER_ICON_MAP.put("coverage", "folder-coverage");
        FOLDER_ICON_MAP.put(".nyc_output", "folder-coverage");
        FOLDER_ICON_MAP.put("benchmark", "folder-benchmark");
        FOLDER_ICON_MAP.put("benchmarks", "folder-benchmark");
        FOLDER_ICON_MAP.put("perf", "folder-benchmark");
        FOLDER_ICON_MAP.put("security", "folder-secure");
        FOLDER_ICON_MAP.put("secure", "folder-secure");
        FOLDER_ICON_MAP.put("keys", "folder-keys");
        FOLDER_ICON_MAP.put("certs", "folder-keys");
        FOLDER_ICON_MAP.put("certificates", "folder-keys");
        FOLDER_ICON_MAP.put("secrets", "folder-keys");
        FOLDER_ICON_MAP.put("migrations", "folder-migrations");
        FOLDER_ICON_MAP.put("migration", "folder-migrations");
        FOLDER_ICON_MAP.put("seeds", "folder-seeders");
        FOLDER_ICON_MAP.put("seeders", "folder-seeders");
        FOLDER_ICON_MAP.put("main", "folder-src");
        FOLDER_ICON_MAP.put(".akode", "folder-config");
        FOLDER_ICON_MAP.put(".clangd", "folder-config");
        FOLDER_ICON_MAP.put("cmake", "folder-config");
        FOLDER_ICON_MAP.put("CMakeFiles", "folder-dist");
    }

    
    /**
     * 获取文件图标 Drawable
     */
    public static Drawable getFileIconDrawable(Context context, File file, int sizeDp) {
        if (file == null) return null;
        
        String iconName = file.isDirectory() ? getFolderIconName(file.getName()) : getFileIconName(file.getName());
        return loadSvgIcon(context, iconName, sizeDp);
    }
    
    /**
     * 获取文件图标 Drawable（支持头文件目录和项目目录检测）
     * @param context 上下文
     * @param file 文件
     * @param sizeDp 图标大小
     * @param headerDirs 头文件目录集合（可为null）
     * @param checkProject 是否检测项目目录
     */
    public static Drawable getFileIconDrawable(Context context, File file, int sizeDp, 
                                                Set<String> headerDirs, boolean checkProject) {
        if (file == null) return null;
        
        String iconName;
        if (file.isDirectory()) {
            // 检查是否是头文件目录
            if (headerDirs != null && headerDirs.contains(file.getAbsolutePath())) {
                iconName = "folder-include";
            }
            // 检查是否是项目目录
            else if (checkProject && isProjectFolder(file)) {
                iconName = "folder-project";
            }
            else {
                iconName = getFolderIconName(file.getName());
            }
        } else {
            iconName = getFileIconName(file.getName());
        }
        return loadSvgIcon(context, iconName, sizeDp);
    }
    
    /**
     * 检查是否是项目文件夹
     * 项目文件夹包含以下任一文件：CMakeLists.txt, Makefile, .git, build.gradle, package.json 等
     */
    public static boolean isProjectFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return false;
        
        String[] projectIndicators = {
            "CMakeLists.txt",
            "Makefile",
            "makefile",
            "GNUmakefile",
            ".git",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "pom.xml",
            "package.json",
            "Cargo.toml",
            "go.mod",
            "pubspec.yaml",
            "setup.py",
            "pyproject.toml",
            "Gemfile",
            "build.xml",
            ".project",
            "meson.build",
            "BUILD",
            "WORKSPACE",
            "compile_commands.json",
            ".akode"
        };
        
        for (String indicator : projectIndicators) {
            File indicatorFile = new File(folder, indicator);
            if (indicatorFile.exists()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 根据文件名获取图标名
     */
    public static String getFileIconName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "document";
        }
        
        String lowerName = fileName.toLowerCase();
        
        // 先检查特殊文件名
        if (NAME_ICON_MAP.containsKey(lowerName)) {
            return NAME_ICON_MAP.get(lowerName);
        }
        
        // 再检查扩展名
        String ext = getExtension(lowerName);
        if (ext != null && EXT_ICON_MAP.containsKey(ext)) {
            return EXT_ICON_MAP.get(ext);
        }
        
        // 默认图标
        return "document";
    }
    
    /**
     * 根据文件夹名获取图标名
     */
    public static String getFolderIconName(String folderName) {
        if (folderName == null || folderName.isEmpty()) {
            return "folder-base"; // 使用 folder-base 作为默认文件夹图标
        }
        
        String lowerName = folderName.toLowerCase();
        
        if (FOLDER_ICON_MAP.containsKey(lowerName)) {
            return FOLDER_ICON_MAP.get(lowerName);
        }
        
        return "folder-base"; // 使用 folder-base 作为默认文件夹图标
    }
    
    /**
     * 加载 SVG 图标
     */
    public static Drawable loadSvgIcon(Context context, String iconName, int sizeDp) {
        if (iconName == null) iconName = "document";
        
        String cacheKey = iconName + "_" + sizeDp;
        Drawable cached = iconCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        try {
            // 从 assets/icons 目录加载 SVG
            String svgPath = iconName + ".svg";
            InputStream is = context.getAssets().open("icons/" + svgPath);
            SVG svg = SVG.getFromInputStream(is);
            is.close();
            
            float density = context.getResources().getDisplayMetrics().density;
            int sizePx = (int) (sizeDp * density);
            
            svg.setDocumentWidth(sizePx);
            svg.setDocumentHeight(sizePx);
            
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.renderToCanvas(canvas);
            
            Drawable drawable = new BitmapDrawable(context.getResources(), bitmap);
            iconCache.put(cacheKey, drawable);
            return drawable;
        } catch (Exception e) {
            // 尝试加载备用图标
            if (!iconName.equals("document") && !iconName.equals("folder-base")) {
                // 根据图标名判断是文件还是文件夹
                String fallback = iconName.startsWith("folder-") ? "folder-base" : "document";
                return loadSvgIcon(context, fallback, sizeDp);
            }
            // 加载失败，返回 null，让调用方使用默认图标
            return null;
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return null;
    }
    
    /**
     * 检查是否有对应的自定义图标
     */
    public static boolean hasCustomIcon(File file) {
        if (file == null) return false;
        
        if (file.isDirectory()) {
            String lowerName = file.getName().toLowerCase();
            return FOLDER_ICON_MAP.containsKey(lowerName);
        }
        
        String fileName = file.getName().toLowerCase();
        if (NAME_ICON_MAP.containsKey(fileName)) {
            return true;
        }
        
        String ext = getExtension(fileName);
        return ext != null && EXT_ICON_MAP.containsKey(ext);
    }
    
    /**
     * 清除图标缓存
     */
    public static void clearCache() {
        iconCache.evictAll();
    }
    
    /**
     * 兼容旧接口 - 获取文件图标资源ID（返回默认图标）
     */
    public static int getFileIcon(File file) {
        return file != null && file.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file;
    }
    
    /**
     * 兼容旧接口 - 获取文件图标资源ID
     */
    public static int getFileIcon(String fileName) {
        return R.drawable.ic_file;
    }
    
    /**
     * 兼容旧接口 - 获取文件夹图标资源ID
     */
    public static int getFolderIcon(String folderName) {
        return R.drawable.ic_folder;
    }
}
