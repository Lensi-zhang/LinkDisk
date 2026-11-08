# LinkDisk

LinkDisk 是一款原生 Kotlin Android WebDAV 文件共享应用，可通过局域网或官方 FRP 隧道分享手机中的文档、照片和下载内容。

**当前版本：1.0** · **最低系统：Android 7.0（API 24）**

## 下载

[从 GitHub Releases 下载 LinkDisk 1.0 APK](https://github.com/Lensi-zhang/LinkDisk/releases/download/v1.0/LinkDisk-1.0.apk)

SHA-256：`F1D952F3D50C1C74D5F88CB44873026A18067075A6DF077D8ABBB6DF90EE5629`

安装前请核对以上校验值。Android 可能需要在系统设置中允许当前浏览器或文件管理器安装未知来源应用。

## 功能

- 使用系统存储访问框架（SAF）选择共享目录，无需存储权限。
- 提供 WebDAV `OPTIONS`、`PROPFIND`、`GET`、`HEAD`、`PUT`、`DELETE`、`MKCOL`、`COPY`、`MOVE`、`LOCK` 和 `UNLOCK` 支持。
- 默认启用用户名和密码认证，公网访问要求至少 16 位密码。
- 支持粘贴或导入官方 `frpc` TOML 配置及旧版 INI 配置。
- 自动识别 HTTP、HTTPS、TCP 隧道、本地端口和公网地址。
- 单候选自动应用，多候选由用户显式选择，并同步 WebDAV 端口。
- 提供“测试并启动公网”，启动后通过带认证的 `OPTIONS`/`DAV` 探测确认可用性。
- 原始 FRP 配置保持不变并加密保存，运行时交由官方 `libfrpc.so -c` 执行。
- 前台服务、失败退避重连及停止后的进程清理。

## 使用

### 局域网共享

1. 打开 LinkDisk，选择要共享的目录。
2. 设置端口和认证信息。默认用户名为 `admin`，首次运行会生成随机密码。
3. 启动服务，在与手机同一局域网的电脑中访问界面显示的地址。
4. 可使用支持 WebDAV 的文件管理器，或 Windows“映射网络驱动器”。

### 公网共享

1. 准备与当前设备兼容的官方 `frpc` TOML 或旧版 INI 配置。
2. 点击“粘贴配置”或“导入文件”。文件必须为 UTF-8，大小不超过 256 KiB。
3. 如果识别出多个隧道，点击“选择隧道”；确认本地端口和公网地址已同步。
4. 点击“测试并启动公网”，等待界面显示“公网 WebDAV 已通过认证探测”。
5. 高级用户可展开“高级配置”直接编辑原始配置和公网 URL。

LinkDisk 不接入 CHMLFRP 私有 API，也不修改原始 FRP 配置。用户需要自行准备可用的 `frps` 服务和兼容的官方配置。

## 安全说明

- 公网访问必须设置非空用户名和至少 16 位密码。
- **HTTP 不加密传输内容或 Basic 认证凭据，公网使用时应优先选择 HTTPS。** 仅在可信网络中使用 HTTP。
- WebDAV 密码和 FRP 原始配置使用 Android Keystore 保护的 AES-GCM 加密保存。
- 配置识别错误、应用状态和日志不应包含 Token、密码或整段配置。
- 分享目录即授予客户端对该目录内容的读写能力；请仅选择需要分享的目录，并在使用后停止服务。
- 从其他来源安装 APK 前，请验证签名和哈希，避免安装被篡改的包。

## 从源码构建

环境要求：JDK 17、Android SDK 34。

```powershell
# 单元测试与 lint
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain

# 安装调试版本到已连接设备
.\gradlew.bat :app:installDebug --console=plain

# 构建发布版本
.\gradlew.bat :app:assembleRelease --console=plain
```

未配置签名时，`assembleRelease` 生成未签名 APK。正式构建可在本机被 Git 忽略的 `local.properties` 中配置：

```properties
linkdiskStoreFile=C:/path/to/private-release.jks
linkdiskStorePassword=replace-with-local-secret
linkdiskKeyAlias=linkdisk
linkdiskKeyPassword=replace-with-local-secret
```

签名密钥和密码不得提交到仓库。丢失发布密钥将导致无法以同一签名发布后续升级。

## 项目结构

- `MainActivity.kt`：配置向导、状态展示及用户交互。
- `WebDavServer.kt` / `WebDavService.kt`：WebDAV 服务、认证、公网探测及生命周期。
- `FrpcConfigInspector.kt`：只读 TOML/INI 隧道识别。
- `FrpcProcess.kt` / `TunnelConfig.kt`：原始配置校验、脱敏及官方原生进程管理。
- `SecretStore.kt`：基于 Android Keystore 的本地加密存储。

## 第三方组件

- AndroidX、Material Components
- Kotlin
- NanoHTTPD
- 官方 FRP 原生客户端

各组件遵循其原有许可证。LinkDisk 与 CHMLFRP 或 FRP 官方没有隶属或背书关系。
