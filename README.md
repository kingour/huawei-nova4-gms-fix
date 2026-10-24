# Huawei nova4 GMS Fix

用于华为 nova4 的 Google 联网检测修复工具。通过本地 HTTP 转发代理调整 Google 检测请求的 User-Agent，尝试恢复系统对 Google 网络可用性的判断及 Google 账号登录流程。

当前 App 版本为 **1.2**（versionCode `3`），包名为 `cn.kingour.googleprobe`。无需 Root，首次使用需要通过 ADB 授权。本项目是针对特定设备故障的绕行方案，不负责安装 GMS，也不提供 VPN 或网络出口。

## 适用环境

原 README 记录的实机环境如下，其他设备和系统版本尚未验证：

| 项目 | 环境 |
| --- | --- |
| 设备 | 华为 nova4，VCE-AL00 |
| 系统 | Android 10，`VCE-AL00 10.0.0.166(C00E34R2P4)` |
| 上游代理 | NekoBox，提供 `127.0.0.1:2080` HTTP 代理入口 |
| 本应用监听 | `127.0.0.1:31808` |

Manifest 声明最低 Android 8.0（API 26）、目标 API 28；最低版本声明不代表已验证所有 Android 8.0 及以上设备。两个代理端口目前写在源码中，尚无界面配置。

## 工作原理

该设备曾出现 Google 网页可以访问，但系统 Google 连通性检测失败、GMS 登录立即提示无网络的情况。历史对照测试中，Android 默认 User-Agent 请求 `http://www.google.com` 最终返回 404，改为 `curl/8.0` 后返回真实 200；该结果可能随 Google 响应和网络环境变化。

```text
遵循系统 HTTP 代理的请求
    ↓ 系统代理 127.0.0.1:31808
本应用 LoopbackProxy
    ├─ www.google.com:80 HTTP → 改写 UA → NekoBox HTTP 入口 127.0.0.1:2080
    ├─ www.google.com.hk:80 HTTP → 保留 UA → 同一 NekoBox HTTP 入口
    └─ 其他 HTTP / HTTPS CONNECT → 建立目标 TCP 连接 → 当前 VPN 网络
NekoBox → 网络
```

- 仅对目标为 `http://www.google.com`、端口为 80 或未指定端口的请求，将 User-Agent 改为 `curl/8.0`。
- 为保持本机检测重定向链兼容，`http://www.google.com.hk:80` 也经 `2080` 转发，但不改 UA。该路径曾在直接连接时触发 Google 验证码/429，不能把它视为所有出口均不可用。
- 除上述两个主机外，其他 HTTP 请求直接连接目标，保留 UA，并将代理形式请求行转换为目标服务器需要的形式；不再转给 NekoBox 的本地 HTTP 入口。实现会调整连接管理头并移除代理认证头。
- HTTPS CONNECT 由本应用直接连接目标并双向传输字节，TLS 握手和验证由客户端完成，不解密 TLS，不伪造 Google 的成功响应。目标连接和 DNS 显式绑定当前 VPN，VPN 不可用时失败而不回退物理网络；依赖 NekoBox VPN 接管，不能将本应用排除在 VPN 之外。
- 启动和运行期间检查当前默认网络具有 VPN 传输能力及 NekoBox 上游端口；这不能验证 VPN 的提供者、分应用排除或分流出口。启动时先检查上游端口，再经本地代理验证 Google 返回 HTTP 200、正文至少三个字符，成功后才设置系统代理。
- 若 Wi-Fi 已开启，会短暂关闭并重新开启，以触发系统检测。

**这是系统全局 HTTP 代理设置，所有遵循它的应用请求仍会经过本应用，并非仅影响 GMS。** “其他请求走 VPN”表示本应用将目标连接和 DNS 绑定当前 VPN 网络，不是收到请求后直接丢弃或让客户端自动绕过代理。

1.2 将同时处理的客户端连接上限从 16 提高为 **128**，监听 backlog 请求值为 **32**，另有 **32** 个已接受连接的等待位置。工作连接和等待队列都满时，接收线程等待队列空位，后续连接在系统监听队列等待；不再因为线程池满而立即关闭新连接。系统 backlog 的实际长度由 Android 内核决定，全部等待位置耗尽时仍可能发生客户端超时，不提供无限排队。 不遵循系统代理的流量不在本工具控制范围内。

## 安装与授权

电脑需要可用的 ADB；手机需开启 USB 调试并允许电脑连接。以下命令在仓库根目录执行，默认仅连接一台设备；多设备时请为 ADB 指定 `-s <设备序列号>`。

安装仓库中的 [APK](app/Google联网修复.apk)：

```bash
adb devices
adb install -r "app/Google联网修复.apk"
adb shell pm grant cn.kingour.googleprobe android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant cn.kingour.googleprobe android.permission.DUMP
```

两项权限均为当前实现的启动前提：`WRITE_SECURE_SETTINGS` 用于写入和清理系统代理，`DUMP` 用于尝试读取系统诊断状态。卸载后重新安装需重新授权。

## 日常使用

1. 启动 NekoBox，确认 VPN 正常，且 `127.0.0.1:2080` 支持 HTTP 代理请求，仅有 SOCKS 入口不够。
2. 打开“Google 联网修复”，等待上游检查、HTTP 验证和 Wi-Fi 重连完成。重连会短暂中断网络。
3. 显示“修复代理已启用”或“Google 检测通过”后，可切换到 Google 登录。
4. 使用期间保持 NekoBox 运行。按 Home、返回或移除最近任务不会由本应用主动停止服务，但系统仍可能清理进程。
5. 结束时点击应用内“停止并恢复网络，退出”，或通知栏“停止并恢复网络”。**卸载前也应先停止修复。**

应用使用前台服务维持运行，重复打开时不重复启动已有修复会话。遇到其他系统代理或 PAC 配置时会拒绝覆盖；请先自行确认代理配置，勿同时运行占用 31808 的旧版程序。

### 为什么显示 unknown

普通应用可能受到华为 SELinux 限制，即使授予 DUMP 也无法读取 `powergenius`。此时界面显示 `mIsGoogleConnectOk = unknown`，它不表示 true 或 false；HTTP 200 也不能替代系统内部状态。

通过电脑读取实际状态：

```bash
adb shell 'dumpsys powergenius DeviceStateService | grep -E "mIsGoogleConnectOk|WifiConnected"'
```

“修复代理已启用”表示本地启动流程完成，登录是否恢复仍需实际验证。

## 停止与异常恢复

正常停止时，应用先尝试清理属于本应用的代理设置，再停止监听。运行期间会检查上游入口和本地监听；这不等于持续验证网络出口或 Google 登录状态。

强行停止、崩溃或系统杀进程可能跳过清理，留下指向已停止进程的系统代理。若出现 Wi-Fi 已连接但不能联网：

1. 重新打开应用，再点击停止按钮。
2. 若无法操作应用，确认当前系统代理确为本应用的 `127.0.0.1:31808` 后，通过 ADB 清理：

```bash
adb shell settings get global http_proxy
adb shell settings get global global_http_proxy_host
adb shell settings get global global_http_proxy_port

# 仅在确认要清除本应用代理时执行
adb shell settings put global http_proxy :0
```

清理后再次读取上述设置，确认代理已解除并测试普通联网，必要时重连 Wi-Fi。规范化主机和端口可能稍后才更新。不要立即删除 `http_proxy` 键，以免与系统异步设置观察者产生竞争。

开机和应用更新接收器会尝试清理自有残留代理，**不会自动启动修复或 NekoBox**。真实重启恢复尚未完成实机验证。关闭修复后，原先的 Google 检测故障仍可能复现。

## 工程结构

```text
.
├── LICENSE
├── README.md
├── diagnostics/              # 诊断索引、公开证据及被忽略的本地归档
├── tools/                    # HTTP/CDEX 辅助源码及被忽略的本地依赖
└── app/
    ├── AndroidManifest.xml
    ├── build.sh                 # 手动编译、打包及签名脚本
    ├── Google联网修复.apk       # 当前安装包
    ├── README.md                # App 使用说明
    ├── res/                     # 图标和主题
    └── src/cn/kingour/googleprobe/
        ├── MainActivity.java    # 界面和启动入口
        ├── ProbeService.java    # 前台服务、验证和状态轮询
        ├── LoopbackProxy.java   # HTTP 转发和 UA 改写
        ├── ProxySettings.java   # 系统代理设置与清理
        └── BootReceiver.java    # 开机、更新后的残留清理
```

本仓库包含 Android App 和历史 HTTP 诊断工具源码。[诊断资料索引](diagnostics/README.md) 整理了历史结论、可公开的 HTTP 对照记录及本地归档位置；原始设备日志、提取物与反编译产物仅在本地保留，不纳入 Git。

## 从源码构建

当前工程使用独立 Shell 脚本，未配置 Gradle。可通过 [prepare_deps.sh](app/prepare_deps.sh) 自动下载本地 JDK 17、Android SDK 工具、API 29 平台文件和 ADB，支持 HTTP / SOCKS5 代理：

```bash
# 在仓库根目录执行，替换为电脑上的代理地址
HTTPS_PROXY=http://127.0.0.1:7897 bash app/prepare_deps.sh
bash app/build.sh
```

依赖版本、系统要求、代理设置及许可证选项见 [App 构建依赖说明](app/README.md#下载构建依赖)。SDK 使用标准目录 `tools/android-sdk/`，JDK 位于 `tools/jdk/`；这些下载文件不纳入版本控制。构建以 Java 8 目标格式编译源码。

脚本会生成资源和 Java 字节码、转换 DEX、打包、对齐、签名，并执行签名验证；输出覆盖 `app/Google联网修复.apk`，中间文件位于 `app/build/`。

首次构建若没有 `app/signing/release.jks`，脚本会生成本地签名密钥及 `app/signing/password.txt`。请私下备份，后续覆盖更新必须使用相同签名；自行构建的签名可能与仓库 APK 不同，无法直接覆盖安装。卸载重装前先停止修复，重装后重新授权。

## 验证范围与限制

原项目记录在 2026-10-24 对 App 1.1 完成了构建和签名检查，并在上述 nova4 上验证启动、短时间切换后台、显式停止及再次启动，ADB 曾读取到 `mIsGoogleConnectOk=true`。上述 1.1 结果为历史记录；1.2 已完成本地回归、覆盖安装及启动恢复验证，详情见 [App 验证记录](app/README.md#12-验证记录2026-10-24)。部分 HTTP 对照和状态快照见[诊断资料](diagnostics/README.md)，完整原始日志仅本地归档。

- 不保证整夜待机、华为省电清理或长期无人值守可靠性。
- 最近任务移除、通知栏停止和真实重启恢复尚未分别完成实机操作验证。
- 历史登录恢复确认来自同原理的临时代理；App 1.1 的历史验证未重新走完整账号登录及二步验证流程。
- 不保证修复 Google 二步验证通知、FCM 推送、后台同步或所有 GMS 问题。
- Google 响应、系统版本或上游配置变化均可能使方案失效；端口目前固定。

## GitHub 发布注意

`.gitignore` 已排除本地签名材料、构建中间文件、APK 签名辅助文件、本地 SDK 和 OMX 状态。**不要提交 `app/signing/` 中的密钥或密码。** 发布前可执行 `git status --short --untracked-files=all` 核对待提交文件；APK 可随仓库保留，也可另行上传到 GitHub Releases。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
