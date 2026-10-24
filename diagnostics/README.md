# nova4 诊断资料

本目录整理自原 `~/nova4-diagnostics`。原目录保留不动；当前 App 使用方法以 [App README](../app/README.md) 为准。

## 可公开的历史证据

以下文件从 2026-10-24 的记录中原样复制，已逐个查看内容，并校验复制前后 SHA-256：

| 文件 | 内容与边界 |
| --- | --- |
| [路径对照](evidence/google-phone-path-comparison.txt) | 默认 UA 经默认路径和显式 HTTP 代理均得到 404 |
| [UA 对照](evidence/google-phone-useragent-comparison.txt) | 改为 curl/8.0 后得到 200，再改回默认 UA 得到 404；实际使用的 UA 以当时测试记录为依据，文件中的 DEFAULT_USER_AGENT 是系统属性而非最终请求头 |
| [URL 对照](evidence/google-phone-url-comparison.txt) | /ncr 和 HTTPS 在该次测试中没有得到预期的 200 |
| [修复后状态](evidence/fix-current-state.txt) | 当时的本地代理、Wi-Fi 和 mIsGoogleConnectOk=true 状态快照 |

这些记录仅代表当时那台设备和网络。它们不证明当前 Google 响应仍相同，也不证明二步验证通知或 FCM 推送已修复。

## 诊断结论与阶段差异

原调查将登录时的 `HAware B uid` 日志与 GMS 的 `no network connection` 错误关联起来，并在设备提取的 PowerGenie 实现中找到 Google 连通状态触发 GMS 网络限制的路径。HTTP 对照后，临时 UA 改写代理曾使系统状态恢复为 true，随后封装为当前 App。

完整登录日志和反编译产物保存在下面的本地归档中，未作为公开仓库的完整可复核证据。另一版本的公开 `PGGoogleServicePolicy` 源码只能作为参考，不能等同于本机 Maple 系统服务的精确还原。

原始 `findings.txt`、`http-findings.txt`、`fix-status.txt` 分别记录不同阶段，包含“尚未实施”“尚未确认”等当时状态。旧 `fix-status.txt` 的回退示例曾要求写入 `:0` 后立即删除代理键，已被后续调查纠正：**当前做法是保留 `:0`，避免异步观察者竞争。** 不要照旧记录执行回退。

## 本地归档（不提交 Git）

`diagnostics/local/` 保存完整日志、设备提取物、反编译目录、原始阶段笔记、旧版总文档及工具缓存。里面可能含设备、网络、账户相关信息，因此整体忽略；设备 APK、VDEX、DEX、原生库和反编译源码也不作为本项目 Apache 许可证下的公开源码发布。

`local/migration-manifest.json` 记录迁入文件的源相对路径、当前路径及 SHA-256，用于核对迁移，不包含签名密钥。签名密钥、密码和 App 构建中间文件未迁入；当前工程 `app/` 中已有源码、资源、Manifest 和 APK 经比对复用，不覆盖新的构建脚本与文档。

本地工具归位如下：

- SDK Build Tools → `tools/android-sdk/build-tools/34.0.0/`。
- Android API 29 → `tools/android-sdk/platforms/android-29/`。
- JADX 和 vdexExtractor → `tools/vendor/`，保留各自原有许可文件，排除嵌套 `.git`。
- HTTP 诊断工具 → [tools/http-probe](../tools/http-probe/README.md)，提供源码及独立 DEX JAR 构建脚本。
- CDEX 转换源码 → [tools/cdex](../tools/cdex/README.md)。

SDK 和第三方工具均被忽略，不要求上传 GitHub。新克隆者可用 `app/prepare_deps.sh` 准备 App / HTTP 工具所需依赖；该脚本不下载反编译工具。
