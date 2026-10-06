# MEMORY — ReSukiSU-Port-For-IQOO_Neo9 项目约定

> 逐日细节：`YYYY-MM-DD.md` · 导航：`docs/INDEX.md` · App 约定：`apk/ksuonetap/DESIGN_NOTES.md`
> 内核集成：`docs/KERNEL_INTEGRATION.md` · 换版本重推：`neo9-root/tools/README.md`「换系统版本怎么重推」

## 硬约束（红线）

1. 漏洞命中地址一个字节不能改（`gPhyAddrs`/`KERNEL_PHYS_BASE`/`kFakeGpuAddr`/`FW_*_VA`/`NEO9_*`/`OFFSETOF_*`/spray 布局），单一来源 `neo9-root/exploit/fw_profile.h`；两道闸门 = `exploit_vivo.c` 的 `_Static_assert`（每 profile 一组期望值）+ `verify_bins.py`（独立写死期望表）。
2. 模块刷入永远由人手动执行；不改动 OS 文件；设备测试前安全检查。
3. `source/resukisu/.git` 不能删；`.workbuddy/` 是项目记忆。

## 版本锚点（2026-09-28）

- 已适配（tier=full）：`15.1.14.7`（内核 `5.15.178-gaacdc35637c4-dirty`，真机验证）/ `14.0.17.2`（内核 `5.15.137-gc870e76526d2-dirty`，真机验证）/ `14.0.17.6`（内核 `5.15.137-g7cb3e06b062c-dirty`，**2026-09-28 离线适配完成、真机验证待做**；全部常量重推后与 17.2 逐项相同 ⇒ fw_profile.h 用 `||` 共享分支，溯源 `neo9-root/docs/DERIVE_PD2338_A_14_0_17_6.md`）。产物在 `apk/ksuonetap/assets/<版本>/`，档位写在各自 `SYSTEM.txt` 的 `tier=`。
- `16.2.13.2`（OriginOS 6，Android 16/SDK36，内核 `5.15.197-g708015331567-dirty`；离线分析完成、真机未验；目录名 13.2 与镜像内 13.0 不符）。7 月批次 LPE（43499/64560/64468/69）经 `neo9-root/tools/verify_patches.py` 反汇编核验**内核侧全部 UNPATCHED**（2026-09-28，阳性对照=137 内核）；"橘子6 已封堵 43499"若属实必在内核之外的层，待真机定位。引导链已提取分析（2026-09-29）：**OTA 无 `lk` 分区**，`abl`=QC secboot ELF 壳 + 4MB UEFI FV(FVMAIN_COMPACT/LZMA) + **LinuxLoader**(PE32+ AArch64, libavb+fastboot+ATCMD+Anrirollback, 整机 P-384 ECC 验签)；模块准入全在内核（PROTECT 允许未签名 ko，红线 `module.c:2279/2354`）；「GG 修改器/LK 放行 ko」叙事不实；提取物 `~/OriginOS固件/out-16.2.13.2-bootchain/`（+`fv-extract/` 解包、`ABL_DEEP_ANALYSIS.md`）。
- ReSukiSU `main@fa8311f6`，KSU_VERSION=35184（manager/ksud/驱动三者同源，uapi=4）。manager 升级 `bash scripts/fetch-manager.sh --to-assets`。
- KSUOneTap v1.0.6（versionCode 8）；**升版必须用户明确要求**；版本号只改 AndroidManifest.xml。App 判版本只能走 `DeviceGate.resolveBuildDir()`（SysProps 反射），禁用 `Build.DISPLAY`。
- 内核源码树 `$HOME/kernel/vivo-neo9-android15`（=5.15.178）；工具链 NDK r27 clang 18。137 无公开源码树 → `vrpatch`/`unpatch` 按 profile 重编，`kernelsu-vivo.ko` 用 `scripts/patch_ko_vermagic.py` 等长对齐 vermagic；装载走 `ksud insmod`（`late-load --kmi` 会 panic，别走）。
- 模块重建：`FW=<版本> bash test-modules/build.sh`（产物 `test-modules/out/<版本>/`；⚠️ `make M=` 会覆盖源目录正本，编完核 md5）。常量：`VR_DETECT_OFFSET`（随固件，两版均 0x2ecc）、`CAP_BPRM_PA`。vr.ko 取法 `tools/vboot_extract_one.py` + `VRKO_BYPASS.md` §5。
- App 判 KSU root 必须解析 su 入口真执行一次 `id`（`Terminal.ksuEntry()`），见 DESIGN_NOTES §8。

## 产物分类（每类一个 out/<版本>/）

exploit → `neo9-root/exploit/out/<版本>/`；vrpatch/unpatch → `test-modules/out/<版本>/`；kernelsu-vivo.ko → `kernel-module/out/<版本>/`。`assets/<版本>/` 是真机验证过的权威副本（+SYSTEM.txt，tier=full/lpe-su）；闸门 `./build.sh sync`（= `scripts/check-assets-sync.sh`，也是版本号与档位单一来源）。

## 构建与闸门

- 入口 `./build.sh`：all / exploit（`--fw` 选 profile）/ modules / test / **sync** / verify / doctor / fetch-ndk / clean。
- **新增系统版本落点（5 处）**：① `fw_profile.h` 分支 ② `exploit_vivo.c` `_Static_assert` ③ `fw_profile.py` PROFILE_KEYS + `verify_bins.py` 独立期望表 ④ `build_exploit_stable.sh` FW_TABLE ⑤ `assets/<版本>/`+SYSTEM.txt+`DeviceGate.kt` 清单+`check-assets-sync.sh` VERSIONED。最后 `./build.sh exploit --fw …` → `verify` → `sync`；**真机验证必做**。
- 改产物三处同步：源目录 → assets/<版本>/ → SYSTEM.txt md5。`exploit/` 的 5 个变体产物不能删。
- 重编 kernelsu 模块：`bash scripts/build_ksu_module.sh`；必须 ARCH=arm64 + LTO_CLANG_FULL+CFI（漏 ARCH 会静默关 LTO → `struct module` init 偏移漂移）；编完 `git -C source/resukisu clean -fdxq kernel/`。
- init-hook 路径（`run_init_hook_lpe`）**未接线**，常量齐备不改变产物二进制。

## 环境坑

- 同一文件多个 Edit 不并行发；沙箱 `rm` 是安全删除包装（构建前 `PATH=/usr/bin:/bin:$PATH`）；大临时文件放工作区，别放 /tmp；`adb root` 后读 App 私有目录要用 `/system/bin/su -c 'cat …'`；装机用 `pm install -r -d`（不弹 vivo 拦截页）。
- exploit 自检偶发 `FW symbol verification failed; continuing anyway` ≠ 地址错（失败即跳过的防御自检，可加 `[stab]` 同款重试加固）。
