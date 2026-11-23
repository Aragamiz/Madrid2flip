# Madrid2flip（小米 18 背屏移植模块）

面向 **Xiaomi MIX Flip 2 (`bixi`, HyperOS 4 / Android 17)** 的独立 Xposed 模块，将小米 18 (`madrid`) 背屏中心（`com.xiaomi.subscreencenter`）移植到 MIX Flip 2 外屏运行。

> **前置依赖**：需配合提取自小米 18 OS4 固件的配套资源模块（挂载 `/product/app/subscreencenter`、`/product/media/rearscreen`、`/product/etc/precust_theme/theme/.data`），本仓库仅包含开源 Xposed 适配模块源码与 Release APK，不包含小米原厂闭源资源。

## 核心特性

- **多场景自由唤起**
  - **合盖轻拍指纹键**：解锁后在外屏桌面拍两下指纹键全屏拉起小米 18 背屏（支持开机防重置持久化、系统快捷手势原生菜单注入）；
  - **展开 / 半折桌钟模式**：支持展开自动开启、半折（`~90°`）自动开启、轻敲背面（两下/三下）与控制中心快捷磁贴切换；
  - **多种退出方式**：支持直接息屏直达 AOD（默认，零闪屏）、回原厂锁屏、回外屏桌面三档，双击毛玻璃空白区亦可退出。
- **显示与动效适配**
  - 按 MIX Flip 2 双摄挖孔精准对齐 `1208×738` 背屏视口，下半屏实时 `PixelCopy` 采样生成 GPU 静态管线毛玻璃延展背板 + `TransitionDrawable` 交叉淡化 + 磁盘首帧缓存；
  - 拦截 `StartingWindow` 消除开启白屏，整窗 `alpha` 平滑淡入，合盖模式下自动隐藏外屏桌面小组件与状态栏/导航栏；
  - 支持重力 `0°/180°` 自动旋转、防烧屏微移、无操作自动息屏、倒扣桌面自动息屏、左滑直达外屏自拍相机。
- **独立背屏亮度与 AI 应用卡**
  - 接管外屏独立后置环境光传感器，支持 5 档自动亮度精细倍率与最高亮度上限保护；
  - 解锁主题商店「背屏设置」入口、AI 应用卡社区广场列表、详情页比例修复与一键下载安装，支持 Lumi 语音对话桥接。

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
2. 运行 `powershell -ExecutionPolicy Bypass -File build.ps1` 即可生成带签名的 Release APK。
