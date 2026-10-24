# CDEX 转换辅助源码

`ConvertCdex.java` 从原诊断目录迁入，使用 JADX 所带 dexlib2 将 CDEX 转为标准 DEX。它是反编译调查辅助工具，不属于 App 构建依赖。

本地迁入的 JADX 1.5.6 位于 `tools/vendor/jadx/`，第三方工具目录不纳入 Git。若从 GitHub 克隆工程，需自行取得相应版本 JADX，并指定实际 JAR 路径。以仓库根目录为工作目录：

```bash
mkdir -p tools/cdex/build
javac -cp tools/vendor/jadx/lib/jadx-1.5.6-all.jar \
  -d tools/cdex/build tools/cdex/ConvertCdex.java
java -cp "tools/cdex/build:tools/vendor/jadx/lib/jadx-1.5.6-all.jar" \
  ConvertCdex /path/to/input.cdex /path/to/output.dex
```

源码使用 Java 11 及以上语法；可使用准备脚本下载的 `tools/jdk/bin/javac` 和 `tools/jdk/bin/java`。输入、输出参数必须提供，输入应为已提取的 CDEX。设备原始二进制和反编译结果仅保存在本地归档中。
