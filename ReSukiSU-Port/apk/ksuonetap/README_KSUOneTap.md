# KSUOneTap — 一键提权 App (iQOO Neo9 PD2338C)

> **v1.0.6**（多系统版本适配）。通过 Shizuku 授权，App 内一键完成：
> GPU 漏洞提权 → 加载 KernelSU (ReSukiSU) 驱动 → 加载 unpatch/vrpatch → 安装 ReSukiSU Manager。
>
> v1.0.6 新增 / 修复：
> - **新增适配系统版本 `PD2338_A_14.0.17.2.W10.V000L1`**（内核 `5.15.137-gc870e76526d2-dirty`），
>   与 15.1.14.7 一样是**完整档位 `tier=full`**（exploit + 3 个内核模块）。
>   该固件是 vivo 自研内核、**没有公开源码树**，编不出 `kernelsu-vivo.ko` —— 所以那份是用已有
>   产物做 **vermagic 等长对齐**得到的（`scripts/patch_ko_vermagic.py`，`cmp -l` 只差 14 字节，
>   真机加载验证过）；`vrpatch.ko` / `unpatch.ko` 则是按 137 profile 重编的
> - **版本绑定产物改为按档位管理**：`assets/<版本目录>/SYSTEM.txt` 里用 `tier=full|lpe-su`
>   声明该版本齐备哪些产物，App 按档位推文件、按档位开关 KSU 阶段
> - 设备判定卡新增「已适配（仅临时 root）」状态（`LPE_ONLY_BUILDS`）—— 目前两版都是 `full`，
>   这个档位留给"先只做 LPE、内核模块留待后续"的新版本
> - **修复「OriginOS 4 上明明已适配却判成未适配」**（2026-09-28）：以前拿 `Build.DISPLAY`
>   当系统版本号，而 `ro.build.display.id` 在两代 OriginOS 上语义不同 ——
>   OriginOS 5 给的是软件版本号，**OriginOS 4 给的是 AOSP build id**
>   （`UP1A.231005.007 release-keys`），于是 14.0.17.2 那台永远匹配不上。
>   现在改为按 `DeviceGate.SOFTWARE_VERSION_PROPS` 依次读 vivo 的版本类 property，
>   归一化成**版本核**（如 `14.0.17.2.W10`）再与 assets 目录名比对 ——
>   同时抹平了 `PD2338` vs `PD2338C`、`.V000L1` 这类写法差异。
>   判定**仍然不需要 Shizuku**（`core/SysProps.kt` 走 `SystemProperties` 反射，
>   被 hidden API 挡住才退化到 shell `getprop` / `Build.DISPLAY`，并在界面上注明）
> - 关于页的两处版本清单改为从 `DeviceGate` 现取（原先写死在 `strings.xml`，加了新版本就成了假信息）
>
> v1.0.5 新增 / 修复（历史）：
> - **一键提取两种模式**（设置页切换）：仅提取临时 root（不部署 KSU）／提取 root 并部署 KSU
> - **内置终端**：三条执行通道（Shizuku shell / 临时 root / KernelSU）+ 快捷命令 + 命令历史
> - **设备与系统版本判定**：状态卡新增「设备」行；未适配组合在部署前弹强警告
> - **「软重启」「清理并重启」仅在 root 可用时可点**（非 root 置灰，避免点了没反应）
> - 修复**运行日志无法手动滑动**：日志改为固定高度的独立滚动区 + 智能自动跟随
> - 应用图标改为二次元风格（矢量，紫粉渐变 + Q 版角色）

## 使用

```sh
# 1. 一次性激活 Shizuku（每次重启后需重新激活）:
adb shell /data/app/~~*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so
#    (Shizuku app 内查看确切命令; 或用无线调试)

# 2. 安装 App:
adb install -r KSUOneTap-v1.0.6.apk        # 签名一致才能 -r 原地升级, 见「构建」

# 3. 打开 App → 「一键提权」（会先弹确认框）
#    - 首次会弹 Shizuku 授权, 点允许（也可先点「激活 Shizuku」）
#    - 等待 spray 命中（通常 <1 分钟, 最长约 9 分钟）
#    - 完成后状态卡显示 KernelSU 已加载 / Root uid=0
# 4. 若当前是「仅提取 root」模式（状态卡：KernelSU=未加载 / Root=uid=0 (临时 root)），
#    第一排显示的是「激活 KSU」而不是「软重启」—— 点它即可接着把 KernelSU 装上，
#    **不用重跑漏洞**；激活成功后该按钮自动变回「软重启」。
# 5. 「软重启」= ksud soft-reboot（ReSukiSU 同款, 快且稳定; **需要 KSU 在线**）
# 6. 「清理并重启」= 卸载 Manager + 清 /data/local/tmp 与 /data/adb → 重启（需确认）
#    提权客户端按环境自动选: KSU 在线用 su_ksu, 只有临时 root 时用内置 su
```

### 界面结构（底部 dock 四页）

```sh
# 主页 : 大状态卡 + 状态明细 + 操作按钮 + 日志区   (dock 第 1 项)
# 日志 : LogBus 全文, 全屏滚动 + 复制/清空           (dock 第 2 项)
# 设置 : 一键提取模式 + 外观 + 当前设备              (dock 第 3 项)
# 关于 : 应用信息 + 适用范围 + 授权与使用 + 声明       (dock 第 4 项)
#
# 切页三种方式, 都落在同一条横向条上 (scrollX = 页序号 x 页宽):
#   1. 手指左右拖 —— 跟手, 拖到哪显示哪
#   2. 松手 snap —— 甩动速度够就翻页, 否则看位移是否过半; 都不满足则回弹,
#      不会停在两页之间
#   3. 点 dock —— setCurrentItem(i), 平滑滚过去, 高亮同步
#
# 为什么不用 ViewPager: 构建链没有 AndroidX (aapt2+kotlinc+d8), framework 的 ViewPager
# 早已废弃。HorizontalScrollView 自带跟手与惯性, 补上"等宽 + 松手 snap"就够了
# (ui/SwipePager.kt)。页面里的日志/输出区是 InnerScrollView, 它**只在垂直意图明确时
# 才锁父容器**, 否则水平手势会被它吃掉、在这些区域上就滑不动页。
#
# 页面不再是 Activity: 四个页面是 activity_pager.xml 里横向条的四个子 View
# (page_home / page_log / page_settings / page_about), 逻辑在 ui/page/ 下同名类里,
# 生命周期由 PagerActivity 转发成 onShow / onHide (页面实现要幂等)。
#
# 终端仍是独立的二级页 (主页顶栏「终端」进入, 那一页保留返回键), 用转场动画打开;
# 它**也带 dock**, 只是四项都不高亮 (Dock.dim)。
```

### 设置（dock 第 3 项）

```sh
# 一键提取模式（二选一, 改完立即生效, 主按钮文案随之变化）
#   ● 提取 root 并部署 KSU   → 完整流程（默认）
#   ○ 仅使用 root，不部署 KSU → 只到临时 root 就绪为止（终端里 uid=0，重启即失效）
#
# 外观
#   主题色:   靛紫 / 蔚蓝 / 青碧 / 翠绿 / 暖橙 / 绯红   点色点即换, 立即生效
#   深浅模式: 跟随系统 / 浅色 / 深色                    Android 12+ 可强制, 更低版本只跟随
#
# 当前设备: 实时的机型 + 系统 + 适配判定
```

### 关于（dock 第 4 项）

```sh
# 应用信息: 应用名 / 版本 (含 versionCode) / 包名
# 适用范围: 适用机型 / 支持 KSU 部署的系统版本 / 漏洞可利用范围 / 临时 root 适配范围
# 授权与使用: 仅供安全研究使用 (随包二进制的源码索引见 src/root_files/)
# 声明:     漏洞偏移绑定具体版本的说明与风险提示
```

### 内置终端（主页顶栏「终端」）

```sh
# 三条通道, 顶部下拉切换（默认「自动」= KSU -> 临时 root -> shell 依次探测）
#   KernelSU      : 走 Terminal.ksuEntry() 解析出的 su 入口 —— 优先随包的 /data/local/tmp/su_ksu,
#                   退化到 KernelSU 自己装的 /system/bin/su（手工装 KSU 时只有后者）。
#   临时 root     : su -c       （只提取了 root、没部署 KSU；内置 su，直接 uid=0）
#   Shizuku shell : uid 2000    （任何时候可用, 只读探测）
#
# 命令执行: 输入框 + 「执行」(或键盘回车); 上方一排是快捷命令
# 输出区:   可手动滑动读历史; 上滑后右下角出现「回到最新」; 支持复制全文
# 历史:     「↑ 上一条 / ↓ 下一条」在已执行过的命令间切换
```

**注意**：终端里执行命令**不经过** App 的部署逻辑，`su` 类操作会直接以所选通道的身份跑；
换通道前先想清楚当前身份（状态行会显示「Shizuku 已激活 · KSU 可用 · 临时 root 离线 · 通道「…」」）。

## 工程结构（v1.0.6）

```
apk/ksuonetap/
├── AndroidManifest.xml          # versionCode 8 / versionName v1.0.6 (activity 写全限定名)
├── build_ksuonetap.sh           # ★ 现行构建脚本 (bash, 自动探测 SDK/JDK/kotlinc)
├── legacy/build_ksuonetap.ps1   # 旧 Windows 构建 (javac 版, 已淘汰: 编不了 Kotlin)
├── assets/                      # 部署到设备的文件 (已无 .sh)
│   ├── PD2338_A_15.1.14.7.W10.V000L1/  # tier=full   : exploit_vivo_neo9 + kernelsu-vivo.ko
│   │                            #               + unpatch.ko + vrpatch.ko   (4 个绑定产物)
│   ├── PD2338_A_14.0.17.2.W10.V000L1/  # tier=full   : exploit_vivo_neo9 + kernelsu-vivo.ko
│   │                            #               + unpatch.ko + vrpatch.ko   (4 个绑定产物)
│   │                            #   (内核 5.15.137; kernelsu-vivo.ko 是把 5.15.178 那份
│   │                            #    做 vermagic 等长对齐得到的 —— 该固件无公开源码树)
│   │                            # 每个版本目录都带 SYSTEM.txt (版本标注 + 产物 md5 + tier)
│   └── ksud / su_ksu / u0 / resukisu-manager.apk  # 不绑系统版本, 留在顶层
│                                # 这些文件在各版本目录里的用途:
│                                #   exploit_vivo_neo9  临时 root exploit (STUB_MODE=13,
│                                #                      -DCHEESE_SU_DEFAULT=1 => 自带内置 su:
│                                #                      起 socket 服务并装 <dev>/su, 不需要 u0)
│                                #   kernelsu-vivo.ko   KSU 驱动 (设备适配构建)     ┐
│                                #   ksud / u0 / su_ksu KSU 用户态 + 提权辅助       │ 仅完整模式
│                                #   unpatch.ko        软重启保护                  │ 推送 (仅提取
│                                #   vrpatch.ko        vr.ko 中和                  │ root 模式不推,
│                                #   resukisu-manager   管理器 (35184 / v4.2.0-rc3) ┘  并清已有残留)
├── LICENSE                      # GPL v3.0 全文 (取自上游, 保留)
├── NOTICE                       # 许可结构说明 (适用范围 / 上游组件 / 第三方依赖)
├── res/                         # 主题色与观感资源
│   ├── layout/                  #   activity_pager (分页容器 + dock) / activity_terminal /
│   │                            #   view_dock (各页共用) / page_{home,log,settings,about}
│   ├── values/                  #   attrs(主题色 attr) / themes(6 套色板) / colors(中性色) /
│   │                            #   strings / styles / arrays (通道 + 快捷命令)
│   ├── values-night/            #   深色覆盖 (colors 与 themes 各一份)
│   ├── drawable/                #   卡片/胶囊/选项卡片/输入框/dock 图标/图标前景 (矢量)
│   └── mipmap-anydpi-v26/       #   自适应图标
└── src/com/neoroot/ksuonetap/   # ★ 全部 Kotlin (22 个文件 / 4 个包)
    ├── core/                    # 执行原语与设备事实 (不依赖 UI / 部署)
    │   ├── Shell.kt             #   Shizuku 执行原语 (超时 / 流式写文件 / 输出上限)
    │   ├── ShizukuBridge.kt     #   Shizuku 反射桥
    │   ├── SysProps.kt          #   系统 property 读取 (SystemProperties 反射; 版本号靠它)
    │   ├── DeviceGate.kt        #   机型/系统版本判定 (适用范围门控)
    │   ├── Prefs.kt             #   设置持久化 (模式 / 通道 / 色板 / 深浅)
    │   ├── Channel.kt           #   终端通道的标识与展示名
    │   ├── B64.kt               #   base64 编码 (终端脚本包装 / rootd 队列共用)
    │   ├── LogBus.kt            #   全局日志缓冲 (每行同时落盘到 LogStore)
│   ├── LogStore.kt          #   日志落盘: filesDir/logs/<日期>.txt, 逐行 flush
│   ├── Progress.kt          #   部署进度 (阶段式: pct + 阶段名 + running/failed)
    │   └── Theme.kt             #   色板/深浅的读写与 setTheme
    ├── deploy/                  # 设备侧动作
    │   ├── Deployer.kt          #   部署流程 (含「仅提取 root」分支) + 清理重启
    │   ├── DeployScript.kt      #   设备侧命令 (原两个 .sh 的内容) + 常量
    │   └── Terminal.kt          #   终端执行通道 (shell / 临时 root / KSU)
    └── ui/                      # 界面 (只用 framework 控件, 无 AndroidX/Material)
        ├── App.kt               #   Application: 启动即初始化日志落盘 (LogStore.init)
        ├── SwipePager.kt        #   跟手左右滑的分页容器 (等宽 + 松手 snap)
        ├── Dock.kt              #   底部 dock: 点按切页 + 高亮
        ├── TerminalActivity.kt  #   内置终端 (独立二级页, 也带 dock)
        ├── UiKit.kt             #   InnerScrollView + Insets + TailFollower (自动跟随)
        └── page/                #   四个页面 (不再是 Activity)
            ├── Page.kt          #     页面基类: 持有自己的 root, 暴露 onShow / onHide
            ├── HomePage.kt      #     主页: 大状态卡 / 状态明细 / 进度(转圈+%) / 操作按钮
            │                    #       (不再显示日志 —— 日志归日志页)
            ├── LogPage.kt       #     日志页: 实时 + 按日期历史 + 复制/导出/历史/清空
            ├── SettingsPage.kt  #     设置页: 一键提取模式 + 外观(色板/深浅) + 当前设备
            └── AboutPage.kt     #     关于页: 应用信息 / 适用范围 / 许可 / 声明
│
└── src/root_files/              # ★ 随包二进制的源码索引 (不参与 APK 打包: 构建只收 src/**/*.kt)
    ├── README.md                #   总索引: 每个部署物 -> 设备落点 / 二进制来源 / 源码在哪
    ├── exploit_vivo_neo9/       #   exploit_vivo.c + adrenaline.h + stubs/ + 构建与校验脚本
    ├── kernelsu-vivo.ko/        #   vivo.config + 构建脚本 + 内核集成补丁 + 版本码改写工具
    ├── unpatch.ko/ vrpatch.ko/  #   两个自研内核模块的 .c + Makefile + build.sh
    ├── u0/ su_ksu/              #   u0.c / su_patched.c
    └── ksud/                    #   只放 README (源码属上游 ReSukiSU, 本工程不重复提供)
```

依赖方向单向：`ui → deploy → core`。core 不认识 UI 与部署流程，deploy 不认识 UI。

### 主题（色板 + 深浅）

主题色走**自定义 attr**，不写死色值 —— 布局与 drawable 只引用 `?attr/ksuPrimary` 这类名字，
具体颜色由主题 style 给，所以换色板就是换 style，资源不用重建。

| 层 | 文件 | 内容 |
|---|---|---|
| attr 声明 | `values/attrs.xml` | `ksuPrimary` / `ksuPrimaryDim` / `ksuOnPrimary` / `ksuPrimaryContainer` / `ksuTopbarBg` / `ksuTopbarBgAlt` / `ksuTopbarMarkBg` |
| 色板·浅色 | `values/themes.xml` | `Theme.KSUOneTap`（= 靛紫）+ `.Blue` / `.Teal` / `.Green` / `.Orange` / `.Red`，各只覆盖上面这些 attr |
| 色板·深色 | `values-night/themes.xml` | 同名 style 各写一份深色取值（顶栏统一压暗：`#1B1A20` → `#2B2930`） |

> **顶栏的硬约束**：任何色板 + 任何深浅下都必须是「深底 + 浅字」。所以 `bg_topbar` 的渐变
> 只能用 `ksuTopbarBgAlt → ksuTopbarBg`，**不能**改用 `ksuPrimary` / `ksuPrimaryDim` ——
> 深色模式下那两个是**浅色**值，用了会变成浅底配 `topbar_fg` 的浅字（对比度几乎为零）。
> 同理，凡是垫白色品牌图形（机器人 + 钥匙）的底色都走 `ksuTopbarBg` 系
> （`bg_mark_primary` 就是）。
| 中性色 | `colors.xml` + `values-night/colors.xml` | 背景/文字/描边/状态色，只按深浅两套覆盖 |
| 切换 | `core/Theme.kt` | `apply(activity)` 在 `super.onCreate` **之前** `setTheme`；`primaryOf()` 从 style 里读 `ksuPrimary` 给色点染色（色点与真实主题永远一致，不重复维护色值） |

**深浅模式**用 `UiModeManager.setApplicationNightMode`（API 31+，即 Android 12 起）；
更低的系统没有强制接口，只能跟随系统 —— 界面上会给出提示。切色板后当前页 `recreate()` 立即生效。

**v1.0.6 的主要变化**

1. **多系统版本适配落进 App**：`assets/` 下每个系统版本一个目录，目录名 = 该固件的
   软件版本号，取件走 `DeployScript.assetPath(DeviceGate.resolveBuildDir(), name)` ——
   **结构上不可能把别的系统版本的偏移推到本机**；版本不匹配时部署在推文件之前就报错拒绝。
2. **档位（tier）**：每个版本目录的 `SYSTEM.txt` 用 `tier=full|lpe-su` 声明齐备要求。
   `full` = exploit + 3 个 `.ko`（可提取 root 并激活 KSU）；`lpe-su` = 只有 exploit
   （只能提取临时 root + 用内置 su）。`DeployScript.systemBoundFor()` 按档位给集合，
   `Deployer` 按档位跳过 KSU 阶段并给出「该版本模块未适配」的说明。
   **当前两版都是 `full`**（14.0.17.2 于 2026-09-28 由 `lpe-su` 升为 `full` —— 3 个模块
   都已真机加载验证）；`lpe-su` 档位保留给"先只做 LPE、内核模块留待后续"的新版本。
3. **设备判定**：`DeviceGate` 分两份清单（`ADAPTED_BUILDS` / `LPE_ONLY_BUILDS`），
   状态摘要有「已适配（仅临时 root）」一态；`./build.sh sync` 校验
   **版本目录 ↔ 两份清单 ↔ `SYSTEM.txt` 的 tier** 三者对齐。
4. **版本绑定常量外移到 `neo9-root/exploit/fw_profile.h`**（按固件 profile 分支），
   stub 机器码由字段偏移生成 —— 换固件时"改了偏移忘了改 stub"这类错不可能再发生。
   闸门仍是两道：编译期 `_Static_assert` + `verify_bins.py` 逐 profile 反汇编核对。
5. **版本号判定改走 property**（2026-09-28 修 bug）：不再拿 `Build.DISPLAY` 当系统版本号
   （它在 OriginOS 4 上是 AOSP build id），见下方「设备与系统版本判定」一节的对照表。
   归一化成版本核 `x.y.z.Wnn` 后比对，机型后缀 `C` / 批次后缀 `V000L1` 的写法差异被抹平。
6. **主页改版：不再显示运行日志，改成"转圈 + 百分比 + 阶段名"的执行进度**。
   日志全部归日志页。按钮统一叫 **「一键 Root」**（原「一键提权」/「一键提取 Root」两种叫法合并）。
   进度是**阶段式**的（spray 那段时长不可控，给不出连续百分比），失败时定格并写明原因 ——
   见 `DESIGN_NOTES.md` §9。
7. **日志系统重做**：每写一行就落盘到 `<filesDir>/logs/<日期>.txt`（**按日期分文件、立即 flush**，
   崩溃/被杀不丢）；日志页可**按日期翻历史**、**导出**（系统分享，可存文件或发出去）。
8. **KSU 的 su 入口改为多候选解析**（2026-09-28 修 bug）：不再只认随包的
   `/data/local/tmp/su_ksu` —— 手工装 KSU 时设备上只有 KernelSU 自己装的 `/system/bin/su`，
   只认前者会把「KernelSU 已加载」误判成「无 root」，还会把「软重启/清理并重启」置灰。
   见 `DESIGN_NOTES.md` §8。

**v1.0.5 的主要变化**

1. **Java → Kotlin**：原来单个 `MainActivity.java`（22KB，程序化建视图）拆成多个 Kotlin
   文件（当前 13 个 / 3 个包，见上方结构树）
2. **部署脚本内联进 Kotlin**：`assets/deploy_onedevice.sh` 与 `assets/cleanup_reboot.sh`
   **已删除**，内容全部由 `DeployScript.ksuLoadCommand()` / `cleanupRebootCommand()` 生成，
   经 rootd 文件队列（`B64:` 协议）或 `su_ksu -c` 执行。设备上不再落任何 .sh。
3. **UI 参考 LSPosed 管理器重做**：渐变圆角顶栏 + 分层圆角卡片 + 状态胶囊
   + 主/次按钮 + 日志卡（复制/清空），并做深色主题与 edge-to-edge insets 适配。
   只用 framework 控件（构建链没有 AndroidX/Material 依赖），观感靠配色 + shape + elevation。
4. **新增设置页**（一键提取模式单选 + 当前设备判定 + 关于），新增 **内置终端**（三通道执行）。
5. **日志区改为独立滚动区**（`InnerScrollView`，固定 260dp）：
   - 之前日志 `TextView` 直接挂在外层 `ScrollView` 里，且每来一行就 `fullScroll(DOWN)` ——
     用户往回翻立刻被拉回底部，表现就是"滑不动"。
   - 现在触摸时禁止外层拦截（`requestDisallowInterceptTouchEvent`），并且只在用户停在
     底部时才自动跟随；上滑后右下角出现「↓ 回到最新」。
   - 另修一个隐蔽问题：`TextView.setText()` 会**重置滚动位置**，所以刷新前先记 `scrollY`、
     写完再恢复（`TailFollower.beforeChange/afterChange`），否则读历史时会被不断弹回顶部。
6. **源码按职责分成 3 个包**（`core` / `deploy` / `ui`，依赖方向 `ui → deploy → core`）：
   - 原先 11 个文件平铺在一个包里，`Prefs` 为了拿默认通道要去引用 `Terminal`，形成
     core 反向依赖 deploy；现在通道标识独立成 `core/Channel.kt`，依赖方向被拉直。
   - base64 编码原先在 `DeployScript` 与 `Terminal` 各有一份，统一为 `core/B64.kt`。
   - `Terminal.execWrapped` 的 KSU / SHELL 两个分支原本抄了同一段落盘命令，合并为一处。
   - `AndroidManifest.xml` 的 activity 改用全限定名（`.MainActivity` 已不在根包）。
   - 顺带清掉死代码（`Deployer.status()`、`ShizukuBridge.context()`）与死资源
     （14 个未引用字符串、未引用的 `bg_option.xml` selector），并把 `Shell.exec`、
     `Terminal.rcOf/stripRc` 收窄为 `private`。
7. **换成带内置 su 的 exploit，「临时 root」通道改走 `su -c`**：原先推的
   `assets/<系统版本>/exploit_vivo_neo9` 是**旧版**（31KB，不含内置 su），只能靠 rootd 文件队列，
   `id` 显示的还是调用方 shizuku 的 `uid=2000`。现在换成
   `neo9-root/exploit/out/<系统版本>/exploit_vivo_neo9_stable_su_ndk13`（71KB，`-DCHEESE_SU_DEFAULT=1`），
   起来自带 su 服务并把 `/data/local/tmp/su` 装好 —— **`su -c 'id'` 直接 `uid=0(root)`**，
   且设备上不再需要 `u0`。详见「内置终端」章节的实现要点 2。
8. **新增「激活 KSU」**：临时 root 在手但 KSU 未激活时，主界面第一排把「软重启」换成
   **「激活 KSU」**—— 点它接着把 KSU 装上，**不用重跑漏洞**；激活成功后自动隐藏、
   换回「软重启」。所有模块一律经 `ksud insmod` 加载。详见「从临时 root 接着激活 KSU」。

9. **主界面参考 KernelSU 重做 + 换图标**：新增**大状态卡**（一句话结论 + 下一步提示 +
   版本号，由 `MainActivity.updateVerdict()` 计算），顶栏精简到只有标题 + 终端/设置两个按钮；
   图标从二次元角色换成「机器人 + `#`」（root 提示符），见 `ic_launcher_foreground.xml`。

## 一键提取模式（设置页）

`Prefs.isRootOnly()` 决定 `Deployer.run()` 跑到哪一步：

| 模式 | 流程 | 结果 |
|---|---|---|
| **提取 root 并部署 KSU**（默认） | [1] 推文件 → [2] 起 exploit → [3] 等临时 root → [4]…[6] 加载 KSU/vrpatch + 装 Manager | `su` 可用（uid=0, KSU） |
| **仅使用 root，不部署 KSU** | 只到 [3] 停下 | 临时 root（**`su` 可用，uid=0**），走**临时 root** 通道 |

「仅提取 root」模式的要点：
- **只推 1 个文件**（`exploit_vivo_neo9`），其余 KSU 相关资产不推、并主动清理残留
  （`u0` / `kernelsu-vivo.ko` / `ksud` / `su_ksu` / `unpatch.ko` / `vrpatch.ko` /
  `resukisu-manager.apk`，合计约 30MB）—— 该模式下 `/data/local/tmp` 只该有 exploit
  与它自己装的 `su`。
- exploit 起来就带**内置 su 服务**（App 传 `CHEESE_SU=1`；推的资产就是
  `-DCHEESE_SU_DEFAULT=1` 构建的 `exploit_vivo_neo9_stable_su_ndk13`（`out/<系统版本>/`））：装
  `/data/local/tmp/su`（指向自身的符号链接）+ 建 `su.sock`。所以 `su -c 'id'`
  直接 `uid=0(root)`，**设备上不需要 `u0`**。
- 不 insmod、不碰 `/data/adb`、不装 Manager —— 重启即失效，不留持久化痕迹。
- 该模式**也带 rootd 文件队列**（`CHEESE_SU_QUEUE=1`）：空转时不创建 `rootd_cmd` /
  `rootd_out`，但「激活 KSU」要靠队列里的 `u0` 提权才能 `ksud insmod`。
- 提权落脚的域是 **`u:r:shell:s0`**（不是 exploit 默认的 `u:r:vrp:s0`）—— 原因见下方
  「为什么不用 vrp 域」。
- 软重启仍不可用（要 `ksud`，得 KSU 在）；该模式下也没有 `su_ksu`。

#### 为什么不用 vrp 域（`u:r:vrp:s0`）

exploit 的默认落脚域是 `u:r:vrp:s0` —— 它是 vr.ko 的**白名单**（`euid=0` 时不被击杀），
但它是个 vivo 自定义域，**权限只够跑裸命令**：

```
$ su -c 'service list'
Found 0 services:                          ← vrp 域问不到 servicemanager
$ su -c 'cmd package path frb.axeron.manager'
cmd: Can't find service: package
```

内核里的 avc 记录（`permissive=0`，即**真的被拒**）：

```
denied { find } name=package  scontext=u:r:vrp:s0  tcontext=u:object_r:package_service:s0  tclass=service_manager
denied { list } name=service_manager  scontext=u:r:vrp:s0  tcontext=u:r:servicemanager:s0
```

后果：**所有依赖系统服务的工具都起不来**。典型例子是 AxManager/Axeron
（`frb.axeron.manager`，Shizuku 类的特权服务框架）—— 它的 starter 启动时要
*"waits for system services (package, activity, user, power)"*，
因此报 `Can't find service: package` → `fatal: can't get path of manager` 直接退出。

**改用 `u:r:shell:s0`**：Android 标准域，policy 对 servicemanager / package / activity
等有完整 allow。实测：

| 检查 | vrp 域 | shell 域 |
|---|---|---|
| `service list` | `Found 0 services` | **`Found 398 services`** |
| `cmd package path frb.axeron.manager` | `Can't find service: package` | **`package:/data/app/…/base.apk`** |
| 压测（120 轮 `cmd package list packages` + 读 `/proc/self/status`） | — | **全程存活**，`uid=0(root)` 保持 |

**代价**：放弃了 vr.ko 的白名单。本机实测 shell 域 + `euid=0` 未被击杀；若换机型后遇到
被杀的，先加载 `vrpatch.ko` 中和 vr.ko 再切域。

### 从临时 root 接着激活 KSU

拿到临时 root 后**不必重跑漏洞**：主界面第一排会把「软重启」换成**「激活 KSU」**，
点它即走完整模式的 `[4]~[6]`。按钮显隐规则：`有 root 且 KSU 未激活` → 「激活 KSU」；
`KSU 已激活` → 「软重启」。

| 步骤 | 动作 |
|---|---|
| `[1']` 补推 KSU 资产 | `kernelsu-vivo.ko` / `ksud` / `u0` / `su_ksu` / `unpatch.ko` / `vrpatch.ko` / Manager（**不含 exploit**） |
| 队列确认 | 投一条 no-op 探活；队列不在就用内置 su 补起一个 `--su-server`（该路径不碰漏洞/GPU，不受实例锁限制，**同样不用重跑**） |
| `[4]` | rootd 域 `u0 ksud insmod kernelsu-vivo.ko allow_shell=1` |
| `[4c]` | `su_ksu -c 'ksud insmod unpatch.ko'` / `vrpatch.ko` |
| `[4d]~[6]` | 结束临时 root → 验证 → 装 Manager；收尾把 `u0` 删掉 |

**所有模块加载都经 `ksud insmod`** —— 裸 `insmod` 不做 ksud 的 UAPI 校验与初始化，不能用。

## 内置终端（三通道执行）

`Terminal.kt` 把三种设备侧执行方式收敛成一个接口：

| 通道 | 实现 | 身份 | 何时可用 |
|---|---|---|---|
| `Shizuku shell` | Shizuku `newProcess` | uid 2000 / `u:r:shell:s0` | Shizuku 激活即可 |
| `临时 root` | `su -c '<脚本>'`（**exploit 内置 su**，经 `su.sock`）+ 执行前先 `setcon` | **uid 0** / `u:r:shell:s0` | 内置 su 服务在线（`su.sock` 存在） |
| `KernelSU` | `su_ksu -c '<脚本>'` | uid 0 / `u:r:ksu:s0` | KSU 已加载 |
| `自动` | KSU → 临时 root → shell 依次探测 | — | 默认 |

三个实现要点：
1. **命令一律落盘再执行**（`.kt_term.sh`，base64 传入）：命令里出现单引号或换行时，
   层层引用（`su -c '...'` / `su_ksu -c '...'`）极易被吞，落盘后传给客户端的只有 base64 串。
   三条通道共用这一条路径，所以 `[rc=N]` 的解析也只有一份。
2. **「临时 root」= exploit 自带的内置 su**，设备上**不需要 `u0`**：exploit 启动时
   `stab_su_server()` 会 `stab_install_su()` 把 `/data/local/tmp/su` 装成指向**自身**的
   符号链接并建 `su.sock`；客户端（`argv[0]` 名为 `su` 即进客户端模式）把命令经 socket
   交给服务端，服务端 fork 后 `setuid(0)`（落脚域由 `CHEESE_U0_CTX` 决定）再 `sh -c` ——
   所以 `su -c 'id'` 直接 `uid=0(root)`。
   **域固定用 `u:r:shell:s0`**：App 启动 exploit 时传 `CHEESE_U0_CTX=u:r:shell:s0`；
   终端通道还会在执行前再 `setcon` 一次兜底 —— 这样即使设备上跑的是旧实例（域还是 vrp），
   App 里的命令也照样能访问 servicemanager。
3. **可用性判 `su.sock` 而不是 `rootd_ready.txt`**：后者在新旧版本里都会写，只有前者能
   证明 `su -c` 真的可用 —— 判据要和实际用到的通道一致。

## 设备与系统版本判定

`DeviceGate.kt` 判定机型/版本（**不需要 root/Shizuku**，所以未授权时也有结论）。
机型用 `android.os.Build`；**系统版本号必须读 property**——不能拿 `Build.DISPLAY`：

| 项 | 值 |
|---|---|
| 目标机型 | `PD2338`（`Build.DEVICE`）/ `V2338A`（`Build.MODEL`） |
| 已适配的系统版本 | `DeviceGate.ADAPTED_BUILDS`（完整）+ `LPE_ONLY_BUILDS`（仅临时 root） |
| 版本号的来源 | `DeviceGate.SOFTWARE_VERSION_PROPS`（`ro.vivo.default.version` / `ro.build.version.bbk` / `ro.vivo.product.version` …），归一化成版本核 `x.y.z.Wnn` |
| 绑定产物取件目录 | `assets/<版本目录>/`（= 解析出的 `DeviceGate.resolveBuildDir()`） |
| 漏洞可利用范围 | OriginOS 4 ~ OriginOS 5（更宽区间，但本工程未做偏移适配） |

**为什么不用 `Build.DISPLAY`**（2026-09-28 踩过）：`ro.build.display.id` 是 `Build.DISPLAY`
的来源，但它的语义在两代 OriginOS 上不同 ——

| 系统 | `ro.build.display.id` | 真软件版本号 |
|---|---|---|
| OriginOS 5（Android 15） | `PD2338_A_15.1.14.7.W10.V000L1` | 同左（巧合相同） |
| OriginOS 4（Android 14） | `UP1A.231005.007 release-keys` | `ro.vivo.default.version` = `PD2338_A_14.0.17.2.W10.V000L1` |

后果就是 OriginOS 4 的机器被判成"未适配"。而且 `Build` 的**公开字段里没有**软件版本号
（`Build.ID` / `Build.FINGERPRINT` / `Build.VERSION.INCREMENTAL` 都不含），
`/system/build.prop` 又是 `0600 root`（App 读不到）⇒ 只能走 `core/SysProps.kt`
的 `SystemProperties` 反射（读不到时退化到 shell `getprop`，最后才 `Build.DISPLAY` 兜底）。

**系统版本绑定产物与目录布局**。这 4 个产物与目标系统的内核二进制布局绑定，换版本必须重做，
所以按系统版本分目录存；其余 4 个不绑系统版本（只绑 KSU 版本 / UAPI），留在 `assets/` 顶层：

| 产物 | 绑定什么 | 位置 |
|---|---|---|
| `exploit_vivo_neo9` | `KERNEL_PHYS_BASE` / `CHEESE_STEXT_PA` / `gPhyAddrs[]` / spray | `assets/<系统版本>/` |
| `kernelsu-vivo.ko` | vermagic + `struct module` 布局（LTO/CFI/BTF）+ `KSU_VERSION` | `assets/<系统版本>/` |
| `unpatch.ko` | 同上 + `CAP_BPRM_PA`（= stext_pa + 符号偏移） | `assets/<系统版本>/` |
| `vrpatch.ko` | 同上 + vr.ko 内 `VR_DETECT_OFFSET` | `assets/<系统版本>/` |
| `u0` / `su_ksu` / `ksud` / `resukisu-manager.apk` | 不绑系统版本 | `assets/` 顶层 |

取件路径由 `DeployScript.assetPath(Build.DISPLAY, name)` 决定，**部署开始前** `Deployer.ensureSystemAssets()`
会先查这个目录在不在：不在就直接报"当前系统未被适配"并列出已适配清单与修法，不会推进到推送文件。
该目录下的 `SYSTEM.txt` 是版本标注本身（build / 机型 / 内核 / KSU 版本 / UAPI / 每个产物的 md5），
`./build.sh sync` 会逐项核对它的 md5，并校验 **目录 ↔ `ADAPTED_BUILDS`** 互相覆盖。

- OriginOS 大版本：`ro.vivo.os.version` 与 Android 版本号对齐（设备实测 `15.0`），
  所以 `>= 10` 时减 10 才是 OriginOS 大版本（`15.0` → OriginOS 5）；拿不到时用 SDK 兜底
  （34 → OriginOS 4、35 → OriginOS 5）。
- 状态卡「设备」一行给出短结论（`已适配` / `未适配（版本不同）` / `非目标机型` / `版本超出漏洞范围`）；
  点「一键提权」时若未适配，会先把完整判定与风险写进确认弹窗，再让用户决定是否继续。
- 调试入口：`am start -n com.neoroot.ksuonetap/.MainActivity --ez no_root true`
  可强制按「无 root」渲染（用于验证按钮门控，不必卸载 KSU）。

## 部署阶段（日志标记与 v1.0.4 对齐）

| 阶段 | 做什么 |
|---|---|
| `[1] 部署文件` | 按模式推 assets（完整模式 8 个 / 仅提取 root 模式**只推 1 个**），4 线程并行流式写入 `/data/local/tmp` + stat 校验大小 + chmod 755；仅提取 root 模式会先清掉 `u0` 与 KSU 那套 |
| `[2] 启动 exploit` | 先 `killall -9 exploit_vivo_neo9` 清掉旧实例（新版有并发防护），再由 Shizuku(shell 域) 起：`CHEESE_SU=1 CHEESE_SU_QUEUE=1 CHEESE_PATCH_CAP=1 CHEESE_STEXT_PA=0xa8010000`（两种模式都带队列 —— 「激活 KSU」要用它，空转不留文件） |
| `[3] 等临时 root` | 轮询 `rootd_ready.txt`；**若 exploit 进程已死会立即报 `!! EXPLOIT DEAD` 并贴日志尾部** |
| `[4] 加载 kernelsu` | 经 rootd 文件队列执行（rootd 域有 caps，u0 才能 setuid(0)）：`mkdir -p /data/adb` → `cp ksud /data/adb/ksud` → `u0 ksud insmod kernelsu-vivo.ko allow_shell=1` |
| `[4a] 模块确认` | `grep kernelsu /proc/modules` |
| `[4b] 等 su` | 轮询 `su_ksu -c id` 直到出现 `uid=0` |
| `[4c] unpatch + vrpatch` | `su_ksu -c 'ksud insmod unpatch.ko'`、`... vrpatch.ko` |
| `[4c2] 复核模块` | 再读一次模块行，确认三个都在 |
| `[4d] 结束临时 root` | `killall -9 exploit_vivo_neo9` |
| `[5] 验证` | `su_ksu -c id` |
| `[6] 安装 Manager` | `su_ksu -c 'pm install -r -d /data/local/tmp/resukisu-manager.apk'` |

> 为什么 `[4]` 一定要走 rootd：kernelsu 加载前 `u0` 的 `setuid(0)` 需要 caps，Shizuku 的
> 普通 shell 域没有。而 kernelsu 一加载，rootd 那侧 kernel-sid 的 sh 就再也 exec/写不了
> 任何东西（KSU execve hook 生效），所以后续收尾必须换 App 走 `su_ksu`。
>
> `/data/adb/ksud` 那一步是新版 sucompat 的硬依赖：内核把 `/system/bin/su` 重定向到
> `/data/adb/ksud`，未就位时提权会直接失败。所以必须在 insmod **之前**（rootd 还能写文件时）拷好。

## 构建

```sh
cd apk/ksuonetap
bash build_ksuonetap.sh                # 产物: out/KSUOneTap-v1.0.6.apk
bash build_ksuonetap.sh --clean        # 先清 out/（keystore 在 .keystore/ 不会被删）
```

依赖（脚本会自动探测，可用环境变量覆盖）：

| 项 | 默认探测位置 | 覆盖变量 |
|---|---|---|
| Android SDK | `~/Android/Sdk` | `ANDROID_SDK_ROOT` |
| JDK 21 | `~/.workbuddy/binaries/jdk21` | `JAVA_HOME` |
| Kotlin 编译器 | `~/.workbuddy/binaries/dl/kotlinc-dist/kotlinc` | `KOTLIN_HOME` |

构建链：`aapt2 compile/link` → `kotlinc` → `d8` → 打入 `classes.dex` → `zipalign` → `apksigner`。
（Kotlin 标准库会一并进 dex；Shizuku 的三个 aar 从 `deps/` 解出 class 一起 dex
—— 全反射调用，编译期不依赖。）

### 签名与原地升级

设备上已装的 KSUOneTap（含 v1.0.4）用的是证书
**SHA-256 = `64572e28efd90f2cf8a68022319b7efbfe700ff8ff611efb30d980b989c38a08`**
（即旧 PS1 在 `out-onetap/` 生成的那个 `ksuonetap.keystore`，口令均为 `ksupass`）。

```sh
KEYSTORE=/path/to/ksuonetap.keystore bash build_ksuonetap.sh
# 脚本末尾会打印证书指纹并与上面这个值比对:
#   ✓ 一致 -> 可直接 adb install -r 原地升级
#   ✗ 不同 -> 会 INSTALL_FAILED_UPDATE_INCOMPATIBLE, 只能卸载重装
```

默认 keystore 在 `apk/ksuonetap/.keystore/ksuonetap.keystore`（首次构建自动生成，
**不放 out/**，所以 `--clean` 不会删掉它 —— 否则每次重建都换签名）。

### 更换 ReSukiSU Manager

部署最后一步装的管理器取自 `assets/resukisu-manager.apk`。要换成上游新版或
vivo 适配的重打包版：

```sh
MANAGER_APK=~/Desktop/ReSukiSU_v4.2.0-rc3_35184-arm64-v8a-release.apk bash build_ksuonetap.sh
# 不指定时脚本会在工程目录/上一级自动找 ReSukiSU-manager-*.apk
# 日志会打印管理器 versionCode，并**与 assets/<版本>/SYSTEM.txt 的 `resukisu=` 比对**
# （管理器的 versionCode 与驱动的 KSU_VERSION 是同一套版本号，应当一致）
```

> 重打包版的含义：拿官方管理器 APK，把它的 `assets/ksud` 与 `assets/kernelsu.ko`
> 换成 vivo 可用的那两份（就是本工程 `assets/` 里的），再重签名。
> 上游仓库自带 `source/resukisu/repack_apk*.py` 可做同类替换。

## 软重启: `ksud soft-reboot` + `vrpatch.ko` 中和 vr.ko (关键!)

**ReSukiSU Manager 的软重启 = `ksud soft-reboot`** (源码: KsuCli.kt `reboot("soft_reboot")`
→ `execKsud("soft-reboot")`; ksud init_event.rs `soft_reboot()`)。

它完整模拟 Android boot 流程:
1. `resetprop sys.boot_completed 0` (重置 boot 标志)
2. `run_stage("emulated-soft-reboot")` (模块脚本)
3. `stop` → **`on_post_data_fs()`** (restorecon / sepolicy / 模块挂载 / 事件流)
4. `start` → services → `wait_for_boot_completed` → boot-completed

**vivo vr.ko 误杀 netd 的根因与治本 (vrpatch.ko)**:

- vr.ko (反 root 检测) 挂 kprobe 在 avc_has_perm/do_init_module 等,
  检测 `current->cred->euid==0` 且非 vrp 域进程 → **force_sig(SIGABRT, SI_QUEUE)**。
- **KSU 加载后软重启** (无论 stop&&start 还是 ksud soft-reboot):
  zygote 重新 exec 触发 KSU execve hook → 大量 SELinux 检查 → vr.ko 检测到
  zygote/netd (uid 0) → 击杀 → netd SIGABRT 循环 / zygote restarting → 半启动卡死。
- **治本方案: `vrpatch.ko`** (`test-modules/vrpatch/`):
  `find_module("vr")` → text 基址 + 0x2ecc = 检测函数入口
  → `set_memory_rw` (模块内存是 vmalloc, 可用) → 写 `mov w0,#0; ret`
  → 检测函数直接返回 0 = "无异常", vr.ko 不再击杀任何 uid 0 进程。
- **软重启不重载内核模块 → patch 持久生效** (硬重启后重新部署)。
- 实测: 无 vrpatch 时 netd 崩 26+ 次/5min + zygote 循环;
  加载 vrpatch 后 **netd 0 崩溃, zygote 稳定, uptime 连续, 网络持续 OK**。
- (sys.powerctl reboot,soft 在 vivo 上会变硬重启, 不可用)

## 关键技术点 (移植其他设备必读)

| 项 | 说明 |
|---|---|
| **静态 ELF 不可用** | untrusted_app 域 seccomp 杀静态链接 ELF (SIGSYS, rc=159); 必须 NDK 动态链接 (bionic + linker64) |
| **vhangup 被 seccomp 拦** | app 域调 syscall(58) 直接 SIGSYS → 必须经 shizuku (shell 域) 启动 exploit |
| **STUB_MODE=13** | exploit 内嵌 stub: euid 保持非 0 (vr.ko 不触发) + fsuid/fsgid=0 + 全 caps + cred->security sid=kernel (SELinux 放行 module_load) |
| **insmod 必须经 rootd** | u0 (setuid 0) 需要 caps 域; shizuku 普通 shell 无 caps, 直接跑会失败 |
| **suc fscompat 需要 /data/adb/ksud** | 新版内核把 /system/bin/su 重定向到 /data/adb/ksud, 必须在 insmod 前拷好 |
| **binder 1MB 限制** | 大文件不能用 base64 命令行内联 (32MB parcel 超限); 用 Process.getOutputStream() 流式写 |
| **软重启方式** | `ksud soft-reboot` (ReSukiSU 同款); 裸 stop&&start 触发 vr.ko 误杀 netd; reboot,soft 变硬重启 |
| **⛔ 禁 CHEESE_PATCH_KPROBE** | 全局 patch kprobe_dispatcher 会让 vivo init 依赖的 kprobe 失效 → init SIGABRT 硬重启 |
| **shizuku 集成** | 需要 3 个 aar: api (rikka.shizuku.*) + aidl (moe.shizuku.server.*) + provider (ShizukuProvider); manifest 注册 provider authorities=包名.shizuku, exported=true; `${applicationId}` 占位符 aapt2 不替换需写死 |
| **minSdk 必须显式** | aapt2 link 需 `--min-sdk-version 28 --target-sdk-version 34`, 否则 vivo 兼容模式出怪问题 |
| **aapt2 样式名带点** | `Text.SectionTitle` 会被当成隐式继承去要父样式 `Text`; 需显式 `parent=""` |
| **Kotlin 块注释可嵌套** | 注释里写 `deps/*.aar` 这种 `/*` 会开嵌套注释 → "Unclosed comment"; 注释里别出现 `/*` |

## 设备适配

- `DeployScript.kt` 的 `DEV` / 文件名常量 / exploit 资产名
- `Deployer.kt` 里启动 exploit 的 `CHEESE_STEXT_PA` 与参数
- `exploit_vivo_neo9` 需用目标设备的提权漏洞源码 + STUB_MODE=13 重新编译
- `kernelsu-vivo.ko` / `unpatch.ko` 需目标设备内核构建
  （`KERNEL_TREE=/path bash scripts/build_ksu_module.sh`，见 docs/KERNEL_INTEGRATION.md）

## 红线

- ⛔ 禁止 boot patch (BL 未解锁, vbmeta 校验会变砖)
- ⛔ 不触碰 /system (erofs + dm-verity)
- ⛔ 禁止 CHEESE_PATCH_KPROBE (全局 kprobe_dispatcher patch 导致 init 崩溃)
- 重启后需重新: 激活 shizuku → 打开 App 点一键提权 (LKM 是 RAM 态)

## 授权与使用

**本工具仅供安全研究使用，请勿用于非法用途。** 第三方组件（KernelSU / ReSukiSU 上游、
Shizuku API、自研内核模块等）的版权归各自作者，许可与源码位置按各自原样保留在对应目录，
不再逐个抄录。`LICENSE` 文件保留在本目录。

### 随包二进制的源码在哪

`assets/` 里每个二进制的源码、构建方式、设备侧用法，都索引在
**[`src/root_files/README.md`](src/root_files/README.md)**：`exploit_vivo_neo9/`、`u0/`、
`unpatch.ko/`、`vrpatch.ko/`、`kernelsu-vivo.ko/`、`ksud/`、`su_ksu/`。

其中 `ksud` 按约定**只写 README 不给源码** —— 它由 ReSukiSU 管理器 APK 提取而来，
源码属上游（ReSukiSU 仓库 `userspace/ksud/`，本仓库已 vendor 在
`source/resukisu/userspace/ksud/`）。

### 与 2026-09-27 早先口径的关系

当天早些时候曾定为「只开源本应用（MIT）、其余不公开」。改为 GPL-3.0 的原因：
随包模块的**源码现在也放在 App 工程内**（`src/root_files/`），"开源目录里装不公开源码"
无法自洽；统一到 GPL-3.0 后与上游 ReSukiSU 组件同向。

## 真机验证记录（v1.0.6 / `PD2338_A_14.0.17.2.W10.V000L1`）

**2026-09-28 实测通过** —— 该版本的首次真机验证，分两步：
03:21 走通 **LPE / 临时 root**（当时该版本还是 `lpe-su` 档位），
随后 3 个内核模块全部编出并加载成功，该版本**由 `lpe-su` 升为 `full`**。

设备：iQOO Neo9 `PD2338` / `V2338A` / OriginOS 4（`ro.vivo.os.version=14.1`）/ 内核
`5.15.137-gc870e76526d2-dirty`；App：versionCode 8 / v1.0.6，签名 `94122eb6…`。

### ① LPE / 临时 root

App 侧日志（`logcat -s KSUONETAP`）：

```
[0] 系统版本目录: assets/PD2338_A_14.0.17.2.W10.V000L1/ (绑定产物 1 个, 仅 LPE/临时 root)
    设备软件版本: PD2338_A_14.0.17.2.W10.V000L1
== [1] 部署文件 ==  [deploy] exploit_vivo_neo9 (71200B) ok   结果: 成功 1 / 失败 0 (共 1)
== [2] 启动 exploit（Shizuku shell 域）== started
== [3] 等待临时 root 就绪 ==  临时 root 就绪（40s）
*** 临时 ROOT OK (未部署 KSU, su 可用) ***
```

> 那时该版本还是 `lpe-su`，所以日志里是「绑定产物 1 个」；升为 `full` 后这里会显示 4 个。

exploit 自身日志（`/data/local/tmp/exploit_daemon.log`）——**三个版本绑定偏移写下去即校验通过**：

| 补丁点 | 物理地址 | 校验 |
|---|---|---|
| `__arm64_sys_vhangup` | `0xa85e2db4`（= `stext_pa 0xa8010000` + `0x5d2db4`） | 整段 96 B 写回校验通过 |
| `cap_bprm_creds_from_file` | `0xa893937c`（`+0x92937c`） | 读回校验通过 |
| `kptr_restrict` | `0xaacddcb4`（`+0x2ccdcb4`） | 读回校验通过（相邻 `ptr_key` 保留） |

SELinux permissive 写入 verify 通过；`[stab] 写校验统计: 通过 14 次, 降级 0 次, 读故障 0 次`；
提权后 `CapEff 000001ffffffffff`。独立复验（adb）：`/data/local/tmp/su -c id` →
`uid=0(root) … context=u:r:shell:s0`。

> **已知现象（不影响结果）**：exploit 前置的符号自检里 `init_task` 的读回标记偶发超时
> （`init_task bulk qword read failed` → `symbol sanity read failed` → `FW symbol verification
> failed; continuing anyway`，该检查设计为失败即跳过）。同一次运行里 14 次写回读校验全过、
> 读路径探针正常，判为偶发读超时而非地址错。要不要给它加"与 `[stab]` 同款重试"待定。

### ② 3 个内核模块（该版本随后升为 `tier=full`）

装载走 `ksud insmod`（= `ksuinit` 用户态手工装载：用 `/proc/kallsyms` 填未定义符号，
首次失败后读 kmsg 取内核要求的 vermagic、在内存里替换再重试）：

```sh
./su -c "./ksud insmod /sdcard/kernelsu-vivo.ko"   # → Loaded kernel module: …   [rc=0]
./su -c "./ksud insmod /data/local/tmp/vrpatch.ko"
./su -c "./ksud insmod /data/local/tmp/unpatch.ko"
```

```
/proc/modules →  kernelsu 229376 1 - Live
                 vrpatch  16384 0 - Live
                 unpatch  16384 0 - Live
id           →  uid=0(root) … context=u:r:ksu:s0        (KSU 域生效)
```

| 模块 | md5 | 关键版本绑定值 |
|---|---|---|
| `kernelsu-vivo.ko` | `d7546a32ed485aa32ec545a3589c620d` | vermagic 由 `5.15.178-…` 等长对齐到 `5.15.137-gc870e76526d2-dirty`（`cmp -l` 仅 14 字节不同） |
| `vrpatch.ko` | `ddc744a3c47d3a2e0327d5e2158ec052` | `VR_DETECT_OFFSET = 0x2ecc`（vr.ko `.text` 内检测函数偏移） |
| `unpatch.ko` | `40a742973da7b291460f474f7ef71efe` | `CAP_BPRM_PA = 0xa893937c` / 原始字节 `d10243ffd503233f` |

> `kernelsu-vivo.ko` **不是为本版本重编的** —— 该固件是 vivo 自研内核、没有公开源码树。
> 它是 15.1.14.7 那份真重编产物经 `scripts/patch_ko_vermagic.py` 对齐 vermagic 得到的
> （生成命令与理由见 `kernel-module/out/README.md`）+ `test-modules/build.sh` 的 137 profile。
>
> ⚠️ 这条路**不要**走 `ksud late-load --kmi android14-5.15`（装官方 GKI 模块）：2026-09-28
> 实测**内核 panic** —— `ksuinit` 能改 vermagic、能填符号，但改不了 GKI 模块与厂商内核之间的
> `struct module` 布局/CFI 差异。判据见 `neo9-root/docs/LKM_ON_OTHER_KERNEL.md`。

> **该版本的模块加载是手工 `ksud insmod` 验证的** —— 还没跑过「由 App 自动推 3 个模块 +
> 自己 insmod」那条完整流程（需要在装有临时 root 或 adb root 的机器上点一次）。

### ③ KSU su 入口解析（2026-09-28 修的一个判定 bug）

现象：**手工装 KSU**（`adb root` + `su -c "ksud insmod …"`）后，主页出现
「KernelSU 已加载」+「Root 无 root」的矛盾状态，且「软重启」「清理并重启」被置灰。

根因：KSU 通道的判据写死成 `$DEV/su_ksu -c id`（随包产物），而这条路上设备上根本没有
`su_ksu` —— 真正的入口是 KernelSU 自己装的 **`/system/bin/su`**（5.6 MB，与 ksud 同源）。
当时 `.allowlist` 里已有 `com.neoroot.ksuonetap` 与 `moe.shizuku.privileged.api`，
`/system/bin/su -c id` → `uid=0`，**root 客观上可用**。

改法：`Terminal.ksuEntry()` 按优先级**真执行一次 `id`** 依次探
`$DEV/su_ksu` → `/system/bin/su` → `/data/adb/ksu/bin/su` → `su`，探到即缓存；
所有 KSU 调用统一走 `Terminal.ksuRun()`。约定见 `DESIGN_NOTES.md` §8。

修后实测（同一台设备）：

| | 修前 | 修后 |
|---|---|---|
| 主状态 | `未就绪` / 点下方「一键提取 Root」开始 | **`工作正常`** / KernelSU 已激活 · su 可直接使用 |
| Root | `无 root` | **`uid=0 (KSU)`** |
| 终端通道行 | — | `Shizuku 已激活 · KSU 可用（su）· 临时 root 离线 · 通道「KernelSU」` |
| 「软重启」/「清理并重启」 | `enabled=false`（置灰） | **`enabled=true`** |

> 这次**只是读界面**（`uiautomator dump`），没有点任何会改设备的按钮。

### ④ 主页改版 + 日志落盘（2026-09-28 验证）

装上这一版后逐项看过界面（`uiautomator dump` 读控件，只读）：

| 项 | 结果 |
|---|---|
| 主页不再有日志区 | 滚到底只有 4 个操作按钮 → dock，**没有**「运行日志」 |
| 主按钮文案 | **「一键 Root」** |
| 日志页时间戳 | `[05:44:56] == KSUOneTap v1.0.6 ==` / `[05:44:56] uid=10331` |
| 日志页操作栏 | 复制 / **导出** / **历史** / 清空 |
| **日志已落盘** | `<filesDir>/logs/2026-09-28.txt`（用 `/system/bin/su -c` 读到：目录 `drwx------ u0_a331`，
文件 55 B，内容就是上面那两行带时间戳的日志）⇒ **按日期分文件 + 逐行落盘都生效** |
| App 未崩溃 | events buffer 里只有正常的 `pause/stop`，无 `am_crash`；logcat 无 `FATAL EXCEPTION` |

> **未验证（如实说明）**：
> 1. **进度 UI（转圈 + 百分比 + 阶段名）没在真机上点验** —— 点「一键 Root」会让 App
>    **自动 `ksud insmod`**（这台设备上模块已加载，会重复加载），按本项目红线不做。
>    渲染逻辑见 `DESIGN_NOTES.md` §9.3，下次点一次即可看到。
> 2. 「历史」对话框与「导出」分享面板也没点（点「历史」的时候你正好切到酷安回帖，
>    前台变成 `com.coolapk.market`，我停手了，没有继续操作手机）。

## 真机验证记录（v1.0.5）

> 本节记录的是 **v1.0.5** 那次上机（设备系统 `PD2338_A_15.1.14.7.W10.V000L1`，`tier=full`）。
> v1.0.6 的改动已在上一节（14.0.17.2，LPE + 3 个模块加载）验证过；
> **15.1.14.7 那条尚未用 v1.0.6 回归**。下面表格里的 `v1.0.5` 字样是当时的原始记录，不回改。

设备：iQOO Neo9 `PD2338` / `V2338A`，系统 `PD2338_A_15.1.14.7.W10.V000L1`（build 与目标版本一致）。
App：versionCode 7 / versionName v1.0.5，签名 SHA-256 `94122eb62b635a29…`。

| 项 | 结果 |
|---|---|
| 设备判定 | 状态卡「设备」= **已适配**；设置页显示 `OriginOS 5 (ro.vivo.os.version=15.0)` / `Android API 35` |
| 状态行 | 设备/Shizuku/KernelSU/Root 四行标签与值都完整（值过长时省略号截断，不再挤掉标签） |
| 按钮门控 | 「清理并重启」只要有能提权的客户端（KSU 的 `su_ksu` **或**临时 root 的内置 `su`）即可点；「软重启」要 `ksud`，**只有 KSU 在线时才可点** —— 只有临时 root 时前者可点、后者置灰；`--ez no_root true` 强制无 root → 两者都 `enabled=false` 且淡化 |
| 「激活 KSU」 | 设备处于「临时 root 在手 + KSU 未激活」时出现并**取代「软重启」**。`uiautomator` 实测按钮区为 `一键提取 Root / 激活 KSU / 刷新状态 / 激活 Shizuku / 清理并重启`（**无「软重启」**），状态卡为 `KernelSU=未加载` / `Root=uid=0 (临时 root)` |
| 激活后切换 | 「激活 KSU」成功后隐藏并换回「软重启」—— 这一步**未实测**（会真的加载内核模块，按红线由人工执行） |
| 一键提取模式 | 切到「仅使用 root」后返回，主按钮文案变为「一键提取 Root」；两项互斥高亮 |
| 内置终端 | 通道下拉 4 项顺序正确；`id` 在 KernelSU 通道 → `uid=0` + `[rc=0]`；切 Shizuku shell → `uid=2000(shell)` + `[rc=0]` |
| 日志/输出滚动 | 长输出（`getprop` 几百行）自动跟随到底部；手动上滑可读历史且出现「↓ 回到最新」；点它回到底部 |
| 应用图标 | 安卓机器人（头 / 身 / 天线 / 手臂）**举着金色钥匙**的矢量图标；真机桌面确认圆角遮罩下完整未裁 |
| dock 四页 | `am start` 分别拉起主页 / 日志 / 设置 / 关于 → 四个 Activity 同时存在于任务栈, **零崩溃**（crash buffer `0`, 无 `InflateException` / `ClassNotFoundException`） |
| 主题与资源入包 | `aapt2 dump resources` 确认: 6 套色板 style、6 个 `ksu*` attr、`view_dock` / `activity_log` / `activity_about` / `ic_dock_*` 与全部新增字符串都在 APK 内 |

**已验证（2026-09-26 补充）**：「临时 root」通道 —— 真机实测 `su -c 'id'` 返回
`uid=0(root)`；命令引用处理用 5 类用例逐条验过（空格参数 / 管道+单引号 / 双引号+重定向 /
多行 / 必定匹配的单引号用例），全部输出正确。

**域已从 `vrp` 改为 `u:r:shell:s0`**（原因见「为什么不用 vrp 域」）：用 App 真正下发的
命令形式实测 `cmd package path frb.axeron.manager` → 成功、`service list` → 398 个服务、
`pm list packages` → 正常；120 轮压测进程全程存活，域与 `uid=0` 均稳定。

**未验证**：本版「仅提取 Root」一键流程的**端到端** —— 换 exploit 二进制后需要重跑一次
漏洞命中（`spray`），所以命令层与通道层分别验证过，整链路由你在冷窗口点一次「一键提取
Root」确认。

**已验证（2026-09-27 补做，设备解锁后完整走查）**：

| 项 | 结果 |
|---|---|
| dock 四页 | 依次点 主页 / 日志 / 设置 / 关于，`topResumedActivity` 每次都对；图标与选中态染色正常 |
| 日志页 | `LogBus` 内容正确（`== KSUOneTap v1.0.5 ==` / `uid=10360`），顶栏复制 / 清空在位 |
| 设置页 | 「仅使用 root」单选态正确；**外观卡片** 6 个色点颜色与色板一致，选中项带圆环 + 色板名 |
| 关于页 | 头部 K 圆标跟随主题色，应用信息 / 适用范围卡片正常 |
| 主题切换 | 绯红 → 蔚蓝 → 切回绯红：顶栏、状态栏、选中描边、dock 高亮、色点环**全部跟着变** |
| 桌面图标 | 机器人 + 金色钥匙在圆角遮罩下完整显示（截桌面并放大核对） |
| 崩溃 | 全程 crash buffer **0** |
| **跟手分页（三轮）** | 左滑逐页 → 日志 → 设置，dock 高亮逐页跟随；右滑回退；**半程拖动 220px（< 半屏 630px）松手回弹**、不误翻页；点 dock 切页正确 |
| 换色板重建 | 在设置页换色板 → 主题立即变，且 `recreate()` 之后**仍停在设置页**（容器记页序号） |
| 终端页 dock | 从主页顶栏进终端后 dock 在位（bounds 与四页一致），四项均不高亮 |
| 深色模式顶栏 | 切深色后顶栏为深灰渐变（`#1B1A20 → #2B2930`）配浅字，清晰可读；主按钮 / 状态胶囊 / dock 配色协调 |
| 顶栏与关于的品牌图形 | 46dp 顶栏圆标与 62dp 关于页圆标都显示机器人 + 钥匙（不再是字母 `K`），截图放大核对过 |

> 前一天那次截图时设备处于**锁屏**（`mDreamingLockscreen=true`），锁屏下 `screencap` 只能拍到
> 锁屏/指纹界面，也没有解锁它（不做关闭锁屏这类降低设备安全性的操作），所以视觉验证推迟到
> 设备解锁后进行。页面能否构建当时已由「四个 Activity 都成功启动且零崩溃」证实。

## 源码整理记录（2026-09-26）

| 项 | 内容 |
|---|---|
| 包结构 | 11 个平铺文件 → `core`(6) / `deploy`(3) / `ui`(4)，共 13 个（新增 `Channel.kt`、`B64.kt`） |
| 依赖方向 | 拉直为 `ui → deploy → core`（原先 `Prefs → Terminal` 是 core 反向依赖 deploy） |
| 死代码 | 删 `Deployer.status()`、`ShizukuBridge.context()`；`Shell.exec`、`Terminal.rcOf/stripRc` 收为 `private` |
| 去重 | base64 三处收敛到 `core/B64`；`Terminal.execWrapped` 的 KSU / SHELL 分支合并 |
| 死资源 | 删 14 个未引用字符串（`stage_*`×8、`dlg_gate_title`、`hint_shizuku_ok`、`term_stop/quick/clear_screen/queued`） |
| 归档 | `apk/KSUOneTap.apk`（1.0.0_beta1）、`apk/resukisu-manager.apk`（35072）→ `apk/archive/` |
| 许可证 | `NOTICE` 原写「本工程 = GPL-3.0」与 LICENSE(MIT) 矛盾，当时按「自有代码 MIT + 集成组件各自许可」重写（**口径当天又调整过两次，最终为全工程 GPL-3.0，见下**） |
| **回归修复** | 分包时漏改 layout XML 里的 `InnerScrollView` 全限定名 → 装机闪退；已修，并把「查非代码文件里的点分类名」补进流程 |
| **内置 su** | `assets/<系统版本>/exploit_vivo_neo9` 换成 `-DCHEESE_SU_DEFAULT=1` 版本（31KB→71KB）；「临时 root」通道改走 `su -c`；仅提取 root 模式只推 exploit 并清理 u0 与 KSU 那套资产（约 30MB） |
| **部署前置清理** | 启动 exploit 前先 `killall` 旧实例（新版有并发防护，不清掉根本起不来），并清掉旧就绪标记与 `su.sock` |
| **清理并重启** | 提权客户端按环境选（`su_ksu` **或**内置 `su`）—— 原先写死 `su_ksu`，只提取 root 时点了必然失败；「软重启」单独门控到 KSU 在线；清理项补 `*.sock` / `.cheese*` / `.kt_term.sh`（`*.sh` 在 sh 里**不匹配**以点开头的文件） |
| **激活 KSU** | 新增功能：临时 root 在手时把「软重启」换成「激活 KSU」，一键接着上 KSU（**不用重跑漏洞**）；`run()` 的 `[4]~[6]` 抽成 `finishKsuDeploy()` 供两处共用；队列不在时用 `--su-server` 现补 |
| **临时 root 域** | 从 `u:r:vrp:s0` 改为 **`u:r:shell:s0`** —— vrp 是 vivo 自定义域，权限只够跑裸命令（`service list` → `Found 0 services`、`cmd package` → `Can't find service: package`，avc 里 `permissive=0`），导致 Axeron/AxManager 这类依赖系统服务的工具直接起不来；现在启动传 `CHEESE_U0_CTX` + 终端执行前 `setcon` 双保险 |
| **`[rc=N]` 去重** | `stripRc` 改为循环剥离 —— 临时 root 通道会叠两个（脚本自己的 + 内置 su 客户端补的） |
| **界面/图标** | 新增 KernelSU 风格的大状态卡（一句话结论 + 下一步 + 版本号）；顶栏去掉冗余副标题；图标换成机器人 + `#` |
| **底部 dock** | 新增 `view_dock.xml` + `Dock.kt`：主页 / 日志 / 设置 / 关于四项，图标用 `setColorFilter` 染色，跳转带 `REORDER_TO_FRONT`；主页顶栏撤掉「设置」按钮（只留「终端」），设置页去掉返回键（dock 即导航） |
| **日志独立成页** | 新增 `core/LogBus`（全局缓冲 + 主线程订阅）与 `LogActivity`：主页日志区与日志页**同源**，切页面不丢历史；主页日志区仍保留（部署时要看实时进度） |
| **关于独立成页** | 原设置页的「关于」卡片迁到 `AboutActivity`，拆成应用信息 / 适用范围 / 开源许可 / 声明四块，并补上组件许可行（现口径：本工程 GPL-3.0 / Shizuku Apache-2.0） |
| **主题体系** | 新增 `attrs.xml` + `themes.xml`(含 night) + `core/Theme.kt`：6 套色板 × 深浅两版，主题色一律改走 `?attr/ksu*`；设置页新增「外观」卡片（色点选色板 + 三档深浅）；色点颜色从 style 里读 `ksuPrimary`，不重复维护色值 |
| **顺带修正** | 浅色主题原先 `windowLightStatusBar=true`（深色图标压在紫色顶栏上，对比度差）→ 改 `false`，并补上 `windowLightNavigationBar`；`colors.xml` 清掉随主题迁移的 8 个 accent 色与 6 个未引用项 |
| **图标（2026-09-27）** | 由「机器人 + `#`」改为**安卓机器人举着金色钥匙**：机器人缩到头 32×21 / 身 34×22 才腾出右侧空间（安全区是中心半径 ~33 的圆，并排两个主体时宽度预算很紧）；钥匙用 `#FFD35C`，与天线端点呼应 |
| **许可口径（2026-09-27）** | 当天两次调整，最终定为 **全工程 GPL-3.0**：先删掉 `LICENSE_GPL3.txt`（原给 `unpatch/vrpatch` 挂 GPL-3.0，与当时「不公开」口径矛盾）并把 LICENSE 从 MIT 换成 GPL-3.0 全文；`NOTICE` / 根 `README` / 本文件 / App 关于页统一。原因：随后在 `src/root_files/` 里提供了随包模块的**源码**，"MIT 开源目录内装不公开源码"无法自洽；统一 GPL-3.0 后与上游 ReSukiSU 组件（同为 GPL-3.0）同向 |
| **随包源码索引（2026-09-27）** | 新增 `src/root_files/`：按 7 个部署物各建一个目录（`exploit_vivo_neo9` / `u0` / `unpatch.ko` / `vrpatch.ko` / `kernelsu-vivo.ko` / `ksud` / `su_ksu`），每个带 README 说明「源码在哪 / 怎么重建 / 设备侧怎么用 / 许可」；新增 `patches/kernel-integration.patch`（vivo 内核的 3 处集成改动）；镜像一致性并入 `./build.sh sync` |
| 构建 | `bash build_ksuonetap.sh` 通过（21 个源文件 / 4 个包）；产物 `out/KSUOneTap-v1.0.5.apk`（code 7 / v1.0.5 / 签名 `94122eb6…`） |
| **授权口径简化（2026-09-27 二次）** | 作者决定**不再逐一标注版权声明**（本工具只用于安全研究）：App 关于页的「开源许可」卡片（4 行组件许可 + 长说明）删成一行「本工具仅供安全研究使用」，章节改名「授权与使用」；`NOTICE` 由 66 行瘦到 17 行（去掉许可表与修订记录）；根 `README` 与本文件的「开源许可」章节改为「授权与使用」；`src/root_files/*/README.md` 的许可小节只留血统与归属；`LICENSE` 文件保留 |

> **已清理（2026-09-26 13:32）**：迁移时留下的 6 个 **0 字节空壳**
> （`src/com/neoroot/ksuonetap/` 下的 `Prefs.kt`、`DeviceGate.kt`、`Terminal.kt`、
> `SettingsActivity.kt`、`TerminalActivity.kt`、`UiKit.kt`）**已全部删除**。
> 首次清理时它们被沙箱的 bind-mount 锁住（`rm` 报 EBUSY、`umount` 报 not mounted），
> 换个不再持锁的会话就能正常 `rm`。同一原因当时也保留下来的
> `res/drawable/bg_option.xml`（无引用的 selector）**一并删除**。
>
> `build_ksuonetap.sh` 里"跳过 0 字节 `.kt`"的逻辑**保留作防御**：将来若再出现迁移残留，
> 构建日志会明确提示，而不是静默混进编译。
