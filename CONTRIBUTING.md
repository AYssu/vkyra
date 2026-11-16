# 参与 Vkyra

欢迎提交问题、文档修正、回归测试与代码。开始前请阅读 [README](README.md)、[架构说明](docs/ARCHITECTURE.md) 和 [维护手册](docs/MAINTENANCE.md)。公开文档就是贡献者的维护入口，不需要任何本地私有资料、维护者机器路径或签名密钥。

Vkyra 沿用 **GPL-3.0-only**。向主应用提交的贡献使用该许可；另有许可的组件遵循各自既有条款。提交前请确认你有权提供相关代码，并保留第三方版权与许可声明，详见 [许可说明](LICENSE.md)。

## 报告 Issue

在仓库 Issues 页面选择 Bug 或功能建议模板。提交前搜索已有问题。Bug 请提供：

- 应用版本、安装包来源、Android 版本、设备 ABI，以及是否 Root。
- 可以重复的最少操作步骤、预期结果与实际结果。
- 最小示例项目或不含敏感数据的源文件；文件问题应注明编码、换行和路径特点。
- 相关日志、截图或录屏；编译问题附命令、编译选项和完整错误文本。
- 回退到旧版后是否仍可复现，以及最近正常的版本（如果知道）。

发出前删除访问令牌、密码、私钥、个人文件路径和私有项目内容。涉及凭据泄露、任意文件写入等安全问题请遵循 [SECURITY.md](SECURITY.md)，不要公开漏洞细节。

## 提交 Pull Request

1. 对较大的行为或架构变更先开 Issue，说明目标、兼容性影响和验证方式。
2. Fork 仓库，从最新 `main` 创建分支，例如 `fix/editor-save-recovery`。
3. 每个 PR 聚焦一个问题；行为修改与大规模搬包、格式化分别提交。
4. 补上能复现原缺陷的测试，更新涉及的维护文档和第三方声明。
5. 完成本地验证，推送到自己的分支，然后向上游 `main` 发起 PR。
6. 在 PR 中写清问题、修改后的行为、验证命令与结果、未验证的环境，并关联 Issue，例如 `Closes #123`。
7. 等待 CI 和维护者审查；修订后再次验证。CI 成功不会自动授权合并。

```bash
git switch main
git pull --ff-only
git switch -c fix/editor-save-recovery
# 修改、验证后，仅暂存本次相关的文件。
git add <本次修改的文件>
git diff --cached
git commit -m "fix: preserve editor draft when saving fails"
git push -u origin fix/editor-save-recovery
```

提交消息推荐 `fix:`、`feat:`、`refactor:`、`test:`、`docs:`、`build:`、`ci:`。不要提交构建产物、运行日志、IDE 配置、密钥和本地 SDK 配置。新增大型运行资源须遵循 `.gitattributes`，并解释来源、许可与校验方法。

## 本地检查

先按照 README 下载 Git LFS 资源、安装 SDK/NDK/JDK。完整 JVM 测试需要真实主机 clangd：

```bash
git lfs pull
git lfs fsck
export VKYRA_HOST_CLANGD="$(command -v clangd)"
test -n "$VKYRA_HOST_CLANGD"
test -x "$VKYRA_HOST_CLANGD"
./gradlew :app:testX86_64DebugUnitTest :terminal-emulator:testDebugUnitTest
./gradlew :app:lintX86_64Debug
./gradlew :app:assembleAarch64Debug :app:assembleX86_64Debug
./gradlew :app:assembleAarch64Release :app:assembleX86_64Release
./gradlew :release-smoke:assembleDebug
```

测试报告位于各模块 `build/reports/tests/`；lint 报告位于 `app/build/reports/`。不要用排除测试、放宽断言或增加全局忽略规则使失败变绿。报告测试失败与跳过时，保留真实计数和原因。

### Android 页面与集成测试

建立专用于 Vkyra 的 **x86_64** AVD，推荐名称 `vkyra_test_only`。覆盖 API 26 与当前 Android 系统；存储权限改动另加 API 30。AVD 可在 Android Studio Device Manager 创建。测试可能安装应用、授权权限、创建文件并执行本地程序，不能使用日常设备或其他项目的模拟器。

先启动测试 AVD。下方示例的 serial 只是占位值，必须替换为该 AVD 的真实 serial，并先核实名称：

```bash
adb devices -l
export ANDROID_SERIAL=emulator-5580
adb -s "$ANDROID_SERIAL" emu avd name
./gradlew :app:assembleX86_64Debug :app:assembleX86_64DebugAndroidTest
adb -s "$ANDROID_SERIAL" install -r -g app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk
```

全套页面测试以前置工具链可用为条件，先单独运行真实工具链安装与验证，再运行完整 suite：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.vkyra.code.toolchain.ToolchainRuntimeTest \
  com.vkyra.code.test/androidx.test.runner.AndroidJUnitRunner

adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  com.vkyra.code.test/androidx.test.runner.AndroidJUnitRunner
```

必须检查输出中的 `FAILURES`、`INSTRUMENTATION_FAILED`、测试数量和跳过状态，不能只依据 `adb` 的退出码。正常完整 suite 中存储权限用例是已授权路径；拒权恢复需要在启动测试前撤销权限，再以 `phase=denied` 单独运行 `StoragePermissionDeniedTest`，并处理系统版本的权限组与重复拒绝状态。不要把正常路径的 PASS 记作拒权验收。

Debug 与真正经 R8 处理的 Release 应分别验证。Debug 完整 suite 使用 `com.vkyra.code.test`；Release 的两个 `ReleaseSmokeTest` 用例由独立的 `release-smoke` 测试驱动（`com.vkyra.code.release.test`）运行，通过公共页面和真实编译运行验证。执行前须使用与该驱动相同的测试证书签名 Release 副本，保留正式签名密钥隔离，并确认工具链已准备好。测试驱动自带运行时，不能直接把依赖 Debug 目标库的测试 APK 用于 R8 Release。操作细节和发布验证见 [维护手册](docs/MAINTENANCE.md)。

## 代码约束

- 主应用使用 Java，按功能包组织；新逻辑放在对应功能下，避免不断扩大 `EditorActivity`。
- 保留 `applicationId=com.vkyra.code`、`minSdk=26`、`targetSdk=28` 与 Termux JNI 命名空间。版本迁移需要单独设计和运行证明。
- UI 管导航、展示、权限；文件、Git、进程和持久化实现不依赖 Activity 静态方法。
- I/O、网络和等待进程不能阻塞主线程。后台任务须明确取消、关闭和页面销毁后的回调处理。
- 保存失败必须保留可编辑草稿；保留编码、BOM、换行、权限与旧数据兼容，不能先截断唯一副本。
- 凭据按真实网络 origin 使用；日志和测试夹具不能包含真实令牌。
- 插件和压缩包操作必须校验标识、路径、解压边界，替换失败能恢复旧版本。
- 新增或修改 keep 规则时说明反射/JNI/序列化依据，运行 Release 验证；不加入字符串加密或额外混淆插件。
- 依赖升级要核对官方变更和 Android 兼容性，遵循维护手册，不盲目批量升版。

## CI 与合并

提交和 PR 会触发仓库配置的 Actions。稳定的合并门禁名称为 **`CI required`**，它汇总主机检查和 API 26/30/37 仪器测试；查看失败 job 的原始日志与上传报告，先修复根因。第三方贡献者不需要任何发布 secret。工作流文件中的实际 job 范围是 CI 覆盖边界，文档、Release 构建或单元测试成功不能证明所有页面和设备都正常。

准备好 SDK、Git LFS 和 `VKYRA_HOST_CLANGD` 后，可直接运行主机检查入口：

```bash
bash scripts/ci/verify.sh
```

CI 自建 AVD 的安装与完整仪器/Release 检查入口见 [GitHub 设置手册](docs/GITHUB_SETUP.md)。这些脚本会核对专用 AVD 名称，不能将其他设备的 serial 传入代替。

涉及编辑器、终端、JNI、权限、工具链或 APK 导出的 PR，请补充目标设备、ABI、Android 版本与人工/仪器验证结果。维护者仍需审查行为与兼容性；分支保护和 required checks 必须由仓库管理员在 GitHub 配置，提交一个 YAML 文件不会自动开启这些设置。
