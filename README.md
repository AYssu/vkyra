# Vkyra

**C/C++ IDE · Powered by Clang**

Vkyra 是面向 Android 的 C/C++ 开发环境，提供代码编辑、clangd 语言服务、编译运行、终端、Git、插件与 APK 导出。应用包名为 `com.vkyra.code`，支持 Android 8.0（API 26）及以上设备，提供 ARM64 与 x86_64 两种安装包。

## 主要功能

- Sora 编辑器与 TextMate 高亮：C/C++、JSON、Makefile，支持多文档、搜索替换、括号匹配和缩进引导线。
- Clang/clangd：编译、格式化、诊断、补全及代码导航；项目自己的编译数据库优先。
- 项目文件操作、Git 仓库操作、终端与自定义编译命令。
- 文档草稿恢复、事务式工具链与插件安装、APK 导出和签名。

当前编辑器没有点击箭头收起函数或代码块的交互式折叠。插件和终端命令能执行代码，应只运行自己信任的内容。内置更新默认不绑定任何仓库；发布者配置仓库后才会请求更新。

## 安装

普通 ARM64 手机选择 `aarch64` 安装包，x86_64 模拟器选择 `x86_64`。仓库的 Actions 构建产物是开发测试包；正式发布包以维护者在 Releases 中说明的签名和校验值为准。

首次运行时按应用提示安装内置工具链。

## 开发构建

### 环境要求

| 项目 | 仓库配置 |
| --- | --- |
| JDK | 17 |
| Gradle | 9.4.1，使用仓库自带 Wrapper |
| Android Gradle Plugin | 9.2.1 |
| compileSdk / minSdk / targetSdk | 37 / 26 / 28 |
| Android NDK | 28.2.13676358 |
| 原生构建 | `ndkBuild`，不是 CMake |
| 大文件 | Git LFS，包含两种 ABI 的工具链和导出模板 |

### 获取源码

先安装 Git LFS。Debian / Ubuntu 可使用 `sudo apt-get install git-lfs`。从仓库页面复制 clone 地址后执行：

```bash
git lfs install
git clone <仓库的克隆地址> vkyra
cd vkyra
git lfs pull
git lfs fsck
```

### 构建 APK

请用 Android Studio 的 SDK Manager 安装 Android SDK Platform 37 与 NDK `28.2.13676358`，设置本机 `ANDROID_HOME`，或在忽略的 `local.properties` 中填写 `sdk.dir`。Linux 主机上的完整测试还需要可执行的 `clangd`；构建应用本身不使用主机 clangd。

```bash
./gradlew :app:assembleAarch64Debug :app:assembleX86_64Debug
```

输出目录：

```text
app/build/outputs/apk/aarch64/debug/app-aarch64-debug.apk
app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
```

若下载大文件受到 Git LFS 配额或网络限制，请先解决资源下载问题；LFS 指针文件不能当作工具链打包使用。GitHub 的源码 ZIP 不应作为验证 LFS 下载完整性的依据。

## 验证改动

准备好 SDK 和 Git LFS 资源后，Linux 开发机安装主机 `clangd`（Debian / Ubuntu 可使用 `sudo apt-get install clangd`），再运行与 CI 相同的主机检查：

```bash
export VKYRA_HOST_CLANGD="$(command -v clangd)"
test -n "$VKYRA_HOST_CLANGD"
test -x "$VKYRA_HOST_CLANGD"
bash scripts/ci/verify.sh
```

单独运行测试、lint、双 ABI 构建及 Android 页面测试的步骤见 [贡献指南](CONTRIBUTING.md#本地检查)。页面和工具链测试需要专用模拟器；CI 的覆盖范围与配置见 [GitHub 设置手册](docs/GITHUB_SETUP.md)。

Debug 保持可读，Release 使用标准 R8 收缩和资源缩减。正式签名与发布验证见 [维护手册](docs/MAINTENANCE.md)。

## 参与贡献与维护

- [贡献指南](CONTRIBUTING.md)：报告问题、提交 PR、本地验证和审查要求。
- [架构说明](docs/ARCHITECTURE.md)：模块、功能包、状态和资源所有权。
- [维护与升级手册](docs/MAINTENANCE.md)：依赖升级、Termux 同步、工具链替换和发布。
- [GitHub 设置手册](docs/GITHUB_SETUP.md)：自动检查、分支保护、发布环境和 GitHub CLI。
- [安全报告](SECURITY.md)：敏感问题的报告方式和支持范围。

## 许可与来源

Vkyra 沿用 **GNU GPL v3 only（GPL-3.0-only）**。仓库保留 Termux 与其他组件的既有许可要求。请阅读 [LICENSE](LICENSE)、[许可说明](LICENSE.md) 和 [第三方声明](THIRD_PARTY_NOTICES.md)，以及各组件内的原始许可证；开源应用不意味着可以移除第三方版权声明。

内置预编译工具由项目维护者编译提供，上游来源与许可见 [第三方声明](THIRD_PARTY_NOTICES.md) 和 [许可来源记录](licenses/SOURCES.json)。发行资料的维护要求见 [发行资料检查](distribution/README.md)。
