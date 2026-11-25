# Madrid2flip（小米 18 背屏移植模块）

面向 **Xiaomi MIX Flip 2 (`bixi`, HyperOS 4 / Android 17)** 的独立 Xposed 模块，将小米 18 (`madrid`) 背屏中心（`com.xiaomi.subscreencenter`）移植到 MIX Flip 2 外屏运行。

> **前置依赖（配套系统资源模块）**
> 仅安装 LSPosed 模块无法点亮小米 18 背屏，请先在 KernelSU / Magisk 中刷入配套的小米 18 背屏系统资源模块（`madrid_subscreen_port_v1.0.0.zip`，用于挂载 `/product/app/subscreencenter`、`/product/media/rearscreen`、`/product/etc/precust_theme/theme/.data`），并授予本应用 Root 权限。安装包与配套资源模块均可在 [Releases 页面](https://github.com/Aragamiz/Madrid2flip/releases) 直接下载；本仓库源码仅包含 Xposed 适配模块本身。

## 核心特性（v2.0.0）

- **多场景自由唤起与退出**
  - **指纹键轻拍两下**：支持展开态、半展开态以及合盖解锁进外屏桌面后轻拍两下侧边指纹键快速开关小米 18 背屏（支持系统快捷手势原生菜单注入与开机防重置持久化）；
  - **展开 / 半折桌钟 / 弯折支架模式**：支持展开自动开启、半折（`~90°`）自动开启、控制中心「小米18背屏」快捷磁贴；新增 **弯折支架模式**（半展开时自动熄灭内屏、只亮背屏当桌面支架展示壁纸，展开/合盖/关闭开关立即恢复内屏）；
  - **多种退出方式**：合盖背屏支持「直接息屏直达 AOD（默认，零闪屏）」「回原厂锁屏」「回外屏桌面」三档退出，双击毛玻璃空白区亦可快速退出。
- **显示、视口与动效适配**
  - **双摄对齐半屏 ↔ 原厂全屏视口无缝切换**：默认按 MIX Flip 2 双摄挖孔精准对齐 `1208×738` 背屏视口，下半屏可选纯黑 OLED 熄灭或实时 `PixelCopy` 采样毛玻璃延展背板（支持首帧磁盘缓存与 `TransitionDrawable` 交叉淡化）；开启「背屏全屏模式（原厂视口）」可满铺 `1208×1392` 展示自定义照片/视频壁纸，切换自动重载并带平滑淡入淡出动画；
  - **翻转 / 弯折双摄避让三档可选**：支持重力 `0°/180°` 倒置旋转与半折自动固定摄像头朝下、内容朝上，提供「贴左避让」「居中满幅」「关闭翻转」三档避让方式；
  - **防误触与屏幕保护**：内置 **背屏触摸锁定**（展开态拦截外屏触摸且在 WMS 层阻止抢占焦点，打字时不收起内屏输入法，弯折支架模式下自动放行触摸）、三档防烧屏位移（原厂 AOD / 平衡 / 积极大幅）、无操作超时自动息屏、后置接近传感器原始流（`33171124`）平放桌面自动息屏。
- **官方背屏个性化增强（主题商店）**
  - **半屏 / 全屏双分区与真实比例预览**：官方「相册轮播」「壁纸与签名」拆分为 **半屏**（`1208×738` 预览裁切）与 **全屏**（`1208×1392` 预览裁切）两组独立模块，选图调图所见即所得；
  - **解锁视频与 Live 实况图 + 四档播放模式**：解锁官方相册选择器直接选择图片、视频与 Live 动图，模块内提供「循环」「点击重播」「亮屏一次」「仅播一次」四档播放模式；
  - **原厂体验修复**：独立后置光感自动亮度（5 档精细倍率 + 亮度上限保护）、解锁 AI 应用卡社区广场与 Lumi 语音对话、自动隐藏 Flip 2 硬件不支持的背屏息屏显示入口、官方新手引导左滑相机步骤支持轻点直接跳过。

## 安装与使用步骤

1. 在 **KernelSU / Magisk** 中刷入 `madrid_subscreen_port_v1.0.0.zip` 并重启手机；
2. 安装本模块 APK（`io.github.aragamiz.madrid2flip_v2.0.0.apk`），在 **LSPosed** 中启用并勾选下方推荐作用域；
3. 在 **KernelSU** 超级用户列表中授予「Madrid(小米18)背屏移植flip2」Root 权限，重启一次手机使 `system_server` Hook 生效；
4. 打开模块 App（顶部横幅显示绿色/黄色就绪状态，缺少 KSU 模块或 Root 权限时会自动弹窗引导），按需开启快捷手势与显示选项；在「系统设置 → 个性背屏」即可自定义半屏/全屏相册轮播、签名、萌宠与 AI 应用卡。

## 推荐作用域（LSPosed）

- `系统框架 (android)`
- `com.xiaomi.subscreencenter`
- `com.android.settings`
- `com.android.thememanager`
- `com.miui.securitycore`
- `com.miui.fliphome`
- `com.android.systemui`

## 构建说明

1. 复制 `keystore.properties.example` 为 `keystore.properties` 并填入你的签名配置（或通过 `FLIPCLOCK_KS` / `FLIPCLOCK_KS_PASS` / `FLIPCLOCK_KEY_ALIAS` / `FLIPCLOCK_KEY_PASS` 环境变量传入）。
2. 运行 `powershell -ExecutionPolicy Bypass -File build.ps1` 即可在 `release/<versionCode>-<versionName>/` 下生成带签名的 Release APK。

## 致谢

- 感谢 [@yutouyes](https://github.com/yutouyes) 贡献的背屏防误触锁定、视频壁纸循环、全屏原厂视口、弯折支架模式与后置接近原始流传感器（`33171124`）适配思路与代码。
