<p align="center">
  <img src="docs/assets/brand/logo.png" alt="Vkyra 紫色 V 形环绕标记与字标" width="200" />
</p>

<h1 align="center">Vkyra</h1>
<p align="center"><strong>C/C++ IDE · Powered by Clang</strong></p>
<p align="center">在 Android 上，编写、编译、运行你的 C/C++ 项目。</p>

<p align="center">
  <a href="https://github.com/AYssu/vkyra/actions/workflows/ci.yml"><img src="https://github.com/AYssu/vkyra/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-8B5CF6" alt="Android 8.0 及以上" />
  <img src="https://img.shields.io/badge/ABI-ARM64%20%7C%20x86__64-6D28D9" alt="ARM64 与 x86_64" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--only-6D28D9" alt="GPL-3.0-only" /></a>
</p>

<p align="center">
  <a href="#安装与开始使用">开始使用</a> ·
  <a href="#开发构建">源码构建</a> ·
  <a href="CONTRIBUTING.md">参与贡献</a> ·
  <a href="https://github.com/AYssu/vkyra/issues/new/choose">反馈问题</a>
</p>

<img align="right" src="docs/assets/brand/mascot.png" alt="戴眼镜和紫色耳机、抱着电脑打招呼的 Vkyra Q 版看板娘" width="24%" />

Vkyra 是一款面向 Android 的开源 C/C++ 开发环境，将代码编辑、Clang 工具链、终端和项目管理放在一起。从写下第一段程序，到编译运行、管理 Git 变更，都可以在手机上完成。

### 从编辑到运行

- **专注写代码**：Sora 编辑器、TextMate 高亮、多文档、搜索替换、括号匹配和缩进引导线。
- **让 Clang 提供支持**：clangd 补全、诊断与代码导航，配合编译和格式化，优先使用项目自己的编译数据库。
- **掌握开发流程**：项目文件操作、Git、终端、自定义编译命令，以及插件和 APK 导出签名。
- **接着上次的工作**：文档草稿恢复；工具链和插件采用事务式安装。

<br clear="right" />

## 安装与开始使用

支持 **Android 8.0（API 26）及以上**，应用包名为 `com.vkyra.code`。

| 安装包 | 适用设备 |
| --- | --- |
| `aarch64` | 普通 ARM64 Android 手机和平板 |
| `x86_64` | 对应架构设备与 Android 模拟器 |

1. 在 [Releases](https://github.com/AYssu/vkyra/releases) 查看正式发布；需要测试主分支时，前往 [Debug APK](https://github.com/AYssu/vkyra/actions/workflows/debug.yml) 的成功运行，在 **Artifacts → vkyra-debug-apks** 下载测试包。
2. 安装对应架构的 APK，首次打开时按提示安装内置工具链。
3. 创建或打开项目，编辑 C/C++ 文件，编译并运行。

Actions 产物是开发测试包，下载通常需要登录 GitHub。正式发布包以对应发布说明中的签名和校验值为准。

<details>
<summary>当前能力与使用说明</summary>

- 当前没有点击箭头收起函数或代码块的交互式折叠。
- 插件和终端命令能执行代码，请只运行自己信任的内容。
- 内置更新默认不绑定任何仓库；发布者配置仓库后才会请求更新。

</details>

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

先安装 Git LFS。Debian / Ubuntu 可使用 `sudo apt-get install git-lfs`，然后获取源码与工具链资源：

```bash
git lfs install
git clone https://github.com/AYssu/vkyra.git
cd vkyra
git lfs pull
git lfs fsck
```

### 构建 APK

请用 Android Studio 的 SDK Manager 安装 Android SDK Platform 37（SDK 包名 `platforms;android-37.0`）与 NDK `28.2.13676358`，设置本机 `ANDROID_HOME`，或在忽略的 `local.properties` 中填写 `sdk.dir`。Linux 主机上的完整测试还需要可执行的 `clangd`；构建应用本身不使用主机 clangd。

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
