# test-modules/out — 按系统版本区分的模块产物

构建入口是 `../build.sh`（`FW=<版本>` 选 profile，产物落这里）：

```sh
bash test-modules/build.sh                            # 默认 profile (15.1.14.7, 内核 5.15.178)
FW=14.0.17.2 MODULE=vrpatch bash test-modules/build.sh   # 只编 137 那一版
bash test-modules/build.sh --list                     # 可选版本
```

## 规则

- **本目录只放"待交付 / 刚落地"版本的产物**，作为 `check-assets-sync.sh` 里
  `VERSIONED` 表的源路径。
- **已固化进 `assets/` 的版本，以 `apk/ksuonetap/assets/<版本>/` 里那份为权威副本**
  （那是真机验证过的）。所以这里**不会**同时留一份字节不同的旧版本产物 ——
  那只会让人分不清该用哪个。

## 当前内容

| 版本目录 | 产物 | 状态 |
|---|---|---|
| `PD2338_A_14.0.17.2.W10.V000L1/` | `vrpatch.ko` / `unpatch.ko` | 2026-09-28 编（内核 5.15.137 profile）；**已进 `assets/` 并真机加载验证** |

## 另一个坑：`make M=<dir>` 会把 `.ko` 写回模块源目录

kbuild 的输出落在 `M=` 指向的目录里，也就是 `../vrpatch/`、`../unpatch/` ——
**那里放的是 15.1.14.7 的正本，会被覆盖**。`../build.sh` 已经把产物转到本目录，
但构建后仍建议 `md5sum ../{vrpatch,unpatch}/*.ko` 复核一遍（必要时从
`apk/ksuonetap/assets/PD2338_A_15.1.14.7.W10.V000L1/` 恢复）。
