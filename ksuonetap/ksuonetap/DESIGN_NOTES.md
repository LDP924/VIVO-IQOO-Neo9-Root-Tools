# KSUOneTap 设计约定

> 从 `.workbuddy/memory/MEMORY.md` 迁出（2026-09-27）—— **App 内部的实现约定**都记在这里，
> 跨切面的红线/构建/版本锚点仍在 `MEMORY.md`。
> 目录树与使用说明见 [README_KSUOneTap.md](README_KSUOneTap.md)。

## 1. 包结构与依赖方向

三包，依赖**单向** `ui → deploy → core`：

- `core/` — Shell · ShizukuBridge · SysProps · DeviceGate · Prefs · Channel · B64 · LogBus · Theme
- `deploy/` — Deployer · DeployScript · Terminal
- `ui/` — `PagerActivity` + `ui/page/*` · `TerminalActivity` · Dock · UiKit · SwipePager

core 不认识 UI 与部署；deploy 不认识 UI。跨包必须**显式 import** ——
包括 `import com.neoroot.ksuonetap.R`（Kotlin 不会自动导入外层包）。

`AndroidManifest.xml` 的 activity 写**全限定名**（已不在根包）。

**改包名 / 移动类之后必须全局查"点分类名"引用**：layout XML 的自定义 View、manifest 的
activity / provider / parentActivityName、prefs XML 等**都是字符串形式的类名**，
编译器与 aapt2 都不校验（构建全绿但一跑就 `ClassNotFoundException`）。查法：

```sh
grep -rn "com\.neoroot\.ksuonetap" apk/ksuonetap/res apk/ksuonetap/AndroidManifest.xml
```

再逐条与 `classes.dex` 实际类名对账。**最终必须装机启动一次才算验证完** ——
2026-09-26 就是漏了 layout 里的 `InnerScrollView` 导致主页闪退。

## 2. 构建与签名

- 构建：`bash apk/ksuonetap/build_ksuonetap.sh`（无 gradle：aapt2 → kotlinc → d8 →
  zip 塞 dex → zipalign → apksigner）。脚本会**跳过 0 字节 `.kt`**（防御性 ——
  2026-09-26 迁移留下的空壳已全删，这段留着防将来再出现）。
- 签名：`.keystore/ksuonetap.keystore` = 证书 `94122eb6…`，与设备上现装的一致，
  可直接原地升级（旧 v1.0.4 是 `64572e28…`）。
- 版本号只改 `AndroidManifest.xml` 的 versionCode / versionName（当前 **8 / v1.0.6**）。
  特性迭代 → 小版本 +1、versionCode +1；App 显示的版本号是运行期从 `packageManager`
  读的，所以只需改这一处。
- 换管理器：`MANAGER_APK=<新 APK> bash build_ksuonetap.sh`；配套的 `ksud` 用
  `bash scripts/fetch-manager.sh --to-assets` 从管理器 APK 里重提（见 MEMORY.md 的版本锚点）。

## 3. 界面结构 = 单 Activity + 跟手分页 + 底部 dock

- `PagerActivity` 是唯一入口 Activity；四个页面是 `activity_pager.xml` 里 `SwipePager`
  的四个子 View（`page_home` / `page_log` / `page_settings` / `page_about`）。
- 页面逻辑在 `ui/page/`（`HomePage` / `LogPage` / `SettingsPage` / `AboutPage`），
  都继承 `Page`（`ui/page/Page.kt`）；生命周期由容器转发成 `onShow` / `onHide`，
  **必须幂等**。
- **页面里所有 findViewById 走 `root`**（`Page.id()`）：四个 layout 有同名 id
  （`topBar` / `tvLog` / `btnCopyLog` …），从 Activity 查会串页。
- 终端 `TerminalActivity` 仍是独立二级页（入口在主页顶栏，保留返回键），用转场动画打开；
  它**也带 dock**，四项都不高亮（`Dock.dim`）。
- **二级页的 dock 是"回容器"而不是"切页"**（2026-09-27 修）：二级页里没有 `SwipePager`，
  所以走 `Dock.attachSecondary(activity)` —— 点第 i 个 tab = 带 `PagerActivity.EXTRA_PAGE=i`
  + `FLAG_ACTIVITY_REORDER_TO_FRONT` 回到容器并 `finish()` 自己（复用已有实例，返回栈不堆）。
  容器侧在 `onNewIntent` 里接住页码并调 `pager.setCurrentItem(target)`（走回调而不是直接改
  状态，这样 `onHide`/`onShow`/高亮三件事照常发生）。
  教训：只调 `Dock.dim()` **不会**绑点击 —— `view_dock.xml` 的 tab 本身就是 `clickable`，
  没监听器时表现为"点得动但没反应"，很容易误判成别的问题。
- **分页容器 `ui/SwipePager.kt`**：`HorizontalScrollView` 子类 —— 跟手拖动 + 松手 snap
  （先看甩动速度 ≥ `scaledMinimumFlingVelocity`，否则看位移是否过半；都不满足就回弹）。
  `onMeasure` 里把每个页面拉成与容器等宽，否则 scrollX 与页序号对不上；
  `ACTION_UP` **不要交回父类**，否则 ScrollView 会再触发一次 fling 滚到两页之间。
  没用 ViewPager 是因为构建链没有 AndroidX。
- **`InnerScrollView` 只在垂直意图明确时才锁父容器**（`|dy| > |dx|`）—— 无条件锁会让
  "在日志/输出区上左右滑" 失效（2026-09-27 修）。
- dock 布局 `res/layout/view_dock.xml`（容器与页面都 include 同一份）；`ui/Dock.kt` 只管
  点按切页（`attach`）与高亮（`highlight` / `dim`）—— 由容器在 `onPageChanged` 里调
  `highlight`，**别让两处都去写 `pager.onPageChanged`**（会互相顶掉）。
- `Insets.apply(activity, topBars: List<View>, bottom: View?)`：顶栏是**一组**（四个页面
  各有一个，都要让出状态栏），dock 让出导航栏。`Theme.apply(this)` 必须在 `super.onCreate` 之前。
- **换主题色板会 `recreate()` 整个容器** → `PagerActivity` 用 `savedInstanceState` 记住当前
  页序号，重建后仍停在原页。
- **加一个 tab 的步骤**：① 新建 `page_x.xml` + `Page` 子类；② `activity_pager.xml` 的横向条
  里加 `<include>`（顺序 = dock 顺序）；③ `PagerActivity` 的 `pages` 列表加一项；
  ④ `view_dock.xml` 加一项 + `Dock.ITEMS` 加一行 + 加图标与字符串。

## 4. 文案与状态

- **顶栏约定**：不放副标题（版本号在大状态卡右上角）；结论由**大状态卡**承载 ——
  一句话（`工作正常` / `临时 root 就绪` / `需要激活 Shizuku` / `未就绪`，24sp 加粗 + 状态色）
  + 一行下一步提示，由 `MainActivity.updateVerdict()` 计算。改文案先看 `strings.xml` 的 `verdict_*`。
- **日志只经 `core/LogBus`**：主页与日志页是**同一份**缓冲（切页面不丢历史）。
  不要在页面里自建 `StringBuilder`；`LogBus` 的回调已在主线程，页面在 `onStart/onStop`
  里订阅/退订即可。

## 5. 主题体系

主题色一律走 `?attr/ksu*`（`attrs.xml` 里 7 个：Primary / PrimaryDim / OnPrimary /
PrimaryContainer / TopbarBg / TopbarBgAlt / TopbarMarkBg），**不要**在 layout/drawable 里写
`@color/seed_*` 这类具体色值 —— 色值只出现在 `values/themes.xml`（浅色）与
`values-night/themes.xml`（深色）的 6 套色板 style 里。

**加一套色板的步骤**：① 两个 themes.xml 各加一个 `Theme.KSUOneTap.<Name>`；
② `core/Theme.kt` 的 `SEEDS` 加一项（顺序必须与设置页 `dotItem0..5` / `dotColor0..5` 一致）；
③ `strings.xml` 加色板名。色点颜色由 `Theme.primaryOf()` 从 style 读 `ksuPrimary`，
**不要在 Kotlin 里再写一份色值**。深浅模式走 `UiModeManager.setApplicationNightMode`
（API 31+，更低版本只能跟随系统）。

**顶栏的硬约束**：任何色板 + 任何深浅下都必须是「深底 + 浅字」。`bg_topbar` 的渐变只能用
`ksuTopbarBgAlt → ksuTopbarBg`，**别改用 `ksuPrimary` / `ksuPrimaryDim`** —— 深色模式下那两个
是浅色值，会变成浅底配浅字（2026-09-27 修过一次）。垫白色品牌图形的底色同理走 `ksuTopbarBg` 系。

**品牌图形**：`res/drawable/ic_brand.xml` = 从启动图标裁出的主体，viewport **直接贴包围盒**
（60×54，别沿用 108/72 画布，否则图形在 46dp 圆标里又小又偏）；用于主页顶栏 `ivMark`
与关于页品牌头。

**图标**：`ic_launcher_foreground.xml` = 安卓机器人（头 + 身 + 天线 + 手臂）**举着金色钥匙**，
背景沿用紫粉渐变。改图标注意安全区（内容收在**中心半径 ~33 的圆**内），否则会被 OEM
遮罩裁掉；并排两个主体时宽度预算很紧（圆心高度处可用宽 66，越往上越窄）。

## 6. 部署链路的约定

- **适配档位（tier）= 一个版本到底能做多少事**。`assets/<系统版本>/SYSTEM.txt` 的
  `tier=` 是单一来源，`DeviceGate` 的两个清单与之对应，`scripts/check-assets-sync.sh`
  三处互校（不一致会直接报错）：
  - `tier=full` → `ADAPTED_BUILDS`：4 件绑定产物齐备（exploit + 3 个内核模块），
    可以一路走到 KernelSU
  - `tier=lpe-su` → `LPE_ONLY_BUILDS`：**只有 exploit**。这类版本上 `run()` 只推
    `ASSETS_ROOT_ONLY`、拿到临时 root 就收尾（日志明确写「本版本仅 LPE/su」），
    `activateKsu()` 会**直接拒绝**并说明原因 —— 因为内核模块与内核二进制绑定
    （vermagic + `struct module` 布局 + `KSU_VERSION`），拿别的版本的模块去
    `insmod` 不是"失败"而是"加载失败或内核 panic"。
  - 若只想问"能不能部署"，看 `DeviceGate.probe(...).isTargetBuild`；
    `fullySupported` 还额外要求 `!isLpeOnly`（即 KSU 那套也能用）。
  - **当前两版（15.1.14.7 / 14.0.17.2）都是 `full`**，所以 `LPE_ONLY_BUILDS` 是空的 ——
    这个档位保留给"先只做 LPE、内核模块留待后续"的新版本（14.0.17.2 就先经历过这一档，
    2026-09-28 补完 3 个模块后升到 `full`）。
- **「临时 root」通道走 exploit 内置的 `su`**（既不是老 rootd 文件队列，也不用 `u0`）：
  `$DEV/su` 是指向 exploit 自身的符号链接，命令经 `$DEV/su.sock` 交给服务端，
  服务端 `setuid(0)` 后 `sh -c` —— 所以 `su -c 'id'` 直接 `uid=0(root)`。
  **前提**：`assets/<系统版本>/exploit_vivo_neo9` 必须是 `-DCHEESE_SU_DEFAULT=1` 版本
  （= `neo9-root/exploit/out/<系统版本>/exploit_vivo_neo9_stable_su_ndk13`，App 启动时另传 `CHEESE_SU=1`）。
  没有内置 su 的那份老 assets 里 `su` 根本不会存在。
- **「仅提取 root」模式只推 exploit**（`DeployScript.ASSETS_ROOT_ONLY`），并清掉
  `u0` 与 KSU 那套资产（`ASSETS_FULL` 才是 8 个）；该模式刻意不带 `u0`。
- **启动 exploit 前必须先 `killall -9 exploit_vivo_neo9`**：新版带并发防护，
  旧实例活着会直接拒起（日志 "another instance is active"）。
- **提权客户端的选取规则（统一）**：KSU 在线 → `su_ksu`；只有临时 root → 内置 `su`。
  凡"以 root 执行设备侧命令"的地方都按这条选（终端通道、清理并重启）。
  `su_ksu` 是 KSU 的客户端，**KSU 未加载时不存在** —— 写死它会在仅提取 root 模式下失效。
  例外：「软重启」要 `ksud`，所以只有 KSU 在线才能点（单独门控，别和"有 root"混用）。
- **模块加载一律经 `ksud insmod`**（用户明确的约束）：`kernelsu-vivo.ko` 走 rootd 域
  `u0 ksud insmod ... allow_shell=1`；`unpatch.ko` / `vrpatch.ko` 走
  `su_ksu -c 'ksud insmod ...'`。裸 `insmod` 不做 ksud 的 UAPI 校验与初始化，不能用。
- **「激活 KSU」**：临时 root 在手但 KSU 未激活时，主界面第一槽位把「软重启」换成它
  （`MainActivity.updateActionButtons()` 里二选一），点它走 `Deployer.activateKsu()` ——
  `run()` 的 `[4]~[6]` 已抽成 `finishKsuDeploy()` 供两处共用。队列不在时用
  `--su-server` 现补（该路径不碰漏洞/GPU，不受实例锁限制）。
- **`getenforce` 是 Enforcing** —— 别按代码注释里的旧结论（permissive）推断跨域可行性；
  需要跨 SELinux 域的能力，先 `getenforce` 确认。
- **临时 root 的 SELinux 域固定用 `u:r:shell:s0`**（`DeployScript.U0_CTX`）——
  **不要**用 exploit 默认的 `u:r:vrp:s0`：vrp 是 vivo 自定义域，权限只够跑裸命令
  （`service list` → `Found 0 services`、`cmd package` → `Can't find service`），
  所有依赖系统服务的工具（Axeron/AxManager 等）都起不来。`u:r:kernel:s0` 也不行。
  启动时传 `CHEESE_U0_CTX=${DeployScript.U0_CTX}`，终端通道执行前还会再 `setcon` 一次兜底
  （这样设备上跑旧实例时也能用）。代价：放弃 vr.ko 白名单
  （`euid==0 && sid != vrp` 会命中，但本机实测未被击杀）。

## 7. 系统版本号的取法（红线，2026-09-28 踩过）

**判定设备系统版本 / 决定用哪一版绑定产物，只能走
`DeviceGate.resolveBuildDir()` —— 不许用 `Build.DISPLAY`。**

原因：`Build.DISPLAY`（= `ro.build.display.id`）的语义在两代 OriginOS 上不同：

| 系统 | `ro.build.display.id` | 真软件版本号 |
|---|---|---|
| OriginOS 5（Android 15） | `PD2338_A_15.1.14.7.W10.V000L1` | 同左（**巧合**相同，所以早期代码是"对的"） |
| OriginOS 4（Android 14） | `UP1A.231005.007 release-keys` | `PD2338_A_14.0.17.2.W10.V000L1`（`ro.vivo.default.version`） |

后果：OriginOS 4 的机器被判成"未适配（版本不同）"，明明 assets 里有它的适配产物。
而且 `Build` 的**公开字段里没有**软件版本号（`ID` / `FINGERPRINT` / `VERSION.INCREMENTAL`
都不含），`/system/build.prop` 又是 `0600 root` —— 所以只能：

1. `core/SysProps.kt` 反射 `android.os.SystemProperties.get()`，按
   `DeviceGate.SOFTWARE_VERSION_PROPS` 依次读（`ro.vivo.default.version` /
   `ro.build.version.bbk` / `ro.vivo.product.version` / …）
2. 取不到才退化到 shell `getprop`（需 Shizuku）
3. 再取不到才用 `Build.DISPLAY` 兜底，并在界面上注明"是按兜底判定的"

**归一化成版本核再比**：`versionCore()` 把 `PD2338C_A_14.0.17.2.W10.V000L1`、
`PD2338_A_14.0.17.2.W10`、`14.0.17.2.W10` 都算成 `14.0.17.2.W10`。
直接整串相等**永远匹配不上**（机型后缀 `C`、批次后缀 `V000L1` 在不同 prop / 不同界面上写法不一）。
没有 `.W<数字>` 的串（`UP1A.231005.007 release-keys`、`os.version=14.1`）核为空，不会误命中。

**取件路径必须用解析出来的目录名**：`resolveBuildDir()` 返回空串时调用方**必须拒绝继续**，
绝不能退回 `Build.DISPLAY` 去拼路径 —— 拼错不是"缺文件"，是把别的版本的偏移打进内核。

**UI 侧的版本清单不要写死在 `strings.xml`**：关于页那两处改为从
`DeviceGate.ADAPTED_BUILDS` / `ALL_ADAPTED_BUILDS` 现取 —— 加新版本时忘了改 strings
就会出现"App 说只支持 A，实际 B 也支持"的假信息（v1.0.6 加 14.0.17.2 时就真出现了）。

## 8. KSU 的 su 入口必须**解析**，不能只认随包的 `su_ksu`（红线，2026-09-28 真机踩过）

原先"KSU 通道是否可用"是判单一文件：

```kotlin
Shell.run("$DEV/su_ksu -c id").contains("uid=0")   // ← 错
```

`su_ksu` 是**随包**的（App 部署时推到 `/data/local/tmp`）。但本项目有**两条**通往 KSU 的路：

| 路径 | 设备上真正的 su 入口 |
|---|---|
| App 一键部署（`ksud insmod kernelsu-vivo.ko`） | `/data/local/tmp/su_ksu`（App 推的） |
| **手工装 KSU**（`adb root` + `su -c "ksud insmod …"`） | **`/system/bin/su`**（KernelSU 自己装的，5.6 MB，与 ksud 同源） |

第二条路上 `su_ksu` 根本不存在 ⇒ 判定失败 ⇒ 主页出现 **「KernelSU 已加载」+「Root 无 root」**
的矛盾状态，并把「软重启」「清理并重启」一起**置灰**（`updateActionButtons()` 依赖 `ksuAvailable`）。
实测那台设备：`.allowlist` 里已有 `com.neoroot.ksuonetap` 与 `moe.shizuku.privileged.api`，
`/system/bin/su -c id` → `uid=0` —— **root 客观上可用，是判定的口径太窄**。

**约定**：KSU 的 su 入口一律走 `Terminal.ksuEntry()`，按优先级**真执行一次 `id`** 依次探：

```
① $DEV/su_ksu（随包，优先）  ② /system/bin/su  ③ /data/adb/ksu/bin/su  ④ su
```

- **只看文件在不在不算数** —— KSU 会拒绝未授权的调用者，必须真跑出 `uid=0`
- 探不到**不缓存**（KSU 可能还没加载完）；探到了缓存，但 `ksuRun()` 输出里见到
  `inaccessible` / `not found` / `No such file` 就 `invalidateKsu()` 重探
- **所有** KSU 通道的调用都走 `Terminal.ksuRun(cmd, timeout)`（终端、主页 root 判定、
  软重启、清理并重启、`waitKsuSu()`、部署流程里的 `ksud insmod` / `pm install`）——
  不要再手写 `Shell.run("… su_ksu -c …")`
- 唯一例外：「仅提取 root」模式清残留时 `rm -f` 的 `su_ksu` 文件名
  （`DeployScript.ASSETS_*` / `cleanupRebootCommand()`）—— 那是**要删的文件名**，不是入口
- 顺带：终端的通道概览行现在会把入口名打出来（`KSU 可用（su）`），排查时一眼看出走的哪条

## 9. 日志与进度（2026-09-28 改版）

**主页不再显示运行日志** —— 这是刻意的：主页只放「状态 + 进度 + 操作」，
日志全部归日志页（那里能按日期翻历史、能导出）。原先"同一个 `LogBus` 同时喂主页和日志页"
的写法删掉了，主页也不再订阅 `LogBus`。

### 9.1 日志落盘（`core/LogStore.kt`，`LogBus` 逐行转发）

- 位置：`<filesDir>/logs/<yyyy-MM-dd>.txt`（App 私有目录，**零权限**）
- **每行 `write` + `flush`**：`FileOutputStream` 没有用户态缓冲，写一次就是一次 `write(2)`
  —— 崩溃 / 被系统杀，最多丢"正在写的那一行"
- **按日期直接写**，不做"临时文件 + 收尾归档"：跨天、进程重启、崩溃恢复都不需要额外动作，
  那个文件本身就是"实时落盘"的那一份；换天由 `append` 逐行判断
- `LogBus.append` 统一加 `[HH:mm:ss]` 前缀，**内存那份与落盘那份完全一致**
- `LogBus.clear()` = 清内存 **＋ truncate 当天文件**（只清内存不够：日志页能从文件读回来，
  看起来像"没清掉"）；其他日期的历史保留
- 初始化放在 `App : Application`（不是某个 Activity）—— 日志要在任何界面进来之前就能落盘，
  否则从内置终端直接启动时（PagerActivity 没跑过）那一轮日志只在内存里

### 9.2 日志页（`ui/page/LogPage.kt`）

- 同一块显示区两个数据源：**实时**（`LogBus.text()`）/ **历史**（`LogStore.read(day)`，弹窗按日期选）
- 底部操作栏 4 个：**复制 / 导出 / 历史 / 清空**。
  放底栏而不是顶栏 —— 顶栏塞 4 个两字按钮会超出 1080px（标题 22sp + 4×(14dp×2+2字) ≈ 1092px）
- **导出走 `Intent.ACTION_SEND` 分享**：不需要存储权限、不需要 FileProvider，
  用户在分享面板里自己选"保存到文件 / 发到别处"
- 清空是"清**当前显示**的那一份"：实时 → `LogBus.clear()`；历史 → `LogStore.truncate(day)`

### 9.3 进度（`core/Progress.kt`，`Deployer` 各阶段上报）

- **阶段式**，不是平滑进度：spray 那段时长完全不可控（40 s ~ 9 分钟），给不出连续百分比
- `State(pct, label, running, failed)`；主页 `renderProgress()` 的三种表现：
  - `running` → 显示（转圈 + `45%` + 阶段名）
  - `failed` → **定格显示**（百分比停在失败那一刻，label 变"失败：原因"）—— 卡在哪一步一眼可见
  - 其余 → 整行隐藏（结论由上方状态卡表达）
- 阶段点写在 `Deployer` 旁边：推送按件数 `12→30%` / 等漏洞 `35→62%`（每 10 s 爬一点）/
  加载驱动 `70` / 确认模块 `76` / 等 su `82` / unpatch+vrpatch `88` / 校验 root `94` /
  装 Manager `97` → `finish()` 100%
- 每个失败分支都配 `Progress.fail(原因)`（Shizuku 未激活 / 授权失败 / 系统版本未适配 /
  漏洞未命中 / 驱动加载失败 / su 不可用 / root 校验失败 …）

### 9.4 按钮文案

主按钮统一叫 **「一键 Root」**（原「一键提权」/「一键提取 Root」两种叫法合并）——
两种模式（完整 / 仅提取）靠按钮下方的提示与日志区分，不再靠按钮名。
设置页里的模式名「一键提取模式」保留（它描述的是模式，不是按钮）。
