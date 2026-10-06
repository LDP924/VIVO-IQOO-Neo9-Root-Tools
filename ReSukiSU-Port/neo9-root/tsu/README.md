# tsu（Neo9Root 适配版）

Termux 的 `tsu`（termux-su）**适配修改版**：让 tsu 能识别本项目的 magisk su 位置。

## 背景

tsu 8.6.0 用**硬编码路径**搜索 su 二进制（不查 PATH）：

```sh
SU_BINARY_SEARCH=("/system/xbin/su" "/system/bin/su")
if [[ -x "/sbin" ]]; then
    SU_BINARY_SEARCH+=("/sbin/su" "/sbin/bin/su")
fi
```

本项目部署的 magisk su 在 `/debug_ramdisk/.magisk/su`（RAM tmpfs），不在上述任何路径
→ 原版 tsu 报 `No superuser binary detected. Are you rooted?`。

## 修改内容（唯一改动）

```diff
-SU_BINARY_SEARCH=("/system/xbin/su" "/system/bin/su")
+SU_BINARY_SEARCH=("/debug_ramdisk/.magisk/su" "/system/xbin/su" "/system/bin/su")
```

其余代码与 Termux 发行版 8.6.0 完全一致（`tsu-8.6.0.orig` 为设备原版，`tsu.patch` 为补丁）。

## 文件

| 文件 | 说明 |
|---|---|
| `tsu` | **修改版**（安装用） |
| `tsu-8.6.0.orig` | Termux 发行版原版（8.6.0，备份/对比用） |
| `tsu.patch` | unified diff（升级后可重新应用） |
| `LICENSE.md` | 上游许可证（**ISC**，Copyright (c) 2020, Cswl Coldwind） |
| `LICENSE_MIT` | 上游仓库附带的 MIT 许可（仓库双许可文件，一并保留） |

## 安装（设备端）

```bash
# 备份原版
cp $PREFIX/bin/tsu $PREFIX/bin/tsu.bak

# 替换为修改版（先 push 本目录 tsu 到 /sdcard 或经 adb）
cp /sdcard/tsu $PREFIX/bin/tsu
chmod 755 $PREFIX/bin/tsu

# 防止 pkg upgrade 覆盖 (termux-su 包更新会还原)
apt-mark hold termux-su

# 测试
tsu          # → 找到 /debug_ramdisk/.magisk/su → magiskd 授权 → root shell
```

## 许可合规

- 上游 tsu 为 **ISC License**（允许修改与再分发，需保留版权声明 —— 脚本头部已保留）
- 仓库附带上游双许可文件（ISC + MIT），随修改版一并分发
- 修改点仅一处路径添加，不涉及其他上游代码
- 上游地址：https://github.com/cswl/tsu（tag v8 / 8.6.0 为 Termux 发行版本号）
