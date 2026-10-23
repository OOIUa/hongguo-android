# 发布与在线更新

## 当前发布约定

- GitHub 仓库：[OOIUa/hongguo-android](https://github.com/OOIUa/hongguo-android)。
- 首个支持在线更新的版本：`0.3.0`，`versionCode=3`，tag 为 `v0.3.0`。
- 应用 ID：`com.hongguo.android`；当前最低系统为 API 26。
- 固定清单地址：`https://github.com/OOIUa/hongguo-android/releases/latest/download/update.json`。
- 应用启动时按 24 小时间隔静默检查，也提供按钮手动检查。下载与系统安装均需用户操作。

0.1.x / 0.2.x 用户需手动覆盖安装一次 0.3.0，才会获得后续在线更新入口。发布应使用既有相同签名，避免破坏现有安装与本机数据。

## 签名必须保持连续

当前构建沿用既有测试签名，密钥由发布机器本地保管。后续发布需使用同一个本地密钥，不能用另一台机器新生成的测试密钥代替。

`release-signing.sha256` 保存的是**签名证书的公开 SHA-256 指纹**。它可以随源码发布，但不能用于签名，也不是 APK 文件的 SHA-256。每次发布前用 Android SDK 的 `apksigner verify --print-certs <最终APK路径>` 读取证书指纹，与该文件核对；指纹变化时应停止发布并找回原密钥，而不是直接更新指纹文件掩盖变化。

不得将 `.keystore`、`.jks`、私钥、密码、GitHub 凭据、本机 `local.properties`、SDK 或构建缓存提交到仓库或上传为 release 附件。应在本地另行备份原密钥及必要访问资料。

若未来改用不同的正式签名，当前应用的签名校验和 Android 覆盖安装规则会阻止直接覆盖现有安装。需另行安排用户迁移；卸载后重装会删除设备身份、收藏、历史与应用私有缓存，不能将其描述为无损升级。本项目没有预置 CI 签名密钥。

## 构建与验证

在保有原签名密钥的机器上构建。例如发布 0.3.0：

```powershell
.\gradlew.bat -PappVersionCode=3 -PappVersionName=0.3.0 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

当前待发布 APK 来自 `app/build/outputs/apk/debug/app-debug.apk`。后续版本应严格增加 `appVersionCode`，例如 0.3.1 使用 4；不要通过提高 `versionName`、修改 tag 或替换清单来绕过 versionCode 检查。

发布前核对最终 APK 的真实包名、versionCode、versionName、minSdk 和签名证书。清单中的版本名称和最低系统版本必须与 APK 完全一致。若构建后又重新签名或改动 APK，必须重新计算文件大小及 SHA-256。

设备可用时还应运行 `:app:connectedDebugAndroidTest`，并验证同签名旧版本覆盖安装后的收藏、历史和续播数据。每次发布的实际测试结果记录在 `VALIDATION.md`，不要将 JVM 校验通过等同于真实系统安装已完成。

## 生成 update.json

清单 schemaVersion 固定为 1，包含以下字段：

| 字段 | 要求 |
| --- | --- |
| `schemaVersion` | 整数 `1` |
| `applicationId` | `com.hongguo.android` |
| `versionCode` | 正整数，且高于客户端已安装版本才会提示更新 |
| `versionName` | 与最终 APK 完全一致，仅用于显示 |
| `apkUrl` | 本仓库 `releases/download/<tag>/<APK文件名>` 下的 HTTPS 地址，不带账号、query 或 fragment |
| `sha256` | 最终 APK 文件的 64 位十六进制 SHA-256 |
| `sizeBytes` | 最终 APK 的真实字节数，最大 150 MiB（157286400 字节） |
| `minSdk` | 与 APK 的最低 Android API 一致；当前为 26 |
| `notes` | 字符串形式的更新说明 |
| `publishedAt` | 可选发布时间字符串，建议使用 UTC ISO 8601 |

`update.json` 不得超过 128 KiB。APK 地址不能使用 `latest/download` 或外部网盘地址，应明确指向本次 tag 的 APK，保证清单与安装包对应。

仓库提供 `scripts/prepare-release.ps1` 生成发布资产。先提交本次全部源码和文档，使 Git 工作区干净；确保 `ANDROID_HOME` 指向包含 Build Tools 36.0.0 的 Android SDK，然后从项目目录执行：

```powershell
pwsh -File scripts/prepare-release.ps1
```

脚本默认读取已构建的 `app/build/outputs/apk/debug/app-debug.apk`，从 APK 提取包名、版本和 minSdk，并核对签名指纹；源码有未提交修改或未跟踪文件时会停止。它把以下资产写入项目上级的 `../dist/`：

- `hongguo-android-<版本>.apk`：最终 APK。
- `hongguo-android-<版本>-source.zip`：由当前干净的 Git HEAD 通过 `git archive` 生成的对应源码。
- `update.json`：从实际 APK 提取的版本、大小、哈希和固定仓库下载地址。
- `SHA256SUMS-<版本>.txt`：APK、源码 ZIP 和清单的 SHA-256。

可通过 `-Apk`、`-OutputDirectory`、`-Repository` 显式调整输入和目标；本应用正式更新源保持 `OOIUa/hongguo-android`。后续发布还需更新脚本中的版本说明 `notes`，确认打包的源码 HEAD 与 APK 对应，再为该 HEAD 创建版本 tag。

上传附件名必须与清单中的 `apkUrl` 完全一致。生成发布资产后，不要对 APK 再次签名或修改；如有变化，应重新执行检查及资产生成。

## 上传到 GitHub Releases

1. 将本次对应的应用源码、Gradle wrapper、构建配置、许可证和改动说明提交至仓库，再创建对应 tag。
2. 在该 tag 创建 release，可先保留为草稿，上传脚本生成的 APK、源码 ZIP、`update.json` 和 `SHA256SUMS-<版本>.txt`。签名公开指纹与版本说明也可一并提供，私钥始终留在本地。
3. 确认该 tag 可以取得对应完整源码。应用按 GPL-3.0-only 分发，不能只上传 APK 而遗漏该版本的对应源码；来源和通知见 `THIRD_PARTY_NOTICES.md`。
4. 发布为**非草稿、非 prerelease** 的正式 release，并使其成为 Latest。首发使用 `v0.3.0`；prerelease 不用于本应用固定的 latest 清单入口。
5. 匿名访问固定的 `latest/download/update.json`，确认可经 HTTPS 重定向取得新清单；再校验清单指向的 tag、APK 文件名、真实文件大小及哈希。此过程不应需要 GitHub 账号或 token。
6. 在已装旧版本且签名一致的测试设备上，手动检查更新，核对显示版本、下载进度、安装来源权限、系统安装交接与安装后的数据保留。

清单和 APK 均走匿名 HTTPS。客户端只接受 GitHub 及其明确的 release asset 主机，支持必要的 HTTPS 重定向；任何 GitHub 认证都只用于发布机器上的上传操作，不能嵌入 APK 或 update.json。

## 发布后调整

清单若有文件名或哈希笔误，应先停止推广并更正清单；APK 字节一旦变化，必须同步更新大小与哈希。应用只接受更高 versionCode，不提供版本降级通道。需要修复已发布版本时，应发布新的更高版本，而不是让旧 versionCode 冒充更新。

不要向用户承诺自动下载或静默安装。应用只自动检查元数据，用户点按钮后才下载，并由系统安装界面最终确认。
