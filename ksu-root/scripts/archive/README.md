# scripts/archive — 构建/集成脚本归档区

这里放**已被取代、但保留以备回溯**的脚本。规则与 `neo9-root/archive/` 一致：
每个文件都写清"是什么 / 为什么归档 / 替代品在哪"，确认无用后可整体删除。

整理于 2026-09-27。这一批都是 **2026-08-19** 的早期版本 —— 那时 KSU 集成还走
「rootd 文件队列 + rootc 客户端」的老链路，脚本自然分成一堆单步工具；现在构建入口
已经收敛到仓库根的 `./build.sh`。

## 内核侧（编 kernelsu-vivo.ko）

| 文件 | 是什么 | 为什么归档 | 现行替代 |
|---|---|---|---|
| `build_ksu.sh` | 最早的模块编译脚本 | **LTO 关闭**，产出有 struct module 布局 bug（加载即崩） | `scripts/build_ksu_lto2.sh`（LTO+CFI，产出当前可用的 .ko）/ `scripts/build_ksu_module.sh` |
| `build_ksu2.sh` | 上面那版的小改 | 同上 | 同上 |
| `setup_kernel_env.sh` | 准备内核源码树（下载/解包 vivo 源码） | 一次性环境准备，源码树已就位 | 手写；流程见 `docs/KERNEL_INTEGRATION.md` |
| `integrate_ksu.sh` | 把 KSU 源码集成进内核树 | 同上，集成已完成 | 同上 |
| `enable_lto_cfi.sh` | 打开 LTO / CFI 配置项 | 已被 `build_ksu_lto2.sh` 内联（它自己写 CONFIG） | `scripts/build_ksu_lto2.sh` |
| `patch_vermagic.sh` | 改 vermagic 使其与设备一致 | 同上，已内联 | 同上（`CONFIG_LOCALVERSION`） |

## 设备侧（部署 / 监视）

| 文件 | 是什么 | 为什么归档 | 现行替代 |
|---|---|---|---|
| `deploy_ksu.sh` | 设备端加载模块（`u0 ksud insmod`） | 面向**老 rootd 链路**（由主机 push 后经 rootc 执行） | App 的部署流程：`apk/ksuonetap/src/.../deploy/DeployScript.kt` |
| `deploy_all.ps1` | Windows 主机侧一键部署（含 `-SoftRebootReady` 闭环） | 同上；且当前工作流已从「PowerShell 主机脚本」转为「App 内 Kotlin 部署」 | 同上 |
| `monitor_soft_reboot.ps1` | 软重启过程的监视工具 | 同上 | App 日志页 / `LogBus` |

## 与现行流程的关系

`docs/RESUKISU_FULL_FLOW.md` 里描述的完整流程**仍然有效**（它记录的是"整链跑通"的
验证结论），但其中提到的脚本路径已经归档 —— 对应关系见上面两张表。文档里的引用已在
2026-09-27 同步加注。

**不要删的东西**：`build_ksu_lto2.sh` 没归档（它是产出当前 35179 版 .ko 的那个脚本），
`fetch-ndk.sh` / `fetch-manager.sh` / `patch_ko_version.py` / `check-assets-sync.sh` /
`app-test/` 也都是现役。
