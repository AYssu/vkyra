# 维护与升级手册

本手册面向接手代码和审查 PR 的维护者。环境准备、克隆和基础命令见 [README](../README.md)，提交流程见 [CONTRIBUTING](../CONTRIBUTING.md)。升级记录、验证结果与已知限制应随 PR 公开，不能把本地分析资料当作贡献者的必需输入。

## 必须保留的兼容性

- `applicationId` 为 `com.vkyra.code`。变更会影响数据目录、升级安装、权限和外部引用，须单独迁移。
- `minSdk=26`、`targetSdk=28`。现有内置编译器从应用数据目录执行，不能因为 lint 或模板建议直接提高 target。compileSdk 可独立评估升级，但不是免测试变更。
- 保留 `com.termux.terminal`、`com.termux.view`、`com.termux.shared` 的 Java/JNI 关系。
- 继续读取既有 `.akode` 项目元数据，保留用户源文件、编译选项、插件包格式、终端环境和设置。迁移应可回退，并验证旧数据。
- Debug 保持无额外混淆；Release 只使用标准 R8 和资源缩减。密码学、凭据保护与 APK 签名是产品功能，不属于需要删除的字符串混淆。

## 修改与验证顺序

1. 阅读实际实现与相关测试，记录当前行为；先复现问题，再决定改动层。
2. 明确失败、取消、恢复和旧数据的预期。搬包、库迁移和行为修复尽量分开。
3. 实现最小完整改动，补能在旧实现上失败的回归测试。
4. 执行 JVM、lint、双 ABI Debug/Release 构建，检查失败和跳过数量。
5. 涉及 Android 行为时，在独占 AVD 验证页面与生命周期；涉及原生资源另做 ARM64 运行验证。
6. 检查实际经 R8 处理的包、第三方声明和文档，再接受审查。没有测试的环境标为未验证。

## 依赖升级

主要依赖版本位于 `gradle/libs.versions.toml`；部分直接声明保留在模块 `build.gradle`。更新时同时检查，避免同一库的核心和扩展模块版本漂移。Gradle Wrapper 位于 `gradle/wrapper/gradle-wrapper.properties`，SDK/NDK 配置在 `gradle.properties` 和应用构建文件中。

| 组件 | 当前维护边界 |
| --- | --- |
| Sora `0.24.7` | Editor 与 language-textmate 必须同版；检查分析器、补全、诊断、括号、资源与 keep 规则。当前没有交互式折叠，不能凭折叠区域接口宣称实现了折叠。 |
| LSP4J `1.0.0` | Java/Android 可用 API、反射 DTO、Gson、UTF-16 位置、超时取消和进程清理均需验证。 |
| JGit `5.13.3.202401111512-r` | 当前 Android 兼容版本；升大版本先核查 Java/Android API 与 desugaring，验证真实传输、凭据 origin、重定向和资源关闭。 |
| Markwon `4.6.2` | core、扩展和 termux-shared 使用的模块协调升级，验证真实 Markdown 页面。 |
| `androidx.window:window:1.0.0-alpha09` | Termux `ViewUtils` 有既有 API 耦合；先适配消费者再升版。 |
| termux-shared 的 `commons-io:2.5` | 有历史兼容约束注释。当前 minSdk 26 也不意味着可以直接删约束，须核查真实消费者和设备表现。 |
| AGP / Gradle / JDK / NDK | 作为兼容组合升级，覆盖 ndkBuild、Lint、变体输出、R8、双 ABI 与 CI。 |

升级 PR 应记录官方发布说明或源代码依据、选择版本的原因、兼容性上限与回退方法。依赖机器人 PR 是待审查变更，不是自动合并许可。不要通过删除真实 clangd 测试或扩大 `dontwarn` 来绕开兼容问题。

## Termux 源码同步

这三个模块携带项目修改，不能用上游目录整体覆盖：

1. 固定要同步的上游仓库与 commit/tag，检查对应组件许可证和变更说明。
2. 对 `terminal-emulator`、`terminal-view`、`termux-shared` 分别做 diff，区分上游修复、应用定制和本项目进程桥接。
3. 逐项合并并保留 JNI 名称、PTY 所有权、Unicode 参数/环境、受控管道进程、取消与回收语义。
4. 运行终端单元测试、两种 ABI 原生构建，以及真实键盘输入、输出、调整窗口、前后台切换和关闭会话测试。
5. 核查最终 APK/导出模板是否仍含另一份终端实现；主工程更新不会自动修复预编译模板中的代码。
6. 在 PR 记录上游基线、采纳的修复和未合并项，避免下一位维护者再次猜测版本。

## 内置工具链与大型资源

构建 APK 所需的宿主 NDK 与运行在 Android 上的内置 Clang/zsh/build-tools 不同。常规 PR 直接使用仓库提供的 Git LFS 归档，不需要重新交叉编译 LLVM。

替换资源时：

1. 分别准备 `app/src/aarch64/assets/`、`app/src/x86_64/assets/` 下与现有命名、目录结构对应的归档。
2. 记录上游源码 revision、构建选项、构建环境、补丁、许可证、源代码获取方式和 SHA-256。只有二进制能运行不代表满足再分发要求。
3. 阅读 `ClangInstaller`、`BuildToolsInstaller`、`ZshInstaller` 及 `ToolchainArchives` 的布局/必需文件校验；Clang 升级还需同步 `app/src/main/assets/clang-version` 和资源目录逻辑。
4. 验证干净安装、已有版本升级、损坏包、取消、磁盘失败与事务恢复，确保失败保留旧安装。
5. 在两种实际 ABI 上运行编译、链接、格式化、clangd 初始化/诊断/语义补全、Unicode 工作目录和终端。检查系统版本边界与动态库依赖。
6. 用 `git lfs fsck` 确认对象完整，检查 `.gitattributes`；不要把二进制归档改为普通 Git 大文件。

`app/src/main/assets/demo.apk` 是导出 APK 的运行模板，不是可随意清理的构建缓存。更换前核查来源、许可证、配套源码与补丁；验证最终导出 APK 的安装、签名、真实 stdout、前后台/旋转、冷启动与版本升级。主应用单元测试不能证明模板内部代码无缺陷。当前仓库并不宣称可从公开步骤完整重建所有预编译资源。

来源状态记录在 [`distribution/manifest.json`](../distribution/manifest.json)，检查入口和补齐要求见 [发行资料检查说明](../distribution/README.md)。一般代码 CI 执行 `python3 distribution/check.py`；正式发行执行 `python3 distribution/check.py --release`，实际许可归属、完整声明或许可证要求的对应源码未补齐时失败。精确 revision、可复现配方等 `maintenanceItems` 不阻塞发行；Apache-2.0/LLVM 等许可不能被一概解释为要求公开修改源码。第三方声明是当前盘点，不能当作已核清全部传递依赖的完整 SBOM。修改资源时更新实际证据和 hash，不能只把状态改为完成。

## R8 与签名

Release 在 `app/build.gradle` 开启 `minifyEnabled` 与 `shrinkResources`，规则在 `app/proguard-rules.pro`。不引入 StringFog、BlackObfuscator 或替代字符串加密插件。R8 是压缩优化与重命名工具，不是安全边界。

反射 DTO、Gson 持久化字段、LSP4J、JNI、XML inflate 的组件和按名称访问的资源需要有依据的保留规则。调整规则后检查 `app/build/outputs/mapping/<variant>/mapping.txt`，使用真实 Release 包做页面和编译运行验证；不要只测试 Debug，也不要通过关闭所有收缩来让验证通过。

升级 ARSCLib 时还须检查其是否携带 Android/XML 平台 API 的重复定义。`ARSCLib 1.3.8` 携带 `AttributeSet`、`XmlResourceParser` 和四个 `org.xmlpull.v1` API；真实 Release 验证发现重命名 `AttributeSet` 会改变 `LayoutInflater.Factory2` 回调签名，导致页面启动失败。现有 R8 规则定向保留这六个 API 的身份和方法签名，生产优化仍然开启。升级后重新核对包内容、mapping 与实际 Release 页面行为，不要以 Debug 正常代替验证，也不要仅为消除错误扩大为整库无条件保留。

`assemble*Release` 在未配置签名时生成未签名包，不能直接视为可安装的正式发行包。可选签名配置为本机忽略的 `keystore.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`），或环境变量 `VKYRA_KEYSTORE_FILE`、`VKYRA_KEYSTORE_PASSWORD`、`VKYRA_KEY_ALIAS`、`VKYRA_KEY_PASSWORD`；部分配置缺失应立即失败，不能悄悄降级为其他证书。证书和密码不得提交。对外发布必须使用维护者管理的稳定发行证书；更换证书会影响更新身份验证，不能用临时测试证书代替。

Debug 完整 suite 使用应用原始 instrumentation（`com.vkyra.code.test`）。Release 的两个 `ReleaseSmokeTest` 用例通过 manifest 组件、公共 UI 和资源名验证收缩后的 APK，由 `:release-smoke:assembleDebug` 构建的独立驱动 `com.vkyra.code.release.test` 运行。此模块只复用现有测试源码，自带 AndroidX Test/Kotlin 测试运行时，不依赖 `app`；不能为测试方便关闭生产 R8 优化或保留全部 Kotlin 库。

驱动通过生成的单文件源码目录复用 `ReleaseSmokeTest.java`，不提交第二份测试源码。Instrumentation 与目标共享类加载环境，升级 AndroidX/Kotlin 时须比较双方 runtime 依赖图，同步驱动中的 Lifecycle、Kotlin、协程、注解和 tracing 版本约束；依赖独立打包不等于这些共享 ABI 可以任意混用。约束细节见 [测试驱动说明](../release-smoke/README.md)，更新后分别验证 API 26/30/37 的两个 Release 用例，并保留实际结果。

若在本地运行，需要用独立驱动所用的开发证书签名一份 Release 副本，先以同证书 Debug 安装并准备内置工具链，再以 `adb install -r` 替换成该 Release，安装独立驱动并验证已安装 `base.apk` 与待测包一致后运行指定测试类。该驱动是测试 APK，不在正式发行中分发。保存以下证据：

- 原始 Release、签名副本、实际安装 APK 的 SHA-256 和证书摘要。
- 签名前后除签名元数据外内容未变，manifest 中无 debuggable 标记。
- 该次构建的 mapping 文件和 SHA-256。
- 指定测试类实际执行的测试名称、结果、设备/ABI/API 与日志。

驱动输出为 `release-smoke/build/outputs/apk/debug/release-smoke-debug.apk`，因 manifest 设置 `testOnly=true`，安装其同证书测试签名副本时需要 `adb install -t`。运行组件为 `com.vkyra.code.release.test/androidx.test.runner.AndroidJUnitRunner`，目标仍是 `com.vkyra.code`。公共 CI 脚本已经处理签名、安装、hash 和两个用例的完成检查；详见 [测试驱动说明](../release-smoke/README.md)。

这种本地测试签名仅服务于验证；它不创建正式发行身份。

## 配置更新与发布

`VersionChecker` 读取 Gradle 属性 `vkyra.updateRepository`，格式严格为 `owner/repository`。空值表示不请求更新，不要为了消除空配置而填写无关仓库：

```bash
./gradlew :app:assembleAarch64Release :app:assembleX86_64Release \
  -Pvkyra.updateRepository=你的GitHub账号/你的仓库名
```

发布前检查：

- `app/build.gradle` 的 `versionName` 符合 SemVer，`versionCode` 高于已发布版本。
- 标签与版本一致，两个 ABI 的资源齐全；命名保留 `aarch64`/`arm64` 或 `x86_64`，让更新选择器识别。
- 所有 required checks 通过，变化涉及的设备、拒权、离线、生命周期、旧数据与 Release 验证有证据。
- 正式签名与已安装发行版兼容；APK 签名、包名、版本、校验值正确。
- 第三方版权、许可证、二进制许可归属，以及按许可证要求提供的对应源码/补丁已核对，实际再分发阻塞项已关闭。
- 先创建草稿 Release，附变更说明、适用 ABI、已知限制和校验值，维护者审核后再发布。

GitHub 自动化、管理员分支设置及签名配置见 [GitHub 设置手册](GITHUB_SETUP.md) 和仓库工作流。CI 构建产物、手动验证和正式 Release 应明确区分；未实际运行的步骤不能写成通过。

## 常见问题

| 现象 | 优先检查 |
| --- | --- |
| 解压失败、资源很小、首次工具安装失败 | Git LFS 是否已拉取真实对象，归档是否损坏或 ABI 放错 |
| 主机测试找不到 clangd 或发生跳过 | `VKYRA_HOST_CLANGD` 是否指向可执行的主机 clangd，而不是 Android 归档里的二进制 |
| ndkBuild 找不到 NDK | SDK 目录、NDK 精确版本、环境覆盖 `JITPACK_NDK_VERSION` |
| UI 测试在工具缺失或权限弹窗处失败 | 先安装 Debug 与工具链，确认独占 AVD、普通存储权限和 API 版本 |
| Release 才崩溃 | 对应构建的 mapping、反射/JNI/序列化规则、资源收缩与实际签名包 |
| clangd 有跳转但缺少语义补全 | 真实 PCH/临时文件错误、应用缓存可写性、编译参数，不要把词法候选当作语义结果 |
| 更新检查没有网络请求 | 是否仍保持默认空仓库；配置格式、Release 标签、ABI 和 HTTPS 资源是否符合选择器要求 |
