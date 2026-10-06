# apk/archive — apk 侧的归档区

这里放**已被取代、但保留以备回溯**的产物（APK 与随包二进制）。规则与 `neo9-root/archive/` 一致：

- 归档 ≠ 源码：可复现的产物放这里，可编辑的源码留在 `apk/ksuonetap/`。
- 每条都写清"是什么 / 为什么归档 / 替代品在哪"，确认无用后可整体删除。

整理于 2026-09-26，2026-09-27 补充 ksud 条目。

## APK

| 文件 | 版本 | 大小 | md5 | 归档原因 | 现行替代 |
|---|---|---|---|---|---|
| `KSUOneTap-v1.0.5.apk` | versionCode 7 / `v1.0.5` | 17,432,562 B | `f62c2e116ebbfa2aafd8137eb5e92a77` | **真机验证过的那一版**（15.1.14.7 上跑通全流程，`screenshots/` 即其留档）。v1.0.6 目前只在打包/资产层验证过，留一份以便随时回滚 | `ksuonetap/out/KSUOneTap-v1.0.6.apk` |
| `KSUOneTap-1.0.0_beta1.apk` | versionCode 2 / `1.0.0_beta1` | 7,693,546 B | `c51d883fb86fd284d112e8968398947a` | v1.0.0 时期的 Java 版 App，UI 与部署逻辑均已重写 | `ksuonetap/out/KSUOneTap-v1.0.6.apk` |
| `ReSukiSU-manager-v4.2.0-rc3-35179.apk` | versionCode 35179 / `v4.2.0-rc3` | 9,383,458 B | `f7b80165e7e61e02c9e049793b3c5505` | 旧管理器：与驱动 `KSU_VERSION=35184` 差 5 个提交（35179 = `0e469895`）。2026-09-28 升到 35184，使 **manager/ksud/ko 三者同源** | `ksuonetap/assets/resukisu-manager.apk`（35184 / `v4.2.0-rc3`） |
| `ReSukiSU-manager-v4.2.0-rc1-35072.apk` | versionCode 35072 / `v4.2.0-rc1` | 14,344,327 B | `9aac989cddfbbc39bb854bb888df6e32` | KSU_VERSION 35072 阶段用的管理器，早于 35179 | `ksuonetap/assets/resukisu-manager.apk`（35184 / `v4.2.0-rc3`） |

## ksud（KSU 用户态；部署时推成设备上的 `/data/local/tmp/ksud`）

| 文件 | 版本串 | uapi | 大小 | 归档原因 | 现行替代 |
|---|---|---|---|---|---|
| `ksud-35072-g829f61fb` | `4.2.0-rc1-11-g829f61fb` | **2** | 5,127,424 B | 35072 阶段的 ksud（与上面那个 rc1 manager 配套） | `userspace/ksud` |
| `ksud-35140-gc04159fc` | `4.2.0-rc1-79-gc04159fc` | 4 | 8,235,808 B | 35140 阶段的 ksud。**2026-09-27 前一直躺在 `assets/ksud`**，而 .ko / manager 早已是 35179，属版本漂移 | `userspace/ksud`（35184 / `4.2.0-rc3-13-gfa8311f6`，uapi 4） |
| `ksud-35179-g0e469895` | `4.2.0-rc3-8-g0e469895` | 4 | 5,681,504 B | 35179 阶段的 ksud（与上面那份 35179 manager 配套）。2026-09-28 随 manager 一起升到 35184 | `userspace/ksud`（35184 / `4.2.0-rc3-13-gfa8311f6`，uapi 4） |

> **为什么 35140 / 35179 那两份一直没出事**：它们的 `uapi` 都是 **4**，与驱动侧
> `KERNEL_SU_UAPI_VERSION = 4`（`source/resukisu/uapi/supercall.h:20`）匹配，所以 ksud 的
> UAPI 校验过得去 —— 不是"没问题"，而是"恰好没踩到"。真正会炸的是旁边那份 **uapi 2**
> 的 35072 版：ksud 的校验是严格 `!=`，会直接拒绝所有操作。
>
> **替换后的回滚路径**：若 35184 版 `ksud insmod` 出现异常，把
> `ksud-35179-g0e469895`（或更早的 `ksud-35140-gc04159fc`）复制回
> `apk/ksuonetap/assets/ksud` 与 `userspace/ksud` 即可 —— 两份必须一起换，
> 否则 `scripts/check-assets-sync.sh` 会报不一致。
>
> **换管理器为什么是安全的**：新 manager（35184）与旧版（35179）的**签名逐字节相同**
> （都是 ReSukiSU 官方证书 `d3469712…`），所以仍在驱动内嵌的签名白名单
> （`EXPECTED_HASH_RESUKISU`）里 —— 换版本不会让 ko 拒绝管理器。

**为什么不是直接删**：这些版本出现在 `docs/RESUKISU_VERSION.md` 的版本谱系与设备侧
验证记录里。保留一份可以在回溯"某个结论是在哪个版本上得到的"时对上手头的包。

清理方式：确认不再需要后，直接删掉本目录即可（不再有脚本引用这里的文件）。
