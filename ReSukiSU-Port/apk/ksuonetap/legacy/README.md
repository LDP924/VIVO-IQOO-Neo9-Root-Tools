# legacy — 已淘汰的构建方式

| 文件 | 说明 |
|---|---|
| `build_ksuonetap.ps1` | v1.0.0~v1.0.4 时期在 Windows 上用的构建脚本：`aapt2 + javac + d8 + apksigner`，路径写死 `D:\android-sdk` / `D:\payload-dumper-go\...` |

**为什么淘汰**：它用 `javac` 编译 **Java** 源码。当前工程已迁移到 **Kotlin**
(`src/**/*.kt`)，`javac` 无法编译 Kotlin，所以这份脚本对新源码无效。

现行构建方式：`bash build_ksuonetap.sh`（Linux/macOS，自动探测 SDK/JDK/kotlinc，
支持 `KEYSTORE=` 指定签名密钥）。见 `../README_KSUOneTap.md`。

其中一条仍值得保留的信息：它生成的 `ksuonetap.keystore`
（`-storepass ksupass -keypass ksupass -dname CN=KSUOneTap`）就是当前设备上已装
KSUOneTap 所用的签名密钥，证书 SHA-256 = `64572e28efd90f2cf8a68022319b7efbfe700ff8ff611efb30d980b989c38a08`。
想原地升级必须拿这个 keystore 重签。
