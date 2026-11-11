# PhanthyMotus Watch7 Companion (Wear OS)

此工程从原来的 Heart Rate Wear Test 演进而来，现为 **Watch7 Driver 的配套手表 APK**。请勿把 Android 工程当作公司 MCP Card：部署于 Linux/机器人的是 `phanthymotus-driver/samsung/watch7`，APK 运行在手表上。

## S4 构建变体（2026-11-07）

| 变体 | applicationId | versionName | 主要功能 |
| --- | --- | --- | --- |
| **Standard** | `com.phanthymotus.watch7` | `0.2.0-standard` | Health Services 心率、IMU、公开传感器、v1/v2 设备通信、设备状态 |
| **Research** | `com.example.heartrateweartest` | `0.2.0-research` | Standard 公共代码 + 本人点击启动的 Samsung Health Sensor SDK 研究测量（PPG/ECG/BIA/SpO₂/温度等，受设备和权限限制） |

两个变体均为 `versionCode=2`，均可同时安装（包名不同），但不应同时占用同一个光学/健康传感器。Research 沿用旧包名以保护已有研究版私有存储。能否**覆盖升级**旧研究 APK 还取决于签名证书是否相同，不能自动删除原应用。

三星相关的 `SamsungTrackerBridge.java`、`SamsungMeasurementActivity.kt`、专有权限及 Activity 注册全部位于 `app/src/research`。公开版不编译、打包三星 SDK，不需要三星专有权限。研究版通过 `researchImplementation` 加载单独提供的 AAR；**不要将三星 AAR、测量数据或私有签名密钥上传公司 PR**。

## 构建前置条件

- JDK 17（当前代码在 Windows / Android Gradle Plugin 8.13 / Kotlin 2.2.20 下构建）
- Android SDK 36，Android SDK Build Tools 35/36
- Gradle 8.13：包含 `gradlew.bat` 和 `gradle/wrapper`；若首次在新电脑执行需要下载 Gradle 和 Maven 依赖
- Python 3.10+（可选，方便校验和制作开发包）

`build_s4.py` 优先使用已有 `.tools/gradle/gradle-8.13`，否则使用项目 `gradlew.bat`；SDK 优先读取 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，否则使用现有 `.tools/android-sdk`。

在本工程根目录执行：

```powershell
# Standard：必须不依赖三星 AAR，适合公司 Driver 配套
python build_s4.py --variant standard --offline --no-samsung-sdk

# Research：需要开发者自行获得且有权使用的官方 SDK
python build_s4.py --variant research --offline

# 检查两套 APK 的真实包名、版本、权限、字节码、签名和校验和
python scripts/verify_s4_apks.py

# 制作仅供可信测试使用的 Standard 开发安装包
python scripts/package_s4_dev.py
```

若在**另一台干净电脑**构建且依赖尚未缓存，移除 `--offline`，首次会从官方软件仓库下载 Gradle/Android 依赖。Research 默认寻找：

`../research/watch-capability-probe/app/libs/samsung-health-sensor-api.aar`

也可通过环境变量 `SAMSUNG_SDK_AAR` 或 Gradle 属性 `-PsamsungSdkAar=<ABSOLUTE_PATH>` 显式指定官方 AAR。普通版的 `--no-samsung-sdk` 会主动指定不存在的 AAR，验证二者隔离。

## APK 与公司 Driver 的协议契约

- **Watch7 APK** 是设备端执行程序，**公司 Driver** 是机器人/Linux 上的标准 Adapter。
- 当前标准 Driver（首个 PR）MCP 为 **127.0.0.1:15750**（仅 Agent Core 内部可访问）；**手表连接地址应为 `http://<Driver所在Linux主机局域网IP>:15751`**，不是 MCP 端口。
- 在手表“连接设置”中填写 Driver 地址和与服务器一致的设备密钥。密钥不得出现在 Git、文档、APK、Solution 或日志。
- 设备协议支持 **v1**（心率上报）和 **v2**（设备状态/传感器摘要）；`DeviceStateRepository` 在 v2 心跳中发送 `app_version`。
- v2 数据上报默认关闭，须由使用者在手表前台明确开启。新 Driver 第一版只发布 `heart_rate`、`device_state` 两张 Sensor Card。
- v1/v2 协议兼容不能替代功能兼容：远程振动、任务授权、TTS、机器人动作和高级健康能力**尚未在首个公司 Driver PR 启用**。设备收到空命令队列是预期行为。
- 高级波形只存放手表私有目录，不通过基础 v1/v2 接口自动传输。

## 安装和升级注意事项

在佩戴者同意并打开调试的情况下，选择对应 APK 安装：

```powershell
adb devices
adb install -r app\build\outputs\apk\standard\debug\app-standard-debug.apk

# 研究版（仅自己有权使用 SDK 时）
adb install -r app\build\outputs\apk\research\debug\app-research-debug.apk
```

本 S4 阶段未自动连接、唤醒或覆盖手表。若 Research 原包与新的调试签名不同，Android 可能报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。**不要为了安装成功就卸载旧研究版**，否则本机的研究数据可能消失；先导出/备份再制定升级策略。

## 安全、隐私与发布

目前设备通信沿用研究期 **Wi-Fi HTTP + token**。这种方式只能用于受控可信的实验局域网，**不能直接暴露到公网或不可信网络**；生产或企业推广需要 TLS、配对认证、密钥轮换及安全评审。

本阶段的 `assemble*Debug` APK 使用**调试签名**；`apksigner verify` 通过不等于正式发布签名。正式分发必须另外准备公司认可的 release keystore、签名/更新机制和审核。标准版即使不包含 Samsung AAR，也不能直接把 Debug APK 当生产发布版。

Research 内含 SDK 二进制，按三星授权协议使用；未经许可不应公开分发。ECG/BIA 只能由本人在手表上明确触发；不是医疗诊断设备。

## S4 验收状态

2026-11-07，本地实际完成以下检查：

- Standard 离线编译成功（刻意指定缺失的 Samsung AAR）
- Research 使用本地合法持有的 SDK AAR 离线编译成功
- `scripts/verify_s4_apks.py` 通过：包名、版本、权限、SDK 字节码隔离、v1/v2 代码、APK 完整性与 v2 签名
- 生成 `S4_APK_VERIFICATION.json`（含 SHA-256）

**尚未完成：** 新 APK 在真实 Watch7 上安装及测量回归；TLS/正式 release 签名；S5 ARM64 Driver 真机集成与 Dashboard 验收。因此 S4 可判定为**开发版构建/打包完成**，不能判定为**可上架/公司发布版完成**。

## R17 独立源码归档（2026-11-11）

本工程为 **Android Companion 独立 Git 源码仓库**，不得以 Driver 目录中的
`docs/research-source-v3/` 源码快照替代完整 Android 构建工程。

本次对应的 Driver **精确代码提交**（不是随时会变动的分支名）：

`andersonmichaelmzfsp5294-jpg/phanthymotus-driver@666d48552cb36b6526e8090ff5fd9a52c24344f8`

详见 `compatibility/watch7-r17.json`。本地源码归档及 Git bundle 的
Android commit、Driver commit 和 SHA-256 保存在相邻
`../archive/watch7-r17-source-20261111/ARCHIVE_PROVENANCE.json`。
此文件在归档完成后生成，不是手工猜测的提交号。

独立构建过程：

```powershell
# Windows 11；JDK 17，Android SDK 36，Gradle Wrapper 8.13。
# 无需任何三星 SDK；适合复现普通 Watch7 数据采集应用。
python -B build_s4.py --variant standard --no-samsung-sdk

# Research：必须首先从合法授权来源单独提供 Samsung Health Sensor AAR。
# 可设置 SAMSUNG_SDK_AAR 环境变量，或放到约定的外部路径。
python -B build_s4.py --variant research

# 没有联网时可以加 --offline；新机器第一次构建需要下载依赖。
# 执行不会安装到 Watch7。
python -B scripts/release_archive.py audit
python -B scripts/release_archive.py create
python -B scripts/release_archive.py verify
```

复现版本时，先将本仓库检出到 `ARCHIVE_PROVENANCE.json` 所列
`companion_commit`，再将 Driver 独立检出到所列 `driver_commit`。
两者使用相同 `release_id` 和协议集合 `v1/v2/v3`，不要以
`latest`、`main` 或未经绑定的镜像标签替代。

**归档安全边界：**源码仓库不保存 Samsung SDK AAR、APK、真实健康数据、
录音、位置、Wi-Fi/Driver 配对密钥、签名证书或缓存。Research APK
必须由具备 SDK 授权者自行编译，不以本源码归档代替 SDK 分发权利。
Research 构建会校验 `compatibility/watch7-r17.json` 锁定的 Samsung AAR SHA-256，
避免更换 SDK 版本后误以为构建的是同一版本。

**验收边界：**17 张 Card 是软件源码及协议登记数量；截至本归档，
未证明全部 17 张 Card 真实手表采集、网络、Linux Agent Core 和
Bumi 端到端验收通过。

