# 历史 HTTP 诊断工具

从原 nova4 诊断目录迁入，源码保持不变。日常使用请优先安装 [App](../../app/README.md)。这两个程序在 Android 的 `app_process` 中运行，不是桌面应用，也不是 APK。

## 构建与 HTTP 对照

在仓库根目录执行：

```bash
bash app/prepare_deps.sh
bash tools/http-probe/build.sh
adb push tools/http-probe/build/GoogleHttpProbe.jar /data/local/tmp/nova4-http-probe.jar
adb shell 'CLASSPATH=/data/local/tmp/nova4-http-probe.jar app_process /system/bin GoogleHttpProbe follow direct default http://www.google.com'
adb shell 'CLASSPATH=/data/local/tmp/nova4-http-probe.jar app_process /system/bin GoogleHttpProbe follow proxy curl/8.0 http://www.google.com'
adb shell rm /data/local/tmp/nova4-http-probe.jar
```

若 ADB 不在 PATH，请使用 `tools/android-sdk/platform-tools/adb`。连接多台设备时指定 `adb -s <序列号>`。

`GoogleHttpProbe` 参数依次为：重定向模式（`follow` 才跟随）、代理模式（`proxy` 才显式使用 `127.0.0.1:2080`）、UA（`default` 保留默认值）、目标 URL。程序输出 HTTP 状态、最终地址和读取字节数；即使请求失败也可能以正常退出码结束，应查看输出中的异常和 `HTTP_STATUS`。

## 临时代理原型

`GoogleProbeProxy` 监听 `127.0.0.1:31808` 并转发到 `127.0.0.1:2080`，仅改写指定 Google HTTP 请求的 UA。它没有 App 的前台服务、健康检查和残留清理能力，不适合常驻使用，不要与 App 同时运行。

如需研究该原型，可上传并在前台运行，但这两步本身不会设置系统代理：

```bash
adb push tools/http-probe/build/GoogleProbeProxy.jar /data/local/tmp/nova4-google-probe-proxy.jar
adb shell 'CLASSPATH=/data/local/tmp/nova4-google-probe-proxy.jar app_process /system/bin GoogleProbeProxy'
```

若另行将系统代理指向此程序，结束前必须先恢复原代理设置。原本没有代理且当前值确为 `127.0.0.1:31808` 时，执行 `adb shell settings put global http_proxy :0`，确认清理生效后再结束程序；不要立即删除该设置键。进程不能跨重启自动恢复。
