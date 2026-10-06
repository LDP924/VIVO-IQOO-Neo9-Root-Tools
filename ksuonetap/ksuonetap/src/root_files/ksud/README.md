# ksud — KernelSU 用户态

**用途**：KSU 的用户态主程序。本工程用到它的两件事：

1. **`ksud insmod <ko>`** —— 加载内核模块的**唯一正路**（裸 `insmod` 不做 ksud 的 UAPI
   校验与初始化，不能用）
2. 作为 `supercall` 的 UAPI 客户端（校验驱动 ↔ 管理器版本是否匹配）

**不绑系统版本**：它绑的是 **KSU 版本与 UAPI**（`ensure_uapi_version_matched` 是严格 `!=`，
不匹配就拒绝所有操作）—— 换系统版本不影响，所以留在 `assets/` 顶层。

**设备落点**：`/data/local/tmp/ksud`（5,681,504 字节，`assets/ksud`）

## 源码在哪 —— 本工程不提供

**这份 `ksud` 不是本工程编译的，源码也不在本目录。** 两件事：

| | 说明 |
|---|---|
| **它是怎么来的** | 直接从 **ReSukiSU 管理器 APK 里提取**：`lib/arm64-v8a/libksud.so`。管理器自己安装时也是把这一份复制成 `/data/adb/ksu/bin/ksud`，所以两者必然一致 |
| **源码在哪** | **ReSukiSU 仓库的 `userspace/ksud/`**（Rust 实现）。本仓库已把上游源码 vendor 在 **`source/resukisu/userspace/ksud/`**（含 `src/main.rs`、`lkm_image*.rs`、`boot_patch.rs`、`apk_sign.rs` 等 13 个源文件） |

按约定：源码属上游、此处不重复提供 —— 看源码请去
`source/resukisu/userspace/ksud/`（或上游仓库 `ReSukiSU/ReSukiSU` 同路径）。

## 版本：必须与管理器一致（有闸门）

```sh
# 当前
strings apk/ksuonetap/assets/ksud | grep -oE '4\.2\.0-rc[0-9]-[0-9]+-g[0-9a-f]+ \(uapi: [0-9]+\)'
# → 4.2.0-rc3-13-gfa8311f6 (uapi: 4)
```

| 项 | 值 |
|---|---|
| ksud 版本串 | `4.2.0-rc3-13-gfa8311f6` |
| uapi | **4** |
| 驱动侧 `KERNEL_SU_UAPI_VERSION` | **4**（`source/resukisu/uapi/supercall.h:20`） |
| 管理器 | versionCode **35184** / `v4.2.0-rc3`（与驱动 KSU_VERSION 同源） |

**为什么必须一致**：ksud 的 UAPI 校验是**严格 `!=`** —— 不相等会**拒绝所有操作**。
2026-09-27 整理时就发现过一次漂移：当时的 .ko 和 manager 都是 35179，`assets/ksud` 还停在
35140（`c04159fc`）。虽然两者 `uapi` 恰好都是 4 所以没炸，但这是"恰好没踩到"而非"没问题"。

**换管理器时**：

```sh
bash scripts/fetch-manager.sh --to-assets    # 换 manager 并**自动重提 ksud**
bash scripts/fetch-manager.sh --ksud         # 只重提 ksud（修漂移）
./build.sh sync                              # 校验 ksud == manager 内那一份
```

`check-assets-sync.sh` 里的 ksud 检查不是简单比对文件，而是**从 manager APK 里重新提取
一份来 `cmp`** —— 这样才拦得住"两边一起停在旧版"。

## 许可

上游 ReSukiSU。
