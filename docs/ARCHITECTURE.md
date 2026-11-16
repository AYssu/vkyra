# 架构说明

Vkyra 是 Java Android 应用，使用按功能分包的四个产品模块，另有独立的 `release-smoke` 测试驱动模块。应用的 C/C++ 编译器是 APK 内携带、安装后运行的 Android 原生程序；它和构建 APK 时使用的 Android NDK 是两套工具链。

## 模块依赖

```text
app
├── terminal-emulator
├── terminal-view ── terminal-emulator
└── termux-shared ── terminal-view
```

| 模块 | namespace | 职责 |
| --- | --- | --- |
| `app` | `com.vkyra.code` | 编辑器、项目、工具链、插件、Git、设置和 APK 导出 |
| `terminal-emulator` | `com.termux.terminal` | 终端仿真、PTY、JNI 与受控管道进程 |
| `terminal-view` | `com.termux.view` | 终端 Android View、输入与渲染 |
| `termux-shared` | `com.termux.shared` | 终端与应用共用的 Android 工具 |
| `release-smoke` | `com.vkyra.code.release.test` | 独立 Release 黑盒测试驱动，仅测试使用，不参与产品依赖链 |

`release-smoke` 不参与上述产品依赖链。它构建包名 `com.vkyra.code.release.test` 的测试驱动 APK，自带 AndroidX Test/Kotlin 测试运行时，复用现有 `ReleaseSmokeTest.java` 的两个黑盒用例。它通过 instrumentation 验证实际 R8 Release，不依赖 `app` 的类库；正式 APK 不引用或发布此模块。

终端模块是内嵌源码，不能靠更新 Maven 版本自动同步 Termux。其 Java 名称、JNI 符号和消费者规则应一起审查。

## 主应用功能包

源码根为 `app/src/main/java/com/vkyra/code/`，根目录只保留应用入口与日志封装。

| 包 | 维护入口与边界 |
| --- | --- |
| `home`、`project` | 首页、新建/最近项目、项目路径与权限交互 |
| `editor` | 编辑器 UI、文档模型、会话持久化和语言外观 |
| `editor/language` | Sora/TextMate 适配、词法快照、括号和补全提案 |
| `lsp` | LSP4J 通信、clangd 生命周期、编译数据库与文本编辑 |
| `files` | 文件浏览/变更、文本编码、原子保存、Root 命令与存储策略 |
| `git` | JGit 操作、远端身份、认证与传输 |
| `toolchain` | 编译选项、编译/格式化、自定义命令和各工具安装入口 |
| `installer` | 安装任务、归档验证、事务提交、取消和崩溃恢复 |
| `terminal` | 终端页面/会话、环境配置、进程执行与取消 |
| `plugin` | 插件模型、解压、安装事务、启用与钩子 |
| `signature`、`sharing` | APK 模板修改、导出任务、签名、校验和受限分享 |
| `update` | 发布选择、HTTPS 下载、APK 身份验证与更新 UI |
| `settings`、`crash`、`ui`、`http` | 偏好、错误页、共用 UI/Markdown/SVG 和 HTTP 客户端 |

这是逐步拆分的现有应用，尚不是每个功能都具有独立 `data/domain/presentation` 层。`EditorActivity` 仍承担较多交互；新增业务应提取到有明确输入输出与资源所有者的类，而不是继续增加 Activity 静态依赖。

## 状态与数据流

### 编辑器

`EditorActivity` 将缓冲区与文档选择交给 `EditorSessionViewModel`。`EditorDocument` 持有稳定文档身份、路径、草稿和选择位置；`EditorSessionStore` 将多文档快照写入应用 `noBackupFilesDir/editor-sessions`，不把全文塞进 Bundle。恢复完成前应限制会覆盖草稿的操作。

写文件通过文件层保留编码、BOM、换行与原文件数据。关闭页面、导航和编译继续执行，以保存成功为前提。失败时显示错误并保留草稿；缺失原文件不能直接抹掉恢复副本。不同项目的会话保持隔离，另存为还需更新 LSP 文档身份。

### 语言服务

Sora 与 TextMate 提供编辑、高亮、括号和缩进信息；LSP4J 管理协议，`ClangdClient` 管理真实子进程和页面回调。词法补全候选不等同于 clangd 的语义补全，回归测试应区分二者。

`ClangdCompilationDatabase` 优先使用项目已有 `.clangd`、`compile_commands.json`、`compile_flags.txt` 或支持位置下的编译数据库。仅在没有项目配置时，生成应用缓存中的 fallback 数据库；不得改写用户配置。clangd 的临时目录由 `ClangdProcessEnvironment` 指向应用缓存，不能依赖 Android 上不存在的桌面 `/tmp`。

### 编译与进程

`CppCompiler` 和 `CustomCommandTask` 接收明确的文件、目录和命令配置。自定义任务使用有界工作队列和输出限制，取消后等待所属进程与管道清理，阻止销毁页面的迟到回调。`ManagedProcessRunner` 与 JNI 管道桥接维护进程/进程组身份；不能用全局 `pkill` 杀死同名进程。

交互终端的 PTY 会话由 `TerminalSessionViewModel` 维护。终端与普通后台命令的生命周期和输出消费者不同，不应互相复用没有所有权保证的静态进程。

### 安装、导出与网络

工具链、插件和 APK 输出均采用独立 staging、验证后提交、失败/取消清理的流程。工具链有安装事务日志与恢复路径；插件替换失败保留旧版本。插件脚本具有执行能力，当前架构不提供不可信插件沙箱。

Git 认证绑定真实远端 origin，变更远端后不能沿用不匹配的凭据。更新入口默认未配置；配置后仍需进行版本、ABI、HTTPS 地址和 APK 签名身份校验。APK 分享只授予指定文件的临时 URI 访问权。

## 资源与变体

```text
app/src/main/assets/          # 公共资源、TextMate、字体、图标、demo.apk
app/src/aarch64/assets/       # ARM64 clang / zsh / build-tools 归档
app/src/x86_64/assets/        # x86_64 clang / zsh / build-tools 归档
terminal-emulator/src/main/jni/Android.mk
```

ABI 归档放在各自 flavor 中，合并 assets 不删除源码文件。`removeAllArchAssets=true` 会排除工具链归档，只适合明确知道后果的开发验证；不能将这样生成的包作为完整发行包。Git LFS 跟踪这些归档和导出模板。

## 测试边界

`app/src/test/` 包含纯 Java、Robolectric、真实主机 clangd、文件/Git/HTTP/子进程测试；`app/src/androidTest/` 验证真实页面、Android 权限、JNI、内置编译器与 APK 导出，使用原始 Debug instrumentation；终端协议单元测试在 `terminal-emulator/src/test/`。真正收缩后的 Release 使用 `release-smoke` 独立驱动再执行两个黑盒用例，避免其测试框架调用目标包已被 R8 重命名的 Kotlin 等实现。

请按修改的边界选择失败、取消、恢复、生命周期与 Unicode/大输入用例。x86_64 仪器测试不能代替 ARM64 运行验收，非 Root 模拟器不能代替 Root 文件操作，Activity recreation 也不能代替外部进程终止后的持久化恢复。
