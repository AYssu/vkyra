# GitHub 仓库与自动化设置

公开文档、Issue/PR 模板和 Actions 随代码提交；分支保护、审核者、发布密钥和私密漏洞报告属于 GitHub 仓库设置，必须由管理员在真实仓库中配置。克隆仓库不会继承这些服务端设置。

## 首次建立仓库

GitHub CLI（`gh`）是维护者可选工具，不是应用或贡献者的运行依赖。也可以全部通过 GitHub 页面操作。以下命令中的 `OWNER/REPO` 必须替换为实际仓库，执行前核对可见性和目标：

```bash
gh auth login
gh auth status
git lfs install
git lfs fsck
git status --short
```

先审查待提交文件、版权与资源来源，完成公开仓库所需的许可资料，再建立和推送仓库。不要将本机配置、密钥或忽略目录强制加入版本控制。已有远端时不要重复创建；先用 `git remote -v` 核对。

```bash
# 仅当确实需要新建公开仓库时执行；不会自动提交本地工作区。
gh repo create OWNER/REPO --public --source=. --remote=origin
# 准备并审查首次提交后，再推送 main 与 Git LFS 对象。
git push -u origin main
```

默认 PR CI 可以检查代码。正式发布前须关闭实际的再分发要求，包括资产许可归属、完整声明及按许可证要求提供的对应源码；可复现配方或精确 revision 等单独记录为维护事项，不一概阻止发行。

## 自动检查的范围

代码推送至主分支或提交 PR 后，CI 执行主机检查与独立的 Android 模拟器验证。必需状态检查选 **`CI required`**，不要仅要求某个较快的构建 job：

| 层 | 检查内容 |
| --- | --- |
| 主机检查 | 资源/源码检查、真实主机 clangd 的 JVM 测试、终端测试、lint、双 ABI Debug、R8 Release、原始 Android 测试 APK 与独立 `release-smoke` 驱动构建 |
| API 26 / 30 / 37 | 每个 job 创建自己的 x86_64 AVD，安装并验证内置工具链，再用 `com.vkyra.code.test` 运行 Debug 完整仪器测试 |
| Release 运行 | 使用本地临时测试签名的真实 R8 Release，由独立 `com.vkyra.code.release.test` 驱动运行两个黑盒用例，验证页面、资源与编译后终端 stdout |
| `CI required` | 上述必需 job 全部成功才通过，失败或缺失不能当作成功 |

Fork PR 不需要发行密钥，不能得到发布权限。维护者应审查第一次贡献或工作流变更后再批准受限制的外部工作流执行，不要在 `pull_request_target` 中直接运行外部分支代码和 secrets。

### 缓存与重复下载

Gradle Wrapper、依赖与转换结果由 `setup-gradle` 缓存。主机 job 另缓存 Git LFS 对象和构建用 SDK/NDK；三个模拟器 job 分别缓存 emulator、platform-tools 与对应系统镜像，不安装构建用 NDK。命中 SDK 缓存时跳过安装，LFS 命中后仍执行对象完整性检查。

SDK 缓存按操作系统、CPU 架构、安装脚本和月份区分；构建 SDK 另包含 Gradle 属性，模拟器另包含 API。LFS key 取工具链与模板的指针文件内容，资产更新后重新缓存。首次运行、每月 SDK 缓存刷新、依赖调整或缓存被回收时仍需要下载。修改安装组件时同步修改缓存路径；手动刷新可删除相应 GitHub Actions cache。

只有 `main` 的 push 检查写入新增的共享缓存，PR 只读取；不缓存签名密钥、凭据、AVD 用户数据或测试结果。缓存用于减少下载，所有测试仍然执行。模拟器任务使用同一主机 job 上传的准确 APK 输入，不把旧构建产物当作验证结果。

`release-smoke` 自带 AndroidX Test/Kotlin 测试运行时，复用原有 `ReleaseSmokeTest.java`，不作为目标应用的依赖，也不随正式 APK 发布。这使测试框架不依赖 R8 已改名的目标库，生产优化保持启用。Debug 的完整 suite 和 Release 的两个黑盒用例是两条独立的验证路径。

当前自动验证仍有边界：x86_64 不能证明 ARM64 运行正常，正常权限路径不能证明真实拒权恢复，非 Root AVD 不能证明 Root 文件操作，模拟器也不能替代真实输入法、设备厂商行为和全部插件组合。影响这些边界的 PR 需要额外证据。

### 本地重现 CI

Linux 主机已安装 Java 17、Git LFS 和主机 clangd 时：

```bash
export VKYRA_HOST_CLANGD="$(command -v clangd)"
test -n "$VKYRA_HOST_CLANGD"
test -x "$VKYRA_HOST_CLANGD"
bash scripts/ci/install-sdk.sh build
bash scripts/ci/verify.sh
```

`install-sdk.sh` 使用配置的 Android SDK 目录安装固定版本组件。模拟器 job 的公开入口为：

```bash
bash scripts/ci/install-sdk.sh 30
# 先按工作流参数创建并启动名为 vkyra_ci_api30 的专用 AVD。
# 下面 serial 必须与实际启动端口对应；脚本会检查 AVD 身份。
python3 scripts/ci/emulator-smoke.py \
  --serial emulator-5556 --api 30 \
  --inputs build/ci-inputs --output build/ci-evidence
```

`build/ci-inputs` 由主机检查准备，不能拿其他版本的 APK、mapping 或测试包混用。模拟器具体启动参数与环境准备以 workflow 为准；脚本会安装应用、授权普通存储权限、运行原生程序和替换为测试签名 Release，只可用于脚本白名单中的专用 Vkyra AVD。重复使用自己的验收 AVD 时可传 `--test-keystore /本机/debug.keystore`，使用默认 alias `androiddebugkey`、密码 `android` 的开发证书保留原安装，不能传正式密钥。`run-emulator.sh` 仅用于一次性的 GitHub 托管 runner，本地不要伪造 `GITHUB_ACTIONS` 绕过限制。

### 获取测试包与日志

在仓库 **Actions → 对应 CI run → Artifacts** 下载构建产物和测试报告。CI APK 用于验证具体提交，不应以正式版本名对外散发；测试证书的包不一定能覆盖安装维护者正式签名的版本。报告 Bug 时附 run 地址、commit、job/API/ABI 和失败日志。

## main 分支保护

先让 CI 至少运行一次，让 GitHub 识别 `CI required`。在仓库 **Settings → Rules → Rulesets** 或 **Branches → Branch protection** 为默认分支配置：

1. 通过 PR 合并，至少 1 个合适的审核者批准；新提交使旧批准失效。
2. 要求 **`CI required`** 成功，合并前保持目标分支最新。
3. 开启解决所有审查讨论后才能合并，阻止强推和删除主分支。
4. 为 `.github/`、构建、签名、JNI 等敏感改动指定真实维护者，并要求 code owner 审查。
5. 检查管理员/指定角色的 bypass 范围；如果目标是贡献者不能直接合并 `main`，不要把 bypass 授予普通贡献者。

`CODEOWNERS` 需要实际有效且有相应访问权限的账号或团队；占位账号没有保护作用。贡献者可以通过 Fork 和 PR 参与，无需主仓库写权限。给协作者写权限后，更应检查实际 ruleset 是否生效。

仓库提供 [`scripts/github/configure-repository.py`](../scripts/github/configure-repository.py) 作为分支保护配置入口。前提是 `gh` 已登录、对真实仓库有管理员权限，且目标分支**当前 commit** 已有 GitHub Actions 的成功 `CI required` 检查。先只读预览并审查目标和保护内容，再执行写入：

```bash
python3 scripts/github/configure-repository.py OWNER/REPO --branch main --dry-run
python3 scripts/github/configure-repository.py OWNER/REPO --branch main
```

脚本保留或加强现有 required checks、审核人数和分支限制，默认要求管理员也遵守 PR 审核与最新 CI，禁止强推和删分支，并启用 Issues 与合并后删除功能分支。Vkyra 的 [CODEOWNERS](../.github/CODEOWNERS) 指定维护者 `@AYssu`；Fork 后应改成自己的真实维护者账号。

对于当前单维护者仓库，维护者选择保留管理员绕过权限，其他贡献者仍须通过 PR、成功 CI 和 code owner 审核。复用该选择时需显式传入以下选项：

```bash
python3 scripts/github/configure-repository.py OWNER/REPO --dry-run --allow-admin-bypass --require-code-owner-reviews
python3 scripts/github/configure-repository.py OWNER/REPO --allow-admin-bypass --require-code-owner-reviews
```

`--allow-admin-bypass` 适用于仓库管理员角色，不应把管理员权限授予普通贡献者；其他 required checks 和审查要求仍保留。私密漏洞报告与 `release` environment 分别配置。文件和命令存在不代表远端设置已经执行或生效，实际状态以 GitHub Settings/API 为准。

只有一名维护者时需明确处理本人 PR：作者不能批准自己的 PR。可以增加可信审核者；若选择保留管理员紧急绕过权限，需记录使用理由和检查结果，不要为一次合并长期关闭 CI 门禁。GitHub 的套餐和仓库可见性可能影响保护能力，管理员应在页面确认功能可用。

## 正式发布环境与签名

发布工作流只响应维护者的 tag/手动发布操作；先解析固定的源代码 commit 并执行发行资料检查，再对该 commit 运行完整 CI，签名前再次检查。版本标签使用 `v*` 并与应用版本一致。[发行检查](../distribution/README.md) 的 `python3 distribution/check.py --release` 当前因真实再分发要求未补齐而阻断，不能改成跳过来获得绿色发布；独立的维护跟踪项不阻塞发行。

在仓库 **Settings → Environments** 创建 **`release`**，配置可信发布审核者、限制可部署分支或标签，按团队人数决定是否允许发起者自批。再在该 environment 中配置以下 secrets：

| Secret | 内容 |
| --- | --- |
| `VKYRA_KEYSTORE_BASE64` | 正式 keystore 的 Base64 内容 |
| `VKYRA_KEYSTORE_PASSWORD` | keystore 密码 |
| `VKYRA_KEY_ALIAS` | 正式签名 alias |
| `VKYRA_KEY_PASSWORD` | alias 对应密钥密码 |

Base64 是传输编码，不是加密。不要把它写进仓库、Issue 或日志。使用 `gh` 时可通过标准输入设置，不要把密码作为命令行参数：

```bash
base64 < /安全位置/release.jks | tr -d '\r\n' | gh secret set VKYRA_KEYSTORE_BASE64 --env release --repo OWNER/REPO
gh secret set VKYRA_KEYSTORE_PASSWORD --env release --repo OWNER/REPO
gh secret set VKYRA_KEY_ALIAS --env release --repo OWNER/REPO
gh secret set VKYRA_KEY_PASSWORD --env release --repo OWNER/REPO
```

本地 Gradle 签名使用 keystore 文件路径 `VKYRA_KEYSTORE_FILE`。正式 CI 发布走另一条路径：[`sign-release.py`](../scripts/ci/sign-release.py) 严格解码单行 Base64 到临时 keystore，使用 `apksigner` 直接签署**同一次 CI 已验证的未签名 APK**，不重新运行 Gradle 编译。脚本核对 commit、APK 和 mapping 的 hash，并验证签名前后非签名内容一致；签名所需字段缺失应失败，不应转用调试密钥发布。随后创建草稿 Release，核对双 ABI APK、SHA-256、证书、版本和说明，再由维护者确认发布。

只把发行 secrets 放进受保护 environment；PR CI 无需它们。保存 keystore 的离线备份与密码恢复方案，不能依赖 GitHub secret 导出作为备份。正式发行前还需将应用更新仓库配置为正确的 `owner/repository`，详见 [维护手册](MAINTENANCE.md)。

## Issue、安全与依赖维护

- 确认 Issues 已启用，Bug/功能模板和 PR 模板可正常显示。
- 在 **Settings → Advanced Security** 启用 Private vulnerability reporting，确认 Security 页面出现私密报告入口；维护者流程见 [SECURITY](../SECURITY.md)。
- 保持 Actions 的默认 token 权限最小；发布 job 才需要写 Release 的权限，不向 Fork PR 发送写令牌或 secrets。
- 定期检查依赖更新、工作流所用 action 的固定 revision、SDK 镜像和 Git LFS 配额。不要自动合并未经 Android 兼容验证的依赖升级。
- 接受一次测试 PR，分别验证成功、测试失败以及工作流失败时 `CI required` 的状态，再开放贡献。

## 官方参考

- [GitHub CLI secret set](https://cli.github.com/manual/gh_secret_set)
- [受保护分支](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)
- [发布环境与审核规则](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [配置私密漏洞报告](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository)
