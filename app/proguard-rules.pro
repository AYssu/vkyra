# Vkyra — standard R8 compatibility rules
# Release uses AGP R8 optimization/shrinking; debug remains unminified.
# Keep reflection, JNI, persisted schemas and established library integration seams.

# ARSCLib 1.3.8 bundles these Android boot-classpath API definitions. Preserve
# their identities and method descriptors: renaming AttributeSet changes the
# LayoutInflater.Factory2 callback signature and crashes even a normal launch.
# Android resolves the actual implementations from its boot class loader.
-keep interface android.util.AttributeSet { *; }
-keep interface android.content.res.XmlResourceParser { *; }
-keep interface org.xmlpull.v1.XmlPullParser { *; }
-keep interface org.xmlpull.v1.XmlSerializer { *; }
-keep class org.xmlpull.v1.XmlPullParserFactory { *; }
-keep class org.xmlpull.v1.XmlPullParserException { *; }

# ===========================================
# 基本配置
# ===========================================


# 保留行号信息，便于调试
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留泛型信息
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# 保留异常信息
-keepattributes Exceptions

# ===========================================
# Android 系统类保护（完全保护）
# ===========================================

# 保护所有Activity、Service等系统组件及其所有成员
-keep public class * extends android.app.Activity {
    *;
}
-keep public class * extends android.app.Application {
    *;
}
-keep public class * extends android.app.Service {
    *;
}
-keep public class * extends android.content.BroadcastReceiver {
    *;
}
-keep public class * extends android.content.ContentProvider {
    *;
}
-keep public class * extends androidx.fragment.app.Fragment {
    *;
}
-keep public class * extends android.app.Fragment {
    *;
}

# 保护View相关类及其所有成员
-keep public class * extends android.view.View {
    *;
}
-keep public class * extends android.view.ViewGroup {
    *;
}

# 保护Parcelable和Serializable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ===========================================
# 完全保护的应用类（不混淆）
# ===========================================

# 崩溃处理相关 - 防止LayoutInflater.Factory2接口问题
-keep class com.vkyra.code.crash.CrashActivity { *; }
-keep class com.vkyra.code.crash.CrashActivity$* { *; }

# APK签名和解析相关 - 防止反射和文件操作失败
-keep class com.vkyra.code.signature.** { *; }

# 编辑器相关 - 包含内部类和复杂逻辑
-keep class com.vkyra.code.editor.EditorActivity { *; }
-keep class com.vkyra.code.editor.EditorActivity$* { *; }
-keep class com.vkyra.code.editor.CppLanguage { *; }
-keep class com.vkyra.code.editor.MakefileLanguage { *; }
-keep class com.vkyra.code.editor.JsonLanguage { *; }
-keep class com.vkyra.code.editor.CppColorScheme { *; }
-keep class com.vkyra.code.editor.CppLightColorScheme { *; }
-keep class com.vkyra.code.files.FileAdapter { *; }
-keep class com.vkyra.code.files.FileAdapter$* { *; }

# 安装器相关 - 涉及文件操作和反射
-keep class com.vkyra.code.toolchain.ClangInstaller { *; }
-keep class com.vkyra.code.toolchain.ClangInstaller$* { *; }
-keep class com.vkyra.code.toolchain.GccInstaller { *; }
-keep class com.vkyra.code.toolchain.GccInstaller$* { *; }
-keep class com.vkyra.code.toolchain.BuildToolsInstaller { *; }
-keep class com.vkyra.code.toolchain.BuildToolsInstaller$* { *; }
-keep class com.vkyra.code.toolchain.ZshInstaller { *; }
-keep class com.vkyra.code.toolchain.ZshInstaller$* { *; }

# 插件相关类
-keep class com.vkyra.code.plugin.PluginInfo { *; }
-keep class com.vkyra.code.plugin.PluginAdapter { *; }
-keep class com.vkyra.code.plugin.PluginAdapter$* { *; }
-keep class com.vkyra.code.plugin.PluginManager { *; }
-keep class com.vkyra.code.plugin.PluginInstallScript { *; }
-keep class com.vkyra.code.plugin.PluginInstallScript$* { *; }
-keep class com.vkyra.code.plugin.PluginHookManager { *; }
-keep class com.vkyra.code.plugin.PluginHookManager$* { *; }

# 编译器和格式化相关类
-keep class com.vkyra.code.toolchain.CppCompiler { *; }
-keep class com.vkyra.code.toolchain.ClangFormatter { *; }
-keep class com.vkyra.code.files.RootFileUtils { *; }

# 自定义View和复杂UI组件
-keep class com.vkyra.code.ui.ExpandableFabMenu { *; }
-keep class com.vkyra.code.ui.ExpandableFabMenu$* { *; }

# 项目管理相关
-keep class com.vkyra.code.project.RecentProjectManager { *; }
-keep class com.vkyra.code.project.RecentProjectManager$* { *; }

# Markdown和侧边栏
-keep class com.vkyra.code.ui.MarkdownUtils { *; }
-keep class com.vkyra.code.ui.MarkdownUtils$* { *; }


# 设置相关类
-keep class com.vkyra.code.settings.SettingsActivity { *; }
-keep class com.vkyra.code.settings.SettingsActivity$* { *; }

# ===========================================
# ===========================================


# ===========================================
# 第三方库完全保护（不混淆）
# ===========================================

# Terminal相关库
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keep class com.termux.shared.** { *; }

# libsu - Root库
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Sora Editor - 代码编辑器
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# AndroidX和Material Design - 完整保护
-keep class androidx.** { *; }
-keepclassmembers class androidx.** { *; }
-keepclasseswithmembers class androidx.** { *; }
-dontwarn androidx.**

-keep class com.google.android.material.** { *; }
-keepclassmembers class com.google.android.material.** { *; }
-keepclasseswithmembers class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# 特别保护 LayoutInflater.Factory2 相关
-keep class * implements android.view.LayoutInflater$Factory { *; }
-keep class * implements android.view.LayoutInflater$Factory2 { *; }
-keepclassmembers class * implements android.view.LayoutInflater$Factory { *; }
-keepclassmembers class * implements android.view.LayoutInflater$Factory2 { *; }

# 保护 AppCompat 相关的 Factory - 这是关键！
-keep class androidx.appcompat.** { *; }
-keepclassmembers class androidx.appcompat.** { *; }
-keep class androidx.appcompat.app.AppCompatViewInflater { *; }
-keep class androidx.appcompat.app.AppCompatDelegateImpl { *; }
-keep class androidx.appcompat.app.AppCompatDelegateImpl$* { *; }
-keep class androidx.appcompat.app.AppCompatActivity { *; }

# 保护 Material Components 的 Factory
-keep class com.google.android.material.theme.MaterialComponentsViewInflater { *; }

# 保护所有AppCompatActivity的子类
-keep class * extends androidx.appcompat.app.AppCompatActivity { *; }
-keepclassmembers class * extends androidx.appcompat.app.AppCompatActivity { *; }

# Markwon Markdown渲染库
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**

# APK解析和签名相关库
-keep class net.dongliu.apk.parser.** { *; }
-dontwarn net.dongliu.apk.parser.**
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**

# 加密库
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ZIP和压缩库
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# HTTP客户端
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# JGit库
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# SLF4J日志库
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# 崩溃捕获库
-keep class cat.ereza.customactivityoncrash.** { *; }
-dontwarn cat.ereza.customactivityoncrash.**


# ZXing二维码库
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# AndroidSVG库
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# ===========================================
# 反射和注解保护
# ===========================================

# 保护所有被@Keep注解的类和方法
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 保护RecyclerView相关
-keep class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder { *; }
-keep class * extends androidx.recyclerview.widget.RecyclerView$Adapter { *; }

# 保护JNI方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保护枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保护R类
-keepclassmembers class **.R$* {
    public static <fields>;
}

# 保护自定义View
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
    *** get*();
}

# App-facing library/reflection seams above are deliberately conservative.
# Do not blanket-keep all interfaces, implementations, constructors or public
# methods: R8 may shrink, optimize and rename ordinary reachable code.

# ===========================================
# 警告抑制
# ===========================================

# 抑制常见警告
-dontwarn android.support.**
-dontwarn com.google.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# Java SE类警告抑制（Android不支持）
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn org.ietf.jgss.**

# Checker Framework注解警告抑制（R8生成）
-dontwarn afu.org.checkerframework.checker.formatter.qual.ConversionCategory
-dontwarn afu.org.checkerframework.checker.formatter.qual.ReturnsFormat
-dontwarn afu.org.checkerframework.checker.nullness.qual.EnsuresNonNull
-dontwarn afu.org.checkerframework.checker.regex.qual.Regex
-dontwarn afu.org.checkerframework.dataflow.qual.Pure
-dontwarn afu.org.checkerframework.dataflow.qual.SideEffectFree
-dontwarn afu.org.checkerframework.framework.qual.EnsuresQualifierIf
-dontwarn afu.org.checkerframework.framework.qual.EnsuresQualifiersIf
-dontwarn org.checkerframework.checker.formatter.qual.ConversionCategory
-dontwarn org.checkerframework.checker.formatter.qual.ReturnsFormat
-dontwarn org.checkerframework.checker.nullness.qual.EnsuresNonNull
-dontwarn org.checkerframework.checker.regex.qual.Regex
-dontwarn org.checkerframework.dataflow.qual.Pure
-dontwarn org.checkerframework.dataflow.qual.SideEffectFree
-dontwarn org.checkerframework.framework.qual.EnsuresQualifierIf
# LSP4J JSON-RPC endpoints and typed DTOs are accessed through reflection.
-keep class org.eclipse.lsp4j.** { *; }
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
# Session snapshots must retain their versioned JSON field names.
-keep class com.vkyra.code.editor.EditorDocument { *; }
-keep class com.vkyra.code.editor.EditorSessionStore$Snapshot { *; }

# Managed pipe subprocess bridge is loaded by name for host/Android portability.
-keep class com.termux.terminal.PipeProcess { *; }
-keep class com.vkyra.code.update.UpdateReleaseSelector$Release { *; }
-keep class com.vkyra.code.update.UpdateReleaseSelector$Asset { *; }
