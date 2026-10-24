# Google 联网修复 1.2（nova4 专用）

## 使用方法

先启动 NekoBox，确保本地 HTTP 代理 `127.0.0.1:2080` 可用，再打开本应用。

应用通过本地 `31808` 代理改写 Google HTTP 检测请求的 User-Agent，确认真实 HTTP 200 且正文至少三个字符后设置系统代理并重连 Wi-Fi。

1.2 的转发规则：

- `http://www.google.com:80`（包括省略默认端口的写法）：UA 改为 `curl/8.0`，经 NekoBox `2080` HTTP 入口转发。
- Google 检测跳转使用的 `http://www.google.com.hk:80`：保留 UA，仍经 `2080` 转发，避免本机直连分支触发的验证码/429 回归。
- 其他 HTTP：保留 UA，直接建立目标连接，连接和 DNS 绑定当前 VPN 网络；不再经过 `2080` 入口。
- HTTPS CONNECT：直接建立目标 TCP 隧道，连接和 DNS 绑定当前 VPN 网络，不改 UA、不解密 TLS。

所有遵循系统代理的请求仍会经过本应用。“其他请求走 VPN”不等于丢弃请求；请勿在 NekoBox 中排除本应用，并确保其流量按预期走 VPN 出口。应用会检查默认网络是否为 VPN，VPN 消失或上游入口不可达时尝试停止并清理代理；该检查不保证 VPN 提供者或每条分流规则正确。

并发上限 **128 条客户端连接**，监听 backlog 请求值 **32**，应用内等待队列 **32**。满额后接收线程等待队列空位，系统监听队列继续承接新连接；队列均耗尽时仍可能超时。CONNECT 通常需要两条传输线程，但不是 256 条客户端连接。

本版本在按 Home、返回、切到其他应用或移除界面任务时，均不主动停止前台服务；通知栏显示运行状态。

停止时，点击应用内“停止并恢复网络，退出”或通知栏“停止并恢复网络”。应用先尝试清理系统代理，随后停止本地监听。

## 首次授权

首次安装需要电脑通过 ADB 授权：

```bash
adb shell pm grant cn.kingour.googleprobe android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant cn.kingour.googleprobe android.permission.DUMP
```

## 系统检测状态

已验证的华为系统中，SELinux 阻止普通应用直接读取 `powergenius`，即使 DUMP 已授权，应用仍可能显示 `unknown`。它不是检测失败的证明。

可通过电脑读取真实值，查找 `mIsGoogleConnectOk`：

```bash
adb shell dumpsys powergenius DeviceStateService
```

## 停止与异常恢复

前台服务不能保证抵抗强行停止或系统杀进程。强停可能遗留代理，请重新打开应用并点击停止。重启恢复接收器也会尝试清理，不自动开启修复；重启恢复尚未实机验证。

确认系统代理为本应用的 `127.0.0.1:31808` 后，可通过 ADB 紧急清理：

```bash
adb shell settings put global http_proxy :0
```

不要立即删除 `http_proxy` 键。不要在应用运行时卸载；请先停止修复。

完整的适用范围、安装与构建说明见[项目 README](../README.md)。

## 下载构建依赖

只安装 APK 时不需要下载编译依赖；以下步骤用于从源码构建。无需安装 Android Studio 或 Gradle。

准备脚本支持 macOS（Intel / Apple Silicon）及 Linux x86_64（glibc），需要本机已有 `bash`、`curl`、`python3`、`unzip`、`tar`；构建还需要 `zip` 和 `openssl`。Windows 请在 Linux x86_64 的 WSL 环境中运行。Apple Silicon 上的 Android Build Tools 34 可能需要 Rosetta 2，脚本会运行 `aapt2` 检查是否可执行。

在**仓库根目录**执行，代理地址填写电脑上的实际入口：

```bash
HTTPS_PROXY=http://127.0.0.1:7897 bash app/prepare_deps.sh
```

也可使用 SOCKS5：

```bash
ALL_PROXY=socks5://127.0.0.1:7897 bash app/prepare_deps.sh
```

脚本兼容大小写代理环境变量，优先级为 HTTPS、ALL、HTTP（同类小写优先），并将代理显式传给 `curl` 和 `sdkmanager`。当前支持无认证、显式端口的 HTTP / SOCKS5 代理，不支持带账号密码或 `https://` 协议的代理入口。`HTTPS_PROXY=http://...` 表示通过 HTTP 代理下载 HTTPS 资源，是有效配置。没有代理时可直接运行 `bash app/prepare_deps.sh`。

默认会提示确认 Android SDK 条款，并通过 `sdkmanager --licenses` 展示许可证。阅读并同意 [Android SDK 条款](https://developer.android.com/studio#terms) 后，无人值守运行可显式使用：

```bash
HTTPS_PROXY=http://127.0.0.1:7897 bash app/prepare_deps.sh --accept-licenses
```

脚本下载以下依赖，均保存在仓库的 `tools/` 下，不修改系统 Java 或 SDK：

| 依赖 | 来源与版本 | 本地目录 |
| --- | --- | --- |
| JDK | Eclipse Temurin 最新 JDK 17 补丁版，Adoptium API | `tools/jdk/` |
| Android Command-line Tools | Google，固定 build 11076708 | `tools/android-sdk/cmdline-tools/11076708/` |
| Android 平台文件 | API 29 | `tools/android-sdk/platforms/android-29/` |
| Android Build Tools | 34.0.0 | `tools/android-sdk/build-tools/34.0.0/` |
| ADB 等设备工具 | 当前稳定版 platform-tools | `tools/android-sdk/platform-tools/` |

JDK 压缩包按 Adoptium API 返回的 SHA-256 校验；Command-line Tools 按脚本固定的 SHA-256 校验；其余 SDK 包由 `sdkmanager` 下载管理。JDK 补丁版和 platform-tools 未锁定具体版本。已存在的本地 JDK 和命令行工具会复用，SDK 包由 `sdkmanager` 检查是否已安装。下载失败会退出并清理临时文件，可排除网络问题后重试；若提示已有不完整目录，请先将提示的目录移走再重试。

完成后构建：

```bash
bash app/build.sh
```

构建脚本自动使用本地 JDK 和标准 SDK 目录，输出 `app/Google联网修复.apk`。ADB 不会加入系统 PATH，可直接执行：

```bash
tools/android-sdk/platform-tools/adb devices
```

`tools/jdk/` 和 `tools/android-sdk/` 已加入 `.gitignore`。准备脚本不会生成或上传签名材料；构建签名说明见[项目 README](../README.md)。依赖下载需要电脑能访问 Google、Adoptium 及其 GitHub 下载地址，手机上的代理不会自动成为电脑代理。

## 本地代理回归测试

在仓库根目录执行 `bash app/test.sh`。测试使用本机回环网络和模拟上游，不访问 Google、不需要连接手机，覆盖定向 UA 改写、其他 HTTP 直连、CONNECT 字节隧道及连接排队/停止。

## 1.2 验证记录（2026-10-24）

- 8 项本机回环网络测试通过：Google UA 改写、香港站保持 UA、其他 HTTP 直连、CONNECT 字节转发、半关闭响应、错误请求恢复、128 条连接及第 129 条等待恢复、满额停止清理。
- APK v2/v3 签名验证通过，已使用原签名在 nova4 上覆盖安装，保留数据及授权。
- 初版 1.2 将香港站重定向请求改为 VPN 直连，原生网络库对照得到验证码/429；同一设备的 1.1 全转发路径得到 200。将 `www.google.com.hk:80` 恢复为经 NekoBox HTTP 入口转发且保留 UA 后，App 日志记录 `HTTP probe status=200 finalHost=www.google.com.hk`，系统代理启用，用户确认恢复。
- 只记录固定检测请求的最终状态和主机，不记录查询参数、请求头、响应正文或账户信息。

当前限制：VPN DNS 多地址结果仅尝试首个地址，尚未实现 IPv4/IPv6 多地址故障回退。排队容量和短时间实机验证不代表长期后台保活或任意负载均可靠。
